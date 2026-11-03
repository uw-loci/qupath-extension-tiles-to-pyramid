package qupath.ext.basicstitching.assembly.direct;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uncompressed copies of downsampled pyramid levels, kept in a hidden folder beside the output
 * while {@link DirectTiffOutputWriter} writes, so each level is made from the one before it
 * without compositing the mosaic again.
 *
 * <p>Plain {@link FileChannel} reads and writes only (no memory mapping), so every file can be
 * closed and deleted on Windows. The folder holds a {@code lock} file locked for the whole write;
 * a folder whose lock can be taken belongs to a run that ended without cleaning up and is deleted
 * by the next write to the same folder.
 */
final class LevelStaging implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LevelStaging.class);

    /** Name prefix of the staging folders. */
    static final String PREFIX = ".t2p-level-staging-";

    /** Free space kept in hand beyond the staged levels and the output. */
    static final long MARGIN_BYTES = 512L * 1024 * 1024;

    private static final String LOCK = "lock";

    /** Folders open in this JVM, which the stale-folder sweep must leave alone. */
    private static final Set<Path> ACTIVE = ConcurrentHashMap.newKeySet();

    /** Bytes promised to staging folders open in this JVM, per volume. */
    private static final Map<FileStore, Long> RESERVED = new HashMap<>();

    /** Free space on the volume holding a folder. */
    @FunctionalInterface
    interface FreeSpace {
        long usableBytes(Path dir) throws IOException;
    }

    /**
     * System property that, when set to a number of bytes, is taken as the free space instead of
     * the volume's own figure: {@code -Dt2p.stagingFreeBytes=0} writes every pyramid the slower
     * way, to check the fallback or compare the two.
     */
    static final String FREE_BYTES_PROPERTY = "t2p.stagingFreeBytes";

    static final FreeSpace VOLUME = dir -> {
        Long override = Long.getLong(FREE_BYTES_PROPERTY);
        return override != null ? override : Files.getFileStore(dir).getUsableSpace();
    };

    private final Path dir;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FileStore store;
    private final long reserved;
    private final Map<Integer, StagedLevel> levels = new HashMap<>();

    private LevelStaging(Path dir, FileChannel lockChannel, FileLock lock, FileStore store, long reserved) {
        this.dir = dir;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.store = store;
        this.reserved = reserved;
    }

    /**
     * Open a staging folder in {@code outputDir}, after removing stale ones, if the volume has
     * {@code stagingBytes + outputBytes + MARGIN_BYTES} free beyond what other writes in this JVM
     * have reserved.
     *
     * @return the staging folder, or null (with the reason passed to {@code warnings}) to write
     *     without staging
     */
    static LevelStaging open(
            Path outputDir, long stagingBytes, long outputBytes, FreeSpace space, Consumer<String> warnings) {
        removeStale(outputDir, warnings);
        long need = stagingBytes + outputBytes + MARGIN_BYTES;
        FileStore fileStore = null;
        long usable;
        long othersReserved = 0;
        try {
            usable = space.usableBytes(outputDir);
            fileStore = Files.getFileStore(outputDir);
        } catch (IOException e) {
            warnings.accept("Could not read the free disk space in " + outputDir + " (" + e.getMessage()
                    + "), so the pyramid levels were made the slower way, each from full resolution.");
            return null;
        }
        synchronized (RESERVED) {
            othersReserved = RESERVED.getOrDefault(fileStore, 0L);
            if (usable - othersReserved < need) {
                warnings.accept(String.format(
                        Locale.ROOT,
                        "Not enough free disk space beside the output to stage the pyramid levels: %s needed"
                                + " (%s staged levels + %s output + %s margin), %s free in %s%s. The pyramid levels"
                                + " were made the slower way, each from full resolution; the image is the same.",
                        gb(need),
                        gb(stagingBytes),
                        gb(outputBytes),
                        gb(MARGIN_BYTES),
                        gb(usable),
                        outputDir,
                        othersReserved > 0
                                ? " (" + gb(othersReserved) + " of it held by other stitches running now)"
                                : ""));
                return null;
            }
            RESERVED.merge(fileStore, need, Long::sum);
        }
        Path dir = outputDir.resolve(PREFIX + UUID.randomUUID());
        // Marked active before it exists, so a sweep by a stitch running beside this one never
        // takes it for stale in the moment before its lock is held.
        ACTIVE.add(dir);
        FileChannel lockChannel = null;
        try {
            Files.createDirectory(dir);
            hide(dir);
            lockChannel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FileLock lock = lockChannel.lock();
            lockChannel.write(ByteBuffer.wrap(("Pyramid levels staged by Tiles to Pyramid, process "
                            + ProcessHandle.current().pid() + ". Safe to delete when no stitch is running.\n")
                    .getBytes(StandardCharsets.US_ASCII)));
            logger.info(
                    "Staging pyramid levels in {} (up to {}; {} free, {} needed with the output)",
                    dir,
                    gb(stagingBytes),
                    gb(usable),
                    gb(need));
            return new LevelStaging(dir, lockChannel, lock, fileStore, need);
        } catch (IOException e) {
            closeQuietly(lockChannel);
            deleteTree(dir);
            ACTIVE.remove(dir);
            unreserve(fileStore, need);
            warnings.accept("Could not create a folder to stage the pyramid levels in " + outputDir + " ("
                    + e.getMessage() + "), so they were made the slower way, each from full resolution.");
            return null;
        }
    }

    /**
     * Create the staged copy of one level, {@code width x height} per plane.
     */
    StagedLevel create(int level, int width, int height, int tileSize, int bytesPerPixel) throws IOException {
        Path file = dir.resolve("level" + level + ".raw");
        FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        StagedLevel staged = new StagedLevel(file, channel, width, height, tileSize, bytesPerPixel);
        levels.put(level, staged);
        return staged;
    }

    /**
     * Close and delete one level once the level after it is written. A file that cannot be
     * deleted yet (a virus scanner holding it on Windows) is left for {@link #close()}.
     */
    void release(int level) throws IOException {
        StagedLevel staged = levels.remove(level);
        if (staged != null) {
            staged.channel.close();
            try {
                Files.deleteIfExists(staged.file);
            } catch (IOException e) {
                logger.debug("Staged level {} not deleted yet: {}", staged.file, e.getMessage());
            }
        }
    }

    Path dir() {
        return dir;
    }

    @Override
    public void close() {
        for (StagedLevel staged : levels.values()) {
            closeQuietly(staged.channel);
        }
        levels.clear();
        try {
            lock.release();
        } catch (IOException e) {
            logger.debug("Releasing the staging lock: {}", e.getMessage());
        }
        closeQuietly(lockChannel);
        ACTIVE.remove(dir);
        if (!deleteTree(dir)) {
            logger.warn("Could not delete the pyramid staging folder {}; delete it by hand", dir);
        }
        unreserve(store, reserved);
    }

    /** Delete staging folders in {@code outputDir} that no running write holds. */
    static void removeStale(Path outputDir, Consumer<String> warnings) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir, PREFIX + "*")) {
            stream.forEach(found::add);
        } catch (IOException e) {
            logger.debug("Listing {} for stale staging folders: {}", outputDir, e.getMessage());
            return;
        }
        for (Path old : found) {
            if (!Files.isDirectory(old) || ACTIVE.contains(old)) {
                continue;
            }
            if (!unlocked(old)) {
                continue;
            }
            if (deleteTree(old)) {
                logger.info("Deleted the staging folder {} left by an earlier stitch that did not finish", old);
            } else {
                warnings.accept("A staging folder left by an earlier stitch that did not finish could not be"
                        + " deleted: " + old + ". Delete it by hand when no stitch is running.");
            }
        }
    }

    /**
     * Whether no process holds {@code folder}'s lock. A folder without a lock file counts as free
     * once it is ten minutes old (another process may be about to create its lock).
     */
    private static boolean unlocked(Path folder) {
        Path lockFile = folder.resolve(LOCK);
        if (!Files.exists(lockFile)) {
            try {
                long age = System.currentTimeMillis()
                        - Files.getLastModifiedTime(folder).toMillis();
                return age > 10 * 60 * 1000L;
            } catch (IOException e) {
                return false;
            }
        }
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) {
                return false;
            }
            held.release();
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            logger.debug("Could not test the lock of {}: {}", folder, e.getMessage());
            return false;
        }
    }

    private static void hide(Path dir) {
        try {
            Files.setAttribute(dir, "dos:hidden", true);
        } catch (Exception e) {
            // Not a DOS file system; the leading dot hides it.
        }
    }

    private static boolean deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException e) {
            logger.debug("Deleting {}: {}", dir, e.getMessage());
            return false;
        }
    }

    private static void unreserve(FileStore fileStore, long bytes) {
        if (fileStore == null) {
            return;
        }
        synchronized (RESERVED) {
            RESERVED.computeIfPresent(fileStore, (k, v) -> v - bytes <= 0 ? null : v - bytes);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                logger.debug("Closing a staging file: {}", e.getMessage());
            }
        }
    }

    static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1e9);
    }

    /**
     * One staged level: every staged plane of it, uncompressed, as the packed bytes the writer
     * gives Bio-Formats. Written in blocks, each the 2 x 2 average of one tile of the level
     * before, and read back a tile at a time.
     *
     * <p>Block of tile (tx, ty) of the level before, in plane slot s, starts at byte
     * {@code bpp * (s*W*H + ty*(T/2)*W + tx*(T/2)*h)}, where W x H is this level, T the tile size
     * and h that block's height: rows of blocks are T/2 high except the last, blocks are T/2 wide
     * except the last in a row, so the blocks fill the file exactly.
     */
    static final class StagedLevel {

        private final Path file;
        private final FileChannel channel;
        private final int width;
        private final int height;
        private final int half;
        private final int bytesPerPixel;

        private StagedLevel(Path file, FileChannel channel, int width, int height, int tileSize, int bytesPerPixel) {
            this.file = file;
            this.channel = channel;
            this.width = width;
            this.height = height;
            this.half = tileSize / 2;
            this.bytesPerPixel = bytesPerPixel;
        }

        private long offset(int slot, int tx, int ty, int blockH) {
            return (long) bytesPerPixel
                    * ((long) slot * width * height + (long) ty * half * width + (long) tx * half * blockH);
        }

        /** Store the block made from tile (tx, ty) of the level before: {@code w x h} packed samples. */
        void writeBlock(int slot, int tx, int ty, int w, int h, byte[] data) throws IOException {
            if (w <= 0 || h <= 0) {
                return;
            }
            ByteBuffer buf = ByteBuffer.wrap(data, 0, w * h * bytesPerPixel);
            long pos = offset(slot, tx, ty, h);
            while (buf.hasRemaining()) {
                pos += channel.write(buf, pos);
            }
        }

        /** Packed samples of tile (tx, ty) of this level, {@code w x h}. */
        byte[] readTile(int slot, int tx, int ty, int w, int h) throws IOException {
            int bpp = bytesPerPixel;
            byte[] tile = new byte[w * h * bpp];
            int w0 = Math.min(half, w);
            int h0 = Math.min(half, h);
            for (int by = 0; by < 2; by++) {
                int bh = by == 0 ? h0 : h - h0;
                if (bh <= 0) {
                    continue;
                }
                for (int bx = 0; bx < 2; bx++) {
                    int bw = bx == 0 ? w0 : w - w0;
                    if (bw <= 0) {
                        continue;
                    }
                    byte[] block = new byte[bw * bh * bpp];
                    ByteBuffer buf = ByteBuffer.wrap(block);
                    long pos = offset(slot, 2 * tx + bx, 2 * ty + by, bh);
                    while (buf.hasRemaining()) {
                        int n = channel.read(buf, pos);
                        if (n < 0) {
                            throw new IOException("Staged pyramid level ends early: " + file);
                        }
                        pos += n;
                    }
                    int rowBytes = bw * bpp;
                    for (int y = 0; y < bh; y++) {
                        System.arraycopy(block, y * rowBytes, tile, ((by * h0 + y) * w + bx * w0) * bpp, rowBytes);
                    }
                }
            }
            return tile;
        }
    }
}
