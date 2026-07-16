package qupath.ext.basicstitching.registration;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
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
 * grid is roughly 170 MB, which would blow the ~40 MB envelope this whole architecture exists to
 * protect. Re-reading a band for each of a tile's edges costs a few extra decodes and keeps peak
 * memory at a couple of bands per worker.
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
        BufferedImage img = pool.readRegion(file, x, y, width, height);
        if (img == null) {
            throw new IOException("Null region read from " + file);
        }
        return toBand(img);
    }

    /**
     * @param file tile file
     * @return the tile's dimensions and type, read from the header without decoding pixels
     * @throws IOException if the header cannot be read
     */
    public static TileReaderPool.TileDimensions dimensions(File file) throws IOException {
        return TileReaderPool.getDimensions(file);
    }

    private static OverlapBand toBand(BufferedImage img) {
        Raster raster = img.getRaster();
        int w = raster.getWidth();
        int h = raster.getHeight();
        int bands = raster.getNumBands();
        int n = w * h;
        if (n == 0) {
            return new OverlapBand(new float[0][0], 0, 0, 0, maxPossibleValue(img));
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

        float[][] gray = new float[h][w];
        double[] flat = new double[n];
        double sum = 0;
        double sumSq = 0;
        for (int yy = 0; yy < h; yy++) {
            float[] row = gray[yy];
            int base = yy * w;
            for (int xx = 0; xx < w; xx++) {
                double v = acc[base + xx] / bands;
                row[xx] = (float) v;
                flat[base + xx] = v;
                sum += v;
                sumSq += v * v;
            }
        }

        double mean = sum / n;
        double variance = Math.max(0, sumSq / n - mean * mean);
        return new OverlapBand(gray, Ncc.medianOf(flat), Ncc.robustSpread(flat), variance, maxPossibleValue(img));
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
