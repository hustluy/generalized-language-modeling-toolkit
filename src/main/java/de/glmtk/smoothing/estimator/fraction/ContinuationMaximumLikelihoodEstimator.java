package de.glmtk.smoothing.estimator.fraction;

import de.glmtk.smoothing.NGram;

public class ContinuationMaximumLikelihoodEstimator extends FractionEstimator {

    @Override
    protected double calcNumerator(NGram sequence, NGram history, int recDepth) {
        NGram contFullSequence =
                NGram.SKIPPED_WORD_NGRAM.concat(getFullSequence(sequence,
                        history));
        long contFullSequenceCount =
                corpus.getContinuation(contFullSequence).getOnePlusCount();
        logDebug(recDepth, "contFullSequence = {} ({})", contFullSequence,
                contFullSequenceCount);
        return contFullSequenceCount;
    }

    @Override
    protected double
        calcDenominator(NGram sequence, NGram history, int recDepth) {
        NGram contFullHistory =
                NGram.SKIPPED_WORD_NGRAM.concat(getFullHistory(sequence,
                        history));
        long contFullHistoryCount =
                corpus.getContinuation(contFullHistory).getOnePlusCount();
        logDebug(recDepth, "contFullHistory = {} ({})", contFullHistory,
                contFullHistoryCount);
        return contFullHistoryCount;
    }

}