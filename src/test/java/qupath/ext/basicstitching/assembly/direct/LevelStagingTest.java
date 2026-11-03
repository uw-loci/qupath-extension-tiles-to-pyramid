package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.H;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.TILE;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.W;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.assertSameBytes;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.mosaic;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.stagingFolders;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.write;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.ChannelMergeImageServer;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.WrappedBufferedImageServer;

/**
 * Pyramid levels made from a staged copy of the level before are the levels the unstaged path
 * (0.8.3) makes, for every plane; full resolution is read once per plane; the staging folder is
 * gone after a write, failed or not, and a write without the disk space falls back with a warning.
 */
class LevelStagingTest {

    /** Free space that never runs out, and none at all (forces the unstaged path). */
    private static final LevelStaging.FreeSpace PLENTY = dir -> Long.MAX_VALUE / 4;

    private static final LevelStaging.FreeSpace NONE = dir -> 0L;

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void noTileCache() {
        // A cache would hide repeated reads and keep tiles between tests.
        ImageServerProvider.setCache(null, BufferedImage.class);
    }

    @Test
    void sixteenBitLevelsEqualTheUnstagedPath() throws Exception {
        assertStagedEqualsUnstaged(() -> new WrappedBufferedImageServer("m16", mosaic(3)), "m16");
    }

    @Test
    void eightBitLevelsEqualTheUnstagedPath() throws Exception {
        BufferedImage m = mosaic(4);
        BufferedImage b = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                b.getRaster().setSample(x, y, 0, m.getRaster().getSample(x, y, 0) >> 8);
            }
        }
        assertStagedEqualsUnstaged(() -> new WrappedBufferedImageServer("m8", b), "m8");
    }

    @Test
    void everyChannelZAndTEqualsTheUnstagedPath() throws Exception {
        assertStagedEqualsUnstaged(() -> new PlanesServer(3, 2, 2, PixelType.UINT16), "czt");
    }

    @Test
    void aMergeOfImagesWithoutLevelsStagesEachPart() throws Exception {
        assertStagedEqualsUnstaged(
                () -> new ChannelMergeImageServer(
                        List.of(
                                new WrappedBufferedImageServer("p0", mosaic(5)),
                                new WrappedBufferedImageServer("p1", mosaic(6))),
                        null),
                "merge");
    }

    @Test
    void fullResolutionIsReadOncePerPlane() throws Exception {
        int c = 2, z = 2, t = 1;
        int tiles = ((W + TILE - 1) / TILE) * ((H + TILE - 1) / TILE);
        PlanesServer staged = new PlanesServer(c, z, t, PixelType.UINT16);
        write(staged, dir, "staged", PLENTY, null);
        assertEquals(tiles * c * z * t, staged.reads.get(), "full-resolution tile reads, staged");

        PlanesServer unstaged = new PlanesServer(c, z, t, PixelType.UINT16);
        write(unstaged, dir, "unstaged", NONE, null);
        assertTrue(
                unstaged.reads.get() > 2 * tiles * c * z * t,
                "the unstaged path remakes coarser levels from full resolution: " + unstaged.reads.get());
    }

    @Test
    void withoutTheDiskSpaceTheLevelsAreMadeTheSlowerWayWithAWarning() throws Exception {
        List<String> warnings = new ArrayList<>();
        Path slow = write(new WrappedBufferedImageServer("m", mosaic(7)), dir, "slow", NONE, warnings::add);
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).startsWith("Not enough free disk space"), warnings.get(0));
        assertTrue(warnings.get(0).contains(dir.toString()), warnings.get(0));
        List<String> none = new ArrayList<>();
        Path fast = write(new WrappedBufferedImageServer("m", mosaic(7)), dir, "fast", PLENTY, none::add);
        assertTrue(none.isEmpty(), String.valueOf(none));
        assertSameBytes(slow, fast);
        assertTrue(stagingFolders(dir).isEmpty());
    }

    @Test
    void theStagingFolderIsDeletedWhenTheWriteFails() throws Exception {
        int tiles = ((W + TILE - 1) / TILE) * ((H + TILE - 1) / TILE);
        PlanesServer failing = new PlanesServer(1, 1, 1, PixelType.UINT16);
        List<Path> during = new ArrayList<>();
        failing.failAt = tiles; // the last full-resolution tile, with level 1 being staged
        failing.beforeFailing = () -> during.addAll(listStaging());
        assertThrows(IOException.class, () -> write(failing, dir, "failed", PLENTY, null));
        assertEquals(1, during.size(), "a staging folder existed while writing");
        assertTrue(stagingFolders(dir).isEmpty(), "staging folder left after a failed write");
    }

    @Test
    void staleFoldersAreDeletedAndHeldOnesKept() throws Exception {
        Path stale = Files.createDirectory(dir.resolve(LevelStaging.PREFIX + "stale"));
        Files.writeString(stale.resolve("lock"), "old");
        Files.write(stale.resolve("level1.raw"), new byte[1000]);
        Path oldNoLock = Files.createDirectory(dir.resolve(LevelStaging.PREFIX + "old-no-lock"));
        Files.setLastModifiedTime(
                oldNoLock, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        Path newNoLock = Files.createDirectory(dir.resolve(LevelStaging.PREFIX + "new-no-lock"));
        Path held = Files.createDirectory(dir.resolve(LevelStaging.PREFIX + "held"));
        try (FileChannel ch =
                        FileChannel.open(held.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = ch.lock()) {
            write(new WrappedBufferedImageServer("m", mosaic(8)), dir, "out", PLENTY, null);
            assertFalse(Files.exists(stale), "stale staging folder not deleted");
            assertTrue(Files.exists(held), "a folder whose lock is held must be left alone");
            assertFalse(Files.exists(oldNoLock), "an hour-old folder without a lock is stale");
            assertTrue(Files.exists(newNoLock), "a new folder without a lock may be about to get one");
        }
    }

    @Test
    void halvingMatchesTheAreaAverageOfAnyEvenBlock() {
        Random random = new Random(11);
        for (int size = 0; size < 24 * 24; size++) {
            int w = 1 + size % 24;
            int h = 1 + size / 24;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
            WritableRaster r = img.getRaster();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    r.setSample(x, y, 0, random.nextInt(4) == 0 ? 0 : random.nextInt(65536));
                }
            }
            int ow = w / 2, oh = h / 2;
            if (ow == 0 || oh == 0) {
                assertEquals(0, DirectTiffOutputWriter.halve(r.getSamples(0, 0, w, h, 0, (int[]) null), w, h).length);
                continue;
            }
            BufferedImage even = img.getSubimage(0, 0, 2 * ow, 2 * oh);
            BufferedImage copy = new BufferedImage(2 * ow, 2 * oh, BufferedImage.TYPE_USHORT_GRAY);
            copy.setData(even.getData());
            BufferedImage expected = CompositorImageServer.areaAverage(copy, ow, oh, true);
            assertArrayEquals(
                    expected.getRaster().getSamples(0, 0, ow, oh, 0, (int[]) null),
                    DirectTiffOutputWriter.halve(r.getSamples(0, 0, w, h, 0, (int[]) null), w, h),
                    w + " x " + h);
        }
    }

    @Test
    void packedSamplesUnpackToThemselves() {
        int[] u16 = {0, 1, 255, 256, 32767, 32768, 65535};
        assertArrayEquals(
                u16, DirectTiffOutputWriter.unpackSamples(DirectTiffOutputWriter.packSamples(u16, 2), 2, false));
        int[] s16 = {-32768, -1, 0, 1, 32767};
        assertArrayEquals(
                s16, DirectTiffOutputWriter.unpackSamples(DirectTiffOutputWriter.packSamples(s16, 2), 2, true));
        int[] u8 = {0, 1, 127, 128, 255};
        assertArrayEquals(
                u8, DirectTiffOutputWriter.unpackSamples(DirectTiffOutputWriter.packSamples(u8, 1), 1, false));
    }

    private List<Path> listStaging() {
        try {
            return stagingFolders(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface ServerFactory {
        ImageServer<BufferedImage> create() throws Exception;
    }

    private void assertStagedEqualsUnstaged(ServerFactory factory, String name) throws Exception {
        List<String> warnings = new ArrayList<>();
        Path staged;
        try (ImageServer<BufferedImage> s = factory.create()) {
            staged = write(s, dir, name + "-staged", PLENTY, warnings::add);
        }
        Path unstaged;
        try (ImageServer<BufferedImage> s = factory.create()) {
            unstaged = write(s, dir, name + "-unstaged", NONE, w -> {});
        }
        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        assertSameBytes(staged, unstaged);
        assertTrue(stagingFolders(dir).isEmpty(), "staging folder left after the write");
    }

    /**
     * {@code c} channels, {@code z} slices, {@code t} time points of 16- or 8-bit noise with
     * stretches of empty canvas that differ by plane; one resolution, tiles of {@link
     * PyramidTestFiles#TILE}, counting tile reads.
     */
    static final class PlanesServer extends AbstractTileableImageServer {

        final AtomicInteger reads = new AtomicInteger();
        int failAt = -1;
        Runnable beforeFailing = () -> {};
        private final ImageServerMetadata metadata;

        PlanesServer(int c, int z, int t, PixelType type) {
            List<ImageChannel> channels = new ArrayList<>();
            for (int i = 0; i < c; i++) {
                channels.add(ImageChannel.getInstance("c" + i, ImageChannel.getDefaultChannelColor(i)));
            }
            metadata = new ImageServerMetadata.Builder()
                    .width(W)
                    .height(H)
                    .pixelType(type)
                    .rgb(false)
                    .channels(channels)
                    .sizeZ(z)
                    .sizeT(t)
                    .preferredTileSize(TILE, TILE)
                    .build();
        }

        @Override
        protected BufferedImage readTile(TileRequest tile) throws IOException {
            if (reads.incrementAndGet() == failAt) {
                beforeFailing.run();
                throw new IOException("planned failure");
            }
            int w = tile.getTileWidth();
            int h = tile.getTileHeight();
            int bands = nChannels();
            boolean u16 = getPixelType() == PixelType.UINT16;
            WritableRaster r =
                    Raster.createBandedRaster(u16 ? DataBuffer.TYPE_USHORT : DataBuffer.TYPE_BYTE, w, h, bands, null);
            int max = u16 ? 65535 : 255;
            for (int b = 0; b < bands; b++) {
                for (int y = 0; y < h; y++) {
                    int gy = tile.getImageY() + y;
                    for (int x = 0; x < w; x++) {
                        int gx = tile.getImageX() + x;
                        int plane = b + 3 * tile.getZ() + 7 * tile.getT();
                        boolean canvas = ((gx + 13 * plane) / 97 + gy / 89) % 5 == 0 || gx < (gy % (31 + plane));
                        int hash = (gx * 73856093) ^ (gy * 19349663) ^ ((plane + 1) * 83492791);
                        r.setSample(x, y, b, canvas ? 0 : 1 + Math.floorMod(hash, max));
                    }
                }
            }
            return new BufferedImage(
                    ColorModelFactory.createColorModel(
                            getPixelType(), getMetadata().getChannels()),
                    r,
                    false,
                    null);
        }

        @Override
        public Collection<URI> getURIs() {
            return List.of();
        }

        @Override
        public String getServerType() {
            return "planes";
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
            return "planes:" + UUID.randomUUID();
        }
    }
}
