package qupath.ext.basicstitching.assembly.direct;

/**
 * Simple blend strategy that overwrites pixels -- last tile written wins.
 * Matches the behavior of the existing SparseImageServer approach where
 * overlapping tile regions simply overwrite each other.
 */
public class OverwriteBlendStrategy implements BlendStrategy {

    @Override
    public float weight(int distFromEdge, int overlapWidth) {
        return 1.0f;
    }

    @Override
    public boolean requiresOverlapDetection() {
        return false;
    }
}
