package qupath.ext.basicstitching.assembly.direct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.TileMapping;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

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
    public ChunkCompositor(TileReaderPool readerPool, TileSpatialIndex spatialIndex,
                           BlendStrategy blendStrategy, boolean whiteBackground,
                           boolean isRGB, int bitDepth) {
        this.readerPool = readerPool;
        this.spatialIndex = spatialIndex;
        this.blendStrategy = blendStrategy;
        this.whiteBackground = whiteBackground;
        this.isRGB = isRGB;
        this.bitDepth = bitDepth;
    }

    /**
     * Composite source tiles into an output chunk buffer.
     * Coordinates are in origin-translated space (0-based).
     *
     * @param chunkX Chunk X position in the full image
     * @param chunkY Chunk Y position in the full image
     * @param chunkW Chunk width (may be less than chunk size at image edge)
     * @param chunkH Chunk height (may be less than chunk size at image edge)
     * @return Composited BufferedImage
     */
    public BufferedImage compositeChunk(int chunkX, int chunkY, int chunkW, int chunkH) throws IOException {
        // Query spatial index for contributing tiles
        List<TileMapping> tiles = spatialIndex.query(chunkX, chunkY, chunkW, chunkH);

        // Create output buffer with appropriate type and background
        BufferedImage output = createOutputBuffer(chunkW, chunkH);

        int originX = spatialIndex.getOriginX();
        int originY = spatialIndex.getOriginY();

        // Composite each contributing tile
        for (TileMapping tile : tiles) {
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

                // Draw onto output (Java2D handles color model conversion)
                Graphics2D g = output.createGraphics();
                try {
                    g.drawImage(tileData, dstX, dstY, null);
                } finally {
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
     * Create an output buffer with the correct type and background color.
     */
    private BufferedImage createOutputBuffer(int width, int height) {
        BufferedImage img;

        if (isRGB) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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
