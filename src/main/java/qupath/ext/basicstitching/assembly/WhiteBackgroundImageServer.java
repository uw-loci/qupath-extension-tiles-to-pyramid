package qupath.ext.basicstitching.assembly;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.TileRequestManager;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * A wrapper ImageServer that fills empty regions with white for RGB/color images.
 * <p>
 * This server wraps a SparseImageServer (or any ImageServer with known covered regions)
 * and fills any pixels not covered by actual tile data with white instead
 * of the default black (0,0,0).
 * <p>
 * For non-RGB images (fluorescence, etc.), pixels are left as zeros to preserve
 * the expected black background.
 * <p>
 * This is particularly useful for stitched brightfield images where gaps in tile
 * coverage should appear as white slide background rather than black.
 * <p>
 * <b>Bit Depth Handling:</b> The implementation correctly handles different bit depths:
 * <ul>
 *   <li>8-bit RGB: fills with 255</li>
 *   <li>12-bit data in 16-bit storage: fills with 4095 (if metadata specifies maxValue)</li>
 *   <li>14-bit data in 16-bit storage: fills with 16383 (if metadata specifies maxValue)</li>
 *   <li>16-bit RGB: fills with 65535</li>
 * </ul>
 * The actual max value is determined from the server's metadata when available,
 * falling back to the storage bit depth's maximum when not specified.
 */
public class WhiteBackgroundImageServer implements ImageServer<BufferedImage> {

    private static final Logger logger = LoggerFactory.getLogger(WhiteBackgroundImageServer.class);

    private final ImageServer<BufferedImage> wrappedServer;
    private final Collection<ImageRegion> coveredRegions;
    private final boolean applyWhiteBackground;
    private final double maxValue; // The maximum pixel value for this image (handles 12/14-bit in 16-bit space)

    /**
     * Create a WhiteBackgroundImageServer wrapping the specified server.
     * <p>
     * White background fill is applied if the wrapped server is:
     * <ul>
     *   <li>Flagged as RGB (standard 8-bit RGB), OR</li>
     *   <li>Has exactly 3 channels (likely a color image stored at higher bit depth)</li>
     * </ul>
     *
     * @param server The server to wrap (typically a SparseImageServer)
     * @param coveredRegions The regions that contain actual tile data
     */
    public WhiteBackgroundImageServer(ImageServer<BufferedImage> server, Collection<ImageRegion> coveredRegions) {
        this.wrappedServer = server;
        this.coveredRegions = coveredRegions != null ? coveredRegions : Collections.emptyList();

        // Determine if this is a color image that should have white background
        // isRGB() only returns true for 8-bit RGB, so also check for 3-channel images
        boolean isColorImage = server.isRGB() || (server.nChannels() == 3 && !isFluorescenceImage(server));
        this.applyWhiteBackground = isColorImage;

        // Get the maximum pixel value, respecting effective bit depth (12-bit, 14-bit, etc.)
        // This handles cameras that capture 12/14-bit data but store in 16-bit containers
        Number maxVal = server.getMetadata().getMaxValue();
        this.maxValue = maxVal != null ? maxVal.doubleValue() : getDefaultMaxValue(server);

        if (applyWhiteBackground) {
            logger.debug("WhiteBackgroundImageServer: Will apply white background for color image");
            logger.debug(
                    "  - isRGB: {}, nChannels: {}, pixelType: {}, maxValue: {}",
                    server.isRGB(),
                    server.nChannels(),
                    server.getMetadata().getPixelType(),
                    this.maxValue);
            logger.debug("  - Covered regions: {}", this.coveredRegions.size());
        } else {
            logger.debug(
                    "WhiteBackgroundImageServer: Skipping white background for non-color image (pass-through mode)");
        }
    }

    /**
     * Heuristic to detect fluorescence images that should have black background.
     * Checks channel names for common fluorescence indicators.
     */
    private static boolean isFluorescenceImage(ImageServer<BufferedImage> server) {
        var channels = server.getMetadata().getChannels();
        if (channels == null || channels.isEmpty()) {
            return false;
        }

        // Common fluorescence channel patterns
        for (var channel : channels) {
            String name = channel.getName().toLowerCase();
            if (name.contains("dapi")
                    || name.contains("fitc")
                    || name.contains("cy")
                    || name.contains("alexa")
                    || name.contains("rhodamine")
                    || name.contains("gfp")
                    || name.contains("rfp")
                    || name.contains("yfp")
                    || name.contains("cfp")
                    || name.contains("hoechst")
                    || name.contains("sytox")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get default max value based on pixel type when metadata doesn't specify.
     */
    private static double getDefaultMaxValue(ImageServer<BufferedImage> server) {
        var pixelType = server.getMetadata().getPixelType();
        if (pixelType != null) {
            return pixelType.getUpperBound().doubleValue();
        }
        // Fallback for 8-bit
        return 255.0;
    }

    /**
     * Create a WhiteBackgroundImageServer without explicit region tracking.
     * <p>
     * This constructor uses a simpler approach that fills the entire tile with white
     * first, then reads from the underlying server. This works well when the underlying
     * server (like SparseImageServer) composites tiles over the white background.
     * <p>
     * Note: This approach requires the wrapped server to properly handle tile compositing.
     *
     * @param server The server to wrap
     */
    public WhiteBackgroundImageServer(ImageServer<BufferedImage> server) {
        this(server, null);
    }

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        if (!applyWhiteBackground) {
            // Non-RGB: pass through unchanged
            return wrappedServer.readRegion(request);
        }

        // Read the tile from the wrapped server
        BufferedImage img = wrappedServer.readRegion(request);

        if (img == null) {
            return null;
        }

        // For RGB images, replace black background with white
        // We need to identify pixels that are:
        // 1. Pure black (0,0,0)
        // 2. Not covered by any tile region
        return fillUncoveredWithWhite(img, request);
    }

    /**
     * Fill uncovered regions of the image with white.
     * <p>
     * If covered regions are provided, only fills pixels outside those regions.
     * Otherwise, fills all pure black pixels (simple but may affect intentional black).
     */
    private BufferedImage fillUncoveredWithWhite(BufferedImage img, RegionRequest request) {
        int width = img.getWidth();
        int height = img.getHeight();
        double downsample = request.getDownsample();

        // Request bounds in image coordinates
        int reqX = request.getX();
        int reqY = request.getY();
        int reqWidth = request.getWidth();
        int reqHeight = request.getHeight();

        // If we have covered regions, use them for precise filling
        if (!coveredRegions.isEmpty()) {
            return fillWithRegionTracking(img, reqX, reqY, reqWidth, reqHeight, downsample, width, height);
        } else {
            // Fallback: fill all pure black pixels
            // This is less precise but works when regions aren't tracked
            return fillBlackPixelsWithWhite(img);
        }
    }

    /**
     * Fill pixels outside covered regions with white.
     * <p>
     * This is the most efficient approach as it only processes pixels that need changing.
     * Properly handles different bit depths (8-bit, 16-bit, etc.) by using the
     * maximum value for each sample based on the color model.
     */
    private BufferedImage fillWithRegionTracking(
            BufferedImage img,
            int reqX,
            int reqY,
            int reqWidth,
            int reqHeight,
            double downsample,
            int outWidth,
            int outHeight) {
        // Create a coverage mask for the requested region
        boolean[][] covered = new boolean[outHeight][outWidth];

        // Mark pixels that are covered by tile regions
        for (ImageRegion region : coveredRegions) {
            // Check if this region intersects our request
            if (!region.intersects(reqX, reqY, reqWidth, reqHeight)) {
                continue;
            }

            // Calculate the intersection in output image coordinates
            int overlapX1 = Math.max(reqX, region.getX());
            int overlapY1 = Math.max(reqY, region.getY());
            int overlapX2 = Math.min(reqX + reqWidth, region.getX() + region.getWidth());
            int overlapY2 = Math.min(reqY + reqHeight, region.getY() + region.getHeight());

            // Convert to output pixel coordinates
            int pixelX1 = (int) Math.round((overlapX1 - reqX) / downsample);
            int pixelY1 = (int) Math.round((overlapY1 - reqY) / downsample);
            int pixelX2 = (int) Math.round((overlapX2 - reqX) / downsample);
            int pixelY2 = (int) Math.round((overlapY2 - reqY) / downsample);

            // Clamp to output bounds
            pixelX1 = Math.max(0, Math.min(outWidth, pixelX1));
            pixelY1 = Math.max(0, Math.min(outHeight, pixelY1));
            pixelX2 = Math.max(0, Math.min(outWidth, pixelX2));
            pixelY2 = Math.max(0, Math.min(outHeight, pixelY2));

            // Mark covered pixels
            for (int y = pixelY1; y < pixelY2; y++) {
                for (int x = pixelX1; x < pixelX2; x++) {
                    covered[y][x] = true;
                }
            }
        }

        // Fill uncovered pixels with white, handling different bit depths
        // Use the pre-calculated maxValue which respects effective bit depth (12/14-bit in 16-bit storage)
        var raster = img.getRaster();
        var sampleModel = raster.getSampleModel();
        int numBands = sampleModel.getNumBands();

        // Use the maxValue from metadata (handles 12-bit, 14-bit stored in 16-bit space)
        // This is set during construction from server.getMetadata().getMaxValue()
        double[] whiteValues = new double[numBands];
        for (int band = 0; band < numBands; band++) {
            whiteValues[band] = this.maxValue;
        }

        logger.trace(
                "Filling uncovered regions with white value {} for {} bands (effective bit depth from metadata)",
                this.maxValue,
                numBands);

        // Fill uncovered pixels
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                if (!covered[y][x]) {
                    raster.setPixel(x, y, whiteValues);
                }
            }
        }

        return img;
    }

    /**
     * Simple fallback: fill all pure black (0,0,0) pixels with white.
     * <p>
     * This is less precise and may incorrectly fill intentional black pixels,
     * but works when tile regions aren't available.
     * <p>
     * Uses the maxValue from metadata for proper bit depth handling.
     */
    private BufferedImage fillBlackPixelsWithWhite(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();

        var raster = img.getRaster();
        var sampleModel = raster.getSampleModel();
        int numBands = sampleModel.getNumBands();

        // Create white value array using metadata's maxValue
        double[] whiteValues = new double[numBands];
        double[] pixelValues = new double[numBands];
        for (int band = 0; band < numBands; band++) {
            whiteValues[band] = this.maxValue;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, y, pixelValues);
                // Check if pixel is pure black (all channels are 0)
                boolean isBlack = true;
                for (int band = 0; band < numBands; band++) {
                    if (pixelValues[band] != 0) {
                        isBlack = false;
                        break;
                    }
                }
                if (isBlack) {
                    raster.setPixel(x, y, whiteValues);
                }
            }
        }

        return img;
    }

    // Delegate all other methods to the wrapped server

    @Override
    public String getPath() {
        return wrappedServer.getPath();
    }

    @Override
    public Collection<URI> getURIs() {
        return wrappedServer.getURIs();
    }

    @Override
    public String getServerType() {
        return wrappedServer.getServerType() + " (white background)";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return wrappedServer.getOriginalMetadata();
    }

    @Override
    public ImageServerMetadata getMetadata() {
        return wrappedServer.getMetadata();
    }

    @Override
    public void setMetadata(ImageServerMetadata metadata) {
        wrappedServer.setMetadata(metadata);
    }

    @Override
    public BufferedImage getDefaultThumbnail(int z, int t) throws IOException {
        BufferedImage thumbnail = wrappedServer.getDefaultThumbnail(z, t);
        if (applyWhiteBackground && thumbnail != null) {
            return fillBlackPixelsWithWhite(thumbnail);
        }
        return thumbnail;
    }

    @Override
    public Class<BufferedImage> getImageClass() {
        return wrappedServer.getImageClass();
    }

    @Override
    public boolean isRGB() {
        return wrappedServer.isRGB();
    }

    @Override
    public double[] getPreferredDownsamples() {
        return wrappedServer.getPreferredDownsamples();
    }

    @Override
    public int nResolutions() {
        return wrappedServer.nResolutions();
    }

    @Override
    public double getDownsampleForResolution(int level) {
        return wrappedServer.getDownsampleForResolution(level);
    }

    @Override
    public int getWidth() {
        return wrappedServer.getWidth();
    }

    @Override
    public int getHeight() {
        return wrappedServer.getHeight();
    }

    @Override
    public int nChannels() {
        return wrappedServer.nChannels();
    }

    @Override
    public ImageChannel getChannel(int channel) {
        return wrappedServer.getChannel(channel);
    }

    @Override
    public int nZSlices() {
        return wrappedServer.nZSlices();
    }

    @Override
    public int nTimepoints() {
        return wrappedServer.nTimepoints();
    }

    @Override
    public BufferedImage readRegion(double downsample, int x, int y, int width, int height, int z, int t)
            throws IOException {
        return readRegion(RegionRequest.createInstance(getPath(), downsample, x, y, width, height, z, t));
    }

    @Override
    public ServerBuilder<BufferedImage> getBuilder() {
        // Note: This doesn't preserve the white background wrapping for serialization
        // If needed, create a custom ServerBuilder
        return wrappedServer.getBuilder();
    }

    @Override
    public TileRequestManager getTileRequestManager() {
        return wrappedServer.getTileRequestManager();
    }

    @Override
    public PixelType getPixelType() {
        return wrappedServer.getPixelType();
    }

    @Override
    public BufferedImage getCachedTile(TileRequest tile) {
        return wrappedServer.getCachedTile(tile);
    }

    @Override
    public List<String> getAssociatedImageList() {
        return wrappedServer.getAssociatedImageList();
    }

    @Override
    public BufferedImage getAssociatedImage(String name) {
        return wrappedServer.getAssociatedImage(name);
    }

    @Override
    public boolean isEmptyRegion(RegionRequest request) {
        return wrappedServer.isEmptyRegion(request);
    }

    @Override
    public void close() throws Exception {
        wrappedServer.close();
    }
}
