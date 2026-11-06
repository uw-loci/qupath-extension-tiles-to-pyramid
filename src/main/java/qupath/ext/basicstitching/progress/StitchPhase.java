package qupath.ext.basicstitching.progress;

/** The phases of one stitch, in the order they run. */
public enum StitchPhase {
    /** Reading every tile's header for its size and position. */
    SCAN("reading tile headers"),
    /** Measuring the overlaps between neighboring tiles (registration solve only). */
    SOLVE("measuring tile overlaps"),
    /** Compositing the mosaic and writing every pyramid level. */
    WRITE("writing the image");

    private final String label;

    StitchPhase(String label) {
        this.label = label;
    }

    /** Lower-case description for status lines, e.g. "writing the image". */
    public String label() {
        return label;
    }
}
