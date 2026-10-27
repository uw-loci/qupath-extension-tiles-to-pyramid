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
 * <p>These exercise the whole chain -- neighbor graph, region reads, correlation, gates, global
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
    void rotatedGrid_isNotMoved() throws IOException {
        // A grid that is already correct but NOT axis-aligned: stage and camera slightly out of
        // square, so each column steps a few pixels in Y and each row a few pixels in X. The
        // tiles sit exactly where TileConfiguration.txt says, so zero correction is the only correct
        // answer.
        //
        // Regression: the overlap band for a horizontal edge was read at ay = 0 (and the neighbor
        // at bx = 0), silently assuming the perpendicular offset was zero. On a rotated grid the two
        // bands then covered DIFFERENT world regions, NCC reported the drift itself as the measured
        // offset, and since measured = nominal + offset the drift was counted twice -- applying a
        // spurious shift equal to it on EVERY edge. Measured on a real 0.251 deg acquisition: +8 px
        // of bogus Y shift per horizontal edge and -6 px of bogus X shift per vertical edge, i.e. a
        // torn seam at every tile boundary, strictly worse than not registering at all.
        // What matters is the perpendicular drift in PIXELS per step, not the angle. The real
        // acquisition stepped 1860 px per column, so 0.251 deg drifted ~8 px. Bigger tiles than the
        // other tests use, at 0.8 deg, reproduce that (~12 px per step) while keeping the drift
        // under medianStep's 2%-of-tile gap tolerance, so the lattice is still detected as a grid.
        // At 0.25 deg on a 256 px tile the drift is sub-pixel and the defect is genuinely invisible.
        SyntheticGridFixture.Grid grid = SyntheticGridFixture.writeRotated(tempDir, 4, 4, 1024, 768, 0.15, 0.8, 11);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "a rotated textured grid must still register: " + result.summary());
        assertTrue(
                result.maxAbsDeltaPx() <= 2.0,
                "a rotated but already-correct grid must not be moved; got max |delta| = " + result.maxAbsDeltaPx()
                        + " px -- " + result.summary());
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
    void blankIsland_inheritsNeighborField_notNominal() throws IOException {
        // The failure this fixes: a blank tile whose edges are all rejected used to be pinned to
        // nominal (0, 0). That is only right when nominal is right. Under a real, smooth correction
        // field -- here a scale-like drift of 6 px/tile -- nominal strands the tile a dozen pixels
        // from where its neighbors put the shared content, which is the worst seam in the mosaic
        // (the on-scope symptom: a doubled edge with a white gap). The tile must instead inherit the
        // field its registered neighbors define.
        SyntheticGridFixture.Grid grid =
                SyntheticGridFixture.write(tempDir, 5, 5, TILE_W, TILE_H, 0.15, 2.0, 6.0, List.of(0), 5);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "the textured majority must still solve: " + result.summary());

        // The blank corner (index 0 -> 1.tif) inherits the mean of its two registered grid
        // neighbors: the tile to its right (index 1 -> 2.tif) and below it (index 5 -> 6.tif).
        double[] blank = result.deltaFor("1.tif");
        double[] right = result.deltaFor("2.tif");
        double[] below = result.deltaFor("6.tif");
        assertEquals(
                (right[0] + below[0]) / 2, blank[0], 1e-6, "blank island X must be its neighbors' mean, not nominal");
        assertEquals((right[1] + below[1]) / 2, blank[1], 1e-6, "blank island Y must be its neighbors' mean");
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
        // Eight columns by three rows. The middle row is blank across columns 2-5 and textured at
        // both ends; the rows above and below are textured throughout, so the whole mosaic is ONE
        // registered piece and the gap has measured tissue on every side to ramp between. Two
        // textured ends joined by nothing but the gap would be two pieces, and the second is then
        // attached to the first by continuity with nothing to ramp toward (see the next test): the
        // ramp this test asserts only exists when both sides are measured against each other.
        SyntheticGridFixture.Grid grid =
                SyntheticGridFixture.write(tempDir, 8, 3, TILE_W, TILE_H, 0.15, 1.0, 6.0, List.of(10, 11, 12, 13), 11);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "the textured tiles must still register: " + result.summary());

        // Row 1 is 9.tif..16.tif (row-major, 1-based): 10.tif is the last textured tile on the left
        // of the gap, 15.tif the first on the right. The drift field puts five columns, 30 px,
        // between them.
        double[] left = result.deltaFor("10.tif");
        double[] right = result.deltaFor("15.tif");
        double span = right[0] - left[0];
        assertTrue(
                Math.abs(span) > 2.0,
                "the two sides must be corrected differently or this test proves nothing; span " + span);

        // Five steps from column 1 to column 6, so the k-th blank tile sits k/5 of the way across.
        // The registered tiles carry their recovered jitter (sigma 1 px), so the ramp is linear to
        // within a couple of pixels, not to machine precision.
        String[] gap = {"11.tif", "12.tif", "13.tif", "14.tif"};
        for (int k = 0; k < gap.length; k++) {
            double expected = left[0] + span * (k + 1) / 5.0;
            assertEquals(
                    expected,
                    result.deltaFor(gap[k])[0],
                    2.5,
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
    void texturedBlockBeyondABlankRow_isAttachedToTheMosaic_notPinnedAtNominal() throws IOException {
        // PPM, 2026-10-26: a U-shaped region whose right arm narrowed to three tiles. The three seam
        // edges across that neck were rejected, so the block above became its own piece of the
        // accepted-edge graph. The solve pins each piece at its own nominal mean, and the main
        // piece's correction field had grown to hundreds of pixels by the time it reached the neck,
        // so the block sat where the stage said while everything below it had moved: a white gap
        // and a sideways step across the whole arm, with real overlap on both sides of it.
        //
        // Same mechanism with a blank row instead of rejected seams: 3 columns x 7 rows, row 3
        // blank, drift 6 px/tile. The bottom block (rows 4-6) registers to itself and to nothing
        // else. Row-major, 1-based filenames: row r, column c is (r * 3 + c + 1).tif.
        SyntheticGridFixture.Grid grid =
                SyntheticGridFixture.write(tempDir, 3, 7, TILE_W, TILE_H, 0.15, 1.0, 6.0, List.of(9, 10, 11), 23);

        RegistrationResult result = register(grid);

        assertFalse(result.degenerate(), "both textured blocks must register: " + result.summary());

        // The true field puts rows 4-6 a further 24 px down in Y than rows 0-2. Pinned at their own
        // nominal means, the two blocks' mean corrections were equal. Attached through the blank
        // row, the bottom block at least continues the top block's edge value (about 12 px); the
        // field is not extrapolated beyond that, which is the honest estimate.
        double topMean = meanDeltaY(result, 0, 2);
        double bottomMean = meanDeltaY(result, 4, 6);
        assertTrue(
                bottomMean - topMean > 9.0,
                "the bottom block must be shifted to meet the mosaic, not left at nominal; top mean " + topMean
                        + ", bottom mean " + bottomMean);

        // And the seam between the blank row and the block is continuous, column by column.
        for (int c = 0; c < 3; c++) {
            double blank = result.deltaFor((3 * 3 + c + 1) + ".tif")[1];
            double below = result.deltaFor((4 * 3 + c + 1) + ".tif")[1];
            assertEquals(blank, below, 3.0, "column " + c + ": the block must meet the blank row above it");
        }

        // Inside the block the measured geometry is kept: rows still step by the 6 px drift.
        double row4 = meanDeltaY(result, 4, 4);
        double row6 = meanDeltaY(result, 6, 6);
        assertEquals(12.0, row6 - row4, 2.0, "the block's internal registration must survive the shift");
    }

    /** Mean Y correction over the tiles of rows {@code r0..r1} of a 3-column grid. */
    private static double meanDeltaY(RegistrationResult result, int r0, int r1) {
        double sum = 0;
        int count = 0;
        for (int r = r0; r <= r1; r++) {
            for (int c = 0; c < 3; c++) {
                sum += result.deltaFor((r * 3 + c + 1) + ".tif")[1];
                count++;
            }
        }
        return sum / count;
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
        // The whole stitcher exists to hold a footprint set by the chunk rather than the tile
        // count; registration must not be
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
        long before = HeapMeasurement.settledHeapBytes(runtime);

        RegistrationResult result = TileRegistrationEngine.register(
                new RegistrationRequest("0", grid.nominal(), settings().withThreads(4)));

        long retained = HeapMeasurement.settledHeapBytes(runtime) - before;
        assertFalse(result.degenerate());
        long retainedMb = Math.max(0, retained) / (1024 * 1024);
        assertTrue(
                retainedMb < 20,
                "registration retained " + retainedMb
                        + " MB after the solve; only per-tile corrections should survive");
    }
}
