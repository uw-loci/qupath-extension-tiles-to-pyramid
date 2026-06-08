package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * Verifies that {@link TileConfigurationTxtStrategy} derives per-tile z/t
 * indices from {@code z{zz}} / {@code t{tt}} directory names while keeping
 * XY from a 2D TileConfiguration.txt, and that flat / dim=3 layouts remain
 * unchanged (every tile z=0, t=0).
 */
public class TileConfigZTStrategyTest {

    private static final int TILE = 32;
    private static final int COLS = 2;
    private static final int ROWS = 2;

    /** Z preserved, single timepoint: tiles under z00/, z01/, z02/. */
    @Test
    public void testZSubdirsTagZ() throws Exception {
        Path root = Files.createTempDirectory("tcfg-z-");
        try {
            Path group = Files.createDirectory(root.resolve("group1"));
            writeTileConfig(group, 2 /* dims in config line */);
            for (int z = 0; z < 3; z++) {
                Path zdir = Files.createDirectories(group.resolve(String.format("z%02d", z)));
                writeMosaicTiles(zdir);
            }

            List<TileMapping> mappings =
                    new TileConfigurationTxtStrategy().prepareStitching(root.toString(), 1.0, 1.0, "group");

            assertEquals(COLS * ROWS * 3, mappings.size(), "4 tiles x 3 z-slices");
            Map<Integer, Integer> perZ = countByZ(mappings);
            assertEquals(Map.of(0, 4, 1, 4, 2, 4), new TreeMap<>(perZ), "4 tiles per z-slice");
            assertTrue(mappings.stream().allMatch(m -> m.region.getT() == 0), "t must be 0 when no t dirs");
            // XY still routed from TileConfiguration (one tile at origin, one at +TILE).
            assertTrue(
                    mappings.stream().anyMatch(m -> m.region.getX() == 0 && m.region.getY() == 0),
                    "a tile must sit at the config origin");
            assertTrue(
                    mappings.stream().anyMatch(m -> m.region.getX() == TILE && m.region.getY() == TILE),
                    "a tile must sit at (TILE, TILE) per config");
        } finally {
            deleteRecursively(root);
        }
    }

    /** Z + T preserved: tiles under t00/z00, t00/z01, t01/z00, t01/z01. */
    @Test
    public void testTZSubdirsTagBoth() throws Exception {
        Path root = Files.createTempDirectory("tcfg-tz-");
        try {
            Path group = Files.createDirectory(root.resolve("group1"));
            writeTileConfig(group, 2);
            for (int t = 0; t < 2; t++) {
                for (int z = 0; z < 2; z++) {
                    Path dir = Files.createDirectories(
                            group.resolve(String.format("t%02d", t)).resolve(String.format("z%02d", z)));
                    writeMosaicTiles(dir);
                }
            }

            List<TileMapping> mappings =
                    new TileConfigurationTxtStrategy().prepareStitching(root.toString(), 1.0, 1.0, "group");

            assertEquals(COLS * ROWS * 2 * 2, mappings.size(), "4 tiles x 2 z x 2 t");
            int maxZ = mappings.stream().mapToInt(m -> m.region.getZ()).max().orElse(-1);
            int maxT = mappings.stream().mapToInt(m -> m.region.getT()).max().orElse(-1);
            assertEquals(1, maxZ, "max z index");
            assertEquals(1, maxT, "max t index");
            // Exactly 4 tiles at (z=1, t=0) and 4 at (z=0, t=1) -- proves z/t are not swapped.
            assertEquals(
                    4,
                    mappings.stream()
                            .filter(m -> m.region.getZ() == 1 && m.region.getT() == 0)
                            .count(),
                    "z1,t0");
            assertEquals(
                    4,
                    mappings.stream()
                            .filter(m -> m.region.getZ() == 0 && m.region.getT() == 1)
                            .count(),
                    "z0,t1");
        } finally {
            deleteRecursively(root);
        }
    }

    /** Flat layout (projected / 2D): every tile z=0, t=0 -- backward identical. */
    @Test
    public void testFlatLayoutAllZero() throws Exception {
        Path root = Files.createTempDirectory("tcfg-flat-");
        try {
            Path group = Files.createDirectory(root.resolve("group1"));
            writeTileConfig(group, 2);
            writeMosaicTiles(group);

            List<TileMapping> mappings =
                    new TileConfigurationTxtStrategy().prepareStitching(root.toString(), 1.0, 1.0, "group");

            assertEquals(COLS * ROWS, mappings.size());
            assertTrue(
                    mappings.stream().allMatch(m -> m.region.getZ() == 0 && m.region.getT() == 0),
                    "flat layout must stay 2D");
        } finally {
            deleteRecursively(root);
        }
    }

    /** dim=3 TileConfiguration line (legacy) must still parse XY; z stays 0 (no z dirs). */
    @Test
    public void testDim3ConfigStillParsesXY() throws Exception {
        Path root = Files.createTempDirectory("tcfg-dim3-");
        try {
            Path group = Files.createDirectory(root.resolve("group1"));
            writeTileConfig(group, 3);
            writeMosaicTiles(group);

            List<TileMapping> mappings =
                    new TileConfigurationTxtStrategy().prepareStitching(root.toString(), 1.0, 1.0, "group");

            assertEquals(COLS * ROWS, mappings.size(), "all tiles still mapped from dim=3 config");
            assertTrue(
                    mappings.stream().anyMatch(m -> m.region.getX() == TILE && m.region.getY() == TILE),
                    "XY from coord[0],coord[1] of a dim=3 line");
            assertTrue(mappings.stream().allMatch(m -> m.region.getZ() == 0), "no z dirs -> z=0");
        } finally {
            deleteRecursively(root);
        }
    }

    // --- helpers ---

    private static Map<Integer, Integer> countByZ(List<TileMapping> mappings) {
        Map<Integer, Integer> perZ = new TreeMap<>();
        for (TileMapping m : mappings) {
            perZ.merge(m.region.getZ(), 1, Integer::sum);
        }
        return perZ;
    }

    /** Write a 2x2 grid of constant tiles named tile_000.tif .. tile_003.tif in {@code dir}. */
    private static void writeMosaicTiles(Path dir) throws IOException {
        int idx = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                File f = dir.resolve(String.format("tile_%03d.tif", idx)).toFile();
                writeConstantTile(f, 50 + idx * 10);
                idx++;
            }
        }
    }

    /** TileConfiguration.txt with the same tile order as {@link #writeMosaicTiles}. */
    private static void writeTileConfig(Path group, int dims) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("dim = ").append(dims).append('\n');
        int idx = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                String name = String.format("tile_%03d.tif", idx);
                double x = col * (double) TILE;
                double y = row * (double) TILE;
                if (dims == 3) {
                    sb.append(String.format("%s; ; (%.1f, %.1f, 0.0)%n", name, x, y));
                } else {
                    sb.append(String.format("%s; ; (%.1f, %.1f)%n", name, x, y));
                }
                idx++;
            }
        }
        Files.writeString(group.resolve("TileConfiguration.txt"), sb.toString());
    }

    private static void writeConstantTile(File file, int value) throws IOException {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
        var raster = img.getRaster();
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                raster.setSample(x, y, 0, value);
            }
        }
        if (!ImageIO.write(img, "TIFF", file)) {
            throw new IOException("No TIFF ImageWriter available to write " + file);
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(TileConfigZTStrategyTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best-effort cleanup
        }
    }
}
