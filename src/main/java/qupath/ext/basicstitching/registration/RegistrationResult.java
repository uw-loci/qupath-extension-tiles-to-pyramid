package qupath.ext.basicstitching.registration;

import java.util.List;
import java.util.Map;

/**
 * The product of a registration solve: a per-tile position correction, plus the audit trail behind
 * it.
 *
 * <p>Deltas are keyed by tile filename because that is the only key that survives the trip to a
 * sibling angle or channel subdirectory, where the same grid position is the same filename but a
 * different list index.
 *
 * @param deltaPxByFilename tile filename to {@code {dxPx, dyPx}} correction relative to nominal
 * @param edges every edge considered, accepted or not; the audit trail
 * @param overlapFracX the X overlap fraction actually used, whether derived or explicit
 * @param overlapFracY the Y overlap fraction actually used
 * @param edgesTotal edges in the neighbour graph
 * @param edgesAccepted edges that survived the gates and the outlier pass
 * @param tilesClamped tiles whose solved correction hit the overlap clamp
 * @param degenerate when true the grid could not be registered (0% overlap, single tile, all edges
 *     rejected) and every delta is zero; the caller should warn but must still stitch
 * @param summary one-line human-readable outcome, for logging
 */
public record RegistrationResult(
        Map<String, double[]> deltaPxByFilename,
        List<EdgeMeasurement> edges,
        double overlapFracX,
        double overlapFracY,
        int edgesTotal,
        int edgesAccepted,
        int tilesClamped,
        boolean degenerate,
        String summary) {

    public RegistrationResult {
        deltaPxByFilename = Map.copyOf(deltaPxByFilename);
        edges = List.copyOf(edges);
    }

    /**
     * A no-op result that leaves every tile at its nominal position.
     *
     * <p>This is what the engine returns for anything it cannot register -- 0% overlap, a single
     * tile, unreadable files, an unexpected failure. Registration is an improvement over nominal,
     * never a precondition for stitching, so the failure mode is "no correction", never "no
     * output".
     *
     * @param nominal the tiles that were to be registered
     * @param why human-readable reason, surfaced in {@link #summary()}
     * @return an identity result
     */
    public static RegistrationResult identity(List<TileNode> nominal, String why) {
        Map<String, double[]> zero = nominal.stream()
                .collect(java.util.stream.Collectors.toMap(TileNode::filename, t -> new double[] {0, 0}, (a, b) -> a));
        return new RegistrationResult(zero, List.of(), 0, 0, 0, 0, 0, true, why);
    }

    /**
     * @param filename tile file name
     * @return the correction for the given tile, or {@code {0, 0}} if this solve does not cover it
     */
    public double[] deltaFor(String filename) {
        double[] d = deltaPxByFilename.get(filename);
        return d == null ? new double[] {0, 0} : new double[] {d[0], d[1]};
    }

    /** @return the largest absolute correction on either axis, in pixels. */
    public double maxAbsDeltaPx() {
        double max = 0;
        for (double[] d : deltaPxByFilename.values()) {
            max = Math.max(max, Math.max(Math.abs(d[0]), Math.abs(d[1])));
        }
        return max;
    }
}
