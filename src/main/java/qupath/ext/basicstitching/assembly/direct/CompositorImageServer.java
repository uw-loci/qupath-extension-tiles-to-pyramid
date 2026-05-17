package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.TileRequestManager;
import qupath.lib.regions.RegionRequest;

/**
 * A read-only {@link ImageServer} backed by a {@link ChunkCompositor} and
 * {@link TileSpatialIndex} instead of {@code SparseImageServer}.
 * <p>
 * This enables the existing {@code OMEPyramidWriter} and {@code OMEZarrWriter}
 * to write large stitched images without OOM, because:
 * <ul>
 *   <li>Only 8 tile files are open at a time (bounded reader pool)</li>
 *   <li>Tile lookup is O(1) via spatial index (not O(N) linear scan)</li>
 *   <li>Each readRegion call composites on demand from source tiles</li>
 * </ul>
 * <p>
 * Memory usage: ~30 MB steady state (reader pool + one output buffer)
 * regardless of total tile count.
 */
public class CompositorImageServer implements ImageServer<BufferedImage> {

    private static final Logger logger = LoggerFactory.getLogger(CompositorImageServer.class);

    private final ChunkCompositor compositor;
    private final TileReaderPool readerPool;
    private final ImageServerMetadata metadata;
    private final boolean isRGB;
    private final String id;

    // Set true once close() has been called. Once true, readRegion() returns an
    // empty tile instead of routing into the compositor / reader pool. Without
    // this gate, downsample-level prefetch tasks spawned by QuPath's
    // PyramidGeneratingImageServer keep arriving after writeSeries returns, race
    // past close() / readerPool close, and try to open new ImageReaders for tile
    // files that the calling workflow has already moved or deleted -- producing
    // thousands of "Cannot create ImageInputStream for: ..." warnings during
    // multi-angle stitching cleanup.
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a compositor-backed ImageServer.
     *
     * @param compositor Compositor for generating pixel data on demand
     * @param readerPool Reader pool (caller retains ownership; closed externally)
     * @param imageWidth Full image width in pixels
     * @param imageHeight Full image height in pixels
     * @param nChannels Number of channels (3 for RGB, 1 for grayscale)
     * @param isRGB Whether the image is RGB
     * @param bitDepth Bits per sample (8 or 16)
     * @param pixelSizeMicrons Pixel size for metadata
     * @param zSpacingMicrons Z-spacing for metadata
     */
    public CompositorImageServer(
            ChunkCompositor compositor,
            TileReaderPool readerPool,
            int imageWidth,
            int imageHeight,
            int nChannels,
            boolean isRGB,
            int bitDepth,
            double pixelSizeMicrons,
            double zSpacingMicrons) {
        this.compositor = compositor;
        this.readerPool = readerPool;
        this.isRGB = isRGB;
        this.id = "compositor-" + UUID.randomUUID();

        // Build metadata
        PixelType pixelType = (bitDepth > 8) ? PixelType.UINT16 : PixelType.UINT8;

        List<ImageChannel> channels;
        if (isRGB) {
            channels = ImageChannel.getDefaultRGBChannels();
        } else if (nChannels == 1) {
            channels = List.of(ImageChannel.getInstance("Channel 0", null));
        } else {
            // Multi-channel non-RGB
            var chList = new java.util.ArrayList<ImageChannel>();
            for (int i = 0; i < nChannels; i++) {
                chList.add(ImageChannel.getInstance("Channel " + i, null));
            }
            channels = chList;
        }

        var builder = new ImageServerMetadata.Builder()
                .width(imageWidth)
                .height(imageHeight)
                .pixelType(pixelType)
                .rgb(isRGB)
                .channels(channels)
                .preferredTileSize(512, 512);

        if (pixelSizeMicrons > 0) {
            builder.pixelSizeMicrons(pixelSizeMicrons, pixelSizeMicrons);
        }
        if (zSpacingMicrons > 0) {
            builder.zSpacingMicrons(zSpacingMicrons);
        }

        this.metadata = builder.build();

        logger.info(
                "CompositorImageServer created: {}x{}, {} channels, {} bit, RGB={}",
                imageWidth,
                imageHeight,
                nChannels,
                bitDepth,
                isRGB);
    }

    // --- Core pixel data method ---

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        // RegionRequest contract: x, y, w, h are in FULL-RESOLUTION pixel coordinates.
        // The downsample indicates the desired output resolution.
        // So for downsample=4, region (0, 0, 2048, 2048) means:
        //   "read the 2048x2048 full-res region and return it at 512x512"
        int x = request.getX();
        int y = request.getY();
        int w = request.getWidth();
        int h = request.getHeight();
        double downsample = request.getDownsample();

        // Clamp to image bounds
        int srcW = Math.min(w, getWidth() - x);
        int srcH = Math.min(h, getHeight() - y);

        if (srcW <= 0 || srcH <= 0) {
            int outW = (int) Math.max(1, Math.round(w / downsample));
            int outH = (int) Math.max(1, Math.round(h / downsample));
            return createEmptyTile(outW, outH);
        }

        // If close() has run, the source tile directory may have already been
        // moved / deleted by the surrounding workflow. Skip the I/O path and
        // hand back an empty tile so stragglers don't log read failures.
        if (closed.get()) {
            int outW = (int) Math.max(1, Math.round(srcW / downsample));
            int outH = (int) Math.max(1, Math.round(srcH / downsample));
            return createEmptyTile(outW, outH);
        }

        // For level-0 reads (downsample=1), composite directly
        if (downsample == 1.0) {
            return compositor.compositeChunk(x, y, srcW, srcH);
        }

        // For downsampled requests: read full-res region, then scale down
        BufferedImage fullRes = compositor.compositeChunk(x, y, srcW, srcH);

        // Scale to requested output size
        int outW = (int) Math.max(1, Math.round(srcW / downsample));
        int outH = (int) Math.max(1, Math.round(srcH / downsample));

        if (outW == fullRes.getWidth() && outH == fullRes.getHeight()) {
            return fullRes;
        }

        BufferedImage scaled = new BufferedImage(outW, outH, fullRes.getType());
        var g = scaled.createGraphics();
        try {
            g.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(fullRes, 0, 0, outW, outH, null);
        } finally {
            g.dispose();
        }

        return scaled;
    }

    @Override
    public BufferedImage readRegion(double downsample, int x, int y, int width, int height, int z, int t)
            throws IOException {
        return readRegion(RegionRequest.createInstance(getPath(), downsample, x, y, width, height, z, t));
    }

    private BufferedImage createEmptyTile(int width, int height) {
        if (isRGB) {
            var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.dispose();
            return img;
        }
        return new BufferedImage(
                width,
                height,
                metadata.getPixelType() == PixelType.UINT16
                        ? BufferedImage.TYPE_USHORT_GRAY
                        : BufferedImage.TYPE_BYTE_GRAY);
    }

    // --- Metadata methods ---

    @Override
    public String getPath() {
        return id;
    }

    @Override
    public Collection<URI> getURIs() {
        return Collections.emptyList();
    }

    @Override
    public String getServerType() {
        return "Direct compositor";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    public ImageServerMetadata getMetadata() {
        return metadata;
    }

    @Override
    public void setMetadata(ImageServerMetadata metadata) {
        // Read-only server -- metadata is fixed at construction
        logger.warn("setMetadata() called on read-only CompositorImageServer (ignored)");
    }

    @Override
    public Class<BufferedImage> getImageClass() {
        return BufferedImage.class;
    }

    @Override
    public boolean isRGB() {
        return isRGB;
    }

    @Override
    public PixelType getPixelType() {
        return metadata.getPixelType();
    }

    // --- Dimension methods ---

    @Override
    public int getWidth() {
        return metadata.getWidth();
    }

    @Override
    public int getHeight() {
        return metadata.getHeight();
    }

    @Override
    public int nChannels() {
        return metadata.getChannels().size();
    }

    @Override
    public ImageChannel getChannel(int channel) {
        return metadata.getChannels().get(channel);
    }

    @Override
    public int nZSlices() {
        return metadata.getSizeZ();
    }

    @Override
    public int nTimepoints() {
        return metadata.getSizeT();
    }

    // --- Resolution methods ---

    @Override
    public double[] getPreferredDownsamples() {
        return metadata.getPreferredDownsamplesArray();
    }

    @Override
    public int nResolutions() {
        return metadata.nLevels();
    }

    @Override
    public double getDownsampleForResolution(int level) {
        return metadata.getDownsampleForLevel(level);
    }

    // --- Unsupported / trivial methods ---

    @Override
    public BufferedImage getDefaultThumbnail(int z, int t) throws IOException {
        // Generate a small thumbnail by reading a heavily downsampled region
        int thumbW = 256;
        int thumbH = (int) Math.round(256.0 * getHeight() / getWidth());
        double ds = (double) getWidth() / thumbW;
        return readRegion(RegionRequest.createInstance(getPath(), ds, 0, 0, getWidth(), getHeight(), z, t));
    }

    @Override
    public ServerBuilder<BufferedImage> getBuilder() {
        // Non-persistent server -- cannot be serialized/reconstructed
        return null;
    }

    @Override
    public TileRequestManager getTileRequestManager() {
        // Return null -- ImageServers.pyramidalize() will create its own
        return null;
    }

    @Override
    public BufferedImage getCachedTile(TileRequest tile) {
        // No caching -- always composite on demand
        return null;
    }

    @Override
    public List<String> getAssociatedImageList() {
        return Collections.emptyList();
    }

    @Override
    public BufferedImage getAssociatedImage(String name) {
        return null;
    }

    @Override
    public boolean isEmptyRegion(RegionRequest request) {
        return false;
    }

    @Override
    public void close() throws Exception {
        // Reader pool is owned by the caller (DirectTileStitcher)
        // Do not close it here
        closed.set(true);
        logger.debug("CompositorImageServer closed");
    }
}
