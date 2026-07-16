package qupath.ext.basicstitching.registration;

/**
 * A tile's overlap region, as single-band float grayscale, with the statistics needed to decide
 * whether it carries enough signal to register against.
 *
 * @param gray pixels as {@code [y][x]}
 * @param median median intensity
 * @param robustSpread median absolute deviation, scaled to compare with a standard deviation
 * @param variance intensity variance, for diagnostics and pyramid-level checks
 * @param maxPossibleValue the largest value the source bit depth can hold, for the saturation check
 */
public record OverlapBand(
        float[][] gray, double median, double robustSpread, double variance, double maxPossibleValue) {

    /**
     * How much structure this band has, scale-free: {@code robustSpread / median}.
     *
     * <p>Two deliberate choices here, each fixing a way our data produces confident nonsense.
     *
     * <p><b>Relative, not absolute.</b> Absolute variance is not comparable across bit depths or
     * exposures -- an 8-bit and a 16-bit image of the same scene differ by a factor of 65k -- so no
     * single absolute threshold can work. A ratio is scale-free, and it is also the right shape for
     * the real failure case: a bright, low-contrast background has a large mean and a small spread.
     *
     * <p><b>Robust, not standard deviation.</b> A standard deviation is dominated by outliers. One
     * dust speck on an otherwise blank field yields a large variance, passes a variance gate, and
     * then correlates against the other band's speck at near-perfect confidence -- a wrong answer
     * held with total certainty, which is the worst outcome this subsystem can produce. The median
     * absolute deviation ignores the speck and reports the truth: there is nothing here.
     *
     * @return the robust coefficient of variation, or 0 when the median is non-positive
     */
    public double textureScore() {
        if (median <= 1e-9) {
            return 0;
        }
        return robustSpread / median;
    }

    /**
     * @param fraction fraction of full scale above which the band counts as blown out
     * @return whether the median sits within {@code fraction} of full scale, so detail is clipped
     */
    public boolean nearSaturated(double fraction) {
        return maxPossibleValue > 0 && median >= maxPossibleValue * fraction;
    }

    /** @return band height in pixels. */
    public int height() {
        return gray.length;
    }

    /** @return band width in pixels. */
    public int width() {
        return gray.length == 0 ? 0 : gray[0].length;
    }
}
