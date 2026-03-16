package qupath.ext.basicstitching.assembly.direct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded pool of open {@link ImageReader} instances with LRU eviction.
 * <p>
 * Keeps at most {@code maxOpen} tile files open simultaneously, evicting the
 * least-recently-used reader when the pool is full. This bounds memory usage
 * to approximately {@code maxOpen * tileSize} regardless of total tile count.
 * <p>
 * Supports sub-region reads via {@link #readRegion(File, int, int, int, int)}
 * so that only the pixels needed for a given output chunk are loaded.
 */
public class TileReaderPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TileReaderPool.class);

    private final int maxOpen;
    private final LinkedHashMap<File, ReaderEntry> cache;
    private int evictionCount = 0;

    private static class ReaderEntry {
        final ImageReader reader;
        final ImageInputStream stream;

        ReaderEntry(ImageReader reader, ImageInputStream stream) {
            this.reader = reader;
            this.stream = stream;
        }

        void close() {
            try { reader.dispose(); } catch (Exception e) { /* ignore */ }
            try { stream.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * Create a reader pool with the given maximum number of open files.
     *
     * @param maxOpen Maximum concurrent open readers (default: 8)
     */
    public TileReaderPool(int maxOpen) {
        this.maxOpen = maxOpen;
        // access-order LinkedHashMap: most recently accessed entry moves to tail
        this.cache = new LinkedHashMap<>(maxOpen + 1, 0.75f, true);
    }

    private synchronized ReaderEntry getOrCreateReader(File file) throws IOException {
        ReaderEntry entry = cache.get(file);
        if (entry != null) {
            return entry;
        }

        // Evict LRU entries until we're under capacity
        while (cache.size() >= maxOpen) {
            Iterator<Map.Entry<File, ReaderEntry>> it = cache.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<File, ReaderEntry> oldest = it.next();
                oldest.getValue().close();
                it.remove();
                evictionCount++;
            }
        }

        // Open new reader
        ImageInputStream iis = ImageIO.createImageInputStream(file);
        if (iis == null) {
            throw new IOException("Cannot create ImageInputStream for: " + file);
        }

        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            iis.close();
            throw new IOException("No ImageReader found for: " + file);
        }

        ImageReader reader = readers.next();
        // seekable (not forward-only) so we can re-read regions; ignore metadata for performance
        reader.setInput(iis, false, true);

        entry = new ReaderEntry(reader, iis);
        cache.put(file, entry);
        return entry;
    }

    /**
     * Read a sub-region from a tile file.
     *
     * @param file Source tile file
     * @param srcX X offset within the tile
     * @param srcY Y offset within the tile
     * @param width Region width to read
     * @param height Region height to read
     * @return BufferedImage containing the requested region
     */
    public synchronized BufferedImage readRegion(File file, int srcX, int srcY, int width, int height) throws IOException {
        ReaderEntry entry = getOrCreateReader(file);
        ImageReadParam param = entry.reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(srcX, srcY, width, height));
        return entry.reader.read(0, param);
    }

    /**
     * Read the full tile image.
     *
     * @param file Source tile file
     * @return Full BufferedImage
     */
    public synchronized BufferedImage readFull(File file) throws IOException {
        ReaderEntry entry = getOrCreateReader(file);
        return entry.reader.read(0);
    }

    @Override
    public synchronized void close() {
        for (ReaderEntry entry : cache.values()) {
            entry.close();
        }
        cache.clear();
        logger.debug("TileReaderPool closed. Total evictions: {}", evictionCount);
    }

    /**
     * Tile image properties read from file header only (no pixel data loaded).
     */
    public record TileDimensions(int width, int height, int nChannels, boolean isRGB, int bitDepth) {}

    /**
     * Read tile dimensions and type from the file header without loading pixel data.
     * Opens and closes the reader immediately -- does not use the pool.
     *
     * @param file Tile file to inspect
     * @return Dimensions and type information
     */
    public static TileDimensions getDimensions(File file) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) {
                throw new IOException("Cannot create ImageInputStream for: " + file);
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No ImageReader found for: " + file);
            }

            ImageReader reader = readers.next();
            reader.setInput(iis, true, true);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);

            // Read a 1x1 pixel sample to determine color model
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(0, 0, 1, 1));
            BufferedImage sample = reader.read(0, param);

            int nChannels = sample.getRaster().getNumBands();
            int bitDepth = sample.getColorModel().getComponentSize(0);
            boolean isRGB = sample.getType() == BufferedImage.TYPE_INT_RGB
                    || sample.getType() == BufferedImage.TYPE_INT_ARGB
                    || sample.getType() == BufferedImage.TYPE_3BYTE_BGR
                    || sample.getType() == BufferedImage.TYPE_4BYTE_ABGR
                    || (nChannels >= 3 && bitDepth == 8);

            reader.dispose();

            return new TileDimensions(width, height, nChannels, isRGB, bitDepth);
        }
    }
}
