package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.direct.ChunkCompositor;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.assembly.direct.TileReaderPool;
import qupath.ext.basicstitching.assembly.direct.TileSpatialIndex;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.regions.ImageRegion;

/**
 * Tests for how the compositor resolves pixels covered by more than one tile.
 *
 * <p>The fixture is two flat tiles of different brightness placed side by side with a known overlap.
 * Flat rather than textured on purpose: it isolates the blend arithmetic completely, and it is also
 * the case blending exists for -- an intensity step between neighbouring tiles, which registration
 * cannot fix because the tiles are already in the right place.
 */
class ChunkCompositorBlendTest {

    @TempDir
    Path tempDir;

    private static final int TILE = 100;
    private static final int OVERLAP = 20;
    private static final int STEP = TILE - OVERLAP;
    private static final int LEFT_VALUE = 100;
    private static final int RIGHT_VALUE = 200;

    /** Two flat 8-bit tiles side by side, overlapping by {@value #OVERLAP} px. */
    private List<TileMapping> twoTiles() throws IOException {
        List<TileMapping> mappings = new ArrayList<>();
        mappings.add(flatTile("left.tif", LEFT_VALUE, 0));
        mappings.add(flatTile("right.tif", RIGHT_VALUE, STEP));
        return mappings;
    }

    private TileMapping flatTile(String name, int value, int x) throws IOException {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < TILE; y++) {
            for (int col = 0; col < TILE; col++) {
                img.getRaster().setSample(col, y, 0, value);
            }
        }
        File file = tempDir.resolve(name).toFile();
        if (!ImageIO.write(img, "TIFF", file)) {
            throw new IOException("No TIFF writer available for " + file);
        }
        return new TileMapping(file, ImageRegion.createInstance(x, 0, TILE, TILE, 0, 0), "0");
    }

    private BufferedImage composite(List<TileMapping> mappings, OverlapBlend blend) throws IOException {
        TileSpatialIndex index = new TileSpatialIndex(mappings, 1024);
        try (TileReaderPool pool = new TileReaderPool(8)) {
            ChunkCompositor compositor = new ChunkCompositor(pool, index, blend, false, false, 8);
            return compositor.compositeChunk(0, 0, index.getImageWidth(), index.getImageHeight());
        }
    }

    @Test
    void overlapIsMeasuredFromWhereTilesActuallySit() throws IOException {
        TileSpatialIndex index = new TileSpatialIndex(twoTiles(), 1024);

        assertEquals(OVERLAP, index.getOverlapPxX(), "the horizontal overlap must be read off the placed positions");
        assertEquals(0, index.getOverlapPxY(), "a single row of tiles has no vertical overlap to measure");
    }

    @Test
    void lastWinsLeavesAHardStepAtTheSeam() throws IOException {
        BufferedImage out = composite(twoTiles(), OverlapBlend.LAST_WINS);

        // The right tile is composited second, so it owns the whole overlap outright.
        assertEquals(LEFT_VALUE, sample(out, STEP - 1), "just left of the overlap belongs to the left tile");
        assertEquals(RIGHT_VALUE, sample(out, STEP), "the step is one pixel wide -- that is the seam");
        assertEquals(RIGHT_VALUE, sample(out, STEP + OVERLAP - 1), "and the right tile holds it to the far edge");
    }

    @Test
    void linearFeatherRampsAcrossTheOverlapAndIsSymmetricAtTheMiddle() throws IOException {
        BufferedImage out = composite(twoTiles(), OverlapBlend.LINEAR_FEATHER);

        // Outside the overlap nothing has changed: feathering must not touch pixels only one tile
        // covers, or it would darken the whole mosaic toward its background.
        assertEquals(LEFT_VALUE, sample(out, 40), "a pixel only the left tile covers must be untouched");
        assertEquals(RIGHT_VALUE, sample(out, TILE + 30), "and likewise on the right");

        // Across the overlap the value has to climb from one tile's level to the other's, with no
        // step anywhere. A hard cut fails this on the very first pair.
        for (int x = STEP; x < STEP + OVERLAP - 1; x++) {
            assertTrue(
                    sample(out, x + 1) >= sample(out, x),
                    "the feather must not step backwards at x=" + x + ": " + sample(out, x) + " then "
                            + sample(out, x + 1));
        }
        assertTrue(sample(out, STEP) < LEFT_VALUE + 30, "the overlap must start near the left tile's level");
        assertTrue(sample(out, STEP + OVERLAP - 1) > RIGHT_VALUE - 30, "and finish near the right tile's level");

        assertEquals(
                (LEFT_VALUE + RIGHT_VALUE) / 2.0,
                centreOfOverlap(out),
                1.5,
                "the middle of the overlap must be the mean of the two tiles");
    }

    @Test
    void cosineFeatherSpansTheSameRangeMoreGently() throws IOException {
        BufferedImage linear = composite(twoTiles(), OverlapBlend.LINEAR_FEATHER);
        BufferedImage cosine = composite(twoTiles(), OverlapBlend.COSINE_FEATHER);

        assertEquals(
                (LEFT_VALUE + RIGHT_VALUE) / 2.0,
                centreOfOverlap(cosine),
                1.5,
                "symmetry at the centre holds for any weighting");

        // The point of the cosine is that it leaves the endpoints flat rather than kinked, so near
        // the ends of the overlap it stays closer to the tile it is leaving than the linear ramp does.
        int near = STEP + 2;
        assertTrue(
                Math.abs(sample(cosine, near) - LEFT_VALUE) < Math.abs(sample(linear, near) - LEFT_VALUE),
                "the cosine must hold closer to the departing tile near the seam edge: cosine " + sample(cosine, near)
                        + " vs linear " + sample(linear, near));
    }

    @Test
    void aPixelCoveredByOneTileSurvivesTheOutermostRow() throws IOException {
        // The failure this guards: weights fall to zero at a tile's own border, and at the outer
        // border of the whole mosaic there is no second tile to make up the difference. Without a
        // floor on the weight, normalising divides by zero and paints a background-coloured line
        // right around the image.
        BufferedImage out = composite(twoTiles(), OverlapBlend.LINEAR_FEATHER);

        assertEquals(LEFT_VALUE, sample(out, 0), "the very first column must not be lost to a zero weight");
        assertEquals(
                RIGHT_VALUE,
                sample(out, out.getWidth() - 1),
                "nor the last -- this is the ring of background the floor exists to prevent");
    }

    @Test
    void lastWinsIsByteIdenticalToTheUnblendedPath() throws IOException {
        // The default must cost nothing and change nothing. LAST_WINS answers false to
        // requiresOverlapDetection, so it never enters the accumulator at all; this pins that the
        // output is the same bytes it always was, not merely the same to within rounding.
        List<TileMapping> mappings = twoTiles();
        BufferedImage out = composite(mappings, OverlapBlend.LAST_WINS);

        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                int expected = x >= STEP ? RIGHT_VALUE : LEFT_VALUE;
                assertEquals(expected, out.getRaster().getSample(x, y, 0), "at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void blendedCompositingStaysWithinTheChunkMemoryEnvelope() throws IOException {
        // The accumulator is the one part of blending that can break the streaming design: it is
        // float, so it costs four times the chunk it describes, plus a weight plane. A full-size RGB
        // chunk is the worst case at 12 MB + 4 MB, and the whole direct path exists to hold ~40 MB
        // regardless of how many tiles there are.
        //
        // This measures RETAINED memory, like memoryStaysBounded: what must not happen is the
        // accumulator being hoisted to a field or otherwise outliving the chunk, since a pyramid
        // write has several chunks in flight.
        List<TileMapping> mappings = twoTiles();
        TileSpatialIndex index = new TileSpatialIndex(mappings, 1024);

        try (TileReaderPool pool = new TileReaderPool(8)) {
            ChunkCompositor compositor = new ChunkCompositor(pool, index, OverlapBlend.LINEAR_FEATHER, false, false, 8);
            compositor.compositeChunk(0, 0, index.getImageWidth(), index.getImageHeight());

            Runtime runtime = Runtime.getRuntime();
            long before = HeapMeasurement.settledHeapBytes(runtime);
            for (int i = 0; i < 20; i++) {
                compositor.compositeChunk(0, 0, index.getImageWidth(), index.getImageHeight());
            }
            long retainedMb = Math.max(0, HeapMeasurement.settledHeapBytes(runtime) - before) / (1024 * 1024);

            assertTrue(
                    retainedMb < 20,
                    "compositing 20 chunks retained " + retainedMb + " MB; accumulators must not outlive their chunk");
        }
    }

    /** Sample from the middle row, away from any vertical edge effect. */
    private static int sample(BufferedImage img, int x) {
        return img.getRaster().getSample(x, img.getHeight() / 2, 0);
    }

    /**
     * The value at the point where both tiles are equally far from their own edge.
     *
     * <p>Averaged over the two central columns rather than read from one, because an even-width
     * overlap has no centre pixel: with a 20 px band the left tile's distances run 20..1 while the
     * right tile's run 1..20, so they would be equal only at a half-pixel position. The two columns
     * either side are mirror images of each other, so their mean is exactly the balanced value -- and
     * a single column is not, by about two grey levels, which is real and not rounding.
     *
     * <p>Whatever the weighting curve, that balanced value must be the plain mean of the two tiles.
     * This is what catches a weight computed from the wrong edge, which would still ramp smoothly and
     * still pass a monotonicity check while being centred in the wrong place.
     */
    private static double centreOfOverlap(BufferedImage img) {
        return (sample(img, STEP + OVERLAP / 2 - 1) + sample(img, STEP + OVERLAP / 2)) / 2.0;
    }
}
