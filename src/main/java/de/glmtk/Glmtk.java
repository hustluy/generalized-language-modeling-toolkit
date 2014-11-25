package de.glmtk;

import static de.glmtk.utils.NioUtils.CheckFile.EXISTS;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.glmtk.Status.TrainingStatus;
import de.glmtk.counting.AbsoluteCounter;
import de.glmtk.counting.ContinuationCounter;
import de.glmtk.counting.Tagger;
import de.glmtk.querying.NGramProbabilityCalculator;
import de.glmtk.querying.ProbMode;
import de.glmtk.querying.estimator.Estimator;
import de.glmtk.querying.estimator.Estimators;
import de.glmtk.utils.CountCache;
import de.glmtk.utils.NioUtils;
import de.glmtk.utils.Pattern;
import de.glmtk.utils.Patterns;
import de.glmtk.utils.StringUtils;

/**
 * Here happens counting (during training phase) and also querying (during
 * application phase) This class is been called either by one of the Executable
 * classes and filled from config or console input or it is called from
 * UnitTests
 * Expects parameters to be set via setters before calling any other method
 * TODO: what about default parameters
 *
 */
public class Glmtk {

    // TODO: fix needPos (reduntant parameter)
    // TODO: Output should be empty if a phase is skipped
    // TODO: Some Unicode bug prevents "海底軍艦 , to be Undersea" from turning up in en0008t corpus absolute 11111 counts.
    // TODO: Detect ngram model length from testing.

    private static final Logger LOGGER = LogManager
            .getFormatterLogger(Glmtk.class);

    private Config config = Config.get();

    private Path corpus;

    private Path workingDir;

    private Path statusFile;

    private Path trainingFile;

    private Path absoluteDir;

    private Path absoluteTmpDir;

    private Path continuationDir;

    private Path continuationTmpDir;

    private Path testCountsDir;

    private Path testingDir;

    private Status status;

    private CountCache countCache = null;

    public Glmtk(
            Path corpus,
            Path workingDir) throws IOException {
        this.corpus = corpus;
        this.workingDir = workingDir;
        statusFile = workingDir.resolve("status");
        trainingFile = workingDir.resolve("training");
        absoluteDir = workingDir.resolve(Constants.ABSOLUTE_DIR_NAME);
        absoluteTmpDir =
                workingDir.resolve(Constants.ABSOLUTE_DIR_NAME + ".tmp");
        continuationDir = workingDir.resolve(Constants.CONTINUATION_DIR_NAME);
        continuationTmpDir =
                workingDir.resolve(Constants.CONTINUATION_DIR_NAME + ".tmp");
        testCountsDir = workingDir.resolve("testcounts");
        testingDir = workingDir.resolve("testing");

        if (!Files.exists(workingDir)) {
            Files.createDirectories(workingDir);
        }

        status = new Status(statusFile, corpus);
        status.logStatus();
        // TODO: check file system if status is accurate.
    }

    public void count(boolean needPos, Set<Pattern> neededPatterns)
            throws IOException {
        // TODO: update status with smaller increments (each completed pattern).

        Set<Pattern> neededAbsolute = new HashSet<Pattern>();
        Set<Pattern> neededContinuation = new HashSet<Pattern>();

        // Split patterns into absolute and continuation and add patterns that
        // are needed to generate continuation.
        for (Pattern pattern : neededPatterns) {
            if (pattern.isAbsolute()) {
                neededAbsolute.add(pattern);
            } else {
                neededContinuation.add(pattern);
                Pattern source = pattern.getContinuationSource();
                if (source.isAbsolute()) {
                    neededAbsolute.add(source);
                } else {
                    neededContinuation.add(source);
                }
            }
        }

        LOGGER.debug("Counting %s", StringUtils.repeat("-", 80 - 9));
        LOGGER.debug("needPos            = %s", needPos);
        LOGGER.debug("neededAbsolute     = %s", neededAbsolute);
        LOGGER.debug("neededContinuation = %s", neededContinuation);

        // Training / Tagging //////////////////////////////////////////////////

        // TODO: Need to check if training is already tagged and act accordingly.
        // TODO: doesn't detect the setting that user changed from untagged training file, to tagged file with same corpus.
        // TODO: doesn't detect when switching from untagged training to continuing with now tagged corpus.
        if (needPos) {
            if (status.getTraining() == TrainingStatus.DONE_WITH_POS) {
                LOGGER.info("Detected tagged training already present, skipping tagging.");
            } else {
                if (corpus.equals(trainingFile)) {
                    Path tmpCorpus = Files.createTempFile("", "");
                    Files.copy(corpus, tmpCorpus);
                    corpus = tmpCorpus;
                }

                Tagger tagger =
                        new Tagger(config.getUpdateInterval(),
                                config.getModel());
                Files.deleteIfExists(trainingFile);
                tagger.tag(corpus, trainingFile);
                status.setTraining(TrainingStatus.DONE_WITH_POS, trainingFile);
            }
        } else {
            if (status.getTraining() != TrainingStatus.NONE) {
                LOGGER.info("Detected training already present, skipping copying training.");
            } else {
                if (!corpus.equals(trainingFile)) {
                    Files.deleteIfExists(trainingFile);
                    Files.copy(corpus, trainingFile);
                }
                status.setTraining(TrainingStatus.DONE, trainingFile);
            }
        }

        // Absolute ////////////////////////////////////////////////////////////

        AbsoluteCounter absoluteCounter =
                new AbsoluteCounter(neededAbsolute, config.getNumberOfCores(),
                        config.getUpdateInterval());
        absoluteCounter
                .count(status, trainingFile, absoluteDir, absoluteTmpDir);

        // Continuation ////////////////////////////////////////////////////////

        ContinuationCounter continuationCounter =
                new ContinuationCounter(neededContinuation,
                        config.getNumberOfCores(), config.getUpdateInterval());
        continuationCounter.count(status, absoluteDir, absoluteTmpDir,
                continuationDir, continuationTmpDir);
    }

    public CountCache getOrCreateCountCache() throws IOException {
        if (countCache == null) {
            countCache = new CountCache(workingDir);
        }
        return countCache;
    }

    public static void main(String[] args) throws IOException {
        Glmtk glmtk =
                new Glmtk(Paths.get("/home/lukas/langmodels/data/en0008t"),
                        Paths.get("/home/lukas/langmodels/data/en0008t.out/"));

        Set<Pattern> neededPatterns =
                Patterns.getUsedPatterns(Estimators.MOD_KNESER_NEY,
                        ProbMode.MARG);

        glmtk.getOrCreateTestCountCache(
                Paths.get("/home/lukas/langmodels/data/en0008t-t/5s"),
                neededPatterns);
    }

    // TODO: make it clever (only do new stuff if needed).
    public CountCache getOrCreateTestCountCache(
            Path testingFile,
            Set<Pattern> neededPatterns) throws IOException {
        LOGGER.info("Generating TestCountCache for '{}'.", testingFile);
        LOGGER.debug("Needed Patterns: {}", neededPatterns);

        boolean hasPos = false;
        // TODO: detect if test file has pos

        Path testCountDir =
                testCountsDir.resolve(Long.toString(new Random().nextLong()));
        Path testAbsoluteDir =
                testCountDir.resolve(Constants.ABSOLUTE_DIR_NAME);
        Path testContinuationDir =
                testCountDir.resolve(Constants.CONTINUATION_DIR_NAME);
        Files.createDirectories(testAbsoluteDir);
        Files.createDirectories(testContinuationDir);

        for (Pattern pattern : neededPatterns) {
            Path countFile, testCountFile;
            if (pattern.isAbsolute()) {
                countFile = absoluteDir.resolve(pattern.toString());
                testCountFile = testAbsoluteDir.resolve(pattern.toString());
            } else {
                countFile = continuationDir.resolve(pattern.toString());
                testCountFile = testContinuationDir.resolve(pattern.toString());
            }
            if (!NioUtils.checkFile(countFile, EXISTS)) {
                throw new IllegalStateException(
                        "Don't have corpus counts pattern '" + pattern
                                + "', needed for TestCounts.");
            }

            SortedSet<String> neededSequences =
                    new TreeSet<String>(extractSequencesForPattern(testingFile,
                            hasPos, pattern));
            filterAndWriteTestCounts(countFile, testCountFile, neededSequences);
        }

        return new CountCache(testCountDir);
    }

    private Set<String> extractSequencesForPattern(
            Path testingFile,
            boolean hasPos,
            Pattern pattern) throws IOException {
        Set<String> result = new HashSet<String>();

        int patternSize = pattern.size();
        try (BufferedReader reader =
                Files.newBufferedReader(testingFile, Charset.defaultCharset())) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] split =
                        StringUtils.splitAtChar(line, ' ').toArray(
                                new String[0]);
                String[] words = new String[split.length];
                String[] poses = new String[split.length];
                StringUtils.extractWordsAndPoses(split, hasPos, words, poses);

                for (int p = 0; p <= split.length - patternSize; ++p) {
                    result.add(pattern.apply(words, poses, p));
                }
            }
        }

        return result;
    }

    private void filterAndWriteTestCounts(
            Path countFile,
            Path testCountFile,
            SortedSet<String> neededSequences) throws IOException {
        try (BufferedReader reader =
                Files.newBufferedReader(countFile, Charset.defaultCharset());
                BufferedWriter writer =
                        Files.newBufferedWriter(testCountFile,
                                Charset.defaultCharset())) {
            String nextSequence = neededSequences.first();

            String line;
            while ((line = reader.readLine()) != null) {
                int p = line.indexOf('\t');
                String sequence = p == -1 ? line : line.substring(0, p);

                int compare;
                while ((compare = sequence.compareTo(nextSequence)) >= 0) {
                    if (compare == 0) {
                        writer.write(line);
                        writer.write('\n');
                    }

                    neededSequences.remove(nextSequence);
                    if (neededSequences.isEmpty()) {
                        break;
                    }
                    nextSequence = neededSequences.first();
                }
            }
        }
    }

    public void test(
            Path testingFile,
            Estimator estimator,
            ProbMode probMode,
            CountCache testCountCache) throws IOException {
        Files.createDirectories(testingDir);

        estimator.setCountCache(testCountCache);

        NGramProbabilityCalculator calculator =
                new NGramProbabilityCalculator();
        calculator.setProbMode(probMode);
        calculator.setEstimator(estimator);

        SimpleDateFormat dateFormat =
                new SimpleDateFormat(" yyyy-MM-dd HH:mm:ss");
        Path outputFile =
                testingDir.resolve(testingFile.getFileName() + " "
                        + Estimators.getName(estimator)
                        + dateFormat.format(new Date()));
        Files.deleteIfExists(outputFile);

        LOGGER.info("Testing '%s' -> '%s'.", testingFile, outputFile);

        try (BufferedReader reader =
                Files.newBufferedReader(testingFile, Charset.defaultCharset());
                BufferedWriter writer =
                        Files.newBufferedWriter(outputFile,
                                Charset.defaultCharset())) {
            int cntZero = 0;
            int cntNonZero = 0;
            double sumProbabilities = 0;
            double crossEntropy = 0;
            double entropy = 0;
            double logBase = Math.log(Constants.LOG_BASE);

            String line;
            while ((line = reader.readLine()) != null) {
                double probability =
                        calculator.probability(StringUtils.splitAtChar(line,
                                ' '));

                if (probability == 0) {
                    ++cntZero;
                } else {
                    ++cntNonZero;
                    sumProbabilities += probability;
                    crossEntropy -= Math.log(probability);
                    entropy -= Math.log(probability) * probability;
                }

                writer.append(line);
                writer.append('\t');
                writer.append(Double.toString(probability));
                writer.append('\n');
            }

            if (cntNonZero != 0) {
                crossEntropy /= (cntNonZero * logBase);
                entropy /= logBase;
            }

            LOGGER.info("Count Zero-Propablity Sequences = %s (%6.2f%%)",
                    cntZero, (double) cntZero / (cntZero + cntNonZero) * 100);
            LOGGER.info("Count Non-Zero-Propability Sequences = %s (%6.2f%%)",
                    cntNonZero, (double) cntNonZero / (cntZero + cntNonZero)
                            * 100);
            LOGGER.info("Sum of Propabilities = %s", sumProbabilities);
            LOGGER.info("Cross Entropy = %s", crossEntropy);
            LOGGER.info("Entropy = %s", entropy);
        }
    }

}
