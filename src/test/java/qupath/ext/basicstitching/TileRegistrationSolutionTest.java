package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.registration.TileRegistrationSolution.SolutionHeader;

/**
 * Tests for the registration solution file -- the artifact that carries one solve across every
 * angle/channel subdirectory of an acquisition.
 *
 * <p>The compatibility tests matter most. Corrections are pixel-space, so applying a solution to a
 * run with a different pixel size or flip convention would shift tiles by a wrong-but-plausible
 * amount -- a silent corruption. The header exists to make that impossible.
 */
class TileRegistrationSolutionTest {

    @TempDir
    Path tempDir;

    private static SolutionHeader header() {
        return new SolutionHeader("0", 0.4988, 1.0, false, false, 1400, 1000, 0.101, 0.098, 172, 180, 0);
    }

    private static TileRegistrationSolution sample() {
        Map<String, double[]> deltas = new LinkedHashMap<>();
        deltas.put("1.tif", new double[] {0.412, -1.203});
        deltas.put("2.tif", new double[] {0.388, -1.190});
        deltas.put("10.tif", new double[] {-2.5, 3.75});
        return new TileRegistrationSolution(header(), deltas);
    }

    @Test
    void roundTripPreservesHeaderAndDeltas() throws IOException {
        Path file = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);
        TileRegistrationSolution written = sample();
        written.write(file);

        TileRegistrationSolution read = TileRegistrationSolution.read(file);

        assertEquals("0", read.header().reference());
        assertEquals(0.4988, read.header().pixelSizeUm(), 1e-6);
        assertEquals(1.0, read.header().baseDownsample(), 1e-6);
        assertFalse(read.header().flipX());
        assertFalse(read.header().flipY());
        assertEquals(1400, read.header().tileWidthPx());
        assertEquals(1000, read.header().tileHeightPx());
        assertEquals(0.101, read.header().overlapFracX(), 1e-4);
        assertEquals(0.098, read.header().overlapFracY(), 1e-4);
        assertEquals(172, read.header().edgesAccepted());
        assertEquals(180, read.header().edgesTotal());
        assertEquals(0, read.header().tilesClamped());

        assertEquals(3, read.deltaPxByFilename().size());
        assertArrayEquals(new double[] {0.412, -1.203}, read.deltaFor("1.tif"), 1e-3);
        assertArrayEquals(new double[] {-2.5, 3.75}, read.deltaFor("10.tif"), 1e-3);
    }

    @Test
    void roundTripSurvivesFlipFlagsSetTrue() throws IOException {
        Path file = tempDir.resolve("flipped.txt");
        SolutionHeader flipped = new SolutionHeader("45", 0.25, 2.0, true, true, 512, 512, 0.1, 0.1, 4, 4, 1);
        new TileRegistrationSolution(flipped, Map.of("1.tif", new double[] {1, 2})).write(file);

        TileRegistrationSolution read = TileRegistrationSolution.read(file);

        assertTrue(read.header().flipX());
        assertTrue(read.header().flipY());
        assertEquals(2.0, read.header().baseDownsample(), 1e-6);
        assertEquals("45", read.header().reference());
    }

    @Test
    void unknownTileYieldsZeroDelta() {
        assertArrayEquals(new double[] {0, 0}, sample().deltaFor("does-not-exist.tif"), 1e-9);
    }

    @Test
    void fileIsAsciiOnly() throws IOException {
        // Production runs on Windows with cp1252; any non-ASCII byte in a file the stitcher parses
        // has hung workflows before.
        Path file = tempDir.resolve("ascii.txt");
        sample().write(file);
        byte[] bytes = Files.readAllBytes(file);
        for (byte b : bytes) {
            assertTrue(b >= 0, "non-ASCII byte in solution file");
        }
    }

    @Test
    void writeUsesDotDecimalSeparatorRegardlessOfLocale() throws IOException {
        // A comma decimal separator would silently corrupt every coordinate on a de/fr JVM.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            Path file = tempDir.resolve("locale.txt");
            sample().write(file);
            String text = Files.readString(file, StandardCharsets.US_ASCII);
            assertTrue(text.contains("0.412"), "expected dot decimal separator, got:\n" + text);
            assertTrue(TileRegistrationSolution.read(file).deltaFor("1.tif")[0] > 0.4);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void deltasAreSortedForDiffability() throws IOException {
        Path file = tempDir.resolve("sorted.txt");
        sample().write(file);
        List<String> dataLines = Files.readAllLines(file).stream()
                .filter(l -> !l.startsWith("#"))
                .toList();
        assertEquals(List.of("1.tif; 0.412; -1.203", "10.tif; -2.500; 3.750", "2.tif; 0.388; -1.190"), dataLines);
    }

    @Test
    void nonSolutionFileIsRejected() throws IOException {
        Path file = tempDir.resolve("TileConfiguration.txt");
        Files.write(file, List.of("dim = 2", "1.tif; ; (0.0, 0.0)"), StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> TileRegistrationSolution.read(file));
    }

    @Test
    void matchingRunIsCompatible() {
        assertNull(sample().incompatibilityReason(0.4988, 1.0, false, false, 1400, 1000));
        assertTrue(sample().isCompatibleWith(0.4988, 1.0, false, false, 1400, 1000));
    }

    @Test
    void mismatchedPixelSizeIsRefused() {
        String why = sample().incompatibilityReason(0.25, 1.0, false, false, 1400, 1000);
        assertNotNull(why, "a different pixel size must be refused, not silently applied");
        assertTrue(why.contains("pixel size"), why);
        assertFalse(sample().isCompatibleWith(0.25, 1.0, false, false, 1400, 1000));
    }

    @Test
    void mismatchedDownsampleIsRefused() {
        String why = sample().incompatibilityReason(0.4988, 4.0, false, false, 1400, 1000);
        assertNotNull(why);
        assertTrue(why.contains("downsample"), why);
    }

    @Test
    void mismatchedFlipIsRefused() {
        // The failure this guards is the nastiest of the set: identical geometry, corrections
        // applied with inverted sign, tiles pushed apart by exactly twice the true error.
        String why = sample().incompatibilityReason(0.4988, 1.0, true, false, 1400, 1000);
        assertNotNull(why, "an inverted flip convention must be refused");
        assertTrue(why.contains("flip"), why);
    }

    @Test
    void mismatchedTileSizeIsRefused() {
        String why = sample().incompatibilityReason(0.4988, 1.0, false, false, 512, 512);
        assertNotNull(why);
        assertTrue(why.contains("tile size"), why);
    }
}
