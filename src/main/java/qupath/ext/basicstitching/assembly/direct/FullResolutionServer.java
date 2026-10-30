package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import qupath.lib.images.servers.AbstractImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.regions.RegionRequest;

/**
 * Presents only the full-resolution level of another image, and makes every downsampled read
 * from that level by averaging on the raster.
 *
 * <p>For rebuilding a pyramid whose stored downsampled levels are wrong (see
 * {@link PyramidRebuilder}): a downsampled read from the wrapped file would return the damaged
 * level, so the levels are dropped from the metadata and every read starts from full resolution.
 */
final class FullResolutionServer extends AbstractImageServer<BufferedImage> {

    private final ImageServer<BufferedImage> base;
    private final ImageServerMetadata metadata;

    FullResolutionServer(ImageServer<BufferedImage> base) {
        super(BufferedImage.class);
        this.base = base;
        this.metadata = new ImageServerMetadata.Builder(base.getMetadata())
                .levelsFromDownsamples(1.0)
                .build();
    }

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        double downsample = request.getDownsample();
        int x = request.getX();
        int y = request.getY();
        int w = Math.min(request.getWidth(), getWidth() - x);
        int h = Math.min(request.getHeight(), getHeight() - y);
        RegionRequest full = RegionRequest.createInstance(
                base.getPath(), 1.0, x, y, Math.max(1, w), Math.max(1, h), request.getZ(), request.getT());
        BufferedImage img = base.readRegion(full);
        if (downsample == 1.0 || img == null) {
            return img;
        }
        int outW = (int) Math.max(1, Math.round(img.getWidth() / downsample));
        int outH = (int) Math.max(1, Math.round(img.getHeight() / downsample));
        if (outW == img.getWidth() && outH == img.getHeight()) {
            return img;
        }
        return CompositorImageServer.areaAverage(img, outW, outH);
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    public Collection<URI> getURIs() {
        return base.getURIs();
    }

    @Override
    public String getServerType() {
        return "Full resolution of " + base.getServerType();
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    protected String createID() {
        return base.getPath() + "#full-resolution-only";
    }

    @Override
    public void close() throws Exception {
        base.close();
    }
}
