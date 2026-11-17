package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.AcquisitionRecord;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;

/**
 * The time increment of a TileConfiguration.txt time lapse ({@code t{nn}/} folders): a
 * TileConfiguration.txt has no field for it, so it is the interval in the QPSC acquisition's
 * {@code acquisition_metadata.json} ({@code acquisition.time_lapse.interval_s}), else unknown and
 * not written. Each case is read back through {@code ImageServers}, which is how QuPath gets the
 * seconds it shows in place of timepoint numbers.
 */
class TimeIncrementTest {

    private static final int TILE = 40;
    private static final double PIXEL = 0.5;

    @Test
    void theRecordIsReadBesideOrAboveTheFolder(@TempDir Path tmp) throws Exception {
        Path root = acquisition(tmp, 2, "30.0");
        assertEquals(30.0, AcquisitionRecord.timeIncrementS(root));
        assertEquals(30.0, AcquisitionRecord.timeIncrementS(root.resolve("State0")), "a tile folder selected itself");
        Files.writeString(
                root.resolve(AcquisitionRecord.FILE_NAME),
                "{\"acquisition\":{\"time_lapse\":{\"timepoints\":2,\"interval_s\":0}}}");
        assertNull(AcquisitionRecord.timeIncrementS(root), "back-to-back timepoints have no known spacing");
        Files.writeString(
                root.resolve(AcquisitionRecord.FILE_NAME),
                "{\"acquisition\":{\"time_lapse\":{\"timepoints\":2,\"interval_s\":null}}}");
        assertNull(AcquisitionRecord.timeIncrementS(root), "no interval");
        Files.writeString(root.resolve(AcquisitionRecord.FILE_NAME), "{\"acquisition\":{\"pixel_size_um\":0.5}}");
        assertNull(AcquisitionRecord.timeIncrementS(root), "no time lapse");
    }

    @Test
    void anOmeTiffCarriesTheRecordedIncrement(@TempDir Path tmp) throws Exception {
        Path out = stitch(acquisition(tmp, 3, "12.5"), StitchingConfig.OutputFormat.OME_TIFF);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(3, server.nTimepoints());
            var cal = server.getPixelCalibration();
            assertEquals(TimeUnit.SECONDS, cal.getTimeUnit());
            assertEquals(0.0, cal.getTimepoint(0), 1e-9);
            assertEquals(12.5, cal.getTimepoint(1), 1e-9);
            assertEquals(25.0, cal.getTimepoint(2), 1e-9);
            for (int t = 0; t < 3; t++) {
                BufferedImage plane = server.readRegion(1.0, 0, 0, TILE, TILE, 0, t);
                assertEquals(value(t), plane.getRaster().getSample(TILE / 2, TILE / 2, 0), "t " + t);
            }
        }
        assertTrue(
                stitchInfo(out)
                        .lines()
                        .anyMatch(l -> l.strip().equals("time increment (s): 12.5 (from acquisition_metadata.json)")),
                stitchInfo(out));
    }

    @Test
    void withNoRecordTheIncrementIsLeftUnset(@TempDir Path tmp) throws Exception {
        Path out = stitch(acquisition(tmp, 2, null), StitchingConfig.OutputFormat.OME_TIFF);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(2, server.nTimepoints());
            assertTrue(Double.isNaN(server.getPixelCalibration().getTimepoint(1)), "no time claimed");
        }
        assertTrue(
                stitchInfo(out).lines().anyMatch(l -> l.strip().equals("time increment (s): unknown (not written)")),
                stitchInfo(out));
    }

    @Test
    void anOmeZarrCarriesTheRecordedIncrement(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(BloscSupport.isAvailable(), BloscSupport.unavailableReason());
        Path out = stitch(acquisition(tmp, 2, "30"), StitchingConfig.OutputFormat.OME_ZARR);
        JsonObject ms = multiscales(out);
        assertEquals("second", axis(ms, "t").get("unit").getAsString());
        for (var ds : ms.getAsJsonArray("datasets")) {
            assertEquals(30.0, scale(ds.getAsJsonObject()).get(0).getAsDouble(), "t scale is the interval");
        }
        String xml = Files.readString(out.resolve("OME/METADATA.ome.xml"));
        assertTrue(xml.contains("TimeIncrement=\"30.0\""), xml);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(2, server.nTimepoints());
            assertEquals(30.0, server.getPixelCalibration().getTimepoint(1), 1e-9);
            for (int t = 0; t < 2; t++) {
                BufferedImage plane = server.readRegion(1.0, 0, 0, TILE, TILE, 0, t);
                assertEquals(value(t), plane.getRaster().getSample(TILE / 2, TILE / 2, 0), "t " + t);
            }
        }
    }

    @Test
    void anOmeZarrWithNoRecordHasAUnitlessTimeAxis(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(BloscSupport.isAvailable(), BloscSupport.unavailableReason());
        Path out = stitch(acquisition(tmp, 2, null), StitchingConfig.OutputFormat.OME_ZARR);
        JsonObject ms = multiscales(out);
        assertFalse(axis(ms, "t").has("unit"), "no unit on an unknown interval");
        assertEquals(
                1.0,
                scale(ms.getAsJsonArray("datasets").get(0).getAsJsonObject())
                        .get(0)
                        .getAsDouble());
        assertFalse(Files.readString(out.resolve("OME/METADATA.ome.xml")).contains("TimeIncrement"));
    }

    @Test
    void aMergeKeepsTheIncrement(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(BloscSupport.isAvailable(), BloscSupport.unavailableReason());
        Path a = stitch(acquisition(tmp.resolve("a"), 2, "30"), StitchingConfig.OutputFormat.OME_ZARR);
        Path b = stitch(acquisition(tmp.resolve("b"), 2, "30"), StitchingConfig.OutputFormat.OME_ZARR);
        String merged = ChannelMerger.merge(
                List.of(a.toString(), b.toString()),
                List.of("A", "B"),
                tmp.toString(),
                "merged",
                "zstd",
                StitchingConfig.OutputFormat.OME_ZARR);
        assertNotNull(merged);
        Path m = Path.of(merged);
        assertTrue(axis(multiscales(m), "t").has("unit"), "merged t keeps its unit");
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(m.toUri())) {
            assertEquals(2, server.nChannels());
            assertEquals(2, server.nTimepoints());
            assertEquals(30.0, server.getPixelCalibration().getTimepoint(1), 1e-6);
        }
    }

    private static int value(int t) {
        return 20 + 10 * t;
    }

    /**
     * A QPSC-shaped time lapse: {@code bounds/State0/t{nn}/} tiles with a TileConfiguration.txt, and
     * a record with {@code interval} as the JSON text of {@code interval_s} when it is given.
     */
    private static Path acquisition(Path tmp, int timepoints, String interval) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("bounds"));
        Path group = Files.createDirectories(root.resolve("State0"));
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        for (int i = 0; i < 2; i++) {
            cfg.append(String.format(Locale.ROOT, "%d.tif; ; (%.1f, 0.0)%n", i, i * TILE * 0.9));
        }
        Files.writeString(group.resolve("TileConfiguration.txt"), cfg.toString());
        for (int t = 0; t < timepoints; t++) {
            Path tdir = Files.createDirectories(group.resolve(String.format(Locale.ROOT, "t%02d", t)));
            for (int i = 0; i < 2; i++) {
                BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
                var raster = img.getRaster();
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        raster.setSample(x, y, 0, value(t));
                    }
                }
                ImageIO.write(img, "TIFF", tdir.resolve(i + ".tif").toFile());
            }
        }
        if (interval != null) {
            Files.writeString(
                    root.resolve(AcquisitionRecord.FILE_NAME),
                    "{\"schema_version\":\"1.0\",\"acquisition\":{\"pixel_size_um\":" + PIXEL
                            + ",\"time_lapse\":{\"timepoints\":" + timepoints + ",\"interval_s\":" + interval
                            + ",\"projection\":\"none\",\"order\":\"region\"}}}");
        }
        return root;
    }

    private static Path stitch(Path root, StitchingConfig.OutputFormat format) throws IOException {
        Path out = Files.createDirectories(root.resolveSibling("out"));
        StitchingConfig cfg = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                root.toString(),
                out.toString(),
                format == StitchingConfig.OutputFormat.OME_ZARR ? "zstd" : "LZW",
                PIXEL,
                1.0,
                "State",
                0.0,
                format);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
        assertEquals(1, result.outputs().size(), result.toString());
        return Path.of(result.outputs().get(0));
    }

    private static JsonObject multiscales(Path zarr) throws IOException {
        return JsonParser.parseString(Files.readString(zarr.resolve(".zattrs")))
                .getAsJsonObject()
                .getAsJsonArray("multiscales")
                .get(0)
                .getAsJsonObject();
    }

    private static JsonArray scale(JsonObject dataset) {
        return dataset.getAsJsonArray("coordinateTransformations")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("scale");
    }

    private static JsonObject axis(JsonObject multiscales, String name) {
        for (var a : multiscales.getAsJsonArray("axes")) {
            if (name.equals(a.getAsJsonObject().get("name").getAsString())) {
                return a.getAsJsonObject();
            }
        }
        throw new AssertionError("no axis " + name + " in " + multiscales);
    }

    private static String stitchInfo(Path image) throws IOException {
        return Files.readString(image.resolveSibling(
                UtilityFunctions.imageStem(image.getFileName().toString()) + ".stitch-info.txt"));
    }
}
