package de.typology.counting;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.typology.indexing.WordIndex;
import de.typology.patterns.PatternBuilder;
import de.typology.patterns.PatternTransformer;

/**
 * Counts continuation counts of sequences for a number of patterns using
 * absolute counts.
 */
public class ContinuationCounter {

    private Path inputDirectory;

    private Path outputDirectory;

    private WordIndex wordIndex;

    private String delimiter;

    private int numberOfCores;

    private boolean deleteTempFiles;

    private Logger logger = LogManager.getLogger(this.getClass().getName());

    private static final Comparator<boolean[]> PATTERN_COMPARATOR =
            new Comparator<boolean[]>() {

                @Override
                public int compare(boolean[] pattern1, boolean[] pattern2) {
                    return PatternTransformer.getStringPattern(pattern2)
                            .compareTo(
                                    PatternTransformer
                                            .getStringPattern(pattern1));
                }

            };

    public ContinuationCounter(
            Path inputDirectory,
            Path outputDirectory,
            WordIndex wordIndex,
            String delimiter,
            int numberOfCores,
            boolean deleteTempFiles) throws IOException {
        this.inputDirectory = inputDirectory;
        this.outputDirectory = outputDirectory;
        this.wordIndex = wordIndex;
        this.delimiter = delimiter;
        this.numberOfCores = numberOfCores;
        this.deleteTempFiles = deleteTempFiles;

        Files.createDirectory(outputDirectory);
    }

    public void split(List<boolean[]> patterns) throws IOException,
            InterruptedException {
        SortedMap<boolean[], boolean[]> continuationMap =
                filterContinuationMap(getContinuationMap(patterns));

        HashSet<boolean[]> finishedPatterns = new HashSet<boolean[]>();

        while (finishedPatterns.size() < continuationMap.size()) {
            ArrayList<boolean[]> currentPatterns = new ArrayList<boolean[]>();
            // initialize executerService
            ExecutorService executorService =
                    Executors.newFixedThreadPool(numberOfCores);

            for (Entry<boolean[], boolean[]> entry : continuationMap.entrySet()) {
                // list for storing patterns that are currently computed

                if (!finishedPatterns.contains(entry.getKey())) {
                    if (!PatternTransformer.getStringPattern(entry.getValue())
                            .contains("0")) {
                        // read absolute files
                        currentPatterns.add(entry.getKey());
                        logger.info("build continuation for "
                                + PatternTransformer.getStringPattern(entry
                                        .getKey())
                                + " from absolute "
                                + PatternTransformer.getStringPattern(entry
                                        .getValue()));

                        String inputPatternLabel =
                                PatternTransformer.getStringPattern(entry
                                        .getValue());
                        boolean[] outputPattern =
                                PatternTransformer
                                        .getBooleanPattern(PatternTransformer
                                                .getStringPattern(
                                                        entry.getKey())
                                                .replaceAll("0", ""));
                        String outputPatternLabel =
                                PatternTransformer.getStringPattern(
                                        entry.getKey()).replaceAll("0", "_");

                        Path currentAbsoluteworkingDirectory =
                                inputDirectory.resolve(inputPatternLabel);

                        logger.debug("inputPattern: "
                                + PatternTransformer.getStringPattern(entry
                                        .getValue()));
                        logger.debug("inputPatternLabel: " + inputPatternLabel);
                        logger.debug("outputPattern: "
                                + PatternTransformer
                                        .getStringPattern(outputPattern));
                        logger.debug("newPatternLabel: " + outputPatternLabel);
                        logger.debug("patternForModifier: "
                                + PatternTransformer.getStringPattern(entry
                                        .getKey()));

                        splitType(executorService,
                                currentAbsoluteworkingDirectory,
                                outputDirectory, outputPattern,
                                outputPatternLabel, entry.getKey(), wordIndex,
                                true);
                    } else {
                        if (finishedPatterns.contains(entry.getValue())) {
                            // read continuation files
                            currentPatterns.add(entry.getKey());
                            logger.info("build continuation for "
                                    + PatternTransformer.getStringPattern(entry
                                            .getKey())
                                    + " from continuation "
                                    + PatternTransformer.getStringPattern(entry
                                            .getValue()));

                            String inputPatternLabel =
                                    PatternTransformer.getStringPattern(
                                            entry.getValue()).replaceAll("0",
                                            "_");
                            boolean[] outputPattern =
                                    PatternTransformer
                                            .getBooleanPattern(PatternTransformer
                                                    .getStringPattern(
                                                            entry.getKey())
                                                    .replaceAll("0", ""));
                            String outputPatternLabel =
                                    PatternTransformer.getStringPattern(
                                            entry.getKey())
                                            .replaceAll("0", "_");

                            Path currentContinuationworkingDirectory =
                                    outputDirectory.resolve(inputPatternLabel);

                            // build patternForModifier
                            boolean[] patternForModifier =
                                    new boolean[Integer
                                            .bitCount(PatternTransformer
                                                    .getIntPattern(entry
                                                            .getValue()))];
                            System.out.println(outputPatternLabel + "<--"
                                    + inputPatternLabel + " "
                                    + patternForModifier.length);
                            int patternPointer = 0;
                            for (int i = 0; i < entry.getValue().length; i++) {
                                if (entry.getKey()[i] && entry.getValue()[i]) {
                                    patternForModifier[patternPointer] = true;
                                    patternPointer++;
                                } else {
                                    if (!entry.getKey()[i]
                                            && entry.getValue()[i]) {
                                        patternForModifier[patternPointer] =
                                                false;
                                        patternPointer++;
                                    }
                                }
                            }

                            logger.debug("inputPattern: "
                                    + PatternTransformer.getStringPattern(entry
                                            .getValue()));
                            logger.debug("inputPatternLabel: "
                                    + inputPatternLabel);
                            logger.debug("outputPattern: "
                                    + PatternTransformer
                                            .getStringPattern(outputPattern));
                            logger.debug("newPatternLabel: "
                                    + outputPatternLabel);
                            logger.debug("patternForModifier: "
                                    + PatternTransformer
                                            .getStringPattern(patternForModifier));

                            splitType(executorService,
                                    currentContinuationworkingDirectory,
                                    outputDirectory, outputPattern,
                                    outputPatternLabel, patternForModifier,
                                    wordIndex, false);

                        }
                    }
                }
            }

            executorService.shutdown();
            logger.info("end of this round of calculation");
            try {
                executorService.awaitTermination(Long.MAX_VALUE,
                        TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Interrupted
                throw e;
            }

            // add currently computed patterns to finishedPatterns
            for (boolean[] currentPattern : currentPatterns) {
                finishedPatterns.add(currentPattern);
            }
        }

    }

    private void splitType(
            ExecutorService executorService,
            Path currentWorkingDirectory,
            Path outputDirectory,
            boolean[] pattern,
            String patternLabel,
            boolean[] patternForModifier,
            WordIndex wordIndex,
            boolean setCountToOne) throws IOException {
        PipedInputStream input = new PipedInputStream(100 * 8 * 1024);
        OutputStream output = new PipedOutputStream(input);

        // PRODUCER ////////////////////////////////////////////////////////////

        SequenceModifier sequenceModifier =
                new SequenceModifier(currentWorkingDirectory.toFile(), output,
                        delimiter, patternForModifier, true, setCountToOne);
        executorService.execute(sequenceModifier);

        // CONSUMER ////////////////////////////////////////////////////////////

        // if pattern has only falses
        if (PatternTransformer.getIntPattern(pattern) == 0) {
            Path lineCountOutputDirPath = outputDirectory.resolve(patternLabel);
            Files.createDirectory(lineCountOutputDirPath);
            Path lineCountOutputPath = lineCountOutputDirPath.resolve("all");

            OutputStream lineCounterOutput =
                    Files.newOutputStream(lineCountOutputPath);
            LineCounterTask lineCountTask =
                    new LineCounterTask(input, lineCounterOutput, delimiter,
                            setCountToOne);
            executorService.execute(lineCountTask);
        } else {
            // don't add tags here
            PatternCounterTask splitterTask =
                    new PatternCounterTask(input,
                            outputDirectory.resolve(patternLabel), wordIndex,
                            pattern, delimiter, "", "", true, deleteTempFiles);
            executorService.execute(splitterTask);
        }

    }

    /**
     * Removes some entries from continuationMap:
     * 
     * <ul>
     * <li>remove if key == value</li>
     * <li>remove if first two are false</li>
     * </ul>
     */
    private static SortedMap<boolean[], boolean[]> filterContinuationMap(
            SortedMap<boolean[], boolean[]> continuationMap) {
        SortedMap<boolean[], boolean[]> newContinuationMap =
                new TreeMap<boolean[], boolean[]>(PATTERN_COMPARATOR);
        for (Entry<boolean[], boolean[]> entry : continuationMap.entrySet()) {
            if (PatternTransformer.getStringPattern(entry.getKey()).equals(
                    PatternTransformer.getStringPattern(entry.getValue()))) {
                continue;
            }
            boolean[] currentPattern = entry.getKey();
            if (currentPattern.length > 2 && !currentPattern[0]
                    && !currentPattern[1]) {
                continue;
            }
            newContinuationMap.put(entry.getKey(), entry.getValue());

        }
        return newContinuationMap;
    }

    private static SortedMap<boolean[], boolean[]> getContinuationMap(
            List<boolean[]> patterns) {
        SortedMap<boolean[], boolean[]> continuationMap =
                new TreeMap<boolean[], boolean[]>(PATTERN_COMPARATOR);

        for (boolean[] pattern : patterns) {
            addPatterns(continuationMap, pattern, pattern, 0);
        }
        return continuationMap;
    }

    private static void addPatterns(
            SortedMap<boolean[], boolean[]> continuationMap,
            boolean[] pattern,
            boolean[] oldPattern,
            int position) {
        if (position < pattern.length) {
            boolean[] newPattern = pattern.clone();
            newPattern[position] = false;
            continuationMap.put(newPattern, pattern);
            continuationMap.put(pattern, oldPattern);
            addPatterns(continuationMap, newPattern, pattern, position + 1);
            addPatterns(continuationMap, pattern, oldPattern, position + 1);
        }
    }

    // DEBUG FUNCTIONS /////////////////////////////////////////////////////////

    private static void printSortedMap(SortedMap<boolean[], boolean[]> map) {
        System.out.println("SortedMap: {");
        for (Map.Entry<boolean[], boolean[]> entry : map.entrySet()) {
            System.out.println("    "
                    + PatternTransformer.getStringPattern(entry.getKey())
                    + " -> "
                    + PatternTransformer.getStringPattern(entry.getValue()));
        }
        System.out.println("}");
    }

    private static void printList(List<boolean[]> list) {
        System.out.println("List: {");
        for (boolean[] pattern : list) {
            System.out.println("    "
                    + PatternTransformer.getStringPattern(pattern) + ",");
        }
        System.out.println("}");
    }

    public static void main(String[] args) {
        List<boolean[]> patterns = PatternBuilder.getReverseLMPatterns(5);
        printList(patterns);

        SortedMap<boolean[], boolean[]> continuationMap =
                getContinuationMap(patterns);
        printSortedMap(continuationMap);

        continuationMap = filterContinuationMap(continuationMap);
        printSortedMap(continuationMap);
    }

}
