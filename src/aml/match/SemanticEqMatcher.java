/******************************************************************************
* Copyright 2013-2016 LASIGE                                                  *
*                                                                             *
* Licensed under the Apache License, Version 2.0 (the "License"); you may     *
* not use this file except in compliance with the License. You may obtain a   *
* copy of the License at http://www.apache.org/licenses/LICENSE-2.0           *
*                                                                             *
* Unless required by applicable law or agreed to in writing, software         *
* distributed under the License is distributed on an "AS IS" BASIS,           *
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    *
* See the License for the specific language governing permissions and         *
* limitations under the License.                                              *
*                                                                             *
*******************************************************************************
* Matches Ontologies by measuring the word similarity between their classes,  *
* using a weighted Jaccard index.                                             *
*                                                                             *
* @author Daniel Faria                                                        *
******************************************************************************/
package aml.match;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import aml.AML;
import aml.ontology.RelationshipMap;
import aml.settings.EntityType;
import aml.settings.SemanticSimilarity;
import aml.util.Table2Set;

public class SemanticEqMatcher implements SecondaryMatcher {
	// Attributes
	// Semantic Similarity measures, way of weighting and type of computing
	// final score
	public static SemanticSimilarity semanticMeasure;
	public static SemanticSimilarity IC;
	public static SemanticSimilarity weighting;
	public static SemanticSimilarity finalScore;

	// Strategy and radius
	private static SemanticSimilarity strat;
	private static int radius;

	private static final String DESCRIPTION = "Matches entities by computing the maximum\n"
			+ "Semantic similarity between the entries, using " + IC + "'s IC \n" + "and " + semanticMeasure
			+ "'s semantic similarity measure. Using " + strat + " strategy\n" + "and radius " + radius;
	private static final String NAME = "String Matcher";
	private static final EntityType[] SUPPORT = { EntityType.CLASS, EntityType.INDIVIDUAL, EntityType.DATA,
			EntityType.OBJECT };
	// Links to the AML class, relationshipMap, the source and target maximum
	// number
	// of classes
	private AML aml;
	private RelationshipMap rels;
	private int sClasses;
	private int tClasses;
	// Alignments needed as input the one to be extended and the one with lower
	// threshold
	private Alignment input;
	private Alignment alowerTreshold;

	// The available CPU threads
	private int threads;

	// Constructors
	// By default strategy Maximum
	public SemanticEqMatcher() {
		aml = AML.getInstance();
		rels = aml.getRelationshipMap();
		threads = Runtime.getRuntime().availableProcessors();
		radius = 3;
		IC = SemanticSimilarity.icSECO;
		semanticMeasure = SemanticSimilarity.RESNIK;
		strat = SemanticSimilarity.MAXIMUM;
		weighting = SemanticSimilarity.WeightTCONORM;
		finalScore = SemanticSimilarity.FinalAVERAGE;
	}

	// radius and arguments in the order: IC, semantic measure, strategy,
	// weighting, final scoring.
	public SemanticEqMatcher(Alignment aLowerThresh, int rad, SemanticSimilarity... args) {
		this();

		radius = rad;
		alowerTreshold = aLowerThresh;
		if (args.length == 5)
			finalScore = args[4];
		if (args.length >= 4)
			weighting = args[3];
		if (args.length >= 3)
			strat = args[2];
		if (args.length >= 2)
			semanticMeasure = args[1];
		if (args.length >= 1)
			IC = args[0];

	}

	// arguments in the order: IC, semantic measure, strategy,
	// weighting, final scoring.
	public SemanticEqMatcher(Alignment aLowerThresh, SemanticSimilarity... args) {
		this();
		alowerTreshold = aLowerThresh;
		if (args.length == 5)
			finalScore = args[4];
		if (args.length >= 4)
			weighting = args[3];
		if (args.length >= 3)
			strat = args[2];
		if (args.length >= 2)
			semanticMeasure = args[1];
		if (args.length >= 1)
			IC = args[0];
	}
	// Public Methods

	@Override
	public Alignment extendAlignment(Alignment a, EntityType e, double thresh) throws UnsupportedEntityTypeException {
		checkEntityType(e);
		System.out.println("Extending Alignment with Semantic Similarity Matcher");
		long time = System.currentTimeMillis() / 1000;

		input = a;
		// get maximum number of entities from that type to compute IC
		tClasses = aml.getTarget().count(e);
		sClasses = aml.getSource().count(e);

		Table2Set<Integer, Integer> toMap = new Table2Set<Integer, Integer>();
		for (Mapping m : a) {

			if (!aml.getURIMap().isClass(m.getSourceId()))
				continue;
			// Lists of possible pairs to be mapped
			List<Integer> sourceSubClasses = new ArrayList<Integer>();
			List<Integer> targetSubClasses = new ArrayList<Integer>();
			List<Integer> sourceSuperClasses = new ArrayList<Integer>();
			List<Integer> targetSuperClasses = new ArrayList<Integer>();

			addAllInRadius(radius, m.getSourceId(), m.getTargetId(), sourceSuperClasses, targetSuperClasses,
					sourceSubClasses, targetSubClasses);

			for (Integer s : sourceSubClasses) {
				if (input.containsSource(s))
					continue;
				for (Integer t : targetSubClasses) {
					if (input.containsTarget(t))
						continue;
					toMap.add(s, t);
				}
			}

			for (Integer s : sourceSuperClasses) {
				if (input.containsSource(s))
					continue;
				for (Integer t : targetSuperClasses) {
					if (input.containsTarget(t))
						continue;
					toMap.add(s, t);
				}
			}
		}
		Alignment maps = mapInParallel(toMap, thresh);
		time = System.currentTimeMillis() / 1000 - time;
		System.out.println("Finished in " + time + " seconds");

		return maps;
	}

	@Override
	public String getDescription() {
		return DESCRIPTION;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public EntityType[] getSupportedEntityTypes() {
		return SUPPORT;
	}

	// Private Methods

	private void checkEntityType(EntityType e) throws UnsupportedEntityTypeException {
		boolean check = false;
		for (EntityType t : SUPPORT) {
			if (t.equals(e)) {
				check = true;
				break;
			}
		}
		if (!check)
			throw new UnsupportedEntityTypeException(e.toString());
	}

	// Maps a table of classes in parallel, using all available threads
	private Alignment mapInParallel(Table2Set<Integer, Integer> toMap, double thresh) {
		Alignment maps = new Alignment();
		ArrayList<MappingTask> tasks = new ArrayList<MappingTask>();
		for (Integer i : toMap.keySet())
			for (Integer j : toMap.get(i))
				tasks.add(new MappingTask(i, j));
		List<Future<Mapping>> results;
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		try {
			results = exec.invokeAll(tasks);
		} catch (InterruptedException e) {
			e.printStackTrace();
			results = new ArrayList<Future<Mapping>>();
		}
		exec.shutdown();
		for (Future<Mapping> fm : results) {
			try {
				Mapping m = fm.get();
				if (m.getSimilarity() >= thresh)
					maps.add(m);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return maps;
	}

	// Computes the semantic similarity between two terms by
	// checking for mappings between all their ancestors and descendants
	private double mapTwoTerms(int sId, int tId) {
		// similarity for the pair sId and tId from the lower threshold
		// alignment
		double lowerThreshSimilarity = alowerTreshold.getSimilarity(sId, tId);
		if (lowerThreshSimilarity > 0) {

			// Lists with the neighborhood of sId an tId to compute the
			// surrounding semantic similarities
			List<Integer> sourceSubClasses = new ArrayList<Integer>();
			List<Integer> targetSubClasses = new ArrayList<Integer>();
			List<Integer> sourceSuperClasses = new ArrayList<Integer>();
			List<Integer> targetSuperClasses = new ArrayList<Integer>();

			addAllInRadius(radius, sId, tId, sourceSuperClasses, targetSuperClasses, sourceSubClasses,
					targetSubClasses);

			// final semantic similarity scores for the ancestors (finAnc) and
			// the
			// descendants (finDesc)
			double finalAnc = 0;
			double finalDesc = 0;

			if (!strat.equals(SemanticSimilarity.DESCENDANTS)) {

				finalAnc = semanticSim( lowerThreshSimilarity, sId, tId, sourceSuperClasses,
						targetSuperClasses, true);

			}

			if (!strat.equals(SemanticSimilarity.ANCESTORS)) {

				finalDesc = semanticSim( lowerThreshSimilarity, sId, tId, sourceSubClasses,
						targetSubClasses, false);

			}

			if (strat.equals(SemanticSimilarity.ANCESTORS))
				return finalAnc;
			else if (strat.equals(SemanticSimilarity.DESCENDANTS))
				return finalDesc;
			else if (strat.equals(SemanticSimilarity.MINIMUM))
				return Math.min(finalAnc, finalDesc);
			else if (strat.equals(SemanticSimilarity.MAXIMUM))
				return Math.max(finalAnc, finalDesc);
			else
				return (finalAnc + finalDesc) * 0.5;
		}
		return 0;
	}

	private void addAllInRadius(int radius, int sId, int tId, List<Integer> sourceSuperClasses,
			List<Integer> targetSuperClasses, List<Integer> sourceSubClasses, List<Integer> targetSubClasses) {

		for (int r = 1; r <= radius; r++) {
			sourceSuperClasses.addAll(rels.getAncestors(sId, r));
			targetSuperClasses.addAll(rels.getAncestors(tId, r));

			sourceSubClasses.addAll(rels.getDescendants(sId, r));
			targetSubClasses.addAll(rels.getDescendants(tId, r));
		}

	}

	private double semanticSim( double similarityLowerThresh, int sId, int tId,
			List<Integer> sourceList, List<Integer> targetList, boolean getAncestorsTrue) {
		List<Double> parentOrChildSimilarity = new ArrayList<Double>();
		int numberMappings = 0;
		int minim = Math.min(sourceList.size(), targetList.size());

		for (Integer s : sourceList) {
			if (getAncestorsTrue) {// i e j sao pais

				double semanticSimSource = semanticSimilarity(sClasses, s, sId);

				for (Integer t : targetList) {
					double semanticSimTarget = semanticSimilarity(tClasses, t, tId);
					// !! Tconorm!!! weightSimilarity
					double semSim = weightSimilarity(similarityLowerThresh / minim, semanticSimSource,
							semanticSimTarget);
					if (semSim > 0) {

						parentOrChildSimilarity.add(semSim);
						numberMappings += 1;
					}

				}

			} else {// i e j sao pais

				double semanticSimSource = semanticSimilarity(sClasses, sId, s);

				for (Integer t : targetList) {
					double semanticSimTarget = semanticSimilarity(tClasses, tId, t);
					// !! Tconorm!!! weightSimilarity
					double semSim = weightSimilarity(alowerTreshold.getSimilarity(s, t) / minim, semanticSimSource,
							semanticSimTarget);
					if (semSim > 0) {

						parentOrChildSimilarity.add(semSim);
						numberMappings += 1;
					}

				}

			}
		}
		double semSimContribuition = 0;
		if (parentOrChildSimilarity.size() > 0) {
			for (double s : parentOrChildSimilarity.subList(0, parentOrChildSimilarity.size())) {
				semSimContribuition = s + semSimContribuition;
			}

			double finalSimilarity = 0;

			if (finalScore.equals(SemanticSimilarity.FinalTCONORM))
				finalSimilarity = tconorm(semSimContribuition / numberMappings, similarityLowerThresh);
			else if (finalScore.equals(SemanticSimilarity.FinalAVERAGE))
				finalSimilarity = (semSimContribuition / numberMappings + similarityLowerThresh) / 2;
			//

			return finalSimilarity;

		}

		return similarityLowerThresh;
	}

	public double semanticSimilarity(int maxLeaves, int classIdAncestor, int classIdDescendant) {

		if (semanticMeasure.equals(SemanticSimilarity.RESNIK)) {
			if (IC.equals(SemanticSimilarity.icSECO))
				return semMeasureResnikSeco(maxLeaves, classIdAncestor);
			else if (semanticMeasure.equals(SemanticSimilarity.RESNIK))
				return semMeasureResnikResnik(maxLeaves, classIdAncestor);
			return 0;
		} else {

			if (IC.equals(SemanticSimilarity.icSECO)) {
				if (semanticMeasure.equals(SemanticSimilarity.LIN))
					return semMeasureLinSeco(maxLeaves, classIdAncestor, classIdDescendant);
				else if (semanticMeasure.equals(SemanticSimilarity.JC))
					return semMeasureJianConSeco(maxLeaves, classIdAncestor, classIdDescendant);

			} else if (IC.equals(SemanticSimilarity.icRESNIK)) {
				if (semanticMeasure.equals(SemanticSimilarity.LIN))
					return semMeasureLinResnik(maxLeaves, classIdAncestor, classIdDescendant);
				else if (semanticMeasure.equals(SemanticSimilarity.JC))
					return semMeasureJianConResnik(maxLeaves, classIdAncestor, classIdDescendant);

			}
			return 0;

		}
	}

	public double icResnik(int classId, int maxLeaves) {
		int allDescendants = rels.subClassCount(classId, false) + 1;
		double freqC = allDescendants / maxLeaves;
		double ic = -Math.log(freqC); // IC
										// normalizado
		// System.out.println("ic"+ic);

		return ic;
	}

	public double icSeco(int classId, int maxLeaves) {
		int allDescendants = rels.getDescendants(classId).size() + 1;

		double ic = (double) (Math.log(allDescendants) / Math.log(maxLeaves)); // IC
																				// normalizado
		// System.out.println("ic"+ic);

		return (1 - ic);
	}

	private double semMeasureResnikSeco(int maxLeaves, int classIdAncestor) {
		// nao faz sentido pq o mica vai ser o pai...

		double simRes = icSeco(classIdAncestor, maxLeaves);
		return simRes;
	}

	private double semMeasureLinSeco(int maxLeaves, int classIdAncestor, int classIdDescendant) {
		// nao faz sentido pq o mica vai ser o pai...
		double simLin = (2 * icSeco(classIdAncestor, maxLeaves))
				/ (icSeco(classIdDescendant, maxLeaves) + icSeco(classIdAncestor, maxLeaves));

		return simLin;
	}

	private double semMeasureJianConSeco(int maxLeaves, int classIdAncestor, int classIdDescendant) {
		// nao faz sentido pq o mica vai ser o pai...

		double dJianCon = icSeco(classIdDescendant, maxLeaves) + icSeco(classIdAncestor, maxLeaves)
				- 2 * icSeco(classIdAncestor, maxLeaves);
		double simJianCon = 1 / (dJianCon + 1); // 1 - (dJianCon / 2);// or =

		return simJianCon;
	}

	private double semMeasureResnikResnik(int maxLeaves, int classIdAncestor) {
		// nao faz sentido pq o mica vai ser o pai...

		double simRes = icResnik(classIdAncestor, maxLeaves);
		return simRes;
	}

	private double semMeasureLinResnik(int maxLeaves, int classIdAncestor, int classIdDescendant) {
		// nao faz sentido pq o mica vai ser o pai...
		double simLin = (2 * icResnik(classIdAncestor, maxLeaves))
				/ (icResnik(classIdDescendant, maxLeaves) + icResnik(classIdAncestor, maxLeaves));

		return simLin;
	}

	private double semMeasureJianConResnik(int maxLeaves, int classIdAncestor, int classIdDescendant) {
		// nao faz sentido pq o mica vai ser o pai...

		double dJianCon = icResnik(classIdDescendant, maxLeaves) + icResnik(classIdAncestor, maxLeaves)
				- 2 * icResnik(classIdAncestor, maxLeaves);
		double simJianCon = 1 / (dJianCon + 1); // 1 - (dJianCon / 2);// or =

		return simJianCon;
	}

	public double weightSimilarity(double initialSimilarity, double semanticSimilaritySource,
			double semanticSimilarityTarget) {

		if (weighting.equals(SemanticSimilarity.WeightTCONORM)) {
			return weightSimilarityTConorm(initialSimilarity, semanticSimilaritySource, semanticSimilarityTarget);
		} else if (weighting.equals(SemanticSimilarity.WeightAVERAGE)) {
			return weightSimilarityAverage(initialSimilarity, semanticSimilaritySource, semanticSimilarityTarget);
		}
		return 0;
	}

	public double weightSimilarityAverage(double initialSimilarity, double semanticSimilaritySource,
			double semanticSimilarityTarget) {
		double weightedSimilarity = ((initialSimilarity / 2) * semanticSimilaritySource)
				+ ((initialSimilarity / 2) * semanticSimilarityTarget);

		return weightedSimilarity;
	}

	public double weightSimilarityTConorm(double initialSimilarity, double semanticSimilaritySource,
			double semanticSimilarityTarget) {
		double weightedSimilarity = tconorm(semanticSimilaritySource, semanticSimilarityTarget);

		return weightedSimilarity * initialSimilarity;
	}

	private double tconorm(double x, double y) {
		return (x + y - x * y);
	}

	// Callable class for mapping two classes
	private class MappingTask implements Callable<Mapping> {
		private int source;
		private int target;

		MappingTask(int s, int t) {
			source = s;
			target = t;
		}

		@Override
		public Mapping call() {
			return new Mapping(source, target, mapTwoTerms(source, target));
		}
	}

}