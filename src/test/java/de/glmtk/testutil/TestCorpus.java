/*
 * Generalized Language Modeling Toolkit (GLMTK)
 *
 * Copyright (C) 2014-2015 Lukas Schmelzeisen
 *
 * GLMTK is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * GLMTK is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * GLMTK. If not, see <http://www.gnu.org/licenses/>.
 *
 * See the AUTHORS file for contributors.
 */

package de.glmtk.testutil;

import static de.glmtk.common.PatternElem.CNT;
import static de.glmtk.common.PatternElem.SKP;
import static de.glmtk.common.PatternElem.WSKP;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import de.glmtk.Constants;
import de.glmtk.Glmtk;
import de.glmtk.cache.Cache;
import de.glmtk.cache.CacheBuilder;
import de.glmtk.common.Config;
import de.glmtk.common.Pattern;
import de.glmtk.common.Patterns;

public enum TestCorpus {
    ABC,
    MOBYDICK,
    EN0008T;

    public static void intializeTestCorpora(Config config) throws Exception {
        for (TestCorpus testCorpus : values())
            testCorpus.intialize(config);
    }

    private String corpusName;
    private Path corpus;
    private Path workingDir;
    private Glmtk glmtk;

    /**
     * Lazily loaded.
     */
    private Cache cache;

    private TestCorpus() {
        corpusName = toString();
        corpus = Constants.TEST_RESSOURCES_DIR.resolve(corpusName.toLowerCase());
        workingDir = Constants.TEST_RESSOURCES_DIR.resolve(corpusName.toLowerCase()
                + Constants.WORKING_DIR_SUFFIX);

        cache = null;
    }

    private void intialize(Config config) throws Exception {
        glmtk = new Glmtk(config, corpus, workingDir);

        Set<Pattern> neededPatterns = Patterns.getCombinations(Constants.TEST_ORDER,
                Arrays.asList(CNT, SKP));
        for (Pattern pattern : new HashSet<>(neededPatterns)) {
            if (pattern.size() != Constants.TEST_ORDER)
                neededPatterns.add(pattern.concat(WSKP));

            if (pattern.contains(SKP))
                neededPatterns.add(pattern.replace(SKP, WSKP));
        }

        glmtk.count(neededPatterns);
    }

    public String getCorpusName() {
        return corpusName;
    }

    public Path getCorpus() {
        return corpus;
    }

    public Path getWorkingDir() {
        return workingDir;
    }

    public Glmtk getGlmtk() {
        return glmtk;
    }

    public Cache getCache() throws Exception {
        if (cache == null)
            cache = new CacheBuilder(glmtk.getPaths()).withCounts(
                    glmtk.getStatus().getCounted()).withNGramTimes().withLengthDistribution().build();

        return cache;
    }

    public Cache getCache(Set<Pattern> patterns) throws Exception {
        return new CacheBuilder(glmtk.getPaths()).withCounts(patterns).withNGramTimes().withLengthDistribution().build();
    }

    public String[] getWords() throws Exception {
        Set<String> words = getCache().getWords();
        return words.toArray(new String[words.size()]);
    }

    public List<String> getSequenceList(int n,
                                        int length) throws Exception {
        List<String> result = new LinkedList<>();
        for (int k = 0; k != length; ++k) {
            result.add(getWords()[n % getWords().length]);
            n /= getWords().length;
        }
        Collections.reverse(result);
        return result;
    }
}
