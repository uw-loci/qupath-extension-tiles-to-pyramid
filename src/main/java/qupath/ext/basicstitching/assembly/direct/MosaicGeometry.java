package qupath.ext.basicstitching.assembly.direct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.regions.ImageRegion;

/**
 * Checks one image's tile layout for the signs of a pixel size that does not belong to its tiles.
 *
 * <p>A wrong pixel size cannot fail a stitch. Positions are micrometers divided by the pixel size,
 * so too large a value packs the tiles together (stitching a polarized set at 0.653 um/px instead
 * of 0.1732 gave 76% overlap where the acquisition used 10%) and too small a value spreads them
 * apart with empty bands between them. Either way a mosaic is written. The geometry is the only
 * evidence.
 */
public final class MosaicGeometry {

    /**
     * The largest overlap an acquisition plausibly uses. Real ones run 5-20%; a stage would have
     * to be moving less than half a field per step to exceed this on purpose.
     */
    static final double IMPLAUSIBLE_OVERLAP_FRACTION = 0.45;

    /**
     * The widest empty band between neighboring tiles, as a fraction of a tile, before it is
     * reported. Tiled acquisitions overlap; a gap this wide between most neighbors is what a pixel
     * size too small produces (half the true value turns a 10% overlap into an 80% gap).
     */
    static final double IMPLAUSIBLE_GAP_FRACTION = 0.10;

    private MosaicGeometry() {}

    /**
     * What looks wrong with this image's layout, or null.
     *
     * @param mappings the image's tiles, placed
     * @param overlapPxX the median horizontal overlap between neighbors (from {@link TileSpatialIndex})
     * @param overlapPxY the median vertical overlap
     * @param tileWidth a tile's width in pixels
     * @param tileHeight a tile's height in pixels
     * @param pixelSizeMicrons the pixel size the positions were converted with
     * @return a sentence for the user, or null when the layout looks plausible
     */
    public static String check(
            List<TileMapping> mappings,
            int overlapPxX,
            int overlapPxY,
            int tileWidth,
            int tileHeight,
            double pixelSizeMicrons) {
        if (tileWidth <= 0 || tileHeight <= 0) {
            return null;
        }
        double fx = overlapPxX / (double) tileWidth;
        double fy = overlapPxY / (double) tileHeight;
        if (Math.max(fx, fy) >= IMPLAUSIBLE_OVERLAP_FRACTION) {
            return String.format(
                    Locale.ROOT,
                    "The tiles overlap by %.0f%% x %.0f%%, which no acquisition is set up to do. "
                            + "The usual cause is a pixel size that does not belong to these tiles: "
                            + "%s um/px was used, and too large a value packs the tiles together. "
                            + "The image was still written, but check it before using it.",
                    fx * 100,
                    fy * 100,
                    pixelSizeMicrons);
        }
        double gx = medianGapFraction(mappings, true);
        double gy = medianGapFraction(mappings, false);
        double worst = Math.max(Double.isNaN(gx) ? -1 : gx, Double.isNaN(gy) ? -1 : gy);
        if (worst > IMPLAUSIBLE_GAP_FRACTION) {
            return String.format(
                    Locale.ROOT,
                    "Neighboring tiles are %.0f%% of a tile apart, with empty bands between them. "
                            + "The usual cause is a pixel size that does not belong to these tiles: "
                            + "%s um/px was used, and too small a value spreads the tiles apart. "
                            + "If the tiles were acquired apart on purpose (separate pieces of tissue), "
                            + "ignore this.",
                    worst * 100,
                    pixelSizeMicrons);
        }
        return null;
    }

    /**
     * The same check in a few words for a table cell: "tiles overlap 76%" or "tiles 80% of a tile
     * apart", or null when the layout looks plausible.
     */
    public static String brief(
            List<TileMapping> mappings, int overlapPxX, int overlapPxY, int tileWidth, int tileHeight) {
        if (tileWidth <= 0 || tileHeight <= 0) {
            return null;
        }
        double overlap = Math.max(overlapPxX / (double) tileWidth, overlapPxY / (double) tileHeight);
        if (overlap >= IMPLAUSIBLE_OVERLAP_FRACTION) {
            return String.format(Locale.ROOT, "tiles overlap %.0f%%: pixel size too large?", overlap * 100);
        }
        double gx = medianGapFraction(mappings, true);
        double gy = medianGapFraction(mappings, false);
        double gap = Math.max(Double.isNaN(gx) ? -1 : gx, Double.isNaN(gy) ? -1 : gy);
        if (gap > IMPLAUSIBLE_GAP_FRACTION) {
            return String.format(Locale.ROOT, "tiles %.0f%% of a tile apart: pixel size too small?", gap * 100);
        }
        return null;
    }

    /**
     * The median distance between each tile and its next neighbor along one axis, among tiles in
     * the same row (or column), as a fraction of a tile: negative for an overlap, positive for a
     * gap. Only the first plane is used, since every plane shares the layout.
     *
     * @param axisX true for neighbors left to right, false for top to bottom
     * @return the median fraction, or NaN when fewer than two neighbor pairs exist
     */
    static double medianGapFraction(List<TileMapping> mappings, boolean axisX) {
        if (mappings == null || mappings.size() < 2) {
            return Double.NaN;
        }
        ImageRegion first = mappings.get(0).region;
        List<ImageRegion> plane = new ArrayList<>();
        for (TileMapping m : mappings) {
            if (m.region.getZ() == first.getZ() && m.region.getT() == first.getT()) {
                plane.add(m.region);
            }
        }
        // Rows (for X) are tiles whose positions across the axis differ by less than half a tile.
        Comparator<ImageRegion> acrossOrder = Comparator.comparingInt(r -> axisX ? r.getY() : r.getX());
        Comparator<ImageRegion> alongOrder = Comparator.comparingInt(r -> axisX ? r.getX() : r.getY());
        plane.sort(acrossOrder);
        List<Double> gaps = new ArrayList<>();
        int i = 0;
        while (i < plane.size()) {
            ImageRegion start = plane.get(i);
            int startAcross = axisX ? start.getY() : start.getX();
            int acrossSize = axisX ? start.getHeight() : start.getWidth();
            int j = i;
            while (j < plane.size()) {
                ImageRegion r = plane.get(j);
                int across = axisX ? r.getY() : r.getX();
                if ((across - startAcross) * 2 >= acrossSize) {
                    break;
                }
                j++;
            }
            List<ImageRegion> row = new ArrayList<>(plane.subList(i, j));
            row.sort(alongOrder);
            for (int k = 1; k < row.size(); k++) {
                ImageRegion a = row.get(k - 1);
                ImageRegion b = row.get(k);
                int step = axisX ? b.getX() - a.getX() : b.getY() - a.getY();
                int size = axisX ? a.getWidth() : a.getHeight();
                if (step > 0 && size > 0) {
                    gaps.add((step - size) / (double) size);
                }
            }
            i = j;
        }
        if (gaps.size() < 2) {
            return Double.NaN;
        }
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
    }
}
