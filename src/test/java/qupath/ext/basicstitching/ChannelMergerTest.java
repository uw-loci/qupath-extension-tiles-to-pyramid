package qupath.ext.basicstitching;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.assembly.ChannelMergeImageServer;
import qupath.ext.basicstitching.assembly.PyramidImageWriter;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.RegionRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the multichannel stitching path.
 *
 * <p>These tests exercise the full round trip a real widefield IF / BF+IF
 * acquisition takes after per-channel stitching:
 * <ol>
 *   <li>Synthesize N single-channel {@link BufferedImage} "stitched tiles"
 *       filled with distinct constant values (so we can verify which channel
 *       a pixel came from by reading it back)</li>
 *   <li>Wrap each in a {@link WrappedBufferedImageServer}</li>
 *   <li>Write each to a temp directory as a pyramidal OME-TIFF via
 *       {@link PyramidImageWriter#write}</li>
 *   <li>Call {@link ChannelMerger#merge} to combine them into one multichannel
 *       pyramidal OME-TIFF</li>
 *   <li>Re-open the merged file with {@link ImageServers#buildServer} and
 *       verify the number of channels, channel names, dimensions, and that
 *       each channel carries the correct constant value</li>
 * </ol>
 *
 * <p>This is the same round trip that happens in production when a widefield
 * IF acquisition stitches its per-channel subdirectories and then calls
 * {@code ChannelMerger.merge} to combine the outputs. If this test passes,
 * we have high confidence the pipeline works end to end; if it fails, the
 * failure mode (dimension mismatch, channel count wrong, pixel corruption)
 * is visible in the assertions without needing hardware.
 */
public class ChannelMergerTest {

    // Must be large enough that PyramidImageWriter's maxLevels computation
    // yields >= 2. PyramidImageWriter.writeOMETIFF passes `maxLevels` as the
    // second arg to OMEPyramidWriter.Builder.scaledDownsampling(baseDownsample,
    // scaleFactor) -- but that method's second arg is a double *scale factor*,
    // not a level count. When maxLevels=1 it degenerates to scaleFactor=1.0
    // ("each level is identical to the previous"), which the builder greedily
    // allocates Doubles for until the JVM OOMs. This is a latent bug in
    // PyramidImageWriter that production doesn't hit because real acquisitions
    // have large enough images to cap at maxLevels=6-8.
    //
    // 2048x2048 gives maxLevels=2 (~coarse 2x/4x pyramid), enough to exercise
    // the multichannel round trip without triggering the degenerate case.
    //
    // Tracked for fix: `PyramidImageWriter.writeOMETIFF` -- scaledDownsampling
    // signature misuse, see TODO_LIST.md under qupath-extension-tiles-to-pyramid.
    private static final int TEST_WIDTH = 2048;
    private static final int TEST_HEIGHT = 2048;
    private static final int[] CHANNEL_VALUES = {100, 200, 300, 400};
    private static final String[] CHANNEL_NAMES = {"DAPI", "FITC", "TRITC", "Cy5"};

    /**
     * Full round trip for a 4-channel IF-style merge. This is the primary
     * confidence test before touching hardware.
     */
    @Test
    public void testFourChannelRoundTrip() throws Exception {
        Path tempDir = Files.createTempDirectory("channel-merger-test-4ch-");
        try {
            List<String> channelPaths = writeSingleChannelSources(tempDir, 4);
            assertEquals(4, channelPaths.size(), "All 4 single-channel pyramids should be written");

            List<String> channelNames = List.of(CHANNEL_NAMES);
            String mergedPath = ChannelMerger.merge(
                    channelPaths,
                    channelNames,
                    tempDir.toString(),
                    "merged_4ch",
                    "LZW",
                    StitchingConfig.OutputFormat.OME_TIFF);
            assertNotNull(mergedPath, "ChannelMerger.merge should produce an output path");
            assertTrue(Files.exists(Path.of(mergedPath)),
                    "Merged output file should exist at " + mergedPath);

            // Re-open the merged output and verify structure + pixel values per channel.
            try (ImageServer<BufferedImage> merged = ImageServers.buildServer(Path.of(mergedPath).toUri())) {
                assertEquals(4, merged.nChannels(),
                        "Merged image should report 4 channels, got " + merged.nChannels());
                assertEquals(TEST_WIDTH, merged.getWidth(),
                        "Merged image width should match sources");
                assertEquals(TEST_HEIGHT, merged.getHeight(),
                        "Merged image height should match sources");

                // Verify channel order + values: channel i should carry CHANNEL_VALUES[i].
                for (int c = 0; c < 4; c++) {
                    assertEquals(CHANNEL_NAMES[c], merged.getChannel(c).getName(),
                            "Channel " + c + " name should be preserved");
                }

                // Read a small region and sample the pixel value in each channel.
                RegionRequest request = RegionRequest.createInstance(
                        merged.getPath(), 1.0, 0, 0, 16, 16);
                BufferedImage tile = merged.readRegion(request);
                assertNotNull(tile, "Merged region read should return a tile");
                assertEquals(4, tile.getRaster().getNumBands(),
                        "Read tile should have 4 bands");

                WritableRaster raster = tile.getRaster();
                for (int c = 0; c < 4; c++) {
                    int sample = raster.getSample(8, 8, c);
                    assertEquals(CHANNEL_VALUES[c], sample,
                            String.format("Channel %d ('%s') pixel should carry value %d, got %d",
                                    c, CHANNEL_NAMES[c], CHANNEL_VALUES[c], sample));
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Two-channel case, the minimum useful merge. Exercises the short-path
     * without the larger-image overhead of the 4-channel test.
     */
    @Test
    public void testTwoChannelRoundTrip() throws Exception {
        Path tempDir = Files.createTempDirectory("channel-merger-test-2ch-");
        try {
            List<String> channelPaths = writeSingleChannelSources(tempDir, 2);
            String mergedPath = ChannelMerger.merge(
                    channelPaths,
                    List.of("BF", "DAPI"),
                    tempDir.toString(),
                    "merged_2ch",
                    "LZW",
                    StitchingConfig.OutputFormat.OME_TIFF);
            assertNotNull(mergedPath, "2-channel merge should succeed");

            try (ImageServer<BufferedImage> merged = ImageServers.buildServer(Path.of(mergedPath).toUri())) {
                assertEquals(2, merged.nChannels());
                assertEquals("BF", merged.getChannel(0).getName());
                assertEquals("DAPI", merged.getChannel(1).getName());
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Regression test for the OWS3 widefield IF bug (2026-04-13): using JPEG-2000
     * compression with {@code channelsInterleaved()} would silently drop channels
     * beyond the first, producing a "merged" file that the viewer reported as
     * 1 channel even though the ChannelMergeImageServer correctly reported N.
     * This test exercises the EXACT compression production uses and reads back
     * the pixel values per channel to prove both channels survived the round
     * trip.
     */
    @Test
    public void testTwoChannelRoundTripWithJ2K() throws Exception {
        Path tempDir = Files.createTempDirectory("channel-merger-test-j2k-");
        try {
            List<String> channelPaths = writeSingleChannelSources(tempDir, 2);
            String mergedPath = ChannelMerger.merge(
                    channelPaths,
                    List.of("DAPI", "FITC"),
                    tempDir.toString(),
                    "merged_2ch_j2k",
                    "J2K",
                    StitchingConfig.OutputFormat.OME_TIFF);
            assertNotNull(mergedPath, "2-channel J2K merge should succeed");

            try (ImageServer<BufferedImage> merged = ImageServers.buildServer(Path.of(mergedPath).toUri())) {
                assertEquals(2, merged.nChannels(),
                        "J2K merged image should report 2 channels, got " + merged.nChannels());
                assertEquals("DAPI", merged.getChannel(0).getName());
                assertEquals("FITC", merged.getChannel(1).getName());

                // Read back a small region and verify both channels carry their
                // synthesized constant values -- if channel 1 gets lost during
                // the JPEG-2000 write (the OWS3 bug), sample(8,8,1) would be 0
                // or the same as channel 0.
                RegionRequest request = RegionRequest.createInstance(
                        merged.getPath(), 1.0, 0, 0, 16, 16);
                BufferedImage tile = merged.readRegion(request);
                assertNotNull(tile, "J2K merged region read should return a tile");
                assertEquals(2, tile.getRaster().getNumBands(),
                        "J2K read tile should have 2 bands");

                WritableRaster raster = tile.getRaster();
                int ch0 = raster.getSample(8, 8, 0);
                int ch1 = raster.getSample(8, 8, 1);
                // J2K is lossy by default -- allow a few counts of tolerance.
                assertEquals(CHANNEL_VALUES[0], ch0, 5,
                        String.format("J2K channel 0 ('DAPI') should carry value %d, got %d",
                                CHANNEL_VALUES[0], ch0));
                assertEquals(CHANNEL_VALUES[1], ch1, 5,
                        String.format("J2K channel 1 ('FITC') should carry value %d, got %d -- "
                                        + "if 0 or matches channel 0, channelsInterleaved() dropped it",
                                CHANNEL_VALUES[1], ch1));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Single-input merge should be rejected (there's nothing to merge). This
     * guards the early-return branch in ChannelMerger so a misconfigured
     * acquisition with one channel selected can't silently produce a
     * "merged" file that's really just the source renamed.
     */
    @Test
    public void testSingleInputIsRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("channel-merger-test-single-");
        try {
            List<String> channelPaths = writeSingleChannelSources(tempDir, 1);
            String mergedPath = ChannelMerger.merge(
                    channelPaths,
                    List.of("OnlyChannel"),
                    tempDir.toString(),
                    "merged_single",
                    "LZW",
                    StitchingConfig.OutputFormat.OME_TIFF);
            assertNull(mergedPath, "Merge of a single input should return null");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * ChannelMergeImageServer should reject sources with mismatched dimensions
     * at construction time -- a misaligned channel would silently corrupt the
     * multichannel output.
     */
    @Test
    public void testDimensionMismatchRejected() throws Exception {
        BufferedImage a = BufferedImageTools.createImage(256, 256, PixelType.UINT16, 1);
        BufferedImage b = BufferedImageTools.createImage(128, 128, PixelType.UINT16, 1);
        fillWithConstant(a, 100);
        fillWithConstant(b, 200);

        ImageServer<BufferedImage> serverA = new WrappedBufferedImageServer("a", a);
        ImageServer<BufferedImage> serverB = new WrappedBufferedImageServer("b", b);

        assertThrows(IllegalArgumentException.class,
                () -> new ChannelMergeImageServer(List.of(serverA, serverB), null),
                "Sources with different dimensions should throw on construction");

        serverA.close();
        serverB.close();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Synthesizes {@code n} single-channel OME-TIFF pyramids in {@code tempDir},
     * each filled with a distinct constant value so the merged output can be
     * sanity-checked channel-by-channel. Returns the list of written file paths
     * in order.
     */
    private List<String> writeSingleChannelSources(Path tempDir, int n) throws IOException {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BufferedImage img = BufferedImageTools.createImage(TEST_WIDTH, TEST_HEIGHT, PixelType.UINT16, 1);
            fillWithConstant(img, CHANNEL_VALUES[i]);
            ImageServer<BufferedImage> source = new WrappedBufferedImageServer("source-" + i, img,
                    List.of(qupath.lib.images.servers.ImageChannel.getInstance(
                            CHANNEL_NAMES[i], qupath.lib.images.servers.ImageChannel.getDefaultChannelColor(i))));

            String outPath = PyramidImageWriter.write(
                    source,
                    tempDir.toString(),
                    "channel_" + i,
                    "LZW",
                    1.0,
                    StitchingConfig.OutputFormat.OME_TIFF);
            assertNotNull(outPath, "Single-channel pyramid " + i + " should write successfully");
            paths.add(outPath);

            try {
                source.close();
            } catch (Exception e) {
                // ignore close errors in test cleanup
            }
        }
        return paths;
    }

    private static void fillWithConstant(BufferedImage img, int value) {
        WritableRaster raster = img.getRaster();
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int i = 0; i < w; i++) {
            row[i] = value;
        }
        for (int y = 0; y < h; y++) {
            for (int band = 0; band < raster.getNumBands(); band++) {
                raster.setSamples(0, y, w, 1, band, row);
            }
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(ChannelMergerTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best-effort cleanup
        }
    }
}
