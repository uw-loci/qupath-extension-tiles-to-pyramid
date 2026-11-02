package qupath.ext.basicstitching.stitching;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which channel, z-slice and time point each plane of a Micro-Manager 2 acquisition holds, and
 * which file and page it is on. One reading for {@link MicroManagerMetadataStrategy} and
 * {@link MicroManagerPreprocessor}.
 *
 * <ul>
 *   <li><b>One OME-TIFF per position</b> ({@code _MMStack_}): from the Micro-Manager tag (51123) on
 *       each page; for pages without it, from the order of the {@code FrameKey-t-c-z} blocks in the
 *       position's sidecar, which is the order the pages were written; failing both, page {@code i}
 *       is channel {@code i} only when the acquisition has one plane per channel. Anything else is
 *       refused, never guessed. A file Micro-Manager continued past 4 GB
 *       ({@code <label>_1.ome.tif}) belongs to its position and continues its page order.</li>
 *   <li><b>Single-plane TIFF series</b>: from the file name
 *       ({@code img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif}, any subset of those axes in any
 *       order), else from the file's {@code Metadata-} or {@code Coords-} block in metadata.txt.</li>
 * </ul>
 */
public final class MicroManagerPlanes {
    private static final Logger logger = LoggerFactory.getLogger(MicroManagerPlanes.class);

    /** The tag Micro-Manager writes on every page, holding that image's metadata as JSON. */
    static final int MM_METADATA_TAG = 51123;

    static final Pattern FRAME_KEY = Pattern.compile("FrameKey-(\\d+)-(\\d+)-(\\d+)");
    private static final Pattern SINGLE_PLANE_NAME =
            Pattern.compile("img((?:_(?:channel|position|time|z)\\d+)+)\\.tiff?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AXIS = Pattern.compile("_(channel|position|time|z)(\\d+)", Pattern.CASE_INSENSITIVE);
    /** The label of a continuation file Micro-Manager starts past 4 GB: {@code <label>_1}. */
    private static final Pattern CONTINUATION = Pattern.compile("^(.*)_(\\d+)$");

    private MicroManagerPlanes() {}

    /** A plane's channel, z-slice and time point, all 0-based. */
    public record Coord(int c, int z, int t) {
        /** The first channel, slice and time point: what page 0 of a position always holds. */
        public static final Coord ORIGIN = new Coord(0, 0, 0);
    }

    /**
     * One plane on disk.
     *
     * @param file the TIFF holding it
     * @param page its page within the file, as {@code javax.imageio} counts them
     * @param coord its channel, slice and time point
     */
    public record Plane(Path file, int page, Coord coord) {}

    /** Channels, slices and frames from an acquisition's Summary; 0 for a value it does not give. */
    public record Dims(int channels, int slices, int frames) {
        /** @return the Summary's counts, or (0, 0, 0) when there is no Summary */
        static Dims of(JsonObject summary) {
            if (summary == null) return new Dims(0, 0, 0);
            return new Dims(intOr(summary, "Channels", 0), intOr(summary, "Slices", 0), intOr(summary, "Frames", 0));
        }
    }

    /** A sidecar read only when a page needs it. */
    @FunctionalInterface
    public interface SidecarSource {
        /** @return the sidecar JSON, or null when there is none */
        JsonObject get() throws IOException;
    }

    /**
     * Channel, slice and frame from a single-plane file's name, else from its metadata.txt blocks.
     *
     * @param name the file name
     * @param blocks the file's {@code Metadata-} and {@code Coords-} blocks, any of them null
     * @return the coordinates, or null when neither the name nor a block gives them
     */
    public static Coord singlePlaneCoord(String name, JsonElement... blocks) {
        Coord fromName = fromFileName(name);
        if (fromName != null) return fromName;
        for (JsonElement block : blocks) {
            Coord c = fromBlock(block);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * Coordinates from a Micro-Manager 2 single-plane file name: {@code img} followed by
     * {@code _channelNNN}, {@code _positionNNN}, {@code _timeNNN} and {@code _zNNN} in any order and
     * any subset, an absent axis being 0.
     *
     * @return the coordinates, or null when the name is not of that form
     */
    static Coord fromFileName(String name) {
        if (name == null || !SINGLE_PLANE_NAME.matcher(name).matches()) return null;
        int c = 0, z = 0, t = 0;
        Matcher m = AXIS.matcher(name);
        while (m.find()) {
            int v = Integer.parseInt(m.group(2));
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "channel" -> c = v;
                case "time" -> t = v;
                case "z" -> z = v;
                default -> {}
            }
        }
        return new Coord(c, z, t);
    }

    /** Coordinates from a block carrying {@code ChannelIndex} and {@code FrameIndex}; null otherwise. */
    static Coord fromBlock(JsonElement block) {
        if (block == null || !block.isJsonObject()) return null;
        JsonObject b = block.getAsJsonObject();
        if (!b.has("ChannelIndex") || !b.has("FrameIndex")) return null;
        return new Coord(intOr(b, "ChannelIndex", 0), intOr(b, "SliceIndex", 0), intOr(b, "FrameIndex", 0));
    }

    /** Channel, slice and frame from the page's Micro-Manager tag, or null when it has none. */
    static Coord tagCoord(ImageReader reader, int page) {
        try {
            TIFFDirectory dir = TIFFDirectory.createFromMetadata(reader.getImageMetadata(page));
            TIFFField field = dir.getTIFFField(MM_METADATA_TAG);
            if (field == null) return null;
            return fromBlock(JsonParser.parseString(field.getAsString(0)));
        } catch (Exception e) {
            logger.debug("No Micro-Manager tag on page {}: {}", page, e.toString());
            return null;
        }
    }

    /** The sidecar's {@code FrameKey-t-c-z} blocks in file order, which is the order pages were written. */
    static List<Coord> frameKeyOrder(JsonObject sidecar) {
        List<Coord> out = new ArrayList<>();
        if (sidecar == null) return out;
        for (String key : sidecar.keySet()) {
            Matcher m = FRAME_KEY.matcher(key);
            if (m.matches()) {
                out.add(new Coord(
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1))));
            }
        }
        return out;
    }

    /**
     * Group {@code _MMStack_} files by position: each position's first file, then the files
     * Micro-Manager continued it into past 4 GB ({@code <label>_1.ome.tif}, {@code _2}, ...), in
     * that order. Files that are not {@code _MMStack_} files are left out.
     *
     * @param tiffs TIFF files, from any number of folders
     * @param positionLabels the acquisition's position labels ({@code Summary.StagePositions}); a
     *     file whose label is one of them is a position of its own, never a continuation
     * @return first file of each position -> all of that position's files, in input order of the first files
     */
    public static Map<Path, List<Path>> stackFilesByPosition(List<Path> tiffs, java.util.Set<String> positionLabels) {
        Map<Path, List<Path>> out = new LinkedHashMap<>();
        Map<Path, Integer> part = new LinkedHashMap<>();
        for (Path p : tiffs) {
            Path first = firstFileOf(p, positionLabels);
            if (first == null) continue;
            out.computeIfAbsent(first, k -> new ArrayList<>()).add(p);
            part.put(p, p.equals(first) ? 0 : continuationNumber(p));
        }
        for (List<Path> files : out.values()) {
            files.sort(Comparator.comparingInt(part::get));
        }
        return out;
    }

    /**
     * The first file of the position {@code tif} belongs to: {@code tif} itself, or for a
     * continuation file the file it continues, when that exists beside it.
     *
     * @return the first file, or null when {@code tif} is not an {@code _MMStack_} file
     */
    static Path firstFileOf(Path tif, java.util.Set<String> positionLabels) {
        String name = tif.getFileName().toString();
        String label = MicroManagerMetadataStrategy.extractMMStackLabel(name);
        if (label == null) return null;
        Matcher m = CONTINUATION.matcher(label);
        if (m.matches() && !positionLabels.contains(label)) {
            int at = name.indexOf("_MMStack_") + "_MMStack_".length();
            String firstName = name.substring(0, at) + m.group(1) + name.substring(at + label.length());
            Path first = tif.resolveSibling(firstName);
            if (Files.isRegularFile(first)) return first;
        }
        return tif;
    }

    private static int continuationNumber(Path tif) {
        String label = MicroManagerMetadataStrategy.extractMMStackLabel(
                tif.getFileName().toString());
        Matcher m = CONTINUATION.matcher(label == null ? "" : label);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /**
     * Every page of one position's {@code _MMStack_} files, with the plane each holds.
     *
     * @param files the position's files, first file then continuations
     * @param sidecar the position's sidecar, read only when some page carries no tag
     * @param dims the acquisition's channels, slices and frames, for the one-plane-per-channel case
     * @return the planes, in page order
     * @throws IOException when a file cannot be read, or some page's plane cannot be established
     */
    public static List<Plane> stackPlanes(List<Path> files, SidecarSource sidecar, Dims dims) throws IOException {
        List<Path> pageFile = new ArrayList<>();
        List<Integer> pageIndex = new ArrayList<>();
        List<Coord> coords = new ArrayList<>();
        int untagged = 0;
        for (Path file : files) {
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                ImageReader reader = MicroManagerPreprocessor.tiffReader(in, file);
                try {
                    reader.setInput(in, false, false);
                    int pages = reader.getNumImages(true);
                    for (int i = 0; i < pages; i++) {
                        Coord c = tagCoord(reader, i);
                        if (c == null) untagged++;
                        pageFile.add(file);
                        pageIndex.add(i);
                        coords.add(c);
                    }
                } finally {
                    reader.dispose();
                }
            }
        }
        int total = coords.size();
        if (untagged > 0) {
            List<Coord> order = frameKeyOrder(sidecar.get());
            boolean onePerChannel = total == dims.channels() && dims.slices() <= 1 && dims.frames() <= 1;
            for (int i = 0; i < total; i++) {
                if (coords.get(i) != null) continue;
                if (order.size() >= total) {
                    coords.set(i, order.get(i));
                } else if (onePerChannel) {
                    coords.set(i, new Coord(i, 0, 0));
                } else {
                    throw new IOException("Cannot tell which channel, slice and time point page " + pageIndex.get(i)
                            + " of " + pageFile.get(i).getFileName() + " holds: it carries no Micro-Manager tag,"
                            + " the sidecar lists " + order.size() + " frame key(s) for " + total + " page(s),"
                            + " and the acquisition (" + dims.channels() + " channel(s), " + dims.slices()
                            + " slice(s), " + dims.frames() + " time point(s)) is not one page per channel");
                }
            }
        }
        List<Plane> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            out.add(new Plane(pageFile.get(i), pageIndex.get(i), coords.get(i)));
        }
        return out;
    }

    /** @return the {@code Label} of every entry in {@code Summary.StagePositions}; empty without one */
    static java.util.Set<String> positionLabels(JsonObject summary) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (summary == null
                || !summary.has("StagePositions")
                || !summary.get("StagePositions").isJsonArray()) {
            return out;
        }
        for (JsonElement e : summary.getAsJsonArray("StagePositions")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("Label")) {
                try {
                    out.add(e.getAsJsonObject().get("Label").getAsString());
                } catch (RuntimeException ex) {
                    // A label that is not a string names no position.
                }
            }
        }
        return out;
    }

    /** The sidecar beside an {@code _MMStack_} position's first file: {@code <stem>_metadata.txt}. */
    static Path sidecarOf(Path firstFile) {
        return firstFile.resolveSibling(stemOf(firstFile.getFileName().toString()) + "_metadata.txt");
    }

    /** File name without {@code .ome.tif} / {@code .tif}. */
    static String stemOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".ome.tiff", ".ome.tif", ".tiff", ".tif")) {
            if (lower.endsWith(ext)) return name.substring(0, name.length() - ext.length());
        }
        return name;
    }

    /** @return the JSON object in {@code p}, or null when the file holds something else */
    static JsonObject readJson(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new IOException("Cannot parse " + p.getFileName() + ": " + e.getMessage(), e);
        }
    }

    static int intOr(JsonObject o, String key, int fallback) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
