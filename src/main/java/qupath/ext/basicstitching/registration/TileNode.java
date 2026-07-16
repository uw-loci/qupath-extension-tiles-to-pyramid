package qupath.ext.basicstitching.registration;

import java.io.File;

/**
 * One tile's nominal placement, in the stitched output's pixel frame.
 *
 * <p>Coordinates are deliberately in <b>output-pixel space</b>, not raw stage microns. The
 * strategies apply {@code flipStitchingX}/{@code flipStitchingY} while converting stage microns to
 * pixels, so by the time a tile reaches this record the flip is already resolved. Registering in
 * raw microns would mean re-deriving those signs to apply a correction, which is exactly the class
 * of orientation bug the project's "use the real transform numbers, do not reason about
 * orientation" rule exists to prevent.
 *
 * @param filename tile file name; the key that identifies the same grid position across sibling
 *     angle/channel subdirectories
 * @param file the tile file itself, for region reads
 * @param xPx nominal left edge in output pixels
 * @param yPx nominal top edge in output pixels
 * @param widthPx tile width in pixels
 * @param heightPx tile height in pixels
 */
public record TileNode(String filename, File file, double xPx, double yPx, int widthPx, int heightPx) {

    /** @return nominal right edge (exclusive) in output pixels. */
    public double maxXPx() {
        return xPx + widthPx;
    }

    /** @return nominal bottom edge (exclusive) in output pixels. */
    public double maxYPx() {
        return yPx + heightPx;
    }
}
