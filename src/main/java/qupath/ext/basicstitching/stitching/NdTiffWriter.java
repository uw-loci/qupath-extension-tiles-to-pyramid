package qupath.ext.basicstitching.stitching;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes an NDTiff v3 dataset: the reduced copy {@link MicroManagerPreprocessor} makes of an NDTiff
 * acquisition, read back by {@link NdTiffDataset}.
 *
 * <p>Follows the specification in the NDTiffStorage README ("Specification for NDTiff v3.x",
 * github.com/micro-manager/NDTiffStorage, commit b2b8383) and the image directory its
 * {@code NDTiffWriter.writeIFD} writes, without its X/Y resolution tags: per image a little-endian
 * TIFF IFD (width, height, bits per sample, compression 1, photometric 1, one strip, samples per
 * pixel 1, rows per strip, strip byte count, and tag 51123 pointing at the image's metadata JSON),
 * then the pixels, then the metadata. Each stack file starts with the TIFF header, 483729, the major
 * and minor version, 2355492, the summary length and the summary JSON. A stack file is closed before
 * it would pass 4 GB (the index holds 32-bit offsets) and the next one is named {@code _1},
 * {@code _2}, ... Only 8- and 16-bit grayscale images are written (pixel types 0 and 1).
 */
final class NdTiffWriter implements Closeable {

    private static final int MAGIC = 483729;
    private static final int SUMMARY_HEADER = 2355492;
    private static final int MAJOR = 3;
    /** 3.2: axis values may be strings (channel names). */
    private static final int MINOR = 2;

    private static final int ENTRIES = 10;
    /** A stack file is not written past this many bytes; offsets in the index are unsigned 32-bit. */
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 - 1;

    private final Path dir;
    private final String prefix;
    private final byte[] summary;
    private final long maxFileBytes;
    private final ByteArrayOutputStream index = new ByteArrayOutputStream();

    private FileChannel file;
    private String fileName;
    private int files;
    /** Where the last IFD's next-IFD field is, set to 0 when the file is finished. */
    private long lastNextField = -1;

    /**
     * @param dir the dataset folder, created if absent
     * @param prefix the stack files are {@code <prefix>_NDTiffStack.tif}, {@code _1}, ...
     * @param summary the summary metadata written at the start of every stack file
     */
    NdTiffWriter(Path dir, String prefix, JsonObject summary) throws IOException {
        this(dir, prefix, summary, MAX_FILE_BYTES);
    }

    /** As above, with a smaller file size limit (tests). */
    NdTiffWriter(Path dir, String prefix, JsonObject summary, long maxFileBytes) throws IOException {
        this.dir = Files.createDirectories(dir);
        this.prefix = prefix;
        this.summary = summary.toString().getBytes(StandardCharsets.UTF_8);
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * Append one image.
     *
     * @param axes its axes, e.g. {@code {"channel": 0, "z": 2, "position": 0}}
     * @param width image width
     * @param height image height
     * @param sixteenBit 16-bit samples (else 8-bit)
     * @param pixels the samples, row by row, 16-bit ones little-endian
     * @param metadata the image's metadata
     */
    void add(JsonObject axes, int width, int height, boolean sixteenBit, byte[] pixels, JsonObject metadata)
            throws IOException {
        int bytes = width * height * (sixteenBit ? 2 : 1);
        if (pixels.length != bytes) {
            throw new IOException("Image " + axes + " has " + pixels.length + " bytes, not " + bytes);
        }
        byte[] md = metadata.toString().getBytes(StandardCharsets.UTF_8);
        long need = 2 + ENTRIES * 12 + 4 + (long) bytes + md.length + 2;
        if (file == null || file.size() + need > maxFileBytes) {
            nextFile();
            if (file.size() + need > maxFileBytes) {
                throw new IOException("Image " + axes + " does not fit in one stack file");
            }
        }
        long ifd = file.size();
        if (ifd % 2 == 1) {
            file.write(ByteBuffer.wrap(new byte[1]), ifd);
            ifd++;
        }
        long nextField = ifd + 2 + ENTRIES * 12;
        long pixelsAt = nextField + 4;
        long mdAt = pixelsAt + bytes;
        long next = mdAt + md.length;
        if (next % 2 == 1) next++;

        ByteBuffer b = ByteBuffer.allocate(2 + ENTRIES * 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) ENTRIES);
        entry(b, 256, 4, 1, width);
        entry(b, 257, 4, 1, height);
        entry(b, 258, 3, 1, sixteenBit ? 16 : 8);
        entry(b, 259, 3, 1, 1);
        entry(b, 262, 3, 1, 1);
        entry(b, 273, 4, 1, pixelsAt);
        entry(b, 277, 3, 1, 1);
        entry(b, 278, 3, 1, height);
        entry(b, 279, 4, 1, bytes);
        entry(b, 51123, 2, md.length, mdAt);
        // As NDTiffWriter: point at where the next IFD will be; the last one is set to 0 on close.
        b.putInt((int) next);
        b.flip();
        writeFully(b, ifd);
        writeFully(ByteBuffer.wrap(pixels), pixelsAt);
        writeFully(ByteBuffer.wrap(md), mdAt);
        if (lastNextField < 0) {
            // First image of this file: the header's first-IFD offset.
            writeFully(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, (int) ifd), 4);
        }
        lastNextField = nextField;

        byte[] a = axes.toString().getBytes(StandardCharsets.UTF_8);
        byte[] fn = fileName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer e = ByteBuffer.allocate(4 + a.length + 4 + fn.length + 32).order(ByteOrder.LITTLE_ENDIAN);
        e.putInt(a.length).put(a).putInt(fn.length).put(fn);
        e.putInt((int) pixelsAt)
                .putInt(width)
                .putInt(height)
                .putInt(sixteenBit ? 1 : 0)
                .putInt(0);
        e.putInt((int) mdAt).putInt(md.length).putInt(0);
        index.writeBytes(e.array());
    }

    private void nextFile() throws IOException {
        finishFile();
        fileName = prefix + "_NDTiffStack" + (files == 0 ? "" : "_" + files) + ".tif";
        files++;
        file = FileChannel.open(
                dir.resolve(fileName),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        ByteBuffer h = ByteBuffer.allocate(28 + summary.length).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(0);
        h.putInt(MAGIC).putInt(MAJOR).putInt(MINOR).putInt(SUMMARY_HEADER).putInt(summary.length);
        h.put(summary);
        h.flip();
        writeFully(h, 0);
        lastNextField = -1;
    }

    private void finishFile() throws IOException {
        if (file == null) return;
        try {
            if (lastNextField >= 0) {
                writeFully(ByteBuffer.allocate(4), lastNextField);
            }
        } finally {
            file.close();
            file = null;
        }
    }

    private void writeFully(ByteBuffer b, long at) throws IOException {
        long pos = at;
        while (b.hasRemaining()) {
            pos += file.write(b, pos);
        }
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, long value) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            b.putShort((short) value).putShort((short) 0);
        } else {
            b.putInt((int) value);
        }
    }

    /** Finish the last stack file and write {@code NDTiff.index}. */
    @Override
    public void close() throws IOException {
        finishFile();
        Files.write(dir.resolve(NdTiffDataset.INDEX), index.toByteArray());
    }
}
