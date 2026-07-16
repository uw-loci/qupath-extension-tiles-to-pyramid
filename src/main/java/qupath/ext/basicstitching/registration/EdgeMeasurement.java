package qupath.ext.basicstitching.registration;

/**
 * The result of registering one pair of overlapping tiles, plus the audit trail of why it was or
 * was not used.
 *
 * <p>Offsets are {@code j} relative to {@code i}, in output pixels: the solver's residual for this
 * edge is {@code (p_j - p_i) - (dxPx, dyPx)}.
 *
 * @param i index of the first tile in the engine's node list
 * @param j index of the second tile
 * @param dxPx measured X offset of tile {@code j} relative to tile {@code i}
 * @param dyPx measured Y offset of tile {@code j} relative to tile {@code i}
 * @param nominalDxPx the same offset implied by the nominal stage positions
 * @param nominalDyPx the same offset implied by the nominal stage positions
 * @param ncc peak normalized cross-correlation; the confidence of this measurement
 * @param reject {@link RejectReason#NONE} when accepted, otherwise why it was dropped
 */
public record EdgeMeasurement(
        int i,
        int j,
        double dxPx,
        double dyPx,
        double nominalDxPx,
        double nominalDyPx,
        double ncc,
        RejectReason reject) {

    /** @return whether this edge contributes to the global solve. */
    public boolean accepted() {
        return reject == RejectReason.NONE;
    }

    /**
     * Weight for the global solve.
     *
     * <p>{@code max(0, ncc - minNcc)^2}. Subtracting the threshold means a barely-passing edge
     * contributes nearly nothing rather than arriving at full strength the instant it clears the
     * gate; squaring sharpens the preference for confident edges.
     *
     * @param minNcc the acceptance threshold the measurement was gated on
     * @return a non-negative weight; zero for rejected edges
     */
    public double weight(double minNcc) {
        if (!accepted()) {
            return 0;
        }
        double excess = Math.max(0, ncc - minNcc);
        return excess * excess;
    }

    /** @return the correction this edge implies relative to nominal, in output pixels. */
    public double deltaFromNominalXPx() {
        return dxPx - nominalDxPx;
    }

    /** @return the correction this edge implies relative to nominal, in output pixels. */
    public double deltaFromNominalYPx() {
        return dyPx - nominalDyPx;
    }

    /** @return a copy of this measurement marked with the given rejection reason. */
    public EdgeMeasurement rejectedAs(RejectReason reason) {
        return new EdgeMeasurement(i, j, dxPx, dyPx, nominalDxPx, nominalDyPx, ncc, reason);
    }
}
