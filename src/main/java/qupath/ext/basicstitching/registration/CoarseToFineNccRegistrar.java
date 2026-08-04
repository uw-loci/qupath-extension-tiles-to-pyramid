package qupath.ext.basicstitching.registration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Measures tile offsets by a bounded, coarse-to-fine normalized cross-correlation search.
 *
 * <h2>Why a bounded search rather than FFT phase correlation</h2>
 *
 * Phase correlation finds the <b>global</b> peak over <i>all</i> shifts. That unbounded search is
 * exactly the mechanism by which it returns a confident, meaningless answer on repeating texture or
 * a blank field -- the answer then has to be measured and rejected after the fact. Constructing the
 * search window as the physically-possible correction instead makes an out-of-band answer
 * <b>unrepresentable</b>: the gate degenerates to "the peak pinned to the window edge, so the true
 * peak is outside", which is both stronger and cheaper.
 *
 * <p>It is also less arithmetic. For a 10x10 grid of 1400x1000 tiles at 10% overlap the pyramid
 * search is roughly 13 Gflop against roughly 20 Gflop for padded FFTs, and neither is the
 * bottleneck -- tile decode is. And the disambiguation step that phase correlation needs disappears
 * entirely: a real-valued correlation peak has a sign/wrap ambiguity that has to be resolved by
 * scoring the candidates with NCC, whereas here every candidate shift <i>is</i> its own
 * interpretation, already scored by that same metric.
 *
 * <h2>The search</h2>
 *
 * A full grid search at the coarsest level gives the whole correlation surface, which is what the
 * ambiguity gate needs. The top few peaks are then refined down the pyramid, each level searching
 * only a couple of pixels around its parent.
 *
 * <p>Bands are deliberately <b>not</b> windowed. Tapering the band edges is an FFT technique -- it
 * exists to stop a periodic transform wrapping one edge onto the other. A direct search compares
 * only real pixels against real pixels, so there is no artificial boundary to suppress, and
 * applying a taper anyway is actively destructive: the window envelope is smooth, large, and
 * identical in both bands, so it dominates the correlation and matches perfectly at zero shift. On a
 * narrow overlap band that pins every measurement to "no correction" while reporting high
 * confidence.
 */
public final class CoarseToFineNccRegistrar implements PairwiseRegistrar {

    /** Non-maximum-suppression radius when picking distinct peaks off the coarse surface. */
    private static final int NMS_RADIUS = 3;

    /** Refinement half-window at each finer pyramid level, in that level's pixels. */
    private static final int REFINE_RADIUS = 2;

    /** Peaks closer together than this (full-res px) are the same peak, not two candidates. */
    private static final double SAME_PEAK_DISTANCE_PX = 2.0;

    /** Median at or above this fraction of full scale means detail is clipped away. */
    private static final double SATURATION_FRACTION = 0.99;

    /**
     * Fraction of full-resolution variance a pyramid level must retain to be searched. Below this,
     * the downsample has smoothed away the structure the search needs.
     */
    private static final double SIGNAL_RETENTION = 0.2;

    /** A candidate shift and its score, in some pyramid level's pixels. */
    private record Peak(int ox, int oy, double ncc) {}

    @Override
    public EdgeMeasurement measure(
            NeighborGraphBuilder.EdgePair edge,
            OverlapBand bandA,
            OverlapBand bandB,
            double nominalDxPx,
            double nominalDyPx,
            int searchXPx,
            int searchYPx,
            RegistrationSettings settings) {

        int w = Math.min(bandA.width(), bandB.width());
        int h = Math.min(bandA.height(), bandB.height());

        // Band statistics are recorded for EVERY outcome, including the rejections, so the gate
        // thresholds can be read off real distributions rather than assumed. A rejected edge's
        // numbers are the interesting ones: they say how far from passing it actually was.
        Diag diag = new Diag(bandA, bandB);

        if (w < RegistrationSettings.MIN_BAND_PX || h < RegistrationSettings.MIN_BAND_PX) {
            return reject(edge, nominalDxPx, nominalDyPx, 0, RejectReason.NO_OVERLAP, diag.build(w, h, 0, 0, 1));
        }
        diag.band(w, h);

        // Featureless-band gates first: cheapest, and before any correlation work. A band with no
        // structure cannot produce a trustworthy peak no matter how confident the surface looks.
        if (bandA.textureScore() < settings.minCoeffOfVar() || bandB.textureScore() < settings.minCoeffOfVar()) {
            return reject(edge, nominalDxPx, nominalDyPx, 0, RejectReason.LOW_VARIANCE, diag.build(w, h, 0, 0, 1));
        }
        if (bandA.nearSaturated(SATURATION_FRACTION) || bandB.nearSaturated(SATURATION_FRACTION)) {
            return reject(edge, nominalDxPx, nominalDyPx, 0, RejectReason.LOW_VARIANCE, diag.build(w, h, 0, 0, 1));
        }

        // Search bounds: the physical correction limit, capped so at least half the band still
        // correlates at the extremes of the search.
        int searchX = Math.max(1, Math.min(searchXPx, w / 2));
        int searchY = Math.max(1, Math.min(searchYPx, h / 2));

        float[][] a = copy(bandA.gray(), w, h);
        float[][] b = copy(bandB.gray(), w, h);

        List<Integer> levels = pyramidLevels(settings.coarsestDownsample(), a, b, w, h, searchX, searchY);
        int coarsest = levels.get(0);

        float[][] ca = Ncc.downsampleBy(a, coarsest);
        float[][] cb = Ncc.downsampleBy(b, coarsest);
        int cw = Math.min(ca[0].length, cb[0].length);
        int ch = Math.min(ca.length, cb.length);
        int csx = Math.max(1, searchX / coarsest);
        int csy = Math.max(1, searchY / coarsest);

        List<Peak> peaks = topPeaks(ca, cb, cw, ch, csx, csy, settings.topKPeaks());
        if (peaks.isEmpty()) {
            return reject(
                    edge,
                    nominalDxPx,
                    nominalDyPx,
                    0,
                    RejectReason.LOW_NCC,
                    diag.build(w, h, searchX, searchY, coarsest));
        }
        if (peaks.size() >= 2) {
            diag.peaks(
                    peaks.get(0).ncc(),
                    peaks.get(1).ncc(),
                    Math.hypot(
                                    peaks.get(0).ox() - peaks.get(1).ox(),
                                    peaks.get(0).oy() - peaks.get(1).oy())
                            * coarsest);
        } else {
            diag.peaks(peaks.get(0).ncc(), Double.NaN, Double.NaN);
        }

        // Ambiguity: two well-separated candidates scoring comparably means repeating texture, and
        // there is no way to know which period is the true one. The separation clause matters --
        // adjacent high scores are the shoulders of one peak, not two competing answers.
        if (peaks.size() >= 2) {
            Peak best = peaks.get(0);
            Peak second = peaks.get(1);
            double separationPx = Math.hypot(best.ox() - second.ox(), best.oy() - second.oy()) * coarsest;
            if (second.ncc() > settings.ambiguityRatio() * best.ncc() && separationPx > SAME_PEAK_DISTANCE_PX) {
                return reject(
                        edge,
                        nominalDxPx,
                        nominalDyPx,
                        best.ncc(),
                        RejectReason.AMBIGUOUS,
                        diag.build(w, h, searchX, searchY, coarsest));
            }
        }

        // Refine every surviving candidate down the pyramid. Carrying more than the single best
        // guards against the true peak being marginally second at the coarsest level, where a
        // feature can be smoothed away.
        List<Peak> candidates = peaks;
        for (int i = 1; i < levels.size(); i++) {
            int ds = levels.get(i);
            float[][] la = Ncc.downsampleBy(a, ds);
            float[][] lb = Ncc.downsampleBy(b, ds);
            int lw = Math.min(la[0].length, lb[0].length);
            int lh = Math.min(la.length, lb.length);
            int parentDs = levels.get(i - 1);
            int scale = parentDs / ds;

            List<Peak> refined = new ArrayList<>();
            for (Peak p : candidates) {
                refined.add(refineAround(la, lb, lw, lh, p.ox() * scale, p.oy() * scale, searchX / ds, searchY / ds));
            }
            refined.sort(Comparator.comparingDouble(Peak::ncc).reversed());
            candidates = refined;
        }

        Peak best = candidates.get(0);
        int finest = levels.get(levels.size() - 1);
        int ox = best.ox() * finest;
        int oy = best.oy() * finest;

        diag.shift(ox, oy);
        EdgeDiagnostics diagnostics = diag.build(w, h, searchX, searchY, coarsest);

        // A peak pinned to the edge of the search window means the true peak lies outside the
        // physically-possible correction, so this match is spurious. Reject -- never silently clamp,
        // which would fabricate a plausible-looking answer.
        if (Math.abs(ox) > settings.bandMarginFrac() * searchX || Math.abs(oy) > settings.bandMarginFrac() * searchY) {
            return reject(edge, nominalDxPx, nominalDyPx, best.ncc(), RejectReason.OUT_OF_BAND, diagnostics);
        }
        if (best.ncc() < settings.minNcc()) {
            return reject(edge, nominalDxPx, nominalDyPx, best.ncc(), RejectReason.LOW_NCC, diagnostics);
        }

        return new EdgeMeasurement(
                edge.i(),
                edge.j(),
                nominalDxPx + ox,
                nominalDyPx + oy,
                nominalDxPx,
                nominalDyPx,
                best.ncc(),
                RejectReason.NONE,
                diagnostics);
    }

    /**
     * Accumulates the gate inputs as the measurement proceeds. Every field is something a threshold
     * is compared against, so a rejected edge can be read as "how close was it", and a default can be
     * chosen from a distribution instead of assumed.
     */
    private static final class Diag {
        private final OverlapBand a;
        private final OverlapBand b;
        private double bestNcc = Double.NaN;
        private double secondNcc = Double.NaN;
        private double separationPx = Double.NaN;
        private double shiftX = Double.NaN;
        private double shiftY = Double.NaN;

        Diag(OverlapBand a, OverlapBand b) {
            this.a = a;
            this.b = b;
        }

        void band(int w, int h) {
            // Band size is passed to build(); nothing to record here beyond keeping the call explicit.
        }

        void peaks(double best, double second, double separation) {
            this.bestNcc = best;
            this.secondNcc = second;
            this.separationPx = separation;
        }

        void shift(double x, double y) {
            this.shiftX = x;
            this.shiftY = y;
        }

        EdgeDiagnostics build(int w, int h, int searchX, int searchY, int coarsest) {
            double ratio = Double.isNaN(secondNcc) || bestNcc == 0 ? Double.NaN : secondNcc / bestNcc;
            return new EdgeDiagnostics(
                    a.textureScore(),
                    b.textureScore(),
                    a.median(),
                    b.median(),
                    a.maxPossibleValue(),
                    bestNcc,
                    secondNcc,
                    ratio,
                    separationPx,
                    shiftX,
                    shiftY,
                    searchX,
                    searchY,
                    w,
                    h,
                    coarsest);
        }
    }

    /**
     * Pyramid levels from coarsest to full resolution.
     *
     * <p>Two reasons to stop short of the requested coarsest level.
     *
     * <p>The obvious one: a level whose band or search window has shrunk to nothing cannot be
     * searched.
     *
     * <p>The subtle and more important one: downsampling is a box filter, and a box filter
     * annihilates structure near its own scale. Fine repeating texture -- a grating, regular tissue,
     * a sensor artifact -- disappears entirely by the time it is downsampled 8x. That matters
     * because the coarsest level is where the whole correlation surface is computed, and therefore
     * the only place the ambiguity gate can see that a repeating pattern offers several equally good
     * answers. Search a level where the pattern has been smoothed away and the surface looks like
     * benign mush; the refinement then walks down to one of the competing peaks and reports it with
     * high confidence. So: only go as coarse as still retains the signal.
     */
    private static List<Integer> pyramidLevels(
            int coarsest, float[][] a, float[][] b, int w, int h, int searchX, int searchY) {
        int usable = coarsest;
        while (usable > 1
                && (w / usable < RegistrationSettings.MIN_BAND_PX
                        || h / usable < RegistrationSettings.MIN_BAND_PX
                        || searchX / usable < 1
                        || searchY / usable < 1)) {
            usable /= 2;
        }

        double fullVarA = Ncc.variance(a, w, h);
        double fullVarB = Ncc.variance(b, w, h);
        while (usable > 1
                && (retained(a, usable) < SIGNAL_RETENTION * fullVarA
                        || retained(b, usable) < SIGNAL_RETENTION * fullVarB)) {
            usable /= 2;
        }

        List<Integer> levels = new ArrayList<>();
        for (int ds = usable; ds >= 1; ds /= 2) {
            levels.add(ds);
        }
        return levels;
    }

    private static double retained(float[][] img, int ds) {
        float[][] down = Ncc.downsampleBy(img, ds);
        return Ncc.variance(down, down[0].length, down.length);
    }

    /** Full grid search, then non-maximum suppression to pull out distinct candidates. */
    private static List<Peak> topPeaks(float[][] a, float[][] b, int w, int h, int sx, int sy, int k) {
        int nx = 2 * sx + 1;
        int ny = 2 * sy + 1;
        double[][] surface = new double[ny][nx];
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                surface[iy][ix] = Ncc.atShift(a, b, ix - sx, iy - sy, w, h);
            }
        }

        List<Peak> all = new ArrayList<>();
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                double v = surface[iy][ix];
                if (v <= Ncc.NO_MATCH || !isLocalMax(surface, ix, iy, nx, ny)) {
                    continue;
                }
                all.add(new Peak(ix - sx, iy - sy, v));
            }
        }
        all.sort(Comparator.comparingDouble(Peak::ncc).reversed());
        return all.size() > k ? new ArrayList<>(all.subList(0, k)) : all;
    }

    private static boolean isLocalMax(double[][] surface, int x, int y, int nx, int ny) {
        double v = surface[y][x];
        for (int dy = -NMS_RADIUS; dy <= NMS_RADIUS; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= ny) {
                continue;
            }
            for (int dx = -NMS_RADIUS; dx <= NMS_RADIUS; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= nx || (dx == 0 && dy == 0)) {
                    continue;
                }
                if (surface[yy][xx] > v) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Best shift within a small window around a parent level's estimate. */
    private static Peak refineAround(
            float[][] a, float[][] b, int w, int h, int centreX, int centreY, int limitX, int limitY) {
        double bestNcc = Ncc.NO_MATCH;
        int bestX = centreX;
        int bestY = centreY;
        for (int oy = centreY - REFINE_RADIUS; oy <= centreY + REFINE_RADIUS; oy++) {
            for (int ox = centreX - REFINE_RADIUS; ox <= centreX + REFINE_RADIUS; ox++) {
                if (Math.abs(ox) > Math.max(1, limitX) || Math.abs(oy) > Math.max(1, limitY)) {
                    continue;
                }
                double ncc = Ncc.atShift(a, b, ox, oy, w, h);
                if (ncc > bestNcc) {
                    bestNcc = ncc;
                    bestX = ox;
                    bestY = oy;
                }
            }
        }
        return new Peak(bestX, bestY, bestNcc);
    }

    private static float[][] copy(float[][] src, int w, int h) {
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            System.arraycopy(src[y], 0, out[y], 0, w);
        }
        return out;
    }

    private static EdgeMeasurement reject(
            NeighborGraphBuilder.EdgePair edge,
            double nominalDx,
            double nominalDy,
            double ncc,
            RejectReason why,
            EdgeDiagnostics diagnostics) {
        return new EdgeMeasurement(
                edge.i(), edge.j(), nominalDx, nominalDy, nominalDx, nominalDy, ncc, why, diagnostics);
    }
}
