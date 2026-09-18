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
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.ChannelNormalizer;
import qupath.ext.basicstitching.registration.RegistrationChannel;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.registration.RegistrationRequest;
import qupath.ext.basicstitching.registration.RegistrationResult;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.registration.TileRegistrationEngine;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.TileRegistrationStep;
import qupath.lib.regions.ImageRegion;

/**
 * Registration across several co-captured channels: the normalized projection, the measured choice
 * of main channel, and the re-measurement of weak seams on the other channels.
 *
 * <p>Every channel in these grids shares one set of true tile positions (as co-captured channels
 * do) but carries independent content, so a solve on any of them -- or on a mix -- has the same
 * right answer.
 */
class ChannelRegistrationTest {

    @TempDir
    Path tempDir;

    private static final int TILE_W = 256;
    private static final int TILE_H = 192;
    private static final double OVERLAP = 0.15;

    private static RegistrationSettings settings() {
        return RegistrationSettings.defaults().withThreads(1);
    }

    private static StitchingConfig config(Path folder) {
        return new StitchingConfig(
                "Coordinates in TileConfiguration.txt",
                folder.toString(),
                folder.toString(),
                "LZW",
                0.5,
                1.0,
                "",
                1.0,
                1.0,
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
    }

    private static List<TileMapping> mappings(SyntheticGridFixture.Grid grid, String subdir) {
        List<TileMapping> out = new ArrayList<>();
        for (TileNode t : grid.nominal()) {
            out.add(new TileMapping(
                    t.file(),
                    ImageRegion.createInstance(
                            (int) Math.round(t.xPx()), (int) Math.round(t.yPx()), t.widthPx(), t.heightPx(), 0, 0),
                    subdir));
        }
        return out;
    }

    private static List<TileMapping> allMappings(Map<String, SyntheticGridFixture.Grid> channels) {
        List<TileMapping> out = new ArrayList<>();
        channels.forEach((name, grid) -> out.addAll(mappings(grid, name)));
        return out;
    }

    private static void assertRecovers(SyntheticGridFixture.Grid grid, Map<String, double[]> deltas, String what) {
        for (var entry : grid.trueJitterPx().entrySet()) {
            double[] expected = entry.getValue();
            double[] actual = deltas.getOrDefault(entry.getKey(), new double[] {0, 0});
            assertEquals(expected[0], actual[0], 0.75, what + ": X correction for " + entry.getKey());
            assertEquals(expected[1], actual[1], 0.75, what + ": Y correction for " + entry.getKey());
        }
    }

    // ------------------------------------------------------------ normalization

    @Test
    void normalizationScalesEachChannelByItsOwnRange() throws IOException {
        // Same content statistics, ten times the contrast: the brighter channel's scale must be about
        // a tenth of the dimmer one's, so neither dominates the mean.
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir,
                List.of("dim", "bright"),
                new double[] {0.05, 0.5},
                List.of(List.of(), List.of()),
                3,
                3,
                TILE_W,
                TILE_H,
                OVERLAP,
                2.0,
                11);
        List<TileNode> grid = ch.get("dim").nominal();
        List<ChannelNormalizer.Scale> scales = new ArrayList<>();
        ChannelNormalizer.normalize(
                List.of(
                        RegistrationRequest.channelOf("dim", ch.get("dim").nominal()),
                        RegistrationRequest.channelOf("bright", ch.get("bright").nominal())),
                grid,
                scales);

        double ratio = scales.get(0).scale() / scales.get(1).scale();
        assertEquals(10.0, ratio, 2.5, "dim/bright scale ratio should track the 10x contrast difference");
    }

    // ------------------------------------------------------------ projection

    @Test
    void projectionOfABrightAndADimChannelRecoversTheJitter() throws IOException {
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir,
                List.of("DAPI", "FITC"),
                new double[] {0.5, 0.02},
                List.of(List.of(), List.of()),
                3,
                3,
                TILE_W,
                TILE_H,
                OVERLAP,
                3.0,
                21);
        Path solution = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);
        StitchingConfig config = config(tempDir);
        config.setRegistrationMode(new RegistrationMode.Solve(
                solution, settings(), new RegistrationReference.Projection(List.of("DAPI", "FITC"))));

        assertTrue(TileRegistrationStep.solveOnly(allMappings(ch), config), "the projection solve must write a file");

        TileRegistrationSolution read = TileRegistrationSolution.read(solution);
        assertEquals("projection(DAPI+FITC)", read.header().reference());
        assertRecovers(ch.get("DAPI"), read.deltaPxByFilename(), "projection");

        String text = Files.readString(solution, StandardCharsets.US_ASCII);
        assertTrue(text.contains("projection channel: DAPI"), "normalization must be recorded:\n" + text);
        assertTrue(text.contains("projection channel: FITC"), "normalization must be recorded:\n" + text);
    }

    @Test
    void solveOnlyDoesNotNeedTheReferenceToBeStitched() throws IOException {
        // The QPSC path: solve with every channel in view, then apply to each channel separately.
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir,
                List.of("A", "B"),
                new double[] {0.5, 0.5},
                List.of(List.of(), List.of()),
                3,
                3,
                TILE_W,
                TILE_H,
                OVERLAP,
                3.0,
                5);
        Path solution = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);
        StitchingConfig solveConfig = config(tempDir);
        solveConfig.setRegistrationMode(
                new RegistrationMode.Solve(solution, settings(), new RegistrationReference.Single("B")));
        assertTrue(TileRegistrationStep.solveOnly(allMappings(ch), solveConfig));

        StitchingConfig applyConfig = config(tempDir);
        applyConfig.setRegistrationMode(new RegistrationMode.Apply(solution));
        List<TileMapping> before = mappings(ch.get("A"), "A");
        List<TileMapping> after = TileRegistrationStep.applyTo(before, applyConfig);
        for (int i = 0; i < before.size(); i++) {
            double[] truth = ch.get("A").trueJitterPx().get(before.get(i).file.getName());
            assertEquals(
                    truth[0], after.get(i).region.getX() - before.get(i).region.getX(), 1.0);
            assertEquals(
                    truth[1], after.get(i).region.getY() - before.get(i).region.getY(), 1.0);
        }
    }

    // ------------------------------------------------------------ automatic choice

    @Test
    void autoChoosesTheChannelThatMatchesNotTheFirstListed() throws IOException {
        // "empty" is blank almost everywhere; listing it first means a first-channel default would
        // pick it. Measuring seams must pick "full".
        List<Integer> mostlyBlank = List.of(0, 1, 2, 3, 4, 5, 6, 7);
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir,
                List.of("empty", "full"),
                new double[] {0.5, 0.5},
                List.of(mostlyBlank, List.of()),
                3,
                3,
                TILE_W,
                TILE_H,
                OVERLAP,
                3.0,
                8);
        RegistrationChannel chosen = TileRegistrationEngine.chooseReference(
                ch.get("empty").nominal(),
                List.of(
                        RegistrationRequest.channelOf("empty", ch.get("empty").nominal()),
                        RegistrationRequest.channelOf("full", ch.get("full").nominal())),
                settings());
        assertEquals("full", chosen.name());
    }

    // ------------------------------------------------------------ weak-seam rescue

    @Test
    void weakSeamsAreRescuedByAnotherChannel() throws IOException {
        // The primary channel has blank tiles, so every seam touching them is rejected. The alternate
        // channel has content everywhere; re-measuring only those seams on it must recover them.
        List<Integer> holes = List.of(4, 10);
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir,
                List.of("sparse", "full"),
                new double[] {0.5, 0.5},
                List.of(holes, List.of()),
                4,
                4,
                TILE_W,
                TILE_H,
                OVERLAP,
                3.0,
                13);
        List<TileNode> grid = ch.get("sparse").nominal();
        RegistrationChannel sparse = RegistrationRequest.channelOf("sparse", grid);
        RegistrationChannel full =
                RegistrationRequest.channelOf("full", ch.get("full").nominal());

        RegistrationResult alone = TileRegistrationEngine.register(
                new RegistrationRequest("sparse", grid, settings(), List.of(sparse), List.of()));
        RegistrationResult rescued = TileRegistrationEngine.register(
                new RegistrationRequest("sparse", grid, settings(), List.of(sparse), List.of(full)));

        assertTrue(
                rescued.edgesAccepted() > alone.edgesAccepted(),
                "rescue must accept seams the primary rejected: alone " + alone.summary() + " / rescued "
                        + rescued.summary());
        assertEquals(rescued.edgesTotal(), rescued.edgesAccepted(), "every seam has content on some channel");
        assertRecovers(ch.get("sparse"), rescued.deltaPxByFilename(), "rescued");
    }

    @Test
    void withoutAlternatesNothingIsReMeasured() throws IOException {
        // Selecting one channel (or a projection) is an explicit choice: no other channel may be
        // consulted behind the operator's back.
        Map<String, SyntheticGridFixture.Grid> ch = SyntheticGridFixture.writeChannels(
                tempDir,
                List.of("sparse", "full"),
                new double[] {0.5, 0.5},
                List.of(List.of(4), List.of()),
                3,
                3,
                TILE_W,
                TILE_H,
                OVERLAP,
                3.0,
                17);
        Path solution = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);
        StitchingConfig config = config(tempDir);
        config.setRegistrationMode(
                new RegistrationMode.Solve(solution, settings(), new RegistrationReference.Single("sparse")));
        assertTrue(TileRegistrationStep.solveOnly(allMappings(ch), config));

        TileRegistrationSolution read = TileRegistrationSolution.read(solution);
        assertEquals("sparse", read.header().reference());
        assertTrue(
                read.header().edgesAccepted() < read.header().edgesTotal(),
                "the blank centre tile's seams must stay rejected when only 'sparse' is allowed");
    }
}
