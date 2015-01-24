/*
 * Generalized Language Modeling Toolkit (GLMTK)
 *
 * Copyright (C) 2015 Lukas Schmelzeisen
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

package de.glmtk.querying.estimator.learned;

import static de.glmtk.Constants.MODEL_MODKNESERNEY;
import de.glmtk.common.BackoffMode;
import de.glmtk.common.NGram;
import de.glmtk.counts.AlphaCount;
import de.glmtk.counts.LambdaCounts;
import de.glmtk.querying.estimator.AbstractEstimator;

public class LearnedModKneserNeyEstimator extends AbstractEstimator {
    @Override
    protected double calcProbability(NGram sequence,
                                     NGram history,
                                     int recDepth) {
        AlphaCount alphaCount = cache.getAlpha(MODEL_MODKNESERNEY,
                getFullSequence(sequence, history));
        if (alphaCount == null) {
            alphaCount = cache.getAlpha(MODEL_MODKNESERNEY, sequence);
            return alphaCount == null ? 0.0 : alphaCount.getNormal();
        }

        if (history.isEmptyOrOnlySkips())
            return alphaCount.getNormal();

        NGram lambdaNGram = history.concat(NGram.WSKP_NGRAM);

        LambdaCounts lambdaCounts = cache.getLambda(MODEL_MODKNESERNEY,
                lambdaNGram);
        double lambda = 0.0;
        if (lambdaCounts == null)
            return 0.0;
        lambda = lambdaCounts.get(0).getHigh();

        NGram backoffHistory = history.backoffUntilSeen(BackoffMode.DEL, cache);
        double beta = probability(sequence, backoffHistory, recDepth);

        return alphaCount.getDiscounted() + lambda * beta;
    }
}
