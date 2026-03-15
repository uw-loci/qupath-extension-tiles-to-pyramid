package qupath.ext.basicstitching.assembly.direct;

/**
 * Strategy for blending overlapping tile regions during compositing.
 * Implementations provide per-pixel weights based on distance from tile edges.
 * <p>
 * The initial implementation ({@link OverwriteBlendStrategy}) uses last-writer-wins
 * semantics matching the existing SparseImageServer behavior. Future implementations
 * can provide linear or Gaussian blending for smoother overlap transitions.
 */
public interface BlendStrategy {

    /**
     * Compute the blend weight for a pixel at a given distance from the tile edge.
     *
     * @param distFromEdge Distance from the nearest tile edge in pixels
     * @param overlapWidth Width of the overlap zone in pixels
     * @return Weight in [0.0, 1.0]
     */
    float weight(int distFromEdge, int overlapWidth);

    /**
     * Whether this strategy needs overlap zone detection between adjacent tiles.
     *
     * @return true if the compositor should compute overlap zones before blending
     */
    boolean requiresOverlapDetection();
}
