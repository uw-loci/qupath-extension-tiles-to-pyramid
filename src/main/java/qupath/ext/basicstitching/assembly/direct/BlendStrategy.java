package qupath.ext.basicstitching.assembly.direct;

/**
 * How a single tile's contribution is weighted where tiles overlap.
 *
 * <p>The compositor asks each contributing tile for a weight per pixel, accumulates
 * {@code weight * sample}, and divides by the summed weight. A strategy therefore describes only its
 * own tile's confidence in a pixel; it never sees the neighbour, and the normalisation makes the
 * weights' absolute scale irrelevant.
 *
 * <p>The implementations are the constants of {@link OverlapBlend}, which is also where the choice
 * between them is documented.
 */
public interface BlendStrategy {

    /**
     * Weight for a pixel at a given distance inside its own tile.
     *
     * <p>Called once per row and once per column of the region being composited, not once per pixel:
     * the taper is separable, so the caller multiplies an X weight by a Y weight.
     *
     * @param distFromEdge distance in pixels from the nearest tile edge along one axis, counting the
     *     outermost pixel as 1
     * @param overlapWidth width of the overlap band along that axis, or 0 if none could be measured
     * @return a weight in (0, 1]; never 0, or a pixel covered by exactly one tile would normalise to
     *     nothing
     */
    float weight(int distFromEdge, int overlapWidth);

    /**
     * Whether compositing must accumulate weighted contributions rather than overwrite.
     *
     * <p>Answering false lets the compositor keep its direct raster-copy path, which allocates no
     * accumulator and reproduces the output byte for byte. Only answer true if {@link #weight} varies
     * -- a constant weight blends to the same image at real cost.
     *
     * @return true to composite through a weighted accumulator
     */
    boolean requiresOverlapDetection();
}
