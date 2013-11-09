package gear.profile;

import gear.CommandArguments;
import gear.CommandImpl;
import gear.ConstValues;
import gear.family.pedigree.file.SNP;
import gear.util.BufferedReader;
import gear.util.FileUtil;
import gear.util.Logger;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;

public final class ProfileCommandImpl extends CommandImpl
{
	@Override
	public void execute(CommandArguments cmdArgs)
	{
		profCmdArgs = (ProfileCommandArguments)cmdArgs;
		
		printWhichCoeffModelIsUsed();
		
		ScoreFile scoreFile = new ScoreFile(profCmdArgs.getScoreFile(), profCmdArgs.getHasScoreHeader());

		HashMap<String, Float> qScoreMap = readQScores();  // LocusName-to-QScore map
		QRange[] qRanges = readQRanges();
		
		Data genoData = initData();
		SNP[] snps = genoData.getSNPs();

		FilteredSNPs filteredSNPs = new FilteredSNPs(snps, scoreFile, qScoreMap, qRanges, profCmdArgs);

		printSNPFilterResult(scoreFile, qRanges, filteredSNPs);

		ArrayList<String> famIDs = new ArrayList<String>();
		ArrayList<String> indIDs = new ArrayList<String>();
		ArrayList<float[][]> riskProfiles = new ArrayList<float[][]>();
		ArrayList<int[][]> numLociUsed = new ArrayList<int[][]>();
		
		for (int traitIdx = 0; traitIdx < scoreFile.getNumberOfTraits(); ++traitIdx)
		{
			Data.Iterator iter = genoData.iterator();
			while (iter.next())
			{
				int indIdx = iter.getIndividualIndex();
				int locIdx = iter.getLocusIndex();
				
				while (indIdx >= famIDs.size())
				{
					famIDs.add(null);
				}
				famIDs.set(indIdx, iter.getFamilyID());
				
				while (indIdx >= indIDs.size())
				{
					indIDs.add(null);
				}
				indIDs.set(indIdx, iter.getIndividualID());
				
				while (indIdx >= riskProfiles.size())
				{
					riskProfiles.add(new float [scoreFile.getNumberOfTraits()][filteredSNPs.getNumLocusGroups()]);
				}
				
				while (indIdx >= numLociUsed.size())
				{
					numLociUsed.add(new int [scoreFile.getNumberOfTraits()][filteredSNPs.getNumLocusGroups()]);
				}
				
				if (filteredSNPs.getScore(traitIdx, locIdx) == null)
				{
					continue;
				}
				
				float scoreAlleleFrac = 0.0f;
				switch (filteredSNPs.getAlleleMatchScheme(locIdx))
				{
				case MATCH_ALLELE1:
				case MATCH_ALLELE1_FLIPPED:
					scoreAlleleFrac = iter.getAllele1Fraction();
					break;
				case MATCH_ALLELE2:
				case MATCH_ALLELE2_FLIPPED:
					scoreAlleleFrac = 2.0f - iter.getAllele1Fraction();
					break;
				default:
					continue;
				}
				
				float riskValue = profCmdArgs.getCoeffModel().compute(scoreAlleleFrac) * filteredSNPs.getScore(traitIdx, locIdx);
				
				for (int locGrpIdx = 0; locGrpIdx < filteredSNPs.getNumLocusGroups(); ++locGrpIdx)
				{
					if (filteredSNPs.isInLocusGroup(locIdx, locGrpIdx))
					{
						riskProfiles.get(indIdx)[traitIdx][locGrpIdx] += riskValue;
						++numLociUsed.get(indIdx)[traitIdx][locGrpIdx];
					}
				}
			}  // while data
		}  // for each trait

		if (profCmdArgs.getIsWeighted())
		{
			for (int indIdx = 0; indIdx < riskProfiles.size(); ++indIdx)
			{
				for (int traitIdx = 0; traitIdx < scoreFile.getNumberOfTraits(); ++traitIdx)
				{
					for (int locGrpIdx = 0; locGrpIdx < filteredSNPs.getNumLocusGroups(); ++locGrpIdx)
					{
						int denom = numLociUsed.get(indIdx)[traitIdx][locGrpIdx];
						if (denom != 0)
						{
							riskProfiles.get(indIdx)[traitIdx][locGrpIdx] /= profCmdArgs.getIsSameAsPlink() ? (denom << 1) : denom;
						}
					}
				}
			}
		}
		
		// Write result to file
		for (int locGrpIdx = 0; locGrpIdx < filteredSNPs.getNumLocusGroups(); ++locGrpIdx)
		{
			String fileName = profCmdArgs.getResultFile();
			if (qRanges != null)
			{
				fileName += ".q." + qRanges[locGrpIdx].getName();
			}
			fileName += ".profile";
			PrintStream predictorFile = FileUtil.CreatePrintStream(fileName);
			
			// Title Line
			predictorFile.print("FID\tIID");
			for (int traitIdx = 0; traitIdx < scoreFile.getNumberOfTraits(); ++traitIdx)
			{
				predictorFile.print("\tSCORE." + scoreFile.getTrait(traitIdx));
			}
			predictorFile.println();
			
			for (int indIdx = 0; indIdx < riskProfiles.size(); indIdx++)
			{
				predictorFile.print(famIDs.get(indIdx) + "\t" + indIDs.get(indIdx));
				for (int traitIdx = 0; traitIdx < scoreFile.getNumberOfTraits(); ++traitIdx)
				{
					predictorFile.print("\t" + riskProfiles.get(indIdx)[traitIdx][locGrpIdx]);
				}
				predictorFile.println();
			}
			predictorFile.close();
		}
	}

	private void printSNPFilterResult(ScoreFile scoreFile, QRange[] qRanges, FilteredSNPs filteredSNPs)
	{
		Logger.printUserLog("Number of monomorphic loci (removed): " + filteredSNPs.getNumMonoLoci());
		Logger.printUserLog("Number of ambiguous loci (A/T or C/G) " + (profCmdArgs.getIsKeepATGC() ? "detected: " : "removed: ") + filteredSNPs.getNumAmbiguousLoci());

		// Allele Matching Schemes
		Logger.printUserLog("Number of Scheme I predictors: predictor alleles were A1: " + filteredSNPs.getMatchNum(AlleleMatchScheme.MATCH_ALLELE1));
		Logger.printUserLog("Number of Scheme II predictors: predictor alleles were A2: " + filteredSNPs.getMatchNum(AlleleMatchScheme.MATCH_ALLELE2));
		if (profCmdArgs.getIsAutoFlip())
		{
			Logger.printUserLog("Number of Scheme III predictors: predictor alleles were flipped A1: " + filteredSNPs.getMatchNum(AlleleMatchScheme.MATCH_ALLELE1_FLIPPED));
			Logger.printUserLog("Number of Scheme IV predictors: predictor alleles were flipped A2: " + filteredSNPs.getMatchNum(AlleleMatchScheme.MATCH_ALLELE2_FLIPPED));
		}
		Logger.printUserLog("Number of score alleles matching none in the data file: " + filteredSNPs.getMatchNum(AlleleMatchScheme.MATCH_NONE));

		if (profCmdArgs.getQScoreFile() != null)
		{
			Logger.printUserLog("Number of loci having no q-scores: " + filteredSNPs.getNumLociNoQScore());
			for (int i = 0; i < filteredSNPs.getNumLocusGroups(); i++)
			{
				Logger.printUserLog("Number of loci within the range: " + qRanges[i].getLowerBound() + ", " + qRanges[i].getUpperBound() + " is " + filteredSNPs.getNumInLocusGroup(i));
			}
		}
		
		for (int traitIdx = 0; traitIdx < scoreFile.getNumberOfTraits(); ++traitIdx)
		{
			Logger.printUserLog("Number of loci having no score for trait " + scoreFile.getTrait(traitIdx) + ": " + filteredSNPs.getNumLociNoScore(traitIdx));
		}
	}
	
	private void printWhichCoeffModelIsUsed()
	{
		if (profCmdArgs.getIsSameAsPlink())
		{
			Logger.printUserLog("PLINK allelic model is used.");
		}
		else if (profCmdArgs.getCoeffModel() instanceof AdditiveCoeffModel)
		{
			Logger.printUserLog("Additive model is used.");
		}
		else if (profCmdArgs.getCoeffModel() instanceof DominanceCoeffModel)
		{
			Logger.printUserLog("Dominance model is used.");
		}
		else
		{
			Logger.printUserLog("Recessive model is used.");
		}
	}

	private HashMap<String, Float> readQScores()
	{
		String qScoreFile = profCmdArgs.getQScoreFile();
		if (qScoreFile == null)
		{
			return null;
		}
		
		HashMap<String, Float> qScores = new HashMap<String, Float>();

		BufferedReader reader = BufferedReader.openTextFile(qScoreFile, "q-score");
		String[] tokens;
		
		while ((tokens = reader.readTokens(2)) != null)
		{
			if (!ConstValues.isNA(tokens[1]))
			{
				try
				{
					qScores.put(/* locusName = */ tokens[0], Float.parseFloat(tokens[1]));
				}
				catch (NumberFormatException e)
				{
					reader.errorPreviousLine("'" + tokens[1] + "' is not a valid q-score.");
				}
			}
		}
		reader.close();

		Logger.printUserLog("Number of q-scores: " + qScores.size());
		
		return qScores;
	}

	private QRange[] readQRanges()
	{
		String qRangeFile = profCmdArgs.getQRangeFile();
		if (qRangeFile == null)
		{
			return null;
		}
		
		ArrayList<QRange> qRanges = new ArrayList<QRange>();

		BufferedReader reader = BufferedReader.openTextFile(qRangeFile, "q-score-range");
		String[] tokens;
		
		while ((tokens = reader.readTokens(3)) != null)
		{
			try
			{
				float lowerBound, upperBound;
				lowerBound = ConstValues.isNA(tokens[1]) ? Float.MIN_VALUE : Float.parseFloat(tokens[1]);
				upperBound = ConstValues.isNA(tokens[2]) ? Float.MAX_VALUE : Float.parseFloat(tokens[2]);
				
				for (QRange qRange : qRanges)
				{
					if (qRange.getName().equals(tokens[0]))
					{
						Logger.printUserError("QRange '" + qRange.getName() + "' appears more than once in the qrange file '" + profCmdArgs.getQRangeFile() + "'.");
						System.exit(1);
					}
				}
				
				qRanges.add(new QRange(/* name = */tokens[0], lowerBound, upperBound));
			}
			catch (NumberFormatException e)
			{
				reader.errorPreviousLine("An invalid q-score value is detected.");
			}
		}
		reader.close();

		Logger.printUserLog("Number of q-ranges: " + qRanges.size());

		return qRanges.toArray(new QRange[0]);
	}
	
	private Data initData()
	{
		if (profCmdArgs.getFile() != null)
		{
			return PlinkData.createByFile(profCmdArgs.getFile());
		}
		else if (profCmdArgs.getBFile() != null)
		{
			return PlinkData.createByBFile(profCmdArgs.getBFile());
		}
		return MachData.create(profCmdArgs.getMachDosageFile(),
		                       profCmdArgs.getMachInfoFile(),
		                       profCmdArgs.getMachDosageBatch(),
		                       profCmdArgs.getMachInfoBatch());
	}

	private ProfileCommandArguments profCmdArgs;
}
