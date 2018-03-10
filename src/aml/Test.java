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
* Test-runs AgreementMakerLight in Eclipse.                                   *
*                                                                             *
* @author Daniel Faria                                                        *
******************************************************************************/
package aml;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import aml.filter.Repairer;
import aml.filter.Selector;
import aml.match.Alignment;
import aml.match.LexicalMatcher;
import aml.match.SemanticEqMatcher;
import aml.match.StringMatcherNoWeight;
import aml.settings.EntityType;
import aml.settings.LanguageSetting;
import aml.settings.SelectionType;
import aml.settings.SemanticSimilarity;
public class Test {

	// Main Method

	public static void main(String[] args) throws Exception {
		// Path to input ontology files (edit manually)
		String sourcePath = "store/anatomy/mouse.owl";
		String targetPath = "store/anatomy/mouse.owl";
		String referencePath = "store/anatomy/mouse.owl";
		// Path to save output alignment (edit manually, or leave blank for no
		// evaluation)
		String outputPath = "store/anatomy/mouse.owl";

		AML aml = AML.getInstance();
		aml.openOntologies(sourcePath, targetPath);

		double lowerThreshold = 0.4;
		double selectorThreshold = 0.6;
		double threshold = 0.6;

		int radius = 3;

		SemanticSimilarity IC = SemanticSimilarity.icSECO;
		SemanticSimilarity semanticMeasure = SemanticSimilarity.RESNIK;
		SemanticSimilarity strat = SemanticSimilarity.MAXIMUM;
		SemanticSimilarity weighting = SemanticSimilarity.WeightAVERAGE;
		SemanticSimilarity finalScore = SemanticSimilarity.FinalAVERAGE;
		
		Alignment a = new Alignment();
		Alignment aLower = new Alignment();
		
		LanguageSetting lang = LanguageSetting.getLanguageSetting();
		if(lang.equals(LanguageSetting.TRANSLATE))
		{
			aml.translateOntologies();
    		lang = LanguageSetting.getLanguageSetting();
		}

		LexicalMatcher lm = new LexicalMatcher();
		a.addAll(lm.match(EntityType.CLASS, threshold));
		aLower.addAll(lm.match(EntityType.CLASS, lowerThreshold));

//		StringMatcher sm = new StringMatcher();
		StringMatcherNoWeight sm = new StringMatcherNoWeight();
		a.addAll(sm.extendAlignment(a, EntityType.CLASS, threshold));
		aLower.addAll(sm.extendAlignment(aLower, EntityType.CLASS, lowerThreshold));

		aml.setAlignment(a);

		SelectionType sType = aml.getSelectionType();
		Selector s = new Selector(selectorThreshold, sType);
		s.filter();
		
		Repairer r = new Repairer();
		r.filter();
		
		

		a = aml.getAlignment();

		SemanticEqMatcher ssm = new SemanticEqMatcher(aLower, radius, IC, semanticMeasure, strat, weighting,
				finalScore);
		Alignment newSSM = ssm.extendAlignment(a, EntityType.CLASS, threshold);
//		for (Mapping m: newSSM) {
//			System.out.println(m);
//			
//		}
		a.addAll(newSSM);
		
		aml.setAlignment(a);
		
		s.filter();
		r.filter();
		
		ArrayList<String> filenameAndEvaluation = new ArrayList<String>();

		if (!referencePath.equals("")) {
			aml.openReferenceAlignment(referencePath);
			aml.getReferenceAlignment();
			aml.evaluate();
			System.out.println(aml.getEvaluation());
			filenameAndEvaluation.add(outputPath + ";" + aml.getEvaluation());
		}
		if (!outputPath.equals(""))
			aml.saveAlignmentRDF(outputPath + ".rdf");

		saveTxt(outputPath, filenameAndEvaluation);
	}
	
	public static void saveTxt(String filename, ArrayList<String> filenameAndEvaluation) throws FileNotFoundException {
		PrintWriter txtFile = new PrintWriter(new FileOutputStream(filename + ".csv"));
		for (String line : filenameAndEvaluation) {
			txtFile.println(line);
		}
		txtFile.close();
	}
}