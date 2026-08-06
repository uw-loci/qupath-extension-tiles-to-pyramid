package qupath.ext.basicstitching.assembly.direct;

/**
 * How the compositor resolves pixels covered by more than one tile.
 *
 * <h2>Why the default is a hard cut</h2>
 *
 * <p>Feathering averages two views of the same feature. Where the two tiles disagree -- and after
 * registration they still disagree by a pixel or two -- that average is a soft double image, so a
 * feathered mosaic trades a visible seam for a band of blur running along every join. Which of those
 * is preferable depends on what the image is for, so it is a choice rather than an improvement, and
 * the choice defaults to the sharp one.
 *
 * <p>What blending can fix that registration cannot is an intensity <i>step</i>: uneven
 * illumination, or exposure that drifted across a long acquisition, leaves neighbouring tiles at
 * different brightness, and no amount of correct positioning removes the line between them. That is
 * the case worth reaching for a feather.
 *
 * <p>Both feathers weight a pixel by how far it sits from its own tile's edge, so a tile contributes
 * almost nothing at its border and fully at its centre, and the weights are normalised per pixel.
 * The taper is separable: the X and Y weights are computed independently and multiplied, which is
 * what makes a tile corner fade in both directions at once.
 */
public enum OverlapBlend implements BlendStrategy {

    /**
     * Last tile written wins -- a hard cut at the tile boundary. Sharp everywhere, but shows a step
     * wherever two tiles differ in brightness. The historical behaviour and the default.
     */
    LAST_WINS("Last tile wins (sharp, default)") {
        @Override
        public float weight(int distFromEdge, int overlapWidth) {
            return 1.0f;
        }

        @Override
        public boolean requiresOverlapDetection() {
            return false;
        }
    },

    /**
     * Weight rising linearly from the tile edge to the far side of the overlap. The common default
     * in other stitchers (Fiji's Grid/Collection plugin, ASHLAR), and the cheapest thing that hides
     * an intensity step.
     */
    LINEAR_FEATHER("Linear feather") {
        @Override
        public float weight(int distFromEdge, int overlapWidth) {
            return floor((float) ramp(distFromEdge, overlapWidth));
        }

        @Override
        public boolean requiresOverlapDetection() {
            return true;
        }
    },

    /**
     * A raised-cosine roll-off over the same span. The linear ramp has a kink where it reaches full
     * weight, which can leave a faint line of its own on smooth backgrounds; this one meets both ends
     * with zero slope and does not. It blurs slightly more in exchange, because it holds intermediate
     * weights across more of the overlap.
     */
    COSINE_FEATHER("Cosine feather (smoothest)") {
        @Override
        public float weight(int distFromEdge, int overlapWidth) {
            double t = ramp(distFromEdge, overlapWidth);
            return floor((float) (0.5 * (1.0 - Math.cos(Math.PI * t))));
        }

        @Override
        public boolean requiresOverlapDetection() {
            return true;
        }
    };

    /**
     * Smallest weight a covered pixel may carry.
     *
     * <p>A tile's outermost row has essentially zero distance from its edge, so an unfloored weight
     * would be zero there. At the outer border of the whole mosaic that pixel has no second
     * contributor, and dividing by a zero weight sum would punch a background-coloured line around
     * the entire image. The floor keeps such a pixel normalising to exactly its own tile's value,
     * while remaining small enough not to disturb the taper anywhere two tiles genuinely meet.
     */
    private static final float MIN_WEIGHT = 1e-3f;

    private final String label;

    OverlapBlend(String label) {
        this.label = label;
    }

    /** @return the human-readable name shown in the preferences pane. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * Position within the overlap, from 0 at the tile edge to 1 at the far side of the band.
     *
     * <p>A non-positive overlap width means the caller could not measure one -- a single row or
     * column of tiles, or an edge-to-edge grid -- and there is then nothing to blend across, so the
     * pixel takes full weight rather than an arbitrary taper.
     */
    private static double ramp(int distFromEdge, int overlapWidth) {
        if (overlapWidth <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) distFromEdge / overlapWidth));
    }

    private static float floor(float w) {
        return Math.max(MIN_WEIGHT, w);
    }
}
