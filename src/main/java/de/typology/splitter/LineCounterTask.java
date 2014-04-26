package de.typology.splitter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LineCounterTask implements Runnable {

    protected InputStream inputStream;

    protected File outputDirectory;

    protected String patternLabel;

    protected String delimiter;

    protected boolean setCountToOne;

    protected boolean additionalCounts;

    private Logger logger = LogManager.getLogger(this.getClass().getName());

    public LineCounterTask(
            InputStream inputStream,
            File outputDirectory,
            String patternLabel,
            String delimiter,
            boolean setCountToOne,
            boolean additionalCounts) {
        this.inputStream = inputStream;
        this.outputDirectory = outputDirectory;
        this.patternLabel = patternLabel;
        this.delimiter = delimiter;
        this.setCountToOne = setCountToOne;
        this.additionalCounts = additionalCounts;
    }

    @Override
    public void run() {
        File outputDirectory =
                new File(this.outputDirectory.getAbsolutePath() + "/"
                        + patternLabel);
        if (outputDirectory.exists()) {
            try {
                FileUtils.deleteDirectory(outputDirectory);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        outputDirectory.mkdir();
        logger.info("count lines for: " + outputDirectory.getAbsolutePath());

        BufferedReader inputStreamReader =
                new BufferedReader(new InputStreamReader(inputStream));
        long onePlusLineCount = 0L;
        long oneLineCount = 0L;
        long twoLineCount = 0L;
        long threePlusLineCount = 0L;
        String line;
        try {
            if (setCountToOne) {
                while ((line = inputStreamReader.readLine()) != null) {
                    onePlusLineCount++;
                }
            } else {
                while ((line = inputStreamReader.readLine()) != null) {
                    long currentCount =
                            Long.parseLong(line.split(delimiter)[1]);
                    onePlusLineCount += currentCount;
                    if (currentCount == 1L) {
                        oneLineCount += currentCount;
                    }
                    if (currentCount == 2L) {
                        twoLineCount += currentCount;
                    }
                    if (currentCount >= 3L) {
                        threePlusLineCount += currentCount;
                    }
                }
            }
            inputStreamReader.close();

            BufferedWriter bufferedWriter =
                    new BufferedWriter(new FileWriter(
                            outputDirectory.getAbsolutePath() + "/" + "all"));
            if (additionalCounts) {
                bufferedWriter.write(onePlusLineCount + delimiter
                        + oneLineCount + delimiter + twoLineCount + delimiter
                        + threePlusLineCount + "\n");
            } else {
                bufferedWriter.write(onePlusLineCount + "\n");
            }
            bufferedWriter.close();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}