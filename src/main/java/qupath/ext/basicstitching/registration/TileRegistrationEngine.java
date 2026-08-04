package qupath.ext.basicstitching.registration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder.EdgePair;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder.GridGeometry;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder.NeighborGraph;

/**
 * Corrects nominal tile positions by registering neighbouring tiles on their shared image content.
 *
 * <p>Tiles are placed from stage coordinates, and a stage has per-move error: backlash, encoder
 * resolution, and thermal drift across a long acquisition. Nominal placement leaves all of that in
 * the output as faint seams or soft double images inside the overlap band. This engine measures
 * where neighbouring tiles actually line up and solves for a globally consistent set of corrections.
 *
 * <h2>Contract</h2>
 *
 * {@link #register} <b>never throws</b>. Registration improves on nominal placement; it is not a
 * precondition for stitching. Anything unexpected returns {@link RegistrationResult#identity} and
 * the caller stitches at nominal positions exactly as it would have before.
 *
 * <h2>Sharing one solve across angles and channels</h2>
 *
 * This engine solves <b>one</b> subdirectory. Polarization angles and fluorescence channels are
 * captured at the same stage position for a given tile, so solving each independently would
 * misregister them against each other -- worse than a shared nominal grid. The sharing is done by
 * persisting the result as a {@link TileRegistrationSolution} and applying it to every sibling,
 * which is also what makes the corrections reproducible across a re-stitch.
 */
public final class TileRegistrationEngine {

    private static final Logger logger = LoggerFactory.getLogger(TileRegistrationEngine.class);

    /** Open-file budget shared across all workers, matching what a single stitch already uses. */
    private static final int TOTAL_READER_BUDGET = 64;

    /** Correction bound when an axis has no neighbours to derive an overlap from. */
    private static final double FALLBACK_SEARCH_FRACTION = 0.05;

    private TileRegistrationEngine() {}

    /**
     * Register a grid and solve for corrected positions.
     *
     * @param request the tiles, at nominal positions in output-pixel space, plus tuning
     * @return the corrections, or an identity result if the grid cannot be registered
     */
    public static RegistrationResult register(RegistrationRequest request) {
        return register(request, new CoarseToFineNccRegistrar());
    }

    /**
     * Register a grid using a specific correlation backend.
     *
     * @param request the tiles, at nominal positions in output-pixel space, plus tuning
     * @param registrar the pairwise backend
     * @return the corrections, or an identity result if the grid cannot be registered
     */
    public static RegistrationResult register(RegistrationRequest request, PairwiseRegistrar registrar) {
        List<TileNode> nominal = request.nominal();
        RegistrationSettings settings = request.settings();
        try {
            if (nominal.size() < 2) {
                return RegistrationResult.identity(nominal, "only " + nominal.size() + " tile(s); nothing to register");
            }

            NeighborGraph graph = NeighborGraphBuilder.build(nominal, settings);
            GridGeometry geometry = graph.geometry();

            if (graph.edges().isEmpty()) {
                // Almost always the 0%-overlap case: tiles placed edge to edge have no shared
                // content, so there is nothing to correlate. This is the acute cause of visible
                // seams and is worth saying loudly rather than failing quietly.
                return RegistrationResult.identity(
                        nominal,
                        String.format(
                                Locale.ROOT,
                                "no overlapping neighbours (overlap %.1f%% x %.1f%%); "
                                        + "registration needs a non-zero tile overlap",
                                geometry.overlapFracX() * 100,
                                geometry.overlapFracY() * 100));
            }

            int tileW = nominal.get(0).widthPx();
            int tileH = nominal.get(0).heightPx();
            int overlapXPx = geometry.overlapXPx(tileW);
            int overlapYPx = geometry.overlapYPx(tileH);

            // Per-EDGE search bound: how far a tile can sit from its immediate NEIGHBOUR -- one
            // stage step's worth of backlash and encoder noise. Real data puts this at a p90 of
            // ~15 px. It is a different quantity from the per-TILE correction, which is the running
            // sum of those steps across the whole grid and legitimately reaches tens of pixels.
            // Searching the full overlap (hundreds of pixels) for a per-edge offset is what lets a
            // low-texture band lock onto a wrong peak, and needlessly inflates the ambiguity
            // rejection rate -- a wider window holds more competing peaks. Bound it to the plausible
            // step error instead; the per-tile clamp below keeps the cumulative headroom.
            int perEdgeX = perEdgeSearch(overlapXPx, tileW, settings);
            int perEdgeY = perEdgeSearch(overlapYPx, tileH, settings);

            List<EdgeMeasurement> measured =
                    measureAll(nominal, graph.edges(), registrar, settings, perEdgeX, perEdgeY);

            long accepted = measured.stream().filter(EdgeMeasurement::accepted).count();
            if (accepted == 0) {
                return RegistrationResult.identity(
                        nominal, "no edge survived the confidence gates; keeping nominal positions");
            }

            // Per-TILE clamp stays at the overlap: the cumulative correction is legitimately large,
            // and only a displacement beyond a full overlap means a tile has lost its neighbour
            // entirely rather than merely drifted.
            int clampX = overlapXPx > 0 ? overlapXPx : (int) Math.round(FALLBACK_SEARCH_FRACTION * tileW);
            int clampY = overlapYPx > 0 ? overlapYPx : (int) Math.round(FALLBACK_SEARCH_FRACTION * tileH);
            GlobalPositionSolver.SolveOutcome outcome =
                    GlobalPositionSolver.solve(nominal, measured, settings, clampX, clampY);

            Map<String, double[]> deltas = new HashMap<>();
            for (int i = 0; i < nominal.size(); i++) {
                GlobalPositionSolver.SolvedPosition p = outcome.positions().get(i);
                deltas.put(nominal.get(i).filename(), new double[] {p.dxPx(), p.dyPx()});
            }

            // A tile whose every edge was rejected was left at nominal by the solve. That is only
            // right when nominal is approximately right; inside a large, smooth correction field it
            // strands the tile tens of pixels out of step with its corrected neighbours -- the worst
            // seam in the mosaic. Diffuse the neighbours' field into those islands instead, unless
            // the caller has turned that off.
            int filledIslands = settings.fillUnregistered()
                    ? fillUnregisteredIslands(nominal, graph.edges(), outcome.edges(), deltas)
                    : 0;

            int finalAccepted = (int)
                    outcome.edges().stream().filter(EdgeMeasurement::accepted).count();
            RegistrationResult result = new RegistrationResult(
                    deltas,
                    outcome.edges(),
                    geometry.overlapFracX(),
                    geometry.overlapFracY(),
                    measured.size(),
                    finalAccepted,
                    outcome.tilesClamped(),
                    false,
                    summarise(measured, outcome, geometry, deltas));

            logger.info("Tile registration: {}", result.summary());
            logEdgeDiagnostics(nominal, outcome.edges());
            if (filledIslands > 0) {
                logger.info(
                        "Filled {} unregisterable tile(s) from neighbouring corrections instead of nominal",
                        filledIslands);
            }
            logRejections(outcome.edges());
            return result;

        } catch (RuntimeException e) {
            // Deliberately broad. A correction is an improvement, never a prerequisite -- an
            // unexpected failure here must cost the user a slightly worse mosaic, not their
            // acquisition.
            logger.error("Tile registration failed; falling back to nominal positions", e);
            return RegistrationResult.identity(nominal, "registration failed: " + e);
        }
    }

    /**
     * Measure every edge, in parallel.
     *
     * <p>Work is partitioned across workers rather than queued per edge so each worker can own one
     * {@link OverlapBandReader} for its whole chunk. The readers must not be shared: every {@link
     * qupath.ext.basicstitching.assembly.direct.TileReaderPool} method is synchronized on the pool
     * and holds that lock across the tile decode, so workers sharing a pool would serialize on all
     * of the work and gain exactly nothing.
     */
    private static List<EdgeMeasurement> measureAll(
            List<TileNode> nominal,
            List<EdgePair> edges,
            PairwiseRegistrar registrar,
            RegistrationSettings settings,
            int searchX,
            int searchY) {

        int threads = Math.max(1, Math.min(settings.threads(), edges.size()));
        int budgetPerWorker = Math.max(4, TOTAL_READER_BUDGET / threads);
        List<List<EdgePair>> chunks = partition(edges, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "tile-registration");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Callable<List<EdgeMeasurement>>> tasks = new ArrayList<>();
            for (List<EdgePair> chunk : chunks) {
                tasks.add(() -> {
                    List<EdgeMeasurement> out = new ArrayList<>(chunk.size());
                    try (OverlapBandReader reader = new OverlapBandReader(budgetPerWorker)) {
                        for (EdgePair edge : chunk) {
                            out.add(measureOne(nominal, edge, reader, registrar, settings, searchX, searchY));
                        }
                    }
                    return out;
                });
            }

            List<EdgeMeasurement> all = new ArrayList<>(edges.size());
            for (Future<List<EdgeMeasurement>> future : pool.invokeAll(tasks)) {
                all.addAll(future.get());
            }
            return all;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Tile registration interrupted; keeping nominal positions");
            return List.of();
        } catch (java.util.concurrent.ExecutionException e) {
            logger.error("Tile registration worker failed", e.getCause());
            return List.of();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Read both overlap bands for one edge and measure it. */
    private static EdgeMeasurement measureOne(
            List<TileNode> nominal,
            EdgePair edge,
            OverlapBandReader reader,
            PairwiseRegistrar registrar,
            RegistrationSettings settings,
            int searchX,
            int searchY) {

        TileNode ti = nominal.get(edge.i());
        TileNode tj = nominal.get(edge.j());
        double nominalDx = tj.xPx() - ti.xPx();
        double nominalDy = tj.yPx() - ti.yPx();

        // The shared world region, expressed in each tile's own local pixels. Only these bands are
        // read -- never the whole tile.
        int ovW;
        int ovH;
        int ax;
        int ay;
        if (edge.horizontal()) {
            ovW = (int) Math.round(ti.widthPx() - nominalDx);
            ovH = Math.min(ti.heightPx(), tj.heightPx());
            ax = (int) Math.round(nominalDx);
            ay = 0;
        } else {
            ovW = Math.min(ti.widthPx(), tj.widthPx());
            ovH = (int) Math.round(ti.heightPx() - nominalDy);
            ax = 0;
            ay = (int) Math.round(nominalDy);
        }

        if (ovW < RegistrationSettings.MIN_BAND_PX || ovH < RegistrationSettings.MIN_BAND_PX) {
            return new EdgeMeasurement(
                    edge.i(), edge.j(), nominalDx, nominalDy, nominalDx, nominalDy, 0, RejectReason.NO_OVERLAP);
        }

        try {
            OverlapBand bandA = reader.read(ti.file(), ax, ay, ovW, ovH);
            OverlapBand bandB = reader.read(tj.file(), 0, 0, ovW, ovH);
            return registrar.measure(edge, bandA, bandB, nominalDx, nominalDy, searchX, searchY, settings);
        } catch (java.io.IOException | RuntimeException e) {
            logger.debug("Could not read overlap for {} / {}: {}", ti.filename(), tj.filename(), e.toString());
            return new EdgeMeasurement(
                    edge.i(), edge.j(), nominalDx, nominalDy, nominalDx, nominalDy, 0, RejectReason.READ_FAILED);
        }
    }

    /**
     * The per-edge search half-width: the largest single-step stage error we will look for, capped
     * so the search never exceeds the physical overlap.
     *
     * <p>Expressed as a fraction of the tile because stage error scales with travel, not with the
     * overlap the operator happened to choose. {@link RegistrationSettings#maxStepErrorFrac()} of a
     * tile (default 2%) comfortably clears the ~15 px p90 seen on real acquisitions while cutting the
     * search area to a small fraction of the full-overlap window it replaced;
     * {@link RegistrationSettings#minStepErrorPx()} floors it so small tiles keep a usable window.
     */
    private static int perEdgeSearch(int overlapPx, int tileSizePx, RegistrationSettings settings) {
        int step = Math.max(settings.minStepErrorPx(), (int) Math.round(settings.maxStepErrorFrac() * tileSizePx));
        int base = overlapPx > 0 ? overlapPx : (int) Math.round(FALLBACK_SEARCH_FRACTION * tileSizePx);
        return Math.max(1, Math.min(base, step));
    }

    /**
     * Replace the nominal fallback of unregisterable tiles with the correction their neighbours
     * imply.
     *
     * <p>A tile with no accepted edge reduces, in the global solve, to {@code lambda*p = lambda*n} --
     * it stays at nominal. That is the right answer only when nominal is close to the truth. When the
     * grid as a whole needed a large, smooth correction (a real scale error or slow drift), a tile
     * pinned at nominal sits tens of pixels away from where its corrected neighbours put the shared
     * content, which reads as a hard double-image seam. An unregisterable tile is nearly always blank
     * or low-texture, and the smooth field its neighbours define is the best estimate available for
     * it, so diffuse that field inward.
     *
     * <p>Adjacency comes from the neighbour graph's candidate edges -- grid adjacency, independent of
     * whether any given edge was accepted. A tile with no path to a registered tile has no field to
     * inherit and is left at nominal.
     *
     * @param nominal tiles in solve order
     * @param candidateEdges the grid adjacency (accepted or not)
     * @param edges the post-solve edge list, carrying final acceptance
     * @param deltas the per-filename corrections, mutated in place for filled tiles
     * @return the number of tiles filled
     */
    private static int fillUnregisteredIslands(
            List<TileNode> nominal,
            List<EdgePair> candidateEdges,
            List<EdgeMeasurement> edges,
            Map<String, double[]> deltas) {

        int n = nominal.size();
        boolean[] registered = new boolean[n];
        for (EdgeMeasurement e : edges) {
            if (e.accepted()) {
                registered[e.i()] = true;
                registered[e.j()] = true;
            }
        }

        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (EdgePair e : candidateEdges) {
            adjacency.get(e.i()).add(e.j());
            adjacency.get(e.j()).add(e.i());
        }

        double[] dx = new double[n];
        double[] dy = new double[n];
        boolean[] known = new boolean[n];
        for (int i = 0; i < n; i++) {
            double[] d = deltas.get(nominal.get(i).filename());
            dx[i] = d[0];
            dy[i] = d[1];
            known[i] = registered[i];
        }

        // Jacobi relaxation: each round averages the already-known neighbours into every remaining
        // island, spreading the field outward one ring at a time until nothing more can be reached.
        int filled = 0;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            double[] nx = dx.clone();
            double[] ny = dy.clone();
            boolean[] next = known.clone();
            for (int i = 0; i < n; i++) {
                if (known[i]) {
                    continue;
                }
                double sx = 0;
                double sy = 0;
                int c = 0;
                for (int j : adjacency.get(i)) {
                    if (known[j]) {
                        sx += dx[j];
                        sy += dy[j];
                        c++;
                    }
                }
                if (c > 0) {
                    nx[i] = sx / c;
                    ny[i] = sy / c;
                    next[i] = true;
                    filled++;
                    progressed = true;
                }
            }
            dx = nx;
            dy = ny;
            known = next;
        }

        for (int i = 0; i < n; i++) {
            if (!registered[i] && known[i]) {
                deltas.put(nominal.get(i).filename(), new double[] {dx[i], dy[i]});
            }
        }
        return filled;
    }

    private static <T> List<List<T>> partition(List<T> items, int parts) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            chunks.add(new ArrayList<>());
        }
        for (int i = 0; i < items.size(); i++) {
            chunks.get(i % parts).add(items.get(i));
        }
        chunks.removeIf(List::isEmpty);
        return chunks;
    }

    private static String summarise(
            List<EdgeMeasurement> measured,
            GlobalPositionSolver.SolveOutcome outcome,
            GridGeometry geometry,
            Map<String, double[]> deltas) {
        double maxDelta = 0;
        double sumDelta = 0;
        for (double[] d : deltas.values()) {
            double mag = Math.hypot(d[0], d[1]);
            maxDelta = Math.max(maxDelta, mag);
            sumDelta += mag;
        }
        int accepted =
                (int) outcome.edges().stream().filter(EdgeMeasurement::accepted).count();
        return String.format(
                Locale.ROOT,
                "%d/%d edges accepted, overlap %.1f%% x %.1f%%, corrections mean %.2f px / max %.2f px, %d clamped",
                accepted,
                measured.size(),
                geometry.overlapFracX() * 100,
                geometry.overlapFracY() * 100,
                deltas.isEmpty() ? 0 : sumDelta / deltas.size(),
                maxDelta,
                outcome.tilesClamped());
    }

    /**
     * Per-edge gate inputs, at DEBUG only.
     *
     * <p>These are what every threshold is compared against, so they are what a default should be
     * chosen from. They are far too verbose for a normal run -- one line per edge, hundreds of edges
     * -- so they stay behind DEBUG on this logger; the diagnostic probe script writes the same values
     * to a CSV when a run is being investigated in earnest.
     */
    private static void logEdgeDiagnostics(List<TileNode> nominal, List<EdgeMeasurement> edges) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug("Edge diagnostics: tileA tileB reject ncc textureA textureB peakRatio shift search band");
        for (EdgeMeasurement e : edges) {
            EdgeDiagnostics d = e.diagnostics();
            logger.debug(
                    "  {} {} {} ncc={} texture={}/{} peakRatio={} shift=({},{}) search=({},{}) band={}x{}{}",
                    nominal.get(e.i()).filename(),
                    nominal.get(e.j()).filename(),
                    e.reject(),
                    fmt(e.ncc()),
                    fmt(d.textureA()),
                    fmt(d.textureB()),
                    fmt(d.secondPeakRatio()),
                    fmt(d.shiftXPx()),
                    fmt(d.shiftYPx()),
                    d.searchXPx(),
                    d.searchYPx(),
                    d.bandWidthPx(),
                    d.bandHeightPx(),
                    d.shiftAtSearchBound() ? "  [AT SEARCH BOUND -- window may be too small]" : "");
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.3f", v);
    }

    /** Counts per rejection reason, so a bad run says why rather than just "few edges". */
    private static void logRejections(List<EdgeMeasurement> edges) {
        Map<RejectReason, Integer> counts = new java.util.EnumMap<>(RejectReason.class);
        for (EdgeMeasurement e : edges) {
            counts.merge(e.reject(), 1, Integer::sum);
        }
        counts.remove(RejectReason.NONE);
        if (!counts.isEmpty()) {
            logger.info("Rejected edges by reason: {}", counts);
        }
    }

    /**
     * Pick the subdirectory most worth solving on.
     *
     * <p>Registration is only as good as the texture it correlates, and the angles or channels of
     * one acquisition differ wildly in that respect -- a polarization extinction angle is nearly
     * black, and a poorly-stained fluorescence channel is nearly empty. Sampling actual content and
     * picking the busiest subdirectory beats hardcoding a per-modality default, and needs no
     * knowledge of what the subdirectories mean.
     *
     * @param candidates subdirectory name to that subdirectory's tiles
     * @param sampleSize how many tiles to sample per candidate
     * @return the name of the subdirectory with the most texture, or null if none can be read
     */
    public static String chooseReference(Map<String, List<TileNode>> candidates, int sampleSize) {
        String best = null;
        double bestScore = -1;
        try (OverlapBandReader reader = new OverlapBandReader(8)) {
            for (Map.Entry<String, List<TileNode>> entry : candidates.entrySet()) {
                double score = medianTexture(entry.getValue(), reader, sampleSize);
                logger.debug("Reference candidate '{}': median texture score {}", entry.getKey(), score);
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getKey();
                }
            }
        }
        if (best != null) {
            logger.info(
                    "Registration reference: '{}' (median texture score {})",
                    best,
                    String.format(Locale.ROOT, "%.4f", bestScore));
        }
        return best;
    }

    private static double medianTexture(List<TileNode> tiles, OverlapBandReader reader, int sampleSize) {
        List<Double> scores = new ArrayList<>();
        int stride = Math.max(1, tiles.size() / Math.max(1, sampleSize));
        for (int i = 0; i < tiles.size() && scores.size() < sampleSize; i += stride) {
            TileNode t = tiles.get(i);
            try {
                // A centre crop: tile corners are the likeliest place to find only background.
                int w = Math.max(RegistrationSettings.MIN_BAND_PX, t.widthPx() / 4);
                int h = Math.max(RegistrationSettings.MIN_BAND_PX, t.heightPx() / 4);
                OverlapBand band = reader.read(t.file(), (t.widthPx() - w) / 2, (t.heightPx() - h) / 2, w, h);
                scores.add(band.textureScore());
            } catch (java.io.IOException | RuntimeException e) {
                logger.debug("Could not sample {}: {}", t.filename(), e.toString());
            }
        }
        if (scores.isEmpty()) {
            return -1;
        }
        Collections.sort(scores);
        return scores.get(scores.size() / 2);
    }
}
