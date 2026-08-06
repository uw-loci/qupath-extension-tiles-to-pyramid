package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.registration.RegistrationRequest;
import qupath.ext.basicstitching.registration.RegistrationResult;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileRegistrationEngine;

/**
 * End-to-end tests for the registration engine against real TIFF tiles on disk.
 *
 * <p>These exercise the whole chain -- neighbour graph, region reads, correlation, gates, global
 * solve, clamp -- against a grid whose true displacements are known.
 */
class TileRegistrationEngineTest {

    @TempDir
    Path tempDir;

    private static final int TILE_W = 256;
    private static final int TILE_H = 192;

    /** Single-threaded so failures are deterministic and stack traces readable. */
    private static RegistrationSettings settings() {
        return RegistrationSettings.defaults().withThreads(1);
    }

    private static RegistrationResult register(SyntheticGridFixture.Grid grid) {
        return TileRegistrationEngine.register(new RegistrationRequest("0", grid.nominal(), settings()));
    }

    @Test
    void endToEnd_syntheticGrid_recoversJitter() throws IOException {
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 4, 4, TILE_W, TILE_H, 0.15, 3.0, 42);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "a textured 15%-overlap grid must register: " + result.summary());
        assertTrue(
                result.edgesAccepted() >= 0.9 * result.edgesTotal(),
                "expected most edges to survive, got " + result.summary());

        for (var entry : grid.trueJitterPx().entrySet()) {
            double[] expected = entry.getValue();
            double[] actual = result.deltaFor(entry.getKey());
            assertEquals(expected[0], actual[0], 0.5, "X correction for " + entry.getKey());
            assertEquals(expected[1], actual[1], 0.5, "Y correction for " + entry.getKey());
        }
    }

    @Test
    void endToEnd_noJitter_leavesGridEssentiallyUnmoved() throws IOException {
        // Guards the opposite failure from the one above: registration must not invent corrections
        // for a grid that is already correct.
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, 0.15, 0.0, 7);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate());
        assertTrue(result.maxAbsDeltaPx() <= 1.0, "expected near-zero corrections, got " + result.maxAbsDeltaPx());
    }

    @Test
    void zeroOverlapGrid_isDegenerateNoOp() throws IOException {
        // The acute cause of the seams this work exists to fix. Tiles placed edge to edge share no
        // content, so there is nothing to correlate -- the engine must say so plainly and leave the
        // grid alone rather than fabricate corrections or throw.
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, 0.0, 2.0, 1);

        RegistrationResult result = register(grid);

        assertTrue(result.degenerate(), "0% overlap must be reported as degenerate");
        assertEquals(0, result.maxAbsDeltaPx(), 1e-9, "no tile may move when there is no overlap");
        assertTrue(result.summary().contains("overlap"), "the summary must explain why: " + result.summary());
        assertEquals(9, result.deltaPxByFilename().size(), "every tile still needs an entry, just a zero one");
    }

    @Test
    void singleTile_isDegenerateNoOp() throws IOException {
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 1, 1, TILE_W, TILE_H, 0.15, 0.0, 3);

        RegistrationResult result = register(grid);

        assertTrue(result.degenerate());
        assertEquals(0, result.maxAbsDeltaPx(), 1e-9);
    }

    @Test
    void blankIsland_inheritsNeighbourField_notNominal() throws IOException {
        // The failure this fixes: a blank tile whose edges are all rejected used to be pinned to
        // nominal (0, 0). That is only right when nominal is right. Under a real, smooth correction
        // field -- here a scale-like drift of 6 px/tile -- nominal strands the tile a dozen pixels
        // from where its neighbours put the shared content, which is the worst seam in the mosaic
        // (the on-scope symptom: a doubled edge with a white gap). The tile must instead inherit the
        // field its registered neighbours define.
        SyntheticGridFixture.Grid grid =
                SyntheticGridFixture.write(tempDir, 5, 5, TILE_W, TILE_H, 0.15, 2.0, 6.0, List.of(0), 5);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "the textured majority must still solve: " + result.summary());

        // The blank corner (index 0 -> 1.tif) inherits the mean of its two registered grid
        // neighbours: the tile to its right (index 1 -> 2.tif) and below it (index 5 -> 6.tif).
        double[] blank = result.deltaFor("1.tif");
        double[] right = result.deltaFor("2.tif");
        double[] below = result.deltaFor("6.tif");
        assertEquals(
                (right[0] + below[0]) / 2, blank[0], 1e-6, "blank island X must be its neighbours' mean, not nominal");
        assertEquals((right[1] + below[1]) / 2, blank[1], 1e-6, "blank island Y must be its neighbours' mean");
        assertTrue(
                Math.hypot(blank[0], blank[1]) > 3.0,
                "the field is large here, so nominal would strand the tile; got " + java.util.Arrays.toString(blank));

        // And the textured tiles must genuinely recover the drift, or the test proves nothing about a
        // large field. Tile 5.tif (index 4, a far corner) carries ~12 px of drift on each axis.
        double[] textured = result.deltaFor("5.tif");
        double[] expected = grid.trueJitterPx().get("5.tif");
        assertEquals(expected[0], textured[0], 2.0, "textured tiles must recover the drift field");
        assertEquals(expected[1], textured[1], 2.0);
    }

    @Test
    void blankGapBetweenTwoRegions_rampsAcross_ratherThanStaircasing() throws IOException {
        // The second case the user asked for: not one isolated blank tile, but a run of them between
        // two pieces of tissue -- the empty slide between two sections. Those tiles have to be placed
        // "in the middle" of what surrounds them, which for a span means interpolating between the two
        // sides, not snapping each tile to whichever side happens to be nearer.
        //
        // A single outward wavefront cannot do that: it freezes each tile the first time it is
        // reached, so the left half of the gap all takes the left boundary's value and the right half
        // all takes the right's, giving a staircase with a step in the middle. Relaxing to convergence
        // -- Laplace with the registered tiles as fixed boundary -- gives the linear ramp asserted
        // below. The two are indistinguishable on a single isolated tile and on any symmetric gap,
        // which is why this uses an even-length span and checks every tile in it.
        //
        // One row of 8, textured at both ends and blank across the middle four.
        SyntheticGridFixture.Grid grid =
                SyntheticGridFixture.write(tempDir, 8, 1, TILE_W, TILE_H, 0.15, 1.0, 6.0, List.of(2, 3, 4, 5), 11);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "the textured ends must still register: " + result.summary());

        double[] left = result.deltaFor("2.tif"); // index 1, the last textured tile on the left
        double[] right = result.deltaFor("7.tif"); // index 6, the first on the right
        double span = right[0] - left[0];
        assertTrue(
                Math.abs(span) > 2.0,
                "the two sides must be corrected differently or this test proves nothing; span " + span);

        // Five steps from index 1 to index 6, so the k-th blank tile sits k/5 of the way across.
        String[] gap = {"3.tif", "4.tif", "5.tif", "6.tif"};
        for (int k = 0; k < gap.length; k++) {
            double expected = left[0] + span * (k + 1) / 5.0;
            assertEquals(
                    expected,
                    result.deltaFor(gap[k])[0],
                    1e-4,
                    gap[k] + " must sit " + (k + 1) + "/5 of the way across the gap, not at either boundary");
        }

        // And say it as the shape rather than the numbers: strictly monotonic, no flat pair. A
        // staircase repeats a value at the step, so this fails on it independently of the arithmetic
        // above.
        for (int k = 1; k < gap.length; k++) {
            double prev = result.deltaFor(gap[k - 1])[0];
            double curr = result.deltaFor(gap[k])[0];
            assertTrue(
                    Math.signum(curr - prev) == Math.signum(span) && Math.abs(curr - prev) > 1e-9,
                    "the gap must ramp monotonically; " + gap[k - 1] + "=" + prev + " then " + gap[k] + "=" + curr);
        }
    }

    @Test
    void unreadableTiles_produceIdentityNotAnException() throws IOException {
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 2, 2, TILE_W, TILE_H, 0.15, 2.0, 9);
        for (var node : grid.nominal()) {
            assertTrue(node.file().delete(), "could not remove fixture tile for the failure case");
        }

        RegistrationResult result =
                TileRegistrationEngine.register(new RegistrationRequest("0", grid.nominal(), settings()));

        assertTrue(result.degenerate(), "unreadable tiles must degrade to nominal, not throw");
        assertEquals(0, result.maxAbsDeltaPx(), 1e-9);
    }

    @Test
    void correctionsNeverExceedTheOverlapBand() throws IOException {
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 4, 4, TILE_W, TILE_H, 0.15, 4.0, 21);

        RegistrationResult result = register(grid);

        double maxX = result.overlapFracX() * TILE_W;
        double maxY = result.overlapFracY() * TILE_H;
        for (var entry : result.deltaPxByFilename().entrySet()) {
            double[] d = entry.getValue();
            assertTrue(Math.abs(d[0]) <= maxX + 1e-6, entry.getKey() + " X correction " + d[0] + " exceeds " + maxX);
            assertTrue(Math.abs(d[1]) <= maxY + 1e-6, entry.getKey() + " Y correction " + d[1] + " exceeds " + maxY);
        }
    }

    @Test
    void overlapIsDerivedFromTheGridNotAssumed() throws IOException {
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, 0.25, 1.0, 13);

        RegistrationResult result = register(grid);

        assertEquals(0.25, result.overlapFracX(), 0.02, "overlap must be read off the nominal step");
        assertEquals(0.25, result.overlapFracY(), 0.02);
    }

    @Test
    void memoryStaysBounded() throws IOException {
        // The whole stitcher exists to hold ~40 MB regardless of tile count; registration must not be
        // the thing that breaks that. What is checked here is that nothing scaling with tile count is
        // RETAINED once the solve returns -- specifically that overlap bands are not cached across
        // edges, which is the one design decision that would quietly reintroduce unbounded growth.
        //
        // Both readings are taken after a collection. Comparing live heap without collecting measured
        // transient garbage instead, including garbage other tests in this JVM had left behind, which
        // made it read anywhere from 9 MB to 101 MB against a 100 MB bound for the same code.
        //
        // Peak transient use during the solve is deliberately NOT asserted here: this fixture's 100
        // tiles come to under 10 MB in total, so even reading every tile whole would fit inside any
        // bound worth setting, and a test that cannot fail for the reason it names is worse than none.
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 10, 10, TILE_W, TILE_H, 0.15, 2.0, 4);

        Runtime runtime = Runtime.getRuntime();
        long before = settledHeapBytes(runtime);

        RegistrationResult result = TileRegistrationEngine.register(
                new RegistrationRequest("0", grid.nominal(), settings().withThreads(4)));

        long retained = settledHeapBytes(runtime) - before;
        assertFalse(result.degenerate());
        long retainedMb = Math.max(0, retained) / (1024 * 1024);
        assertTrue(
                retainedMb < 20,
                "registration retained " + retainedMb
                        + " MB after the solve; only per-tile corrections should survive");
    }

    /**
     * Live heap after asking for a collection, repeated until the reading stops falling.
     *
     * <p>{@code System.gc()} is a hint, so a single call can return before anything has been
     * reclaimed. Iterating until two consecutive readings agree makes the measurement about what is
     * reachable rather than about when the collector happened to run.
     */
    private static long settledHeapBytes(Runtime runtime) {
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            long used = runtime.totalMemory() - runtime.freeMemory();
            if (used >= previous) {
                return used;
            }
            previous = used;
        }
        return previous;
    }
}
