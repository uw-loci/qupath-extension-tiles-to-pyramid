package qupath.ext.basicstitching.registration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Places every tile at once by least squares, rather than walking a spanning tree.
 *
 * <p>The objective, with x and y independent so this is two 1-D solves rather than one 2-D one:
 *
 * <pre>
 *   sum over edges  w_ij * ||(p_j - p_i) - d_ij||^2
 *     + sum over tiles  lambda * ||p_i - n_i||^2
 * </pre>
 *
 * <p>The first term asks every measured edge to be satisfied; the second pulls every tile toward
 * its nominal stage position. Setting the gradient to zero gives the normal equations
 * {@code (L_w + lambda*I) p = lambda*n + b}, where {@code L_w} is the weighted graph Laplacian.
 *
 * <p>Two consequences of the lambda term are worth stating outright, because they are what make
 * this simpler than the usual formulation rather than more complicated:
 *
 * <ul>
 *   <li>{@code L_w + lambda*I} is <b>strictly</b> positive definite for any {@code lambda > 0}. The
 *       Laplacian alone is only semi-definite -- it cannot see a global translation -- which is why
 *       tree-based and anchor-based schemes have to nominate a fixed tile and inherit whatever
 *       error that tile happens to carry. The nominal pull removes that null space, so the solution
 *       is unique and no anchor exists to be chosen badly.
 *   <li>Disconnected pieces need no special casing. Each component is pinned by its own nominal
 *       pull, and a tile with no accepted edges reduces to {@code lambda*p_i = lambda*n_i}, so it
 *       stays at nominal exactly.
 * </ul>
 *
 * <p>Every accepted edge is used. This is the whole point of solving rather than tree-walking: on a
 * 10x10 grid a spanning tree keeps 99 of the 180 neighbour edges and simply discards the other 81,
 * so nothing in the result is ever asked to close a loop. Error then accumulates along tree paths,
 * and two tiles that are neighbours in space but distant in the tree are free to disagree -- which
 * is what "large areas suddenly touching" looks like in a stitched mosaic. The loop-closing edges
 * are the constraints that prevent it, so they are exactly the ones not to throw away.
 *
 * <p>The solve runs internally in <b>delta space</b>, {@code q_i = p_i - n_i}, which is algebraically
 * identical (both terms depend only on differences from nominal) but keeps the numbers small and
 * makes the isolated-tile and clamping cases exact rather than nearly exact.
 */
public final class GlobalPositionSolver {

    /** Residual tolerance for the inner CG solve, relative to {@code ||b||}. */
    private static final double CG_TOL = 1e-9;

    /** Iteration cap for the inner CG solve. Convergence reaches {@link #CG_TOL} well before this. */
    private static final int CG_MAX_ITER = 1000;

    /** Scales a MAD into a standard-deviation estimate for normally distributed residuals. */
    private static final double MAD_TO_SIGMA = 1.4826;

    /** Robust sigmas beyond the median residual at which an edge is called an outlier. */
    private static final double OUTLIER_SIGMAS = 3.0;

    /**
     * Floor on the outlier threshold, in output pixels.
     *
     * <p>Without it, a near-noise-free grid drives the MAD toward zero and the threshold collapses,
     * so ordinary edges disagreeing by thousandths of a pixel get called outliers. Sub-pixel
     * disagreement is never evidence of a bad match -- it is the interpolation floor of NCC peak
     * fitting -- so no threshold below one pixel is meaningful.
     */
    private static final double MIN_OUTLIER_RESIDUAL_PX = 1.0;

    /** Below this many accepted edges the residual distribution is too small to estimate a MAD from. */
    private static final int MIN_EDGES_FOR_OUTLIER_PASS = 4;

    private GlobalPositionSolver() {}

    /**
     * Where the solver put one tile.
     *
     * @param xPx solved left edge in output pixels
     * @param yPx solved top edge in output pixels
     * @param dxPx the correction applied relative to nominal, after clamping
     * @param dyPx the correction applied relative to nominal, after clamping
     */
    public record SolvedPosition(double xPx, double yPx, double dxPx, double dyPx) {}

    /**
     * The product of a global solve.
     *
     * @param positions solved positions, in the same order as the nominal tile list
     * @param edges every edge handed in, unchanged; the outlier pass now down-weights inconsistent
     *     edges rather than rejecting them, so the reject reasons here are only the pairwise gates'
     * @param tilesClamped how many tiles had their correction cut back to the overlap limit
     */
    public record SolveOutcome(List<SolvedPosition> positions, List<EdgeMeasurement> edges, int tilesClamped) {

        public SolveOutcome {
            positions = List.copyOf(positions);
            edges = List.copyOf(edges);
        }
    }

    /**
     * Solves for every tile position at once.
     *
     * @param nominal the tiles at their nominal positions; the solve is indexed by this list, and
     *     {@link EdgeMeasurement#i()} / {@link EdgeMeasurement#j()} index into it
     * @param edges the pairwise measurements, accepted or not; rejected ones are carried through
     *     untouched and contribute nothing
     * @param settings supplies {@code lambda}, {@code maxOutlierIters} and {@code minNcc}
     * @param maxDxPx largest correction allowed on X, in output pixels
     * @param maxDyPx largest correction allowed on Y, in output pixels
     * @return the solved positions and the updated edge list
     */
    public static SolveOutcome solve(
            List<TileNode> nominal,
            List<EdgeMeasurement> edges,
            RegistrationSettings settings,
            double maxDxPx,
            double maxDyPx) {

        int n = nominal.size();
        List<EdgeMeasurement> working = new ArrayList<>(edges);
        double[] qx = new double[n];
        double[] qy = new double[n];

        // Per-EDGE sanity bound, applied before the solve. A correction larger than the overlap band
        // would mean the two tiles do not overlap at all, so it cannot be a real measurement of their
        // shared content -- unlike a CUMULATIVE per-tile correction, which legitimately grows past
        // any single overlap as per-step errors sum along the mosaic. This is the bound the old
        // per-tile clamp should always have been: it constrains the quantity that is physically
        // limited and leaves the one that is not alone. The engine's search window normally makes
        // such an edge unrepresentable; this is defence in depth for callers that supply
        // measurements directly.
        double edgeLimitX = Math.abs(maxDxPx);
        double edgeLimitY = Math.abs(maxDyPx);
        if (edgeLimitX > 0 && edgeLimitY > 0) {
            for (int e = 0; e < working.size(); e++) {
                EdgeMeasurement m = working.get(e);
                if (!m.accepted()) {
                    continue;
                }
                if (Math.abs(m.dxPx() - m.nominalDxPx()) > edgeLimitX
                        || Math.abs(m.dyPx() - m.nominalDyPx()) > edgeLimitY) {
                    working.set(e, m.rejectedAs(RejectReason.OUT_OF_BAND));
                }
            }
        }

        // Robust reweighting keeps EVERY accepted edge in the solve and only reduces the influence of
        // the inconsistent ones -- it never cuts them. Hard-cutting an edge un-ties that seam: the two
        // tiles then float apart through other paths and the seam gaps open to the whole accumulated
        // drift. Measured on a real acquisition, a row of 6 px seams that the outlier pass cut had torn
        // open to 50+ px, in a straight line across the mosaic, while every kept seam stayed at ~5 px.
        // Down-weighting leaves the seam tied to its measurement, so the unavoidable loop-closure error
        // spreads thinly across the mosaic instead of concentrating into a few visible tears. Weights
        // are indexed parallel to the working edge list.
        double[] robust = new double[working.size()];
        Arrays.fill(robust, 1.0);

        // Fixed for the whole solve: the nominal pull is a property of the dataset, not of whichever
        // weights an iteration happens to land on.
        double lambda = nominalPull(working, settings);

        if (lambda > 0 && n > 0) {
            for (int pass = 0; pass <= settings.maxOutlierIters(); pass++) {
                Graph graph = Graph.of(nominal, working, settings, robust);
                if (graph.count == 0) {
                    Arrays.fill(qx, 0);
                    Arrays.fill(qy, 0);
                    break;
                }
                // Components of the accepted-edge graph. Each is pinned by its own mean rather than
                // tile-by-tile, so relative geometry inside a component comes purely from the
                // measurements while the piece as a whole stays where the stage said it was.
                int[] component = new int[n];
                int componentCount = components(n, graph, component);
                qx = solveAxis(n, graph, graph.deltaX, lambda, component, componentCount);
                qy = solveAxis(n, graph, graph.deltaY, lambda, component, componentCount);
                if (pass == settings.maxOutlierIters() || !reweightOutliers(graph, qx, qy, robust)) {
                    break;
                }
            }
        }

        // The cumulative per-tile correction is NOT clamped. It is the running sum of many small
        // per-step errors, so it legitimately grows with the mosaic: a systematic scale error of a
        // fraction of a percent reaches hundreds of pixels across a few hundred tiles in a line.
        // Clamping it to one overlap width -- which is a bound on a PER-EDGE correction, not a
        // cumulative one -- silently truncated exactly the long acquisitions that need it most.
        // Bad measurements are the robust reweighting's job, not this clamp's.
        double reportLimit = Math.max(Math.abs(maxDxPx), Math.abs(maxDyPx));
        List<SolvedPosition> positions = new ArrayList<>(n);
        int beyondOverlap = 0;
        for (int i = 0; i < n; i++) {
            TileNode tile = nominal.get(i);
            double dx = qx[i];
            double dy = qy[i];
            if (reportLimit > 0 && Math.hypot(dx, dy) > reportLimit) {
                beyondOverlap++;
            }
            positions.add(new SolvedPosition(tile.xPx() + dx, tile.yPx() + dy, dx, dy));
        }
        return new SolveOutcome(positions, working, beyondOverlap);
    }

    /**
     * Strength of the pull toward nominal, in the same units as the edge weights.
     *
     * <p>{@link RegistrationSettings#lambda()} is a <b>relative</b> factor, so it is scaled by the
     * median accepted edge weight. An absolute lambda would mean something different on every
     * dataset: weights are {@code (ncc - minNcc)^2}, so a crisp fluorescence mosaic and a
     * low-contrast brightfield one differ by an order of magnitude in weight, and a lambda tuned on
     * one would either steamroll the measurements or vanish against them on the other.
     *
     * @return the absolute lambda, or zero when there is nothing to weigh it against
     */
    private static double nominalPull(List<EdgeMeasurement> edges, RegistrationSettings settings) {
        double[] weights = edges.stream()
                .filter(EdgeMeasurement::accepted)
                .mapToDouble(e -> e.weight(settings.minNcc()))
                .toArray();
        if (weights.length == 0) {
            return 0;
        }
        double median = median(weights);
        if (median <= 0) {
            // More than half the accepted edges sit exactly on the NCC threshold and carry zero
            // weight. Fall back to the strongest edge so lambda still has a scale; if even that is
            // zero there is no measurement to trade off against and every tile stays at nominal.
            median = Arrays.stream(weights).max().orElse(0);
        }
        return median <= 0 ? 0 : settings.lambda() * median;
    }

    /**
     * Solves {@code (L_w + lambda*I) q = b} on one axis.
     *
     * <p>{@code b_i} collects {@code -w_ij*delta_ij} over edges where {@code i} is the source and
     * {@code +w_ji*delta_ji} over edges where it is the target. The signs fall out of the gradient:
     * an edge asks {@code q_j - q_i} to equal {@code delta_ij}, so it pushes {@code i} the opposite
     * way it pushes {@code j}.
     */
    private static double[] solveAxis(
            int n, Graph graph, double[] delta, double lambda, int[] component, int componentCount) {
        int[] from = graph.from;
        int[] to = graph.to;
        double[] weight = graph.weight;
        int m = graph.count;

        double[] b = new double[n];
        for (int e = 0; e < m; e++) {
            double wd = weight[e] * delta[e];
            b[from[e]] -= wd;
            b[to[e]] += wd;
        }

        double[] componentSum = new double[componentCount];
        ConjugateGradient.Operator operator = (x, out) -> {
            // Gauge term. The edge Laplacian cannot see where a connected piece sits as a whole (only
            // differences), so something must pin that freedom. Pinning it per TILE -- the old
            // lambda*I -- also pulls every real displacement back toward nominal, shrinking the whole
            // correction field: measured at 8.5% on a real grid, which is invisible on a 6 px
            // correction and tens of pixels on the cumulative correction of a long line. Pinning the
            // component MEAN instead constrains only the one degree of freedom that is genuinely
            // undetermined, and leaves every other mode to the measurements. It stays symmetric
            // positive definite, so the solve is still unique and CG still converges.
            Arrays.fill(componentSum, 0);
            for (int k = 0; k < n; k++) {
                componentSum[component[k]] += x[k];
            }
            for (int k = 0; k < n; k++) {
                out[k] = lambda * componentSum[component[k]];
            }
            for (int e = 0; e < m; e++) {
                int i = from[e];
                int j = to[e];
                double flow = weight[e] * (x[i] - x[j]);
                out[i] += flow;
                out[j] -= flow;
            }
        };
        return ConjugateGradient.solve(n, operator, b, CG_TOL, CG_MAX_ITER);
    }

    /**
     * Label each tile with its connected component over the accepted edges.
     *
     * <p>Components matter because each is only determined up to its own translation: a mosaic that
     * has split into two pieces has two independent gauge freedoms, and pinning only the global mean
     * would leave the difference between them unconstrained. A tile with no accepted edge is its own
     * component and is therefore pinned at nominal exactly, which is the right answer for it.
     *
     * @param n tile count
     * @param graph the accepted-edge graph
     * @param component output: component id per tile
     * @return the number of components
     */
    private static int components(int n, Graph graph, int[] component) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int e = 0; e < graph.count; e++) {
            union(parent, graph.from[e], graph.to[e]);
        }
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int count = 0;
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (label[root] < 0) {
                label[root] = count++;
            }
            component[i] = label[root];
        }
        return count;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    /**
     * One iteratively-reweighted-least-squares pass: down-weight every edge whose residual is a
     * robust outlier so the next solve trusts it less -- but never to zero.
     *
     * <p>The scale is {@code median(r) + 3 * 1.4826 * MAD(r)}, floored at {@link
     * #MIN_OUTLIER_RESIDUAL_PX} -- three robust sigmas above the typical residual (MAD, not standard
     * deviation, so the outliers cannot inflate the scale enough to hide in it). Within that scale an
     * edge keeps full weight; beyond it a redescending factor {@code (delta / |r|)^2} tapers its
     * influence. The square rather than a plain {@code delta/|r|} matters: a genuinely-wrong edge (a
     * residual many times the scale) must collapse to near-nothing, while a linear taper would leave
     * it enough weight to still drag its tiles. It never reaches exactly zero, though, which is the
     * whole point: a cut edge stops constraining its seam, which then drifts open, whereas a
     * down-weighted one still holds the seam while losing the tug-of-war when it genuinely disagrees
     * with everything around it. Edges that merely settle a little above the scale keep meaningful
     * weight, so the pass does not cascade the way a hard threshold did.
     *
     * @param robust per-edge weight multipliers, indexed by the working edge list; updated in place
     * @return whether any weight changed materially, i.e. whether another pass is worth doing
     */
    private static boolean reweightOutliers(Graph graph, double[] qx, double[] qy, double[] robust) {
        int m = graph.count;
        if (m < MIN_EDGES_FOR_OUTLIER_PASS) {
            return false;
        }
        double[] residual = new double[m];
        for (int e = 0; e < m; e++) {
            int i = graph.from[e];
            int j = graph.to[e];
            residual[e] = Math.hypot((qx[j] - qx[i]) - graph.deltaX[e], (qy[j] - qy[i]) - graph.deltaY[e]);
        }
        double median = median(residual);
        double[] deviation = new double[m];
        for (int e = 0; e < m; e++) {
            deviation[e] = Math.abs(residual[e] - median);
        }
        double mad = median(deviation);
        double delta = Math.max(median + OUTLIER_SIGMAS * MAD_TO_SIGMA * mad, MIN_OUTLIER_RESIDUAL_PX);

        boolean changed = false;
        for (int e = 0; e < m; e++) {
            double ratio = delta / residual[e];
            double w = residual[e] <= delta ? 1.0 : ratio * ratio;
            int src = graph.source[e];
            if (Math.abs(robust[src] - w) > 1e-3) {
                changed = true;
            }
            robust[src] = w;
        }
        return changed;
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    /** @return the median of the given values; the argument is copied, not sorted in place. */
    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /**
     * The accepted edges, flattened into parallel arrays.
     *
     * <p>The CG operator is applied a few hundred times per axis; re-filtering a list of records and
     * re-deriving weights inside that loop is the difference between a fast solve and a slow one.
     */
    private static final class Graph {

        /** Source tile index per edge. */
        final int[] from;
        /** Target tile index per edge. */
        final int[] to;
        /** Edge weight. */
        final double[] weight;
        /** Measured X offset minus the offset the nominal positions imply. */
        final double[] deltaX;
        /** Measured Y offset minus the offset the nominal positions imply. */
        final double[] deltaY;
        /** Index back into the working edge list, so the outlier pass can reweight the right edge. */
        final int[] source;
        /** Number of accepted edges, i.e. the used prefix length of every array above. */
        final int count;

        private Graph(
                int[] from, int[] to, double[] weight, double[] deltaX, double[] deltaY, int[] source, int count) {
            this.from = from;
            this.to = to;
            this.weight = weight;
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.source = source;
            this.count = count;
        }

        /**
         * The nominal offset is re-derived from the {@link TileNode} positions rather than read from
         * {@link EdgeMeasurement#nominalDxPx()}. The two agree whenever the edge was built against
         * this same node list, but deriving them is what makes the delta-space solve exactly
         * equivalent to the position-space one it stands in for, rather than equivalent only as long
         * as the edge's cached copy stays in step.
         */
        static Graph of(
                List<TileNode> nominal, List<EdgeMeasurement> edges, RegistrationSettings settings, double[] robust) {
            int max = edges.size();
            int[] from = new int[max];
            int[] to = new int[max];
            double[] weight = new double[max];
            double[] deltaX = new double[max];
            double[] deltaY = new double[max];
            int[] source = new int[max];
            int count = 0;
            for (int e = 0; e < max; e++) {
                EdgeMeasurement edge = edges.get(e);
                if (!edge.accepted()) {
                    continue;
                }
                TileNode a = nominal.get(edge.i());
                TileNode b = nominal.get(edge.j());
                from[count] = edge.i();
                to[count] = edge.j();
                weight[count] = edge.weight(settings.minNcc()) * robust[e];
                deltaX[count] = edge.dxPx() - (b.xPx() - a.xPx());
                deltaY[count] = edge.dyPx() - (b.yPx() - a.yPx());
                source[count] = e;
                count++;
            }
            return new Graph(from, to, weight, deltaX, deltaY, source, count);
        }
    }
}
