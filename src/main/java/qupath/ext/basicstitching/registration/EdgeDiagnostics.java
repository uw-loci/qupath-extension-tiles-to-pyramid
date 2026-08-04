package qupath.ext.basicstitching.registration;

/**
 * The values every gate actually saw when an edge was measured, carried out for inspection.
 *
 * <h2>Why this exists</h2>
 *
 * The gate thresholds ({@code minCoeffOfVar}, {@code ambiguityRatio}, {@code minNcc}, the search
 * bound) were originally chosen by reasoning about what ought to be true of microscope tiles, not by
 * measuring what is true of any particular scope's tiles. That is guesswork, and it has been wrong
 * more than once: a search bound sized from already-smoothed output was too tight to measure the
 * real correction, and a weight curve tuned for "confident edges" silently nullified correct
 * low-contrast ones.
 *
 * <p>Every field here is a quantity a threshold is compared against. Dumping them per edge -- over a
 * real acquisition, ideally one that includes background as well as tissue -- turns every default
 * from an assumption into something that can be read off a distribution. Nothing here affects the
 * solve; it is pure instrumentation.
 *
 * @param textureA robust coefficient of variation of the first tile's overlap band; the value the
 *     low-texture gate ({@code minCoeffOfVar}) rejects on
 * @param textureB the same for the second tile's band
 * @param medianA median intensity of the first band; what the saturation check tests against full
 *     scale
 * @param medianB the same for the second band
 * @param maxPossibleValue full scale for the tiles' bit depth, so the medians can be read as a
 *     fraction
 * @param bestNcc correlation of the winning peak, before the {@code minNcc} gate
 * @param secondPeakNcc correlation of the runner-up peak, or NaN when there was only one
 * @param secondPeakRatio {@code secondPeakNcc / bestNcc}; the value the ambiguity gate rejects on
 * @param secondPeakSeparationPx distance between the two peaks; the ambiguity gate only fires when
 *     this exceeds the same-peak distance, so a high ratio alone is not a rejection
 * @param shiftXPx measured X shift of the winning peak, relative to nominal
 * @param shiftYPx measured Y shift of the winning peak, relative to nominal
 * @param searchXPx half-width of the X search window actually used -- a shift at this bound means
 *     the true peak may lie outside and never had a chance to be found
 * @param searchYPx half-width of the Y search window actually used
 * @param bandWidthPx width of the correlated overlap band
 * @param bandHeightPx height of the correlated overlap band
 * @param coarsestDownsampleUsed coarsest pyramid level the search actually ran at, which can be
 *     finer than requested when downsampling would have destroyed the signal
 */
public record EdgeDiagnostics(
        double textureA,
        double textureB,
        double medianA,
        double medianB,
        double maxPossibleValue,
        double bestNcc,
        double secondPeakNcc,
        double secondPeakRatio,
        double secondPeakSeparationPx,
        double shiftXPx,
        double shiftYPx,
        int searchXPx,
        int searchYPx,
        int bandWidthPx,
        int bandHeightPx,
        int coarsestDownsampleUsed) {

    /** Placeholder for edges that never reached the correlation stage (no overlap, unreadable). */
    public static final EdgeDiagnostics EMPTY = new EdgeDiagnostics(
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            0,
            0,
            0,
            0,
            0);

    /**
     * @return whether the winning shift sits at the edge of the search window on either axis, which
     *     means the window may have been too small to contain the true peak
     */
    public boolean shiftAtSearchBound() {
        if (searchXPx <= 0 && searchYPx <= 0) {
            return false;
        }
        return (searchXPx > 0 && Math.abs(shiftXPx) >= 0.9 * searchXPx)
                || (searchYPx > 0 && Math.abs(shiftYPx) >= 0.9 * searchYPx);
    }
}
