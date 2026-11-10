package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.H;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.TILE;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.W;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.assertSameBytes;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.assertSameLevels;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.mosaic;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.open;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import loci.common.RandomAccessInputStream;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.ChannelMergeImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TransformingImageServer;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.images.writers.ome.OMEPyramidWriter.CompressionType;
import qupath.lib.regions.RegionRequest;

/**
 * A merge of OME-TIFFs stored as the output will be copies their compressed tiles: the same
 * pixels and OME-XML as decoding and encoding them, no tile read through an image server, and the
 * decode path, with the reason logged, whenever a part does not match.
 */
class MergeTileCopyTest {

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void reset() {
        ImageServerProvider.setCache(null, BufferedImage.class);
        System.clearProperty(CompressedTileCopy.PROPERTY);
    }

    @Test
    void copiedLzwMergeEqualsTheDecodedOneAtEveryPlaneAndLevel() throws Exception {
        assertCopyEqualsDecode(CompressionType.LZW);
    }

    @Test
    void copiedZlibMergeEqualsTheDecodedOne() throws Exception {
        assertCopyEqualsDecode(CompressionType.ZLIB);
    }

    @Test
    void copiedUncompressedMergeEqualsTheDecodedOne() throws Exception {
        assertCopyEqualsDecode(CompressionType.UNCOMPRESSED);
    }

    @Test
    void copiedEightBitMergeEqualsTheDecodedOne() throws Exception {
        List<Path> parts = new ArrayList<>();
        parts.add(write(new LevelStagingTest.PlanesServer(1, 1, 1, PixelType.UINT8, 1), "a", CompressionType.LZW));
        parts.add(write(new LevelStagingTest.PlanesServer(1, 1, 1, PixelType.UINT8, 2), "b", CompressionType.LZW));
        Merged m = mergeBothWays(parts, CompressionType.LZW);
        assertSameBytes(m.copied, m.decoded);
        assertTilesCopied(m.copied, parts);
    }

    /** Parts with z-slices, time points and two channels in one part: every plane lands where it belongs. */
    @Test
    void copiedMergeOfMultiPlanePartsEqualsTheDecodedOne() throws Exception {
        List<Path> parts = new ArrayList<>();
        parts.add(write(new LevelStagingTest.PlanesServer(2, 2, 3, PixelType.UINT16, 1), "two", CompressionType.LZW));
        parts.add(write(new LevelStagingTest.PlanesServer(1, 2, 3, PixelType.UINT16, 2), "one", CompressionType.LZW));
        Merged m = mergeBothWays(parts, CompressionType.LZW);
        assertSameBytes(m.copied, m.decoded);
        try (ImageServer<BufferedImage> merged = open(m.copied);
                ImageServer<BufferedImage> two = open(parts.get(0));
                ImageServer<BufferedImage> one = open(parts.get(1))) {
            assertEquals(3, merged.nChannels());
            for (int level = 0; level < merged.nResolutions(); level++) {
                double d = merged.getDownsampleForResolution(level);
                for (int t = 0; t < 3; t++) {
                    for (int z = 0; z < 2; z++) {
                        assertArrayEquals(samples(two, d, z, t, 0), samples(merged, d, z, t, 0));
                        assertArrayEquals(samples(two, d, z, t, 1), samples(merged, d, z, t, 1));
                        assertArrayEquals(samples(one, d, z, t, 0), samples(merged, d, z, t, 2));
                    }
                }
            }
        }
    }

    /** No tile is read through the parts' image servers when copying; every tile is when not. */
    @Test
    void copyReadsNoTileThroughTheImageServers() throws Exception {
        List<Path> parts = lzwParts(3);
        List<CountingWrapper> counted = wrap(parts);
        writeMerged(
                new ChannelMergeImageServer(new ArrayList<>(counted), null),
                dir.resolve("copied"),
                CompressionType.LZW);
        for (CountingWrapper c : counted) {
            assertEquals(0, c.reads.get(), "tiles read through the image server while copying");
            c.close();
        }

        System.setProperty(CompressedTileCopy.PROPERTY, "false");
        List<CountingWrapper> decoded = wrap(parts);
        writeMerged(
                new ChannelMergeImageServer(new ArrayList<>(decoded), null),
                dir.resolve("decoded"),
                CompressionType.LZW);
        double[] downsamples = DirectTiffOutputWriter.computePyramidDownsamples(W, H, 1.0, TILE);
        int tiles = 0;
        for (double d : downsamples) {
            int w = (int) (W / d);
            int h = (int) (H / d);
            tiles += ((w + TILE - 1) / TILE) * ((h + TILE - 1) / TILE);
        }
        for (CountingWrapper c : decoded) {
            assertEquals(tiles, c.reads.get(), "tiles read through the image server when decoding");
            c.close();
        }
    }

    @Test
    void anotherOutputCompressionDecodesAndSaysWhy() throws Exception {
        List<Path> parts = lzwParts(2);
        List<String> info = new ArrayList<>();
        Path out;
        try (ImageServer<BufferedImage> merge = merge(parts)) {
            out = captureInfo(info, () -> writeMerged(merge, dir.resolve("zlib"), CompressionType.ZLIB));
        }
        assertTrue(
                info.stream()
                        .anyMatch(m -> m.startsWith("Merge: decoding and encoding every tile again, since")
                                && m.contains("uses TIFF compression 5, the output zlib")),
                String.join("\n", info));
        for (int c = 0; c < 2; c++) {
            assertSameLevels(out, c, parts.get(c), 0);
        }
    }

    @Test
    void everyMismatchIsRefusedWithItsReason() throws Exception {
        List<Path> lzw = lzwParts(2);
        double[] downsamples = DirectTiffOutputWriter.computePyramidDownsamples(W, H, 1.0, TILE);
        assertEquals(4, downsamples.length);

        assertRefused(lzw, "JPEG", TILE, downsamples, "only LZW, ZLIB and none are copied");
        assertRefused(lzw, "zlib", TILE, downsamples, "uses TIFF compression 5, the output zlib");
        assertRefused(lzw, "LZW", 120, downsamples, "not a multiple of 16");

        System.setProperty(CompressedTileCopy.PROPERTY, "false");
        assertRefused(lzw, "LZW", TILE, downsamples, "-Dt2p.mergeTileCopy=false");
        System.clearProperty(CompressedTileCopy.PROPERTY);

        Path uncompressed = write(new WrappedBufferedImageServer("u", mosaic(3)), "u", CompressionType.UNCOMPRESSED);
        assertRefused(List.of(lzw.get(0), uncompressed), "LZW", TILE, downsamples, "uses TIFF compression 1");

        // Tiles of 64 px: more levels than the output, in the wrong tiles.
        Path small = dir.resolve("tile64.ome.tif");
        DirectTiffOutputWriter.write(
                new WrappedBufferedImageServer("s", mosaic(4)), small.toString(), CompressionType.LZW, 64, 1.0, null);
        assertRefused(List.of(lzw.get(0), small), "LZW", TILE, downsamples, "is not in tiles of 128 px");

        // Tiles of 1024 px: one level only.
        Path single = dir.resolve("tile1024.ome.tif");
        DirectTiffOutputWriter.write(
                new WrappedBufferedImageServer("s", mosaic(5)),
                single.toString(),
                CompressionType.LZW,
                1024,
                1.0,
                null);
        assertRefused(List.of(lzw.get(0), single), "LZW", TILE, downsamples, "stores 1 levels, the output has 4");

        // A part that is not a file.
        try (ImageServer<BufferedImage> a = open(lzw.get(0));
                ChannelMergeImageServer m = new ChannelMergeImageServer(
                        List.of(a, new WrappedBufferedImageServer("mem", mosaic(6))), null)) {
            var e = assertThrows(
                    CompressedTileCopy.NotCopyable.class,
                    () -> CompressedTileCopy.open(m, m, "LZW", TILE, downsamples));
            assertTrue(e.getMessage().contains("is not one file (0 URIs)"), e.getMessage());
        }
    }

    private void assertCopyEqualsDecode(CompressionType compression) throws Exception {
        List<Path> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parts.add(write(new WrappedBufferedImageServer("c" + i, mosaic(20 + i)), "channel" + i, compression));
        }
        Merged m = mergeBothWays(parts, compression);
        assertSameBytes(m.copied, m.decoded);
        for (int c = 0; c < parts.size(); c++) {
            assertSameLevels(m.copied, c, parts.get(c), 0);
        }
        assertTilesCopied(m.copied, parts);
        assertEquals(omeXmlWithoutUuids(m.decoded), omeXmlWithoutUuids(m.copied));
    }

    private record Merged(Path copied, Path decoded) {}

    /** The same merge written by copying and by decoding, both named merged.ome.tif. */
    private Merged mergeBothWays(List<Path> parts, CompressionType compression) throws Exception {
        List<String> info = new ArrayList<>();
        Path copied;
        try (ImageServer<BufferedImage> merge = merge(parts)) {
            copied = captureInfo(info, () -> writeMerged(merge, dir.resolve("copied"), compression));
        }
        assertTrue(
                info.stream().anyMatch(m -> m.startsWith("Merge: copying the compressed tiles")),
                String.join("\n", info));
        System.setProperty(CompressedTileCopy.PROPERTY, "false");
        Path decoded;
        try (ImageServer<BufferedImage> merge = merge(parts)) {
            decoded = writeMerged(merge, dir.resolve("decoded"), compression);
        } finally {
            System.clearProperty(CompressedTileCopy.PROPERTY);
        }
        return new Merged(copied, decoded);
    }

    private void assertRefused(List<Path> parts, String compression, int tile, double[] downsamples, String reason)
            throws Exception {
        try (ChannelMergeImageServer m = merge(parts)) {
            var e = assertThrows(
                    CompressedTileCopy.NotCopyable.class,
                    () -> CompressedTileCopy.open(m, m, compression, tile, downsamples));
            assertTrue(e.getMessage().contains(reason), e.getMessage());
        }
    }

    private List<Path> lzwParts(int n) throws IOException {
        List<Path> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            parts.add(write(new WrappedBufferedImageServer("c" + i, mosaic(30 + i)), "lzw" + i, CompressionType.LZW));
        }
        return parts;
    }

    /** A part, {@code <name>.ome.tif}. */
    private Path write(ImageServer<BufferedImage> server, String name, CompressionType compression) throws IOException {
        Path out = dir.resolve(name + ".ome.tif");
        DirectTiffOutputWriter.write(server, out.toString(), compression, TILE, 1.0, null);
        return out;
    }

    /** A merge, {@code <folder>/merged.ome.tif}, so two merges differ only in their UUIDs. */
    private static Path writeMerged(ImageServer<BufferedImage> server, Path folder, CompressionType compression)
            throws IOException {
        Files.createDirectories(folder);
        Path out = folder.resolve("merged.ome.tif");
        DirectTiffOutputWriter.write(server, out.toString(), compression, TILE, 1.0, null);
        return out;
    }

    private static ChannelMergeImageServer merge(List<Path> parts) throws IOException {
        List<ImageServer<BufferedImage>> servers = new ArrayList<>();
        for (Path p : parts) {
            servers.add(open(p));
        }
        return new ChannelMergeImageServer(servers, null);
    }

    private static List<CountingWrapper> wrap(List<Path> parts) throws IOException {
        List<CountingWrapper> out = new ArrayList<>();
        for (Path p : parts) {
            out.add(new CountingWrapper(open(p)));
        }
        return out;
    }

    private interface IOSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T captureInfo(List<String> messages, IOSupplier<T> action) throws IOException {
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DirectTiffOutputWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            return action.get();
        } finally {
            log.detachAppender(appender);
            appender.list.forEach(e -> messages.add(e.getFormattedMessage()));
        }
    }

    private static int[] samples(ImageServer<BufferedImage> server, double d, int z, int t, int band)
            throws IOException {
        BufferedImage img = server.readRegion(RegionRequest.createInstance(server.getPath(), d, 0, 0, W, H, z, t));
        return img.getRaster().getSamples(0, 0, img.getWidth(), img.getHeight(), band, (int[]) null);
    }

    /** Every compressed tile of channel c of {@code merged} is byte for byte the tile of single-channel part c. */
    private static void assertTilesCopied(Path merged, List<Path> parts) throws Exception {
        try (RandomAccessInputStream min = new RandomAccessInputStream(merged.toString())) {
            TiffParser mp = new TiffParser(min);
            IFDList mains = mp.getMainIFDs();
            for (int c = 0; c < parts.size(); c++) {
                try (RandomAccessInputStream pin =
                        new RandomAccessInputStream(parts.get(c).toString())) {
                    TiffParser pp = new TiffParser(pin);
                    List<IFD> mergedLevels = levels(mp, mains.get(c));
                    List<IFD> partLevels = levels(pp, pp.getMainIFDs().get(0));
                    assertEquals(partLevels.size(), mergedLevels.size());
                    for (int level = 0; level < partLevels.size(); level++) {
                        long[] mo = mergedLevels.get(level).getIFDLongArray(IFD.TILE_OFFSETS);
                        long[] mc = mergedLevels.get(level).getIFDLongArray(IFD.TILE_BYTE_COUNTS);
                        long[] po = partLevels.get(level).getIFDLongArray(IFD.TILE_OFFSETS);
                        long[] pc = partLevels.get(level).getIFDLongArray(IFD.TILE_BYTE_COUNTS);
                        assertArrayEquals(pc, mc, "tile byte counts, channel " + c + " level " + level);
                        for (int i = 0; i < mo.length; i++) {
                            assertArrayEquals(read(pin, po[i], pc[i]), read(min, mo[i], mc[i]));
                        }
                    }
                }
            }
        }
    }

    private static List<IFD> levels(TiffParser parser, IFD main) throws IOException {
        List<IFD> out = new ArrayList<>();
        parser.fillInIFD(main);
        out.add(main);
        for (IFD sub : parser.getSubIFDs(main)) {
            parser.fillInIFD(sub);
            out.add(sub);
        }
        return out;
    }

    private static byte[] read(RandomAccessInputStream in, long offset, long count) throws IOException {
        byte[] b = new byte[(int) count];
        in.seek(offset);
        in.readFully(b);
        return b;
    }

    private static String omeXmlWithoutUuids(Path file) throws IOException {
        try (RandomAccessInputStream in = new RandomAccessInputStream(file.toString())) {
            return new TiffParser(in).getComment().replaceAll("urn:uuid:[0-9a-fA-F-]+", "urn:uuid:X");
        }
    }

    /** A file-backed image counting the regions read through it. */
    private static final class CountingWrapper extends TransformingImageServer<BufferedImage> {

        final AtomicInteger reads = new AtomicInteger();

        CountingWrapper(ImageServer<BufferedImage> server) {
            super(server);
        }

        @Override
        public BufferedImage readRegion(RegionRequest request) throws IOException {
            reads.incrementAndGet();
            return getWrappedServer()
                    .readRegion(request.updatePath(getWrappedServer().getPath()));
        }

        @Override
        public String getServerType() {
            return "counting wrapper";
        }

        @Override
        protected ServerBuilder<BufferedImage> createServerBuilder() {
            return null;
        }

        @Override
        protected String createID() {
            return "counting:" + UUID.randomUUID();
        }
    }
}
