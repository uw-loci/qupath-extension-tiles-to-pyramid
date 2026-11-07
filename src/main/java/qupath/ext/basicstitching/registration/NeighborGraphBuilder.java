package qupath.ext.basicstitching.registration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the 4-connected neighbor graph over a nominal tile grid, and derives the grid's step and
 * overlap from the positions themselves.
 *
 * <p>Deriving rather than being told the overlap is deliberate. The acquisition's overlap
 * preference can change between acquiring a plate and re-stitching it later, at which point a
 * passed-in value describes a different run than the one on disk. The nominal positions cannot lie.
 * It also means a 0%-overlap grid -- the acute cause of the seams this work exists to fix -- is
 * detected for free rather than needing its own check.
 *
 * <p>The grid need not be aligned with the image axes. A grid laid out on a slide that sits slightly
 * rotated on the stage is rotated in stage coordinates too, so a column's X drifts by a few hundred
 * pixels from its top to its bottom. The grid is therefore described by two step VECTORS, measured
 * from each tile's nearest right and lower neighbors, and every tile is indexed by solving for its
 * (column, row) in that lattice. Indexing by X and Y separately undercounts the step on such a grid,
 * skips an index every dozen or so columns and rows, and cuts the graph into blocks that never
 * register to each other.
 *
 * <p>Neighbors are found through a hash of tile-sized cells, so building the graph is O(n).
 */
public final class NeighborGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(NeighborGraphBuilder.class);

    /** A neighbor step is searched between these fractions of the tile size along its axis. */
    private static final double MIN_STEP_FRACTION = 0.25;

    private static final double MAX_STEP_FRACTION = 1.5;

    /** ...and within this fraction of the tile size across it. */
    private static final double MAX_CROSS_FRACTION = 0.5;

    private NeighborGraphBuilder() {}

    /**
     * One candidate registration: two tiles whose nominal footprints overlap.
     *
     * @param i index of the left/upper tile in the node list
     * @param j index of the right/lower tile
     * @param horizontal true for a left-right pair, false for a top-bottom pair
     */
    public record EdgePair(int i, int j, boolean horizontal) {}

    /**
     * The grid's measured geometry.
     *
     * @param stepXPx column-to-column step along X (the X component of the column step vector)
     * @param stepYPx row-to-row step along Y (the Y component of the row step vector)
     * @param overlapFracX fraction of tile width shared with the horizontal neighbor
     * @param overlapFracY fraction of tile height shared with the vertical neighbor
     */
    public record GridGeometry(double stepXPx, double stepYPx, double overlapFracX, double overlapFracY) {

        /** @return the horizontal overlap band width in pixels. */
        public int overlapXPx(int tileWidthPx) {
            return (int) Math.round(overlapFracX * tileWidthPx);
        }

        /** @return the vertical overlap band height in pixels. */
        public int overlapYPx(int tileHeightPx) {
            return (int) Math.round(overlapFracY * tileHeightPx);
        }

        /** @return whether either axis has too little overlap to correlate. */
        public boolean degenerate() {
            return overlapFracX <= 0 && overlapFracY <= 0;
        }
    }

    /**
     * The graph plus the geometry it was derived from.
     *
     * @param edges candidate registrations
     * @param geometry the measured grid step and overlap
     */
    public record NeighborGraph(List<EdgePair> edges, GridGeometry geometry) {}

    /**
     * Build the neighbor graph for a nominal grid.
     *
     * @param nominal tiles at their nominal positions
     * @param settings tuning; supplies the optional explicit overlap override
     * @return the graph, possibly with no edges if the grid is a single tile or has no overlap
     */
    public static NeighborGraph build(List<TileNode> nominal, RegistrationSettings settings) {
        if (nominal.size() < 2) {
            return new NeighborGraph(List.of(), new GridGeometry(0, 0, 0, 0));
        }

        int tileW = nominal.get(0).widthPx();
        int tileH = nominal.get(0).heightPx();

        // Column and row step vectors; null when the grid has a single column / row.
        double[] colStep = medianNeighborStep(nominal, tileW, tileH, true);
        double[] rowStep = medianNeighborStep(nominal, tileW, tileH, false);

        double stepX = colStep == null ? 0 : colStep[0];
        double stepY = rowStep == null ? 0 : rowStep[1];

        double overlapX = settings.hasExplicitOverlapX()
                ? settings.overlapPercentX() / 100.0
                : (stepX > 0 ? 1.0 - stepX / tileW : 0);
        double overlapY = settings.hasExplicitOverlapY()
                ? settings.overlapPercentY() / 100.0
                : (stepY > 0 ? 1.0 - stepY / tileH : 0);

        // A tiny negative overlap is round-off on an edge-to-edge grid, not a gap.
        overlapX = Math.max(0, overlapX);
        overlapY = Math.max(0, overlapY);

        GridGeometry geometry = new GridGeometry(stepX, stepY, overlapX, overlapY);
        double rotationDeg = colStep != null
                ? Math.toDegrees(Math.atan2(colStep[1], colStep[0]))
                : rowStep != null ? Math.toDegrees(Math.atan2(-rowStep[0], rowStep[1])) : 0;
        logger.info(
                "Grid geometry: step {}x{} px, tile {}x{} px, overlap {}% x {}%, grid rotated {} deg",
                String.format(Locale.ROOT, "%.1f", stepX),
                String.format(Locale.ROOT, "%.1f", stepY),
                tileW,
                tileH,
                String.format(Locale.ROOT, "%.1f", overlapX * 100),
                String.format(Locale.ROOT, "%.1f", overlapY * 100),
                String.format(Locale.ROOT, "%.3f", rotationDeg));

        // A missing axis gets a stand-in vector perpendicular to the other, sized so every tile
        // lands in index 0 along it. It produces no edges: the overlap on that axis is zero.
        double[] u = colStep != null
                ? colStep
                : rowStep != null ? new double[] {rowStep[1], -rowStep[0]} : new double[] {tileW, 0};
        double[] v = rowStep != null ? rowStep : new double[] {-u[1], u[0]};
        double det = u[0] * v[1] - u[1] * v[0];

        TileNode origin = nominal.get(0);
        int[] col = new int[nominal.size()];
        int[] row = new int[nominal.size()];
        Map<Long, Integer> byCell = new HashMap<>();
        for (int idx = 0; idx < nominal.size(); idx++) {
            TileNode t = nominal.get(idx);
            double dx = t.xPx() - origin.xPx();
            double dy = t.yPx() - origin.yPx();
            col[idx] = (int) Math.round((dx * v[1] - dy * v[0]) / det);
            row[idx] = (int) Math.round((u[0] * dy - u[1] * dx) / det);
            Integer clash = byCell.put(key(col[idx], row[idx]), idx);
            if (clash != null) {
                logger.warn(
                        "Tiles {} and {} map to the same grid cell ({}, {}); the grid may be irregular",
                        nominal.get(clash).filename(),
                        t.filename(),
                        col[idx],
                        row[idx]);
            }
        }

        List<EdgePair> edges = new ArrayList<>();
        for (int idx = 0; idx < nominal.size(); idx++) {
            if (overlapX > 0) {
                Integer right = byCell.get(key(col[idx] + 1, row[idx]));
                if (right != null) {
                    edges.add(new EdgePair(idx, right, true));
                }
            }
            if (overlapY > 0) {
                Integer below = byCell.get(key(col[idx], row[idx] + 1));
                if (below != null) {
                    edges.add(new EdgePair(idx, below, false));
                }
            }
        }

        logger.info("Neighbor graph: {} tiles, {} candidate edges", nominal.size(), edges.size());
        return new NeighborGraph(edges, geometry);
    }

    private static long key(int col, int row) {
        return ((long) col << 32) ^ (row & 0xffffffffL);
    }

    /**
     * Median vector from each tile to its nearest neighbor on one side.
     *
     * <p>For the column step the neighbor is the closest tile to the right whose X offset lies
     * between {@link #MIN_STEP_FRACTION} and {@link #MAX_STEP_FRACTION} of the tile width and whose Y
     * offset is under {@link #MAX_CROSS_FRACTION} of the tile height; the row step is the same with
     * the axes swapped. The upper bound keeps a tile whose immediate neighbor is missing from
     * reporting a double step. The median, so a few irregular tiles do not move the estimate.
     *
     * @param horizontal true for the column step (right neighbor), false for the row step (lower)
     * @return {dx, dy} in pixels, or null when no tile has such a neighbor
     */
    private static double[] medianNeighborStep(List<TileNode> nominal, int tileW, int tileH, boolean horizontal) {
        double along = horizontal ? tileW : tileH;
        double across = horizontal ? tileH : tileW;
        double minAlong = MIN_STEP_FRACTION * along;
        double maxAlong = MAX_STEP_FRACTION * along;
        double maxAcross = MAX_CROSS_FRACTION * across;

        // Buckets of one tile size: a neighbor within the window is at most two buckets away
        // along the axis and one across it.
        Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < nominal.size(); i++) {
            TileNode t = nominal.get(i);
            long k = key((int) Math.floor(t.xPx() / tileW), (int) Math.floor(t.yPx() / tileH));
            buckets.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        }

        List<Double> dAlong = new ArrayList<>();
        List<Double> dAcross = new ArrayList<>();
        for (int i = 0; i < nominal.size(); i++) {
            TileNode t = nominal.get(i);
            int bx = (int) Math.floor(t.xPx() / tileW);
            int by = (int) Math.floor(t.yPx() / tileH);
            double bestAlong = Double.POSITIVE_INFINITY;
            double bestAcross = 0;
            for (int a = 0; a <= 2; a++) {
                for (int c = -1; c <= 1; c++) {
                    List<Integer> cell = buckets.get(horizontal ? key(bx + a, by + c) : key(bx + c, by + a));
                    if (cell == null) {
                        continue;
                    }
                    for (int j : cell) {
                        TileNode o = nominal.get(j);
                        double da = horizontal ? o.xPx() - t.xPx() : o.yPx() - t.yPx();
                        double dc = horizontal ? o.yPx() - t.yPx() : o.xPx() - t.xPx();
                        if (da > minAlong && da < maxAlong && Math.abs(dc) < maxAcross && da < bestAlong) {
                            bestAlong = da;
                            bestAcross = dc;
                        }
                    }
                }
            }
            if (bestAlong < Double.POSITIVE_INFINITY) {
                dAlong.add(bestAlong);
                dAcross.add(bestAcross);
            }
        }
        if (dAlong.isEmpty()) {
            return null;
        }
        double ma = median(dAlong);
        double mc = median(dAcross);
        return horizontal ? new double[] {ma, mc} : new double[] {mc, ma};
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int m = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(m) : 0.5 * (sorted.get(m - 1) + sorted.get(m));
    }
}
