package qupath.ext.basicstitching.progress;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.beans.property.DoubleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * The phase rates measured in earlier stitches on this computer, kept in QuPath's preferences
 * per compression (the write rate depends on the codec far more than on anything else), so the
 * first estimate of the next stitch starts from what this machine actually did.
 *
 * <p>Each finished phase moves the stored rate a third of the way toward the one it measured, so
 * one unusual stitch does not take over. Rates measured while other stitches ran at the same time
 * are slower than a lone stitch's; that is the usual case on an acquisition rig, so they are kept
 * as measured, and a stitch's own measured speed corrects the estimate within a minute anyway.
 */
public final class LearnedRates {

    private static final Logger logger = LoggerFactory.getLogger(LearnedRates.class);

    /** Weight of a new measurement. */
    static final double ALPHA = 1.0 / 3.0;

    private static final Map<String, DoubleProperty> PREFS = new ConcurrentHashMap<>();

    private LearnedRates() {}

    /** The rates to start a stitch with: learned where available, defaults otherwise. */
    public static StitchEta.Rates load(String compression) {
        StitchEta.Rates rates = StitchEta.Rates.defaults(compression);
        for (StitchPhase phase : StitchPhase.values()) {
            double v = read(key(compression, phase));
            if (v > 0 && Double.isFinite(v)) {
                rates = rates.with(phase, v);
            }
        }
        return rates;
    }

    /** Move the stored rate of each phase the stitch measured toward the measured value. */
    public static void learn(String compression, StitchEta eta) {
        for (StitchPhase phase : StitchPhase.values()) {
            double measured = eta.measuredRate(phase);
            if (!(measured > 0) || !Double.isFinite(measured)) {
                continue;
            }
            String key = key(compression, phase);
            double old = read(key);
            double updated = old > 0 && Double.isFinite(old) ? old + ALPHA * (measured - old) : measured;
            write(key, updated);
            logger.debug("Learned {} rate {}: {} -> {}", phase, compression, old, updated);
        }
    }

    static String key(String compression, StitchPhase phase) {
        String codec = phase == StitchPhase.WRITE
                ? (compression == null || compression.isBlank()
                        ? "default"
                        : compression.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                : "any";
        return "basicstitching.eta." + phase.name().toLowerCase(Locale.ROOT) + "." + codec;
    }

    private static double read(String key) {
        try {
            return pref(key).get();
        } catch (Throwable t) {
            // No QuPath preferences (a unit test, a headless tool): use the defaults.
            return Double.NaN;
        }
    }

    private static void write(String key, double value) {
        try {
            pref(key).set(value);
        } catch (Throwable t) {
            logger.debug("Could not store learned stitch rate {}: {}", key, t.toString());
        }
    }

    private static DoubleProperty pref(String key) {
        return PREFS.computeIfAbsent(key, k -> PathPrefs.createPersistentPreference(k, Double.NaN));
    }
}
