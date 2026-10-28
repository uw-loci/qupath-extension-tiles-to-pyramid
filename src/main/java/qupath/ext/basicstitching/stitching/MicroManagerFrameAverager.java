package qupath.ext.basicstitching.stitching;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.stream.ImageInputStream;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.ImageWriter;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Averages the time points of a MicroManager multi-position acquisition, writing a copy with one
 * frame per position in the same layout, so {@link MicroManagerMetadataStrategy} stitches it like
 * any single-frame acquisition. Both MicroManager layouts are handled:
 *
 * <ul>
 *   <li><b>One OME-TIFF per position</b> ({@code <prefix>_MMStack_<label>.ome.tif} plus
 *       {@code _metadata.txt}). A page's channel, slice and frame come from the MicroManager tag
 *       (51123) on every page; when a file lacks it, the order of the {@code FrameKey-t-c-z}
 *       blocks in the sidecar is used, and a file matching neither is refused rather than
 *       guessed.</li>
 *   <li><b>Single-plane TIFF series</b> (a folder per position holding
 *       {@code img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif} files and a
 *       {@code metadata.txt}). Channel, slice and frame come from the file name, or from the
 *       file's {@code Metadata-} block. The copy keeps each plane's frame-0 file name.</li>
 * </ul>
 *
 * <p>Each output plane is the mean of that channel and z-slice over every frame, rounded to the
 * source bit depth (float data stays float).
 */
public final class MicroManagerFrameAverager {
    private static final Logger logger = LoggerFactory.getLogger(MicroManagerFrameAverager.class);

    /** The tag MicroManager writes on every page, holding that image's metadata as JSON. */
    static final int MM_METADATA_TAG = 51123;

    /** Written into the output folder; its presence is what allows a later run to overwrite it. */
    public static final String RECORD_FILENAME = "frame_averaging.txt";

    /** Suffix of the default output folder, beside the source folder. */
    public static final String FOLDER_SUFFIX = "_averaged";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern FRAME_KEY = Pattern.compile("FrameKey-(\\d+)-(\\d+)-(\\d+)");
    private static final Pattern SINGLE_PLANE =
            Pattern.compile("img_channel(\\d+)_position\\d+_time(\\d+)_z(\\d+)\\.tiff?", Pattern.CASE_INSENSITIVE);
    /** Root files MicroManager writes beside the position folders, copied so the copy matches. */
    private static final List<String> ROOT_EXTRAS = List.of("comments.txt", "DisplaySettings.json");
    /** A continuation file MicroManager starts past 4 GB: {@code <label>_1.ome.tif}. */
    private static final Pattern CONTINUATION = Pattern.compile("^(.*)_(\\d+)$");

    private MicroManagerFrameAverager() {}

    /**
     * What one run did.
     *
     * @param output the folder written
     * @param positions positions written
     * @param framesPerPosition frames averaged, per position label
     * @param notes anything a reader of the stitch record should know (uneven frame counts, ...)
     */
    public record Result(Path output, int positions, Map<String, Integer> framesPerPosition, List<String> notes) {
        /** @return "12" or "10 to 12" */
        public String frameSummary() {
            int min = framesPerPosition.values().stream().mapToInt(i -> i).min().orElse(0);
            int max = framesPerPosition.values().stream().mapToInt(i -> i).max().orElse(0);
            return min == max ? String.valueOf(max) : min + " to " + max;
        }
    }

    /**
     * Frames per position of the acquisition in {@code folder}, from the first sidecar's Summary.
     *
     * @return the frame count, or 0 when the folder holds no MicroManager sidecar
     */
    public static int countFrames(File folder) {
        if (folder == null || !folder.isDirectory()) return 0;
        Path root = folder.toPath().toAbsolutePath().normalize();
        if (root.getParent() == null) return 0;
        try (var files = Files.list(root)) {
            for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                String name = p.getFileName().toString();
                Path sidecar;
                if (name.endsWith("_metadata.txt") && name.contains("_MMStack_")) {
                    sidecar = p;
                } else if (Files.isRegularFile(p.resolve("metadata.txt"))) {
                    sidecar = p.resolve("metadata.txt");
                } else {
                    continue;
                }
                JsonObject summary = summaryOf(readJson(sidecar));
                if (summary != null) {
                    return intOr(summary, "Frames", 1);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read frame count under {}: {}", folder, e.toString());
        }
        return 0;
    }

    /** @return the default output folder: {@code <source>_averaged} beside the source */
    public static Path defaultOutput(Path source) {
        Path abs = source.toAbsolutePath().normalize();
        return abs.resolveSibling(abs.getFileName() + FOLDER_SUFFIX);
    }

    /**
     * Average every position in {@code source} into {@code output}.
     *
     * @param source the acquisition folder: {@code _MMStack_} files and their sidecars, or one
     *     sub-folder per position (single-plane TIFF series)
     * @param output folder to write; created if absent. An existing folder is written into only
     *     when it is empty or was written by this class (it holds {@link #RECORD_FILENAME}).
     * @return what was written
     * @throws IOException when nothing could be averaged, a file's page order cannot be
     *     established, or the output folder belongs to something else
     */
    public static Result average(Path source, Path output) throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path out = output.toAbsolutePath().normalize();
        if (src.equals(out)) {
            throw new IOException("The averaged copy cannot be written over its source folder " + src);
        }
        prepareOutput(out);

        Map<String, Integer> frames = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Map<String, List<Path>> filesByPosition = positionFiles(src);
        List<Path> positionDirs = filesByPosition.isEmpty() ? singlePlaneDirs(src) : List.of();
        if (filesByPosition.isEmpty() && positionDirs.isEmpty()) {
            throw new IOException("No MicroManager acquisition in " + src + ": neither *_MMStack_*.ome.tif files"
                    + " nor position folders holding a metadata.txt");
        }
        for (Path dir : positionDirs) {
            averageSinglePlanePosition(dir, out.resolve(dir.getFileName().toString()), src, frames, notes);
        }
        for (String extra : ROOT_EXTRAS) {
            Path f = src.resolve(extra);
            if (Files.isRegularFile(f)) {
                Files.copy(f, out.resolve(extra), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (var entry : filesByPosition.entrySet()) {
            String label = entry.getKey();
            List<Path> files = entry.getValue();
            Path first = files.get(0);
            String stem = stemOf(first.getFileName().toString());
            Path sidecarIn = first.resolveSibling(stem + "_metadata.txt");
            JsonObject sidecar = Files.isRegularFile(sidecarIn) ? readJson(sidecarIn) : null;
            if (sidecar == null) {
                notes.add(label + ": no sidecar " + sidecarIn.getFileName() + "; stage position from other sidecars");
            }

            Planes planes = averagePosition(files, sidecar, null);
            frames.put(label, planes.frames);
            if (planes.missing > 0) {
                notes.add(label + ": " + planes.missing + " of " + planes.frames * planes.planeCount()
                        + " planes absent; those planes averaged over the frames present");
            }

            String outName = stem + ".ome.tif";
            writeTiff(
                    out.resolve(outName),
                    planes,
                    java.util.Arrays.asList(planes.mean),
                    planes.channels,
                    planes.slices,
                    channelNames(sidecar),
                    firstFrameBlock(sidecar),
                    label);
            if (sidecar != null) {
                Files.writeString(
                        out.resolve(stem + "_metadata.txt"),
                        GSON.toJson(averagedSidecar(sidecar, planes, outName, src)),
                        StandardCharsets.UTF_8);
            }
            logger.info(
                    "Averaged {} frame(s) of {} ({} channel(s), {} slice(s)) -> {}",
                    planes.frames,
                    label,
                    planes.channels,
                    planes.slices,
                    outName);
        }

        int min = frames.values().stream().mapToInt(i -> i).min().orElse(0);
        int max = frames.values().stream().mapToInt(i -> i).max().orElse(0);
        if (min != max) {
            notes.add(
                    0,
                    "positions have different frame counts (" + min + " to " + max
                            + "); each was averaged over its own frames");
        }
        Result result = new Result(out, frames.size(), frames, notes);
        writeRecord(src, result);
        return result;
    }

    /** One position's averaged planes, indexed {@code c + channels * z}. */
    static final class Planes {
        int width;
        int height;
        int channels;
        int slices;
        int frames;
        /** Bio-Formats pixel type of the source. */
        int pixelType;
        /** (channel, slice) pairs some frame did not provide. */
        int missing;

        double[][] mean;

        int planeCount() {
            return channels * slices;
        }
    }

    /** Page coordinates (channel, slice, frame). */
    private record Coord(int c, int z, int t) {}

    /**
     * @param files the position's files, in the order MicroManager wrote them
     * @param sidecar the position's sidecar, for the frame-key fallback; may be null
     * @param fileCoords single-plane files' coordinates, or null to read them from each page
     */
    private static Planes averagePosition(List<Path> files, JsonObject sidecar, Map<Path, Coord> fileCoords)
            throws IOException {
        List<Coord> sidecarOrder = sidecar == null ? List.of() : frameKeyOrder(sidecar);

        Map<Long, double[]> sums = new TreeMap<>();
        Map<Long, Integer> counts = new TreeMap<>();
        int maxC = 0, maxZ = 0, maxT = 0;
        Planes p = new Planes();
        p.pixelType = -1;

        int pageIndex = 0;
        for (Path file : files) {
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                ImageReader reader = tiffReader(in, file);
                try {
                    reader.setInput(in, false, false);
                    int pages = reader.getNumImages(true);
                    for (int i = 0; i < pages; i++, pageIndex++) {
                        if (fileCoords != null && pages > 1) {
                            throw new IOException(file.getFileName() + " has " + pages
                                    + " pages; a single-plane TIFF series file holds one");
                        }
                        Coord coord = fileCoords != null ? fileCoords.get(file) : tagCoord(reader, i);
                        if (coord == null) {
                            // No tag: the sidecar's frame keys are in the order the pages were written.
                            if (pageIndex >= sidecarOrder.size()) {
                                throw new IOException("Cannot tell which channel, slice and frame page " + i
                                        + " of " + file.getFileName() + " holds: it carries no MicroManager tag"
                                        + " and the sidecar lists only " + sidecarOrder.size() + " frame key(s)");
                            }
                            coord = sidecarOrder.get(pageIndex);
                        }
                        Raster r = reader.read(i).getRaster();
                        if (r.getNumBands() != 1) {
                            throw new IOException(file.getFileName() + " page " + i + " has " + r.getNumBands()
                                    + " samples per pixel; only single-sample (grayscale) frames can be averaged");
                        }
                        if (p.width == 0) {
                            p.width = r.getWidth();
                            p.height = r.getHeight();
                            p.pixelType = pixelType(r.getDataBuffer().getDataType(), file);
                        } else if (r.getWidth() != p.width || r.getHeight() != p.height) {
                            throw new IOException(file.getFileName() + " page " + i + " is " + r.getWidth() + " x "
                                    + r.getHeight() + ", not " + p.width + " x " + p.height);
                        }
                        long key = ((long) coord.z << 32) | coord.c;
                        double[] sum = sums.computeIfAbsent(key, k -> new double[p.width * p.height]);
                        accumulate(r, sum);
                        counts.merge(key, 1, Integer::sum);
                        maxC = Math.max(maxC, coord.c);
                        maxZ = Math.max(maxZ, coord.z);
                        maxT = Math.max(maxT, coord.t);
                    }
                } finally {
                    reader.dispose();
                }
            }
        }

        p.channels = maxC + 1;
        p.slices = maxZ + 1;
        p.frames = maxT + 1;
        p.mean = new double[p.planeCount()][];
        for (int z = 0; z < p.slices; z++) {
            for (int c = 0; c < p.channels; c++) {
                long key = ((long) z << 32) | c;
                double[] sum = sums.get(key);
                int n = counts.getOrDefault(key, 0);
                if (sum == null || n == 0) {
                    throw new IOException("Channel " + c + ", slice " + z + " has no frames in " + files.get(0));
                }
                p.missing += p.frames - n;
                for (int k = 0; k < sum.length; k++) sum[k] /= n;
                p.mean[c + p.channels * z] = sum;
            }
        }
        return p;
    }

    /**
     * Add a page to the running sum. Sums are doubles, exact for integers up to 2^53, so 16-bit
     * frames cannot wrap or lose precision below about 10^11 frames; the type's range is applied
     * only to the final mean, in {@link #pack}.
     */
    private static void accumulate(Raster r, double[] sum) {
        int w = r.getWidth();
        int h = r.getHeight();
        double[] row = new double[w];
        for (int y = 0; y < h; y++) {
            r.getSamples(r.getMinX(), r.getMinY() + y, w, 1, 0, row);
            int off = y * w;
            for (int x = 0; x < w; x++) sum[off + x] += row[x];
        }
    }

    private static ImageReader tiffReader(ImageInputStream in, Path file) throws IOException {
        if (in == null) throw new IOException("Cannot open " + file);
        var readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("No TIFF reader for " + file);
        return readers.next();
    }

    /** Channel, slice and frame from the page's MicroManager tag, or null when it has none. */
    private static Coord tagCoord(ImageReader reader, int page) {
        try {
            TIFFDirectory dir = TIFFDirectory.createFromMetadata(reader.getImageMetadata(page));
            TIFFField field = dir.getTIFFField(MM_METADATA_TAG);
            if (field == null) return null;
            JsonObject json = JsonParser.parseString(field.getAsString(0)).getAsJsonObject();
            if (!json.has("ChannelIndex") || !json.has("FrameIndex")) return null;
            return new Coord(
                    intOr(json, "ChannelIndex", 0), intOr(json, "SliceIndex", 0), intOr(json, "FrameIndex", 0));
        } catch (Exception e) {
            logger.debug("No MicroManager tag on page {}: {}", page, e.toString());
            return null;
        }
    }

    /** The sidecar's {@code FrameKey-t-c-z} blocks in file order, which is the order pages were written. */
    private static List<Coord> frameKeyOrder(JsonObject sidecar) {
        List<Coord> out = new ArrayList<>();
        for (String key : sidecar.keySet()) {
            Matcher m = FRAME_KEY.matcher(key);
            if (m.matches()) {
                out.add(new Coord(
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1))));
            }
        }
        return out;
    }

    private static int pixelType(int dataType, Path file) throws IOException {
        return switch (dataType) {
            case DataBuffer.TYPE_BYTE -> FormatTools.UINT8;
            case DataBuffer.TYPE_USHORT -> FormatTools.UINT16;
            case DataBuffer.TYPE_SHORT -> FormatTools.INT16;
            case DataBuffer.TYPE_INT -> FormatTools.INT32;
            case DataBuffer.TYPE_FLOAT -> FormatTools.FLOAT;
            default -> throw new IOException(file.getFileName() + ": unsupported pixel data type " + dataType);
        };
    }

    /** Pack one averaged plane little-endian in the source type, rounding integer types. */
    static byte[] pack(double[] plane, int pixelType) {
        int bpp = FormatTools.getBytesPerPixel(pixelType);
        ByteBuffer buf = ByteBuffer.allocate(plane.length * bpp).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : plane) {
            switch (pixelType) {
                case FormatTools.UINT8 -> buf.put((byte) clampRound(v, 0, 255));
                case FormatTools.UINT16 -> buf.putShort((short) clampRound(v, 0, 65535));
                case FormatTools.INT16 -> buf.putShort((short) clampRound(v, Short.MIN_VALUE, Short.MAX_VALUE));
                case FormatTools.INT32 -> buf.putInt((int) clampRound(v, Integer.MIN_VALUE, Integer.MAX_VALUE));
                default -> buf.putFloat((float) v);
            }
        }
        return buf.array();
    }

    private static long clampRound(double v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, Math.round(v)));
    }

    /**
     * Write planes (channel fastest, then z) as one TIFF; {@code .ome.tif} gets OME-XML, a plain
     * {@code .tif} is a plain TIFF.
     */
    private static void writeTiff(
            Path path,
            Planes p,
            List<double[]> planes,
            int sizeC,
            int sizeZ,
            List<String> names,
            JsonObject block,
            String label)
            throws IOException {
        Files.deleteIfExists(path);
        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        MetadataTools.populateMetadata(
                meta,
                0,
                stemOf(path.getFileName().toString()),
                true,
                DimensionOrder.XYCZT.getValue(),
                FormatTools.getPixelTypeString(p.pixelType),
                p.width,
                p.height,
                sizeZ,
                sizeC,
                1,
                1);
        for (int c = 0; c < sizeC; c++) {
            if (c < names.size() && names.get(c) != null) meta.setChannelName(names.get(c), 0, c);
        }
        Double px = block == null ? null : doubleOr(block, "PixelSizeUm");
        if (px != null && px > 0) {
            meta.setPixelsPhysicalSizeX(new Length(px, UNITS.MICROMETER), 0);
            meta.setPixelsPhysicalSizeY(new Length(px, UNITS.MICROMETER), 0);
        }
        Double x = block == null ? null : doubleOr(block, "XPositionUm");
        Double y = block == null ? null : doubleOr(block, "YPositionUm");
        meta.setStageLabelName(label, 0);
        if (x != null && y != null) {
            meta.setStageLabelX(new Length(x, UNITS.MICROMETER), 0);
            meta.setStageLabelY(new Length(y, UNITS.MICROMETER), 0);
        }

        try (ImageWriter writer = new ImageWriter()) {
            writer.setWriteSequentially(true);
            writer.setMetadataRetrieve(meta);
            writer.setId(path.toString());
            for (int i = 0; i < planes.size(); i++) {
                writer.saveBytes(i, pack(planes.get(i), p.pixelType));
            }
        } catch (FormatException e) {
            throw new IOException("Could not write " + path, e);
        }
    }

    /** The source sidecar reshaped for one frame: Summary says 1 frame, one FrameKey per (c, z). */
    private static JsonObject averagedSidecar(JsonObject sidecar, Planes p, String outName, Path source) {
        JsonObject out = new JsonObject();
        JsonObject summary = summaryOf(sidecar);
        if (summary != null) {
            out.add("Summary", oneFrameSummary(summary, p.frames, source));
        }
        Map<String, JsonObject> earliest = new LinkedHashMap<>();
        for (String key : sidecar.keySet()) {
            Matcher m = FRAME_KEY.matcher(key);
            if (!m.matches() || !sidecar.get(key).isJsonObject()) continue;
            String cz = m.group(2) + "-" + m.group(3);
            int t = Integer.parseInt(m.group(1));
            JsonObject block = sidecar.getAsJsonObject(key);
            JsonObject have = earliest.get(cz);
            if (have == null || t < have.get("__t").getAsInt()) {
                JsonObject copy = block.deepCopy();
                copy.addProperty("__t", t);
                earliest.put(cz, copy);
            }
        }
        for (int z = 0; z < p.slices; z++) {
            for (int c = 0; c < p.channels; c++) {
                JsonObject block = earliest.get(c + "-" + z);
                if (block == null) continue;
                block.remove("__t");
                block.addProperty("FileName", outName);
                block.addProperty("FramesAveraged", p.frames);
                out.add("FrameKey-0-" + c + "-" + z, block);
            }
        }
        return out;
    }

    /** Summary.ChNames, or an empty list. */
    private static List<String> channelNames(JsonObject sidecar) {
        List<String> names = new ArrayList<>();
        JsonObject summary = summaryOf(sidecar);
        if (summary != null && summary.has("ChNames") && summary.get("ChNames").isJsonArray()) {
            summary.getAsJsonArray("ChNames").forEach(e -> names.add(e.isJsonPrimitive() ? e.getAsString() : null));
        }
        return names;
    }

    /** Position folders of a single-plane TIFF series: sub-folders holding a metadata.txt. */
    private static List<Path> singlePlaneDirs(Path src) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(src)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (!p.getFileName().toString().contains(":")
                        && Files.isDirectory(p)
                        && Files.isRegularFile(p.resolve("metadata.txt"))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * Average one position folder of a single-plane TIFF series into {@code outDir}: one file per
     * channel and slice, named as that plane's earliest frame, and a metadata.txt for one frame.
     */
    private static void averageSinglePlanePosition(
            Path dir, Path outDir, Path src, Map<String, Integer> frames, List<String> notes) throws IOException {
        String label = dir.getFileName().toString();
        JsonObject sidecar = readJson(dir.resolve("metadata.txt"));
        if (sidecar == null) {
            throw new IOException("Empty metadata.txt in " + dir);
        }
        Map<Path, Coord> coords = new LinkedHashMap<>();
        Map<Long, Path> earliest = new TreeMap<>();
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.sorted()::iterator) {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.contains(":") || !(lower.endsWith(".tif") || lower.endsWith(".tiff"))) continue;
                Coord c = singlePlaneCoord(name, sidecar.get("Metadata-" + label + "/" + name));
                if (c == null) {
                    throw new IOException("Cannot tell which channel, slice and frame " + label + "/" + name
                            + " holds: its name is not img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif"
                            + " and metadata.txt has no Metadata- block for it");
                }
                coords.put(f, c);
                long key = ((long) c.z << 32) | c.c;
                Path have = earliest.get(key);
                if (have == null || c.t < coords.get(have).t) earliest.put(key, f);
            }
        }
        if (coords.isEmpty()) {
            notes.add(label + ": no TIFF files; skipped");
            return;
        }
        Planes planes = averagePosition(new ArrayList<>(coords.keySet()), null, coords);
        frames.put(label, planes.frames);
        if (planes.missing > 0) {
            notes.add(label + ": " + planes.missing + " of " + planes.frames * planes.planeCount()
                    + " planes absent; those planes averaged over the frames present");
        }

        Files.createDirectories(outDir);
        List<String> names = channelNames(sidecar);
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (int z = 0; z < planes.slices; z++) {
            for (int c = 0; c < planes.channels; c++) {
                Path first = earliest.get(((long) z << 32) | c);
                if (first == null) continue;
                String name = first.getFileName().toString();
                kept.add(name);
                JsonElement block = sidecar.get("Metadata-" + label + "/" + name);
                writeTiff(
                        outDir.resolve(name),
                        planes,
                        List.of(planes.mean[c + planes.channels * z]),
                        1,
                        1,
                        c < names.size() ? java.util.Collections.singletonList(names.get(c)) : List.of(),
                        block != null && block.isJsonObject() ? block.getAsJsonObject() : null,
                        label);
            }
        }
        Files.writeString(
                outDir.resolve("metadata.txt"),
                GSON.toJson(averagedSinglePlaneSidecar(sidecar, label, kept, planes.frames, src)),
                StandardCharsets.UTF_8);
        logger.info(
                "Averaged {} frame(s) of {} ({} channel(s), {} slice(s))",
                planes.frames,
                label,
                planes.channels,
                planes.slices);
    }

    /** Channel, slice and frame from a single-plane file's name, else from its Metadata- block. */
    private static Coord singlePlaneCoord(String name, JsonElement block) {
        Matcher m = SINGLE_PLANE.matcher(name);
        if (m.matches()) {
            return new Coord(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)));
        }
        if (block != null && block.isJsonObject()) {
            JsonObject b = block.getAsJsonObject();
            if (b.has("ChannelIndex") && b.has("FrameIndex")) {
                return new Coord(intOr(b, "ChannelIndex", 0), intOr(b, "SliceIndex", 0), intOr(b, "FrameIndex", 0));
            }
        }
        return null;
    }

    /**
     * The position's metadata.txt for one frame: Summary says 1 frame, and only the per-file
     * blocks of the files written are kept.
     */
    private static JsonObject averagedSinglePlaneSidecar(
            JsonObject sidecar, String label, java.util.Set<String> kept, int framesAveraged, Path source) {
        JsonObject out = new JsonObject();
        for (String key : sidecar.keySet()) {
            JsonElement value = sidecar.get(key);
            if (key.equals("Summary") && value.isJsonObject()) {
                out.add(key, oneFrameSummary(value.getAsJsonObject(), framesAveraged, source));
                continue;
            }
            String rel = key.startsWith("Metadata-")
                    ? key.substring("Metadata-".length())
                    : key.startsWith("Coords-") ? key.substring("Coords-".length()) : null;
            if (rel == null) {
                out.add(key, value.deepCopy());
                continue;
            }
            String base = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            if (!kept.contains(base)) continue;
            JsonElement copy = value.deepCopy();
            if (key.startsWith("Metadata-") && copy.isJsonObject()) {
                copy.getAsJsonObject().addProperty("FramesAveraged", framesAveraged);
            }
            out.add(key, copy);
        }
        return out;
    }

    /** A copy of Summary describing one frame, with the averaging recorded in it. */
    private static JsonObject oneFrameSummary(JsonObject summary, int framesAveraged, Path source) {
        JsonObject s = summary.deepCopy();
        s.addProperty("Frames", 1);
        if (s.has("IntendedDimensions") && s.get("IntendedDimensions").isJsonObject()) {
            s.getAsJsonObject("IntendedDimensions").addProperty("time", 1);
        }
        JsonObject avg = new JsonObject();
        avg.addProperty("Source", source.toString());
        avg.addProperty("FramesAveraged", framesAveraged);
        avg.addProperty("Method", "mean over frames, rounded to the source bit depth");
        s.add("FrameAveraging", avg);
        return s;
    }

    private static void writeRecord(Path source, Result result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Frame averaging (tiles-to-pyramid)\n");
        sb.append("source: ").append(source).append('\n');
        sb.append("written: ")
                .append(java.time.OffsetDateTime.now().withNano(0))
                .append('\n');
        sb.append("method: mean over frames, rounded to the source bit depth\n");
        sb.append("positions: ").append(result.positions()).append('\n');
        sb.append("frames per position: ").append(result.frameSummary()).append('\n');
        for (String note : result.notes()) sb.append("note: ").append(note).append('\n');
        result.framesPerPosition()
                .forEach((k, v) ->
                        sb.append("  ").append(k).append(": ").append(v).append('\n'));
        Files.writeString(result.output().resolve(RECORD_FILENAME), sb.toString(), StandardCharsets.US_ASCII);
    }

    private static void prepareOutput(Path out) throws IOException {
        if (Files.isDirectory(out)) {
            boolean ours = Files.isRegularFile(out.resolve(RECORD_FILENAME));
            boolean empty;
            try (var s = Files.list(out)) {
                empty = s.findAny().isEmpty();
            }
            if (!ours && !empty) {
                throw new IOException(out + " already exists and was not written by frame averaging;"
                        + " move it or choose another folder");
            }
        } else {
            Files.createDirectories(out);
        }
    }

    /** {@code _MMStack_} files grouped by position, continuation files after the first. */
    private static Map<String, List<Path>> positionFiles(Path src) throws IOException {
        Map<String, List<Path>> out = new TreeMap<>();
        try (var s = Files.list(src)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.contains(":") || !(lower.endsWith(".tif") || lower.endsWith(".tiff"))) continue;
                String label = MicroManagerMetadataStrategy.extractMMStackLabel(name);
                if (label == null) continue;
                Matcher m = CONTINUATION.matcher(label);
                if (m.matches() && Files.isRegularFile(p.resolveSibling(name.replace("_" + m.group(2) + ".", ".")))) {
                    label = m.group(1);
                }
                out.computeIfAbsent(label, k -> new ArrayList<>()).add(p);
            }
        }
        return out;
    }

    /** File name without {@code .ome.tif} / {@code .tif}. */
    private static String stemOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".ome.tiff", ".ome.tif", ".tiff", ".tif")) {
            if (lower.endsWith(ext)) return name.substring(0, name.length() - ext.length());
        }
        return name;
    }

    private static JsonObject firstFrameBlock(JsonObject sidecar) {
        if (sidecar == null) return null;
        for (String key : sidecar.keySet()) {
            if (FRAME_KEY.matcher(key).matches() && sidecar.get(key).isJsonObject()) {
                return sidecar.getAsJsonObject(key);
            }
        }
        return null;
    }

    private static JsonObject readJson(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new IOException("Cannot parse " + p.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject summaryOf(JsonObject root) {
        if (root == null) return null;
        JsonElement s = root.get("Summary");
        return s != null && s.isJsonObject() ? s.getAsJsonObject() : null;
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static Double doubleOr(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? null : e.getAsDouble();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
