package qupath.ext.basicstitching.assembly;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.SparseImageServer;
import qupath.lib.regions.ImageRegion;

/**
 * Assembles a list of tile mappings into a SparseImageServer, applying metadata for pixel and z-spacing.
 * <p>
 * For RGB images, the assembled server is automatically wrapped with {@link WhiteBackgroundImageServer}
 * to ensure empty regions appear white instead of black.
 */
public class ImageAssembler {
    private static final Logger logger = LoggerFactory.getLogger(ImageAssembler.class);

    /**
     * Build an ImageServer from the provided list of TileMapping objects.
     * <p>
     * For RGB images, the returned server will automatically fill empty regions with white.
     * For non-RGB images (fluorescence, etc.), empty regions remain black as expected.
     *
     * @param mappings Tiles (with file, region, subdirName)
     * @param pixelSizeMicrons Pixel size for the final image (in microns)
     * @param zSpacingMicrons Z spacing for the final image (in microns)
     * @return ImageServer with correct metadata, or null on failure
     */
    public static ImageServer<BufferedImage> assemble(
            List<TileMapping> mappings, double pixelSizeMicrons, double zSpacingMicrons) throws IOException {
        return assemble(mappings, pixelSizeMicrons, zSpacingMicrons, true);
    }

    /**
     * Build an ImageServer from the provided list of TileMapping objects.
     *
     * @param mappings Tiles (with file, region, subdirName)
     * @param pixelSizeMicrons Pixel size for the final image (in microns)
     * @param zSpacingMicrons Z spacing for the final image (in microns)
     * @param whiteBackgroundForRGB If true, RGB images will have white background for empty regions
     * @return ImageServer with correct metadata, or null on failure
     */
    public static ImageServer<BufferedImage> assemble(
            List<TileMapping> mappings, double pixelSizeMicrons, double zSpacingMicrons, boolean whiteBackgroundForRGB)
            throws IOException {
        if (mappings == null || mappings.isEmpty()) {
            logger.error("No tile mappings provided to assembler.");
            return null;
        }

        SparseImageServer.Builder builder = new SparseImageServer.Builder();
        List<ImageRegion> coveredRegions = new ArrayList<>();
        int nTiles = 0;

        for (TileMapping mapping : mappings) {
            File file = mapping.file;
            ImageRegion region = mapping.region;
            if (file == null || region == null) {
                logger.warn("Null file or region in mapping, skipping...");
                continue;
            }
            var serverBuilders = ImageServerProvider.getPreferredUriImageSupport(
                            BufferedImage.class, file.toURI().toString())
                    .getBuilders();
            if (serverBuilders.isEmpty()) {
                logger.error("No server builders for file: {}", file.getName());
                continue;
            }
            builder.jsonRegion(region, 1.0, serverBuilders.get(0));
            coveredRegions.add(region);
            nTiles++;
        }

        logger.info("Assembled {} tiles into sparse image server.", nTiles);

        // Calculate the origin offset (minimum X and Y across all regions)
        // SparseImageServer uses this internally to shift its coordinate system
        // so that (0,0) represents the top-left of the bounding box
        int originX = Integer.MAX_VALUE;
        int originY = Integer.MAX_VALUE;
        for (ImageRegion region : coveredRegions) {
            if (region.getX() < originX) originX = region.getX();
            if (region.getY() < originY) originY = region.getY();
        }
        // Handle edge case of no regions
        if (originX == Integer.MAX_VALUE) originX = 0;
        if (originY == Integer.MAX_VALUE) originY = 0;

        logger.debug("Calculated origin offset: ({}, {})", originX, originY);

        SparseImageServer sparseServer = builder.build();

        // Update server metadata
        ImageServerMetadata meta = new ImageServerMetadata.Builder(sparseServer.getMetadata())
                .pixelSizeMicrons(pixelSizeMicrons, pixelSizeMicrons)
                .zSpacingMicrons(zSpacingMicrons)
                .build();
        sparseServer.setMetadata(meta);

        // For color images (RGB or 3-channel), wrap with white background server
        // WhiteBackgroundImageServer handles the detection internally, including:
        // - Standard 8-bit RGB (isRGB() == true)
        // - Higher bit-depth 3-channel images (12/14-bit stored in 16-bit)
        // - Excludes fluorescence images based on channel names
        if (whiteBackgroundForRGB) {
            // Let WhiteBackgroundImageServer decide if white background should be applied
            // based on its internal color image detection logic
            logger.info("Checking if white background should be applied ({} covered regions)", coveredRegions.size());

            // CRITICAL: Translate covered regions to match SparseImageServer's coordinate space
            // SparseImageServer shifts all coordinates by (originX, originY) so its (0,0) is the
            // top-left of the bounding box. We must apply the same transformation to coveredRegions.
            List<ImageRegion> translatedRegions = new ArrayList<>();
            for (ImageRegion region : coveredRegions) {
                translatedRegions.add(ImageRegion.createInstance(
                        region.getX() - originX,
                        region.getY() - originY,
                        region.getWidth(),
                        region.getHeight(),
                        region.getZ(),
                        region.getT()));
            }
            logger.debug("Translated {} regions by offset ({}, {})", translatedRegions.size(), originX, originY);

            WhiteBackgroundImageServer wrappedServer = new WhiteBackgroundImageServer(sparseServer, translatedRegions);
            // Only return wrapped server if it will actually apply white background
            // Otherwise return the raw server to avoid unnecessary wrapping overhead
            if (sparseServer.isRGB() || sparseServer.nChannels() == 3) {
                logger.info(
                        "Color image detected (isRGB={}, nChannels={}) - using white background wrapper",
                        sparseServer.isRGB(),
                        sparseServer.nChannels());
                return wrappedServer;
            }
        }

        return sparseServer;
    }

    /**
     * Build a raw SparseImageServer without white background wrapping.
     * <p>
     * Use this method when you need direct access to the SparseImageServer
     * or when you want to apply custom post-processing.
     *
     * @param mappings Tiles (with file, region, subdirName)
     * @param pixelSizeMicrons Pixel size for the final image (in microns)
     * @param zSpacingMicrons Z spacing for the final image (in microns)
     * @return SparseImageServer with correct metadata, or null on failure
     */
    public static SparseImageServer assembleRaw(
            List<TileMapping> mappings, double pixelSizeMicrons, double zSpacingMicrons) throws IOException {
        ImageServer<BufferedImage> server = assemble(mappings, pixelSizeMicrons, zSpacingMicrons, false);
        if (server instanceof SparseImageServer) {
            return (SparseImageServer) server;
        }
        return null;
    }
}
