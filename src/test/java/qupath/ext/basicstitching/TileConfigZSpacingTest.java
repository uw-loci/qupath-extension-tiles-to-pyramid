package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.AcquisitionRecord;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;

/**
 * The z spacing of a TileConfiguration.txt z-stack ({@code z{nn}/} folders): a TileConfiguration.txt
 * has no field for it, so it is the caller's value, else the z-step in the QPSC acquisition's
 * {@code acquisition_metadata.json}, else unknown and not written. Until 2026-11 such a stack was
 * written with 1 um whatever was acquired.
 */
class TileConfigZSpacingTest {

    private static final int TILE = 40;

    @Test
    void theAcquisitionRecordFillsAnUnknownSpacing(@TempDir Path tmp) throws Exception {
        Path root = acquisition(tmp, 3, 2.5);
        Path out = stitch(root, 0.0);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(3, server.nZSlices());
            assertTrue(server.getPixelCalibration().hasZSpacingMicrons());
            assertEquals(2.5, server.getPixelCalibration().getZSpacingMicrons(), 1e-9);
        }
        assertTrue(stitchInfo(out).contains("z spacing (um): 2.5 (from acquisition_metadata.json)"), stitchInfo(out));
    }

    @Test
    void aSpacingTheCallerGivesWins(@TempDir Path tmp) throws Exception {
        Path root = acquisition(tmp, 2, 2.5);
        Path out = stitch(root, 4.0);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(4.0, server.getPixelCalibration().getZSpacingMicrons(), 1e-9);
        }
        // The whole line: no "(from ...)" after it. The record has the platform's line endings.
        assertTrue(stitchInfo(out).lines().anyMatch(l -> l.strip().equals("z spacing (um): 4.0")), stitchInfo(out));
    }

    @Test
    void withNoRecordTheSpacingIsLeftUnset(@TempDir Path tmp) throws Exception {
        Path root = acquisition(tmp, 2, null);
        Path out = stitch(root, 0.0);
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(2, server.nZSlices());
            assertFalse(server.getPixelCalibration().hasZSpacingMicrons(), "no PhysicalSizeZ claimed");
        }
        assertTrue(stitchInfo(out).contains("z spacing (um): unknown (not written)"), stitchInfo(out));
    }

    @Test
    void theRecordIsFoundBesideOrAboveTheFolder(@TempDir Path tmp) throws Exception {
        Path root = acquisition(tmp, 2, 1.75);
        assertEquals(1.75, AcquisitionRecord.zStepUm(root));
        assertEquals(1.75, AcquisitionRecord.zStepUm(root.resolve("State0")), "a tile folder selected itself");
        Files.writeString(
                root.resolve(AcquisitionRecord.FILE_NAME),
                "{\"acquisition\":{\"z_stack\":{\"enabled\":false,\"z_step_um\":3.0}}}");
        assertNull(AcquisitionRecord.zStepUm(root), "a disabled z-stack records no spacing");
        Files.writeString(root.resolve(AcquisitionRecord.FILE_NAME), "{\"acquisition\":{\"pixel_size_um\":0.5}}");
        assertNull(AcquisitionRecord.zStepUm(root), "no z-stack");
    }

    @Test
    void theDialogCountsZFoldersWithoutReadingTiles(@TempDir Path tmp) throws Exception {
        Path root = acquisition(tmp, 3, null);
        assertEquals(3, TileConfigurationTxtStrategy.countZSlices(root, "State"));
        assertEquals(3, TileConfigurationTxtStrategy.countZSlices(root.resolve("State0"), ""));
        Path tz = Files.createDirectories(tmp.resolve("tz/State0"));
        for (int t = 0; t < 2; t++) {
            for (int z = 0; z < 4; z++) {
                Files.createDirectories(tz.resolve(String.format(Locale.ROOT, "t%02d/z%02d", t, z)));
            }
        }
        assertEquals(4, TileConfigurationTxtStrategy.countZSlices(tmp.resolve("tz"), "*"));
        Path flat = Files.createDirectories(tmp.resolve("flat/State0"));
        Files.writeString(flat.resolve("TileConfiguration.txt"), "dim = 2\n");
        assertEquals(0, TileConfigurationTxtStrategy.countZSlices(tmp.resolve("flat"), "State"));
    }

    @Test
    void anEmptyOrBadFieldMeansUnknown() {
        assertEquals(0.0, qupath.ext.basicstitching.functions.StitchingGUI.parseZSpacing(""));
        assertEquals(0.0, qupath.ext.basicstitching.functions.StitchingGUI.parseZSpacing("  "));
        assertEquals(0.0, qupath.ext.basicstitching.functions.StitchingGUI.parseZSpacing("abc"));
        assertEquals(0.0, qupath.ext.basicstitching.functions.StitchingGUI.parseZSpacing("-1"));
        assertEquals(0.8, qupath.ext.basicstitching.functions.StitchingGUI.parseZSpacing(" 0.8 "));
    }

    /** A QPSC-shaped acquisition: {@code State0/z00..} tiles, and a record when {@code zStep} is given. */
    private static Path acquisition(Path tmp, int slices, Double zStep) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("bounds"));
        Path group = Files.createDirectories(root.resolve("State0"));
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        for (int i = 0; i < 2; i++) {
            cfg.append(String.format(Locale.ROOT, "%d.tif; ; (%.1f, 0.0)%n", i, i * TILE * 0.9));
        }
        Files.writeString(group.resolve("TileConfiguration.txt"), cfg.toString());
        for (int z = 0; z < slices; z++) {
            Path zdir = Files.createDirectories(group.resolve(String.format(Locale.ROOT, "z%02d", z)));
            for (int i = 0; i < 2; i++) {
                BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
                byte[] px = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
                Arrays.fill(px, (byte) (20 + 10 * z));
                ImageIO.write(img, "TIFF", zdir.resolve(i + ".tif").toFile());
            }
        }
        if (zStep != null) {
            Files.writeString(
                    root.resolve(AcquisitionRecord.FILE_NAME),
                    "{\"schema_version\":\"1.0\",\"acquisition\":{\"pixel_size_um\":1.0,\"z_stack\":{\"enabled\":true,"
                            + "\"z_start_um\":-2.5,\"z_end_um\":2.5,\"z_step_um\":" + zStep
                            + ",\"projection\":\"None\"}}}");
        }
        return root;
    }

    private static Path stitch(Path root, double zSpacing) throws IOException {
        Path out = Files.createDirectories(root.resolveSibling("out"));
        StitchingConfig cfg = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                root.toString(),
                out.toString(),
                "LZW",
                1.0,
                1.0,
                "State",
                zSpacing,
                StitchingConfig.OutputFormat.OME_TIFF);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
        assertEquals(1, result.outputs().size(), result.toString());
        return Path.of(result.outputs().get(0));
    }

    private static String stitchInfo(Path image) throws IOException {
        return Files.readString(image.resolveSibling(
                UtilityFunctions.imageStem(image.getFileName().toString()) + ".stitch-info.txt"));
    }
}
