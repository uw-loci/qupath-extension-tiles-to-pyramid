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
    void blankTiles_fallBackToNominal_whileTheRestStillSolve() throws IOException {
        // A blank tile at an annotation edge has no signal, so its edges are rejected and the pull
        // toward nominal is all that holds it -- it must land exactly at nominal, and must not drag
        // its textured neighbours with it.
        SyntheticGridFixture.Grid grid =
                SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, 0.15, 3.0, List.of(0, 8), 5);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "the textured majority must still solve: " + result.summary());
        assertArrayEquals(
                new double[] {0, 0}, result.deltaFor("1.tif"), 1e-6, "a blank tile must stay exactly at nominal");
        assertArrayEquals(new double[] {0, 0}, result.deltaFor("9.tif"), 1e-6);

        double[] middle = result.deltaFor("5.tif");
        double[] expected = grid.trueJitterPx().get("5.tif");
        assertEquals(expected[0], middle[0], 2.0, "textured tiles must still be corrected");
        assertEquals(expected[1], middle[1], 2.0);
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
        // The whole stitcher exists to hold ~40 MB regardless of tile count; registration must not
        // be the thing that breaks that. Only overlap bands are ever read, never whole tiles, and
        // bands are not cached across edges.
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.write(tempDir, 10, 10, TILE_W, TILE_H, 0.15, 2.0, 4);

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();

        RegistrationResult result = TileRegistrationEngine.register(
                new RegistrationRequest("0", grid.nominal(), settings().withThreads(4)));

        long after = runtime.totalMemory() - runtime.freeMemory();
        assertFalse(result.degenerate());
        long grownMb = Math.max(0, after - before) / (1024 * 1024);
        assertTrue(grownMb < 100, "registration grew the heap by " + grownMb + " MB; expected well under 100");
    }
}
