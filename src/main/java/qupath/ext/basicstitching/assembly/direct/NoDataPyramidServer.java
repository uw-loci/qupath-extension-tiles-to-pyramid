package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/**
 * The pyramid {@link DirectTiffOutputWriter} writes for a non-RGB image: level 0 is the source at
 * full resolution, and each coarser level is made from the one before it by averaging on the
 * raster at full bit depth, leaving zeros out of the mean.
 *
 * <p>Replaces QuPath's {@code ImageServers.pyramidalizeTiled} for these images. That builds its
 * first level from a downsampled read of the source and every further level with QuPath's resize,
 * so how the levels are made depended on the source; here every level is made the same way.
 * Zero is the empty canvas of a mosaic (no tile landed there): counting it pulled every
 * zoomed-out pixel along the mosaic's edges and gaps toward black, and put a row of spikes at
 * fixed fractions of the background into QuPath's histogram, which is built from the smallest
 * level (SHG MIP, 2026-10-29). Full resolution is unchanged.
 */
final class NoDataPyramidServer extends AbstractTileableImageServer {

    private final ImageServer<BufferedImage> source;
    private final ImageServerMetadata metadata;

    NoDataPyramidServer(ImageServer<BufferedImage> source, int tileWidth, int tileHeight, double... downsamples) {
        this.source = source;
        this.metadata = new ImageServerMetadata.Builder(source.getMetadata())
                .preferredTileSize(tileWidth, tileHeight)
                .levelsFromDownsamples(downsamples)
                .build();
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        RegionRequest request = tileRequest.getRegionRequest();
        int level = tileRequest.getLevel();
        if (level == 0) {
            return source.readRegion(request.updatePath(source.getPath()));
        }
        // The same region from the level before, through this server, so its tiles are cached.
        BufferedImage previous = readRegion(request.updateDownsample(getDownsampleForResolution(level - 1)));
        if (previous == null) {
            return getEmptyTile(tileRequest.getTileWidth(), tileRequest.getTileHeight());
        }
        return CompositorImageServer.areaAverage(
                previous, tileRequest.getTileWidth(), tileRequest.getTileHeight(), true);
    }

    @Override
    public Collection<URI> getURIs() {
        return source.getURIs();
    }

    @Override
    public String getServerType() {
        return "Generated pyramid, zero as no data (" + source.getServerType() + ")";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    protected String createID() {
        return getClass().getSimpleName() + ":" + source.getPath();
    }
}
