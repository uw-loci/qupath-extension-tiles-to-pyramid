package qupath.ext.basicstitching.registration;

import java.io.File;
import java.util.Map;

/**
 * One channel's tiles as registration reads them: where each tile's file is, and the weight its
 * pixels carry in a projection.
 *
 * <p>Tiles are looked up by {@link TileNode#filename()}, the key that identifies one grid position
 * across sibling subdirectories.
 *
 * @param name subdirectory name, for logs and the solution header
 * @param fileByFilename tile filename to that channel's file for it
 * @param scale multiplier applied to this channel's pixels; 1 for a channel read on its own, and
 *     {@code 1 / (white - black)} for a channel inside a normalized projection
 */
public record RegistrationChannel(String name, Map<String, File> fileByFilename, double scale) {

    public RegistrationChannel {
        fileByFilename = Map.copyOf(fileByFilename);
        if (!(scale > 0) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale must be positive and finite, got " + scale);
        }
    }

    /**
     * @param filename tile filename
     * @return this channel's file for that tile, or null if the channel has no such tile
     */
    public File fileFor(String filename) {
        return fileByFilename.get(filename);
    }

    /** @return a copy carrying a different scale. */
    public RegistrationChannel withScale(double newScale) {
        return new RegistrationChannel(name, fileByFilename, newScale);
    }
}
