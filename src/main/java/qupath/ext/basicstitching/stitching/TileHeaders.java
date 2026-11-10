package qupath.ext.basicstitching.stitching;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;

/**
 * Tile sizes from the TIFF header alone, read once per file per QuPath session.
 *
 * <p>Every strategy needs each tile's width and height before it can place it. Reading them
 * through {@code javax.imageio} looks up a reader among every installed plugin and parses the
 * whole first image directory, including tag values such as OME-XML or Micro-Manager's metadata
 * JSON; on a Windows acquisition PC that was about 6 ms a file, and a registration solve followed
 * by one stitch per channel read every file twice. Here the header is parsed directly (the 8- or
 * 16-byte TIFF header and the first image directory's entries, about one disk block), files are
 * read on several threads, and a size is remembered for as long as the file keeps the same length
 * and modification time. A file whose header this parser does not understand falls back to
 * {@code javax.imageio}; every file is still opened and checked, so a tile set of mixed sizes is
 * read correctly.
 */
public final class TileHeaders {
    private static final Logger logger = LoggerFactory.getLogger(TileHeaders.class);

    /** Above this many remembered files the cache starts over: about 100 bytes each. */
    private static final int MAX_CACHED = 500_000;

    /** Header reads in flight at once: the work is waiting on the disk, not on a core. */
    private static final int THREADS =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_TILE_OFFSETS = 324;

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger HEADER_READS = new AtomicInteger();

    private TileHeaders() {}

    /** A tile's width and height in pixels. */
    public record Size(int width, int height) {}

    private record Cached(long length, long modified, Size size) {}

    /**
     * @param file a TIFF
     * @return the width and height of its first image
     * @throws IOException when neither the header parser nor {@code javax.imageio} can read it
     */
    public static Size read(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(key, BasicFileAttributes.class);
        long length = attrs.size();
        long modified = attrs.lastModifiedTime().toMillis();
        Cached hit = CACHE.get(key);
        if (hit != null && hit.length == length && hit.modified == modified) {
            return hit.size;
        }
        Size size = parse(key);
        if (size == null) {
            Map<String, Integer> dims = UtilityFunctions.getTiffDimensions(key.toFile());
            if (dims == null) {
                throw new IOException("Cannot read the image size of " + file);
            }
            size = new Size(dims.get("width"), dims.get("height"));
        }
        if (CACHE.size() >= MAX_CACHED) {
            CACHE.clear();
        }
        CACHE.put(key, new Cached(length, modified, size));
        return size;
    }

    /**
     * The sizes of many tiles, read on several threads.
     *
     * @param files the tiles
     * @param progress told how many files are done so far, on the calling thread; may be null
     * @return each readable file's size, in the order given; a file that cannot be read is absent
     *     (and logged)
     */
    public static Map<Path, Size> readAll(Collection<Path> files, IntConsumer progress) {
        Map<Path, Size> out = new LinkedHashMap<>();
        if (files.isEmpty()) {
            return out;
        }
        long start = System.nanoTime();
        int readsBefore = HEADER_READS.get();
        List<Path> list = new ArrayList<>(files);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, list.size()), r -> {
            Thread t = new Thread(r, "tile-headers");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Size>> futures = new ArrayList<>(list.size());
            for (Path p : list) {
                futures.add(pool.submit(() -> read(p)));
            }
            for (int i = 0; i < list.size(); i++) {
                try {
                    out.put(list.get(i), futures.get(i).get());
                } catch (ExecutionException e) {
                    logger.warn(
                            "Could not read the size of {}: {}",
                            list.get(i),
                            e.getCause().getMessage());
                }
                if (progress != null && ((i + 1) % 50 == 0 || i + 1 == list.size())) {
                    progress.accept(i + 1);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted reading tile sizes; {} of {} read", out.size(), list.size());
        } finally {
            pool.shutdownNow();
        }
        int read = HEADER_READS.get() - readsBefore;
        logger.info(
                "Tile sizes for {} file(s) in {} ms: {} header(s) read, {} remembered from earlier this session",
                list.size(),
                (System.nanoTime() - start) / 1_000_000,
                read,
                Math.max(0, out.size() - read));
        return out;
    }

    /** Forget every remembered size; for tests and measurements. */
    public static void clearCache() {
        CACHE.clear();
    }

    /** @return how many headers this session has parsed rather than taken from the cache */
    static int headerReads() {
        return HEADER_READS.get();
    }

    /**
     * Width and height of the first image directory, read directly. Classic TIFF and BigTIFF, either
     * byte order.
     *
     * @return the size, or null when the file is not a TIFF this parser understands
     */
    static Size parse(Path file) {
        HEADER_READS.incrementAndGet();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Header h = header(ch);
            if (h == null) {
                return null;
            }
            Directory d = directory(ch, h, h.firstIfd);
            return d == null || d.width <= 0 || d.height <= 0 ? null : new Size(d.width, d.height);
        } catch (IOException | RuntimeException e) {
            logger.debug("Header parse failed for {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * Every image directory of a TIFF, in file order, with the offset of its first strip or tile:
     * how a byte offset recorded elsewhere (an NDTiff index) is turned into a page number.
     *
     * @param file a TIFF
     * @return the first strip (or tile) offset of each page, page 0 first
     * @throws IOException when the file is not a TIFF this parser understands
     */
    static List<Long> pageDataOffsets(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Header h = header(ch);
            if (h == null) {
                throw new IOException(file.getFileName() + " is not a TIFF");
            }
            List<Long> out = new ArrayList<>();
            long ifd = h.firstIfd;
            long size = ch.size();
            while (ifd > 0 && ifd < size) {
                Directory d = directory(ch, h, ifd);
                if (d == null) {
                    throw new IOException("Unreadable image directory at byte " + ifd + " of " + file.getFileName());
                }
                out.add(d.firstDataOffset);
                if (out.size() > 10_000_000) {
                    throw new IOException("Image directories of " + file.getFileName() + " form a loop");
                }
                ifd = d.next;
            }
            return out;
        }
    }

    private record Header(ByteOrder order, boolean big, long firstIfd) {}

    private record Directory(int width, int height, long firstDataOffset, long next) {}

    private static Header header(FileChannel ch) throws IOException {
        ByteBuffer b = readAt(ch, 0, 16);
        if (b.remaining() < 8) {
            return null;
        }
        byte b0 = b.get(0);
        byte b1 = b.get(1);
        ByteOrder order;
        if (b0 == 'I' && b1 == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (b0 == 'M' && b1 == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return null;
        }
        b.order(order);
        int magic = Short.toUnsignedInt(b.getShort(2));
        if (magic == 42) {
            return new Header(order, false, Integer.toUnsignedLong(b.getInt(4)));
        }
        if (magic == 43 && b.remaining() >= 16 && b.getShort(4) == 8) {
            return new Header(order, true, b.getLong(8));
        }
        return null;
    }

    private static Directory directory(FileChannel ch, Header h, long at) throws IOException {
        int countBytes = h.big ? 8 : 2;
        int entryBytes = h.big ? 20 : 12;
        int nextBytes = h.big ? 8 : 4;
        ByteBuffer c = readAt(ch, at, countBytes).order(h.order);
        if (c.remaining() < countBytes) {
            return null;
        }
        long count = h.big ? c.getLong(0) : Short.toUnsignedInt(c.getShort(0));
        if (count <= 0 || count > 10_000) {
            return null;
        }
        int len = (int) (count * entryBytes + nextBytes);
        ByteBuffer e = readAt(ch, at + countBytes, len).order(h.order);
        if (e.remaining() < len) {
            return null;
        }
        int width = -1;
        int height = -1;
        long dataOffset = -1;
        for (int i = 0; i < count; i++) {
            int p = i * entryBytes;
            int tag = Short.toUnsignedInt(e.getShort(p));
            if (tag != TAG_IMAGE_WIDTH
                    && tag != TAG_IMAGE_LENGTH
                    && tag != TAG_STRIP_OFFSETS
                    && tag != TAG_TILE_OFFSETS) {
                continue;
            }
            int type = Short.toUnsignedInt(e.getShort(p + 2));
            long n = h.big ? e.getLong(p + 4) : Integer.toUnsignedLong(e.getInt(p + 4));
            int valueAt = p + (h.big ? 12 : 8);
            int typeBytes = typeBytes(type);
            if (typeBytes == 0 || n < 1) {
                continue;
            }
            long first;
            if (n * typeBytes <= (h.big ? 8 : 4)) {
                first = value(e, valueAt, type);
            } else {
                // An offset list stored elsewhere: the first entry is all a page number needs.
                long where = h.big ? e.getLong(valueAt) : Integer.toUnsignedLong(e.getInt(valueAt));
                ByteBuffer v = readAt(ch, where, typeBytes).order(h.order);
                if (v.remaining() < typeBytes) {
                    continue;
                }
                first = value(v, 0, type);
            }
            switch (tag) {
                case TAG_IMAGE_WIDTH -> width = (int) first;
                case TAG_IMAGE_LENGTH -> height = (int) first;
                default -> {
                    if (dataOffset < 0) {
                        dataOffset = first;
                    }
                }
            }
        }
        long next = h.big
                ? e.getLong((int) (count * entryBytes))
                : Integer.toUnsignedLong(e.getInt((int) (count * entryBytes)));
        return new Directory(width, height, dataOffset, next);
    }

    /** Bytes per value of the integer TIFF field types; 0 for any other type. */
    private static int typeBytes(int type) {
        return switch (type) {
            case 1 -> 1; // BYTE
            case 3 -> 2; // SHORT
            case 4, 13 -> 4; // LONG, IFD
            case 16, 18 -> 8; // LONG8, IFD8 (BigTIFF)
            default -> 0;
        };
    }

    private static long value(ByteBuffer b, int at, int type) {
        return switch (type) {
            case 1 -> Byte.toUnsignedInt(b.get(at));
            case 3 -> Short.toUnsignedInt(b.getShort(at));
            case 4, 13 -> Integer.toUnsignedLong(b.getInt(at));
            default -> b.getLong(at);
        };
    }

    private static ByteBuffer readAt(FileChannel ch, long at, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int n = ch.read(b, at + b.position());
            if (n < 0) {
                break;
            }
        }
        b.flip();
        return b;
    }
}
