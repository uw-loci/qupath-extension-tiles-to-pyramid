package qupath.ext.basicstitching.assembly;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.TileRequestManager;
import qupath.lib.regions.RegionRequest;

/**
 * Minimal multi-channel view over a collection of same-shape single-channel
 * (or same-bit-depth multi-channel) source servers. All sources must share
 * pixel dimensions, pixel type, pyramid structure, and pixel size; the
 * constructor validates the essentials and throws otherwise.
 *
 * <p>The produced server reports {@code nChannels = sum of source nChannels},
 * with channels concatenated in the order the sources are supplied. Tile
 * reads fan out to each source in turn and assemble a multi-band
 * {@link BufferedImage} via {@link BufferedImageTools#createImage}.
 *
 * <p>This is the bridge that lets {@link PyramidImageWriter#writeOMETIFF}
 * produce a single multichannel OME-TIFF pyramid from N independently
 * stitched single-channel pyramids -- the primary use case is combining
 * widefield immunofluorescence (and BF+IF) channels back into one image
 * after per-channel stitching.
 */
public class ChannelMergeImageServer implements ImageServer<BufferedImage> {

    private static final Logger logger = LoggerFactory.getLogger(ChannelMergeImageServer.class);

    private final List<ImageServer<BufferedImage>> sources;
    private final List<ImageChannel> mergedChannels;
    private final ImageServerMetadata metadata;

    /**
     * @param sources        list of image servers to merge, in output channel order. Must be non-empty
     * @param channelNames   optional list of display names, one per logical output channel. When shorter
     *                       than the total summed channel count, any missing entries fall back to the
     *                       source server's own channel name. Pass {@code null} to use source names verbatim
     */
    public ChannelMergeImageServer(List<ImageServer<BufferedImage>> sources, List<String> channelNames) {
        this(sources, channelNames, null);
    }

    /**
     * @param sources        list of image servers to merge, in output channel order. Must be non-empty
     * @param channelNames   optional list of display names, one per logical output channel. When shorter
     *                       than the total summed channel count, any missing entries fall back to the
     *                       source server's own channel name. Pass {@code null} to use source names verbatim
     * @param channelColors  optional list of packed ARGB colors, one per logical output channel. When
     *                       {@code null} or shorter than the channel count, missing entries fall back to
     *                       the source server's channel color
     */
    public ChannelMergeImageServer(List<ImageServer<BufferedImage>> sources, List<String> channelNames,
                                   List<Integer> channelColors) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("ChannelMergeImageServer requires at least one source");
        }
        this.sources = List.copyOf(sources);
        validateSourceCompatibility(this.sources);

        ImageServer<BufferedImage> first = this.sources.get(0);
        List<ImageChannel> merged = new ArrayList<>();
        int overrideIdx = 0;
        for (ImageServer<BufferedImage> s : this.sources) {
            for (int c = 0; c < s.nChannels(); c++) {
                ImageChannel srcChannel = s.getChannel(c);
                String name;
                if (channelNames != null && overrideIdx < channelNames.size()
                        && channelNames.get(overrideIdx) != null
                        && !channelNames.get(overrideIdx).isBlank()) {
                    name = channelNames.get(overrideIdx);
                } else {
                    name = srcChannel.getName();
                }
                Integer color = (channelColors != null && overrideIdx < channelColors.size()
                        && channelColors.get(overrideIdx) != null)
                        ? channelColors.get(overrideIdx)
                        : srcChannel.getColor();
                merged.add(ImageChannel.getInstance(name, color));
                overrideIdx++;
            }
        }
        this.mergedChannels = List.copyOf(merged);

        this.metadata = new ImageServerMetadata.Builder(first.getMetadata())
                .channels(this.mergedChannels)
                .rgb(false)
                .build();
    }

    private static void validateSourceCompatibility(List<ImageServer<BufferedImage>> sources) {
        ImageServer<BufferedImage> first = sources.get(0);
        int w = first.getWidth();
        int h = first.getHeight();
        PixelType pt = first.getPixelType();
        int nRes = first.nResolutions();
        for (int i = 1; i < sources.size(); i++) {
            ImageServer<BufferedImage> s = sources.get(i);
            if (s.getWidth() != w || s.getHeight() != h) {
                throw new IllegalArgumentException(String.format(
                        "ChannelMergeImageServer: source %d dimensions %dx%d do not match reference %dx%d",
                        i, s.getWidth(), s.getHeight(), w, h));
            }
            if (s.getPixelType() != pt) {
                throw new IllegalArgumentException(String.format(
                        "ChannelMergeImageServer: source %d pixel type %s does not match reference %s",
                        i, s.getPixelType(), pt));
            }
            if (s.nResolutions() != nRes) {
                logger.warn(
                        "ChannelMergeImageServer: source {} has {} resolutions but reference has {}; reads will "
                                + "still work but pyramid structure may differ",
                        i, s.nResolutions(), nRes);
            }
        }
    }

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        // Read every source first so we can build the output image from the actual
        // tile dimensions returned (downsample arithmetic + pyramid rounding makes
        // computing output size from the request alone fragile).
        BufferedImage[] tiles = new BufferedImage[sources.size()];
        int outW = 0;
        int outH = 0;
        for (int i = 0; i < sources.size(); i++) {
            tiles[i] = sources.get(i).readRegion(request);
            if (tiles[i] != null) {
                outW = Math.max(outW, tiles[i].getWidth());
                outH = Math.max(outH, tiles[i].getHeight());
            }
        }
        if (outW == 0 || outH == 0) {
            // All sources returned null -- nothing to assemble.
            return null;
        }

        BufferedImage output = BufferedImageTools.createImage(outW, outH, getPixelType(), mergedChannels);
        WritableRaster outRaster = output.getRaster();
        int outBand = 0;
        int[] intBuf = null;
        float[] floatBuf = null;
        double[] doubleBuf = null;
        PixelType pt = getPixelType();

        for (int sIdx = 0; sIdx < sources.size(); sIdx++) {
            ImageServer<BufferedImage> source = sources.get(sIdx);
            BufferedImage tile = tiles[sIdx];
            if (tile == null) {
                // Fill missing tile with zero for this source's channels.
                outBand += source.nChannels();
                continue;
            }
            int tileW = Math.min(outW, tile.getWidth());
            int tileH = Math.min(outH, tile.getHeight());
            WritableRaster srcRaster = tile.getRaster();
            for (int c = 0; c < source.nChannels(); c++) {
                if (pt == PixelType.FLOAT32 || pt == PixelType.FLOAT64) {
                    if (pt == PixelType.FLOAT64) {
                        if (doubleBuf == null || doubleBuf.length < tileW * tileH) {
                            doubleBuf = new double[tileW * tileH];
                        }
                        srcRaster.getSamples(0, 0, tileW, tileH, c, doubleBuf);
                        outRaster.setSamples(0, 0, tileW, tileH, outBand, doubleBuf);
                    } else {
                        if (floatBuf == null || floatBuf.length < tileW * tileH) {
                            floatBuf = new float[tileW * tileH];
                        }
                        srcRaster.getSamples(0, 0, tileW, tileH, c, floatBuf);
                        outRaster.setSamples(0, 0, tileW, tileH, outBand, floatBuf);
                    }
                } else {
                    if (intBuf == null || intBuf.length < tileW * tileH) {
                        intBuf = new int[tileW * tileH];
                    }
                    srcRaster.getSamples(0, 0, tileW, tileH, c, intBuf);
                    outRaster.setSamples(0, 0, tileW, tileH, outBand, intBuf);
                }
                outBand++;
            }
        }
        return output;
    }

    @Override
    public BufferedImage readRegion(double downsample, int x, int y, int width, int height, int z, int t)
            throws IOException {
        return readRegion(RegionRequest.createInstance(getPath(), downsample, x, y, width, height, z, t));
    }

    @Override
    public String getPath() {
        return "merged:" + sources.get(0).getPath();
    }

    @Override
    public Collection<URI> getURIs() {
        List<URI> uris = new ArrayList<>();
        for (ImageServer<BufferedImage> s : sources) {
            uris.addAll(s.getURIs());
        }
        return Collections.unmodifiableList(uris);
    }

    @Override
    public String getServerType() {
        return "Channel-merged (" + sources.size() + " sources, " + nChannels() + " channels)";
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
        throw new UnsupportedOperationException("ChannelMergeImageServer metadata is immutable");
    }

    @Override
    public BufferedImage getDefaultThumbnail(int z, int t) throws IOException {
        // Fall back to the first source's thumbnail -- thumbnails are rendering hints,
        // not authoritative pixel data, so a single-channel preview is acceptable.
        return sources.get(0).getDefaultThumbnail(z, t);
    }

    @Override
    public Class<BufferedImage> getImageClass() {
        return BufferedImage.class;
    }

    @Override
    public boolean isRGB() {
        return false;
    }

    @Override
    public double[] getPreferredDownsamples() {
        return sources.get(0).getPreferredDownsamples();
    }

    @Override
    public int nResolutions() {
        return sources.get(0).nResolutions();
    }

    @Override
    public double getDownsampleForResolution(int level) {
        return sources.get(0).getDownsampleForResolution(level);
    }

    @Override
    public int getWidth() {
        return sources.get(0).getWidth();
    }

    @Override
    public int getHeight() {
        return sources.get(0).getHeight();
    }

    @Override
    public int nChannels() {
        return mergedChannels.size();
    }

    @Override
    public ImageChannel getChannel(int channel) {
        return mergedChannels.get(channel);
    }

    @Override
    public int nZSlices() {
        return sources.get(0).nZSlices();
    }

    @Override
    public int nTimepoints() {
        return sources.get(0).nTimepoints();
    }

    @Override
    public ServerBuilder<BufferedImage> getBuilder() {
        // Not serializable -- the merged server is an intermediate assembly step,
        // not a persistent source that should round-trip through a QuPath project.
        return null;
    }

    @Override
    public TileRequestManager getTileRequestManager() {
        return sources.get(0).getTileRequestManager();
    }

    @Override
    public PixelType getPixelType() {
        return sources.get(0).getPixelType();
    }

    @Override
    public BufferedImage getCachedTile(TileRequest tile) {
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
        for (ImageServer<BufferedImage> s : sources) {
            if (!s.isEmptyRegion(request)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws Exception {
        Exception first = null;
        for (ImageServer<BufferedImage> s : sources) {
            try {
                s.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
