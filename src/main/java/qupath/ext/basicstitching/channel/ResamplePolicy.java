package qupath.ext.basicstitching.channel;

/**
 * How a channel's values may be combined when they are averaged, interpolated or blended.
 *
 * <p>Downsampling a pyramid level and feathering a stitch seam both <em>combine</em> pixel
 * values. That is correct for continuous data and meaningless for some other kinds -- and
 * meaningless in the silent way, because the result is still a well-formed image. The mean of
 * label 3 and label 7 is label 5, a real class the pixel never belonged to. The mean of 179
 * and 1 degrees on an axial angle is 90: perpendicular to the truth, and entirely plausible
 * looking. Nothing downstream can detect either.
 *
 * <p>Tiles therefore declare a policy in their OME metadata under {@code qpsc.resample}, written
 * by {@code microscope_imageprocessing.io.channel_semantics}. This enum is the Java half of that
 * contract and must stay in step with it.
 *
 * <p><b>The contract is fail-safe.</b> Only an explicit (or absent) {@link #LINEAR} authorises
 * combining. Every other declared value -- including one a newer writer emits that this build has
 * never heard of -- maps to {@link #UNKNOWN} and forbids it. An unrecognised policy degrades to
 * preserving the data rather than to silently destroying it, which is the whole reason this is a
 * declared vocabulary rather than a boolean.
 */
public enum ResamplePolicy {

    /** Continuous data. Averaging, interpolation and blending are all valid. */
    LINEAR("linear"),

    /** Values must be selected, never combined. Labels, masks, object ids. */
    NEAREST("nearest"),

    /** Axial angle: t and t + 180 degrees are the same value. Combine via sin(2t)/cos(2t). */
    ANGULAR_180("angular180"),

    /** Directional angle: a full cycle is 360 degrees. Combine via sin(t)/cos(t). */
    ANGULAR_360("angular360"),

    /** A policy this build does not recognise. Treated as non-combinable, by design. */
    UNKNOWN(null);

    private final String declared;

    ResamplePolicy(String declared) {
        this.declared = declared;
    }

    /**
     * Parse a declared policy string.
     *
     * @param value the raw {@code qpsc.resample} value; {@code null} or blank means the tile
     *     declared nothing, which is {@link #LINEAR} -- that describes every channel written
     *     before this convention existed
     * @return the matching constant, or {@link #UNKNOWN} for anything unrecognised
     */
    public static ResamplePolicy fromDeclared(String value) {
        if (value == null || value.isBlank()) {
            return LINEAR;
        }
        String normalised = value.trim().toLowerCase();
        for (ResamplePolicy policy : values()) {
            if (normalised.equals(policy.declared)) {
                return policy;
            }
        }
        return UNKNOWN;
    }

    /** Whether averaging, interpolating or blending this channel is permitted. */
    public boolean mayCombine() {
        return this == LINEAR;
    }

    /** Whether values are angles, and so combinable circularly given a period. */
    public boolean isAngular() {
        return this == ANGULAR_180 || this == ANGULAR_360;
    }

    /*
     * Deliberately no harmonics() accessor. It reads as though circular averaging needs
     * to know whether a channel is axial or directional, and it does not: the declared
     * period is one full cycle of the STORED values, so counts always map onto a single
     * turn. Folding an axial angle to double angle is already implied by its period
     * spanning 180 degrees rather than 360. An accessor here invites multiplying it in
     * again, which spans two turns and decodes a wrapped mean to half the period.
     */
}
