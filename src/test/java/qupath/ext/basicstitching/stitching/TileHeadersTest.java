package qupath.ext.basicstitching.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The header-only size reader agrees with {@code javax.imageio} on every layout it parses, falls
 * back to it on one it does not, reads each file once per session, and notices a file that
 * changed.
 */
class TileHeadersTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void forget() {
        TileHeaders.clearCache();
    }

    @Test
    void stripedAndTiledTiffsMatchImageIo() throws Exception {
        Path striped = tmp.resolve("striped.tif");
        ImageIO.write(new BufferedImage(301, 207, BufferedImage.TYPE_USHORT_GRAY), "TIFF", striped.toFile());
        Path tiled = tmp.resolve("tiled.tif");
        writeTiled(tiled, new BufferedImage(700, 333, BufferedImage.TYPE_BYTE_GRAY));
        Path rgb = tmp.resolve("rgb.tif");
        ImageIO.write(new BufferedImage(65, 1030, BufferedImage.TYPE_INT_RGB), "TIFF", rgb.toFile());
        for (Path p : List.of(striped, tiled, rgb)) {
            BufferedImage back = ImageIO.read(p.toFile());
            assertEquals(new TileHeaders.Size(back.getWidth(), back.getHeight()), TileHeaders.parse(p), p.toString());
        }
    }

    @Test
    void littleEndianAndBigTiffHeadersAreParsed() throws Exception {
        Path ii = tmp.resolve("ii.tif");
        Files.write(ii, classicTiff(ByteOrder.LITTLE_ENDIAN, 1234, 567));
        assertEquals(new TileHeaders.Size(1234, 567), TileHeaders.parse(ii));
        Path mm = tmp.resolve("mm.tif");
        Files.write(mm, classicTiff(ByteOrder.BIG_ENDIAN, 70000, 3));
        assertEquals(new TileHeaders.Size(70000, 3), TileHeaders.parse(mm), "LONG width above 65535");
        Path big = tmp.resolve("big.tif");
        Files.write(big, bigTiff(2048, 1536));
        assertEquals(new TileHeaders.Size(2048, 1536), TileHeaders.parse(big));
    }

    @Test
    void notATiffIsRefusedByTheParserAndAbsentFromReadAll() throws Exception {
        Path junk = tmp.resolve("junk.tif");
        Files.writeString(junk, "not an image at all");
        assertNull(TileHeaders.parse(junk));
        Map<Path, TileHeaders.Size> sizes = TileHeaders.readAll(List.of(junk), null);
        assertFalse(sizes.containsKey(junk), "unreadable files are left out, not given a size");
    }

    @Test
    void mixedSizesInOneSetAreEachReadRight() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Path p = tmp.resolve("tile_" + i + ".tif");
            int w = i == 17 ? 99 : 128;
            int h = i == 31 ? 77 : 128;
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY), "TIFF", p.toFile());
            files.add(p);
        }
        Map<Path, TileHeaders.Size> sizes = TileHeaders.readAll(files, null);
        assertEquals(40, sizes.size());
        assertEquals(new TileHeaders.Size(99, 128), sizes.get(files.get(17)));
        assertEquals(new TileHeaders.Size(128, 77), sizes.get(files.get(31)));
        assertEquals(new TileHeaders.Size(128, 128), sizes.get(files.get(0)));
        assertEquals(List.copyOf(files), List.copyOf(sizes.keySet()), "in the order given");
    }

    @Test
    void aSecondReadComesFromTheCacheUntilTheFileChanges() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Path p = tmp.resolve("t" + i + ".tif");
            ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_BYTE_GRAY), "TIFF", p.toFile());
            files.add(p);
        }
        int before = TileHeaders.headerReads();
        TileHeaders.readAll(files, null);
        assertEquals(10, TileHeaders.headerReads() - before, "first pass reads every header");
        int mid = TileHeaders.headerReads();
        TileHeaders.readAll(files, null);
        assertEquals(0, TileHeaders.headerReads() - mid, "second pass reads none");

        // Rewritten at another size (and a different time): must be read again, not remembered.
        Path changed = files.get(3);
        ImageIO.write(new BufferedImage(32, 16, BufferedImage.TYPE_BYTE_GRAY), "TIFF", changed.toFile());
        Files.setLastModifiedTime(changed, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        int last = TileHeaders.headerReads();
        Map<Path, TileHeaders.Size> sizes = TileHeaders.readAll(files, null);
        assertEquals(1, TileHeaders.headerReads() - last, "only the changed file");
        assertEquals(new TileHeaders.Size(32, 16), sizes.get(changed));
    }

    @Test
    void progressIsReportedOnTheCallingThread() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Path p = tmp.resolve("p" + i + ".tif");
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), "TIFF", p.toFile());
            files.add(p);
        }
        Thread caller = Thread.currentThread();
        List<Integer> seen = new ArrayList<>();
        TileHeaders.readAll(files, done -> {
            assertTrue(Thread.currentThread() == caller);
            seen.add(done);
        });
        assertEquals(List.of(50, 100, 120), seen);
    }

    @Test
    void pageDataOffsetsListEveryPageInOrder() throws Exception {
        Path multi = tmp.resolve("multi.tif");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(multi.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < 5; i++) {
                writer.writeToSequence(
                        new IIOImage(new BufferedImage(20 + i, 10, BufferedImage.TYPE_BYTE_GRAY), null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        List<Long> offsets = TileHeaders.pageDataOffsets(multi);
        assertEquals(5, offsets.size());
        for (int i = 1; i < offsets.size(); i++) {
            assertTrue(offsets.get(i) > offsets.get(i - 1), "pages in file order");
        }
    }

    private static void writeTiled(Path file, BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
            param.setTiling(256, 256, 0, 0);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** A minimal classic TIFF: width as LONG, height as SHORT, one strip of zeros (8-bit gray). */
    static byte[] classicTiff(ByteOrder order, int width, int height) {
        int entries = 8;
        int ifd = 8;
        int data = ifd + 2 + entries * 12 + 4;
        int pixels = width * height;
        ByteBuffer b = ByteBuffer.allocate(data + pixels).order(order);
        b.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        b.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        b.putShort((short) 42).putInt(ifd);
        b.putShort((short) entries);
        entry(b, 256, 4, 1, width);
        entry(b, 257, 3, 1, height);
        entry(b, 258, 3, 1, 8);
        entry(b, 259, 3, 1, 1);
        entry(b, 262, 3, 1, 1);
        entry(b, 273, 4, 1, data);
        entry(b, 278, 4, 1, height);
        entry(b, 279, 4, 1, pixels);
        b.putInt(0);
        return b.array();
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, int value) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3) {
            b.putShort((short) value).putShort((short) 0);
        } else {
            b.putInt(value);
        }
    }

    /** A minimal little-endian BigTIFF header and first directory (no pixel data needed to size it). */
    private static byte[] bigTiff(int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(16 + 8 + 2 * 20 + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I')
                .put((byte) 'I')
                .putShort((short) 43)
                .putShort((short) 8)
                .putShort((short) 0);
        b.putLong(16);
        b.putLong(2);
        b.putShort((short) 256).putShort((short) 16).putLong(1).putLong(width);
        b.putShort((short) 257)
                .putShort((short) 3)
                .putLong(1)
                .putShort((short) height)
                .putShort((short) 0)
                .putInt(0);
        b.putLong(0);
        return b.array();
    }
}
