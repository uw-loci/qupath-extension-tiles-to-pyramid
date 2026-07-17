package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.ImageRegion;

/**
 * 5D tiled-stitching tests for {@link DirectTileStitcher}: a mosaic of tiles
 * tagged with z-slice and timepoint indices must assemble into a single
 * multi-dimensional output (OME-TIFF and OME-ZARR) with the correct
 * SizeC / SizeZ / SizeT, and each (z, t) plane carrying the right pixels.
 *
 * <p>Tiles are written as small constant-value TIFFs where the value is a
 * function of (z, t) only, so a plane read back at (z, t) must equal the value
 * acquired for that z-slice and timepoint. The values are distinct for
 * (z=0,t=1) vs (z=1,t=0), so a z/t swap in a writer would fail. The cases cover
 * every combination of {grayscale, RGB} x {Z>1, T>1, both, neither}.
 */
public class DirectStitcher5DTest {

    private static final int TILE = 64;
    private static final int COLS = 2;
    private static final int ROWS = 2;
    private static final int MOSAIC_W = COLS * TILE; // 128
    private static final int MOSAIC_H = ROWS * TILE; // 128

    /** Distinct constant value per (z, t) plane; stays within 0..255 for the cases used. */
    private static int planeValue(int z, int t) {
        return 40 + z * 50 + t * 17;
    }

    // --- Grayscale: Z and T ---

    @Test
    public void testTiffGray_ZandT() throws Exception {
        runCase(2, 2, false, StitchingConfig.OutputFormat.OME_TIFF);
    }

    @Test
    public void testZarrGray_ZandT() throws Exception {
        runCase(2, 2, false, StitchingConfig.OutputFormat.OME_ZARR);
    }

    // --- RGB (multichannel): Z and T ---

    @Test
    public void testTiffRgb_ZandT() throws Exception {
        runCase(2, 2, true, StitchingConfig.OutputFormat.OME_TIFF);
    }

    @Test
    public void testZarrRgb_ZandT() throws Exception {
        runCase(2, 2, true, StitchingConfig.OutputFormat.OME_ZARR);
    }

    // --- Single-axis and 2D (exercise the axis-dropping branches) ---

    @Test
    public void testZarrGray_Zonly() throws Exception {
        runCase(3, 1, false, StitchingConfig.OutputFormat.OME_ZARR);
    }

    @Test
    public void testZarrGray_Tonly() throws Exception {
        runCase(1, 3, false, StitchingConfig.OutputFormat.OME_ZARR);
    }

    @Test
    public void testTiffGray_Tonly() throws Exception {
        runCase(1, 3, false, StitchingConfig.OutputFormat.OME_TIFF);
    }

    @Test
    public void testZarr2D_unchanged() throws Exception {
        runCase(1, 1, false, StitchingConfig.OutputFormat.OME_ZARR);
    }

    /**
     * Fluorescence path: two single-channel 5D stitches merged into one 2-channel
     * 5D OME-TIFF. Verifies the channel axis composes with Z and T end to end --
     * each (z, t) plane must carry channel A's value in band 0 and channel B's in
     * band 1.
     */
    @Test
    public void testMerge5D_twoChannels() throws Exception {
        Path tempDir = Files.createTempDirectory("stitch5d-merge-");
        int nz = 2;
        int nt = 2;
        IntBinaryOperator valueA = (z, t) -> 30 + z * 40 + t * 15; // 30,45,70,85
        IntBinaryOperator valueB = (z, t) -> 150 + z * 20 + t * 10; // 150,160,170,180
        try {
            Path dirA = Files.createDirectory(tempDir.resolve("A"));
            Path dirB = Files.createDirectory(tempDir.resolve("B"));
            List<TileMapping> mapA = buildMosaic(dirA, nz, nt, false, valueA);
            List<TileMapping> mapB = buildMosaic(dirB, nz, nt, false, valueB);

            StitchingConfig config = new StitchingConfig(
                    "filename",
                    tempDir.toString(),
                    tempDir.toString(),
                    "LZW",
                    1.0,
                    1.0,
                    ".",
                    1.0,
                    StitchingConfig.OutputFormat.OME_TIFF);

            String pathA = DirectTileStitcher.stitch(mapA, tempDir.toString(), "chA", config, null);
            String pathB = DirectTileStitcher.stitch(mapB, tempDir.toString(), "chB", config, null);
            assertNotNull(pathA, "channel A stitch");
            assertNotNull(pathB, "channel B stitch");

            String merged = ChannelMerger.merge(
                    List.of(pathA, pathB),
                    List.of("ChA", "ChB"),
                    tempDir.toString(),
                    "merged",
                    "LZW",
                    StitchingConfig.OutputFormat.OME_TIFF);
            assertNotNull(merged, "merge of two 5D single-channel files should succeed");

            try (ImageServer<BufferedImage> read =
                    ImageServers.buildServer(Path.of(merged).toUri())) {
                assertEquals(MOSAIC_W, read.getWidth());
                assertEquals(MOSAIC_H, read.getHeight());
                assertEquals(2, read.nChannels(), "merged channels");
                assertEquals(nz, read.nZSlices(), "merged z-slices");
                assertEquals(nt, read.nTimepoints(), "merged timepoints");

                for (int z = 0; z < nz; z++) {
                    for (int t = 0; t < nt; t++) {
                        BufferedImage plane = read.readRegion(1.0, 0, 0, MOSAIC_W, MOSAIC_H, z, t);
                        int a = plane.getRaster().getSample(TILE / 2, TILE / 2, 0);
                        int b = plane.getRaster().getSample(TILE / 2, TILE / 2, 1);
                        assertEquals(valueA.applyAsInt(z, t), a, "merged band0 (z=" + z + ",t=" + t + ")");
                        assertEquals(valueB.applyAsInt(z, t), b, "merged band1 (z=" + z + ",t=" + t + ")");
                    }
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Build a (nz x nt) mosaic, stitch it, and verify dimensions and per-plane values. */
    private void runCase(int nz, int nt, boolean rgb, StitchingConfig.OutputFormat format) throws Exception {
        if (format == StitchingConfig.OutputFormat.OME_ZARR) {
            // ZARR compresses through the native blosc library, which is provided by the QuPath app
            // in production but absent from a bare Gradle test JVM on some platforms (notably the
            // Windows CI runner, which resolves the Linux native). Skip with the real reason rather
            // than fail on an UnsatisfiedLinkError that says nothing about the code under test. This
            // runs the moment blosc is genuinely loadable. See BloscSupport.
            org.junit.jupiter.api.Assumptions.assumeTrue(BloscSupport.isAvailable(), BloscSupport.unavailableReason());
        }
        Path tempDir = Files.createTempDirectory("stitch5d-");
        try {
            List<TileMapping> mappings = buildMosaic(tempDir, nz, nt, rgb, DirectStitcher5DTest::planeValue);

            String compression = format == StitchingConfig.OutputFormat.OME_ZARR ? "zstd" : "LZW";
            StitchingConfig config = new StitchingConfig(
                    "filename", tempDir.toString(), tempDir.toString(), compression, 1.0, 1.0, ".", 1.0, format);

            String out = DirectTileStitcher.stitch(mappings, tempDir.toString(), "mosaic", config, null);
            assertNotNull(
                    out, format + " stitch should produce output (nz=" + nz + ", nt=" + nt + ", rgb=" + rgb + ")");

            try (ImageServer<BufferedImage> read =
                    ImageServers.buildServer(Path.of(out).toUri())) {
                assertEquals(MOSAIC_W, read.getWidth(), "mosaic width");
                assertEquals(MOSAIC_H, read.getHeight(), "mosaic height");
                assertEquals(rgb ? 3 : 1, read.nChannels(), "channels");
                assertEquals(nz, read.nZSlices(), "z-slices");
                assertEquals(nt, read.nTimepoints(), "timepoints");

                for (int z = 0; z < nz; z++) {
                    for (int t = 0; t < nt; t++) {
                        BufferedImage plane = read.readRegion(1.0, 0, 0, MOSAIC_W, MOSAIC_H, z, t);
                        assertNotNull(plane, "plane (z=" + z + ",t=" + t + ") read null");
                        int v = plane.getRaster().getSample(TILE / 2, TILE / 2, 0);
                        assertEquals(
                                planeValue(z, t),
                                v,
                                "plane (z=" + z + ",t=" + t + ") value -- z/t routed to wrong plane? (format=" + format
                                        + ", rgb=" + rgb + ")");
                    }
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Write one constant tile file per (z, t) and build a COLS x ROWS mosaic for every (z, t). */
    private static List<TileMapping> buildMosaic(Path dir, int nz, int nt, boolean rgb, IntBinaryOperator valueFn)
            throws IOException {
        File[][] files = new File[nz][nt];
        for (int z = 0; z < nz; z++) {
            for (int t = 0; t < nt; t++) {
                File f = dir.resolve("tile_z" + z + "_t" + t + ".tif").toFile();
                writeConstantTile(f, valueFn.applyAsInt(z, t), rgb);
                files[z][t] = f;
            }
        }

        List<TileMapping> mappings = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                for (int z = 0; z < nz; z++) {
                    for (int t = 0; t < nt; t++) {
                        ImageRegion region = ImageRegion.createInstance(col * TILE, row * TILE, TILE, TILE, z, t);
                        mappings.add(new TileMapping(files[z][t], region, "."));
                    }
                }
            }
        }
        return mappings;
    }

    private static void writeConstantTile(File file, int value, boolean rgb) throws IOException {
        BufferedImage img;
        if (rgb) {
            img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
            int packed = (value << 16) | (value << 8) | value;
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    img.setRGB(x, y, packed);
                }
            }
        } else {
            img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
            var raster = img.getRaster();
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    raster.setSample(x, y, 0, value);
                }
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
                    stream.forEach(DirectStitcher5DTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best-effort cleanup
        }
    }
}
