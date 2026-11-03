package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.H;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.TILE;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.W;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.assertSameLevels;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.mosaic;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.open;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.write;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.ChannelMergeImageServer;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.TransformingImageServer;
import qupath.lib.images.servers.WrappedBufferedImageServer;

/**
 * A merge reads each output channel from the image that holds it, once per tile and level, and
 * gives the same pixels as making every level again from full resolution through the whole
 * merged image (the 0.8.2 path).
 */
class MergeReadPathTest {

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void noTileCache() {
        // A cache would hide repeated reads and keep tiles between tests.
        ImageServerProvider.setCache(null, BufferedImage.class);
    }

    @Test
    void mergeMatchesTheWholeImagePathPlaneByPlane() throws Exception {
        int n = 3;
        List<Path> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            parts.add(write(new WrappedBufferedImageServer("c" + i, mosaic(10 + i)), dir, "channel" + i));
        }
        Path merged;
        try (ImageServer<BufferedImage> merge = merge(parts)) {
            merged = write(merge, dir, "merged");
        }
        // Old path: one pyramid over the whole merge, every level made from full resolution.
        Path old;
        try (ImageServer<BufferedImage> full = new FullResolutionServer(merge(parts))) {
            old = write(full, dir, "merged-old");
        }
        for (int c = 0; c < n; c++) {
            assertSameLevels(merged, c, old, c);
            assertSameLevels(merged, c, parts.get(c), 0);
        }
    }

    @Test
    void mergeReadsEachSourceTileOncePerLevel() throws Exception {
        int n = 3;
        List<CountingServer> sources = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sources.add(new CountingServer(i));
        }
        write(new ChannelMergeImageServer(new ArrayList<>(sources), null), dir, "merged");
        double[] downsamples = DirectTiffOutputWriter.computePyramidDownsamples(W, H, 1.0, TILE);
        assertEquals(CountingServer.DOWNSAMPLES.length, downsamples.length);
        for (CountingServer s : sources) {
            assertArrayEquals(tilesPerLevel(), s.reads(), "source " + s.id + " tile reads per level");
        }

        // The same merge hidden behind a plain wrapper is read whole for every plane: n times.
        List<CountingServer> again = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            again.add(new CountingServer(i));
        }
        ChannelMergeImageServer merge = new ChannelMergeImageServer(new ArrayList<>(again), null);
        write(new PlainWrapper(merge), dir, "merged-wrapped");
        int[] expected = tilesPerLevel();
        for (int level = 0; level < expected.length; level++) {
            expected[level] *= n;
        }
        for (CountingServer s : again) {
            assertArrayEquals(expected, s.reads(), "wrapped source " + s.id + " tile reads per level");
        }
    }

    private static int[] tilesPerLevel() {
        double[] d = CountingServer.DOWNSAMPLES;
        int[] tiles = new int[d.length];
        for (int level = 0; level < d.length; level++) {
            int w = (int) (W / d[level]);
            int h = (int) (H / d[level]);
            tiles[level] = ((w + TILE - 1) / TILE) * ((h + TILE - 1) / TILE);
        }
        return tiles;
    }

    private ImageServer<BufferedImage> merge(List<Path> parts) throws IOException {
        List<ImageServer<BufferedImage>> servers = new ArrayList<>();
        for (Path p : parts) {
            servers.add(open(p));
        }
        return new ChannelMergeImageServer(servers, null);
    }

    /** A stored four-level pyramid that counts how often each level's tiles are read. */
    private static final class CountingServer extends AbstractTileableImageServer {

        static final double[] DOWNSAMPLES = {1, 2, 4, 8};

        final int id;
        private final AtomicIntegerArray reads = new AtomicIntegerArray(DOWNSAMPLES.length);
        private final ImageServerMetadata metadata;

        CountingServer(int id) {
            this.id = id;
            this.metadata = new ImageServerMetadata.Builder()
                    .width(W)
                    .height(H)
                    .pixelType(PixelType.UINT16)
                    .rgb(false)
                    .channels(List.of(ImageChannel.getInstance("c" + id, null)))
                    .preferredTileSize(TILE, TILE)
                    .levelsFromDownsamples(DOWNSAMPLES)
                    .build();
        }

        int[] reads() {
            int[] out = new int[DOWNSAMPLES.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = reads.get(i);
            }
            return out;
        }

        @Override
        protected BufferedImage readTile(TileRequest tileRequest) {
            reads.incrementAndGet(tileRequest.getLevel());
            BufferedImage img = new BufferedImage(
                    tileRequest.getTileWidth(), tileRequest.getTileHeight(), BufferedImage.TYPE_USHORT_GRAY);
            WritableRaster r = img.getRaster();
            int value = 1000 * (tileRequest.getLevel() + 1) + id;
            for (int y = 0; y < r.getHeight(); y++) {
                for (int x = 0; x < r.getWidth(); x++) {
                    r.setSample(x, y, 0, value);
                }
            }
            return img;
        }

        @Override
        public Collection<URI> getURIs() {
            return List.of();
        }

        @Override
        public String getServerType() {
            return "counting";
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
            return "counting:" + id + ":" + UUID.randomUUID();
        }
    }

    /** The wrapped image with its levels, but not recognizable as a channel stack. */
    private static final class PlainWrapper extends TransformingImageServer<BufferedImage> {

        PlainWrapper(ImageServer<BufferedImage> server) {
            super(server);
        }

        @Override
        public String getServerType() {
            return "plain wrapper";
        }

        @Override
        protected ServerBuilder<BufferedImage> createServerBuilder() {
            return null;
        }

        @Override
        protected String createID() {
            return "plain:" + UUID.randomUUID();
        }
    }
}
