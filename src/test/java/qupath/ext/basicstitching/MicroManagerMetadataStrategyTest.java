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
}
