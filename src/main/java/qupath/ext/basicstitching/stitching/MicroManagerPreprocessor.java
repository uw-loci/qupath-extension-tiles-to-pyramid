package qupath.ext.basicstitching.stitching;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
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
 * Reduces each position of a MicroManager multi-position acquisition to one plane per channel
 * before stitching: the time points are averaged, then the z-slices are max-intensity projected,
 * in that order. The result is a copy in the same MicroManager layout, with one frame and (when
 * projected) one slice per position, so {@link MicroManagerMetadataStrategy} stitches it like any
 * single-plane acquisition. Both MicroManager layouts are handled:
 *
 * <ul>
 *   <li><b>One OME-TIFF per position</b> ({@code <prefix>_MMStack_<label>.ome.tif} plus
 *       {@code _metadata.txt}, and any {@code <label>_1.ome.tif} continuation).</li>
 *   <li><b>Single-plane TIFF series</b> (a folder per position holding
 *       {@code img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif} files and a
 *       {@code metadata.txt}). The copy keeps each plane's first-frame, first-slice file name.</li>
 * </ul>
 *
 * <p>Which channel, slice and frame each page holds is read by {@link MicroManagerPlanes}, the
 * same reading the stitch uses; a page whose plane cannot be established is refused, not guessed.
 *
 * <p>Averaging is the mean over frames; projection is the per-pixel maximum over slices of those
 * means. Values are rounded to the source bit depth only when written (float data stays float).
 */
public final class MicroManagerPreprocessor {
    private static final Logger logger = LoggerFactory.getLogger(MicroManagerPreprocessor.class);

    /** Written into the output folder; its presence is what allows a later run to overwrite it. */
    public static final String RECORD_FILENAME = "preprocessing.txt";

    /** The record 0.7.9 wrote; a folder holding it was written by this class too. */
    private static final String RECORD_FILENAME_0_7_9 = "frame_averaging.txt";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern FRAME_KEY = MicroManagerPlanes.FRAME_KEY;
    /** Root files MicroManager writes beside the position folders, copied so the copy matches. */
    private static final List<String> ROOT_EXTRAS = List.of("comments.txt", "DisplaySettings.json");

    private MicroManagerPreprocessor() {}

    /**
     * What to do to each position, applied in this order.
     *
     * @param averageFrames average the time points; when false and there are several, the first
     *     frame is used
     * @param flatfield corrects each plane before any projection; null for none
     * @param maxProjectZ take the per-pixel maximum over the z-slices
     */
    public record Options(boolean averageFrames, FlatfieldProfile flatfield, boolean maxProjectZ) {
        /** @return the default output folder's suffix: any of {@code _averaged}, {@code _ff}, {@code _mip} */
        public String folderSuffix() {
            return (averageFrames ? "_averaged" : "") + (flatfield != null ? "_ff" : "") + (maxProjectZ ? "_mip" : "");
        }

        /** @return the method in words, for records */
        public String describe() {
            String frames = averageFrames ? "mean over time points" : "first time point only";
            String shading = flatfield != null ? ", then " + flatfield.describe() : "";
            String z = maxProjectZ ? ", then maximum intensity over z-slices" : "";
            return frames + shading + z + "; rounded to the source bit depth";
        }
    }

    /**
     * Frames and z-slices per position, from the acquisition's Summary.
     *
     * @param frames time points per position; 0 when the folder holds no MicroManager sidecar
     * @param slices z-slices per position; 0 likewise
     */
    public record Shape(int frames, int slices) {}

    /**
     * What one run did.
     *
     * @param output the folder written
     * @param options what was asked
     * @param positions positions written
     * @param framesPerPosition time points in the source, per position label
     * @param slicesPerPosition z-slices in the source, per position label
     * @param notes anything a reader of the stitch record should know (uneven counts, ...)
     */
    public record Result(
            Path output,
            Options options,
            int positions,
            Map<String, Integer> framesPerPosition,
            Map<String, Integer> slicesPerPosition,
            List<String> notes) {
        /** @return "12" or "10 to 12" */
        public String frameSummary() {
            return range(framesPerPosition);
        }

        /** @return "5" or "4 to 5" */
        public String sliceSummary() {
            return range(slicesPerPosition);
        }

        private static String range(Map<String, Integer> counts) {
            int min = counts.values().stream().mapToInt(i -> i).min().orElse(0);
            int max = counts.values().stream().mapToInt(i -> i).max().orElse(0);
            return min == max ? String.valueOf(max) : min + " to " + max;
        }
    }

    /**
     * Frames and slices per position of the acquisition in {@code folder}, from the first sidecar's
     * Summary.
     *
     * @return the shape; (0, 0) when the folder holds no MicroManager sidecar
     */
    public static Shape shape(File folder) {
        if (folder == null || !folder.isDirectory()) return new Shape(0, 0);
        Path root = folder.toPath().toAbsolutePath().normalize();
        if (root.getParent() == null) return new Shape(0, 0);
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
                JsonObject summary = summaryOf(MicroManagerPlanes.readJson(sidecar));
                if (summary != null) {
                    return new Shape(
                            MicroManagerPlanes.intOr(summary, "Frames", 1),
                            MicroManagerPlanes.intOr(summary, "Slices", 1));
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the acquisition shape under {}: {}", folder, e.toString());
        }
        return new Shape(0, 0);
    }

    /** @return the default output folder, beside the source: {@code <source><suffix>} */
    public static Path defaultOutput(Path source, Options options) {
        Path abs = source.toAbsolutePath().normalize();
        return abs.resolveSibling(abs.getFileName() + options.folderSuffix());
    }

    /**
     * Reduce every position in {@code source} into {@code output}.
     *
     * @param source the acquisition folder: {@code _MMStack_} files and their sidecars, or one
     *     sub-folder per position (single-plane TIFF series)
     * @param output folder to write; created if absent. An existing folder is written into only
     *     when it is empty or was written by this class (it holds {@link #RECORD_FILENAME}).
     * @param options what to do, in order
     * @return what was written
     * @throws IOException when nothing could be read, a file's page order cannot be established,
     *     or the output folder belongs to something else
     */
    public static Result run(Path source, Path output, Options options) throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path out = output.toAbsolutePath().normalize();
        if (src.equals(out)) {
            throw new IOException("The reduced copy cannot be written over its source folder " + src);
        }
        prepareOutput(out);

        Map<String, Integer> frames = new LinkedHashMap<>();
        Map<String, Integer> slices = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Map<String, List<Path>> filesByPosition = positionFiles(src);
        List<Path> positionDirs = filesByPosition.isEmpty() ? singlePlaneDirs(src) : List.of();
        if (filesByPosition.isEmpty() && positionDirs.isEmpty()) {
            throw new IOException("No MicroManager acquisition in " + src + ": neither *_MMStack_*.ome.tif files"
                    + " nor position folders holding a metadata.txt");
        }
        for (Path dir : positionDirs) {
            Planes planes =
                    singlePlanePosition(dir, out.resolve(dir.getFileName().toString()), src, options, notes);
            if (planes != null) {
                frames.put(dir.getFileName().toString(), planes.framesInSource);
                slices.put(dir.getFileName().toString(), planes.slicesInSource);
            }
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
            String stem = MicroManagerPlanes.stemOf(first.getFileName().toString());
            Path sidecarIn = first.resolveSibling(stem + "_metadata.txt");
            JsonObject sidecar = Files.isRegularFile(sidecarIn) ? MicroManagerPlanes.readJson(sidecarIn) : null;
            if (sidecar == null) {
                notes.add(label + ": no sidecar " + sidecarIn.getFileName() + "; stage position from other sidecars");
            }

            List<MicroManagerPlanes.Plane> pagePlanes = MicroManagerPlanes.stackPlanes(
                    files, () -> sidecar, MicroManagerPlanes.Dims.of(summaryOf(sidecar)));
            Planes planes = reduce(pagePlanes, false, options, label, notes);
            frames.put(label, planes.framesInSource);
            slices.put(label, planes.slicesInSource);

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
                        GSON.toJson(reducedSidecar(sidecar, planes, outName, src, options)),
                        StandardCharsets.UTF_8);
            }
            logReduced(label, planes, options);
        }

        noteUneven(notes, frames, "time point");
        noteUneven(notes, slices, "z-slice");
        Result result = new Result(out, options, frames.size(), frames, slices, notes);
        writeRecord(src, result);
        return result;
    }

    private static void noteUneven(List<String> notes, Map<String, Integer> counts, String what) {
        int min = counts.values().stream().mapToInt(i -> i).min().orElse(0);
        int max = counts.values().stream().mapToInt(i -> i).max().orElse(0);
        if (min != max) {
            notes.add(
                    0,
                    "positions have different " + what + " counts (" + min + " to " + max
                            + "); each was reduced over its own");
        }
    }

    private static void logReduced(String label, Planes p, Options options) {
        logger.info(
                "{}: {} time point(s) {}, {} z-slice(s) {}, {} channel(s)",
                label,
                p.framesInSource,
                options.averageFrames() ? "averaged" : "(first used)",
                p.slicesInSource,
                p.slicesInSource > 1 && options.maxProjectZ() ? "max-projected" : "kept",
                p.channels);
    }

    /** One position's planes, indexed {@code c + channels * z}. */
    static final class Planes {
        int width;
        int height;
        int channels;
        /** Slices written: 1 after projection. */
        int slices;
        /** Frames combined into each plane: the frame count when averaged, else 1. */
        int frames;

        int framesInSource;
        int slicesInSource;
        /** Bio-Formats pixel type of the source. */
        int pixelType;
        /** (channel, slice) pairs some frame did not provide. */
        int missing;

        double[][] mean;

        int planeCount() {
            return channels * slices;
        }

        boolean projected() {
            return slices < slicesInSource;
        }
    }

    /**
     * Average (or take the first frame), correct the shading, then project if asked, noting missing
     * planes. The correction must come before the projection: a sum would otherwise take the offset
     * off once for many planes, and averaging is unaffected either way.
     */
    private static Planes reduce(
            List<MicroManagerPlanes.Plane> planes,
            boolean singlePlane,
            Options options,
            String label,
            List<String> notes)
            throws IOException {
        Planes p = combineFrames(planes, singlePlane, options.averageFrames());
        if (p.missing > 0) {
            notes.add(label + ": " + p.missing + " of " + p.frames * p.planeCount()
                    + " planes absent; those planes averaged over the frames present");
        }
        if (options.flatfield() != null) {
            for (int z = 0; z < p.slices; z++) {
                for (int c = 0; c < p.channels; c++) {
                    if (options.flatfield().appliesTo(c)) {
                        options.flatfield().correct(p.mean[c + p.channels * z], p.width, p.height);
                    }
                }
            }
        }
        if (options.maxProjectZ()) {
            maxProject(p);
        }
        return p;
    }

    /**
     * Mean over frames for every (channel, slice); with {@code averageFrames} false, frame 0 only.
     *
     * @param planes the position's planes, in the order MicroManager wrote them
     * @param singlePlane each file is a single-plane TIFF series file and must hold one page
     */
    private static Planes combineFrames(
            List<MicroManagerPlanes.Plane> planes, boolean singlePlane, boolean averageFrames) throws IOException {
        Map<Path, List<MicroManagerPlanes.Plane>> byFile = new LinkedHashMap<>();
        for (MicroManagerPlanes.Plane plane : planes) {
            byFile.computeIfAbsent(plane.file(), k -> new ArrayList<>()).add(plane);
        }

        Map<Long, double[]> sums = new TreeMap<>();
        Map<Long, Integer> counts = new TreeMap<>();
        int maxC = 0, maxZ = 0, maxT = 0;
        Planes p = new Planes();
        p.pixelType = -1;

        for (var entry : byFile.entrySet()) {
            Path file = entry.getKey();
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                ImageReader reader = tiffReader(in, file);
                try {
                    reader.setInput(in, false, false);
                    int pages = reader.getNumImages(true);
                    if (singlePlane && pages > 1) {
                        throw new IOException(file.getFileName() + " has " + pages
                                + " pages; a single-plane TIFF series file holds one");
                    }
                    for (MicroManagerPlanes.Plane plane : entry.getValue()) {
                        MicroManagerPlanes.Coord coord = plane.coord();
                        int i = plane.page();
                        maxC = Math.max(maxC, coord.c());
                        maxZ = Math.max(maxZ, coord.z());
                        maxT = Math.max(maxT, coord.t());
                        if (!averageFrames && coord.t() > 0) {
                            continue;
                        }
                        Raster r = reader.read(i).getRaster();
                        if (r.getNumBands() != 1) {
                            throw new IOException(file.getFileName() + " page " + i + " has " + r.getNumBands()
                                    + " samples per pixel; only single-sample (grayscale) planes are supported");
                        }
                        if (p.width == 0) {
                            p.width = r.getWidth();
                            p.height = r.getHeight();
                            p.pixelType = pixelType(r.getDataBuffer().getDataType(), file);
                        } else if (r.getWidth() != p.width || r.getHeight() != p.height) {
                            throw new IOException(file.getFileName() + " page " + i + " is " + r.getWidth() + " x "
                                    + r.getHeight() + ", not " + p.width + " x " + p.height);
                        }
                        long key = ((long) coord.z() << 32) | coord.c();
                        double[] sum = sums.computeIfAbsent(key, k -> new double[p.width * p.height]);
                        accumulate(r, sum);
                        counts.merge(key, 1, Integer::sum);
                    }
                } finally {
                    reader.dispose();
                }
            }
        }

        p.channels = maxC + 1;
        p.slices = maxZ + 1;
        p.slicesInSource = p.slices;
        p.framesInSource = maxT + 1;
        p.frames = averageFrames ? p.framesInSource : 1;
        p.mean = new double[p.planeCount()][];
        for (int z = 0; z < p.slices; z++) {
            for (int c = 0; c < p.channels; c++) {
                long key = ((long) z << 32) | c;
                double[] sum = sums.get(key);
                int n = counts.getOrDefault(key, 0);
                if (sum == null || n == 0) {
                    throw new IOException("Channel " + c + ", slice " + z + " has no "
                            + (averageFrames ? "frames" : "first frame") + " in "
                            + planes.get(0).file());
                }
                p.missing += p.frames - n;
                for (int k = 0; k < sum.length; k++) sum[k] /= n;
                p.mean[c + p.channels * z] = sum;
            }
        }
        return p;
    }

    /** Replace each channel's slices by their per-pixel maximum. */
    static void maxProject(Planes p) {
        if (p.slices <= 1) return;
        double[][] out = new double[p.channels][];
        for (int c = 0; c < p.channels; c++) {
            double[] max = p.mean[c].clone();
            for (int z = 1; z < p.slices; z++) {
                double[] plane = p.mean[c + p.channels * z];
                for (int k = 0; k < max.length; k++) {
                    if (plane[k] > max[k]) max[k] = plane[k];
                }
            }
            out[c] = max;
        }
        p.mean = out;
        p.slices = 1;
    }

    /**
     * Add a page to the running sum. Sums are doubles, exact for integers up to 2^53, so 16-bit
     * frames cannot wrap or lose precision below about 10^11 frames; the type's range is applied
     * only to the final value, in {@link #pack}.
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

    static ImageReader tiffReader(ImageInputStream in, Path file) throws IOException {
        if (in == null) throw new IOException("Cannot open " + file);
        var readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("No TIFF reader for " + file);
        return readers.next();
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

    /** Pack one plane little-endian in the source type, rounding integer types. */
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
                MicroManagerPlanes.stemOf(path.getFileName().toString()),
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

    /** The source sidecar reshaped to what was written: one FrameKey per written (c, z). */
    private static JsonObject reducedSidecar(
            JsonObject sidecar, Planes p, String outName, Path source, Options options) {
        JsonObject out = new JsonObject();
        JsonObject summary = summaryOf(sidecar);
        if (summary != null) {
            out.add("Summary", reducedSummary(summary, p, source, options));
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
                annotate(block, p);
                out.add("FrameKey-0-" + c + "-" + z, block);
            }
        }
        return out;
    }

    /** Per-plane note of what went into the plane. */
    private static void annotate(JsonObject block, Planes p) {
        block.addProperty("FramesAveraged", p.frames);
        if (p.projected()) {
            block.addProperty("SlicesMaxProjected", p.slicesInSource);
        }
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
     * Reduce one position folder of a single-plane TIFF series into {@code outDir}: one file per
     * written channel and slice, named as that plane's first frame, and a matching metadata.txt.
     *
     * @return the planes written, or null when the folder held no TIFFs
     */
    private static Planes singlePlanePosition(Path dir, Path outDir, Path src, Options options, List<String> notes)
            throws IOException {
        String label = dir.getFileName().toString();
        JsonObject sidecar = MicroManagerPlanes.readJson(dir.resolve("metadata.txt"));
        if (sidecar == null) {
            throw new IOException("Empty metadata.txt in " + dir);
        }
        Map<Path, MicroManagerPlanes.Coord> coords = new LinkedHashMap<>();
        Map<Long, Path> earliest = new TreeMap<>();
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.sorted()::iterator) {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.contains(":") || !(lower.endsWith(".tif") || lower.endsWith(".tiff"))) continue;
                MicroManagerPlanes.Coord c = MicroManagerPlanes.singlePlaneCoord(
                        name,
                        sidecar.get("Metadata-" + label + "/" + name),
                        sidecar.get("Coords-" + label + "/" + name));
                if (c == null) {
                    throw new IOException("Cannot tell which channel, slice and frame " + label + "/" + name
                            + " holds: its name is not img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif"
                            + " and metadata.txt has no Metadata- or Coords- block for it");
                }
                coords.put(f, c);
                long key = ((long) c.z() << 32) | c.c();
                Path have = earliest.get(key);
                if (have == null || c.t() < coords.get(have).t()) earliest.put(key, f);
            }
        }
        if (coords.isEmpty()) {
            notes.add(label + ": no TIFF files; skipped");
            return null;
        }
        List<MicroManagerPlanes.Plane> filePlanes = new ArrayList<>();
        coords.forEach((f, c) -> filePlanes.add(new MicroManagerPlanes.Plane(f, 0, c)));
        Planes planes = reduce(filePlanes, true, options, label, notes);

        Files.createDirectories(outDir);
        List<String> names = channelNames(sidecar);
        Set<String> kept = new HashSet<>();
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
                GSON.toJson(reducedSinglePlaneSidecar(sidecar, kept, planes, src, options)),
                StandardCharsets.UTF_8);
        logReduced(label, planes, options);
        return planes;
    }

    /**
     * The position's metadata.txt for what was written: Summary describes the reduced shape, and
     * only the per-file blocks of the files written are kept.
     */
    private static JsonObject reducedSinglePlaneSidecar(
            JsonObject sidecar, Set<String> kept, Planes p, Path source, Options options) {
        JsonObject out = new JsonObject();
        for (String key : sidecar.keySet()) {
            JsonElement value = sidecar.get(key);
            if (key.equals("Summary") && value.isJsonObject()) {
                out.add(key, reducedSummary(value.getAsJsonObject(), p, source, options));
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
                annotate(copy.getAsJsonObject(), p);
            }
            out.add(key, copy);
        }
        return out;
    }

    /** A copy of Summary describing one frame (and one slice when projected), recording the method. */
    private static JsonObject reducedSummary(JsonObject summary, Planes p, Path source, Options options) {
        JsonObject s = summary.deepCopy();
        s.addProperty("Frames", 1);
        JsonObject intended =
                s.has("IntendedDimensions") && s.get("IntendedDimensions").isJsonObject()
                        ? s.getAsJsonObject("IntendedDimensions")
                        : null;
        if (intended != null) intended.addProperty("time", 1);
        if (p.projected()) {
            s.addProperty("Slices", 1);
            if (intended != null) intended.addProperty("z", 1);
        }
        JsonObject rec = new JsonObject();
        rec.addProperty("Source", source.toString());
        rec.addProperty("Method", options.describe());
        rec.addProperty("FramesInSource", p.framesInSource);
        rec.addProperty("FramesAveraged", p.frames);
        rec.addProperty("SlicesInSource", p.slicesInSource);
        rec.addProperty("SlicesMaxProjected", p.projected() ? p.slicesInSource : 0);
        s.add("Preprocessing", rec);
        return s;
    }

    private static void writeRecord(Path source, Result result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("MicroManager preprocessing (tiles-to-pyramid)\n");
        sb.append("source: ").append(source).append('\n');
        sb.append("written: ")
                .append(java.time.OffsetDateTime.now().withNano(0))
                .append('\n');
        sb.append("method: ").append(result.options().describe()).append('\n');
        sb.append("positions: ").append(result.positions()).append('\n');
        sb.append("time points per position: ").append(result.frameSummary()).append('\n');
        sb.append("z-slices per position: ").append(result.sliceSummary()).append('\n');
        for (String note : result.notes()) sb.append("note: ").append(note).append('\n');
        result.framesPerPosition()
                .forEach((k, v) -> sb.append("  ")
                        .append(k)
                        .append(": ")
                        .append(v)
                        .append(" t, ")
                        .append(result.slicesPerPosition().get(k))
                        .append(" z\n"));
        Files.writeString(result.output().resolve(RECORD_FILENAME), sb.toString(), StandardCharsets.US_ASCII);
    }

    private static void prepareOutput(Path out) throws IOException {
        if (Files.isDirectory(out)) {
            boolean ours = Files.isRegularFile(out.resolve(RECORD_FILENAME))
                    || Files.isRegularFile(out.resolve(RECORD_FILENAME_0_7_9));
            boolean empty;
            try (var s = Files.list(out)) {
                empty = s.findAny().isEmpty();
            }
            if (!ours && !empty) {
                throw new IOException(out + " already exists and was not written by tiles-to-pyramid"
                        + " preprocessing; move it or choose another folder");
            }
        } else {
            Files.createDirectories(out);
        }
    }

    /** {@code _MMStack_} files grouped by position label, continuation files after the first. */
    private static Map<String, List<Path>> positionFiles(Path src) throws IOException {
        List<Path> tiffs = new ArrayList<>();
        try (var s = Files.list(src)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!name.contains(":") && (lower.endsWith(".tif") || lower.endsWith(".tiff"))) tiffs.add(p);
            }
        }
        Set<String> labels = Set.of();
        for (Path p : tiffs) {
            String stem = MicroManagerPlanes.stemOf(p.getFileName().toString());
            Path sidecar = p.resolveSibling(stem + "_metadata.txt");
            if (stem.contains("_MMStack_") && Files.isRegularFile(sidecar)) {
                labels = MicroManagerPlanes.positionLabels(summaryOf(MicroManagerPlanes.readJson(sidecar)));
                break;
            }
        }
        Map<String, List<Path>> out = new TreeMap<>();
        MicroManagerPlanes.stackFilesByPosition(tiffs, labels)
                .forEach((first, files) -> out.put(
                        MicroManagerMetadataStrategy.extractMMStackLabel(
                                first.getFileName().toString()),
                        files));
        return out;
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

    private static JsonObject summaryOf(JsonObject root) {
        if (root == null) return null;
        JsonElement s = root.get("Summary");
        return s != null && s.isJsonObject() ? s.getAsJsonObject() : null;
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
