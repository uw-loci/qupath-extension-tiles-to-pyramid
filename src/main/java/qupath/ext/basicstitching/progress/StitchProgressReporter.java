package qupath.ext.basicstitching.progress;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Progress of one stitch: feeds a {@link StitchEta}, hands every update to the caller's listener,
 * and logs a status line with the time left at each phase change and every
 * {@link #LOG_INTERVAL_SECONDS} in between, so a stitch that runs for hours says when it will end.
 *
 * <p>Thread-safe: the solve reports from its worker threads.
 */
public final class StitchProgressReporter {

    private static final Logger logger = LoggerFactory.getLogger(StitchProgressReporter.class);

    /** Seconds between status lines within a phase. */
    static final double LOG_INTERVAL_SECONDS = 300;

    /** Listener calls are throttled to this, apart from phase changes. */
    static final double NOTIFY_INTERVAL_SECONDS = 2;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final String target;
    private final String compression;
    private final Consumer<StitchProgress> listener;
    private final StitchEta eta;

    private StitchPhase lastPhase;
    private double lastLog = Double.NEGATIVE_INFINITY;
    private double lastNotify = Double.NEGATIVE_INFINITY;
    private String detail = "";

    /**
     * @param target what is being stitched, for messages
     * @param compression the output compression, which selects the learned write rate
     * @param work the counts known now (refined later with {@link #setWork})
     * @param listener receives every update; may be null
     */
    public StitchProgressReporter(
            String target, String compression, StitchEta.Work work, Consumer<StitchProgress> listener) {
        this.target = target;
        this.compression = compression;
        this.listener = listener;
        this.eta = new StitchEta(work, LearnedRates.load(compression), System::nanoTime);
    }

    public void setWork(StitchEta.Work work) {
        eta.setWork(work);
    }

    // -- the thread's current stitch, so the scan, solve and write code it calls can report
    // without every signature carrying a reporter

    private static final ThreadLocal<StitchProgressReporter> CURRENT = new ThreadLocal<>();

    /** The stitch running on this thread, or null. */
    public static StitchProgressReporter current() {
        return CURRENT.get();
    }

    /** Make this the current stitch of this thread until the returned scope is closed. */
    public Scope bind() {
        StitchProgressReporter previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** Ends a {@link #bind()}. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    // -- the write phase, over one or more outputs (subdirectory groups) written in turn

    private long[] groupBytes = new long[0];
    private int group;

    /** The outputs this stitch will write, as estimated uncompressed pyramid bytes each. */
    public synchronized void planWrite(long[] bytesPerOutput) {
        groupBytes = bytesPerOutput.clone();
        group = 0;
        refreshWriteWork();
    }

    /** Output {@code index} starts writing. */
    public synchronized void startOutput(int index, String name) {
        group = Math.max(0, Math.min(index, Math.max(0, groupBytes.length - 1)));
        update(StitchPhase.WRITE, writeFraction(0), name);
    }

    /** The current output's exact size, once its first tile has been read. */
    public synchronized void exactOutputBytes(long bytes) {
        if (group < groupBytes.length && bytes > 0) {
            groupBytes[group] = bytes;
            refreshWriteWork();
        }
    }

    /** Progress of the current output, 0 to 1 by uncompressed bytes. */
    public synchronized void outputProgress(double fraction) {
        update(StitchPhase.WRITE, writeFraction(fraction), null);
    }

    private double writeFraction(double fraction) {
        long total = 0, before = 0;
        for (int i = 0; i < groupBytes.length; i++) {
            total += groupBytes[i];
            if (i < group) {
                before += groupBytes[i];
            }
        }
        if (total <= 0) {
            return fraction;
        }
        long current = group < groupBytes.length ? groupBytes[group] : 0;
        return (before + fraction * current) / total;
    }

    private void refreshWriteWork() {
        long total = 0;
        for (long b : groupBytes) {
            total += b;
        }
        StitchEta.Work w = eta.work();
        eta.setWork(new StitchEta.Work(w.tiles(), w.solveBandPixels(), total));
    }

    public StitchEta.Work work() {
        return eta.work();
    }

    /** A phase starts (fraction 0). */
    public void start(StitchPhase phase, String detail) {
        update(phase, 0, detail);
    }

    /** Progress within a phase. */
    public synchronized void update(StitchPhase phase, double fraction, String newDetail) {
        if (newDetail != null) {
            detail = newDetail;
        }
        eta.update(phase, fraction);
        double t = eta.elapsedSeconds();
        boolean phaseChanged = phase != lastPhase;
        lastPhase = phase;
        if (phaseChanged || t - lastLog >= LOG_INTERVAL_SECONDS) {
            lastLog = t;
            log();
        }
        if (listener != null && (phaseChanged || fraction >= 1 || t - lastNotify >= NOTIFY_INTERVAL_SECONDS)) {
            lastNotify = t;
            try {
                listener.accept(snapshot());
            } catch (RuntimeException e) {
                logger.debug("Stitch progress listener threw: {}", e.toString());
            }
        }
    }

    /** The stitch ended; on success the measured rates are learned for the next estimate. */
    public synchronized void finish(boolean success) {
        eta.finish();
        if (success) {
            LearnedRates.learn(compression, eta);
        }
        logger.info(
                "Stitch of '{}' {} after {}",
                target,
                success ? "finished" : "ended without output",
                StitchProgress.formatDuration(eta.elapsedSeconds()));
    }

    /** The current state, as handed to listeners. */
    public synchronized StitchProgress snapshot() {
        return new StitchProgress(
                target,
                eta.phase() == null ? StitchPhase.SCAN : eta.phase(),
                eta.phaseFraction(),
                eta.elapsedSeconds(),
                eta.remainingSeconds(),
                eta.measured(),
                detail);
    }

    private void log() {
        StitchProgress p = snapshot();
        String ends = Double.isNaN(p.remainingSeconds())
                ? ""
                : " (ends about "
                        + LocalTime.now()
                                .plusSeconds((long) p.remainingSeconds())
                                .format(CLOCK)
                        + (p.measured() ? "" : ", not measured yet") + ")";
        logger.info(
                "Stitching '{}': {}{}, {}% of this step, {}{}",
                target,
                p.phase().label(),
                detail.isEmpty() ? "" : " (" + detail + ")",
                Math.round(p.phaseFraction() * 100),
                p.remainingText(),
                ends);
    }
}
