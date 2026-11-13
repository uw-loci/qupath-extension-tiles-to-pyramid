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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
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
 * Reduces each position of a MicroManager multi-position acquisition before stitching: the time
 * points are averaged (or each kept), each plane is flat-field corrected if asked, then the
 * z-slices are max-intensity projected, in that order. Without averaging every time point is
 * reduced on its own and kept. The result is a copy in the same MicroManager layout, with one
 * frame (when averaged) and one slice (when projected) per position, which
 * {@link MicroManagerMetadataStrategy} stitches. Each MicroManager layout is handled:
 *
 * <ul>
 *   <li><b>One OME-TIFF per position</b> ({@code <prefix>_MMStack_<label>.ome.tif} plus
 *       {@code _metadata.txt}, and any {@code <label>_1.ome.tif} continuation).</li>
 *   <li><b>Single-plane TIFF series</b> (a folder per position holding
 *       {@code img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif} files and a
 *       {@code metadata.txt}). The copy keeps each written plane's source file name (its first
 *       frame's when averaged, its first slice's when projected).</li>
 *   <li><b>NDTiff</b> ({@code NDTiff.index} and {@code <prefix>_NDTiffStack*.tif}, read by
 *       {@link NdTiffDataset}). The copy is an NDTiff dataset ({@link NdTiffWriter}): each written
 *       image keeps the axes of its source image (its first frame's when averaged, its first
 *       slice's when projected) without the {@code time} axis when averaged and without {@code z}
 *       when projected, and that image's metadata, so the stitch places it as it would the source.</li>
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
     * @param time how the time points are combined into one; {@link PlaneProjection#NONE} reduces
     *     each time point on its own and keeps it (null means NONE)
     * @param flatfield corrects each plane before any z projection; null for none
     * @param z how the z-slices are combined into one; {@link PlaneProjection#NONE} keeps them (null
     *     means NONE)
     */
    public record Options(PlaneProjection time, FlatfieldProfile flatfield, PlaneProjection z) {
        public Options {
            time = time == null ? PlaneProjection.NONE : time;
            z = z == null ? PlaneProjection.NONE : z;
        }

        /**
         * @return the default output folder's suffix, from {@code _averaged}, {@code _tmax} or
         *     {@code _tmin}, then {@code _ff}, then {@code _mip}, {@code _zmean} or {@code _zmin}
         */
        public String folderSuffix() {
            String t =
                    switch (time) {
                        case MEAN -> "_averaged";
                        case MAX -> "_tmax";
                        case MIN -> "_tmin";
                        case NONE -> "";
                    };
            String zs =
                    switch (z) {
                        case MAX -> "_mip";
                        case MEAN -> "_zmean";
                        case MIN -> "_zmin";
                        case NONE -> "";
                    };
            return t + (flatfield != null ? "_ff" : "") + zs;
        }

        /** @return the method in words, for records */
        public String describe() {
            String frames = time.reduces() ? time.words() + " over time points" : "each time point kept";
            String shading = flatfield != null ? ", then " + flatfield.describe() : "";
            String zs = z.reduces() ? ", then " + z.words() + " over z-slices" : "";
            return frames + shading + zs + "; rounded to the source bit depth";
        }
    }

    /**
     * Frames and z-slices per position, from the acquisition's Summary (an NDTiff dataset's index).
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
        Path nd = NdTiffDataset.locate(root);
        if (nd != null) {
            return ndTiffShape(nd);
        }
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

    /**
     * Distinct time points and z-slices among an NDTiff dataset's images, from its index alone (the
     * dialog asks on every change of the folder field).
     */
    private static Shape ndTiffShape(Path dir) {
        try {
            Set<String> times = new HashSet<>();
            Set<String> zs = new HashSet<>();
            for (NdTiffDataset.Entry e : NdTiffDataset.readIndex(dir.resolve(NdTiffDataset.INDEX))) {
                JsonElement t = e.axes().get("time");
                JsonElement z = e.axes().get("z");
                times.add(t == null ? "" : t.toString());
                zs.add(z == null ? "" : z.toString());
            }
            if (times.isEmpty()) return new Shape(0, 0);
            return new Shape(times.size(), zs.size());
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the NDTiff index in {}: {}", dir, e.toString());
            return new Shape(0, 0);
        }
    }

    private static void report(BiConsumer<Integer, Integer> progress, int done, int total) {
        if (progress != null) {
            progress.accept(done, total);
        }
    }

    /** @return the default output folder, beside the source: {@code <source><suffix>} */
    public static Path defaultOutput(Path source, Options options) {
        Path abs = source.toAbsolutePath().normalize();
        return abs.resolveSibling(abs.getFileName() + options.folderSuffix());
    }

    /**
     * Reduce every position in {@code source} into {@code output}.
     *
     * @param source the acquisition folder: {@code _MMStack_} files and their sidecars, one
     *     sub-folder per position (single-plane TIFF series), or an NDTiff dataset (the folder
     *     holding {@code NDTiff.index}, or its parent when that is a {@code Full resolution} folder)
     * @param output folder to write; created if absent. An existing folder is written into only
     *     when it is empty or was written by this class (it holds {@link #RECORD_FILENAME}).
     * @param options what to do, in order
     * @return what was written
     * @throws IOException when nothing could be read, a file's page order cannot be established,
     *     or the output folder belongs to something else
     */
    public static Result run(Path source, Path output, Options options) throws IOException {
        return run(source, output, options, null);
    }

    /**
     * As {@link #run(Path, Path, Options)}, reporting each finished position.
     *
     * @param progress receives (positions done, positions in all) after each position; may be null
     */
    public static Result run(Path source, Path output, Options options, BiConsumer<Integer, Integer> progress)
            throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path out = output.toAbsolutePath().normalize();
        if (src.equals(out)) {
            throw new IOException("The reduced copy cannot be written over its source folder " + src);
        }
        prepareOutput(out);

        Map<String, Integer> frames = new LinkedHashMap<>();
        Map<String, Integer> slices = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Path ndTiff = NdTiffDataset.locate(src);
        if (ndTiff != null) {
            ndTiffPositions(src, ndTiff, out, options, frames, slices, notes, progress);
            noteUneven(notes, frames, "time point");
            noteUneven(notes, slices, "z-slice");
            Result result = new Result(out, options, frames.size(), frames, slices, notes);
            writeRecord(src, result);
            return result;
        }
        Map<String, List<Path>> filesByPosition = positionFiles(src);
        List<Path> positionDirs = filesByPosition.isEmpty() ? singlePlaneDirs(src) : List.of();
        if (filesByPosition.isEmpty() && positionDirs.isEmpty()) {
            throw new IOException("No MicroManager acquisition in " + src + ": neither *_MMStack_*.ome.tif files"
                    + " nor position folders holding a metadata.txt");
        }
        int positionCount = positionDirs.size() + filesByPosition.size();
        int positionsDone = 0;
        for (Path dir : positionDirs) {
            Planes planes =
                    singlePlanePosition(dir, out.resolve(dir.getFileName().toString()), src, options, notes);
            if (planes != null) {
                frames.put(dir.getFileName().toString(), planes.framesInSource);
                slices.put(dir.getFileName().toString(), planes.slicesInSource);
            }
            report(progress, ++positionsDone, positionCount);
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
            List<Integer> outFrames = outputFrames(pagePlanes, options, label, notes);
            Planes planes = reduce(pagePlanes, false, false, options, label, notes, outFrames.get(0));
            frames.put(label, planes.framesInSource);
            slices.put(label, planes.slicesInSource);

            String outName = stem + ".ome.tif";
            Planes firstFrame = planes;
            writeTiff(
                    out.resolve(outName),
                    planes,
                    planes.channels,
                    planes.slices,
                    outFrames.size(),
                    t -> {
                        Planes p = t == 0
                                ? firstFrame
                                : reduce(pagePlanes, false, false, options, label, notes, outFrames.get(t));
                        if (p.channels != firstFrame.channels
                                || p.slices != firstFrame.slices
                                || p.width != firstFrame.width
                                || p.height != firstFrame.height) {
                            throw new IOException(label + ": time point " + outFrames.get(t)
                                    + " has a different shape from time point " + outFrames.get(0));
                        }
                        return java.util.Arrays.asList(p.mean);
                    },
                    channelNames(sidecar),
                    firstFrameBlock(sidecar),
                    label);
            if (sidecar != null) {
                Files.writeString(
                        out.resolve(stem + "_metadata.txt"),
                        GSON.toJson(reducedSidecar(sidecar, planes, outFrames, outName, src, options)),
                        StandardCharsets.UTF_8);
            }
            logReduced(label, planes, options);
            report(progress, ++positionsDone, positionCount);
        }

        noteUneven(notes, frames, "time point");
        noteUneven(notes, slices, "z-slice");
        Result result = new Result(out, options, frames.size(), frames, slices, notes);
        writeRecord(src, result);
        return result;
    }

    /**
     * Reduce every position of an NDTiff dataset into an NDTiff dataset in {@code out}: per position
     * and written time point, one image per channel and slice, carrying its source image's axes (no
     * {@code time} when averaged, no {@code z} when projected) and metadata. The images are read
     * through {@link NdTiffDataset}: each is a page of a stack file, located by the index.
     */
    private static void ndTiffPositions(
            Path src,
            Path dir,
            Path out,
            Options options,
            Map<String, Integer> frames,
            Map<String, Integer> slices,
            List<String> notes,
            BiConsumer<Integer, Integer> progress)
            throws IOException {
        NdTiffDataset ds = NdTiffDataset.read(dir);
        Map<String, List<MicroManagerPlanes.Plane>> byPosition = new LinkedHashMap<>();
        Map<String, List<NdTiffDataset.Image>> imagesOf = new HashMap<>();
        for (NdTiffDataset.Image im : ds.images()) {
            byPosition
                    .computeIfAbsent(im.position(), p -> new ArrayList<>())
                    .add(new MicroManagerPlanes.Plane(im.file(), im.page(), im.coord()));
            imagesOf.computeIfAbsent(im.position(), p -> new ArrayList<>()).add(im);
        }
        String prefix =
                ds.images().get(0).file().getFileName().toString().replaceFirst("_NDTiffStack(_\\d+)?\\.tiff?$", "");
        NdTiffWriter writer = null;
        int positionsDone = 0;
        try {
            for (var entry : byPosition.entrySet()) {
                String pos = entry.getKey();
                String label = "{}".equals(pos) ? "the position" : "position " + pos;
                List<MicroManagerPlanes.Plane> planes = entry.getValue();
                List<Integer> outFrames = outputFrames(planes, options, label, notes);
                Planes first = null;
                for (int frame : outFrames) {
                    Planes p = reduce(planes, false, true, options, label, notes, frame);
                    if (first == null) {
                        first = p;
                    } else if (p.channels != first.channels
                            || p.slices != first.slices
                            || p.width != first.width
                            || p.height != first.height) {
                        throw new IOException(label + ": time point " + frame
                                + " has a different shape from time point " + outFrames.get(0));
                    }
                    if (p.pixelType != FormatTools.UINT8 && p.pixelType != FormatTools.UINT16) {
                        throw new IOException(label + ": NDTiff holds 8- or 16-bit images, not "
                                + FormatTools.getPixelTypeString(p.pixelType));
                    }
                    if (writer == null) {
                        writer = new NdTiffWriter(
                                out, prefix, reducedSummary(ds.summary(), p, outFrames.size(), src, options));
                    }
                    for (int z = 0; z < p.slices; z++) {
                        for (int c = 0; c < p.channels; c++) {
                            if (p.mean[c + p.channels * z] == null) continue;
                            // The plane's own source image: its time point's, or its first frame's
                            // when averaged (its first slice's when projected).
                            NdTiffDataset.Image from = sourceImage(
                                    imagesOf.get(pos), c, options.z().reduces() ? -1 : z, frame);
                            if (from == null) continue;
                            JsonObject axes = from.entry().axes().deepCopy();
                            if (frame == ALL_FRAMES) axes.remove("time");
                            if (options.z().reduces()) axes.remove("z");
                            JsonObject md = ds.metadata(from).deepCopy();
                            annotate(md, p);
                            writer.add(
                                    axes,
                                    p.width,
                                    p.height,
                                    p.pixelType == FormatTools.UINT16,
                                    pack(p.mean[c + p.channels * z], p.pixelType),
                                    md);
                        }
                    }
                }
                frames.put(pos, first.framesInSource);
                slices.put(pos, first.slicesInSource);
                logReduced(label, first, options);
                report(progress, ++positionsDone, byPosition.size());
            }
        } finally {
            if (writer != null) writer.close();
        }
        for (Path from : List.of(dir.resolve("display_settings.txt"), src.resolve("display_settings.txt"))) {
            if (Files.isRegularFile(from)) {
                Files.copy(
                        from, out.resolve("display_settings.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                break;
            }
        }
    }

    /**
     * The earliest image (by time point, then slice) of a position with channel {@code c}, slice
     * {@code z} (any when negative) and time point {@code frame} (any when {@link #ALL_FRAMES}).
     */
    private static NdTiffDataset.Image sourceImage(List<NdTiffDataset.Image> images, int c, int z, int frame) {
        NdTiffDataset.Image best = null;
        for (NdTiffDataset.Image im : images) {
            MicroManagerPlanes.Coord k = im.coord();
            if (k.c() != c || (z >= 0 && k.z() != z) || (frame != ALL_FRAMES && k.t() != frame)) continue;
            if (best == null
                    || k.t() < best.coord().t()
                    || (k.t() == best.coord().t() && k.z() < best.coord().z())) {
                best = im;
            }
        }
        return best;
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
                options.time().reduces() ? "combined by " + options.time().words() : "kept",
                p.slicesInSource,
                p.slicesInSource > 1 && options.z().reduces()
                        ? "combined by " + options.z().words()
                        : "kept",
                p.channels);
    }

    /** One position's planes, indexed {@code c + channels * z}. */
    static final class Planes {
        int width;
        int height;
        int channels;
        /** Slices written: 1 after projection. */
        int slices;
        /** Frames combined into each plane: the frame count when combined, else 1. */
        int frames;
        /** How the frames were combined; NONE when one time point was taken. */
        PlaneProjection timeProjection = PlaneProjection.NONE;
        /** How the slices were combined; NONE when kept. */
        PlaneProjection zProjection = PlaneProjection.NONE;

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

    /** Frame selection meaning every time point, combined. */
    private static final int ALL_FRAMES = -1;

    /**
     * The time points one position is written with: {@link #ALL_FRAMES} once when averaging, else
     * each time point from the first while every channel and slice has a plane in it. A time point
     * an acquisition stopped part way through, and any after it, is left out and noted.
     *
     * @return frame selections, one per output time point
     */
    private static List<Integer> outputFrames(
            List<MicroManagerPlanes.Plane> planes, Options options, String label, List<String> notes) {
        if (options.time().reduces()) {
            return List.of(ALL_FRAMES);
        }
        TreeMap<Integer, Set<Long>> byFrame = new TreeMap<>();
        Set<Long> all = new HashSet<>();
        for (MicroManagerPlanes.Plane plane : planes) {
            long key = ((long) plane.coord().z() << 32) | plane.coord().c();
            byFrame.computeIfAbsent(plane.coord().t(), k -> new HashSet<>()).add(key);
            all.add(key);
        }
        int frames = byFrame.isEmpty() ? 1 : byFrame.lastKey() + 1;
        List<Integer> out = new ArrayList<>();
        for (int t = 0; t < frames; t++) {
            Set<Long> have = byFrame.get(t);
            if (have == null || !have.containsAll(all)) {
                if (t == 0) {
                    // Refused when it is read, naming the missing plane.
                    out.add(0);
                } else {
                    notes.add(label + ": time point " + t + " has " + (have == null ? 0 : have.size()) + " of "
                            + all.size() + " planes; it and the " + (frames - t - 1)
                            + " after it were left out");
                }
                break;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Combine the time points (or take one), correct the shading, then project z if asked, noting missing
     * planes. The correction must come before the projection: a sum would otherwise take the offset
     * off once for many planes, and averaging is unaffected either way.
     *
     * @param sparse see {@link #combineFrames}
     * @param frame the time point to reduce, or {@link #ALL_FRAMES} to combine them
     */
    private static Planes reduce(
            List<MicroManagerPlanes.Plane> planes,
            boolean singlePlane,
            boolean sparse,
            Options options,
            String label,
            List<String> notes,
            int frame)
            throws IOException {
        Planes p = combineFrames(planes, singlePlane, sparse, frame, options.time());
        if (p.missing > 0) {
            notes.add(label + ": " + p.missing + " of " + p.frames * p.planeCount() + " planes absent; those planes' "
                    + options.time().words() + " taken over the frames present");
        }
        if (options.flatfield() != null) {
            for (int z = 0; z < p.slices; z++) {
                for (int c = 0; c < p.channels; c++) {
                    if (options.flatfield().appliesTo(c) && p.mean[c + p.channels * z] != null) {
                        options.flatfield().correct(p.mean[c + p.channels * z], p.width, p.height);
                    }
                }
            }
        }
        if (options.z().reduces()) {
            projectZ(p, options.z());
        }
        return p;
    }

    /**
     * Every (channel, slice) combined over the frames, or one time point's planes.
     *
     * @param planes the position's planes, in the order MicroManager wrote them
     * @param singlePlane each file is a single-plane TIFF series file and must hold one page
     * @param sparse a (channel, slice) this position has at no time point is left null rather than
     *     refused: an NDTiff position may hold only some of the dataset's z-slices (Magellan explores
     *     each tile over its own z range); the slices are those of the whole dataset
     * @param frame the time point to take, or {@link #ALL_FRAMES} to combine them all
     * @param time how the frames are combined when {@code frame} is {@link #ALL_FRAMES}
     */
    private static Planes combineFrames(
            List<MicroManagerPlanes.Plane> planes, boolean singlePlane, boolean sparse, int frame, PlaneProjection time)
            throws IOException {
        boolean averageFrames = frame == ALL_FRAMES;
        // One time point is a sum of one plane, divided by one.
        PlaneProjection combine = averageFrames && time.reduces() ? time : PlaneProjection.MEAN;
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
                        if (!averageFrames && coord.t() != frame) {
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
                        boolean firstPlane = !sums.containsKey(key);
                        double[] sum = sums.computeIfAbsent(key, k -> new double[p.width * p.height]);
                        accumulate(r, sum, combine, firstPlane);
                        counts.merge(key, 1, Integer::sum);
                    }
                } finally {
                    reader.dispose();
                }
            }
        }

        Set<Long> present = new HashSet<>();
        Set<Integer> presentZ = new HashSet<>();
        for (MicroManagerPlanes.Plane plane : planes) {
            present.add(((long) plane.coord().z() << 32) | plane.coord().c());
            presentZ.add(plane.coord().z());
        }
        p.channels = maxC + 1;
        p.slices = maxZ + 1;
        p.slicesInSource = sparse ? presentZ.size() : p.slices;
        p.framesInSource = maxT + 1;
        p.frames = averageFrames ? p.framesInSource : 1;
        p.timeProjection = averageFrames ? combine : PlaneProjection.NONE;
        p.mean = new double[p.planeCount()][];
        for (int z = 0; z < p.slices; z++) {
            for (int c = 0; c < p.channels; c++) {
                long key = ((long) z << 32) | c;
                double[] sum = sums.get(key);
                int n = counts.getOrDefault(key, 0);
                if (sum == null || n == 0) {
                    if (sparse && !present.contains(key)) {
                        continue;
                    }
                    throw new IOException("Channel " + c + ", slice " + z + " has no "
                            + (averageFrames ? "frames" : "plane at time point " + frame) + " in "
                            + planes.get(0).file());
                }
                p.missing += p.frames - n;
                if (combine == PlaneProjection.MEAN) {
                    for (int k = 0; k < sum.length; k++) sum[k] /= n;
                }
                p.mean[c + p.channels * z] = sum;
            }
        }
        return p;
    }

    /**
     * Replace each channel's slices by their per-pixel mean, maximum or minimum; a null (absent)
     * slice is skipped.
     */
    static void projectZ(Planes p, PlaneProjection mode) {
        if (p.slices <= 1 || !mode.reduces()) return;
        double[][] out = new double[p.channels][];
        for (int c = 0; c < p.channels; c++) {
            double[] acc = null;
            int n = 0;
            for (int z = 0; z < p.slices; z++) {
                double[] plane = p.mean[c + p.channels * z];
                if (plane == null) continue;
                n++;
                if (acc == null) {
                    acc = plane.clone();
                    continue;
                }
                combineInto(acc, plane, mode);
            }
            if (acc != null && mode == PlaneProjection.MEAN) {
                for (int k = 0; k < acc.length; k++) acc[k] /= n;
            }
            out[c] = acc;
        }
        p.mean = out;
        p.slices = 1;
        p.zProjection = mode;
    }

    /** Fold {@code plane} into {@code acc}: sum for MEAN (divided later), else maximum or minimum. */
    private static void combineInto(double[] acc, double[] plane, PlaneProjection mode) {
        switch (mode) {
            case MAX -> {
                for (int k = 0; k < acc.length; k++) if (plane[k] > acc[k]) acc[k] = plane[k];
            }
            case MIN -> {
                for (int k = 0; k < acc.length; k++) if (plane[k] < acc[k]) acc[k] = plane[k];
            }
            default -> {
                for (int k = 0; k < acc.length; k++) acc[k] += plane[k];
            }
        }
    }

    /**
     * Add a page to the running sum. Sums are doubles, exact for integers up to 2^53, so 16-bit
     * frames cannot wrap or lose precision below about 10^11 frames; the type's range is applied
     * only to the final value, in {@link #pack}.
     */
    private static void accumulate(Raster r, double[] sum, PlaneProjection mode, boolean first) {
        int w = r.getWidth();
        int h = r.getHeight();
        double[] row = new double[w];
        for (int y = 0; y < h; y++) {
            r.getSamples(r.getMinX(), r.getMinY() + y, w, 1, 0, row);
            int off = y * w;
            if (first || mode == PlaneProjection.MEAN) {
                for (int x = 0; x < w; x++) sum[off + x] += row[x];
            } else if (mode == PlaneProjection.MAX) {
                for (int x = 0; x < w; x++) if (row[x] > sum[off + x]) sum[off + x] = row[x];
            } else {
                for (int x = 0; x < w; x++) if (row[x] < sum[off + x]) sum[off + x] = row[x];
            }
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

    /** One output time point's planes, channel fastest then z; reduced only when it is written. */
    @FunctionalInterface
    private interface FramePlanes {
        List<double[]> get(int t) throws IOException;
    }

    /**
     * Write planes (channel fastest, then z, then time) as one TIFF, one time point at a time;
     * {@code .ome.tif} gets OME-XML, a plain {@code .tif} is a plain TIFF.
     *
     * @param p the size and pixel type of every plane
     */
    private static void writeTiff(
            Path path,
            Planes p,
            int sizeC,
            int sizeZ,
            int sizeT,
            FramePlanes planes,
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
                sizeT,
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
            int i = 0;
            for (int t = 0; t < sizeT; t++) {
                for (double[] plane : planes.get(t)) {
                    writer.saveBytes(i++, pack(plane, p.pixelType));
                }
            }
        } catch (FormatException e) {
            throw new IOException("Could not write " + path, e);
        }
    }

    /**
     * The source sidecar reshaped to what was written: one FrameKey per written (t, c, z), in page
     * order, each the source block of that time point (the earliest one when averaged).
     *
     * @param frames the frame selection of each written time point
     */
    private static JsonObject reducedSidecar(
            JsonObject sidecar, Planes p, List<Integer> frames, String outName, Path source, Options options) {
        JsonObject out = new JsonObject();
        JsonObject summary = summaryOf(sidecar);
        if (summary != null) {
            out.add("Summary", reducedSummary(summary, p, frames.size(), source, options));
        }
        Map<String, JsonObject> earliest = new LinkedHashMap<>();
        Map<String, JsonObject> byKey = new HashMap<>();
        for (String key : sidecar.keySet()) {
            Matcher m = FRAME_KEY.matcher(key);
            if (!m.matches() || !sidecar.get(key).isJsonObject()) continue;
            String cz = m.group(2) + "-" + m.group(3);
            int t = Integer.parseInt(m.group(1));
            JsonObject block = sidecar.getAsJsonObject(key);
            byKey.put(t + "-" + cz, block);
            JsonObject have = earliest.get(cz);
            if (have == null || t < have.get("__t").getAsInt()) {
                JsonObject copy = block.deepCopy();
                copy.addProperty("__t", t);
                earliest.put(cz, copy);
            }
        }
        for (int ti = 0; ti < frames.size(); ti++) {
            int frame = frames.get(ti);
            for (int z = 0; z < p.slices; z++) {
                for (int c = 0; c < p.channels; c++) {
                    JsonObject own = frame == ALL_FRAMES ? null : byKey.get(frame + "-" + c + "-" + z);
                    JsonObject block = own != null ? own : earliest.get(c + "-" + z);
                    if (block == null) continue;
                    block = block.deepCopy();
                    block.remove("__t");
                    block.addProperty("FileName", outName);
                    annotate(block, p);
                    out.add("FrameKey-" + ti + "-" + c + "-" + z, block);
                }
            }
        }
        return out;
    }

    /** Per-plane note of what went into the plane. */
    private static void annotate(JsonObject block, Planes p) {
        block.addProperty("FramesAveraged", p.frames);
        block.addProperty("TimeProjection", p.timeProjection.name());
        if (p.projected()) {
            block.addProperty("SlicesMaxProjected", p.slicesInSource);
            block.addProperty("ZProjection", p.zProjection.name());
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

    /**
     * The {@code FrameKey-t-c-z} block of each Micro-Manager 1.4 single-plane file, by file name.
     *
     * @return file name -> the key of its block; empty for a Micro-Manager 2 sidecar
     */
    private static Map<String, String> mm14FrameKeys(JsonObject sidecar) {
        Map<String, String> out = new HashMap<>();
        for (String key : sidecar.keySet()) {
            String name = mm14FileName(key, sidecar.get(key));
            if (name != null) out.putIfAbsent(name, key);
        }
        return out;
    }

    /** The file a 1.4 single-plane {@code FrameKey-} block names, or null for any other entry. */
    private static String mm14FileName(String key, JsonElement value) {
        if (!FRAME_KEY.matcher(key).matches() || value == null || !value.isJsonObject()) return null;
        JsonElement f = value.getAsJsonObject().get("FileName");
        if (f == null || !f.isJsonPrimitive()) return null;
        String name = f.getAsString().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.contains("_MMStack_") ? null : name;
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
     * written channel, slice and time point, named as that plane's source file, and a matching metadata.txt.
     *
     * @return the first written time point's planes, or null when the folder held no TIFFs
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
        Map<String, Path> byTzc = new HashMap<>();
        Map<String, String> frameKeyOf = mm14FrameKeys(sidecar);
        List<String> chNames = channelNames(sidecar);
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.sorted()::iterator) {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.contains(":") || !(lower.endsWith(".tif") || lower.endsWith(".tiff"))) continue;
                if (!frameKeyOf.containsKey(name)
                        && MicroManagerPlanes.isMm14FileName(name)
                        && !sidecar.has("Metadata-" + label + "/" + name)) {
                    // A 1.4 file without its FrameKey block: the metadata it carries stands in for
                    // one, so the copy's metadata.txt places it as the stitch would the source.
                    JsonObject md = MicroManagerPlanes.embeddedMetadata(f);
                    MicroManagerPlanes.Coord k = md == null ? null : MicroManagerPlanes.fromMm14FileName(name, chNames);
                    if (k == null && md != null) k = MicroManagerPlanes.fromBlock(md);
                    if (k != null) {
                        String key = "FrameKey-" + k.t() + "-" + k.c() + "-" + k.z();
                        if (!sidecar.has(key)) {
                            JsonObject block = md.deepCopy();
                            block.addProperty("FileName", name);
                            sidecar.add(key, block);
                            frameKeyOf.put(name, key);
                        }
                    }
                }
                String frameKey = frameKeyOf.get(name);
                MicroManagerPlanes.Coord c = MicroManagerPlanes.singlePlaneCoord(
                        name,
                        sidecar.get("Metadata-" + label + "/" + name),
                        sidecar.get("Coords-" + label + "/" + name));
                if (c == null && frameKey != null) {
                    c = MicroManagerPlanes.fromFrameKey(frameKey, sidecar.get(frameKey));
                }
                if (c == null) {
                    c = MicroManagerPlanes.fromMm14FileName(name, chNames);
                }
                if (c == null) {
                    throw new IOException("Cannot tell which channel, slice and frame " + label + "/" + name
                            + " holds: its name is neither img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif"
                            + " (Micro-Manager 2) nor img_TTTTTTTTT_<channel>_ZZZ.tif (1.4) with a channel"
                            + " listed in Summary.ChNames, and metadata.txt has no Metadata-, Coords- or"
                            + " FrameKey- block for it");
                }
                coords.put(f, c);
                byTzc.put(c.t() + "-" + c.z() + "-" + c.c(), f);
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
        List<Integer> outFrames = outputFrames(filePlanes, options, label, notes);

        Files.createDirectories(outDir);
        List<String> names = channelNames(sidecar);
        Set<String> kept = new HashSet<>();
        Planes firstPlanes = null;
        for (int frame : outFrames) {
            Planes planes = reduce(filePlanes, true, false, options, label, notes, frame);
            if (firstPlanes == null) firstPlanes = planes;
            for (int z = 0; z < planes.slices; z++) {
                for (int c = 0; c < planes.channels; c++) {
                    // Named as the plane's own source file: its time point's, or its first frame's
                    // when averaged (the first slice's when projected).
                    Path from = frame == ALL_FRAMES
                            ? earliest.get(((long) z << 32) | c)
                            : byTzc.get(frame + "-" + z + "-" + c);
                    if (from == null) continue;
                    String name = from.getFileName().toString();
                    kept.add(name);
                    JsonElement block = sidecar.get("Metadata-" + label + "/" + name);
                    if (block == null && frameKeyOf.containsKey(name)) {
                        block = sidecar.get(frameKeyOf.get(name));
                    }
                    double[] plane = planes.mean[c + planes.channels * z];
                    writeTiff(
                            outDir.resolve(name),
                            planes,
                            1,
                            1,
                            1,
                            t -> List.of(plane),
                            c < names.size() ? java.util.Collections.singletonList(names.get(c)) : List.of(),
                            block != null && block.isJsonObject() ? block.getAsJsonObject() : null,
                            label);
                }
            }
        }
        Files.writeString(
                outDir.resolve("metadata.txt"),
                GSON.toJson(reducedSinglePlaneSidecar(sidecar, kept, firstPlanes, outFrames.size(), src, options)),
                StandardCharsets.UTF_8);
        logReduced(label, firstPlanes, options);
        return firstPlanes;
    }

    /**
     * The position's metadata.txt for what was written: Summary describes the reduced shape, and
     * only the per-file blocks of the files written are kept.
     */
    private static JsonObject reducedSinglePlaneSidecar(
            JsonObject sidecar, Set<String> kept, Planes p, int framesOut, Path source, Options options) {
        JsonObject out = new JsonObject();
        for (String key : sidecar.keySet()) {
            JsonElement value = sidecar.get(key);
            if (key.equals("Summary") && value.isJsonObject()) {
                out.add(key, reducedSummary(value.getAsJsonObject(), p, framesOut, source, options));
                continue;
            }
            String rel = key.startsWith("Metadata-")
                    ? key.substring("Metadata-".length())
                    : key.startsWith("Coords-") ? key.substring("Coords-".length()) : mm14FileName(key, value);
            if (rel == null) {
                out.add(key, value.deepCopy());
                continue;
            }
            String base = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            if (!kept.contains(base)) continue;
            JsonElement copy = value.deepCopy();
            if ((key.startsWith("Metadata-") || key.startsWith("FrameKey-")) && copy.isJsonObject()) {
                annotate(copy.getAsJsonObject(), p);
            }
            out.add(key, copy);
        }
        return out;
    }

    /** A copy of Summary describing the frames written (and one slice when projected), recording the method. */
    private static JsonObject reducedSummary(
            JsonObject summary, Planes p, int framesOut, Path source, Options options) {
        JsonObject s = summary.deepCopy();
        s.addProperty("Frames", framesOut);
        JsonObject intended =
                s.has("IntendedDimensions") && s.get("IntendedDimensions").isJsonObject()
                        ? s.getAsJsonObject("IntendedDimensions")
                        : null;
        if (intended != null) intended.addProperty("time", framesOut);
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
        rec.addProperty("TimeProjection", p.timeProjection.name());
        rec.addProperty("ZProjection", p.zProjection.name());
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
