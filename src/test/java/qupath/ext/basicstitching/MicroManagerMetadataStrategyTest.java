package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * Unit tests for {@link MicroManagerMetadataStrategy}. Builds a synthetic
 * MMStack-style folder (two TIFFs + sidecar JSON metadata files) and asserts
 * the strategy resolves the correct pixel coordinates.
 */
class MicroManagerMetadataStrategyTest {

    private static final int TILE_W = 16;
    private static final int TILE_H = 16;
    private static final double PIXEL_SIZE_UM = 0.5; // chosen so 8 um -> 16 px
    private static final double DOWNSAMPLE = 1.0;

    @Test
    void resolvesPositionsFromSidecarFrameKey(@TempDir Path tmp) throws IOException {
        writeTile(tmp.resolve("acq_MMStack_Pos-0_000.ome.tif"));
        writeTile(tmp.resolve("acq_MMStack_Pos-1_000.ome.tif"));

        // Two sidecars with authoritative FrameKey positions. Stage X is 0 um
        // and 8 um respectively; at 0.5 um/px that maps to pixels 0 and 16.
        writeSidecar(
                tmp.resolve("acq_MMStack_Pos-0_000_metadata.txt"),
                "acq_MMStack_Pos-0_000.ome.tif",
                0.0,
                0.0,
                List.of(),
                List.of());
        writeSidecar(
                tmp.resolve("acq_MMStack_Pos-1_000_metadata.txt"),
                "acq_MMStack_Pos-1_000.ome.tif",
                8.0,
                4.0,
                List.of(),
                List.of());

        List<TileMapping> mappings =
                new MicroManagerMetadataStrategy().prepareStitching(tmp.toString(), PIXEL_SIZE_UM, DOWNSAMPLE, ".");

        assertEquals(2, mappings.size(), "Both tiles should be mapped");
        TileMapping a = findByName(mappings, "acq_MMStack_Pos-0_000.ome.tif");
        TileMapping b = findByName(mappings, "acq_MMStack_Pos-1_000.ome.tif");
        assertEquals(0, a.region.getX());
        assertEquals(0, a.region.getY());
        assertEquals(16, b.region.getX());
        assertEquals(8, b.region.getY());
        assertEquals(TILE_W, a.region.getWidth());
        assertEquals(TILE_H, a.region.getHeight());
    }

    @Test
    void fallsBackToStagePositionsLabelWhenSidecarMissing(@TempDir Path tmp) throws IOException {
        writeTile(tmp.resolve("acq_MMStack_Pos-0_000.ome.tif"));
        writeTile(tmp.resolve("acq_MMStack_Pos-1_000.ome.tif"));

        // Only one sidecar present, but its Summary.StagePositions block lists
        // both labels with their nominal positions. The second tile should
        // still resolve via the label fallback.
        writeSidecar(
                tmp.resolve("acq_MMStack_Pos-0_000_metadata.txt"),
                "acq_MMStack_Pos-0_000.ome.tif",
                0.0,
                0.0,
                List.of("Pos-0_000", "Pos-1_000"),
                List.of(new double[] {0.0, 0.0}, new double[] {8.0, 0.0}));

        List<TileMapping> mappings =
                new MicroManagerMetadataStrategy().prepareStitching(tmp.toString(), PIXEL_SIZE_UM, DOWNSAMPLE, ".");

        assertEquals(2, mappings.size(), "Both tiles should resolve (one via sidecar, one via label)");
        TileMapping b = findByName(mappings, "acq_MMStack_Pos-1_000.ome.tif");
        assertEquals(16, b.region.getX());
        assertEquals(0, b.region.getY());
    }

    @Test
    void honorsFlipFlags(@TempDir Path tmp) throws IOException {
        writeTile(tmp.resolve("acq_MMStack_Pos-0_000.ome.tif"));
        writeSidecar(
                tmp.resolve("acq_MMStack_Pos-0_000_metadata.txt"),
                "acq_MMStack_Pos-0_000.ome.tif",
                4.0,
                6.0,
                List.of(),
                List.of());

        MicroManagerMetadataStrategy.flipStitchingX = true;
        MicroManagerMetadataStrategy.flipStitchingY = true;
        try {
            List<TileMapping> mappings =
                    new MicroManagerMetadataStrategy().prepareStitching(tmp.toString(), PIXEL_SIZE_UM, DOWNSAMPLE, ".");
            assertEquals(1, mappings.size());
            // 4 um and 6 um flipped to -4 and -6 -> -8 and -12 px.
            assertEquals(-8, mappings.get(0).region.getX());
            assertEquals(-12, mappings.get(0).region.getY());
        } finally {
            MicroManagerMetadataStrategy.flipStitchingX = false;
            MicroManagerMetadataStrategy.flipStitchingY = false;
        }
    }

    @Test
    void resolvesPositionsFromSinglePlaneSeriesLayout(@TempDir Path tmp) throws IOException {
        // SINGLEPLANE_TIFF_SERIES layout: each position is its own subfolder
        // containing a single-image TIFF and a metadata.txt whose per-tile data
        // lives in a "Metadata-<relpath>" block (key encodes the file).
        Path posA = Files.createDirectories(tmp.resolve("Pos-1-000_000"));
        Path posB = Files.createDirectories(tmp.resolve("Pos-1-001_000"));
        writeTile(posA.resolve("img_channel000_position000_time000000000_z000.tif"));
        writeTile(posB.resolve("img_channel000_position001_time000000000_z000.tif"));

        // Stage X is 0 um and 8 um; at 0.5 um/px -> pixels 0 and 16.
        writeSinglePlaneMetadata(
                posA.resolve("metadata.txt"),
                "Pos-1-000_000/img_channel000_position000_time000000000_z000.tif",
                0.0,
                0.0);
        writeSinglePlaneMetadata(
                posB.resolve("metadata.txt"),
                "Pos-1-001_000/img_channel000_position001_time000000000_z000.tif",
                8.0,
                4.0);

        List<TileMapping> mappings =
                new MicroManagerMetadataStrategy().prepareStitching(tmp.toString(), 1.0, DOWNSAMPLE, ".");

        assertEquals(2, mappings.size(), "Both single-plane tiles should be mapped");
        TileMapping a = findByName(mappings, "img_channel000_position000_time000000000_z000.tif");
        TileMapping b = findByName(mappings, "img_channel000_position001_time000000000_z000.tif");
        // Pixel size 0.5 um is read from the Metadata block, overriding the
        // caller's 1.0 argument.
        assertEquals(0, a.region.getX());
        assertEquals(0, a.region.getY());
        assertEquals(16, b.region.getX());
        assertEquals(8, b.region.getY());
        // Single-image TIFFs -> series 0 regardless of StagePositions order.
        assertEquals(0, a.seriesIndex);
        assertEquals(0, b.seriesIndex);
        // All single-plane tiles group into one output named after the folder.
        assertEquals(tmp.getFileName().toString(), a.subdirName);
        assertEquals(tmp.getFileName().toString(), b.subdirName);
    }

    @Test
    void detectsPixelSizeFromSinglePlaneSeriesLayout(@TempDir Path tmp) throws IOException {
        Path pos = Files.createDirectories(tmp.resolve("Pos-1-000_000"));
        writeTile(pos.resolve("img_channel000_position000_time000000000_z000.tif"));
        writeSinglePlaneMetadata(
                pos.resolve("metadata.txt"),
                "Pos-1-000_000/img_channel000_position000_time000000000_z000.tif",
                0.0,
                0.0);

        Double detected = MicroManagerMetadataStrategy.detectPixelSizeUm(tmp.toFile());
        assertNotNull(detected);
        assertEquals(PIXEL_SIZE_UM, detected, 1e-9);
    }

    @Test
    void manualOverrideWinsOverMetadataPixelSize(@TempDir Path tmp) throws IOException {
        // Sidecar reports PixelSizeUm = 0.5 (PIXEL_SIZE_UM); stage X = 8 um.
        writeTile(tmp.resolve("acq_MMStack_Pos-0_000.ome.tif"));
        writeSidecar(
                tmp.resolve("acq_MMStack_Pos-0_000_metadata.txt"),
                "acq_MMStack_Pos-0_000.ome.tif",
                8.0,
                0.0,
                List.of(),
                List.of());

        // Default: metadata 0.5 um/px is authoritative -> 8 um maps to 16 px.
        TileMapping defaultMapping = new MicroManagerMetadataStrategy()
                .prepareStitching(tmp.toString(), 1.0, DOWNSAMPLE, ".")
                .get(0);
        assertEquals(16, defaultMapping.region.getX(), "Metadata pixel size should win by default");

        // Manual override: caller's 1.0 um/px wins -> 8 um maps to 8 px.
        TileMapping overridden = new MicroManagerMetadataStrategy(true)
                .prepareStitching(tmp.toString(), 1.0, DOWNSAMPLE, ".")
                .get(0);
        assertEquals(8, overridden.region.getX(), "Manual override must beat metadata pixel size");
    }

    @Test
    void estimatesPixelSizeFromTileOverlap(@TempDir Path tmp) throws IOException {
        // Two horizontally adjacent tiles cropped from one textured base with a
        // known 64 px overlap step. Stage X step is 32 um, so the true pixel
        // size is 32/64 = 0.5 um/px regardless of any metadata PixelSizeUm.
        int tile = 128;
        int baseW = 192; // tile + 64 px step
        int[][] base = new int[tile][baseW];
        java.util.Random rng = new java.util.Random(42);
        for (int y = 0; y < tile; y++) {
            for (int x = 0; x < baseW; x++) {
                base[y][x] = rng.nextInt(256);
            }
        }
        Path posA = Files.createDirectories(tmp.resolve("Pos-A"));
        Path posB = Files.createDirectories(tmp.resolve("Pos-B"));
        writeGrayTile(posA.resolve("imgA.tif"), base, 0, tile); // cols 0..127
        writeGrayTile(posB.resolve("imgB.tif"), base, 64, tile); // cols 64..191
        writeSinglePlaneMetadata(posA.resolve("metadata.txt"), "Pos-A/imgA.tif", 0.0, 0.0);
        writeSinglePlaneMetadata(posB.resolve("metadata.txt"), "Pos-B/imgB.tif", 32.0, 0.0);

        MicroManagerMetadataStrategy.PixelSizeEstimate est =
                MicroManagerMetadataStrategy.estimatePixelSizeUm(tmp.toFile());
        assertTrue(est.ok(), "Estimate should succeed: " + est.message);
        assertEquals(0.5, est.pixelSizeUm, 0.05, "Estimated pixel size should be ~32um/64px");
        assertTrue(est.confidence > 0.5, "Noise tiles should correlate strongly: " + est.confidence);
    }

    @Test
    void extractsLabelFromMMStackFilename() {
        assertEquals(
                "Pos-3-001_002",
                MicroManagerMetadataStrategy.extractMMStackLabel("prefix_MMStack_Pos-3-001_002.ome.tif"));
        assertEquals("Pos0", MicroManagerMetadataStrategy.extractMMStackLabel("acq_MMStack_Pos0.ome.tif"));
        assertNull(MicroManagerMetadataStrategy.extractMMStackLabel("no_mmstack_here.ome.tif"));
    }

    private static TileMapping findByName(List<TileMapping> mappings, String filename) {
        return mappings.stream()
                .filter(m -> m.file.getName().equals(filename))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tile not found: " + filename));
    }

    private static void writeTile(Path path) throws IOException {
        BufferedImage img = new BufferedImage(TILE_W, TILE_H, BufferedImage.TYPE_BYTE_GRAY);
        // ImageIO accepts the .tif extension via Bio-Formats / TwelveMonkeys at
        // runtime; if not registered, fall back to writing a plain TIFF via
        // the standard JDK provider.
        if (!ImageIO.write(img, "TIFF", path.toFile())) {
            // ImageIO returns false when no writer is found -- try the
            // alternate spelling so the test still runs in JDK-only setups.
            if (!ImageIO.write(img, "tif", path.toFile())) {
                throw new IOException("No TIFF writer available for " + path);
            }
        }
    }

    /**
     * Write a minimal MMStack metadata sidecar JSON. Only the keys read by
     * {@link MicroManagerMetadataStrategy} are populated.
     */
    private static void writeSidecar(
            Path path,
            String tileFilename,
            double xUm,
            double yUm,
            List<String> stageLabels,
            List<double[]> stagePositions)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"Summary\": {\n");
        sb.append("    \"StagePositions\": [");
        for (int i = 0; i < stageLabels.size(); i++) {
            if (i > 0) sb.append(',');
            double[] xy = stagePositions.get(i);
            sb.append("{\n");
            sb.append("      \"Label\": \"").append(stageLabels.get(i)).append("\",\n");
            sb.append("      \"DefaultXYStage\": \"XYStage\",\n");
            sb.append("      \"DevicePositions\": [{\n");
            sb.append("        \"Device\": \"XYStage\",\n");
            sb.append("        \"Position_um\": [")
                    .append(xy[0])
                    .append(", ")
                    .append(xy[1])
                    .append("]\n");
            sb.append("      }]\n");
            sb.append("    }");
        }
        sb.append("]\n");
        sb.append("  },\n");
        sb.append("  \"FrameKey-0-0-0\": {\n");
        sb.append("    \"FileName\": \"").append(tileFilename).append("\",\n");
        sb.append("    \"XPositionUm\": ").append(xUm).append(",\n");
        sb.append("    \"YPositionUm\": ").append(yUm).append(",\n");
        sb.append("    \"PixelSizeUm\": ").append(PIXEL_SIZE_UM).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        Files.writeString(path, sb.toString());
    }

    /**
     * Write a {@code size x size} 8-bit grayscale TIFF cropped from {@code base}
     * starting at column {@code xOffset} (full height). Used to build textured,
     * overlapping tiles for the pixel-size estimator.
     */
    private static void writeGrayTile(Path path, int[][] base, int xOffset, int size) throws IOException {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.getRaster().setSample(x, y, 0, base[y][xOffset + x]);
            }
        }
        if (!ImageIO.write(img, "TIFF", path.toFile()) && !ImageIO.write(img, "tif", path.toFile())) {
            throw new IOException("No TIFF writer available for " + path);
        }
    }

    /**
     * Write a minimal SINGLEPLANE_TIFF_SERIES metadata.txt. The per-tile data
     * lives in a "Metadata-&lt;relFile&gt;" block whose key encodes the file
     * path relative to the acquisition root. Only the keys read by
     * {@link MicroManagerMetadataStrategy} are populated.
     */
    private static void writeSinglePlaneMetadata(Path path, String relFile, double xUm, double yUm) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"Summary\": {\n");
        sb.append("    \"StagePositions\": []\n");
        sb.append("  },\n");
        sb.append("  \"Coords-").append(relFile).append("\": {\n");
        sb.append("    \"PositionIndex\": 0\n");
        sb.append("  },\n");
        sb.append("  \"Metadata-").append(relFile).append("\": {\n");
        sb.append("    \"Width\": ").append(TILE_W).append(",\n");
        sb.append("    \"Height\": ").append(TILE_H).append(",\n");
        sb.append("    \"PixelSizeUm\": ").append(PIXEL_SIZE_UM).append(",\n");
        sb.append("    \"XPositionUm\": ").append(xUm).append(",\n");
        sb.append("    \"YPositionUm\": ").append(yUm).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        Files.writeString(path, sb.toString());
    }
}
