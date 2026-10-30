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
    private final boolean whiteBackground;
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
     * @param whiteBackground Whether areas no tile reaches are white (RGB only; others are 0)
     * @param bitDepth Bits per sample (8 or 16)
     * @param zCount Number of z-slices (>= 1)
     * @param tCount Number of timepoints (>= 1)
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
            boolean whiteBackground,
            int bitDepth,
            int zCount,
            int tCount,
            double pixelSizeMicrons,
            double zSpacingMicrons) {
        this.compositor = compositor;
        this.readerPool = readerPool;
        this.isRGB = isRGB;
        this.whiteBackground = whiteBackground;
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
                .sizeZ(Math.max(1, zCount))
                .sizeT(Math.max(1, tCount))
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
        int z = request.getZ();
        int t = request.getT();
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
            return compositor.compositeChunk(z, t, x, y, srcW, srcH);
        }

        // For downsampled requests: read full-res region, then scale down
        BufferedImage fullRes = compositor.compositeChunk(z, t, x, y, srcW, srcH);

        // Scale to requested output size
        int outW = (int) Math.max(1, Math.round(srcW / downsample));
        int outH = (int) Math.max(1, Math.round(srcH / downsample));

        if (outW == fullRes.getWidth() && outH == fullRes.getHeight()) {
            return fullRes;
        }

        if (!isRGB) {
            return areaAverage(fullRes, outW, outH);
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

    /**
     * Downscale a non-RGB image by averaging the source pixels each output pixel covers, on
     * the raster, so every sample keeps its full bit depth.
     *
     * <p>Not {@code Graphics2D.drawImage}: Java2D renders a {@code TYPE_USHORT_GRAY} image
     * through 8 bits, so every downsampled value came back as a multiple of 257. QuPath builds
     * the first pyramid level from this read and every coarser level from that one, so all
     * zoomed-out levels of every 16-bit stitch were posterized: an LC-PolScope retardance image
     * (0..430 counts) became 99% zeros below full resolution, and an SHG image kept 16
     * distinct values (2026-10-29).
     */
    static BufferedImage areaAverage(BufferedImage src, int outW, int outH) {
        java.awt.image.WritableRaster in = src.getRaster();
        int w = in.getWidth();
        int h = in.getHeight();
        java.awt.image.WritableRaster out = in.createCompatibleWritableRaster(outW, outH);
        int[] x0 = new int[outW];
        int[] x1 = new int[outW];
        for (int ox = 0; ox < outW; ox++) {
            x0[ox] = Math.min(w - 1, (int) ((long) ox * w / outW));
            x1[ox] = Math.max(x0[ox] + 1, Math.min(w, (int) ((long) (ox + 1) * w / outW)));
        }
        int[] y0 = new int[outH];
        int[] y1 = new int[outH];
        for (int oy = 0; oy < outH; oy++) {
            y0[oy] = Math.min(h - 1, (int) ((long) oy * h / outH));
            y1[oy] = Math.max(y0[oy] + 1, Math.min(h, (int) ((long) (oy + 1) * h / outH)));
        }
        int type = in.getDataBuffer().getDataType();
        boolean floating =
                type == java.awt.image.DataBuffer.TYPE_FLOAT || type == java.awt.image.DataBuffer.TYPE_DOUBLE;
        for (int b = 0; b < in.getNumBands(); b++) {
            double[] samples = in.getSamples(0, 0, w, h, b, (double[]) null);
            double[] result = new double[outW * outH];
            for (int oy = 0; oy < outH; oy++) {
                for (int ox = 0; ox < outW; ox++) {
                    double sum = 0;
                    for (int y = y0[oy]; y < y1[oy]; y++) {
                        int row = y * w;
                        for (int x = x0[ox]; x < x1[ox]; x++) {
                            sum += samples[row + x];
                        }
                    }
                    double mean = sum / ((y1[oy] - y0[oy]) * (x1[ox] - x0[ox]));
                    result[oy * outW + ox] = floating ? mean : Math.rint(mean);
                }
            }
            out.setSamples(0, 0, outW, outH, b, result);
        }
        return new BufferedImage(src.getColorModel(), out, src.isAlphaPremultiplied(), null);
    }

    @Override
    public BufferedImage readRegion(double downsample, int x, int y, int width, int height, int z, int t)
            throws IOException {
        return readRegion(RegionRequest.createInstance(getPath(), downsample, x, y, width, height, z, t));
    }

    private BufferedImage createEmptyTile(int width, int height) {
        if (isRGB) {
            var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            if (whiteBackground) {
                var g = img.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.dispose();
            }
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
