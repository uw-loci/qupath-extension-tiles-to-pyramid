package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.basicstitching.MicroManagerFixture.Layout;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

/**
 * A three-channel Micro-Manager acquisition with z-slices and time points, in both layouts, stitched
 * through the dialog's route: the optional preprocessing, the MicroManager method, then the channel
 * merge. Every channel must come out as its own channel with the right values in every plane at
 * every pyramid level, whatever combination of averaging and projection was asked for.
 *
 * <p>Until 2026-11, a single-plane TIFF series stacked every channel into one output (the last
 * file drawn won each tile), and an OME-TIFF per position with planes left over after
 * preprocessing stitched page 0 only; in both cases the merge was then skipped without a word.
 */
class MicroManagerCztStitchTest {

    private static final int CHANNELS = 3;

    /** Options as the dialog builds them: average time points, max-project z-slices. */
    enum Opt {
        NONE(false, false),
        AVERAGE(true, false),
        PROJECT(false, true),
        AVERAGE_PROJECT(true, true);

        final boolean average;
        final boolean project;

        Opt(boolean average, boolean project) {
            this.average = average;
            this.project = project;
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        int[][] shapes = {{1, 1}, {3, 1}, {1, 2}, {3, 2}};
        for (Layout layout : Layout.values()) {
            for (int[] zt : shapes) {
                for (Opt opt : Opt.values()) {
                    // NDTiff is stitched as it is: the preprocessing copy writes the other layouts only.
                    if (layout == Layout.NDTIFF && opt != Opt.NONE) continue;
                    out.add(Arguments.of(layout, zt[0], zt[1], opt));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} C3 Z{1} T{2} {3}")
    @MethodSource("cases")
    void everyChannelSurvivesStitchAndMerge(Layout layout, int slices, int frames, Opt opt, @TempDir Path tmp)
            throws Exception {
        stitchMergeAndCheck(
                MicroManagerFixture.of(layout, CHANNELS, slices, frames).tile(540), opt, tmp);
    }

    /**
     * A position file Micro-Manager continued past 4 GB holds the rest of that position's planes. It
     * must add them to the position, whether the pages carry the tag or only the sidecar's frame keys
     * say what they are (continuing the first file's page order).
     */
    @ParameterizedTest(name = "tags {0}, {1}")
    @MethodSource("rolloverCases")
    void aFileContinuedPast4GbAddsItsPlanesToThePosition(boolean tags, Opt opt, @TempDir Path tmp) throws Exception {
        MicroManagerFixture f =
                MicroManagerFixture.of(Layout.MMSTACK, CHANNELS, 3, 2).tile(540).rolloverAfter(10);
        if (!tags) f.withoutTags();
        stitchMergeAndCheck(f, opt, tmp);
    }

    static Stream<Arguments> rolloverCases() {
        return Stream.of(
                Arguments.of(true, Opt.NONE),
                Arguments.of(false, Opt.NONE),
                Arguments.of(true, Opt.AVERAGE_PROJECT),
                Arguments.of(false, Opt.AVERAGE_PROJECT));
    }

    /** Micro-Manager's other page order (z fastest), with and without the tag. */
    @ParameterizedTest(name = "tags {0}")
    @MethodSource("tagCases")
    void zFastestPageOrderIsReadRight(boolean tags, @TempDir Path tmp) throws Exception {
        MicroManagerFixture f =
                MicroManagerFixture.of(Layout.MMSTACK, CHANNELS, 3, 2).tile(540).zFastest();
        if (!tags) f.withoutTags();
        stitchMergeAndCheck(f, Opt.NONE, tmp);
    }

    static Stream<Arguments> tagCases() {
        return Stream.of(Arguments.of(true), Arguments.of(false));
    }

    private static void stitchMergeAndCheck(MicroManagerFixture fixture, Opt opt, Path tmp) throws Exception {
        int slices = fixture.slices;
        int frames = fixture.frames;
        Path src = fixture.write(tmp);
        assertEquals(CHANNELS, MicroManagerMetadataStrategy.countChannels(src.toFile()), "the dialog offers merge");

        Path folder = src;
        if (opt != Opt.NONE) {
            var options = new MicroManagerPreprocessor.Options(opt.average, null, opt.project);
            folder = MicroManagerPreprocessor.run(src, MicroManagerPreprocessor.defaultOutput(src, options), options)
                    .output();
        }

        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingConfig cfg = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                folder.toString(),
                out.toString(),
                "LZW",
                MicroManagerFixture.PX_UM,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        cfg.setOverlapBlend(OverlapBlend.LAST_WINS);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
        assertEquals(List.of(), result.warnings(), "nothing left out or read in part");
        assertEquals(CHANNELS, result.outputs().size(), "one output per channel: " + result.outputs());

        int zOut = opt.project ? 1 : slices;
        // Projection without averaging projects each time point and keeps it (before 2026-11 it kept
        // only the first).
        int tOut = opt.average ? 1 : frames;

        // Merge as the dialog does: outputs in acquisition order, named by file stem.
        assertTrue(result.acquisitionOrder(), "outputs follow Summary.ChNames");
        List<String> ordered = result.outputs();
        List<String> names = ordered.stream()
                .map(p -> Path.of(p).getFileName().toString().replace(".ome.tif", ""))
                .toList();
        assertEquals(List.of("DAPI", "FITC", "TRITC"), names);
        String merged = ChannelMerger.merge(
                ordered, names, out.toString(), "MDA_region_merged", "LZW", StitchingConfig.OutputFormat.OME_TIFF);
        assertNotNull(merged, "merge");

        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(Path.of(merged).toUri())) {
            assertEquals(fixture.mosaic(), server.getWidth());
            assertEquals(fixture.mosaic(), server.getHeight());
            assertEquals(CHANNELS, server.nChannels());
            assertEquals(zOut, server.nZSlices(), "SizeZ");
            assertEquals(tOut, server.nTimepoints(), "SizeT");
            assertTrue(server.nResolutions() >= 2, "a zoomed-out level to check");
            for (int c = 0; c < CHANNELS; c++) {
                assertEquals(MicroManagerFixture.NAMES[c], server.getChannel(c).getName());
            }
            for (int level = 0; level < server.nResolutions(); level++) {
                double d = server.getDownsampleForResolution(level);
                for (int t = 0; t < tOut; t++) {
                    for (int z = 0; z < zOut; z++) {
                        Raster r = server.readRegion(RegionRequest.createInstance(
                                        server.getPath(), d, 0, 0, server.getWidth(), server.getHeight(), z, t))
                                .getRaster();
                        for (int c = 0; c < CHANNELS; c++) {
                            int want = expected(c, z, t, slices, frames, opt);
                            assertEquals(
                                    want,
                                    nonZeroMean(r, c),
                                    1e-9,
                                    "level " + level + " c" + c + " z" + z + " t" + t + " mean");
                            if (level == 0) {
                                // One pixel well inside each tile: a stacked channel shows here even
                                // when the means happen to agree.
                                int far = fixture.mosaic() - 100;
                                int[][] inside = {{100, 100}, {far, 100}, {100, far}, {far, far}};
                                for (int[] xy : inside) {
                                    assertEquals(
                                            want,
                                            r.getSample(xy[0], xy[1], c),
                                            "c" + c + " z" + z + " t" + t + " at " + xy[0] + "," + xy[1]);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The value a plane of the output must hold, given what was asked for. */
    private static int expected(int c, int z, int t, int slices, int frames, Opt opt) {
        // Averaging takes the mean over the time points; otherwise each is kept.
        int zSrc = opt.project ? slices - 1 : z;
        int base = 1000 * (c + 1) + 100 * zSrc + 1;
        if (opt.average) {
            return base + 10 * (frames - 1) / 2;
        }
        return base + 10 * t;
    }

    private static double nonZeroMean(Raster r, int band) {
        int[] px = r.getSamples(0, 0, r.getWidth(), r.getHeight(), band, (int[]) null);
        double sum = 0;
        long n = 0;
        for (int v : px) {
            if (v != 0) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }
}
