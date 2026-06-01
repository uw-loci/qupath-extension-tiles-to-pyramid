package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.assembly.PyramidImageWriter;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.RegionRequest;

/**
 * Regression tests for {@link qupath.ext.basicstitching.assembly.direct.DirectTiffOutputWriter}
 * (exercised through {@link PyramidImageWriter#write}).
 *
 * <p>The bug this guards against: QuPath's {@code OMEPyramidWriter} silently
 * corrupted downsampled pyramid levels when a level's dimensions were not a
 * clean multiple of the tile size -- the full-resolution level was intact but
 * the upper levels came out black, with no thrown exception. Stitched mosaics
 * almost never have clean-multiple dimensions, so this was the common case.
 *
 * <p>These tests use dimensions chosen so that <i>every</i> pyramid level is a
 * non-multiple of the 512 px tile size, then assert that every level is present,
 * correctly sized, and -- critically -- NOT black, with interior pixels matching
 * the expected downsampled gradient. If the old corruption returned, the upper
 * levels would read back as all-zero and these assertions would fail.
 */
public class DirectTiffPyramidTest {

    // 2600 x 2200 -> levels at downsample 1 (2600x2200), 2 (1300x1100), 4 (650x550).
    // None of these dimensions is a multiple of 512, so all levels exercise the
    // partial-edge-tile path that used to corrupt the upper levels.
    private static final int W = 2600;
    private static final int H = 2200;
    private static final int B_CONST = 60;

    @Test
    public void testRgbPyramidAllLevelsIntact() throws Exception {
        Path tempDir = Files.createTempDirectory("direct-tiff-rgb-");
        try {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < H; y++) {
                int g = (int) ((long) y * 250 / H);
                for (int x = 0; x < W; x++) {
                    int r = (int) ((long) x * 250 / W);
                    img.setRGB(x, y, (r << 16) | (g << 8) | B_CONST);
                }
            }
            ImageServer<BufferedImage> source = new WrappedBufferedImageServer("rgb", img);
            assertTrue(source.isRGB(), "Test source should be RGB");
            assertEquals(3, source.nChannels());

            String outPath = PyramidImageWriter.write(
                    source, tempDir.toString(), "rgb_pyramid", "LZW", 1.0, StitchingConfig.OutputFormat.OME_TIFF);
            source.close();
            assertNotNull(outPath, "RGB pyramid should write");

            try (ImageServer<BufferedImage> read =
                    ImageServers.buildServer(Path.of(outPath).toUri())) {
                assertEquals(W, read.getWidth());
                assertEquals(H, read.getHeight());
                assertEquals(3, read.nChannels());
                assertTrue(read.nResolutions() >= 3, "Expected >=3 pyramid levels, got " + read.nResolutions());

                for (int level = 0; level < read.nResolutions(); level++) {
                    double d = read.getDownsampleForResolution(level);
                    BufferedImage levelImg = readWholeLevel(read, d);
                    int lw = levelImg.getWidth();
                    int lh = levelImg.getHeight();
                    assertLevelDimensions(level, d, lw, lh);
                    assertNotBlack(level, levelImg);

                    // Interior pixel near the center should follow the gradient:
                    // R grows with x, G grows with y, B is constant.
                    int cx = lw / 2;
                    int cy = lh / 2;
                    WritableRaster lr = levelImg.getRaster();
                    int r = lr.getSample(cx, cy, 0);
                    int g = lr.getSample(cx, cy, 1);
                    int b = lr.getSample(cx, cy, 2);
                    assertEquals(125, r, 18, "Level " + level + " center R off-gradient (band0)");
                    assertEquals(125, g, 18, "Level " + level + " center G off-gradient (band1)");
                    assertEquals(B_CONST, b, 18, "Level " + level + " center B off-gradient (band2)");
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void test16BitSingleChannelPyramidAllLevelsIntact() throws Exception {
        Path tempDir = Files.createTempDirectory("direct-tiff-u16-");
        try {
            BufferedImage img = BufferedImageTools.createImage(W, H, PixelType.UINT16, 1);
            WritableRaster raster = img.getRaster();
            int[] row = new int[W];
            for (int y = 0; y < H; y++) {
                int base = (int) ((long) y * 20000 / H);
                for (int x = 0; x < W; x++) {
                    row[x] = base + (int) ((long) x * 10000 / W);
                }
                raster.setSamples(0, y, W, 1, 0, row);
            }
            ImageServer<BufferedImage> source = new WrappedBufferedImageServer(
                    "u16", img, List.of(ImageChannel.getInstance("Gray", ImageChannel.getDefaultChannelColor(0))));
            assertFalse(source.isRGB());
            assertEquals(PixelType.UINT16, source.getPixelType());

            String outPath = PyramidImageWriter.write(
                    source, tempDir.toString(), "u16_pyramid", "LZW", 1.0, StitchingConfig.OutputFormat.OME_TIFF);
            source.close();
            assertNotNull(outPath, "16-bit pyramid should write");

            try (ImageServer<BufferedImage> read =
                    ImageServers.buildServer(Path.of(outPath).toUri())) {
                assertEquals(W, read.getWidth());
                assertEquals(H, read.getHeight());
                assertEquals(1, read.nChannels());
                assertEquals(PixelType.UINT16, read.getPixelType());
                assertTrue(read.nResolutions() >= 3, "Expected >=3 pyramid levels, got " + read.nResolutions());

                for (int level = 0; level < read.nResolutions(); level++) {
                    double d = read.getDownsampleForResolution(level);
                    BufferedImage levelImg = readWholeLevel(read, d);
                    assertLevelDimensions(level, d, levelImg.getWidth(), levelImg.getHeight());
                    assertNotBlack(level, levelImg);

                    int cx = levelImg.getWidth() / 2;
                    int cy = levelImg.getHeight() / 2;
                    int v = levelImg.getRaster().getSample(cx, cy, 0);
                    // center full-res ~ (W/2, H/2) -> base ~10000, x-term ~5000 -> ~15000.
                    assertEquals(15000, v, 1500, "Level " + level + " center value off-gradient");
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static BufferedImage readWholeLevel(ImageServer<BufferedImage> server, double downsample)
            throws IOException {
        RegionRequest request =
                RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight());
        BufferedImage img = server.readRegion(request);
        assertNotNull(img, "readRegion returned null at downsample " + downsample);
        return img;
    }

    private static void assertLevelDimensions(int level, double d, int lw, int lh) {
        int expW = (int) (W / d);
        int expH = (int) (H / d);
        // Allow a 1px rounding slack between the writer's floor and the reader's geometry.
        assertTrue(Math.abs(lw - expW) <= 1, "Level " + level + " width " + lw + " != expected ~" + expW);
        assertTrue(Math.abs(lh - expH) <= 1, "Level " + level + " height " + lh + " != expected ~" + expH);
    }

    /** Fail if the level is entirely zero -- the signature of the old corruption bug. */
    private static void assertNotBlack(int level, BufferedImage img) {
        WritableRaster raster = img.getRaster();
        int w = img.getWidth();
        int h = img.getHeight();
        int bands = raster.getNumBands();
        long max = 0;
        // Sample a sparse grid -- enough to catch an all-black level cheaply.
        int stepX = Math.max(1, w / 32);
        int stepY = Math.max(1, h / 32);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                for (int band = 0; band < bands; band++) {
                    max = Math.max(max, raster.getSample(x, y, band));
                }
            }
        }
        assertTrue(max > 0, "Level " + level + " is entirely black (max sample 0) -- pyramid corruption regression");
    }

    private static void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(DirectTiffPyramidTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best-effort cleanup
        }
    }
}
