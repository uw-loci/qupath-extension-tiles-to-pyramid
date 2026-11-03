package qupath.ext.basicstitching.registration;

import java.util.Map;

/**
 * One channel's tiles as registration reads them: which file and page holds each tile's plane, and
 * the weight its pixels carry in a projection.
 *
 * <p>Tiles are looked up by {@link TileNode#filename()} of the grid being solved, the key that
 * identifies one grid position across sibling subdirectories.
 *
 * @param name subdirectory name, for logs and the solution header
 * @param tileByFilename grid tile filename to this channel's tile at that position; its file and
 *     page are what is read
 * @param scale multiplier applied to this channel's pixels; 1 for a channel read on its own, and
 *     {@code 1 / (white - black)} for a channel inside a normalized projection
 */
public record RegistrationChannel(String name, Map<String, TileNode> tileByFilename, double scale) {

    public RegistrationChannel {
        tileByFilename = Map.copyOf(tileByFilename);
        if (!(scale > 0) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale must be positive and finite, got " + scale);
        }
    }

    /**
     * @param filename grid tile filename
     * @return this channel's tile there (file and page to read), or null if the channel has none
     */
    public TileNode tileFor(String filename) {
        return tileByFilename.get(filename);
    }

    /** @return a copy carrying a different scale. */
    public RegistrationChannel withScale(double newScale) {
        return new RegistrationChannel(name, tileByFilename, newScale);
    }
}
