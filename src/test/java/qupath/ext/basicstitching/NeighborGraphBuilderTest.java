package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder.EdgePair;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder.NeighborGraph;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileNode;

class NeighborGraphBuilderTest {

    // PPM 20x on 2026-11-06: 2064x1544 tiles at 10% overlap on a grid rotated about 0.105 deg on the
    // stage (column step 322.163 x 0.592 um, row step -0.443 x 240.998 um, at 0.1732 um/px).
    private static final int TILE_W = 2064;
    private static final int TILE_H = 1544;
    private static final double[] COL = {322.163 / 0.1732, 0.592 / 0.1732};
    private static final double[] ROW = {-0.443 / 0.1732, 240.998 / 0.1732};

    private interface Mask {
        boolean has(int c, int r);
    }

    private static List<TileNode> grid(int cols, int rows, double[] colStep, double[] rowStep, Mask mask, long seed) {
        Random rnd = new Random(seed);
        List<TileNode> tiles = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!mask.has(c, r)) {
                    continue;
                }
                // A few pixels of stage jitter, as real positions have.
                double x = c * colStep[0] + r * rowStep[0] + (rnd.nextDouble() - 0.5) * 6;
                double y = c * colStep[1] + r * rowStep[1] + (rnd.nextDouble() - 0.5) * 6;
                tiles.add(new TileNode(tiles.size() + ".tif", null, x, y, TILE_W, TILE_H));
            }
        }
        return tiles;
    }

    /** Tiles joined by candidate edges, in the piece that holds tile 0. */
    private static int piece(int n, List<EdgePair> edges) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (EdgePair e : edges) {
            parent[find(parent, e.i())] = find(parent, e.j());
        }
        int root = find(parent, 0);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == root) {
                count++;
            }
        }
        return count;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            i = parent[i] = parent[parent[i]];
        }
        return i;
    }

    @Test
    void slightlyRotatedGridIsOneConnectedGraphWithTheTrueStep() {
        int cols = 45;
        int rows = 53;
        List<TileNode> tiles = grid(cols, rows, COL, ROW, (c, r) -> true, 1);

        NeighborGraph graph = NeighborGraphBuilder.build(tiles, RegistrationSettings.defaults());

        assertEquals(COL[0], graph.geometry().stepXPx(), 2.0, "column step");
        assertEquals(ROW[1], graph.geometry().stepYPx(), 2.0, "row step");
        assertEquals(0.099, graph.geometry().overlapFracX(), 0.002);
        assertEquals(0.099, graph.geometry().overlapFracY(), 0.002);
        assertEquals(2 * cols * rows - cols - rows, graph.edges().size(), "every neighbor pair");
        assertEquals(tiles.size(), piece(tiles.size(), graph.edges()), "one piece, not blocks");
    }

    @Test
    void irregularTissueOnARotatedGridConnectsEveryAdjacentPair() {
        // An oval of tissue: rows of different lengths and starts, as a real region has.
        int cols = 60;
        int rows = 50;
        Mask oval = (c, r) -> {
            double dx = (c - 29.5) / 30.0;
            double dy = (r - 24.5) / 25.0;
            return dx * dx + dy * dy <= 1.0;
        };
        List<TileNode> tiles = grid(cols, rows, COL, ROW, oval, 2);
        int expected = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (oval.has(c, r)) {
                    expected += (oval.has(c + 1, r) ? 1 : 0) + (oval.has(c, r + 1) ? 1 : 0);
                }
            }
        }

        NeighborGraph graph = NeighborGraphBuilder.build(tiles, RegistrationSettings.defaults());

        assertEquals(COL[0], graph.geometry().stepXPx(), 2.0);
        assertEquals(expected, graph.edges().size());
        assertEquals(tiles.size(), piece(tiles.size(), graph.edges()));
        for (EdgePair e : graph.edges()) {
            TileNode a = tiles.get(e.i());
            TileNode b = tiles.get(e.j());
            double along = e.horizontal() ? b.xPx() - a.xPx() : b.yPx() - a.yPx();
            double step = e.horizontal() ? COL[0] : ROW[1];
            assertEquals(step, along, 10.0, "an edge joins adjacent tiles only");
        }
    }

    @Test
    void missingTileDoesNotJoinTilesTwoStepsApart() {
        double[] col = {1800, 0};
        double[] row = {0, 1300};
        List<TileNode> tiles = grid(3, 1, col, row, (c, r) -> c != 1, 3);

        NeighborGraph graph = NeighborGraphBuilder.build(tiles, RegistrationSettings.defaults());

        assertTrue(graph.edges().isEmpty(), "a 3600 px gap is not a neighbor pair: " + graph.edges());
    }

    @Test
    void singleRowHasHorizontalEdgesOnly() {
        double[] col = {1800, 0};
        double[] row = {0, 1300};
        List<TileNode> tiles = grid(5, 1, col, row, (c, r) -> true, 4);

        NeighborGraph graph = NeighborGraphBuilder.build(tiles, RegistrationSettings.defaults());

        assertEquals(4, graph.edges().size());
        assertTrue(graph.edges().stream().allMatch(EdgePair::horizontal));
        assertEquals(0, graph.geometry().stepYPx());
        assertEquals(1800, graph.geometry().stepXPx(), 4.0);
    }

    @Test
    void axisAlignedGridKeepsItsStepAndEdges() {
        double[] col = {1800, 0};
        double[] row = {0, 1300};
        List<TileNode> tiles = grid(6, 4, col, row, (c, r) -> true, 5);

        NeighborGraph graph = NeighborGraphBuilder.build(tiles, RegistrationSettings.defaults());

        assertEquals(1800, graph.geometry().stepXPx(), 4.0);
        assertEquals(1300, graph.geometry().stepYPx(), 4.0);
        assertEquals(2 * 6 * 4 - 6 - 4, graph.edges().size());
    }
}
