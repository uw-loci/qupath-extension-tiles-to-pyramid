package qupath.ext.basicstitching.registration;

import java.util.List;

/**
 * What a registration solve correlates: which subdirectory (angle or channel), or which
 * combination of them.
 *
 * <p>Every sibling subdirectory is captured at the same stage position per tile, so the answer only
 * changes how well the seams can be <i>measured</i>, never what the corrections mean. That is also
 * why measurements taken on different channels can be mixed within one solve: a tile-to-tile offset
 * is the same in every channel, and any constant chromatic shift between channels is common to both
 * tiles of a seam and cancels.
 */
public sealed interface RegistrationReference {

    /**
     * Choose automatically. With several subdirectories, the main channel is the one whose seams
     * match most decisively on a sample, and seams that come out weak on it are re-measured on the
     * other channels (see {@link TileRegistrationEngine}). With one subdirectory there is nothing to
     * choose and this is the same as {@link Single}.
     */
    record Auto() implements RegistrationReference {}

    /**
     * Solve on one subdirectory only.
     *
     * @param subdir the subdirectory name
     */
    record Single(String subdir) implements RegistrationReference {}

    /**
     * Solve on a normalized projection of several subdirectories.
     *
     * <p>Each channel is scaled by one factor for the whole dataset (from a bounded sample of tiles,
     * see {@link ChannelNormalizer}), so a feature looks the same in both tiles of an overlap. Scaling
     * each tile on its own would give the two sides of a seam different brightness, which is exactly
     * what correlation must not see.
     *
     * @param subdirs the subdirectory names to combine; at least one
     */
    record Projection(List<String> subdirs) implements RegistrationReference {
        public Projection {
            subdirs = List.copyOf(subdirs);
            if (subdirs.isEmpty()) {
                throw new IllegalArgumentException("A projection needs at least one subdirectory");
            }
        }
    }

    /** @return the automatic choice. */
    static RegistrationReference auto() {
        return new Auto();
    }
}
