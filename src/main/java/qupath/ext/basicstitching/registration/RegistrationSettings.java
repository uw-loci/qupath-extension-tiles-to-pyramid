package qupath.ext.basicstitching.registration;

/**
 * Tuning for content-based tile registration.
 *
 * <p>{@link #defaults()} is the supported configuration; the individual knobs exist so the gates
 * can be exercised from tests and so an unusual dataset can be rescued without a code change.
 *
 * @param minNcc peak NCC below which a match is not believed. Default 0.30 -- the same threshold
 *     the pixel-size estimator already validates against real PPM tiles.
 * @param minCoeffOfVar {@code sqrt(variance)/mean} below which an overlap band counts as
 *     featureless. Coefficient of variation rather than absolute variance: bright, low-contrast
 *     backgrounds are the failure case here, and absolute variance is not comparable across bit
 *     depths or exposures.
 * @param ambiguityRatio reject when the second-best well-separated peak scores at least this
 *     fraction of the best. Catches repeating texture.
 * @param bandMarginFrac reject when the peak lands beyond this fraction of the search half-width.
 *     A peak pinned to the window edge means the true peak is outside the overlap band, so the
 *     match is spurious.
 * @param lambda strength of the pull toward nominal in the global solve, relative to the median
 *     edge weight. Also what pins gauge freedom and holds disconnected tiles at nominal.
 * @param maxOutlierIters iterative reweighting passes that drop edges disagreeing with the global
 *     solution.
 * @param coarsestDownsample starting downsample for the coarse-to-fine search. Must be a power of
 *     two.
 * @param topKPeaks candidate peaks carried from each pyramid level to the next.
 * @param threads worker threads for pairwise registration. Each gets its own reader pool.
 * @param overlapPercentX explicit X overlap percent, or {@link Double#NaN} to derive it from the
 *     nominal tile step. Deriving is the default and is the more robust choice: the acquisition
 *     preference can change between acquiring and re-stitching, and deriving detects a 0%-overlap
 *     grid for free.
 * @param overlapPercentY explicit Y overlap percent, or {@link Double#NaN} to derive it.
 */
public record RegistrationSettings(
        double minNcc,
        double minCoeffOfVar,
        double ambiguityRatio,
        double bandMarginFrac,
        double lambda,
        int maxOutlierIters,
        int coarsestDownsample,
        int topKPeaks,
        int threads,
        double overlapPercentX,
        double overlapPercentY) {

    /** Bands narrower than this on either axis carry too little signal to correlate. */
    public static final int MIN_BAND_PX = 16;

    public RegistrationSettings {
        if (coarsestDownsample < 1 || Integer.bitCount(coarsestDownsample) != 1) {
            throw new IllegalArgumentException("coarsestDownsample must be a power of two, got " + coarsestDownsample);
        }
        if (topKPeaks < 1) {
            throw new IllegalArgumentException("topKPeaks must be >= 1, got " + topKPeaks);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got " + threads);
        }
        if (lambda <= 0) {
            throw new IllegalArgumentException(
                    "lambda must be > 0 so the solve stays positive definite, got " + lambda);
        }
    }

    /** @return the supported default configuration. */
    public static RegistrationSettings defaults() {
        return new RegistrationSettings(
                0.30,
                0.02,
                0.92,
                0.90,
                0.01,
                2,
                8,
                3,
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                Double.NaN,
                Double.NaN);
    }

    /** @return whether the X overlap is explicitly specified rather than derived from the grid. */
    public boolean hasExplicitOverlapX() {
        return !Double.isNaN(overlapPercentX);
    }

    /** @return whether the Y overlap is explicitly specified rather than derived from the grid. */
    public boolean hasExplicitOverlapY() {
        return !Double.isNaN(overlapPercentY);
    }

    /** @return a copy with the overlap percentages explicitly pinned rather than derived. */
    public RegistrationSettings withExplicitOverlap(double percentX, double percentY) {
        return new RegistrationSettings(
                minNcc,
                minCoeffOfVar,
                ambiguityRatio,
                bandMarginFrac,
                lambda,
                maxOutlierIters,
                coarsestDownsample,
                topKPeaks,
                threads,
                percentX,
                percentY);
    }

    /** @return a copy using the given number of worker threads. */
    public RegistrationSettings withThreads(int workerThreads) {
        return new RegistrationSettings(
                minNcc,
                minCoeffOfVar,
                ambiguityRatio,
                bandMarginFrac,
                lambda,
                maxOutlierIters,
                coarsestDownsample,
                topKPeaks,
                workerThreads,
                overlapPercentX,
                overlapPercentY);
    }
}
