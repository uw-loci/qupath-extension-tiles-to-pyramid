package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.assertSameBytes;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.stagingFolders;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.ImageRegion;

/**
 * A real stitch (compositor over tile files, feathered seams, a missing tile, z and t) writes the
 * same pyramid with staged levels as without, and a stitch that cannot stage says so in its
 * result and its record.
 */
class StagedStitchTest {

    private static final int TILE = 700;
    private static final int STEP = 631;

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void reset() {
        ImageServerProvider.setCache(null, BufferedImage.class);
        System.clearProperty(LevelStaging.FREE_BYTES_PROPERTY);
    }

    @Test
    void stitchInZAndTEqualsTheUnstagedPath() throws Exception {
        List<TileMapping> mappings = new ArrayList<>();
        Random random = new Random(5);
        for (int t = 0; t < 2; t++) {
            for (int z = 0; z < 2; z++) {
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        if (row == 1 && col == 2) {
                            continue; // a hole: empty canvas inside the mosaic
                        }
                        File f = dir.resolve(String.format(Locale.ROOT, "t%d_z%d_%d_%d.tif", t, z, row, col))
                                .toFile();
                        writeTile(f, random, 1000 * (1 + z + 2 * t));
                        mappings.add(new TileMapping(
                                f, ImageRegion.createInstance(col * STEP, row * STEP, TILE, TILE, z, t), "s"));
                    }
                }
            }
        }
        StitchingConfig config = config(dir.toString());
        config.setOverlapBlend(OverlapBlend.LINEAR_FEATHER);
        Path out = Files.createDirectory(dir.resolve("out"));

        String staged = DirectTileStitcher.stitch(mappings, out.toString(), "staged", config, null);
        System.setProperty(LevelStaging.FREE_BYTES_PROPERTY, "0");
        String unstaged = DirectTileStitcher.stitch(mappings, out.toString(), "unstaged", config, null);
        assertNotNull(staged);
        assertNotNull(unstaged);
        assertTrue(config.getOutputWarnings(staged).isEmpty(), String.valueOf(config.getOutputWarnings(staged)));
        assertTrue(
                config.getOutputWarnings(unstaged).get(0).startsWith("Not enough free disk space"),
                String.valueOf(config.getOutputWarnings(unstaged)));
        assertSameBytes(Path.of(staged), Path.of(unstaged));
        assertTrue(stagingFolders(out).isEmpty());
    }

    @Test
    void aStitchThatCannotStageSaysSoInItsResultAndRecord() throws Exception {
        Path tiles = Files.createDirectory(dir.resolve("tiles"));
        List<String> lines = new ArrayList<>(List.of("dim = 2"));
        Random random = new Random(9);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                String name = "tile_" + row + "_" + col + ".tif";
                writeTile(tiles.resolve(name).toFile(), random, 2000);
                lines.add(String.format(Locale.ROOT, "%s; ; (%.3f, %.3f)", name, col * 540.0, row * 540.0));
            }
        }
        // Tiles of 600 at a step of 540: a 1140 px mosaic, so two pyramid levels and one staged.
        Files.write(tiles.resolve("TileConfiguration.txt"), lines, StandardCharsets.US_ASCII);
        Path out = Files.createDirectory(dir.resolve("out"));
        System.setProperty(LevelStaging.FREE_BYTES_PROPERTY, "0");
        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tiles.toString(),
                out.toString(),
                "LZW",
                1.0,
                1.0,
                "",
                1.0,
                1.0,
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config);
        assertTrue(result.hasAnyOutput());
        assertTrue(
                result.warnings().stream().anyMatch(w -> w.contains("Not enough free disk space")),
                String.valueOf(result.warnings()));
        String record =
                Files.readString(StitchInfoFile.pathFor(Path.of(result.lastOutput())), StandardCharsets.US_ASCII);
        assertTrue(record.contains("warning: Not enough free disk space"), record);
    }

    private static StitchingConfig config(String folder) {
        return new StitchingConfig(
                "filename", folder, folder, "LZW", 1.0, 1.0, ".", 1.0, StitchingConfig.OutputFormat.OME_TIFF);
    }

    /** 16-bit noise around {@code level}, the size given by {@link #TILE} unless the file says otherwise. */
    private static void writeTile(File file, Random random, int level) throws IOException {
        int size = file.getName().startsWith("tile_") ? 600 : TILE;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster r = img.getRaster();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                r.setSample(
                        x, y, 0, level + (int) (300 * Math.sin(x / 17.0) * Math.cos(y / 23.0)) + random.nextInt(50));
            }
        }
        if (!ImageIO.write(img, "TIFF", file)) {
            throw new IOException("No TIFF writer for " + file);
        }
    }
}
