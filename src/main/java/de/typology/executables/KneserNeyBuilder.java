package de.typology.executables;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.typology.counting.AbsoluteCounter;
import de.typology.counting.ContinuationCounter;
import de.typology.indexing.WordIndex;
import de.typology.indexing.WordIndexer;
import de.typology.patterns.PatternBuilder;
import de.typology.smoothing.KneserNeySmoother;
import de.typology.smoothing.ModifiedKneserNeySmoother;
import de.typology.splitting.DataSetSplitter;
import de.typology.testing.TestSequenceExtractor;
import de.typology.utils.Config;

public class KneserNeyBuilder {

    private static Logger logger = LogManager.getLogger();

    private Config config;

    private KneserNeyBuilder(
            String[] args) throws IOException, InterruptedException {
        config = Config.get();
        loadStages(args);

        Path workingDirectory =
                Paths.get(config.outputDirectory + config.inputDataSet);
        Path trainingFile = workingDirectory.resolve("training.txt");
        Path indexFile = workingDirectory.resolve("index.txt");
        Path absoluteDirectory = workingDirectory.resolve("absolute");
        Path continuationDirectory = workingDirectory.resolve("continuation");
        Path testExtractOutputDirectory =
                workingDirectory.resolve("testing-samples");

        if (config.splitData) {
            logger.info("split data");
            splitData(workingDirectory);
        }

        if (config.buildIndex) {
            logger.info("build word index");
            buildIndex(trainingFile, indexFile);
        }

        if (config.buildGLM) {
            logger.info("split into GLM sequences");
            buildGLM(trainingFile, indexFile, absoluteDirectory);
        }

        if (config.buildContinuationGLM) {
            logger.info("split into continuation sequences");
            buildContinuationGLM(trainingFile, indexFile, absoluteDirectory,
                    continuationDirectory);
        }

        if (config.extractContinuationGLM) {
            logger.info("extract continuation sequences");
            extractContinuationGLM(workingDirectory, indexFile,
                    absoluteDirectory, continuationDirectory,
                    testExtractOutputDirectory);

        }

        if (config.buildKneserNey) {
            logger.info("build kneser ney");
            KneserNeySmoother kns =
                    buildKneserNey(workingDirectory, absoluteDirectory,
                            continuationDirectory, testExtractOutputDirectory);

            if (config.buildModKneserNey) {
                logger.info("build modified kneser ney");
                buildModKneserNey(workingDirectory, absoluteDirectory,
                        continuationDirectory, testExtractOutputDirectory,
                        kns.absoluteTypeSequenceValueMap,
                        kns.continuationTypeSequenceValueMap);
            }
        }

        logger.info("done");
    }

    private void loadStages(String[] stages) {
        if (stages.length == 0) {
            return;
        }

        config.splitData = false;
        config.buildIndex = false;
        config.buildGLM = false;
        config.buildContinuationGLM = false;
        config.extractContinuationGLM = false;
        config.buildKneserNey = false;
        config.buildModKneserNey = false;

        if (stages.length == 1) {
            if (stages[0].equals("none")) {
                return;
            } else if (stages[0].equals("all")) {
                config.splitData = true;
                config.buildIndex = true;
                config.buildGLM = true;
                config.buildContinuationGLM = true;
                config.extractContinuationGLM = true;
                config.buildKneserNey = true;
                config.buildModKneserNey = true;
                return;
            }
        }

        for (String stage : stages) {
            switch (stage) {
                case "split":
                    config.splitData = true;
                    break;

                case "index":
                    config.buildIndex = true;
                    break;

                case "glm":
                    config.buildGLM = true;
                    break;

                case "contglm":
                    config.buildContinuationGLM = true;
                    break;

                case "extract":
                    config.extractContinuationGLM = true;
                    break;

                case "kneserney":
                    config.buildKneserNey = true;
                    break;

                case "modkneserney":
                    config.buildModKneserNey = true;
                    break;

                default:
                    throw new IllegalArgumentException("Unkown stage: " + stage);
            }
        }
    }

    private void splitData(Path workingDirectory) throws IOException {
        DataSetSplitter dss =
                new DataSetSplitter(workingDirectory.toFile(), "normalized.txt");
        dss.split("training.txt", "learning.txt", "testing.txt");
        dss.splitIntoSequences(
                workingDirectory.resolve("testing.txt").toFile(),
                config.modelLength, config.numberOfQueries);
    }

    private void buildIndex(Path trainingFile, Path indexFile)
            throws IOException {
        InputStream input = Files.newInputStream(trainingFile);
        OutputStream output = Files.newOutputStream(indexFile);
        WordIndexer wordIndexer = new WordIndexer();
        wordIndexer.buildIndex(input, output, config.maxCountDivider,
                "<fs> <s> ", " </s>");
    }

    private void buildGLM(
            Path trainingFile,
            Path indexFile,
            Path absoluteDirectory) throws IOException, InterruptedException {
        List<boolean[]> glmForSmoothingPatterns =
                PatternBuilder
                        .getReverseGLMForSmoothingPatterns(config.modelLength);
        WordIndex wordIndex = new WordIndex(Files.newInputStream(indexFile));
        AbsoluteCounter absolteSplitter =
                new AbsoluteCounter(trainingFile, absoluteDirectory, wordIndex,
                        "\t", "<fs> <s> ", " </s>", config.numberOfCores,
                        config.deleteTempFiles);
        absolteSplitter.split(glmForSmoothingPatterns);
    }

    private void buildContinuationGLM(
            Path trainingFile,
            Path indexFile,
            Path absoluteDirectory,
            Path continuationDirectory) throws IOException,
            InterruptedException {
        List<boolean[]> lmPatterns =
                PatternBuilder.getReverseLMPatterns(config.modelLength);
        WordIndex wordIndex = new WordIndex(Files.newInputStream(indexFile));
        ContinuationCounter smoothingSplitter =
                new ContinuationCounter(absoluteDirectory,
                        continuationDirectory, wordIndex, "\t",
                        config.numberOfCores, config.deleteTempFiles);
        smoothingSplitter.split(lmPatterns);
    }

    private void extractContinuationGLM(
            Path workingDirectory,
            Path indexFile,
            Path absoluteDirectory,
            Path continuationDirectory,
            Path testExtractOutputDirectory) throws IOException {
        Files.createDirectory(testExtractOutputDirectory);

        Path testSequences =
                workingDirectory.resolve("testing-samples-"
                        + config.modelLength + ".txt");

        TestSequenceExtractor tse =
                new TestSequenceExtractor(testSequences.toFile(),
                        absoluteDirectory.toFile(),
                        continuationDirectory.toFile(),
                        testExtractOutputDirectory.toFile(), "\t",
                        new WordIndex(Files.newInputStream(indexFile)));
        tse.extractSequences(config.modelLength, config.numberOfCores);
        tse.extractContinuationSequences(config.modelLength,
                config.numberOfCores);
    }

    private KneserNeySmoother buildKneserNey(
            Path workingDirectory,
            Path absoluteDirectory,
            Path continuationDirectory,
            Path testExtractOutputDirectory) {
        KneserNeySmoother kns =
                new KneserNeySmoother(testExtractOutputDirectory.toFile(),
                        absoluteDirectory.toFile(),
                        continuationDirectory.toFile(), "\t");

        // read absolute and continuation values into HashMaps
        logger.info("read absolute and continuation values into HashMaps for kneser ney");
        kns.absoluteTypeSequenceValueMap =
                kns.readAbsoluteValuesIntoHashMap(kns.extractedAbsoluteDirectory);
        kns.continuationTypeSequenceValueMap =
                kns.readContinuationValuesIntoHashMap(kns.extractedContinuationDirectory);

        for (int i = config.modelLength; i >= 1; i--) {
            Path inputSequenceFile =
                    workingDirectory.resolve("testing-samples-" + i + ".txt");

            if (config.kneserNeySimple) {
                Path resultFile =
                        workingDirectory
                                .resolve("kneser-ney-simple-backoffToCont-" + i
                                        + ".txt");
                kns.smooth(inputSequenceFile.toFile(), resultFile.toFile(), i,
                        false, config.conditionalProbabilityOnly);
            }

            if (config.kneserNeyComplex) {
                Path resultFile =
                        workingDirectory
                                .resolve("kneser-ney-complex-backoffToCont-"
                                        + i + ".txt");
                kns.smooth(inputSequenceFile.toFile(), resultFile.toFile(), i,
                        true, config.conditionalProbabilityOnly);
            }
        }

        return kns;
    }

    private
        void
        buildModKneserNey(
                Path workingDirectory,
                Path absoluteDirectory,
                Path continuationDirectory,
                Path testExtractOutputDirectory,
                HashMap<String, HashMap<String, Long>> absoluteTypeSequenceValueMap,
                HashMap<String, HashMap<String, Long[]>> continuationTypeSequenceValueMap) {
        ModifiedKneserNeySmoother mkns =
                new ModifiedKneserNeySmoother(
                        testExtractOutputDirectory.toFile(),
                        absoluteDirectory.toFile(),
                        continuationDirectory.toFile(), "\t",
                        config.decimalPlaces);

        if (absoluteTypeSequenceValueMap == null) {
            // read absolute and continuation values into HashMaps

            logger.info("read absolute and continuation values into HashMaps for mod kneser ney");
            absoluteTypeSequenceValueMap =
                    mkns.readAbsoluteValuesIntoHashMap(mkns.extractedAbsoluteDirectory);

            continuationTypeSequenceValueMap =
                    mkns.readContinuationValuesIntoHashMap(mkns.extractedContinuationDirectory);
        }

        mkns.absoluteTypeSequenceValueMap = absoluteTypeSequenceValueMap;
        mkns.continuationTypeSequenceValueMap =
                continuationTypeSequenceValueMap;

        for (int i = config.modelLength; i >= 1; i--) {
            Path inputSequenceFile =
                    workingDirectory.resolve("testing-samples-" + i + ".txt");

            if (config.kneserNeySimple) {
                Path resultFile =
                        workingDirectory
                                .resolve("mod-kneser-ney-simple-backoffToCont-"
                                        + i + ".txt");
                mkns.smooth(inputSequenceFile.toFile(), resultFile.toFile(), i,
                        false, config.conditionalProbabilityOnly);
            }

            if (config.kneserNeyComplex) {
                Path resultFile =
                        workingDirectory
                                .resolve("mod-kneser-ney-complex-backoffToCont-"
                                        + i + ".txt");
                mkns.smooth(inputSequenceFile.toFile(), resultFile.toFile(), i,
                        true, config.conditionalProbabilityOnly);
            }
        }
    }

    public static void main(String[] args) throws IOException,
            InterruptedException {
        new KneserNeyBuilder(args);
    }

}
