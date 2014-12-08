package de.glmtk.querying.estimator.interpolation;

import java.util.Set;

import de.glmtk.querying.estimator.Estimator;
import de.glmtk.querying.estimator.discount.DiscountEstimator;
import de.glmtk.utils.BackoffMode;
import de.glmtk.utils.NGram;

public class DiffInterpolationEstimator extends InterpolationEstimator {

    public DiffInterpolationEstimator(
            DiscountEstimator alpha) {
        super(alpha, BackoffMode.SKP);
    }

    public DiffInterpolationEstimator(
            DiscountEstimator alpha,
            Estimator beta) {
        super(alpha, beta, BackoffMode.SKP);
    }

    public DiffInterpolationEstimator(
            DiscountEstimator alpha,
            Estimator beta,
            BackoffMode backoffMode) {
        super(alpha, beta, backoffMode);
    }

    public DiffInterpolationEstimator(
            DiscountEstimator alpha,
            BackoffMode backoffMode) {
        super(alpha, backoffMode);
    }

    @Override
    public void setBackoffMode(BackoffMode backoffMode) {
        // Here all backoffModes are allowed, as opposed to
        // {@link InterpolationEstimator#setBackoffMode(BackoffMode)}.
        this.backoffMode = backoffMode;
    }

    @Override
    protected double
        calcProbability(NGram sequence, NGram history, int recDepth) {
        if (history.isEmptyOrOnlySkips()) {
            //if (history.isEmpty()) {
            return super.calcProbability(sequence, history, recDepth);
        } else {
            double alphaVal = alpha.probability(sequence, history, recDepth);
            double betaVal = 0;
            Set<NGram> differentiatedHistories =
                    history.getDifferentiatedNGrams(backoffMode);
            for (NGram differentiatedHistory : differentiatedHistories) {
                betaVal +=
                        beta.probability(sequence, differentiatedHistory,
                                recDepth);
            }
            betaVal /= differentiatedHistories.size();
            double gammaVal = gamma(sequence, history, recDepth);

            return alphaVal + gammaVal * betaVal;
        }
    }

}
