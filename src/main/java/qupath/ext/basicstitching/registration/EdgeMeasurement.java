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
     * Weight for the global solve: {@code ncc^2}, the squared correlation, i.e. the fraction of
     * variance the match explains.
     *
     * <p>This deliberately does <b>not</b> subtract the acceptance threshold first. The previous
     * {@code (ncc - minNcc)^2} collapsed to zero exactly at the gate, so a barely-passing edge
     * carried about a fiftieth of a typical edge's influence and about a hundred-and-seventieth of a
     * strong one's. Its two tiles were then positioned by the surrounding stronger edges instead of
     * by their own overlap. Measured on a real acquisition: an edge at {@code ncc = 0.34} correctly
     * measured a (-2, -10) px correction, and the solve applied (+25, +16) -- 37 px away, in the
     * opposite direction, leaving visible duplication at a seam whose own measurement had been
     * right all along. Suppressing a weak-but-correct measurement is the same failure as cutting it
     * (see the outlier pass); this formula only removed 98% of it rather than 100%.
     *
     * <p>Weighting weak edges realistically is safe because it is no longer the defence against bad
     * matches: the solver's robust reweighting already down-weights any edge that disagrees with the
     * global consensus, whatever its correlation. Punishing low confidence here as well was double
     * counting, and it silently penalised exactly the low-contrast seams that most need a
     * measurement.
     *
     * @param minNcc the acceptance threshold the measurement was gated on; retained for callers and
     *     for the rejection logic, no longer subtracted from the weight
     * @return a non-negative weight; zero for rejected edges
     */
    public double weight(double minNcc) {
        if (!accepted()) {
            return 0;
        }
        double confidence = Math.max(0, ncc);
        return confidence * confidence;
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
