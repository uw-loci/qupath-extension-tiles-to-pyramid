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

    /** As {@link #write}, with the free disk space taken from {@code space}. */
    static Path write(
            ImageServer<BufferedImage> server,
            Path dir,
            String name,
            LevelStaging.FreeSpace space,
            java.util.function.Consumer<String> warnings)
            throws IOException {
        Path out = dir.resolve(name + ".ome.tif");
        DirectTiffOutputWriter.write(
                server, out.toString(), OMEPyramidWriter.CompressionType.LZW, TILE, 1.0, null, warnings, space);
        return out;
    }

    /** Every sample of every channel, z, t and resolution of two OME-TIFFs is the same. */
    static void assertSameBytes(Path a, Path b) throws Exception {
        try (ImageServer<BufferedImage> sa = open(a);
                ImageServer<BufferedImage> sb = open(b)) {
            assertEquals(sa.nResolutions(), sb.nResolutions(), "levels");
            assertEquals(sa.nChannels(), sb.nChannels(), "channels");
            assertEquals(sa.nZSlices(), sb.nZSlices(), "z");
            assertEquals(sa.nTimepoints(), sb.nTimepoints(), "t");
            assertEquals(sa.getPixelType(), sb.getPixelType(), "pixel type");
            for (int level = 0; level < sa.nResolutions(); level++) {
                double d = sa.getDownsampleForResolution(level);
                assertEquals(d, sb.getDownsampleForResolution(level), 1e-9);
                assertEquals(
                        sa.getMetadata().getLevel(level).getWidth(),
                        sb.getMetadata().getLevel(level).getWidth());
                for (int t = 0; t < sa.nTimepoints(); t++) {
                    for (int z = 0; z < sa.nZSlices(); z++) {
                        RegionRequest ra = RegionRequest.createInstance(
                                sa.getPath(), d, 0, 0, sa.getWidth(), sa.getHeight(), z, t);
                        RegionRequest rb = RegionRequest.createInstance(
                                sb.getPath(), d, 0, 0, sb.getWidth(), sb.getHeight(), z, t);
                        BufferedImage ia = sa.readRegion(ra);
                        BufferedImage ib = sb.readRegion(rb);
                        assertEquals(ia.getWidth(), ib.getWidth());
                        assertEquals(ia.getHeight(), ib.getHeight());
                        for (int c = 0; c < sa.nChannels(); c++) {
                            assertArrayEquals(
                                    ia.getRaster().getSamples(0, 0, ia.getWidth(), ia.getHeight(), c, (int[]) null),
                                    ib.getRaster().getSamples(0, 0, ib.getWidth(), ib.getHeight(), c, (int[]) null),
                                    a.getFileName() + " vs " + b.getFileName() + " level " + level + " z " + z + " t "
                                            + t + " c " + c);
                        }
                    }
                }
            }
        }
    }

    /** Staging folders left in {@code dir}. */
    static java.util.List<Path> stagingFolders(Path dir) throws IOException {
        try (var s = java.nio.file.Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(LevelStaging.PREFIX))
                    .toList();
        }
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
