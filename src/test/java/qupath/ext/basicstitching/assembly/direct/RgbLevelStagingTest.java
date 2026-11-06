package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.H;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.W;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.open;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.stagingFolders;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.write;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.RegionRequest;

/**
 * RGB pyramids are staged too: every level below full resolution is the 2 x 2 mean of the level
 * before, the staging folder is gone afterwards, and the levels stay within rounding of the ones
 * QuPath's pyramidalized server made before RGB was staged.
 */
class RgbLevelStagingTest {

    private static final LevelStaging.FreeSpace PLENTY = dir -> Long.MAX_VALUE / 4;
    private static final LevelStaging.FreeSpace NONE = dir -> 0L;

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void noTileCache() {
        ImageServerProvider.setCache(null, BufferedImage.class);
    }

    @Test
    void eachStagedLevelIsTheTwoByTwoMeanOfTheLevelBefore() throws Exception {
        BufferedImage src = rgbMosaic(21);
        List<String> warnings = new ArrayList<>();
        Path out = write(new WrappedBufferedImageServer("rgb", src), dir, "staged", PLENTY, warnings::add);
        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        assertTrue(stagingFolders(dir).isEmpty(), "staging folder left after the write");

        try (ImageServer<BufferedImage> server = open(out)) {
            assertTrue(server.isRGB());
            assertTrue(server.nResolutions() > 2, "levels: " + server.nResolutions());
            int[][] expected = channels(src, W, H);
            int w = W, h = H;
            for (int level = 0; level < server.nResolutions(); level++) {
                if (level > 0) {
                    expected = halveReference(expected, w, h);
                    w /= 2;
                    h /= 2;
                }
                BufferedImage img = readLevel(server, level);
                assertEquals(w, img.getWidth(), "level " + level + " width");
                assertEquals(h, img.getHeight(), "level " + level + " height");
                int[][] actual = channels(img, w, h);
                for (int c = 0; c < 3; c++) {
                    assertArrayEquals(expected[c], actual[c], "level " + level + " channel " + c);
                }
            }
        }
    }

    @Test
    void stagedLevelsStayCloseToTheUnstagedOnes() throws Exception {
        BufferedImage src = rgbMosaic(22);
        Path staged = write(new WrappedBufferedImageServer("rgb", src), dir, "staged", PLENTY, null);
        Path unstaged = write(new WrappedBufferedImageServer("rgb", src), dir, "unstaged", NONE, null);
        try (ImageServer<BufferedImage> a = open(staged);
                ImageServer<BufferedImage> b = open(unstaged)) {
            assertEquals(a.nResolutions(), b.nResolutions(), "levels");
            for (int level = 0; level < a.nResolutions(); level++) {
                BufferedImage ia = readLevel(a, level);
                BufferedImage ib = readLevel(b, level);
                assertEquals(ia.getWidth(), ib.getWidth(), "level " + level);
                int[][] ca = channels(ia, ia.getWidth(), ia.getHeight());
                int[][] cb = channels(ib, ib.getWidth(), ib.getHeight());
                double diff = 0;
                for (int c = 0; c < 3; c++) {
                    for (int i = 0; i < ca[c].length; i++) {
                        diff += Math.abs(ca[c][i] - cb[c][i]);
                    }
                }
                double mean = diff / (3.0 * ca[0].length);
                if (level == 0) {
                    assertEquals(0.0, mean, "full resolution must be identical");
                } else {
                    // Different resampling (QuPath resizes from the level before); the image must
                    // still be the same picture, within a few counts on average.
                    assertTrue(mean < 6.0, "level " + level + " mean |difference| " + mean);
                }
            }
        }
    }

    @Test
    void halvingInterleavedAveragesEachSampleOfEachBlock() {
        byte[] px = {
            // row 0: two pixels
            (byte) 10, (byte) 200, (byte) 0, (byte) 20, (byte) 255, (byte) 1,
            // row 1
            (byte) 30, (byte) 100, (byte) 0, (byte) 41, (byte) 255, (byte) 2
        };
        byte[] out = DirectTiffOutputWriter.halveInterleaved(px, 2, 2, 3);
        assertEquals(3, out.length);
        assertEquals(Math.rint(101 / 4.0), out[0] & 0xFF); // 10 + 20 + 30 + 41
        assertEquals(Math.rint(810 / 4.0), out[1] & 0xFF); // 200 + 255 + 100 + 255
        assertEquals(1, out[2] & 0xFF); // 0 + 1 + 0 + 2 = 3 -> 0.75 -> 1
        assertEquals(0, DirectTiffOutputWriter.halveInterleaved(new byte[3], 1, 1, 3).length);
    }

    private static BufferedImage readLevel(ImageServer<BufferedImage> server, int level) throws Exception {
        // Request exactly the stored level (W x H at d would round its size up by one pixel).
        double d = server.getDownsampleForResolution(level);
        var size = server.getMetadata().getLevel(level);
        return server.readRegion(
                RegionRequest.createInstance(server.getPath(), d, 0, 0, (int) Math.round(size.getWidth() * d), (int)
                        Math.round(size.getHeight() * d)));
    }

    /** Planes R, G, B of a {@code w x h} image. */
    private static int[][] channels(BufferedImage img, int w, int h) {
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        int[][] out = new int[3][w * h];
        for (int i = 0; i < rgb.length; i++) {
            out[0][i] = (rgb[i] >> 16) & 0xFF;
            out[1][i] = (rgb[i] >> 8) & 0xFF;
            out[2][i] = rgb[i] & 0xFF;
        }
        return out;
    }

    /** Independent reference: each sample the rounded mean of its 2 x 2 block, odd edge dropped. */
    private static int[][] halveReference(int[][] planes, int w, int h) {
        int ow = w / 2, oh = h / 2;
        int[][] out = new int[planes.length][ow * oh];
        for (int c = 0; c < planes.length; c++) {
            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    int sum = planes[c][(2 * y) * w + 2 * x]
                            + planes[c][(2 * y) * w + 2 * x + 1]
                            + planes[c][(2 * y + 1) * w + 2 * x]
                            + planes[c][(2 * y + 1) * w + 2 * x + 1];
                    out[c][y * ow + x] = (int) Math.rint(sum / 4.0);
                }
            }
        }
        return out;
    }

    /** Smooth RGB noise with white canvas between "tiles", as a white-background mosaic has. */
    private static BufferedImage rgbMosaic(long seed) {
        Random random = new Random(seed);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean canvas = (x / 97 + y / 89) % 5 == 0;
                int r = canvas ? 255 : (x * 255 / W + random.nextInt(20)) & 0xFF;
                int g = canvas ? 255 : (y * 255 / H + random.nextInt(20)) & 0xFF;
                int b = canvas ? 255 : random.nextInt(256);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }
}
