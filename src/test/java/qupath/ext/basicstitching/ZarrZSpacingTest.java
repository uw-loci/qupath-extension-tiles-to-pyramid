package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;

/**
 * The z spacing of an OME-Zarr output. NGFF 0.4 ("multiscales" metadata) requires a scale value for
 * every axis and, without a physical scale, the scale relative to the first resolution (1 for z);
 * the axis "unit" is only a SHOULD ("axes" metadata). So an unknown z spacing is written as scale 1
 * with no unit and no PhysicalSizeZ in the OME-XML; a known one as micrometers in both. QuPath reads
 * the physical sizes from {@code OME/METADATA.ome.xml} only (Bio-Formats' Zarr reader keeps the
 * multiscales scale as text), so each case is also read back through {@code ImageServers}.
 */
class ZarrZSpacingTest {

    private static final int TILE = 40;
    private static final double PIXEL = 0.5;

    @BeforeAll
    static void needsBlosc() {
        org.junit.jupiter.api.Assumptions.assumeTrue(BloscSupport.isAvailable(), BloscSupport.unavailableReason());
    }

    @Test
    void anUnknownSpacingIsScaleOneWithNoUnit(@TempDir Path tmp) throws Exception {
        Path out = stitch(acquisition(tmp, 3, 0), 0.0);
        JsonObject ms = multiscales(out);
        JsonObject z = axis(ms, "z");
        assertFalse(z.has("unit"), "no unit on an unknown z: " + z);
        assertEquals("micrometer", axis(ms, "x").get("unit").getAsString());
        for (var ds : ms.getAsJsonArray("datasets")) {
            JsonArray scale = ds.getAsJsonObject()
                    .getAsJsonArray("coordinateTransformations")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("scale");
            assertEquals(3, scale.size(), "one scale value per axis (z, y, x)");
            assertEquals(1.0, scale.get(0).getAsDouble(), "z scale is the slice step");
        }
        String xml = Files.readString(out.resolve("OME/METADATA.ome.xml"));
        assertFalse(xml.contains("PhysicalSizeZ"), xml);
        assertTrue(xml.contains("PhysicalSizeX=\"0.5\""), xml);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(3, server.nZSlices());
            assertFalse(server.getPixelCalibration().hasZSpacingMicrons(), "no z spacing claimed");
            assertEquals(PIXEL, server.getPixelCalibration().getPixelWidthMicrons(), 1e-9);
            for (int zi = 0; zi < 3; zi++) {
                BufferedImage plane = server.readRegion(1.0, 0, 0, TILE, TILE, zi, 0);
                assertEquals(value(zi), plane.getRaster().getSample(TILE / 2, TILE / 2, 0), "z " + zi);
            }
        }
        assertTrue(
                stitchInfo(out)
                        .lines()
                        .anyMatch(l -> l.strip()
                                .equals("z spacing (um): unknown (not written: OME-Zarr z scale 1 with no unit, "
                                        + "no PhysicalSizeZ)")),
                stitchInfo(out));
    }

    @Test
    void aKnownSpacingIsWrittenInMicrometers(@TempDir Path tmp) throws Exception {
        Path out = stitch(acquisition(tmp, 2, 0), 2.5);
        JsonObject ms = multiscales(out);
        assertEquals("micrometer", axis(ms, "z").get("unit").getAsString());
        JsonArray scale0 = ms.getAsJsonArray("datasets")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("coordinateTransformations")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("scale");
        assertEquals(2.5, scale0.get(0).getAsDouble());
        assertEquals(PIXEL, scale0.get(2).getAsDouble());
        assertTrue(Files.readString(out.resolve("OME/METADATA.ome.xml")).contains("PhysicalSizeZ=\"2.5\""));
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(2.5, server.getPixelCalibration().getZSpacingMicrons(), 1e-9);
            assertEquals(PIXEL, server.getPixelCalibration().getPixelWidthMicrons(), 1e-9);
        }
        assertTrue(stitchInfo(out).lines().anyMatch(l -> l.strip().equals("z spacing (um): 2.5")), stitchInfo(out));
    }

    @Test
    void sixteenBitValuesReadBackThroughTheOmeXml(@TempDir Path tmp) throws Exception {
        Path out = stitch(acquisition(tmp, 2, 1000), 0.0);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            for (int zi = 0; zi < 2; zi++) {
                BufferedImage plane = server.readRegion(1.0, 0, 0, TILE, TILE, zi, 0);
                assertEquals(1000 + value(zi), plane.getRaster().getSample(TILE / 2, TILE / 2, 0), "z " + zi);
            }
        }
    }

    @Test
    void aMergeKeepsAnUnknownSpacingUnitless(@TempDir Path tmp) throws Exception {
        Path a = stitch(acquisition(tmp.resolve("a"), 2, 0), 0.0);
        Path b = stitch(acquisition(tmp.resolve("b"), 2, 0), 0.0);
        String merged = ChannelMerger.merge(
                List.of(a.toString(), b.toString()),
                List.of("A", "B"),
                tmp.toString(),
                "merged",
                "zstd",
                StitchingConfig.OutputFormat.OME_ZARR);
        assertNotNull(merged);
        Path m = Path.of(merged);
        assertFalse(axis(multiscales(m), "z").has("unit"), "merged z has no unit");
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(m.toUri())) {
            assertEquals(2, server.nChannels());
            assertEquals(2, server.nZSlices());
            assertFalse(server.getPixelCalibration().hasZSpacingMicrons());
            assertEquals(PIXEL, server.getPixelCalibration().getPixelWidthMicrons(), 1e-9);
        }
    }

    @Test
    void aMergeKeepsAKnownSpacing(@TempDir Path tmp) throws Exception {
        Path a = stitch(acquisition(tmp.resolve("a"), 2, 0), 1.5);
        Path b = stitch(acquisition(tmp.resolve("b"), 2, 0), 1.5);
        String merged = ChannelMerger.merge(
                List.of(a.toString(), b.toString()),
                List.of("A", "B"),
                tmp.toString(),
                "merged",
                "zstd",
                StitchingConfig.OutputFormat.OME_ZARR);
        assertNotNull(merged);
        Path m = Path.of(merged);
        assertEquals("micrometer", axis(multiscales(m), "z").get("unit").getAsString());
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(m.toUri())) {
            assertEquals(1.5, server.getPixelCalibration().getZSpacingMicrons(), 1e-6);
        }
    }

    private static int value(int z) {
        return 20 + 10 * z;
    }

    /** {@code bounds/State0/z{nn}/} tiles with a TileConfiguration.txt; 16-bit when {@code offset > 0}. */
    private static Path acquisition(Path tmp, int slices, int offset) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("bounds"));
        Path group = Files.createDirectories(root.resolve("State0"));
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        for (int i = 0; i < 2; i++) {
            cfg.append(String.format("%d.tif; ; (%.1f, 0.0)%n", i, i * TILE * 0.9));
        }
        Files.writeString(group.resolve("TileConfiguration.txt"), cfg.toString());
        for (int z = 0; z < slices; z++) {
            Path zdir = Files.createDirectories(group.resolve(String.format("z%02d", z)));
            for (int i = 0; i < 2; i++) {
                BufferedImage img = new BufferedImage(
                        TILE, TILE, offset > 0 ? BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_BYTE_GRAY);
                var raster = img.getRaster();
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        raster.setSample(x, y, 0, offset + value(z));
                    }
                }
                ImageIO.write(img, "TIFF", zdir.resolve(i + ".tif").toFile());
            }
        }
        return root;
    }

    private static Path stitch(Path root, double zSpacing) throws IOException {
        Path out = Files.createDirectories(root.resolveSibling("out"));
        StitchingConfig cfg = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                root.toString(),
                out.toString(),
                "zstd",
                PIXEL,
                1.0,
                "State",
                zSpacing,
                StitchingConfig.OutputFormat.OME_ZARR);
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
