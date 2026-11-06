package qupath.ext.basicstitching.progress;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Estimates how long a stitch has left. Pure: no QuPath, no I/O, an injectable clock.
 *
 * <h2>Model</h2>
 *
 * A stitch is three phases run in order, each with a cost proportional to a count known before it
 * starts:
 *
 * <ul>
 *   <li>{@link StitchPhase#SCAN}: one header read per tile, so seconds per tile;
 *   <li>{@link StitchPhase#SOLVE}: one normalized cross-correlation per neighbor pair over the
 *       overlap band, so seconds per band pixel (tile size and overlap both enter here); zero when
 *       the stitch only applies a solution;
 *   <li>{@link StitchPhase#WRITE}: compositing and encoding every pyramid level, so seconds per
 *       uncompressed output byte (width x height x channels x bytes per sample, summed over the
 *       levels; bit depth and channel count enter here). On the PPM rig (J2K, 2026-11-06) this was
 *       1.66e-7 s/byte at full resolution and 1.72e-7 s/byte for staged levels, so one rate covers
 *       every level.
 * </ul>
 *
 * <h2>Estimate</h2>
 *
 * Before a phase has run long enough to measure, it is costed at the prior rate (learned from
 * earlier stitches, see {@link LearnedRates}, else the defaults). Once it has run at least
 * {@link #WARMUP_SECONDS} and moved, its remaining time comes from the speed measured over the
 * last {@link #WINDOW_SECONDS}, and the phases after it are costed at the prior rate times the
 * slowdown measured so far (measured over prior). That slowdown is what absorbs everything the
 * counts do not see: other stitches running at once, a slow disk, a busy CPU.
 */
public final class StitchEta {

    /** Seconds a phase must run, and move, before its own speed is trusted. */
    static final double WARMUP_SECONDS = 30;

    /** Speed is measured over this much of the most recent progress. */
    static final double WINDOW_SECONDS = 600;

    /** Slowdown is clamped to this range so one odd phase cannot make the estimate absurd. */
    static final double MIN_SLOWDOWN = 0.1;

    static final double MAX_SLOWDOWN = 50;

    /**
     * The counts the phases are costed by.
     *
     * @param tiles tiles to scan
     * @param solveBandPixels total overlap-band pixels the solve correlates, 0 when not solving
     * @param writeBytes uncompressed bytes of every pyramid level of the output
     */
    public record Work(long tiles, long solveBandPixels, long writeBytes) {

        /** The amount of work of one phase, in that phase's unit. */
        public double amount(StitchPhase phase) {
            return switch (phase) {
                case SCAN -> tiles;
                case SOLVE -> solveBandPixels;
                case WRITE -> writeBytes;
            };
        }
    }

    /**
     * Seconds per unit of each phase's work.
     *
     * @param scanSecondsPerTile header read per tile
     * @param solveSecondsPerBandPixel correlation per overlap-band pixel
     * @param writeSecondsPerByte composite + encode per uncompressed output byte
     */
    public record Rates(double scanSecondsPerTile, double solveSecondsPerBandPixel, double writeSecondsPerByte) {

        public double perUnit(StitchPhase phase) {
            return switch (phase) {
                case SCAN -> scanSecondsPerTile;
                case SOLVE -> solveSecondsPerBandPixel;
                case WRITE -> writeSecondsPerByte;
            };
        }

        Rates with(StitchPhase phase, double value) {
            return switch (phase) {
                case SCAN -> new Rates(value, solveSecondsPerBandPixel, writeSecondsPerByte);
                case SOLVE -> new Rates(scanSecondsPerTile, value, writeSecondsPerByte);
                case WRITE -> new Rates(scanSecondsPerTile, solveSecondsPerBandPixel, value);
            };
        }

        /**
         * Starting rates for a compression, before anything was learned. Scan and solve come from
         * the 2026-11-06 PPM rig log (2,381 tiles of 2064 x 1544 px: 47 s to scan; 4,226 edges
         * with bands of about 315,000 px: 705 s to solve); the J2K write rate from the same log.
         * The other codecs' write rates are rough (a 12000 x 10000 RGB LZW write in memory ran at
         * 3.5e-8 s/byte) and are replaced by measured ones after the first stitch.
         */
        public static Rates defaults(String compression) {
            String c = compression == null ? "" : compression.toUpperCase(Locale.ROOT);
            double write;
            if (c.contains("J2K") || c.contains("JPEG2000") || c.contains("JPEG-2000")) {
                write = 1.7e-7;
            } else if (c.contains("LZW")) {
                write = 5e-8;
            } else if (c.contains("ZLIB") || c.contains("DEFLATE") || c.contains("ZIP")) {
                write = 8e-8;
            } else if (c.contains("JPEG")) {
                write = 4e-8;
            } else {
                write = 2e-8;
            }
            return new Rates(0.02, 5.3e-7, write);
        }
    }

    private final LongSupplier nanos;
    private final long startNanos;
    private Rates prior;
    private Work work;

    private StitchPhase phase;
    private long phaseStartNanos;
    private double fraction;
    /** (seconds since start, fraction) samples of the current phase, oldest first. */
    private final ArrayDeque<double[]> window = new ArrayDeque<>();

    private double slowdown = 1.0;
    private boolean slowdownMeasured;

    public StitchEta(Work work, Rates prior, LongSupplier nanos) {
        this.work = work;
        this.prior = prior;
        this.nanos = nanos;
        this.startNanos = nanos.getAsLong();
    }

    /** Seconds a whole stitch should take at {@code rates}, before it starts. */
    public static double predictSeconds(Work work, Rates rates) {
        double total = 0;
        for (StitchPhase p : StitchPhase.values()) {
            total += work.amount(p) * rates.perUnit(p);
        }
        return total;
    }

    /** Replace the counts once they are known better (e.g. the output size when writing starts). */
    public synchronized void setWork(Work work) {
        this.work = work;
    }

    public synchronized Work work() {
        return work;
    }

    /** Record progress: the phase running now and how much of it is done (0 to 1). */
    public synchronized void update(StitchPhase newPhase, double newFraction) {
        long now = nanos.getAsLong();
        if (newPhase != phase) {
            if (phase != null) {
                closePhase(now);
            }
            phase = newPhase;
            phaseStartNanos = now;
            window.clear();
        }
        fraction = Math.max(0, Math.min(1, newFraction));
        double t = seconds(now - startNanos);
        window.addLast(new double[] {t, fraction});
        while (window.size() > 2 && t - window.peekFirst()[0] > WINDOW_SECONDS) {
            window.removeFirst();
        }
        double phaseSeconds = seconds(now - phaseStartNanos);
        double priorPhase = work.amount(phase) * prior.perUnit(phase);
        if (phaseSeconds >= WARMUP_SECONDS && fraction > 0 && priorPhase > 0) {
            slowdown = clamp((phaseSeconds / fraction) / priorPhase);
            slowdownMeasured = true;
        }
    }

    /** End of the stitch: the last phase's measured rate, for learning. */
    public synchronized void finish() {
        if (phase != null) {
            closePhase(nanos.getAsLong());
            phase = null;
        }
    }

    /** Rates measured in this stitch per phase, NaN where a phase was too short to measure. */
    public synchronized double measuredRate(StitchPhase p) {
        return measured[p.ordinal()];
    }

    private final double[] measured = {Double.NaN, Double.NaN, Double.NaN};

    private void closePhase(long now) {
        double s = seconds(now - phaseStartNanos);
        double amount = work.amount(phase) * Math.max(fraction, 0);
        if (s >= WARMUP_SECONDS && amount > 0) {
            measured[phase.ordinal()] = s / amount;
        }
    }

    /** Seconds since the stitch started. */
    public synchronized double elapsedSeconds() {
        return seconds(nanos.getAsLong() - startNanos);
    }

    public synchronized StitchPhase phase() {
        return phase;
    }

    public synchronized double phaseFraction() {
        return fraction;
    }

    /** Whether the estimate already rests on speed measured in this stitch. */
    public synchronized boolean measured() {
        return slowdownMeasured;
    }

    /** The current slowdown (measured over prior), 1 until a phase has been measured. */
    public synchronized double slowdown() {
        return slowdown;
    }

    /** Estimated seconds left for the whole stitch. */
    public synchronized double remainingSeconds() {
        if (phase == null) {
            return predictSeconds(work, prior) * slowdown;
        }
        double now = seconds(nanos.getAsLong() - startNanos);
        double current = currentPhaseRemaining(now);
        double later = 0;
        for (StitchPhase p : StitchPhase.values()) {
            if (p.ordinal() > phase.ordinal()) {
                later += work.amount(p) * prior.perUnit(p) * slowdown;
            }
        }
        return current + later;
    }

    private double currentPhaseRemaining(double now) {
        double phaseSeconds = now - seconds(phaseStartNanos - startNanos);
        double left = 1 - fraction;
        if (left <= 0) {
            return 0;
        }
        if (phaseSeconds >= WARMUP_SECONDS && window.size() >= 2) {
            double[] first = window.peekFirst();
            double[] last = window.peekLast();
            double df = last[1] - first[1];
            double dt = last[0] - first[0];
            if (df > 0 && dt > 0) {
                return left * dt / df;
            }
        }
        return left * work.amount(phase) * prior.perUnit(phase) * slowdown;
    }

    private static double clamp(double v) {
        return Math.max(MIN_SLOWDOWN, Math.min(MAX_SLOWDOWN, v));
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }
}
