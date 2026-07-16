package qupath.ext.basicstitching.registration;

/**
 * Measures the true offset between one pair of overlapping tiles from their shared image content.
 *
 * <p>This is the seam where the correlation backend can be swapped. The shipped implementation,
 * {@link CoarseToFineNccRegistrar}, is a bounded normalized-cross-correlation search and needs no
 * dependencies. An FFT phase-correlation backend is the obvious alternative and would slot in here
 * unchanged; it was measured as <i>more</i> arithmetic than the bounded search for our band sizes,
 * so it is not built unless real data argues for it.
 */
public interface PairwiseRegistrar {

    /**
     * Measure one edge.
     *
     * <p>Implementations must never throw: an unmeasurable edge is a rejected {@link
     * EdgeMeasurement}, not an exception. The caller solves around rejected edges.
     *
     * @param edge the pair being measured
     * @param bandA overlap band from the left/upper tile
     * @param bandB overlap band from the right/lower tile
     * @param nominalDxPx nominal X offset of the second tile relative to the first
     * @param nominalDyPx nominal Y offset of the second tile relative to the first
     * @param searchXPx maximum X correction to consider, in pixels; the physical bound
     * @param searchYPx maximum Y correction to consider, in pixels
     * @param settings tuning
     * @return the measurement, accepted or rejected
     */
    EdgeMeasurement measure(
            NeighborGraphBuilder.EdgePair edge,
            OverlapBand bandA,
            OverlapBand bandB,
            double nominalDxPx,
            double nominalDyPx,
            int searchXPx,
            int searchYPx,
            RegistrationSettings settings);
}
