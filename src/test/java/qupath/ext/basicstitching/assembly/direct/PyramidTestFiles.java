package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.regions.RegionRequest;

/** Small 16-bit OME-TIFF pyramids for comparing how levels are made. */
final class PyramidTestFiles {

    /** Odd sizes, so every level has partial edge tiles; tile 128 gives four levels. */
    static final int W = 1501;

    static final int H = 1203;
    static final int TILE = 128;

    private PyramidTestFiles() {}

    static Path write(ImageServer<BufferedImage> server, Path dir, String name) throws IOException {
        Path out = dir.resolve(name + ".ome.tif");
        DirectTiffOutputWriter.write(server, out.toString(), OMEPyramidWriter.CompressionType.LZW, TILE, 1.0, null);
        return out;
    }

    static ImageServer<BufferedImage> open(Path p) throws IOException {
        return ImageServers.buildServer(p.toUri());
    }

    /** Channel {@code ca} of {@code a} equals channel {@code cb} of {@code b} at every level. */
    static void assertSameLevels(Path a, int ca, Path b, int cb) throws Exception {
        try (ImageServer<BufferedImage> sa = open(a);
                ImageServer<BufferedImage> sb = open(b)) {
            assertEquals(sa.nResolutions(), sb.nResolutions(), "levels");
            for (int level = 0; level < sa.nResolutions(); level++) {
                double d = sa.getDownsampleForResolution(level);
                assertEquals(d, sb.getDownsampleForResolution(level), 1e-9);
                int[] va = samples(sa, d, ca);
                int[] vb = samples(sb, d, cb);
                assertEquals(va.length, vb.length, "level " + level + " size");
                assertArrayEquals(va, vb, a.getFileName() + " vs " + b.getFileName() + " level " + level);
            }
        }
    }

    private static int[] samples(ImageServer<BufferedImage> server, double d, int channel) throws IOException {
        BufferedImage img = server.readRegion(RegionRequest.createInstance(server.getPath(), d, 0, 0, W, H));
        return img.getRaster().getSamples(0, 0, img.getWidth(), img.getHeight(), channel, (int[]) null);
    }

    /** 16-bit noise with stretches of empty (zero) canvas, as a mosaic has between tiles. */
    static BufferedImage mosaic(long seed) {
        Random random = new Random(seed);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster r = img.getRaster();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean canvas = (x / 97 + y / 89) % 5 == 0 || x < (y % 37) || y > H - 1 - (x % 23);
                r.setSample(x, y, 0, canvas ? 0 : 1 + random.nextInt(60_000));
            }
        }
        return img;
    }
}
