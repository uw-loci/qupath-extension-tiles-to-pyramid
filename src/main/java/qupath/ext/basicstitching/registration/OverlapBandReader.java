package qupath.ext.basicstitching.registration;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.List;
import qupath.ext.basicstitching.assembly.direct.TileReaderPool;

/**
 * Reads overlap sub-regions out of tile files as float grayscale.
 *
 * <h2>Threading</h2>
 *
 * Each instance owns its own {@link TileReaderPool} and is <b>confined to one thread</b>. That is
 * deliberate. Every {@code TileReaderPool} method is {@code synchronized} on the pool instance and
 * holds that lock across the TIFF decode -- the expensive part -- so workers sharing one pool would
 * serialize on 100% of the work and gain nothing from being parallel. Giving each worker its own
 * pool sidesteps the lock entirely, with no change to the pool class and therefore no risk to the
 * stitching path that already depends on it.
 *
 * <h2>Memory</h2>
 *
 * The reader budget is divided across workers rather than multiplied by them, so the total open-file
 * count matches what a single stitch already uses.
 *
 * <p>Bands are deliberately <b>not</b> cached across edges. Holding four bands per tile for a 100-tile
 * grid is roughly 170 MB, and it grows with the tile count -- which is exactly the dependence this
 * architecture exists to remove (a 100-tile stitch otherwise completes in a 128 MB heap). Re-reading
 * a band for each of a tile's edges costs a few extra decodes and keeps peak memory at a couple of
 * bands per worker.
 */
public final class OverlapBandReader implements AutoCloseable {

    private final TileReaderPool pool;

    /**
     * @param maxOpenReaders open-file budget for this worker's pool
     */
    public OverlapBandReader(int maxOpenReaders) {
        this.pool = new TileReaderPool(Math.max(1, maxOpenReaders));
    }

    /**
     * Read a sub-region of a tile as float grayscale, with its statistics.
     *
     * @param file tile file
     * @param x left edge of the region within the tile
     * @param y top edge of the region within the tile
     * @param width region width
     * @param height region height
     * @return the band
     * @throws IOException if the region cannot be read
     */
    public OverlapBand read(File file, int x, int y, int width, int height) throws IOException {
        BufferedImage img = readImage(file, x, y, width, height);
        return OverlapBand.of(toGray(img), maxPossibleValue(img));
    }

    /**
     * Read the same sub-region of one tile from each channel and combine them into one band: the
     * mean of each channel's pixels times that channel's {@link RegistrationChannel#scale()}.
     *
     * <p>With a single channel at scale 1 this is exactly {@link #read}, so the gates see the same
     * numbers they always have. The full-scale value used by the saturation check is combined the
     * same way, so a projection only counts as saturated when its channels are, on average.
     *
     * @param channels the channels to combine; at least one
     * @param filename the tile, as keyed in every channel
     * @param x left edge of the region within the tile
     * @param y top edge of the region within the tile
     * @param width region width
     * @param height region height
     * @return the combined band
     * @throws IOException if any channel's region cannot be read, or a channel lacks this tile
     */
    public OverlapBand read(List<RegistrationChannel> channels, String filename, int x, int y, int width, int height)
            throws IOException {
        if (channels.size() == 1 && channels.get(0).scale() == 1.0) {
            return read(requireFile(channels.get(0), filename), x, y, width, height);
        }
        float[][] acc = null;
        double maxPossible = 0;
        for (RegistrationChannel channel : channels) {
            BufferedImage img = readImage(requireFile(channel, filename), x, y, width, height);
            float[][] gray = toGray(img);
            float scale = (float) channel.scale();
            if (acc == null) {
                acc = new float[gray.length][gray.length == 0 ? 0 : gray[0].length];
            }
            for (int yy = 0; yy < acc.length; yy++) {
                float[] a = acc[yy];
                float[] g = gray[yy];
                for (int xx = 0; xx < a.length; xx++) {
                    a[xx] += g[xx] * scale;
                }
            }
            maxPossible += maxPossibleValue(img) * channel.scale();
        }
        int n = channels.size();
        for (float[] row : acc) {
            for (int xx = 0; xx < row.length; xx++) {
                row[xx] /= n;
            }
        }
        return OverlapBand.of(acc, maxPossible / n);
    }

    /**
     * Read a sub-region as float grayscale without computing statistics, for callers that only
     * need the pixel values (the normalization sampler).
     *
     * @param file tile file
     * @param x left edge of the region within the tile
     * @param y top edge of the region within the tile
     * @param width region width
     * @param height region height
     * @return pixels as {@code [y][x]}
     * @throws IOException if the region cannot be read
     */
    public float[][] readGray(File file, int x, int y, int width, int height) throws IOException {
        return toGray(readImage(file, x, y, width, height));
    }

    /**
     * @param file tile file
     * @return the tile's dimensions and type, read from the header without decoding pixels
     * @throws IOException if the header cannot be read
     */
    public static TileReaderPool.TileDimensions dimensions(File file) throws IOException {
        return TileReaderPool.getDimensions(file);
    }

    private BufferedImage readImage(File file, int x, int y, int width, int height) throws IOException {
        BufferedImage img = pool.readRegion(file, x, y, width, height);
        if (img == null) {
            throw new IOException("Null region read from " + file);
        }
        return img;
    }

    private static File requireFile(RegistrationChannel channel, String filename) throws IOException {
        File file = channel.fileFor(filename);
        if (file == null) {
            throw new IOException("Channel '" + channel.name() + "' has no tile " + filename);
        }
        return file;
    }

    /** Collapse every band of the image to one by an equal-weight mean. */
    private static float[][] toGray(BufferedImage img) {
        Raster raster = img.getRaster();
        int w = raster.getWidth();
        int h = raster.getHeight();
        int bands = raster.getNumBands();
        int n = w * h;
        float[][] gray = new float[h][w];
        if (n == 0) {
            return gray;
        }

        // Bulk per-band reads rather than per-pixel getPixel: a band is ~100k pixels and the
        // per-call overhead dominates otherwise.
        double[] acc = new double[n];
        int[] samples = new int[n];
        for (int b = 0; b < bands; b++) {
            raster.getSamples(0, 0, w, h, b, samples);
            for (int i = 0; i < n; i++) {
                acc[i] += samples[i];
            }
        }
        for (int yy = 0; yy < h; yy++) {
            float[] row = gray[yy];
            int base = yy * w;
            for (int xx = 0; xx < w; xx++) {
                row[xx] = (float) (acc[base + xx] / bands);
            }
        }
        return gray;
    }

    /** Full scale for the image's bit depth, used only for the saturation check. */
    private static double maxPossibleValue(BufferedImage img) {
        int bits = img.getSampleModel().getSampleSize(0);
        if (bits <= 0 || bits > 32) {
            return 0;
        }
        return Math.pow(2, bits) - 1;
    }

    @Override
    public void close() {
        pool.close();
    }
}
