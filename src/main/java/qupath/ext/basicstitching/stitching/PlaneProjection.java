package qupath.ext.basicstitching.stitching;

/**
 * How a stack of planes (the time points, or the z-slices, of one channel at one position) is
 * reduced to one plane, per pixel. The labels match QPSC's acquisition dialog.
 */
public enum PlaneProjection {
    /** Keep every plane. */
    NONE("None", "kept"),
    /** The per-pixel mean. */
    MEAN("Mean", "mean"),
    /** The per-pixel maximum. */
    MAX("Max Intensity", "maximum intensity"),
    /** The per-pixel minimum. */
    MIN("Min Intensity", "minimum intensity");

    private final String label;
    private final String words;

    PlaneProjection(String label, String words) {
        this.label = label;
        this.words = words;
    }

    /** @return the dropdown label, e.g. "Max Intensity" */
    public String label() {
        return label;
    }

    /** @return lower-case words for records and logs, e.g. "maximum intensity" */
    public String words() {
        return words;
    }

    /** @return whether the planes are reduced to one */
    public boolean reduces() {
        return this != NONE;
    }

    /** @return the projection with this name or label, ignoring case; NONE when nothing matches */
    public static PlaneProjection parse(String text) {
        if (text != null) {
            for (PlaneProjection p : values()) {
                if (p.name().equalsIgnoreCase(text.trim()) || p.label.equalsIgnoreCase(text.trim())) {
                    return p;
                }
            }
        }
        return NONE;
    }

    @Override
    public String toString() {
        return label;
    }
}
