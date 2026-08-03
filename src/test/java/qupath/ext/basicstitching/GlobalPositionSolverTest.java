package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.registration.EdgeMeasurement;
import qupath.ext.basicstitching.registration.GlobalPositionSolver;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.RejectReason;
import qupath.ext.basicstitching.registration.TileNode;

/**
 * Verifies the global least-squares tile placement: that it recovers a known
 * truth, that it uses every edge rather than a spanning tree's worth, that
 * unconstrained tiles stay at nominal, that corrections cannot exceed the
 * overlap, that a bad edge is thrown out, and that it scales.
 *
 * <p>Every test is seeded. Registration failures are hard enough to reproduce
 * from a microscope; a test that only fails on some runs would be worse than
 * no test.
 */
public class GlobalPositionSolverTest {

    private static final int TILE = 512;
    private static final double OVERLAP = 0.10;
    private static final double STEP = TILE * (1 - OVERLAP);

    /** Comfortably above any correction these tests produce, so the clamp stays out of the way. */
    private static final double NO_CLAMP = 1e6;

    /** Well above minNcc, so weights are healthy and equal unless a test says otherwise. */
    private static final double GOOD_NCC = 0.90;

    // ---------------------------------------------------------------- truth recovery

    /**
     * Perfect measurements of a jittered grid: the solve should land back on the truth. Anything
     * that mixes up a sign in the normal equations fails here first.
     *
     * <p>The jitter is centred, because a whole-mosaic translation is deliberately not recoverable
     * -- see {@link #globalTranslation_isPinnedToNominal()}. Removing the mean here is not the test
     * being let off; it is the test asking only for what the formulation claims to answer. With the
     * mean left in, this same seed lands 0.68px away, and all 0.68px of it is the global offset.
     */
    @Test
    public void exactEdges_recoverTruth() {
        int cols = 10;
        int rows = 10;
        List<TileNode> nominal = grid(cols, rows);
        double[][] truth = centredJitter(nominal, 3.0, new Random(42));
        List<EdgeMeasurement> edges = exactEdges(nominal, truth, neighbours(cols, rows));

        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);

        for (int i = 0; i < nominal.size(); i++) {
            GlobalPositionSolver.SolvedPosition p = outcome.positions().get(i);
            assertEquals(truth[i][0], p.xPx(), 0.5, "tile " + i + " x");
            assertEquals(truth[i][1], p.yPx(), 0.5, "tile " + i + " y");
        }
        assertEquals(0, outcome.tilesClamped(), "nothing should clamp at a 1e6 limit");
    }

    /**
     * Slide the whole truth sideways and the solver refuses to follow: it reports nominal.
     *
     * <p>This is the one thing the formulation cannot recover, and it is not an accident. Relative
     * edges say nothing about where the mosaic sits as a whole -- they constrain only differences,
     * so a global translation lives in the Laplacian's null space. Lambda is what removes that null
     * space, and it removes it by pulling the mosaic's mean back to nominal. That is the trade being
     * made: the stitched result stays anchored in the stage frame it was acquired in, and in
     * exchange a real global stage offset is absorbed rather than measured.
     *
     * <p>The practical reading: this solver fixes tiles relative to each other, not the mosaic
     * relative to the slide. Every test here that compares absolute positions to a truth has to
     * account for it.
     */
    @Test
    public void globalTranslation_isPinnedToNominal() {
        int cols = 4;
        int rows = 4;
        List<TileNode> nominal = grid(cols, rows);
        double[][] truth = copyOfNominal(nominal);
        for (double[] t : truth) {
            t[0] += 5.0;
            t[1] -= 3.0;
        }
        List<EdgeMeasurement> edges = exactEdges(nominal, truth, neighbours(cols, rows));

        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);

        for (int i = 0; i < nominal.size(); i++) {
            GlobalPositionSolver.SolvedPosition p = outcome.positions().get(i);
            assertEquals(0.0, p.dxPx(), 1e-6, "tile " + i + " should absorb the global x shift, not follow it");
            assertEquals(0.0, p.dyPx(), 1e-6, "tile " + i + " should absorb the global y shift, not follow it");
        }
    }

    // ---------------------------------------------------------------- the headline test

    /**
     * The accumulated-drift guard, and the reason this is a solve and not a spanning-tree walk.
     *
     * <p>A 10x10 grid has 180 neighbour edges; a spanning tree keeps 99 and discards 81. Those 81
     * are precisely the ones that close loops, so a tree never has to make them agree -- error walks
     * along tree paths and two tiles adjacent in space but far apart in the tree drift apart. That
     * is what "large areas suddenly touching" is. So this test checks EVERY spatially adjacent pair,
     * including all 81 an MST would have thrown away.
     *
     * <p>The bounds are calibrated against the thing being replaced. On this fixture and seed, a
     * BFS spanning-tree walk over the <i>same</i> measurements scores max 8.10px / mean 1.50px,
     * while the solve scores max 2.11px / mean 0.59px. Both bounds below sit in that gap, so they
     * are wide enough not to be seed-luck and tight enough that a tree-walking regression fails
     * them.
     *
     * <p>The max bound is 3 sigma rather than 2 because it is a max over 360 comparisons, and the
     * largest of 360 normal draws lands near 3 sigma by construction. A 2-sigma bound on a
     * max-order statistic would fail on chance about half the time, which would say nothing about
     * drift. The mean bound is the sharper instrument of the two: drift shows up there first.
     */
    @Test
    public void allNeighborPairsAgree_noRegionalDrift() {
        int cols = 10;
        int rows = 10;
        double sigma = 1.0;
        Random rng = new Random(42);

        List<TileNode> nominal = grid(cols, rows);
        double[][] truth = jitter(nominal, 3.0, rng);
        List<int[]> pairs = neighbours(cols, rows);
        assertEquals(180, pairs.size(), "10x10 grid should have 180 neighbour edges");
        assertEquals(99, cols * rows - 1, "a spanning tree would keep only 99 of them");

        List<EdgeMeasurement> edges = new ArrayList<>();
        for (int[] pair : pairs) {
            edges.add(noisyEdge(nominal, truth, pair[0], pair[1], sigma, rng));
        }

        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);

        double total = 0;
        for (int[] pair : pairs) {
            int i = pair[0];
            int j = pair[1];
            GlobalPositionSolver.SolvedPosition a = outcome.positions().get(i);
            GlobalPositionSolver.SolvedPosition b = outcome.positions().get(j);
            double errX = Math.abs((b.xPx() - a.xPx()) - (truth[j][0] - truth[i][0]));
            double errY = Math.abs((b.yPx() - a.yPx()) - (truth[j][1] - truth[i][1]));
            assertTrue(errX <= 3 * sigma, "x drift between adjacent tiles " + i + " and " + j + " was " + errX);
            assertTrue(errY <= 3 * sigma, "y drift between adjacent tiles " + i + " and " + j + " was " + errY);
            total += errX + errY;
        }
        double mean = total / (2 * pairs.size());
        assertTrue(mean <= 1.0 * sigma, "mean adjacent-pair disagreement was " + mean + "px; a tree walk scores ~1.5");
    }

    // ---------------------------------------------------------------- degenerate graphs

    /** A tile nothing measured against has only the lambda term, so it must not move at all. */
    @Test
    public void isolatedTile_staysAtNominal() {
        int cols = 3;
        int rows = 3;
        List<TileNode> nominal = new ArrayList<>(grid(cols, rows));
        int lonely = nominal.size();
        nominal.add(new TileNode("lonely.tif", new File("lonely.tif"), 9999, 9999, TILE, TILE));

        double[][] truth = jitter(nominal, 3.0, new Random(42));
        List<EdgeMeasurement> edges = exactEdges(nominal, truth, neighbours(cols, rows));

        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);

        GlobalPositionSolver.SolvedPosition p = outcome.positions().get(lonely);
        assertEquals(0.0, p.dxPx(), 0.0, "isolated tile must not move on x, exactly");
        assertEquals(0.0, p.dyPx(), 0.0, "isolated tile must not move on y, exactly");
        assertEquals(9999.0, p.xPx(), 0.0);
        assertEquals(9999.0, p.yPx(), 0.0);
    }

    /**
     * Two blocks with no edge between them. Each is pinned by its own nominal term, so each solves
     * internally and neither is free to slide relative to the other. No component bookkeeping, no
     * anchor selection -- this is the lambda term doing its job.
     */
    @Test
    public void disconnectedComponents_eachPinnedToNominalMean() {
        List<TileNode> nominal = new ArrayList<>();
        nominal.addAll(gridAt(3, 3, 0, 0, "a"));
        nominal.addAll(gridAt(3, 3, 100000, 0, "b"));

        double[][] truth = jitter(nominal, 3.0, new Random(42));
        List<int[]> pairs = new ArrayList<>();
        pairs.addAll(neighbours(3, 3));
        for (int[] pair : neighbours(3, 3)) {
            pairs.add(new int[] {pair[0] + 9, pair[1] + 9});
        }
        List<EdgeMeasurement> edges = exactEdges(nominal, truth, pairs);

        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);

        for (int block = 0; block < 2; block++) {
            int base = block * 9;

            // Each component's own constant vector is in the Laplacian's null space, so the only
            // thing holding it is lambda -- and lambda pulls it to nominal. The mean correction over
            // a component is therefore zero to solver precision, not merely small.
            double meanDx = 0;
            double meanDy = 0;
            for (int k = 0; k < 9; k++) {
                meanDx += outcome.positions().get(base + k).dxPx() / 9;
                meanDy += outcome.positions().get(base + k).dyPx() / 9;
            }
            assertEquals(0.0, meanDx, 1e-6, "block " + block + " drifted on x");
            assertEquals(0.0, meanDy, 1e-6, "block " + block + " drifted on y");

            // ...and each block still satisfies its own edges.
            for (int[] pair : neighbours(3, 3)) {
                int i = base + pair[0];
                int j = base + pair[1];
                GlobalPositionSolver.SolvedPosition a = outcome.positions().get(i);
                GlobalPositionSolver.SolvedPosition b = outcome.positions().get(j);
                assertEquals(truth[j][0] - truth[i][0], b.xPx() - a.xPx(), 0.5, "block " + block + " x edge");
                assertEquals(truth[j][1] - truth[i][1], b.yPx() - a.yPx(), 0.5, "block " + block + " y edge");
            }
        }
    }

    // ---------------------------------------------------------------- the clamp

    /**
     * The clamp is the last line of defence and is unconditional. A correction bigger than the
     * overlap cannot be real -- the tiles would not share the pixels the match claims to have found
     * -- so however confident an edge is, it does not get to move a tile further than the overlap.
     */
    @Test
    public void clampNeverExceedsOverlap() {
        int cols = 4;
        int rows = 4;
        List<TileNode> nominal = grid(cols, rows);
        List<int[]> pairs = neighbours(cols, rows);
        double[][] truth = copyOfNominal(nominal);

        List<EdgeMeasurement> edges = new ArrayList<>(exactEdges(nominal, truth, pairs));
        // Absurd, and confident enough to be believed. Disabling the outlier pass forces it to
        // survive into the solve, which is the point: the clamp must hold without help.
        EdgeMeasurement bad = edges.get(0);
        edges.set(
                0,
                new EdgeMeasurement(
                        bad.i(),
                        bad.j(),
                        bad.nominalDxPx() + 500,
                        bad.nominalDyPx() + 500,
                        bad.nominalDxPx(),
                        bad.nominalDyPx(),
                        0.99,
                        RejectReason.NONE));

        RegistrationSettings settings = new RegistrationSettings(
                0.30,
                0.02,
                0.92,
                0.90,
                0.01,
                0 /* no outlier pass */,
                8,
                3,
                1,
                Double.NaN,
                Double.NaN,
                RegistrationSettings.DEFAULT_MAX_STEP_ERROR_FRAC,
                RegistrationSettings.DEFAULT_MIN_STEP_ERROR_PX,
                true);
        double maxDx = 20;
        double maxDy = 12;

        GlobalPositionSolver.SolveOutcome outcome = GlobalPositionSolver.solve(nominal, edges, settings, maxDx, maxDy);

        for (int i = 0; i < nominal.size(); i++) {
            GlobalPositionSolver.SolvedPosition p = outcome.positions().get(i);
            assertTrue(Math.abs(p.dxPx()) <= maxDx, "tile " + i + " dx " + p.dxPx() + " exceeded " + maxDx);
            assertTrue(Math.abs(p.dyPx()) <= maxDy, "tile " + i + " dy " + p.dyPx() + " exceeded " + maxDy);
            assertEquals(nominal.get(i).xPx() + p.dxPx(), p.xPx(), 1e-9);
            assertEquals(nominal.get(i).yPx() + p.dyPx(), p.yPx(), 1e-9);
        }
        assertTrue(outcome.tilesClamped() > 0, "the 500px edge should have driven something into the clamp");
    }

    // ---------------------------------------------------------------- outlier rejection

    /** One badly wrong edge among noisy-but-good ones: it should be identified and dropped. */
    @Test
    public void outlierEdgeRemoved_byIrls() {
        int cols = 5;
        int rows = 5;
        double sigma = 1.0;
        Random rng = new Random(42);

        List<TileNode> nominal = grid(cols, rows);
        double[][] truth = copyOfNominal(nominal);
        List<int[]> pairs = neighbours(cols, rows);

        List<EdgeMeasurement> edges = new ArrayList<>();
        for (int[] pair : pairs) {
            edges.add(noisyEdge(nominal, truth, pair[0], pair[1], sigma, rng));
        }
        int badIndex = pairs.size() / 2;
        EdgeMeasurement good = edges.get(badIndex);
        edges.set(
                badIndex,
                new EdgeMeasurement(
                        good.i(),
                        good.j(),
                        good.nominalDxPx() + 50,
                        good.nominalDyPx(),
                        good.nominalDxPx(),
                        good.nominalDyPx(),
                        GOOD_NCC,
                        RejectReason.NONE));

        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);

        assertEquals(
                RejectReason.OUTLIER_IRLS,
                outcome.edges().get(badIndex).reject(),
                "the 50px edge should have been flagged");

        for (int i : new int[] {good.i(), good.j()}) {
            GlobalPositionSolver.SolvedPosition p = outcome.positions().get(i);
            assertTrue(Math.abs(p.dxPx()) <= 2 * sigma, "tile " + i + " was dragged " + p.dxPx() + "px by the outlier");
            assertTrue(Math.abs(p.dyPx()) <= 2 * sigma, "tile " + i + " y moved " + p.dyPx());
        }
    }

    // ---------------------------------------------------------------- scale

    /**
     * 100x100 tiles, ~19,800 edges. The budget is not about the CPU -- it is a tripwire on
     * materializing the matrix. A dense {@code (L + lambda*I)} for 10,000 tiles is 800 MB and would
     * miss this by orders of magnitude, or fail to allocate at all.
     */
    @Test
    public void solveIsFastEnough() {
        // Warm the JIT on a small grid first; otherwise this measures the interpreter.
        runGridSolve(20, 20);

        int cols = 100;
        int rows = 100;
        List<TileNode> nominal = grid(cols, rows);
        List<int[]> pairs = neighbours(cols, rows);
        assertEquals(10000, nominal.size());
        assertEquals(19800, pairs.size());
        double[][] truth = jitter(nominal, 3.0, new Random(42));
        List<EdgeMeasurement> edges = exactEdges(nominal, truth, pairs);

        long start = System.nanoTime();
        GlobalPositionSolver.SolveOutcome outcome =
                GlobalPositionSolver.solve(nominal, edges, RegistrationSettings.defaults(), NO_CLAMP, NO_CLAMP);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(10000, outcome.positions().size());
        assertTrue(elapsedMs < 100, "solve of 10000 tiles / 19800 edges took " + elapsedMs + "ms, budget 100ms");
    }

    private static void runGridSolve(int cols, int rows) {
        List<TileNode> nominal = grid(cols, rows);
        double[][] truth = jitter(nominal, 3.0, new Random(1));
        GlobalPositionSolver.solve(
                nominal,
                exactEdges(nominal, truth, neighbours(cols, rows)),
                RegistrationSettings.defaults(),
                NO_CLAMP,
                NO_CLAMP);
    }

    // ---------------------------------------------------------------- fixtures

    private static List<TileNode> grid(int cols, int rows) {
        return gridAt(cols, rows, 0, 0, "t");
    }

    /** A perfect cols-by-rows grid of overlapping tiles, in row-major order. */
    private static List<TileNode> gridAt(int cols, int rows, double originX, double originY, String prefix) {
        List<TileNode> tiles = new ArrayList<>(cols * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String name = prefix + "_" + r + "_" + c + ".tif";
                tiles.add(new TileNode(name, new File(name), originX + c * STEP, originY + r * STEP, TILE, TILE));
            }
        }
        return tiles;
    }

    /** Right and down neighbours, row-major -- the pairs a stitcher would actually try to match. */
    private static List<int[]> neighbours(int cols, int rows) {
        List<int[]> pairs = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (c + 1 < cols) {
                    pairs.add(new int[] {i, i + 1});
                }
                if (r + 1 < rows) {
                    pairs.add(new int[] {i, i + cols});
                }
            }
        }
        return pairs;
    }

    private static double[][] copyOfNominal(List<TileNode> nominal) {
        double[][] truth = new double[nominal.size()][2];
        for (int i = 0; i < nominal.size(); i++) {
            truth[i][0] = nominal.get(i).xPx();
            truth[i][1] = nominal.get(i).yPx();
        }
        return truth;
    }

    /** Where the tiles really are: nominal plus independent per-tile stage error. */
    private static double[][] jitter(List<TileNode> nominal, double sigma, Random rng) {
        double[][] truth = copyOfNominal(nominal);
        for (double[] t : truth) {
            t[0] += rng.nextGaussian() * sigma;
            t[1] += rng.nextGaussian() * sigma;
        }
        return truth;
    }

    /**
     * The same, with the mean offset removed, so the truth carries no whole-mosaic translation for
     * the solver to be unable to see.
     */
    private static double[][] centredJitter(List<TileNode> nominal, double sigma, Random rng) {
        double[][] truth = jitter(nominal, sigma, rng);
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < nominal.size(); i++) {
            meanX += (truth[i][0] - nominal.get(i).xPx()) / nominal.size();
            meanY += (truth[i][1] - nominal.get(i).yPx()) / nominal.size();
        }
        for (double[] t : truth) {
            t[0] -= meanX;
            t[1] -= meanY;
        }
        return truth;
    }

    /** Edges measured perfectly: dx is exactly the truth offset. */
    private static List<EdgeMeasurement> exactEdges(List<TileNode> nominal, double[][] truth, List<int[]> pairs) {
        List<EdgeMeasurement> edges = new ArrayList<>(pairs.size());
        for (int[] pair : pairs) {
            edges.add(edge(nominal, truth, pair[0], pair[1], 0, 0));
        }
        return edges;
    }

    private static EdgeMeasurement noisyEdge(
            List<TileNode> nominal, double[][] truth, int i, int j, double sigma, Random rng) {
        return edge(nominal, truth, i, j, rng.nextGaussian() * sigma, rng.nextGaussian() * sigma);
    }

    private static EdgeMeasurement edge(
            List<TileNode> nominal, double[][] truth, int i, int j, double noiseX, double noiseY) {
        double nominalDx = nominal.get(j).xPx() - nominal.get(i).xPx();
        double nominalDy = nominal.get(j).yPx() - nominal.get(i).yPx();
        double dx = (truth[j][0] - truth[i][0]) + noiseX;
        double dy = (truth[j][1] - truth[i][1]) + noiseY;
        return new EdgeMeasurement(i, j, dx, dy, nominalDx, nominalDy, GOOD_NCC, RejectReason.NONE);
    }
}
