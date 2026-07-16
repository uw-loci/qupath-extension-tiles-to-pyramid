package qupath.ext.basicstitching.registration;

/**
 * Normalized cross-correlation primitives shared by tile registration and the pixel-size estimator.
 *
 * <p>{@link #atShift} and {@link #downsample2} were lifted verbatim from
 * {@code MicroManagerMetadataStrategy}, where they had already been validated against real
 * microscope tiles by the pixel-size estimator. That class now delegates here so the two callers
 * cannot drift apart.
 *
 * <p>Arrays are {@code [y][x]} single-band float grayscale.
 */
public final class Ncc {

    private Ncc() {}

    /**
     * Normalized cross-correlation between {@code a} and {@code b} where pixel {@code a[y][x]} is
     * matched against {@code b[y-oy][x-ox]} over their overlapping region.
     *
     * @param a first image
     * @param b second image
     * @param ox X shift of {@code b} relative to {@code a}
     * @param oy Y shift of {@code b} relative to {@code a}
     * @param w width to consider
     * @param h height to consider
     * @return a value in roughly [-1, 1]; {@link #NO_MATCH} if the overlap is empty or degenerate
     */
    public static double atShift(float[][] a, float[][] b, int ox, int oy, int w, int h) {
        int x0 = Math.max(0, ox);
        int y0 = Math.max(0, oy);
        int x1 = Math.min(w, w + ox);
        int y1 = Math.min(h, h + oy);
        int count = 0;
        double sa = 0;
        double sb = 0;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        for (int y = y0; y < y1; y++) {
            int by = y - oy;
            float[] arow = a[y];
            float[] brow = b[by];
            for (int x = x0; x < x1; x++) {
                float av = arow[x];
                float bv = brow[x - ox];
                sa += av;
                sb += bv;
                saa += av * av;
                sbb += bv * bv;
                sab += av * bv;
                count++;
            }
        }
        if (count < MIN_OVERLAP_PIXELS) {
            return NO_MATCH;
        }
        double na = saa - sa * sa / count;
        double nb = sbb - sb * sb / count;
        double denom = Math.sqrt(na * nb);
        if (denom <= 1e-6) {
            return NO_MATCH;
        }
        return (sab - sa * sb / count) / denom;
    }

    /**
     * Score returned when a shift cannot be evaluated: too little overlap, or a constant region on
     * either side. Deliberately below the [-1, 1] range so it always loses a max().
     */
    public static final double NO_MATCH = -2;

    /** Fewer overlapping pixels than this and the correlation is not worth believing. */
    public static final int MIN_OVERLAP_PIXELS = 16;

    /**
     * Box-filter downsample by 2 in each axis. Odd trailing rows/columns are dropped.
     *
     * @param src source image
     * @return a half-size copy
     */
    public static float[][] downsample2(float[][] src) {
        int h = src.length / 2;
        int w = src[0].length / 2;
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            int sy = y * 2;
            for (int x = 0; x < w; x++) {
                int sx = x * 2;
                out[y][x] = 0.25f * (src[sy][sx] + src[sy][sx + 1] + src[sy + 1][sx] + src[sy + 1][sx + 1]);
            }
        }
        return out;
    }

    /**
     * Repeatedly {@link #downsample2} until the requested factor is reached.
     *
     * @param src source image
     * @param factor a power of two; 1 returns {@code src} unchanged
     * @return the downsampled image
     */
    public static float[][] downsampleBy(float[][] src, int factor) {
        float[][] out = src;
        for (int f = factor; f > 1; f /= 2) {
            if (out.length < 2 || out[0].length < 2) {
                break;
            }
            out = downsample2(out);
        }
        return out;
    }

    /**
     * Robust spread of a band, as a median absolute deviation scaled to be comparable with a
     * standard deviation for normally-distributed data.
     *
     * <p>Used instead of the standard deviation to decide whether a band has anything to correlate.
     * The standard deviation is dominated by outliers: a single dust speck on an otherwise blank
     * field produces a large variance and sails through a variance gate, and then correlates against
     * the other band's speck with near-perfect confidence -- a completely wrong answer, held with
     * total certainty. The median absolute deviation ignores the speck and reports what is actually
     * there, which is nothing.
     *
     * @param values pixel values
     * @return the scaled median absolute deviation
     */
    public static double robustSpread(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        double median = median(sorted);
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - median);
        }
        java.util.Arrays.sort(deviations);
        return 1.4826 * median(deviations);
    }

    /**
     * @param values pixel values
     * @return the median
     */
    public static double medianOf(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return median(sorted);
    }

    private static double median(double[] sorted) {
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : 0.5 * (sorted[m - 1] + sorted[m]);
    }

    /**
     * Variance of an image region, for judging whether a pyramid level still carries signal.
     *
     * @param img the image
     * @param w width to consider
     * @param h height to consider
     * @return the variance
     */
    public static double variance(float[][] img, int w, int h) {
        int n = w * h;
        if (n == 0) {
            return 0;
        }
        double sum = 0;
        double sumSq = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = img[y][x];
                sum += v;
                sumSq += v * v;
            }
        }
        double mean = sum / n;
        return Math.max(0, sumSq / n - mean * mean);
    }
}
