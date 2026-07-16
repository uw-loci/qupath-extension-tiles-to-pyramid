package qupath.ext.basicstitching.registration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the 4-connected neighbour graph over a nominal tile grid, and derives the grid's step and
 * overlap from the positions themselves.
 *
 * <p>Deriving rather than being told the overlap is deliberate. The acquisition's overlap
 * preference can change between acquiring a plate and re-stitching it later, at which point a
 * passed-in value describes a different run than the one on disk. The nominal positions cannot lie.
 * It also means a 0%-overlap grid -- the acute cause of the seams this work exists to fix -- is
 * detected for free rather than needing its own check.
 *
 * <p>Tiles are indexed into grid columns and rows rather than compared pairwise. The acquisition is
 * a regular grid from a motorized stage with known nominal positions, so the grid indices are exact;
 * this is O(n log n) instead of O(n^2), which matters at a few thousand tiles.
 */
public final class NeighborGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(NeighborGraphBuilder.class);

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
     * @param stepXPx median column-to-column step
     * @param stepYPx median row-to-row step
     * @param overlapFracX fraction of tile width shared with the horizontal neighbour
     * @param overlapFracY fraction of tile height shared with the vertical neighbour
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
     * Build the neighbour graph for a nominal grid.
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

        double stepX = medianStep(nominal.stream().map(TileNode::xPx).toList(), tileW);
        double stepY = medianStep(nominal.stream().map(TileNode::yPx).toList(), tileH);

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
        logger.info(
                "Grid geometry: step {}x{} px, tile {}x{} px, overlap {}% x {}%",
                String.format(java.util.Locale.ROOT, "%.1f", stepX),
                String.format(java.util.Locale.ROOT, "%.1f", stepY),
                tileW,
                tileH,
                String.format(java.util.Locale.ROOT, "%.1f", overlapX * 100),
                String.format(java.util.Locale.ROOT, "%.1f", overlapY * 100));

        List<EdgePair> edges = new ArrayList<>();
        Map<Long, Integer> byCell = new HashMap<>();
        double minX = nominal.stream().mapToDouble(TileNode::xPx).min().orElse(0);
        double minY = nominal.stream().mapToDouble(TileNode::yPx).min().orElse(0);

        for (int idx = 0; idx < nominal.size(); idx++) {
            TileNode t = nominal.get(idx);
            int col = stepX > 0 ? (int) Math.round((t.xPx() - minX) / stepX) : 0;
            int row = stepY > 0 ? (int) Math.round((t.yPx() - minY) / stepY) : 0;
            Integer clash = byCell.put(key(col, row), idx);
            if (clash != null) {
                logger.warn(
                        "Tiles {} and {} map to the same grid cell ({}, {}); the grid may be irregular",
                        nominal.get(clash).filename(),
                        t.filename(),
                        col,
                        row);
            }
        }

        for (int idx = 0; idx < nominal.size(); idx++) {
            TileNode t = nominal.get(idx);
            int col = stepX > 0 ? (int) Math.round((t.xPx() - minX) / stepX) : 0;
            int row = stepY > 0 ? (int) Math.round((t.yPx() - minY) / stepY) : 0;

            if (overlapX > 0) {
                Integer right = byCell.get(key(col + 1, row));
                if (right != null) {
                    edges.add(new EdgePair(idx, right, true));
                }
            }
            if (overlapY > 0) {
                Integer below = byCell.get(key(col, row + 1));
                if (below != null) {
                    edges.add(new EdgePair(idx, below, false));
                }
            }
        }

        logger.info("Neighbour graph: {} tiles, {} candidate edges", nominal.size(), edges.size());
        return new NeighborGraph(edges, geometry);
    }

    private static long key(int col, int row) {
        return ((long) col << 32) ^ (row & 0xffffffffL);
    }

    /**
     * Median gap between distinct positions along one axis.
     *
     * <p>Positions within a small tolerance are the same column/row and contribute no gap. The
     * median rather than the mean so a single missing tile (a double-width gap) does not stretch the
     * estimate.
     */
    private static double medianStep(List<Double> positions, int tileSizePx) {
        double tolerance = Math.max(1.0, 0.02 * tileSizePx);
        List<Double> sorted = new ArrayList<>(positions);
        sorted.sort(Double::compareTo);

        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            double gap = sorted.get(i) - sorted.get(i - 1);
            if (gap > tolerance) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 0; // single column or row on this axis
        }
        gaps.sort(Double::compareTo);
        int m = gaps.size() / 2;
        return gaps.size() % 2 == 1 ? gaps.get(m) : 0.5 * (gaps.get(m - 1) + gaps.get(m));
    }
}
