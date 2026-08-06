package qupath.ext.basicstitching.assembly.direct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.regions.ImageRegion;

/**
 * Grid-based spatial index for fast tile lookup by region.
 * <p>
 * Divides the full image extent into cells matching the output chunk size.
 * Each cell stores references to tiles that overlap it, enabling O(1) queries
 * instead of the O(N) linear scan used by SparseImageServer.
 * <p>
 * All positions are origin-translated so the image starts at (0, 0).
 * Use {@link #getOriginX()} and {@link #getOriginY()} to translate back
 * to original tile coordinates.
 */
public class TileSpatialIndex {

    private static final Logger logger = LoggerFactory.getLogger(TileSpatialIndex.class);

    private final List<TileMapping>[][] grid;
    private final int gridCols;
    private final int gridRows;
    private final int chunkSize;
    private final int imageWidth;
    private final int imageHeight;
    private final int originX;
    private final int originY;
    private final int overlapPxX;
    private final int overlapPxY;

    /**
     * Build a spatial index from tile mappings.
     *
     * @param mappings All tile mappings (positions from strategy output)
     * @param chunkSize Output chunk size in pixels (used for grid cell size)
     */
    @SuppressWarnings("unchecked")
    public TileSpatialIndex(List<TileMapping> mappings, int chunkSize) {
        this.chunkSize = chunkSize;

        // Compute bounding box from all tile regions
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (TileMapping m : mappings) {
            int x = m.region.getX();
            int y = m.region.getY();
            int w = m.region.getWidth();
            int h = m.region.getHeight();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        this.originX = minX;
        this.originY = minY;
        this.imageWidth = maxX - minX;
        this.imageHeight = maxY - minY;

        this.gridCols = (int) Math.ceil((double) imageWidth / chunkSize);
        this.gridRows = (int) Math.ceil((double) imageHeight / chunkSize);

        logger.info(
                "Spatial index: {}x{} image, {}x{} grid (chunk={}), origin=({},{})",
                imageWidth,
                imageHeight,
                gridCols,
                gridRows,
                chunkSize,
                originX,
                originY);

        // Initialize grid cells
        grid = new List[gridRows][gridCols];
        for (int r = 0; r < gridRows; r++) {
            for (int c = 0; c < gridCols; c++) {
                grid[r][c] = new ArrayList<>(4);
            }
        }

        // Populate grid: for each tile, add to all overlapping cells
        for (TileMapping m : mappings) {
            int tx = m.region.getX() - originX;
            int ty = m.region.getY() - originY;
            int tw = m.region.getWidth();
            int th = m.region.getHeight();

            int startCol = Math.max(0, tx / chunkSize);
            int endCol = Math.min(gridCols - 1, (tx + tw - 1) / chunkSize);
            int startRow = Math.max(0, ty / chunkSize);
            int endRow = Math.min(gridRows - 1, (ty + th - 1) / chunkSize);

            for (int r = startRow; r <= endRow; r++) {
                for (int c = startCol; c <= endCol; c++) {
                    grid[r][c].add(m);
                }
            }
        }

        // Log grid statistics
        int maxPerCell = 0;
        int totalEntries = 0;
        for (int r = 0; r < gridRows; r++) {
            for (int c = 0; c < gridCols; c++) {
                maxPerCell = Math.max(maxPerCell, grid[r][c].size());
                totalEntries += grid[r][c].size();
            }
        }
        logger.info(
                "Spatial index populated: {} tiles, max {} per cell, {} total entries",
                mappings.size(),
                maxPerCell,
                totalEntries);

        this.overlapPxX = measureOverlap(true);
        this.overlapPxY = measureOverlap(false);
        logger.info("Measured tile overlap from placed positions: {} x {} px", overlapPxX, overlapPxY);
    }

    /**
     * Query tiles that may overlap the given region.
     * Coordinates are in origin-translated space (0-based).
     *
     * @param x Region X position
     * @param y Region Y position
     * @param width Region width
     * @param height Region height
     * @return List of tile mappings that may overlap (typically 1-4)
     */
    public List<TileMapping> query(int x, int y, int width, int height) {
        Set<TileMapping> result = new LinkedHashSet<>();

        int startCol = Math.max(0, x / chunkSize);
        int endCol = Math.min(gridCols - 1, (x + width - 1) / chunkSize);
        int startRow = Math.max(0, y / chunkSize);
        int endRow = Math.min(gridRows - 1, (y + height - 1) / chunkSize);

        for (int r = startRow; r <= endRow; r++) {
            for (int c = startCol; c <= endCol; c++) {
                result.addAll(grid[r][c]);
            }
        }

        return new ArrayList<>(result);
    }

    /** Full image width in pixels (origin-translated). */
    public int getImageWidth() {
        return imageWidth;
    }

    /** Full image height in pixels (origin-translated). */
    public int getImageHeight() {
        return imageHeight;
    }

    /** X origin offset -- subtract from tile coordinates to get 0-based positions. */
    public int getOriginX() {
        return originX;
    }

    /** Y origin offset -- subtract from tile coordinates to get 0-based positions. */
    public int getOriginY() {
        return originY;
    }

    /** Number of grid columns. */
    public int getGridCols() {
        return gridCols;
    }

    /** Number of grid rows. */
    public int getGridRows() {
        return gridRows;
    }

    /**
     * Median width of the horizontal overlap between side-by-side tiles, in pixels.
     *
     * @return the measured overlap, or 0 if the layout has none to measure
     */
    public int getOverlapPxX() {
        return overlapPxX;
    }

    /**
     * Median height of the vertical overlap between stacked tiles, in pixels.
     *
     * @return the measured overlap, or 0 if the layout has none to measure
     */
    public int getOverlapPxY() {
        return overlapPxY;
    }

    /**
     * Measure the overlap between neighbouring tiles from where they are actually placed.
     *
     * <p>Measured rather than assumed, because the positions reaching this index may already carry
     * registration corrections: the overlap a feather has to span is the one in the output, not the
     * nominal percentage the operator typed at acquisition time. Taking the median makes it immune to
     * the tiles at the mosaic edge and to any single badly-corrected tile.
     *
     * <p>Only pairs that are genuine grid neighbours count -- offset along the axis by less than a
     * tile, and aligned across it by more than half a tile. Diagonal neighbours share a corner rather
     * than a band, and including them would report an overlap far narrower than the real one.
     *
     * <p>Pairs are drawn from within index cells rather than from the whole tile list. Two tiles that
     * overlap necessarily share the cell their overlap band falls in, so nothing is missed, and the
     * cost stays linear in tile count instead of quadratic.
     *
     * @param axisX true to measure the horizontal overlap, false for the vertical
     * @return the median overlap in pixels, or 0 if no neighbouring pair could be found
     */
    private int measureOverlap(boolean axisX) {
        List<Integer> overlaps = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int r = 0; r < gridRows; r++) {
            for (int c = 0; c < gridCols; c++) {
                List<TileMapping> cell = grid[r][c];
                for (int i = 0; i < cell.size(); i++) {
                    for (int j = i + 1; j < cell.size(); j++) {
                        TileMapping ma = cell.get(i);
                        TileMapping mb = cell.get(j);
                        // A pair can share several cells; count it once.
                        long key = (long) System.identityHashCode(ma) << 32 ^ System.identityHashCode(mb);
                        if (!seen.add(key)) {
                            continue;
                        }
                        int overlap = neighbourOverlap(ma.region, mb.region, axisX);
                        if (overlap > 0) {
                            overlaps.add(overlap);
                        }
                    }
                }
            }
        }
        if (overlaps.isEmpty()) {
            return 0;
        }
        Collections.sort(overlaps);
        return overlaps.get(overlaps.size() / 2);
    }

    /**
     * The overlap between two tiles along one axis, or 0 if they are not grid neighbours on it.
     *
     * <p>Neighbours are offset along the axis by less than a tile and aligned across it by more than
     * half a tile. Diagonal neighbours share only a corner; counting them would report an overlap far
     * narrower than the band a feather actually has to span.
     */
    private static int neighbourOverlap(ImageRegion a, ImageRegion b, boolean axisX) {
        if (a.getZ() != b.getZ() || a.getT() != b.getT()) {
            return 0;
        }
        int along = axisX ? Math.abs(a.getX() - b.getX()) : Math.abs(a.getY() - b.getY());
        int across = axisX ? Math.abs(a.getY() - b.getY()) : Math.abs(a.getX() - b.getX());
        int size = axisX ? Math.min(a.getWidth(), b.getWidth()) : Math.min(a.getHeight(), b.getHeight());
        int acrossSize = axisX ? Math.min(a.getHeight(), b.getHeight()) : Math.min(a.getWidth(), b.getWidth());
        if (along > 0 && along < size && across * 2 < acrossSize) {
            return size - along;
        }
        return 0;
    }
}
