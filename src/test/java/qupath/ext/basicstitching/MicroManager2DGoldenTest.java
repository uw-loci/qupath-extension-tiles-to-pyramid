package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.MicroManagerFixture.Layout;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

/**
 * The Micro-Manager 2D layouts that already stitched correctly must stitch to the same image after
 * plane coordinates are read per page. The expected digests were recorded from release 0.8.1
 * (309d5e9), before the change: the tile mappings, and each output's shape, pixel size, channel
 * names and every sample of every plane at every pyramid level. (The files' bytes differ between
 * two runs of the same code, because the OME-XML carries a fresh UUID.)
 */
class MicroManager2DGoldenTest {

    @Test
    void multiChannelMmStackWithTags(@TempDir Path tmp) throws Exception {
        check(tmp, MicroManagerFixture.of(Layout.MMSTACK, 3, 1, 1), "C3_TAGS");
    }

    @Test
    void multiChannelMmStackWithoutTagsOrFrameKeys(@TempDir Path tmp) throws Exception {
        check(tmp, MicroManagerFixture.of(Layout.MMSTACK, 3, 1, 1).withoutTags().firstFrameKeyOnly(), "C3_BARE");
    }

    @Test
    void singleChannelMmStack(@TempDir Path tmp) throws Exception {
        check(tmp, MicroManagerFixture.of(Layout.MMSTACK, 1, 1, 1), "C1_MMSTACK");
    }

    @Test
    void singleChannelSinglePlaneSeries(@TempDir Path tmp) throws Exception {
        check(tmp, MicroManagerFixture.of(Layout.SINGLE_PLANE, 1, 1, 1), "C1_SINGLEPLANE");
    }

    private static void check(Path tmp, MicroManagerFixture fixture, String key) throws Exception {
        Path src = fixture.write(tmp);
        List<TileMapping> mappings =
                new MicroManagerMetadataStrategy().prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        List<String> lines = new ArrayList<>();
        for (TileMapping m : mappings) {
            var r = m.region;
            lines.add(m.subdirName + "|" + m.file.getName() + "|" + m.ifdIndex + "|" + r.getX() + "," + r.getY() + ","
                    + r.getWidth() + "," + r.getHeight() + "," + r.getZ() + "," + r.getT());
        }
        lines.sort(null);
        String mapDigest = sha(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingConfig cfg = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                src.toString(),
                out.toString(),
                "LZW",
                MicroManagerFixture.PX_UM,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        cfg.setOverlapBlend(OverlapBlend.LAST_WINS);
        StitchingWorkflow.runDetailed(cfg);
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(out)) {
            for (Path p :
                    s.filter(p -> p.toString().endsWith(".ome.tif")).sorted().toList()) {
                files.add(p.getFileName() + "=" + contentDigest(p));
            }
        }
        String actual = mapDigest + " " + String.join(" ", files);
        System.out.println("GOLDEN " + key + " " + actual);
        assertEquals(expected(key), actual, key + ": mappings or output bytes changed");
    }

    private static String expected(String key) {
        // Recorded on 309d5e9 (0.8.1) by this test, before plane coordinates were read per page.
        String dapi = "DAPI.ome.tif=ab4faafc78f22fee9bfe9208e17d4f2b3a2d86d75e92b9a252db79fa270a9d31";
        String fitc = "FITC.ome.tif=59bfaaa3469c4b7c649e59f020b8e85a8ba5fbcf7422bb982a48c5fc0d02dd6e";
        String tritc = "TRITC.ome.tif=7ed1ba75712079f93bb64e163122189717043e9718cd08acaeebf84599e9b3c3";
        return switch (key) {
            case "C3_TAGS", "C3_BARE" ->
                "fc10c3bf4a49aaa1f0d478964913fa9376174091f7aa0a4a5b6abc7893bb5938 " + dapi + " " + fitc + " " + tritc;
            case "C1_MMSTACK" ->
                "ff4e217817225dbafa36a194abea9cce0b12086e279f67a9d545730e0bd22ee4 MDA_region.ome.tif=ab4faafc78f22fee9bfe9208e17d4f2b3a2d86d75e92b9a252db79fa270a9d31";
            case "C1_SINGLEPLANE" ->
                "338ee397c9d6ff9483155cbcba1b897d5545287bfacf44409e514f1f4a036590 MDA_region.ome.tif=ab4faafc78f22fee9bfe9208e17d4f2b3a2d86d75e92b9a252db79fa270a9d31";
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static String contentDigest(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(file.toUri())) {
            StringBuilder head = new StringBuilder();
            head.append(server.getWidth()).append('x').append(server.getHeight());
            head.append(" C").append(server.nChannels()).append(" Z").append(server.nZSlices());
            head.append(" T").append(server.nTimepoints()).append(" L").append(server.nResolutions());
            head.append(" px").append(server.getPixelCalibration().getAveragedPixelSizeMicrons());
            head.append(' ').append(server.getPixelType());
            for (int c = 0; c < server.nChannels(); c++) {
                head.append(' ').append(server.getChannel(c).getName());
            }
            md.update(head.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int level = 0; level < server.nResolutions(); level++) {
                double d = server.getDownsampleForResolution(level);
                for (int t = 0; t < server.nTimepoints(); t++) {
                    for (int z = 0; z < server.nZSlices(); z++) {
                        BufferedImage img = server.readRegion(RegionRequest.createInstance(
                                server.getPath(), d, 0, 0, server.getWidth(), server.getHeight(), z, t));
                        Raster r = img.getRaster();
                        for (int b = 0; b < r.getNumBands(); b++) {
                            int[] px = r.getSamples(0, 0, r.getWidth(), r.getHeight(), b, (int[]) null);
                            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(px.length * 4);
                            buf.asIntBuffer().put(px);
                            md.update(buf.array());
                        }
                    }
                }
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
