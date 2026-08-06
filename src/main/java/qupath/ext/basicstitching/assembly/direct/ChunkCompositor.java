package qupath.ext.basicstitching.assembly.direct;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * Composites source tiles into output chunk buffers.
 * <p>
 * For each output chunk, queries the {@link TileSpatialIndex} for contributing
 * source tiles (typically 1-4), reads the overlapping region from each tile via
 * the {@link TileReaderPool}, and composites them into the output buffer.
 * <p>
 * RGB images get a white background in uncovered regions; non-RGB images
 * get black (zero), matching the existing {@code WhiteBackgroundImageServer} behavior.
 */
public class ChunkCompositor {

    private static final Logger logger = LoggerFactory.getLogger(ChunkCompositor.class);

    private final TileReaderPool readerPool;
    private final TileSpatialIndex spatialIndex;
    private final BlendStrategy blendStrategy;
    private final boolean whiteBackground;
    private final boolean isRGB;
    private final int bitDepth;

    /**
     * Create a compositor.
     *
     * @param readerPool Bounded pool for tile file I/O
     * @param spatialIndex Spatial index for fast tile lookup
     * @param blendStrategy Blend weights for overlapping regions
     * @param whiteBackground Whether to fill uncovered regions with white
     * @param isRGB Whether the image is RGB (affects buffer type and background)
     * @param bitDepth Bits per sample (8 or 16)
     */
    public ChunkCompositor(
            TileReaderPool readerPool,
            TileSpatialIndex spatialIndex,
            BlendStrategy blendStrategy,
            boolean whiteBackground,
            boolean isRGB,
            int bitDepth) {
        this.readerPool = readerPool;
        this.spatialIndex = spatialIndex;
        this.blendStrategy = blendStrategy;
        this.whiteBackground = whiteBackground;
        this.isRGB = isRGB;
        this.bitDepth = bitDepth;
    }

    /**
     * Composite the level-0 plane at {@code (z, t)} for a chunk. Convenience for
     * 2D stitches (z = t = 0).
     */
    public BufferedImage compositeChunk(int chunkX, int chunkY, int chunkW, int chunkH) throws IOException {
        return compositeChunk(0, 0, chunkX, chunkY, chunkW, chunkH);
    }

    /**
     * Composite source tiles into an output chunk buffer for one (z, t) plane.
     * Coordinates are in origin-translated space (0-based). Only tiles whose
     * {@code region} is at the requested z-slice and timepoint contribute; tiles
     * at other z/t are skipped, so an interleaved 5D tile set assembles into the
     * correct plane.
     *
     * @param z Z-slice index to composite
     * @param t Timepoint index to composite
     * @param chunkX Chunk X position in the full image
     * @param chunkY Chunk Y position in the full image
     * @param chunkW Chunk width (may be less than chunk size at image edge)
     * @param chunkH Chunk height (may be less than chunk size at image edge)
     * @return Composited BufferedImage
     */
    public BufferedImage compositeChunk(int z, int t, int chunkX, int chunkY, int chunkW, int chunkH)
            throws IOException {
        // Query spatial index for contributing tiles
        List<TileMapping> tiles = spatialIndex.query(chunkX, chunkY, chunkW, chunkH);

        if (blendStrategy.requiresOverlapDetection()) {
            return compositeBlended(z, t, chunkX, chunkY, chunkW, chunkH, tiles);
        }

        // Create output buffer with appropriate type and background
        BufferedImage output = createOutputBuffer(chunkW, chunkH);

        int originX = spatialIndex.getOriginX();
        int originY = spatialIndex.getOriginY();

        // Composite each contributing tile
        for (TileMapping tile : tiles) {
            // Skip tiles that belong to a different z-slice or timepoint. The XY
            // spatial index is shared across all z/t, so the same cell can hold
            // tiles from every plane; the (z, t) filter selects the current one.
            if (tile.region.getZ() != z || tile.region.getT() != t) {
                continue;
            }
            // Tile position in origin-translated coords
            int tileX = tile.region.getX() - originX;
            int tileY = tile.region.getY() - originY;
            int tileW = tile.region.getWidth();
            int tileH = tile.region.getHeight();

            // Compute intersection of tile with chunk
            int isectLeft = Math.max(tileX, chunkX);
            int isectTop = Math.max(tileY, chunkY);
            int isectRight = Math.min(tileX + tileW, chunkX + chunkW);
            int isectBottom = Math.min(tileY + tileH, chunkY + chunkH);

            if (isectRight <= isectLeft || isectBottom <= isectTop) {
                continue;
            }

            int isectW = isectRight - isectLeft;
            int isectH = isectBottom - isectTop;

            // Source region within the tile file
            int srcX = isectLeft - tileX;
            int srcY = isectTop - tileY;

            // Destination position within the output chunk
            int dstX = isectLeft - chunkX;
            int dstY = isectTop - chunkY;

            try {
                // Read the relevant region from the tile
                BufferedImage tileData = readerPool.readRegion(tile.file, srcX, srcY, isectW, isectH);

                if (tileData.getType() == output.getType() || tileData.getType() == BufferedImage.TYPE_CUSTOM) {
                    // Raw raster transfer -- matches QuPath's SparseImageServer approach.
                    // Preserves all data types exactly (including TYPE_CUSTOM / unusual
                    // bit depths) without going through the Java2D rendering pipeline.
                    output.getRaster()
                            .setDataElements(
                                    dstX,
                                    dstY,
                                    isectW,
                                    isectH,
                                    tileData.getRaster().getDataElements(0, 0, isectW, isectH, null));
                } else {
                    // Type mismatch (e.g. TYPE_3BYTE_BGR tile into TYPE_INT_RGB output).
                    // Use Graphics2D which handles the conversion automatically.
                    Graphics2D g = output.createGraphics();
                    g.drawImage(tileData, dstX, dstY, null);
                    g.dispose();
                }
            } catch (IOException e) {
                logger.warn("Failed to read tile region from {}: {}", tile.file.getName(), e.getMessage());
                // Continue with remaining tiles -- gap will show background color
            }
        }

        return output;
    }

    /**
     * Composite one chunk through a weighted accumulator, for the feathering strategies.
     *
     * <p>Kept entirely separate from the overwrite path above rather than generalising it. The
     * overwrite path transfers raster data directly, allocates nothing per chunk, and is what every
     * existing stitch uses; routing it through an accumulator would make the default slower and no
     * longer bit-exact for the sake of code that only the non-default modes run.
     *
     * <h2>Memory</h2>
     *
     * <p>The accumulator is the one place the streaming design's bounded footprint is at risk: a
     * 1024x1024 RGB chunk needs 12 MB of float sums plus 4 MB of weights. That is per chunk and
     * released as soon as the chunk is written, and it is why the buffers are allocated here rather
     * than held on the compositor -- a field would keep them alive for the whole stitch, and a
     * pyramid write holds several chunks in flight.
     */
    private BufferedImage compositeBlended(
            int z, int t, int chunkX, int chunkY, int chunkW, int chunkH, List<TileMapping> tiles) {

        BufferedImage output = createOutputBuffer(chunkW, chunkH);
        WritableRaster outRaster = output.getRaster();
        int bands = outRaster.getNumBands();
        int pixels = chunkW * chunkH;

        float[][] accum = new float[bands][pixels];
        float[] weights = new float[pixels];

        int overlapX = spatialIndex.getOverlapPxX();
        int overlapY = spatialIndex.getOverlapPxY();
        int originX = spatialIndex.getOriginX();
        int originY = spatialIndex.getOriginY();

        for (TileMapping tile : tiles) {
            if (tile.region.getZ() != z || tile.region.getT() != t) {
                continue;
            }
            int tileX = tile.region.getX() - originX;
            int tileY = tile.region.getY() - originY;
            int tileW = tile.region.getWidth();
            int tileH = tile.region.getHeight();

            int isectLeft = Math.max(tileX, chunkX);
            int isectTop = Math.max(tileY, chunkY);
            int isectRight = Math.min(tileX + tileW, chunkX + chunkW);
            int isectBottom = Math.min(tileY + tileH, chunkY + chunkH);
            if (isectRight <= isectLeft || isectBottom <= isectTop) {
                continue;
            }

            int isectW = isectRight - isectLeft;
            int isectH = isectBottom - isectTop;
            int srcX = isectLeft - tileX;
            int srcY = isectTop - tileY;
            int dstX = isectLeft - chunkX;
            int dstY = isectTop - chunkY;

            BufferedImage tileData;
            try {
                tileData = readerPool.readRegion(tile.file, srcX, srcY, isectW, isectH);
            } catch (IOException e) {
                logger.warn("Failed to read tile region from {}: {}", tile.file.getName(), e.getMessage());
                continue;
            }

            // The taper is separable, so the per-pixel weight is one X term times one Y term. Both
            // are computed once for the intersection rather than per pixel, which turns O(w*h)
            // strategy calls into O(w+h).
            float[] wx = axisWeights(srcX, isectW, tileW, overlapX);
            float[] wy = axisWeights(srcY, isectH, tileH, overlapY);

            Raster src = tileData.getRaster();
            int srcBands = src.getNumBands();
            int[] samples = null;
            for (int b = 0; b < bands; b++) {
                // A single-band tile feeding an RGB output replicates into every channel; anything
                // else takes the matching band, or the last one it has.
                int srcBand = Math.min(b, srcBands - 1);
                samples = src.getSamples(0, 0, isectW, isectH, srcBand, samples);
                float[] acc = accum[b];
                for (int row = 0; row < isectH; row++) {
                    int outRow = (dstY + row) * chunkW + dstX;
                    int inRow = row * isectW;
                    float rowW = wy[row];
                    for (int col = 0; col < isectW; col++) {
                        acc[outRow + col] += rowW * wx[col] * samples[inRow + col];
                    }
                }
            }
            // Weights are band-independent, so they are summed once rather than per band.
            for (int row = 0; row < isectH; row++) {
                int outRow = (dstY + row) * chunkW + dstX;
                float rowW = wy[row];
                for (int col = 0; col < isectW; col++) {
                    weights[outRow + col] += rowW * wx[col];
                }
            }
        }

        writeNormalised(outRaster, accum, weights, chunkW, chunkH);
        return output;
    }

    /**
     * Per-column (or per-row) blend weights for the slice of a tile that lands in this chunk.
     *
     * @param offset where the slice starts inside the tile
     * @param length how many pixels of the tile the slice covers
     * @param tileSize the tile's full extent on this axis
     * @param overlap the measured overlap width on this axis
     * @return one weight per pixel of the slice
     */
    private float[] axisWeights(int offset, int length, int tileSize, int overlap) {
        float[] w = new float[length];
        for (int i = 0; i < length; i++) {
            int pos = offset + i;
            // Distance to the nearer of the two edges, counting the outermost pixel as 1 so no
            // in-tile pixel is ever at distance zero.
            int distFromEdge = Math.min(pos + 1, tileSize - pos);
            w[i] = blendStrategy.weight(distFromEdge, overlap);
        }
        return w;
    }

    /**
     * Divide the accumulated sums by the accumulated weights and write the result.
     *
     * <p>Pixels no tile covered keep the background the buffer was created with, which is how the
     * blended path reproduces the overwrite path's treatment of gaps.
     */
    private static void writeNormalised(
            WritableRaster raster, float[][] accum, float[] weights, int chunkW, int chunkH) {
        int bands = raster.getNumBands();
        int[] out = new int[chunkW * chunkH];
        for (int b = 0; b < bands; b++) {
            int max = (1 << raster.getSampleModel().getSampleSize(b)) - 1;
            float[] acc = accum[b];
            // Uncovered pixels must not be written at all, or they would overwrite the background.
            // Reading the existing samples back is what lets one setSamples call carry both.
            raster.getSamples(0, 0, chunkW, chunkH, b, out);
            for (int i = 0; i < out.length; i++) {
                if (weights[i] > 0) {
                    out[i] = Math.max(0, Math.min(max, Math.round(acc[i] / weights[i])));
                }
            }
            raster.setSamples(0, 0, chunkW, chunkH, b, out);
        }
    }

    /**
     * Create an output buffer with the correct type and background color.
     */
    private BufferedImage createOutputBuffer(int width, int height) {
        BufferedImage img;

        if (isRGB) {
            // Use TYPE_3BYTE_BGR to match the source tile format (JAI camera output).
            // TYPE_INT_RGB causes ClassCastException in QuPath's
            // PyramidGeneratingImageServer when it resizes tiles for pyramid
            // levels -- BufferedImageTools.resize creates a compatible raster
            // from the source, and the INT vs BYTE transfer types are
            // incompatible in getSamples/setSamples.
            img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            if (whiteBackground) {
                Graphics2D g = img.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.dispose();
            }
        } else if (bitDepth > 8) {
            // 16-bit grayscale -- initialized to 0 (black) by default
            img = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        } else {
            // 8-bit grayscale -- initialized to 0 (black) by default
            img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        }

        return img;
    }
}
