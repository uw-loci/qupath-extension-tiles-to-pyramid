package qupath.ext.basicstitching.utilities;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.collections.ObservableList;
import org.controlsfx.control.PropertySheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent, user-visible tuning for content-based tile registration.
 *
 * <h2>Why these live in QuPath's Preferences pane</h2>
 *
 * The stitch dialog exposes only the two genuinely per-run choices -- how much the tiles overlap and
 * which subdirectory to solve on. Everything else here is <i>policy</i>: how strict a match must be,
 * how far a tile may be corrected, how the solver trades measurements against nominal. Policy is
 * stable across runs, so it belongs in one persistent place rather than being re-entered every time.
 *
 * <p>Putting it in QuPath's global Preferences (category {@value #CATEGORY}) also makes it the single
 * source of truth shared with QPSC: QPSC reads these same settings through {@link #toSettings()}
 * rather than defining its own copy of every knob, so a value changed here takes effect in both the
 * standalone stitch and a QPSC-driven acquisition.
 *
 * <p>Every value is defaulted to the tested configuration and clamped to a sane range on read, so an
 * out-of-range edit degrades to the nearest valid value rather than failing a stitch.
 */
public final class RegistrationPreferences {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationPreferences.class);

    /** Preferences-pane category (subsection) these appear under. */
    public static final String CATEGORY = "Tiles-to-pyramid";

    private RegistrationPreferences() {}

    private static final DoubleProperty minNcc =
            PathPrefs.createPersistentPreference("basicstitching.registration.minNcc", 0.30);
    private static final DoubleProperty maxShiftPercent =
            PathPrefs.createPersistentPreference("basicstitching.registration.maxShiftPercent", 2.0);
    private static final IntegerProperty minShiftPx =
            PathPrefs.createPersistentPreference("basicstitching.registration.minShiftPx", 24);
    private static final DoubleProperty lambda =
            PathPrefs.createPersistentPreference("basicstitching.registration.lambda", 0.01);
    private static final IntegerProperty outlierPasses =
            PathPrefs.createPersistentPreference("basicstitching.registration.outlierPasses", 2);
    private static final DoubleProperty minCoeffOfVar =
            PathPrefs.createPersistentPreference("basicstitching.registration.minCoeffOfVar", 0.02);
    private static final DoubleProperty ambiguityRatio =
            PathPrefs.createPersistentPreference("basicstitching.registration.ambiguityRatio", 0.92);
    private static final IntegerProperty coarsestDownsample =
            PathPrefs.createPersistentPreference("basicstitching.registration.coarsestDownsample", 8);
    private static final IntegerProperty topKPeaks =
            PathPrefs.createPersistentPreference("basicstitching.registration.topKPeaks", 3);
    private static final BooleanProperty fillUnregistered =
            PathPrefs.createPersistentPreference("basicstitching.registration.fillUnregistered", true);
    private static final IntegerProperty threads =
            PathPrefs.createPersistentPreference("basicstitching.registration.threads", 0);

    /**
     * Register every knob in QuPath's Preferences pane under the {@value #CATEGORY} category. Safe to
     * call once at extension install; a null GUI (headless) is a no-op.
     *
     * @param qupath the running QuPath instance, or null when headless
     */
    public static void installPreferences(QuPathGUI qupath) {
        if (qupath == null) {
            return;
        }
        ObservableList<PropertySheet.Item> items =
                qupath.getPreferencePane().getPropertySheet().getItems();

        items.add(new PropertyItemBuilder<>(minNcc, Double.class)
                .name("Registration: minimum match confidence")
                .category(CATEGORY)
                .description("Peak normalized cross-correlation (0-1) below which a tile-pair match is not"
                        + " trusted and the edge is dropped. Higher = fewer but safer corrections. Default 0.30.")
                .build());
        items.add(new PropertyItemBuilder<>(maxShiftPercent, Double.class)
                .name("Registration: max shift per step, as % of tile")
                .category(CATEGORY)
                .description("Largest correction, as a percent of tile size, looked for between two neighbouring"
                        + " tiles (one stage step). Bounds the per-EDGE search so a low-texture band cannot lock"
                        + " onto a distant wrong peak; the cumulative per-tile correction can still be larger."
                        + " Default 2.0.")
                .build());
        items.add(new PropertyItemBuilder<>(minShiftPx, Integer.class)
                .name("Registration: max shift per step, floor (px)")
                .category(CATEGORY)
                .description("Floor, in pixels, for the setting above -- the two together define ONE search window:\n"
                        + "  half-width = max(this floor, percent x tile size), capped at the physical overlap.\n"
                        + "It exists so the percentage cannot collapse to something unusably small on a small\n"
                        + "tile (2% of a 256 px tile is 5 px). It never binds on acquisition-sized tiles, where\n"
                        + "the percentage is always larger. Default 24.")
                .build());
        items.add(new PropertyItemBuilder<>(fillUnregistered, Boolean.class)
                .name("Registration: fill unregisterable tiles from neighbours")
                .category(CATEGORY)
                .description("When a tile is too low-texture to register, take the correction its registered"
                        + " neighbours imply instead of leaving it at its raw stage position (which can strand it"
                        + " tens of pixels away inside a corrected grid). Default on.")
                .build());
        items.add(new PropertyItemBuilder<>(lambda, Double.class)
                .name("Registration: nominal pull (lambda)")
                .category(CATEGORY)
                .description("Strength of the gauge pin that holds each connected piece of the mosaic near its"
                        + " nominal stage position, relative to the median edge weight. It constrains only where a"
                        + " piece SITS as a whole, not the geometry inside it, so it no longer shrinks real"
                        + " corrections. Must be > 0. Default 0.01.")
                .build());
        items.add(new PropertyItemBuilder<>(outlierPasses, Integer.class)
                .name("Registration: outlier rejection passes")
                .category(CATEGORY)
                .description("Iterative passes that DOWN-WEIGHT edges disagreeing with the global solution. Edges are"
                        + " never cut -- cutting one un-ties its seam, which then drifts open by the whole"
                        + " accumulated error. 0 disables reweighting. Default 2.")
                .build());
        items.add(new PropertyItemBuilder<>(minCoeffOfVar, Double.class)
                .name("Registration: low-texture gate")
                .category(CATEGORY)
                .description("Robust coefficient of variation (spread/median) below which an overlap band is judged"
                        + " featureless and its edge is dropped. Raise to reject more marginal bands. Default 0.02.")
                .build());
        items.add(new PropertyItemBuilder<>(ambiguityRatio, Double.class)
                .name("Registration: ambiguity ratio")
                .category(CATEGORY)
                .description("Reject a match when a second, well-separated correlation peak scores at least this"
                        + " fraction of the best -- the signature of repeating texture. Default 0.92.")
                .build());
        items.add(new PropertyItemBuilder<>(coarsestDownsample, Integer.class)
                .name("Registration: coarsest search downsample")
                .category(CATEGORY)
                .description("Starting downsample for the coarse-to-fine correlation search (a power of two). Larger"
                        + " searches faster but can smooth away fine structure. Default 8.")
                .build());
        items.add(new PropertyItemBuilder<>(topKPeaks, Integer.class)
                .name("Registration: candidate peaks kept")
                .category(CATEGORY)
                .description("How many correlation peaks are carried from each pyramid level to the next before"
                        + " refining. Default 3.")
                .build());
        items.add(new PropertyItemBuilder<>(threads, Integer.class)
                .name("Registration: worker threads (0 = auto)")
                .category(CATEGORY)
                .description("Threads used for pairwise registration; 0 picks half the available cores. Each worker"
                        + " uses a small share of the open-file budget, so memory is unaffected. Default 0 (auto).")
                .build());

        logger.info("Registered {} tile-registration preferences under '{}'", 11, CATEGORY);
    }

    /**
     * Build a {@link RegistrationSettings} from the current preference values, clamped to valid
     * ranges. Overlap percent and worker-thread count are the two exceptions: overlap is a per-run
     * choice left to the caller (derived from the grid unless the dialog pins it), and threads of 0
     * resolves to the default here.
     *
     * @return settings reflecting the user's current preferences
     */
    public static RegistrationSettings toSettings() {
        int workers = threads.get() > 0 ? threads.get() : RegistrationSettings.defaultThreads();
        double frac = Math.max(1e-4, maxShiftPercent.get() / 100.0);
        return new RegistrationSettings(
                clamp(minNcc.get(), 0.0, 0.999),
                Math.max(0.0, minCoeffOfVar.get()),
                clamp(ambiguityRatio.get(), 0.01, 1.0),
                0.90, // bandMarginFrac: internal gate detail, not user-facing
                Math.max(1e-6, lambda.get()),
                Math.max(0, outlierPasses.get()),
                powerOfTwoAtLeastOne(coarsestDownsample.get()),
                Math.max(1, topKPeaks.get()),
                Math.max(1, workers),
                Double.NaN, // overlapX: per-run, applied by the caller
                Double.NaN, // overlapY: per-run, applied by the caller
                frac,
                Math.max(1, minShiftPx.get()),
                fillUnregistered.get());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Floor to the nearest power of two that is >= 1, so an odd preference value stays valid. */
    private static int powerOfTwoAtLeastOne(int v) {
        if (v < 1) {
            return 1;
        }
        if (Integer.bitCount(v) == 1) {
            return v;
        }
        int floor = Integer.highestOneBit(v);
        if (floor != v) {
            logger.warn("coarsestDownsample {} is not a power of two; using {}", v, floor);
        }
        return floor;
    }
}
