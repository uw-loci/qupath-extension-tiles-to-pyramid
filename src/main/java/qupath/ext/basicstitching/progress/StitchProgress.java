package qupath.ext.basicstitching.progress;

/**
 * Where one stitch is and how long it has left, as handed to a
 * {@link qupath.ext.basicstitching.config.StitchingConfig#setProgressListener progress listener}.
 *
 * @param target the subdirectory (or output) being stitched
 * @param phase the phase running now
 * @param phaseFraction how much of this phase is done, 0 to 1
 * @param elapsedSeconds seconds since the stitch started
 * @param remainingSeconds estimated seconds left for the whole stitch, NaN while unknown
 * @param measured true when the estimate comes from the speed measured in this stitch, false when
 *     it is still the prior (learned or default rates)
 * @param detail short human-readable detail, e.g. "level 2 of 8"
 */
public record StitchProgress(
        String target,
        StitchPhase phase,
        double phaseFraction,
        double elapsedSeconds,
        double remainingSeconds,
        boolean measured,
        String detail) {

    /** "about 1 h 12 min left", "less than a minute left" or "time left unknown". */
    public String remainingText() {
        return formatRemaining(remainingSeconds);
    }

    /** As {@link #remainingText()} for any number of seconds. */
    public static String formatRemaining(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) {
            return "time left unknown";
        }
        if (seconds < 60) {
            return "less than a minute left";
        }
        return "about " + formatDuration(seconds) + " left";
    }

    /** "3 h 05 min", "12 min", "45 s". Rounded to the minute from one minute up. */
    public static String formatDuration(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) {
            return "?";
        }
        if (seconds < 60) {
            return Math.round(seconds) + " s";
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " min";
        }
        return String.format("%d h %02d min", minutes / 60, minutes % 60);
    }
}
