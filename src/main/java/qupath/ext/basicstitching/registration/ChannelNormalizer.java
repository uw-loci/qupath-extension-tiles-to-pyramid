package qupath.ext.basicstitching.registration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds one intensity scale per channel for a normalized projection, from a bounded sample of
 * tiles.
 *
 * <h2>Why one scale per channel, for the whole dataset</h2>
 *
 * A projection correlates the two tiles of a seam against each other, so a feature must have the
 * same value in both. Scaling each tile on its own contrast would change a feature's value from one
 * tile to the next -- exactly the difference correlation is trying to measure. A single factor per
 * channel keeps every tile on the same footing while still stopping the brightest channel from
 * drowning out the others.
 *
 * <p>Only the scale matters, not the black point. Correlation is invariant to adding a constant to a
 * band, so subtracting each channel's black level would change nothing about the match. Leaving it in
 * also keeps the low-texture gate (spread over median) reading the same kind of numbers it reads for
 * a single raw channel; subtracting it would drive the median of a dark fluorescence background to
 * zero and reject every seam.
 *
 * <h2>Cost</h2>
 *
 * Bounded regardless of dataset size: at most {@link #SAMPLE_TILES} centre crops of at most
 * {@link #CROP_PX} square per channel, subsampled every {@link #PIXEL_STRIDE} pixels. That is well
 * under a million values per channel, sorted once, whether the acquisition is 40 tiles or 40,000.
 */
public final class ChannelNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(ChannelNormalizer.class);

    /** Tiles sampled per channel, spread evenly across the grid. */
    static final int SAMPLE_TILES = 64;

    /** Side of the centre crop read from each sampled tile. */
    static final int CROP_PX = 512;

    /** Keep every Nth pixel in each direction of a crop. */
    static final int PIXEL_STRIDE = 4;

    /** Black point percentile. */
    static final double LOW_PERCENTILE = 1.0;

    /**
     * White point percentile. High, because fluorescence is often sparse: on a slide that is mostly
     * background, the stained structures may be well under 1% of the sampled pixels, and a lower
     * percentile would land in the background and inflate that channel's weight to match its noise.
     */
    static final double HIGH_PERCENTILE = 99.9;

    private ChannelNormalizer() {}

    /**
     * The black and white points found for one channel, and the scale derived from them.
     *
     * @param name channel name
     * @param black low-percentile intensity
     * @param white high-percentile intensity
     * @param scale {@code 1 / (white - black)}, or {@code 1 / max(1, white)} when the channel is flat
     * @param tilesSampled tiles actually read
     */
    public record Scale(String name, double black, double white, double scale, int tilesSampled) {
        /** @return a one-line description for logs and the solution file. */
        public String describe() {
            return String.format(
                    Locale.ROOT,
                    "%s black=%.1f white=%.1f scale=%.6g (%d tiles)",
                    name,
                    black,
                    white,
                    scale,
                    tilesSampled);
        }
    }

    /**
     * Measure every channel's scale and return the channels carrying it.
     *
     * @param channels the channels to normalize, each at any scale
     * @param tiles the grid; its filenames are looked up in each channel
     * @param scalesOut if non-null, receives each channel's {@link Scale}, in order
     * @return the channels with their normalization scale set
     */
    public static List<RegistrationChannel> normalize(
            List<RegistrationChannel> channels, List<TileNode> tiles, List<Scale> scalesOut) {
        List<RegistrationChannel> out = new ArrayList<>(channels.size());
        try (OverlapBandReader reader = new OverlapBandReader(8)) {
            for (RegistrationChannel channel : channels) {
                Scale s = measure(channel, tiles, reader);
                logger.info("Projection normalization: {}", s.describe());
                if (scalesOut != null) {
                    scalesOut.add(s);
                }
                out.add(channel.withScale(s.scale()));
            }
        }
        return out;
    }

    private static Scale measure(RegistrationChannel channel, List<TileNode> tiles, OverlapBandReader reader) {
        int stride = Math.max(1, tiles.size() / SAMPLE_TILES);
        double[] values = new double[0];
        int count = 0;
        int sampled = 0;
        for (int i = 0; i < tiles.size() && sampled < SAMPLE_TILES; i += stride) {
            TileNode t = tiles.get(i);
            File file = channel.fileFor(t.filename());
            if (file == null) {
                continue;
            }
            int w = Math.min(CROP_PX, t.widthPx());
            int h = Math.min(CROP_PX, t.heightPx());
            float[][] gray;
            try {
                gray = reader.readGray(file, (t.widthPx() - w) / 2, (t.heightPx() - h) / 2, w, h);
            } catch (java.io.IOException | RuntimeException e) {
                logger.debug("Normalization could not sample {}: {}", file, e.toString());
                continue;
            }
            sampled++;
            int needed = count + (h / PIXEL_STRIDE + 1) * (w / PIXEL_STRIDE + 1);
            if (needed > values.length) {
                values = Arrays.copyOf(values, Math.max(needed, values.length * 2));
            }
            for (int y = 0; y < gray.length; y += PIXEL_STRIDE) {
                float[] row = gray[y];
                for (int x = 0; x < row.length; x += PIXEL_STRIDE) {
                    values[count++] = row[x];
                }
            }
        }
        if (count == 0) {
            logger.warn("Projection normalization: no readable tiles for '{}'; leaving it unscaled", channel.name());
            return new Scale(channel.name(), 0, 1, 1, 0);
        }
        Arrays.sort(values, 0, count);
        double black = percentile(values, count, LOW_PERCENTILE);
        double white = percentile(values, count, HIGH_PERCENTILE);
        double range = white - black;
        // A flat channel (no range at all) still has to enter the mean; weight it by its level so it
        // contributes a near-constant, which correlation ignores, rather than amplified noise.
        double scale = range > 1e-6 ? 1.0 / range : 1.0 / Math.max(1.0, white);
        return new Scale(channel.name(), black, white, scale, sampled);
    }

    private static double percentile(double[] sorted, int count, double pct) {
        int idx = (int) Math.round(pct / 100.0 * (count - 1));
        return sorted[Math.max(0, Math.min(count - 1, idx))];
    }
}
