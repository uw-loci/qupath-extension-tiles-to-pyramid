package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.FlatfieldProfile;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.Options;

/**
 * Applying a profile written by {@code microscope_imageprocessing}: the fixture is the Python
 * {@code FlatfieldProfile.save} output, 12 x 8, flat-field 0.8 for x below 6 and 1.25 from there,
 * offset 100, channel 0.
 */
class FlatfieldPreprocessingTest {

    private static final int W = 12;
    private static final int H = 8;

    private static FlatfieldProfile profile() throws IOException, URISyntaxException {
        return FlatfieldProfile.load(Path.of(Objects.requireNonNull(
                                FlatfieldPreprocessingTest.class.getResource("/flatfield-profile/profile.yml"))
                        .toURI())
                .getParent());
    }

    private static int corrected(double raw, int x) {
        double flatfield = x < 6 ? 0.8 : 1.25;
        return (int) Math.floor((raw - 100) / flatfield + 100 + 0.5);
    }

    @Test
    void correctsOnlyTheProfilesChannelAfterAveraging(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        MicroManagerPreprocessorTest.writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, 2, 1, 3, true);
        Options options = new Options(true, profile(), false);

        Path out = MicroManagerPreprocessor.defaultOutput(src, options);
        assertEquals(tmp.resolve("shg_averaged_ff"), out);
        MicroManagerPreprocessor.run(src, out, options);

        List<int[]> pages = MicroManagerPreprocessorTest.readPages(out.resolve("acq_MMStack_Pos-1-000_000.ome.tif"));
        double mean = 1000 + 4 / 3.0; // frames add 0, 1 and 3
        for (int i = 0; i < W * H; i++) {
            assertEquals(corrected(mean, i % W), pages.get(0)[i], "channel 0, pixel " + i);
            assertEquals(1101, pages.get(1)[i], "channel 1 has no profile, pixel " + i);
        }
    }

    @Test
    void correctsBeforeTheMaxProjection(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("stack"));
        MicroManagerPreprocessorTest.writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, 1, 3, 1, true);
        Options options = new Options(false, profile(), true);

        Path out = MicroManagerPreprocessor.defaultOutput(src, options);
        assertEquals(tmp.resolve("stack_ff_mip"), out);
        MicroManagerPreprocessor.run(src, out, options);

        int[] page = MicroManagerPreprocessorTest.readPages(out.resolve("acq_MMStack_Pos-1-000_000.ome.tif"))
                .get(0);
        assertEquals(corrected(1040, 0), page[0], "z0 is brightest at x=0");
        assertEquals(corrected(1070, 7), page[7], "z1 is brightest at x=7");
    }

    @Test
    void aProfileOfAnotherSizeIsRefused(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        MicroManagerPreprocessorTest.writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, 1, 1, 1, true);
        FlatfieldProfile profile = profile();
        MicroManagerPreprocessorTest.setSize(24, 16);
        try {
            Path other = Files.createDirectories(tmp.resolve("big"));
            MicroManagerPreprocessorTest.writePosition(other, "Pos-1-000_000", 0, 0.0, 0.0, 1, 1, 1, true);
            IOException error = assertThrows(
                    IOException.class,
                    () -> MicroManagerPreprocessor.run(other, tmp.resolve("out"), new Options(false, profile, false)));
            assertTrue(error.getMessage().contains("another scan size"), error.getMessage());
        } finally {
            MicroManagerPreprocessorTest.setSize(W, H);
        }
    }

    @Test
    void aFolderWithoutAProfileSaysSo(@TempDir Path tmp) {
        IOException error = assertThrows(IOException.class, () -> FlatfieldProfile.load(tmp));
        assertTrue(error.getMessage().contains("not a flat-field profile"), error.getMessage());
    }

    @Test
    void theRecordNamesTheProfile() throws Exception {
        String method = new Options(true, profile(), true).describe();
        assertTrue(method.contains("top PMT, 10x"), method);
        assertTrue(method.indexOf("flat-field") < method.indexOf("maximum intensity"), method);
    }
}
