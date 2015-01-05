package de.glmtk;

import static de.glmtk.common.Output.OUTPUT;
import static de.glmtk.counting.Chunker.CHUNKER;
import static de.glmtk.counting.LengthDistributionCalculator.LENGTH_DISTRIBUTION_CALCULATOR;
import static de.glmtk.counting.Merger.MERGER;
import static de.glmtk.counting.NGramTimesCounter.NGRAM_TIMES_COUNTER;
import static de.glmtk.counting.Tagger.TAGGER;
import static de.glmtk.querying.QueryCacherCreator.QUERY_CACHE_CREATOR;
import static de.glmtk.querying.QueryRunner.QUERY_RUNNER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.glmtk.common.CountCache;
import de.glmtk.common.Pattern;
import de.glmtk.common.ProbMode;
import de.glmtk.common.Status;
import de.glmtk.common.Status.Training;
import de.glmtk.counting.Tagger;
import de.glmtk.querying.QueryStats;
import de.glmtk.querying.estimator.Estimator;
import de.glmtk.util.HashUtils;
import de.glmtk.util.NioUtils;
import de.glmtk.util.PrintUtils;

/**
 * Here happens counting (during training phase) and also querying (during
 * application phase) This class is been called either by one of the Executable
 * classes and filled from config or console input or it is called from
 * UnitTests Expects parameters to be set via setters before calling any other
 * method TODO: what about default parameters
 */
public class Glmtk {
    // TODO: API bugs with spaces in filenames
    // TODO: fix needPos (reduntant parameter)
    // TODO: Output should be empty if a phase is skipped
    // TODO: Some Unicode bug prevents "海底軍艦 , to be Undersea" from turning up in en0008t corpus absolute 11111 counts.
    // TODO: Detect ngram model length from testing.
    // TODO: only count nGramTimes if needed
    // TODO: enable comment syntax in input files
    // TODO: how is testing file input treated? (empty lines?)
    // TODO: verify that training does not contain any reserved symbols (_ % / multiple spaces)
    // TODO: update status with smaller increments (each completed pattern).
    // TODO: check to see if we can use SLF4j

    private static final Logger LOGGER = LogManager.getFormatterLogger(Glmtk.class);

    private static class NeededComputations {
        private boolean tagging;
        private Set<Pattern> absolute;
        private Set<Pattern> continuation;

        public NeededComputations(boolean tagging,
                                  Set<Pattern> absolute,
                                  Set<Pattern> continuation) {
            this.tagging = tagging;
            this.absolute = absolute;
            this.continuation = continuation;
        }

        public boolean needTagging() {
            return tagging;
        }

        public Set<Pattern> getAbsolute() {
            return absolute;
        }

        public Set<Pattern> getContinuation() {
            return continuation;
        }
    }

    private Path corpus;
    private GlmtkPaths paths;
    private Status status;
    private CountCache countCache = null;

    public Glmtk(Path corpus,
                 Path workingDir) throws Exception {
        this.corpus = corpus;

        paths = new GlmtkPaths(workingDir);
        paths.logPaths();

        Files.createDirectories(workingDir);

        status = new Status(paths, corpus);
        status.logStatus();
        // TODO: check file system if status is accurate.
    }

    public GlmtkPaths getPaths() {
        return paths;
    }

    public void count(Set<Pattern> neededPatterns) throws Exception {
        NeededComputations needed = computeNeeded(neededPatterns);

        provideTraining(needed.needTagging());

        OUTPUT.beginPhases("Corpus Analyzation...");

        countAbsolute(needed.getAbsolute());
        countContinuation(needed.getContinuation());
        NGRAM_TIMES_COUNTER.count(status, paths.getNGramTimesFile(),
                paths.getAbsoluteDir(), paths.getContinuationDir());
        LENGTH_DISTRIBUTION_CALCULATOR.calculate(status,
                paths.getTrainingFile(), paths.getLengthDistributionFile());

        long corpusSize = NioUtils.calcFileSize(paths.getCountsDir());
        OUTPUT.endPhases(String.format("Corpus Analyzation done (uses %s).",
                PrintUtils.humanReadableByteCount(corpusSize)));
    }

    private NeededComputations computeNeeded(Set<Pattern> neededPatterns) {
        boolean tagging = false;
        Set<Pattern> absolute = new HashSet<Pattern>();
        Set<Pattern> continuation = new HashSet<Pattern>();

        Queue<Pattern> queue = new LinkedList<Pattern>(neededPatterns);
        while (!queue.isEmpty()) {
            Pattern pattern = queue.poll();
            if (pattern.isPos())
                tagging = true;
            if (pattern.isAbsolute())
                absolute.add(pattern);
            else {
                continuation.add(pattern);
                Pattern source = pattern.getContinuationSource();
                if ((source.isAbsolute() ? absolute : continuation).add(source))
                    queue.add(source);
            }
        }

        LOGGER.debug("needPos            = %s", tagging);
        LOGGER.debug("neededAbsolute     = %s", absolute);
        LOGGER.debug("neededContinuation = %s", continuation);

        return new NeededComputations(tagging, absolute, continuation);
    }

    private void provideTraining(boolean needTagging) throws IOException {
        // TODO: Need to check if training is already tagged and act accordingly.
        // TODO: doesn't detect the setting that user changed from untagged training file, to tagged file with same corpus.
        // TODO: doesn't detect when switching from untagged training to continuing with now tagged corpus.

        if (status.getTraining() == Training.TAGGED) {
            LOGGER.info("Detected tagged training already present.");
            return;
        }

        Path trainingFile = paths.getTrainingFile();
        if (!needTagging) {
            if (status.getTraining() == Training.UNTAGGED) {
                LOGGER.info("Detected training already present");
                return;
            }

            if (!corpus.equals(trainingFile)) {
                Files.deleteIfExists(trainingFile);
                Files.copy(corpus, trainingFile);
            }
            if (status.isCorpusTagged())
                status.setTraining(Training.TAGGED);
            else
                status.setTraining(Training.UNTAGGED);
        } else {
            if (status.isCorpusTagged()) {
                if (!corpus.equals(trainingFile)) {
                    Files.deleteIfExists(trainingFile);
                    Files.copy(corpus, trainingFile);
                }
            } else {
                Path untaggedTrainingFile = paths.getUntaggedTrainingFile();
                Files.deleteIfExists(untaggedTrainingFile);
                Files.copy(corpus, untaggedTrainingFile);
                Files.deleteIfExists(trainingFile);

                TAGGER.tag(untaggedTrainingFile, trainingFile);
            }
            status.setTraining(Training.TAGGED);
        }
    }

    private void countAbsolute(Set<Pattern> neededPatterns) throws Exception {
        LOGGER.info("Absolute counting '%s' -> '%s'.", paths.getTrainingFile(),
                paths.getAbsoluteDir());

        Set<Pattern> countingPatterns = new HashSet<Pattern>(neededPatterns);
        countingPatterns.removeAll(status.getCounted(false));

        Set<Pattern> chunkingPatterns = new HashSet<Pattern>(countingPatterns);
        chunkingPatterns.removeAll(status.getChunkedPatterns(false));

        LOGGER.debug("neededPatterns   = %s", neededPatterns);
        LOGGER.debug("countingPatterns = %s", countingPatterns);
        LOGGER.debug("chunkingPatterns = %s", chunkingPatterns);

        CHUNKER.chunkAbsolute(status, chunkingPatterns,
                paths.getTrainingFile(),
                status.getTraining() == Training.TAGGED,
                paths.getAbsoluteChunkedDir());
        validateExpectedResults("Absolute chunking", chunkingPatterns,
                status.getChunkedPatterns(false));

        MERGER.mergeAbsolute(status, countingPatterns,
                paths.getAbsoluteChunkedDir(), paths.getAbsoluteDir());
        validateExpectedResults("Absolute counting", countingPatterns,
                status.getCounted(false));
    }

    private void countContinuation(Set<Pattern> neededPatterns) throws Exception {
        LOGGER.info("Continuation counting '%s' -> '%s'.",
                paths.getAbsoluteDir(), paths.getContinuationDir());

        Set<Pattern> countingPatterns = new HashSet<Pattern>(neededPatterns);
        countingPatterns.removeAll(status.getCounted(true));

        Set<Pattern> chunkingPatterns = new HashSet<Pattern>(countingPatterns);
        chunkingPatterns.removeAll(status.getChunkedPatterns(true));

        LOGGER.debug("neededPatterns   = %s", neededPatterns);
        LOGGER.debug("countingPatterns = %s", countingPatterns);
        LOGGER.debug("chunkingPatterns = %s", chunkingPatterns);

        CHUNKER.chunkContinuation(status, chunkingPatterns,
                paths.getAbsoluteDir(), paths.getContinuationDir(),
                paths.getAbsoluteChunkedDir(),
                paths.getContinuationChunkedDir());
        validateExpectedResults("Continuation chunking", chunkingPatterns,
                status.getChunkedPatterns(true));

        MERGER.mergeContinuation(status, countingPatterns,
                paths.getContinuationChunkedDir(), paths.getContinuationDir());
        validateExpectedResults("Continuation counting", countingPatterns,
                status.getCounted(true));
    }

    public CountCache getOrCreateCountCache() throws IOException {
        if (countCache == null)
            countCache = new CountCache(paths);
        return countCache;
    }

    public CountCache provideQueryCache(Path queryFile,
                                        Set<Pattern> patterns) throws Exception {

        String name = HashUtils.generateMd5Hash(queryFile);
        GlmtkPaths queryCachePaths = paths.newQueryCache(name);
        queryCachePaths.logPaths();

        String message = String.format("QueryCache for file '%s'", queryFile);
        OUTPUT.beginPhases(message + "...");

        Set<Pattern> neededPatterns = new HashSet<Pattern>(patterns);
        neededPatterns.removeAll(status.getQueryCacheCounted(name));

        LOGGER.debug("neededPatterns = %s", neededPatterns);

        boolean tagged = Tagger.detectFileTagged(queryFile);
        QUERY_CACHE_CREATOR.createQueryCache(status, neededPatterns, name,
                queryFile, tagged, paths.getAbsoluteDir(),
                paths.getContinuationDir(), queryCachePaths.getAbsoluteDir(),
                queryCachePaths.getContinuationDir());
        validateExpectedResults("Caching pattern counts", neededPatterns,
                status.getQueryCacheCounted(name));

        Path dir = queryCachePaths.getDir();
        long size = NioUtils.calcFileSize(dir);
        OUTPUT.endPhases(message + " done:");
        OUTPUT.printMessage(String.format(
                "    Saved as '%s' under '%s' (uses %s).", dir.getFileName(),
                dir.getParent(), PrintUtils.humanReadableByteCount(size)));

        return new CountCache(queryCachePaths);
    }

    private void validateExpectedResults(String operation,
                                         Set<Pattern> expected,
                                         Set<Pattern> computed) throws Exception {
        if (!computed.containsAll(expected)) {
            Set<Pattern> missing = new HashSet<Pattern>();
            missing.addAll(expected);
            missing.removeAll(computed);

            LOGGER.error("%s did not yield expected result.%n", operation);
            LOGGER.error("Expected patterns = %s.%n", expected);
            LOGGER.error("Computed patterns = %s.%n", computed);
            LOGGER.error("Missing  patterns = %s.", missing);
            throw new Exception("%s failed.");
        }
    }

    public QueryStats runQuery(String queryTypeString,
                               Path inputFile,
                               Estimator estimator,
                               ProbMode probMode,
                               CountCache countCache) throws Exception {
        return QUERY_RUNNER.runQuery(queryTypeString, inputFile,
                paths.getQueriesDir(), estimator, probMode, countCache);
    }
}