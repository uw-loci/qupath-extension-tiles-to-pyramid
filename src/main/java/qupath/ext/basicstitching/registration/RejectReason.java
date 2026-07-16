package qupath.ext.basicstitching.registration;

/**
 * Why a pairwise edge measurement was not used in the global position solve.
 *
 * <p>Every edge carries one of these. {@link #NONE} means the measurement was accepted and
 * contributes to the solve; every other value means the edge was dropped from the graph.
 *
 * <p>Rejected edges are <b>dropped</b>, not replaced with a nominal-offset edge. Injecting a
 * nominal edge would double-count the solver's nominal pull (the lambda term) and drag a tile with
 * one good edge and three rejected ones back toward nominal at 3:1, undoing the good measurement.
 * The lambda term in {@link GlobalPositionSolver} <i>is</i> the nominal fallback.
 */
public enum RejectReason {

    /** Measurement accepted; the edge contributes to the solve. */
    NONE,

    /**
     * The correlation peak pinned to the edge of the search window, so the true peak lies outside
     * the physically-possible overlap band. A correction larger than the overlap cannot be real.
     */
    OUT_OF_BAND,

    /** Peak normalized cross-correlation fell below the confidence threshold. */
    LOW_NCC,

    /**
     * One or both overlap bands are (near-)uniform, so the correlation surface is meaningless.
     * Gated on coefficient of variation rather than absolute variance: bright low-contrast
     * backgrounds are the failure case, and absolute variance is not comparable across bit depths
     * or exposures.
     */
    LOW_VARIANCE,

    /**
     * Two or more well-separated peaks scored comparably, so the match is ambiguous. Typical of
     * repeating texture where every period correlates about as well as the true one.
     */
    AMBIGUOUS,

    /** A tile could not be read. Registration never propagates I/O failures. */
    READ_FAILED,

    /** The nominal overlap band is too small to correlate (or the tiles do not overlap at all). */
    NO_OVERLAP,

    /**
     * The edge survived the pairwise gates but disagreed with the global solution by more than the
     * robust residual threshold, and was removed by the solver's iterative outlier pass.
     */
    OUTLIER_IRLS
}
