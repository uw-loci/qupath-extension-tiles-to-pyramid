package qupath.ext.basicstitching;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import qupath.ext.basicstitching.registration.TileNode;

/**
 * Builds a synthetic tile grid on disk with known ground truth, for testing registration.
 *
 * <p>Tiles are carved out of one large source image at their <b>true</b> (jittered) positions, but
 * the returned nominal positions and the written TileConfiguration.txt describe the <b>ideal</b>
 * grid. That is exactly the situation registration exists to fix: the stage reported one thing, the
 * pixels say another, and the injected jitter is the answer the solver has to recover.
 */
final class SyntheticGridFixture {

    private SyntheticGridFixture() {}

    /**
     * A generated grid.
     *
     * @param nominal tiles at their ideal grid positions, as the engine receives them
     * @param trueJitterPx filename to the {@code {dx, dy}} actually applied; the answer key
     * @param tileWidthPx tile width
     * @param tileHeightPx tile height
     */
    record Grid(List<TileNode> nominal, Map<String, double[]> trueJitterPx, int tileWidthPx, int tileHeightPx) {}

    /** How a tile's pixels should be generated. */
    enum Content {
        /** Multi-octave noise: structure at every pyramid level, like real tissue. */
        TEXTURE,
        /** Flat mid-grey: no structure at all, like an empty field. */
        BLANK
    }

    /**
     * Write a grid of TIFF tiles plus a nominal TileConfiguration.txt.
     *
     * @param dir destination directory
     * @param cols grid columns
     * @param rows grid rows
     * @param tileW tile width in pixels
     * @param tileH tile height in pixels
     * @param overlapFrac fraction of each tile shared with its neighbour; 0 for edge-to-edge
     * @param jitterSigmaPx standard deviation of the injected per-tile displacement
     * @param driftPerTilePx a smooth, per-column/row displacement growing from the grid centre --
     *     a scale-like field on top of the random jitter, mimicking the real acquisition's
     *     systematic pixel-size/stage-step error. Zero-mean by construction, so the solve's pull
     *     toward nominal recovers it rather than absorbing it into a global offset.
     * @param blank tiles (by zero-based index) to render featureless instead of textured
     * @param seed random seed; fixed by callers so failures reproduce
     * @return the grid's nominal positions and ground truth
     * @throws IOException if the tiles cannot be written
     */
    static Grid write(
            Path dir,
            int cols,
            int rows,
            int tileW,
            int tileH,
            double overlapFrac,
            double jitterSigmaPx,
            double driftPerTilePx,
            List<Integer> blank,
            long seed)
            throws IOException {

        Random rng = new Random(seed);
        int stepX = (int) Math.round(tileW * (1 - overlapFrac));
        int stepY = (int) Math.round(tileH * (1 - overlapFrac));
        double maxDrift = Math.abs(driftPerTilePx) * Math.max((cols - 1) / 2.0, (rows - 1) / 2.0);
        int margin = (int) Math.ceil(4 * Math.max(1, jitterSigmaPx) + maxDrift) + 4;

        int srcW = margin * 2 + (cols - 1) * stepX + tileW;
        int srcH = margin * 2 + (rows - 1) * stepY + tileH;
        float[][] source = texture(srcW, srcH, rng);

        // Zero-mean jitter. The solve pins global translation to nominal (that is what the pull
        // toward nominal is for), so a jitter field with a non-zero mean would be recovered only up
        // to that offset -- which would be the test misreading the contract, not a solver bug.
        int n = cols * rows;
        double[] jx = new double[n];
        double[] jy = new double[n];
        for (int i = 0; i < n; i++) {
            jx[i] = rng.nextGaussian() * jitterSigmaPx;
            jy[i] = rng.nextGaussian() * jitterSigmaPx;
        }
        centre(jx);
        centre(jy);

        List<TileNode> nominal = new ArrayList<>();
        Map<String, double[]> truth = new LinkedHashMap<>();
        List<String> configLines = new ArrayList<>();
        configLines.add("dim = 2");

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                String name = (idx + 1) + ".tif";
                int nomX = c * stepX;
                int nomY = r * stepY;
                double driftX = driftPerTilePx * (c - (cols - 1) / 2.0);
                double driftY = driftPerTilePx * (r - (rows - 1) / 2.0);
                int trueX = (int) Math.round(margin + nomX + jx[idx] + driftX);
                int trueY = (int) Math.round(margin + nomY + jy[idx] + driftY);

                Content content = blank.contains(idx) ? Content.BLANK : Content.TEXTURE;
                File file = dir.resolve(name).toFile();
                writeTile(file, source, trueX, trueY, tileW, tileH, content);

                nominal.add(new TileNode(name, file, nomX, nomY, tileW, tileH));
                // Record the jitter actually realised after rounding to a whole source pixel, so the
                // answer key matches the pixels rather than the intent.
                truth.put(name, new double[] {trueX - margin - nomX, trueY - margin - nomY});
                configLines.add(String.format(Locale.ROOT, "%s; ; (%.3f, %.3f)", name, (double) nomX, (double) nomY));
            }
        }

        Files.write(dir.resolve("TileConfiguration.txt"), configLines, StandardCharsets.US_ASCII);
        return new Grid(nominal, truth, tileW, tileH);
    }

    /**
     * Write a grid whose nominal lattice is ROTATED by {@code rotationDeg}, with the tiles carved at
     * exactly those nominal positions -- so the correct answer is a ZERO correction.
     *
     * <p>Models the common real case of a stage and camera that are not perfectly square (or a
     * multi-tile alignment refinement that solved a small rotation): consecutive columns drift in Y
     * and consecutive rows drift in X. The overlap band for an edge must follow that perpendicular
     * drift; a reader that assumes it is zero compares two different world regions and reports the
     * drift as a measurement, which then gets applied as a spurious per-edge shift.
     *
     * @param dir destination directory
     * @param cols grid columns
     * @param rows grid rows
     * @param tileW tile width in pixels
     * @param tileH tile height in pixels
     * @param overlapFrac fraction of each tile shared with its neighbour
     * @param rotationDeg lattice rotation in degrees; a fraction of a degree is realistic
     * @param seed random seed; fixed by callers so failures reproduce
     * @return the grid's nominal positions and ground truth (rounding residual only)
     * @throws IOException if the tiles cannot be written
     */
    static Grid writeRotated(
            Path dir, int cols, int rows, int tileW, int tileH, double overlapFrac, double rotationDeg, long seed)
            throws IOException {

        Random rng = new Random(seed);
        int stepX = (int) Math.round(tileW * (1 - overlapFrac));
        int stepY = (int) Math.round(tileH * (1 - overlapFrac));
        double sin = Math.sin(Math.toRadians(rotationDeg));
        double cos = Math.cos(Math.toRadians(rotationDeg));

        double span = Math.max((cols - 1) * stepX, (rows - 1) * stepY);
        int margin = (int) Math.ceil(Math.abs(sin) * span) + 8;
        int srcW = margin * 2 + (cols - 1) * stepX + tileW;
        int srcH = margin * 2 + (rows - 1) * stepY + tileH;
        float[][] source = texture(srcW, srcH, rng);

        List<TileNode> nominal = new ArrayList<>();
        Map<String, double[]> truth = new LinkedHashMap<>();
        List<String> configLines = new ArrayList<>();
        configLines.add("dim = 2");

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                String name = (idx + 1) + ".tif";
                double gx = c * stepX;
                double gy = r * stepY;
                double nomX = gx * cos - gy * sin;
                double nomY = gx * sin + gy * cos;
                int trueX = (int) Math.round(margin + nomX);
                int trueY = (int) Math.round(margin + nomY);

                File file = dir.resolve(name).toFile();
                writeTile(file, source, trueX, trueY, tileW, tileH, Content.TEXTURE);

                nominal.add(new TileNode(name, file, nomX, nomY, tileW, tileH));
                // Only the sub-pixel rounding residual: the tiles ARE where nominal says they are.
                truth.put(name, new double[] {trueX - margin - nomX, trueY - margin - nomY});
                configLines.add(String.format(Locale.ROOT, "%s; ; (%.3f, %.3f)", name, nomX, nomY));
            }
        }

        Files.write(dir.resolve("TileConfiguration.txt"), configLines, StandardCharsets.US_ASCII);
        return new Grid(nominal, truth, tileW, tileH);
    }

    /** Convenience: jitter only, no coherent drift. */
    static Grid write(
            Path dir,
            int cols,
            int rows,
            int tileW,
            int tileH,
            double overlapFrac,
            double jitterSigmaPx,
            List<Integer> blank,
            long seed)
            throws IOException {
        return write(dir, cols, rows, tileW, tileH, overlapFrac, jitterSigmaPx, 0.0, blank, seed);
    }

    /** Convenience: a fully textured grid with no blank tiles and no drift. */
    static Grid write(
            Path dir, int cols, int rows, int tileW, int tileH, double overlapFrac, double jitterSigmaPx, long seed)
            throws IOException {
        return write(dir, cols, rows, tileW, tileH, overlapFrac, jitterSigmaPx, 0.0, List.of(), seed);
    }

    private static void writeTile(File file, float[][] source, int x0, int y0, int w, int h, Content content)
            throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        var raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = content == Content.BLANK ? 20000 : (int) source[y0 + y][x0 + x];
                raster.setSample(x, y, 0, Math.max(0, Math.min(65535, v)));
            }
        }
        if (!ImageIO.write(img, "TIFF", file)) {
            throw new IOException("No TIFF writer available for " + file);
        }
    }

    /**
     * Multi-octave value noise.
     *
     * <p>Deliberately not white noise: the search downsamples by up to 8x, and white noise averages
     * away to nothing under a box filter. Real tissue has structure at every scale, and so must the
     * fixture, or the coarse levels would be testing nothing.
     */
    private static float[][] texture(int w, int h, Random rng) {
        double[][] acc = new double[h][w];
        for (int octave = 0; octave < 6; octave++) {
            int cell = 1 << (octave + 2);
            int gw = w / cell + 2;
            int gh = h / cell + 2;
            double[][] grid = new double[gh][gw];
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    grid[y][x] = rng.nextDouble();
                }
            }
            double amp = 1.0 / (octave + 1);
            for (int y = 0; y < h; y++) {
                double gy = (double) y / cell;
                int y0 = (int) gy;
                double fy = gy - y0;
                for (int x = 0; x < w; x++) {
                    double gx = (double) x / cell;
                    int x0 = (int) gx;
                    double fx = gx - x0;
                    double top = grid[y0][x0] * (1 - fx) + grid[y0][x0 + 1] * fx;
                    double bot = grid[y0 + 1][x0] * (1 - fx) + grid[y0 + 1][x0 + 1] * fx;
                    acc[y][x] += amp * (top * (1 - fy) + bot * fy);
                }
            }
        }
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y][x] = (float) (2000 + 20000 * acc[y][x]);
            }
        }
        return out;
    }

    private static void centre(double[] values) {
        double mean = 0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;
        for (int i = 0; i < values.length; i++) {
            values[i] -= mean;
        }
    }
}
