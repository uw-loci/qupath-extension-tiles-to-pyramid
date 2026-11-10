package qupath.ext.basicstitching.stitching;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A Micro-Manager NDTiff dataset (version 2 and 3), read from its {@code NDTiff.index}: each
 * image's axes, which {@code *_NDTiffStack*.tif} file and page holds it, and its metadata.
 *
 * <p>Written from the format specification in the NDTiffStorage README ("Specification for NDTiff
 * v3.x", github.com/micro-manager/NDTiffStorage, commit b2b8383) and checked against the
 * {@code mm_mda_tcz_15} and {@code ndtiffv3.0_test} datasets of the NDTiff test data (Zenodo,
 * doi:10.5281/zenodo.7065552). No NDTiffStorage code is used: its Java reader depends on MMCoreJ.
 *
 * <p>The index is a run of entries, all integers little-endian: the length and UTF-8 JSON of the
 * image's axes ({@code {"channel": 0, "z": 1, "position": 0, "time": 0}}), the length and name of
 * its file, then eight 32-bit fields: pixel offset, width, height, pixel type, pixel compression,
 * metadata offset, metadata length, metadata compression. The stack files are ordinary TIFFs
 * holding one page per image, so the page of an entry is the page whose pixel data starts at the
 * entry's pixel offset. Each file starts with the TIFF header, the NDTiff magic number 483729, the
 * major version, (from v3) the minor version, then the summary metadata header 2355492, its length
 * and the summary JSON.
 *
 * <p>Axes map to planes as in Micro-Manager's other layouts: {@code channel} (an index, or from
 * v3.2 a name), {@code z} and {@code time} each give that coordinate (values ranked, so a negative
 * or sparse index still counts from 0), and every other axis ({@code position}, or
 * {@code row}/{@code column} of a tiled dataset) together names the position. A position's stage
 * coordinates come from its first image's {@code XPositionUm}/{@code YPositionUm}.
 */
public final class NdTiffDataset {

    /** The index file's name. */
    public static final String INDEX = "NDTiff.index";

    /** Where version 2 (and a multi-resolution dataset) keeps the full-resolution images. */
    static final String FULL_RESOLUTION = "Full resolution";

    private static final int NDTIFF_MAGIC = 483729;
    private static final int SUMMARY_HEADER = 2355492;

    private static final java.util.Set<String> PLANE_AXES = java.util.Set.of("channel", "z", "time");

    /** One index entry. */
    public record Entry(
            JsonObject axes,
            String fileName,
            long pixelOffset,
            int width,
            int height,
            int pixelType,
            int pixelCompression,
            long metadataOffset,
            int metadataLength,
            int metadataCompression) {}

    /**
     * One image placed as a plane of a position.
     *
     * @param file the stack file holding it
     * @param page its page in that file
     * @param coord its channel, z-slice and time point, from 0
     * @param position the axes naming its position, as compact JSON; "{}" for a dataset of one position
     * @param entry its index entry
     */
    public record Image(Path file, int page, MicroManagerPlanes.Coord coord, String position, Entry entry) {}

    private final Path dir;
    private final JsonObject summary;
    private final List<Image> images;
    private final List<String> channelNames;

    private NdTiffDataset(Path dir, JsonObject summary, List<Image> images, List<String> channelNames) {
        this.dir = dir;
        this.summary = summary;
        this.images = images;
        this.channelNames = channelNames;
    }

    /** @return the folder holding the index */
    public Path dir() {
        return dir;
    }

    /** @return the summary metadata of the first stack file; an empty object when it has none */
    public JsonObject summary() {
        return summary;
    }

    /** @return every image, in index order, the last entry of any repeated axes winning */
    public List<Image> images() {
        return images;
    }

    /** @return the channel names in channel order: Summary.ChNames, else the axis' own names */
    public List<String> channelNames() {
        return channelNames;
    }

    /**
     * @param folder the folder a user selected
     * @return the folder holding an {@code NDTiff.index}: {@code folder} or its
     *     {@code Full resolution} sub-folder; null when neither has one
     */
    public static Path locate(Path folder) {
        if (folder == null) return null;
        if (Files.isRegularFile(folder.resolve(INDEX))) return folder;
        Path full = folder.resolve(FULL_RESOLUTION);
        if (Files.isRegularFile(full.resolve(INDEX))) return full;
        return null;
    }

    /**
     * Read a dataset.
     *
     * @param dir the folder holding {@code NDTiff.index}
     * @return the dataset
     * @throws IOException when the index or a stack file cannot be read, an image is compressed, or
     *     an entry points at bytes where no page starts
     */
    public static NdTiffDataset read(Path dir) throws IOException {
        try {
            return readUnchecked(dir);
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static NdTiffDataset readUnchecked(Path dir) throws IOException {
        List<Entry> entries = readIndex(dir.resolve(INDEX));
        if (entries.isEmpty()) {
            throw new IOException(INDEX + " in " + dir + " lists no images");
        }
        Map<String, Entry> byAxes = new LinkedHashMap<>();
        for (Entry e : entries) {
            byAxes.put(sortedJson(e.axes()), e);
        }
        JsonObject summary = summaryOf(dir.resolve(entries.get(0).fileName()));
        if (summary == null) summary = new JsonObject();

        // Channel names: Summary.ChNames, else the names a v3.2 channel axis carries itself.
        List<String> chNames = new ArrayList<>();
        JsonElement ch = summary.get("ChNames");
        if (ch != null && ch.isJsonArray()) {
            for (JsonElement el : ch.getAsJsonArray()) {
                chNames.add(el.isJsonPrimitive() ? el.getAsString() : null);
            }
        }
        List<String> axisNames = new ArrayList<>();
        TreeSet<Long> zs = new TreeSet<>();
        TreeSet<Long> ts = new TreeSet<>();
        TreeSet<Long> cs = new TreeSet<>();
        for (Entry e : byAxes.values()) {
            JsonElement c = e.axes().get("channel");
            if (c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isString()) {
                if (!axisNames.contains(c.getAsString())) axisNames.add(c.getAsString());
            } else if (c != null) {
                cs.add(c.getAsLong());
            }
            if (e.axes().has("z")) zs.add(e.axes().get("z").getAsLong());
            if (e.axes().has("time")) ts.add(e.axes().get("time").getAsLong());
        }
        if (chNames.isEmpty() && !axisNames.isEmpty()) chNames.addAll(axisNames);

        Map<String, Map<Long, Integer>> pages = new HashMap<>();
        List<Image> images = new ArrayList<>(byAxes.size());
        for (Entry e : byAxes.values()) {
            if (e.pixelCompression() != 0) {
                throw new IOException("Image " + e.axes() + " is compressed (code " + e.pixelCompression()
                        + "); only uncompressed NDTiff images can be stitched");
            }
            Map<Long, Integer> filePages = pages.computeIfAbsent(e.fileName(), f -> {
                try {
                    Map<Long, Integer> m = new HashMap<>();
                    List<Long> offsets = TileHeaders.pageDataOffsets(dir.resolve(f));
                    for (int i = 0; i < offsets.size(); i++) m.putIfAbsent(offsets.get(i), i);
                    return m;
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            Integer page = filePages.get(e.pixelOffset());
            if (page == null) {
                throw new IOException("Image " + e.axes() + " is listed at byte " + e.pixelOffset() + " of "
                        + e.fileName() + ", where no page's pixels start");
            }
            int c = 0;
            JsonElement cv = e.axes().get("channel");
            if (cv != null && cv.isJsonPrimitive() && cv.getAsJsonPrimitive().isString()) {
                c = chNames.indexOf(cv.getAsString());
                if (c < 0) c = chNames.size() + axisNames.indexOf(cv.getAsString());
            } else if (cv != null) {
                c = cs.headSet(cv.getAsLong()).size();
            }
            int z = e.axes().has("z")
                    ? zs.headSet(e.axes().get("z").getAsLong()).size()
                    : 0;
            int t = e.axes().has("time")
                    ? ts.headSet(e.axes().get("time").getAsLong()).size()
                    : 0;
            JsonObject pos = new JsonObject();
            for (String k : new TreeSet<>(e.axes().keySet())) {
                if (!PLANE_AXES.contains(k)) pos.add(k, e.axes().get(k));
            }
            images.add(new Image(
                    dir.resolve(e.fileName()), page, new MicroManagerPlanes.Coord(c, z, t), pos.toString(), e));
        }
        return new NdTiffDataset(dir, summary, List.copyOf(images), List.copyOf(chNames));
    }

    /**
     * @param image an image of this dataset
     * @return its metadata JSON
     * @throws IOException when it cannot be read or is compressed
     */
    public JsonObject metadata(Image image) throws IOException {
        Entry e = image.entry();
        if (e.metadataCompression() != 0) {
            throw new IOException("The metadata of image " + e.axes() + " is compressed");
        }
        try (FileChannel ch = FileChannel.open(image.file(), StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(e.metadataLength());
            while (b.hasRemaining() && ch.read(b, e.metadataOffset() + b.position()) >= 0) {}
            JsonElement md = JsonParser.parseString(new String(b.array(), 0, b.position(), StandardCharsets.UTF_8));
            return md.isJsonObject() ? md.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("Cannot read the metadata of image " + e.axes() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * The first image of each position, in the order positions first appear in the index.
     *
     * @return position -> its first image
     */
    public Map<String, Image> firstImageOfEachPosition() {
        Map<String, Image> out = new LinkedHashMap<>();
        for (Image im : images) out.putIfAbsent(im.position(), im);
        return out;
    }

    /** @return the number of distinct channels among the images */
    public int channelCount() {
        return images.stream().mapToInt(i -> i.coord().c() + 1).max().orElse(0);
    }

    /**
     * Read every entry of an {@code NDTiff.index}. A zero-length axes field ends the index early, as
     * an unfinished dataset leaves it; a truncated final entry is ignored.
     */
    static List<Entry> readIndex(Path index) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(index)).order(ByteOrder.LITTLE_ENDIAN);
        List<Entry> out = new ArrayList<>();
        while (b.remaining() >= 4) {
            int axesLen = b.getInt();
            if (axesLen <= 0 || axesLen > b.remaining()) break;
            byte[] axes = new byte[axesLen];
            b.get(axes);
            if (b.remaining() < 4) break;
            int nameLen = b.getInt();
            if (nameLen <= 0 || nameLen > b.remaining()) break;
            byte[] name = new byte[nameLen];
            b.get(name);
            if (b.remaining() < 32) break;
            long pixelOffset = Integer.toUnsignedLong(b.getInt());
            int width = b.getInt();
            int height = b.getInt();
            int pixelType = b.getInt();
            int pixelCompression = b.getInt();
            long mdOffset = Integer.toUnsignedLong(b.getInt());
            int mdLength = b.getInt();
            int mdCompression = b.getInt();
            JsonElement ax;
            try {
                ax = JsonParser.parseString(new String(axes, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException("Unreadable axes in " + index + ": " + e.getMessage(), e);
            }
            if (!ax.isJsonObject()) {
                throw new IOException("Axes in " + index + " are not a JSON object: " + ax);
            }
            out.add(new Entry(
                    ax.getAsJsonObject(),
                    new String(name, StandardCharsets.UTF_8),
                    pixelOffset,
                    width,
                    height,
                    pixelType,
                    pixelCompression,
                    mdOffset,
                    mdLength,
                    mdCompression));
        }
        return out;
    }

    /**
     * The summary metadata of the dataset in {@code dir} without reading its whole index: for the
     * dialog, which asks on every change of the folder field.
     *
     * @return the summary, or null when {@code dir} holds no readable dataset
     */
    public static JsonObject summaryOfDataset(Path dir) {
        try (FileChannel ch = FileChannel.open(dir.resolve(INDEX), StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            if (ch.read(b, 0) < 4) return null;
            int axesLen = b.getInt(0);
            if (axesLen <= 0 || axesLen > 1 << 20) return null;
            b.clear();
            if (ch.read(b, 4L + axesLen) < 4) return null;
            int nameLen = b.getInt(0);
            if (nameLen <= 0 || nameLen > 4096) return null;
            ByteBuffer n = ByteBuffer.allocate(nameLen);
            while (n.hasRemaining() && ch.read(n, 8L + axesLen + n.position()) > 0) {}
            String name = new String(n.array(), 0, n.position(), StandardCharsets.UTF_8);
            return summaryOf(dir.resolve(name));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The summary metadata at the start of an NDTiff stack file.
     *
     * @return the summary, or null when the file does not start like one
     */
    static JsonObject summaryOf(Path stack) throws IOException {
        try (FileChannel ch = FileChannel.open(stack, StandardOpenOption.READ)) {
            ByteBuffer h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            while (h.hasRemaining() && ch.read(h, h.position()) >= 0) {}
            if (h.position() < 28 || h.getInt(8) != NDTIFF_MAGIC) return null;
            int at = h.getInt(16) == SUMMARY_HEADER ? 16 : h.getInt(20) == SUMMARY_HEADER ? 20 : -1;
            if (at < 0) return null;
            int len = h.getInt(at + 4);
            if (len <= 0 || len > 64 * 1024 * 1024) return null;
            ByteBuffer s = ByteBuffer.allocate(len);
            while (s.hasRemaining() && ch.read(s, at + 8 + s.position()) >= 0) {}
            JsonElement e = JsonParser.parseString(new String(s.array(), 0, s.position(), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sortedJson(JsonObject o) {
        TreeMap<String, JsonElement> m = new TreeMap<>();
        for (String k : o.keySet()) m.put(k, o.get(k));
        JsonObject s = new JsonObject();
        m.forEach(s::add);
        return s.toString();
    }

    /** @return Summary.StagePositions as an array, or null */
    static JsonArray stagePositions(JsonObject summary) {
        JsonElement e = summary == null ? null : summary.get("StagePositions");
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}
