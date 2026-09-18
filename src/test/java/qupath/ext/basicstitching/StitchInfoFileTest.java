package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;

/** The record written beside every stitched image, and its survival through rename and merge. */
class StitchInfoFileTest {

    @TempDir
    Path tempDir;

    @Test
    void recordSitsBesideTheImageUnderItsStem() {
        assertEquals(
                tempDir.resolve("S_1_DAPI.stitch-info.txt"),
                StitchInfoFile.pathFor(tempDir.resolve("S_1_DAPI.ome.tif")));
        assertEquals(
                tempDir.resolve("S_1_DAPI.stitch-info.txt"),
                StitchInfoFile.pathFor(tempDir.resolve("S_1_DAPI.ome.zarr")));
    }

    @Test
    void recordFollowsARenameAndStaysAscii() throws IOException {
        Path before = tempDir.resolve("a.ome.tif");
        Path after = tempDir.resolve("b.ome.tif");
        StitchInfoFile.write(
                before, List.of(StitchInfoFile.Section.of("image", Map.of("note", "5 " + (char) 0xB5 + "m\nnext"))));
        StitchInfoFile.moveWith(before, after);

        assertFalse(Files.exists(StitchInfoFile.pathFor(before)));
        String text = Files.readString(StitchInfoFile.pathFor(after), StandardCharsets.US_ASCII);
        assertTrue(text.contains("note: 5 ?m next"), text);

        StitchInfoFile.append(after, List.of(StitchInfoFile.Section.of("acquisition", Map.of("sample", "S"))));
        // Lines, not a "\n" substring: the record uses the platform line separator (CRLF on Windows).
        List<String> lines = Files.readAllLines(StitchInfoFile.pathFor(after), StandardCharsets.US_ASCII);
        int heading = lines.indexOf("[acquisition]");
        assertTrue(heading >= 0 && "sample: S".equals(lines.get(heading + 1)), String.join("|", lines));
    }

    @Test
    void stitchAndMergeWriteFullRecords() throws IOException {
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir.resolve("tiles"),
                List.of("DAPI", "FITC"),
                new double[] {0.5, 0.5},
                List.of(List.of(), List.of()),
                2,
                2,
                256,
                192,
                0.15,
                3.0,
                3);
        Path out = Files.createDirectory(tempDir.resolve("out"));
        Path solution = tempDir.resolve("tiles").resolve(TileRegistrationSolution.DEFAULT_FILENAME);

        List<String> outputs = new ArrayList<>();
        for (String name : List.of("DAPI", "FITC")) {
            StitchingConfig config = new StitchingConfig(
                    "Coordinates in TileConfiguration.txt file",
                    tempDir.resolve("tiles").resolve(name).toString(),
                    out.toString(),
                    "LZW",
                    1.0,
                    1.0,
                    "",
                    1.0,
                    1.0,
                    1.0,
                    StitchingConfig.OutputFormat.OME_TIFF);
            config.setOutputFilename("S_" + name);
            config.setRegistrationMode(
                    name.equals("DAPI")
                            ? new RegistrationMode.Solve(
                                    solution,
                                    RegistrationSettings.defaults().withThreads(1),
                                    RegistrationReference.auto())
                            : new RegistrationMode.Apply(solution));
            String written = StitchingWorkflow.run(config);
            assertNotNull(written, name + " did not stitch");
            outputs.add(written);
        }

        String dapi = Files.readString(StitchInfoFile.pathFor(Path.of(outputs.get(0))), StandardCharsets.US_ASCII);
        for (String expected : List.of(
                "[image]",
                "[source tiles]",
                "tile positions: 4",
                "[stitching]",
                "pixel size (um): 1.0",
                "[registration]",
                "mode: solved on this stitch",
                "# reference:",
                "[software]",
                "tiles-to-pyramid:")) {
            assertTrue(dapi.contains(expected), "DAPI record lacks '" + expected + "':\n" + dapi);
        }
        String fitc = Files.readString(StitchInfoFile.pathFor(Path.of(outputs.get(1))), StandardCharsets.US_ASCII);
        assertTrue(fitc.contains("mode: reused a solve from a sibling angle/channel"), fitc);

        String merged = ChannelMerger.merge(
                outputs,
                List.of("DAPI", "FITC"),
                out.toString(),
                "S_merged",
                "LZW",
                StitchingConfig.OutputFormat.OME_TIFF);
        assertNotNull(merged);
        String mergedRecord = Files.readString(StitchInfoFile.pathFor(Path.of(merged)), StandardCharsets.US_ASCII);
        assertTrue(mergedRecord.contains("[channel merge]"), mergedRecord);
        assertTrue(mergedRecord.contains("[source channel 0 record]"), mergedRecord);
        assertTrue(mergedRecord.contains("    mode: reused a solve from a sibling angle/channel"), mergedRecord);
    }
}
