package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.MicroManagerOutputMetadata;

/**
 * {@code <output stem>.mm-metadata.json}: the field set QPSC's removed "Stitch MicroManager Folder"
 * wrote, read from the first position's sidecar whatever order the folder lists them in, without
 * the operator's user and computer names, with each channel's own exposure.
 */
class MicroManagerOutputMetadataTest {

    private static final String SUMMARY_TAIL = ",\"ChNames\":[\"TRITC\",\"DAPI\",\"FITC\"],\"Channels\":3"
            + ",\"PixelType\":\"GRAY16\",\"Width\":2048,\"Height\":1536,\"z-step_um\":2.5"
            + ",\"StartTime\":\"2026-09-24 15:43:45.963 -0500\",\"MicroManagerVersion\":\"2.0.3 20260410\""
            + ",\"UserName\":\"someone\",\"ComputerName\":\"SCOPE-PC\""
            + ",\"StagePositions\":[{\"Label\":\"Pos-B\",\"GridRow\":0,\"GridCol\":1},"
            + "{\"Label\":\"Pos-A\",\"GridRow\":1,\"GridCol\":0},{\"Label\":\"Pos-C\",\"GridRow\":1,\"GridCol\":1}]";

    /** Exposure per channel at Pos-B, the first position; Pos-A, first by name, holds other values. */
    private static final double[] EXPOSURE_B = {200.0, 20.0, 100.0};

    private static String block(String file, double exposure, double z) {
        return "{\"FileName\":\"" + file
                + "\",\"PixelSizeUm\":0.325,\"PixelSizeAffine\":\"0.325;0.0;0.0;0.0;0.325;0.0\"" + ",\"ZPositionUm\":"
                + z + ",\"Exposure-ms\":" + exposure + ",\"BitDepth\":16}";
    }

    /** An OME-TIFF-per-position acquisition's sidecars (no images are needed to read them). */
    private static Path mmStack(Path tmp, String summaryExtra) throws IOException {
        Path acq = Files.createDirectories(tmp.resolve("Run_1"));
        for (String label : List.of("Pos-A", "Pos-B", "Pos-C")) {
            String file = "Run_1_MMStack_" + label + ".ome.tif";
            StringBuilder sb =
                    new StringBuilder("{\"Summary\":{\"Prefix\":\"Run_1\"" + SUMMARY_TAIL + summaryExtra + "}");
            for (int c = 0; c < 3; c++) {
                double exposure = label.equals("Pos-B") ? EXPOSURE_B[c] : 1.0 + c;
                sb.append(",\"FrameKey-0-").append(c).append("-0\":").append(block(file, exposure, 1641.5));
                sb.append(",\"FrameKey-0-").append(c).append("-1\":").append(block(file, 999.0, 1644.0));
            }
            sb.append('}');
            Files.writeString(acq.resolve("Run_1_MMStack_" + label + "_metadata.txt"), sb.toString());
        }
        Files.writeString(acq.resolve("comments.txt"), "{\"Summary\":\"liver, 20x\"}", StandardCharsets.UTF_8);
        return acq;
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    void theMergedImageGetsTheFullFieldSetFromTheFirstPosition(@TempDir Path tmp) throws IOException {
        Path acq = mmStack(tmp, "");
        Path image = Files.createDirectories(tmp.resolve("out")).resolve("Run_1_merged.ome.tif");

        Path written = MicroManagerOutputMetadata.write(image, acq, List.of(0, 1, 2));
        assertEquals(tmp.resolve("out/Run_1_merged.mm-metadata.json"), written);
        String text = Files.readString(written, StandardCharsets.UTF_8);
        assertTrue(text.contains("\n  \"source_folder\""), "pretty-printed:\n" + text);
        JsonObject json = read(written);

        assertEquals(
                List.of(
                        "source_folder",
                        "source_folder_name",
                        "stitched_output",
                        "pixel_size_um",
                        "pixel_size_affine",
                        "z_position_um",
                        "z_step_um",
                        "exposure_ms",
                        "bit_depth",
                        "pixel_type",
                        "tile_width",
                        "tile_height",
                        "channels",
                        "start_time",
                        "micro_manager_version",
                        "tile_count",
                        "grid_rows",
                        "grid_cols",
                        "comments_txt"),
                new ArrayList<>(json.keySet()));
        assertEquals(
                acq.toAbsolutePath().normalize().toString(),
                json.get("source_folder").getAsString());
        assertEquals("Run_1", json.get("source_folder_name").getAsString());
        assertEquals("Run_1_merged.ome.tif", json.get("stitched_output").getAsString());
        assertEquals(0.325, json.get("pixel_size_um").getAsDouble());
        assertEquals(
                "0.325;0.0;0.0;0.0;0.325;0.0", json.get("pixel_size_affine").getAsString());
        assertEquals(1641.5, json.get("z_position_um").getAsDouble(), "first slice");
        assertEquals(2.5, json.get("z_step_um").getAsDouble());
        JsonObject exposure = json.getAsJsonObject("exposure_ms");
        assertEquals(List.of("TRITC", "DAPI", "FITC"), new ArrayList<>(exposure.keySet()));
        for (int c = 0; c < 3; c++) {
            assertEquals(
                    EXPOSURE_B[c],
                    exposure.get(List.of("TRITC", "DAPI", "FITC").get(c)).getAsDouble(),
                    "position B (the first), not A (first by name)");
        }
        assertEquals(16, json.get("bit_depth").getAsInt());
        assertEquals("GRAY16", json.get("pixel_type").getAsString());
        assertEquals(2048, json.get("tile_width").getAsInt());
        assertEquals(1536, json.get("tile_height").getAsInt());
        assertEquals("[\"TRITC\",\"DAPI\",\"FITC\"]", json.get("channels").toString());
        assertEquals("2026-09-24 15:43:45.963 -0500", json.get("start_time").getAsString());
        assertEquals("2.0.3 20260410", json.get("micro_manager_version").getAsString());
        assertEquals(3, json.get("tile_count").getAsInt());
        assertEquals(2, json.get("grid_rows").getAsInt());
        assertEquals(2, json.get("grid_cols").getAsInt());
        assertEquals("{\"Summary\":\"liver, 20x\"}", json.get("comments_txt").getAsString());
        assertFalse(text.contains("someone"), "no user name");
        assertFalse(text.contains("SCOPE-PC"), "no computer name");
    }

    @Test
    void aChannelImageGetsItsOwnChannelAndExposure(@TempDir Path tmp) throws IOException {
        Path acq = mmStack(tmp, "");
        Path image = tmp.resolve("DAPI.ome.tif");
        JsonObject json = read(MicroManagerOutputMetadata.write(image, acq, List.of(1)));
        assertEquals("[\"DAPI\"]", json.get("channels").toString());
        assertEquals(20.0, json.get("exposure_ms").getAsDouble());
        assertEquals("DAPI.ome.tif", json.get("stitched_output").getAsString());
    }

    @Test
    void missingValuesAreLeftOut(@TempDir Path tmp) throws IOException {
        Path acq = Files.createDirectories(tmp.resolve("bare"));
        Files.writeString(
                acq.resolve("bare_MMStack_Pos0_metadata.txt"),
                "{\"Summary\":{\"ChNames\":[\"SHG\"]},\"FrameKey-0-0-0\":{\"PixelSizeUm\":0.5}}");
        JsonObject json = read(MicroManagerOutputMetadata.write(tmp.resolve("bare.ome.tif"), acq, List.of(0)));
        assertEquals(
                List.of("source_folder", "source_folder_name", "stitched_output", "pixel_size_um", "channels"),
                new ArrayList<>(json.keySet()));
    }

    @Test
    void aFolderWithoutMicroManagerMetadataGetsNoFile(@TempDir Path tmp) throws IOException {
        Path image = tmp.resolve("x.ome.tif");
        assertNull(MicroManagerOutputMetadata.write(image, Files.createDirectories(tmp.resolve("empty")), List.of(0)));
        assertFalse(Files.exists(MicroManagerOutputMetadata.pathFor(image)));
    }

    /** A single-plane series: the first position's folder, and the block of each channel's first plane. */
    @Test
    void aSinglePlaneSeriesIsReadFromItsFirstPositionFolder(@TempDir Path tmp) throws IOException {
        Path acq = Files.createDirectories(tmp.resolve("SHG_1"));
        for (String label : List.of("Pos-A", "Pos-B", "Pos-C")) {
            Path dir = Files.createDirectories(acq.resolve(label));
            StringBuilder sb = new StringBuilder("{\"Summary\":{\"Prefix\":\"SHG_1\"" + SUMMARY_TAIL + "}");
            for (int c = 0; c < 3; c++) {
                for (int t = 0; t < 2; t++) {
                    String name = String.format("img_channel%03d_position000_time%09d_z000.tif", c, t);
                    double exposure = label.equals("Pos-B") && t == 0 ? EXPOSURE_B[c] : 7.0;
                    sb.append(",\"Metadata-")
                            .append(label)
                            .append('/')
                            .append(name)
                            .append("\":")
                            .append(block(label + "/" + name, exposure, 10.0));
                }
            }
            sb.append('}');
            Files.writeString(dir.resolve("metadata.txt"), sb.toString());
        }
        JsonObject json = read(MicroManagerOutputMetadata.write(tmp.resolve("FITC.ome.tif"), acq, List.of(2)));
        assertEquals(100.0, json.get("exposure_ms").getAsDouble());
        assertEquals("[\"FITC\"]", json.get("channels").toString());
    }

    /** A preprocessed copy names the acquisition it was made from, and itself. */
    @Test
    void aPreprocessedCopyPointsBackToItsSource(@TempDir Path tmp) throws IOException {
        Path original = Files.createDirectories(tmp.resolve("Run_1_original"));
        Files.writeString(original.resolve("comments.txt"), "from the source");
        String source = original.toString().replace("\\", "\\\\");
        Path copy = mmStack(tmp, ",\"Preprocessing\":{\"Source\":\"" + source + "\"}");
        JsonObject json = read(MicroManagerOutputMetadata.write(tmp.resolve("m.ome.tif"), copy, List.of(0, 1, 2)));
        assertEquals(original.toString(), json.get("source_folder").getAsString());
        assertEquals("Run_1_original", json.get("source_folder_name").getAsString());
        assertEquals(
                copy.toAbsolutePath().normalize().toString(),
                json.get("preprocessed_copy").getAsString());
        assertEquals("from the source", json.get("comments_txt").getAsString());
    }

    @Test
    void theFileFollowsItsImage(@TempDir Path tmp) throws IOException {
        Path acq = mmStack(tmp, "");
        Path image = tmp.resolve("DAPI.ome.tif");
        assertNotNull(MicroManagerOutputMetadata.write(image, acq, List.of(1)));
        Path moved = Files.createDirectories(tmp.resolve("Run_1_channels")).resolve("DAPI.ome.tif");
        MicroManagerOutputMetadata.moveWith(image, moved);
        assertTrue(Files.exists(tmp.resolve("Run_1_channels/DAPI.mm-metadata.json")));
        assertFalse(Files.exists(tmp.resolve("DAPI.mm-metadata.json")));
    }
}
