package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.direct.MosaicGeometry;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.progress.StitchEta;
import qupath.ext.basicstitching.progress.StitchProgress;
import qupath.ext.basicstitching.progress.StitchProgressReporter;
import qupath.ext.basicstitching.stitching.FileNameStitchingStrategy;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.StitchPreflight;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.regions.ImageRegion;

/**
 * A batch (several sub-folders, one image each, one set of settings) must survive one bad folder,
 * say what went wrong with it, and say so before anything is written where it can.
 */
class BatchRobustnessTest {

    private static final int TILE = 64;
    private static final double PIXEL_UM = 0.5;
    private static final String TILECONFIG = "Coordinates in TileConfiguration.txt file";

    /** A 2 x 2 grid with 10% overlap; {@code spread} multiplies the step (2 = a pixel size half the true one). */
    private static void writeGroup(Path dir, double spread) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("dim = 2\n");
        int idx = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                String name = String.format(Locale.ROOT, "tile_%03d.tif", idx);
                BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        img.getRaster().setSample(x, y, 0, 40 + idx * 20);
                    }
                }
                assertTrue(ImageIO.write(img, "TIFF", dir.resolve(name).toFile()));
                double step = TILE * 0.9 * PIXEL_UM * spread;
                sb.append(String.format(Locale.ROOT, "%s; ; (%.2f, %.2f)%n", name, col * step, row * step));
                idx++;
            }
        }
        Files.writeString(dir.resolve("TileConfiguration.txt"), sb.toString());
    }

    private static StitchingConfig config(Path root, Path out, String type) {
        return new StitchingConfig(
                type,
                root.toString(),
                out.toString(),
                "UNCOMPRESSED",
                PIXEL_UM,
                1.0,
                "*",
                0.0,
                StitchingConfig.OutputFormat.OME_TIFF);
    }

    @Test
    void aTileUnreadableWhileWritingIsAHoleTheResultNames(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("batch");
        for (String g : List.of("A", "B", "C")) {
            writeGroup(root.resolve(g), 1.0);
        }
        // B's last tile keeps its header but loses its pixels: found and sized, then unreadable.
        truncate(root.resolve("B").resolve("tile_003.tif"));

        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config(root, out, TILECONFIG));

        assertEquals(3, result.outputs().size(), "every image is written: " + result.outputs());
        assertTrue(
                result.warnings().stream()
                        .anyMatch(w -> w.equals("B: 1 tile (tile_003.tif) could not be read while writing, so the"
                                + " image is blank there.")),
                result.warnings().toString());
    }

    @Test
    void oneImageThatCannotBeWrittenFailsAloneWithItsReason(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("batch");
        for (String g : List.of("A", "B", "C")) {
            writeGroup(root.resolve(g), 1.0);
        }
        // Every tile of B unreadable: the stitch of B cannot even read its first tile's header.
        for (int i = 0; i < 4; i++) {
            truncate(root.resolve("B").resolve(String.format(Locale.ROOT, "tile_%03d.tif", i)));
        }
        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config(root, out, TILECONFIG));

        List<String> names = result.outputs().stream()
                .map(o -> Path.of(o).getFileName().toString())
                .toList();
        assertTrue(!names.contains("B.ome.tif"), names.toString());
        assertTrue(names.contains("A.ome.tif") && names.contains("C.ome.tif"), "A and C are written: " + names);
        if (result.failedSubdirs().contains("B")) {
            String reason = result.failureReasons().get(result.failedSubdirs().indexOf("B"));
            assertTrue(reason.startsWith("B: ") && !reason.endsWith("no file was written"), reason);
        } else {
            // The tile search could not size any of B's tiles, so B never reached the writer.
            assertTrue(
                    result.warnings().stream().anyMatch(w -> w.startsWith("B: ")),
                    result.warnings().toString());
        }
    }

    /** Keep a tile's header and drop its pixels. */
    private static void truncate(Path tile) throws IOException {
        byte[] bytes = Files.readAllBytes(tile);
        Files.write(tile, java.util.Arrays.copyOf(bytes, 400));
    }

    @Test
    void aFolderWithTiffsButNoTileConfigurationIsReported(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("batch");
        writeGroup(root.resolve("A"), 1.0);
        writeGroup(root.resolve("B"), 1.0);
        Files.delete(root.resolve("B").resolve("TileConfiguration.txt"));
        Files.createDirectories(root.resolve("notes")); // no TIFFs: not a tile folder, not reported

        TileConfigurationTxtStrategy strategy = new TileConfigurationTxtStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(root.toString(), PIXEL_UM, 1.0, "*");
        assertEquals(4, mappings.size());
        assertEquals(1, strategy.warnings().size(), strategy.warnings().toString());
        assertTrue(strategy.warnings().get(0).startsWith("B: has TIFF files but no TileConfiguration.txt"));
    }

    @Test
    void tilesListedButMissingAreReported(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("batch");
        writeGroup(root.resolve("A"), 1.0);
        Files.delete(root.resolve("A").resolve("tile_002.tif"));
        // A stitched output in the folder is not a tile and is not reported.
        Files.copy(root.resolve("A").resolve("tile_000.tif"), root.resolve("A").resolve("A.ome.tif"));

        TileConfigurationTxtStrategy strategy = new TileConfigurationTxtStrategy();
        assertEquals(
                3,
                strategy.prepareStitching(root.toString(), PIXEL_UM, 1.0, "*").size());
        assertEquals(
                List.of("A: 1 tile (tile_002.tif) listed in TileConfiguration.txt not found, so the image has holes"
                        + " there."),
                strategy.warnings());
        // The next call starts clean.
        Files.copy(root.resolve("A").resolve("tile_000.tif"), root.resolve("A").resolve("tile_002.tif"));
        strategy.prepareStitching(root.toString(), PIXEL_UM, 1.0, "*");
        assertTrue(strategy.warnings().isEmpty());
    }

    @Test
    void fileNameStrategyKeepsGoingPastAnUnreadableFolder(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("batch");
        for (String g : List.of("A", "B", "C")) {
            Path dir = Files.createDirectories(root.resolve(g));
            BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
            ImageIO.write(img, "TIFF", dir.resolve("tile [0,0].tif").toFile());
            ImageIO.write(img, "TIFF", dir.resolve("tile [29,0].tif").toFile());
        }
        Path b = root.resolve("B");
        try {
            Files.setPosixFilePermissions(b, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions here");
        }
        try {
            assumeTrue(!Files.isReadable(b), "running as a user who can read anything");
            FileNameStitchingStrategy strategy = new FileNameStitchingStrategy();
            List<TileMapping> mappings = strategy.prepareStitching(root.toString(), PIXEL_UM, 1.0, "*");
            assertEquals(
                    List.of("A", "A", "C", "C"),
                    mappings.stream().map(m -> m.subdirName).sorted().toList(),
                    "C is still found after B fails");
            assertEquals(1, strategy.warnings().size());
            assertTrue(
                    strategy.warnings().get(0).startsWith("B: could not be read"),
                    strategy.warnings().toString());
        } finally {
            Files.setPosixFilePermissions(b, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void geometryWarningsNameTheirImageAndDoNotLeakIntoOthers(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("batch");
        writeGroup(root.resolve("A"), 1.0);
        writeGroup(root.resolve("B"), 2.0); // acquired as if at another pixel size: 80% gaps
        writeGroup(root.resolve("C"), 1.0);
        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingConfig cfg = config(root, out, TILECONFIG);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
        assertEquals(3, result.outputs().size());
        List<String> geometry = cfg.getGeometryWarnings();
        assertEquals(1, geometry.size(), geometry.toString());
        assertTrue(geometry.get(0).startsWith("B: Neighboring tiles are 80% of a tile apart"), geometry.get(0));
    }

    @Test
    void gapsAndOverlapsAreMeasuredFromNeighbors() {
        List<TileMapping> grid = grid(TILE * 9 / 10); // 10% overlap
        assertEquals(-0.1, medianGap(grid, true), 0.02);
        assertNull(MosaicGeometry.brief(grid, TILE / 10, TILE / 10, TILE, TILE));
        List<TileMapping> spread = grid(TILE * 18 / 10); // a pixel size half the true one
        assertEquals(0.8, medianGap(spread, true), 0.02);
        assertEquals(0.8, medianGap(spread, false), 0.02);
        assertEquals(
                "tiles 80% of a tile apart: pixel size too small?", MosaicGeometry.brief(spread, 0, 0, TILE, TILE));
        assertNotNull(MosaicGeometry.check(spread, 0, 0, TILE, TILE, PIXEL_UM));
        assertEquals(
                "tiles overlap 75%: pixel size too large?",
                MosaicGeometry.brief(grid(TILE / 4), TILE * 3 / 4, 0, TILE, TILE));
    }

    private static double medianGap(List<TileMapping> mappings, boolean axisX) {
        try {
            var m = MosaicGeometry.class.getDeclaredMethod("medianGapFraction", List.class, boolean.class);
            m.setAccessible(true);
            return (double) m.invoke(null, mappings, axisX);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<TileMapping> grid(int step) {
        List<TileMapping> out = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out.add(new TileMapping(
                        new File("t" + row + col + ".tif"),
                        ImageRegion.createInstance(col * step, row * step, TILE, TILE, 0, 0),
                        "g"));
            }
        }
        return out;
    }

    @Test
    void thePreRunCheckFlagsTheImageThatDiffers() throws Exception {
        Map<String, List<TileMapping>> groups = new LinkedHashMap<>();
        for (String g : List.of("DAPI", "FITC", "TRITC")) {
            groups.put(g, rename(grid(TILE * 9 / 10), g));
        }
        // Cy5: fewer tiles, another tile size, 8-bit where the others are 16-bit.
        List<TileMapping> odd = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            odd.add(new TileMapping(
                    new File("cy5_" + i + ".tif"),
                    ImageRegion.createInstance((i % 2) * 29, (i / 2) * 29, 32, 32, 0, 0),
                    "Cy5"));
        }
        groups.put("Cy5", odd);
        var summarize =
                StitchPreflight.class.getDeclaredMethod("summarize", Map.class, java.util.function.Function.class);
        summarize.setAccessible(true);
        java.util.function.Function<TileMapping, StitchPreflight.Pixels> pixels = m -> m.subdirName.equals("Cy5")
                ? new StitchPreflight.Pixels(8, 1, false)
                : new StitchPreflight.Pixels(16, 1, false);
        @SuppressWarnings("unchecked")
        List<StitchPreflight.Row> rows = (List<StitchPreflight.Row>) summarize.invoke(null, groups, pixels);

        assertEquals(4, rows.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(rows.get(i).flags().isEmpty(), rows.get(i).toString());
            assertEquals("64 x 64", rows.get(i).tileSize());
            assertEquals("16-bit, 1 channel", rows.get(i).pixels());
        }
        StitchPreflight.Row cy5 = rows.get(3);
        assertEquals(
                List.of(
                        "tile size differs (most: 64 x 64)",
                        "pixel type differs (most: 16-bit, 1 channel)",
                        "tile count differs (most: 9)"),
                cy5.flags());
        assertTrue(new StitchPreflight.Report(rows, List.of()).hasProblems());
    }

    private static List<TileMapping> rename(List<TileMapping> tiles, String group) {
        return tiles.stream()
                .map(t -> new TileMapping(new File(group + t.file.getName()), t.region, group))
                .toList();
    }

    @Test
    void theProgressLineCountsImagesAndFailures() throws Exception {
        var detail = StitchingWorkflow.class.getDeclaredMethod(
                "outputDetail", int.class, int.class, String.class, int.class);
        detail.setAccessible(true);
        assertEquals("DAPI", detail.invoke(null, 0, 1, "DAPI", 0), "one image: just its name");
        assertEquals("Image 3 of 12: DAPI", detail.invoke(null, 2, 12, "DAPI", 0));
        assertEquals("Image 4 of 12: FITC (1 failed so far)", detail.invoke(null, 3, 12, "FITC", 1));
    }

    @Test
    void progressFollowsTheWriteOrderNotThePlanOrder() {
        List<Double> fractions = new ArrayList<>();
        List<String> details = new ArrayList<>();
        StitchProgressReporter progress =
                new StitchProgressReporter("t", "UNCOMPRESSED", new StitchEta.Work(0, 0, 0), (StitchProgress p) -> {
                    if (p.phase() == qupath.ext.basicstitching.progress.StitchPhase.WRITE) {
                        fractions.add(p.phaseFraction());
                        details.add(p.detail());
                    }
                });
        Map<String, Long> planned = new LinkedHashMap<>();
        planned.put("small", 100L);
        planned.put("large", 300L);
        progress.planWrite(planned);
        progress.orderOutputs(List.of("large", "small"));
        progress.startOutput(1, "Image 2 of 2: small");
        assertEquals(0.75, fractions.get(fractions.size() - 1), 1e-9, "large, written first, is 3/4 of the work");
        assertEquals("Image 2 of 2: small", details.get(details.size() - 1));
    }
}
