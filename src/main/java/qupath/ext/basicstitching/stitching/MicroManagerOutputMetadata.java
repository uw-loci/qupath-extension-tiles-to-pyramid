package qupath.ext.basicstitching.stitching;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;

/**
 * The acquisition metadata a stitched Micro-Manager image cannot carry, written beside it as
 * {@code <image stem>.mm-metadata.json}: pretty-printed JSON, UTF-8, keys in a fixed order, a key
 * left out when the metadata has no value for it.
 *
 * <p>Read from the sidecar of the acquisition's first position ({@code Summary.StagePositions[0]};
 * the first sidecar by path when that cannot be found), never from whichever sidecar a directory
 * listing gives first. Values that differ per channel come from that channel's plane of the first
 * time point and slice. A stitched preprocessed copy is traced back to the acquisition it was made
 * from ({@code Summary.Preprocessing.Source}). The operator's user and computer names are not
 * written.
 */
public final class MicroManagerOutputMetadata {
    private static final Logger logger = LoggerFactory.getLogger(MicroManagerOutputMetadata.class);

    /** Appended to the image's name stem. */
    public static final String SUFFIX = ".mm-metadata.json";

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MicroManagerOutputMetadata() {}

    /**
     * @param image a stitched image
     * @return where its metadata file lives: beside it, named from its stem
     */
    public static Path pathFor(Path image) {
        return image.resolveSibling(
                UtilityFunctions.imageStem(image.getFileName().toString()) + SUFFIX);
    }

    /**
     * Write the metadata file for one stitched image. Never throws: a missing metadata file must not
     * fail a stitch that produced a good image.
     *
     * @param image the stitched image
     * @param acquisition the Micro-Manager folder that was stitched
     * @param channels the acquisition's channel index of each channel of the image, in image order
     * @return the file written, or null when the folder holds no readable sidecar or writing failed
     */
    public static Path write(Path image, Path acquisition, List<Integer> channels) {
        try {
            JsonObject json = build(image.getFileName().toString(), acquisition, channels);
            if (json == null) {
                logger.info("No Micro-Manager sidecar under {}; no {} written", acquisition, SUFFIX);
                return null;
            }
            Path out = pathFor(image);
            Files.writeString(out, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
            logger.info("Wrote Micro-Manager metadata {}", out.getFileName());
            return out;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not write Micro-Manager metadata for {}: {}", image, e.toString());
            return null;
        }
    }

    /**
     * Move an image's metadata file to follow the image to a new path. A no-op when there is none.
     * Never throws.
     */
    public static void moveWith(Path from, Path to) {
        Path src = pathFor(from);
        Path dst = pathFor(to);
        if (src.equals(dst) || !Files.exists(src)) {
            return;
        }
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not move {} -> {}: {}", src, dst, e.toString());
        }
    }

    /**
     * The metadata for one stitched image.
     *
     * @param outputName the image's file name
     * @param acquisition the Micro-Manager folder that was stitched
     * @param channels the acquisition's channel index of each channel of the image, in image order
     * @return the JSON, or null when the folder holds no readable sidecar
     * @throws IOException when the chosen sidecar cannot be read
     */
    static JsonObject build(String outputName, Path acquisition, List<Integer> channels) throws IOException {
        Path root = acquisition.toAbsolutePath().normalize();
        Path sidecarFile = firstPositionSidecar(root);
        if (sidecarFile == null) {
            return null;
        }
        JsonObject sidecar = MicroManagerPlanes.readJson(sidecarFile);
        if (sidecar == null) {
            return null;
        }
        JsonObject summary = object(sidecar, "Summary");
        JsonObject preprocessing = object(summary, "Preprocessing");
        String sourceText = string(preprocessing, "Source");
        Path source = sourceText != null ? Path.of(sourceText) : root;

        List<String> chNames = new ArrayList<>();
        JsonArray names = array(summary, "ChNames");
        if (names != null) {
            for (JsonElement e : names) {
                chNames.add(e.isJsonPrimitive() ? e.getAsString() : null);
            }
        }
        List<JsonObject> blocks = new ArrayList<>();
        for (int c : channels) {
            blocks.add(firstPlaneBlock(sidecar, c));
        }
        JsonObject first = blocks.isEmpty() ? null : blocks.get(0);

        JsonObject out = new JsonObject();
        out.addProperty("source_folder", source.toString());
        out.addProperty(
                "source_folder_name",
                source.getFileName() == null ? "" : source.getFileName().toString());
        if (sourceText != null) {
            out.addProperty("preprocessed_copy", root.toString());
        }
        out.addProperty("stitched_output", outputName);
        putNumber(out, "pixel_size_um", number(first, "PixelSizeUm"));
        putString(out, "pixel_size_affine", string(first, "PixelSizeAffine"));
        putNumber(out, "z_position_um", number(first, "ZPositionUm"));
        putNumber(out, "z_step_um", number(summary, "z-step_um"));
        putExposure(out, channels, chNames, blocks);
        putNumber(out, "bit_depth", integer(first, "BitDepth"));
        putString(out, "pixel_type", string(summary, "PixelType"));
        putNumber(out, "tile_width", integer(summary, "Width"));
        putNumber(out, "tile_height", integer(summary, "Height"));
        JsonArray channelNames = new JsonArray();
        for (int c : channels) {
            if (c < chNames.size() && chNames.get(c) != null && !chNames.get(c).isEmpty()) {
                channelNames.add(chNames.get(c));
            }
        }
        if (!channelNames.isEmpty()) {
            out.add("channels", channelNames);
        }
        putString(out, "start_time", string(summary, "StartTime"));
        putString(out, "micro_manager_version", string(summary, "MicroManagerVersion"));
        JsonArray positions = array(summary, "StagePositions");
        if (positions != null) {
            int count = 0;
            int maxRow = -1;
            int maxCol = -1;
            for (JsonElement e : positions) {
                if (!e.isJsonObject()) continue;
                count++;
                Number row = integer(e.getAsJsonObject(), "GridRow");
                Number col = integer(e.getAsJsonObject(), "GridCol");
                if (row != null) maxRow = Math.max(maxRow, row.intValue());
                if (col != null) maxCol = Math.max(maxCol, col.intValue());
            }
            out.addProperty("tile_count", count);
            if (maxRow >= 0) out.addProperty("grid_rows", maxRow + 1);
            if (maxCol >= 0) out.addProperty("grid_cols", maxCol + 1);
        }
        Path comments = Files.isRegularFile(source.resolve("comments.txt"))
                ? source.resolve("comments.txt")
                : root.resolve("comments.txt");
        if (Files.isRegularFile(comments)) {
            String text = Files.readString(comments, StandardCharsets.UTF_8);
            if (!text.isEmpty()) out.addProperty("comments_txt", text);
        }
        return out;
    }

    /**
     * The sidecar of the acquisition's first position: the one {@code Summary.StagePositions[0]}
     * names ({@code <prefix>_MMStack_<label>_metadata.txt}, or {@code <label>/metadata.txt} in a
     * single-plane series), else the first sidecar by path.
     *
     * @return the sidecar, or null when the folder holds none
     */
    static Path firstPositionSidecar(Path root) {
        List<Path> files = new ArrayList<>(MicroManagerMetadataStrategy.findMetadataFiles(root));
        if (files.isEmpty()) {
            return null;
        }
        files.sort(null);
        String label = null;
        for (Path f : files) {
            try {
                JsonObject json = MicroManagerPlanes.readJson(f);
                JsonArray positions = array(object(json, "Summary"), "StagePositions");
                if (positions != null
                        && !positions.isEmpty()
                        && positions.get(0).isJsonObject()) {
                    label = string(positions.get(0).getAsJsonObject(), "Label");
                }
                break;
            } catch (IOException e) {
                logger.debug("Could not read {}: {}", f, e.toString());
            }
        }
        if (label != null) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                boolean stack = name.endsWith("_MMStack_" + label + "_metadata.txt");
                boolean folder = name.equals("metadata.txt")
                        && f.getParent() != null
                        && f.getParent().getFileName().toString().equals(label);
                if (stack || folder) {
                    return f;
                }
            }
        }
        return files.get(0);
    }

    /**
     * The block of channel {@code c}'s plane at the earliest time point, then the lowest slice:
     * {@code FrameKey-t-c-z} in an OME-TIFF per position's sidecar, the {@code Metadata-} block of a
     * single-plane file otherwise.
     *
     * @return the block, or null when the sidecar has none for that channel
     */
    static JsonObject firstPlaneBlock(JsonObject sidecar, int c) {
        JsonObject best = null;
        long bestOrder = Long.MAX_VALUE;
        for (String key : sidecar.keySet()) {
            JsonElement value = sidecar.get(key);
            if (!value.isJsonObject()) continue;
            MicroManagerPlanes.Coord coord = null;
            Matcher m = MicroManagerPlanes.FRAME_KEY.matcher(key);
            if (m.matches()) {
                coord = new MicroManagerPlanes.Coord(
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)));
            } else if (key.startsWith("Metadata-")) {
                String name = key.substring(key.lastIndexOf('/') + 1);
                coord = MicroManagerPlanes.singlePlaneCoord(name, value);
            }
            if (coord == null || coord.c() != c) continue;
            long order = ((long) coord.t() << 32) | coord.z();
            if (order < bestOrder) {
                bestOrder = order;
                best = value.getAsJsonObject();
            }
        }
        return best;
    }

    /**
     * {@code exposure_ms}: one number for an image of one channel; for several, an object keyed by
     * channel name in image order, holding each channel's own exposure. Left out when no channel
     * records one.
     */
    private static void putExposure(
            JsonObject out, List<Integer> channels, List<String> chNames, List<JsonObject> blocks) {
        if (channels.size() == 1) {
            putNumber(out, "exposure_ms", number(blocks.get(0), "Exposure-ms"));
            return;
        }
        JsonObject byChannel = new JsonObject();
        Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < channels.size(); i++) {
            Number ms = number(blocks.get(i), "Exposure-ms");
            if (ms == null) continue;
            int c = channels.get(i);
            String name = c < chNames.size() && chNames.get(c) != null ? chNames.get(c) : "channel_" + c;
            if (!used.add(name)) name = name + "_" + c;
            byChannel.addProperty(name, ms);
        }
        if (!byChannel.isEmpty()) {
            out.add("exposure_ms", byChannel);
        }
    }

    private static void putNumber(JsonObject out, String key, Number value) {
        if (value != null) out.addProperty(key, value);
    }

    private static void putString(JsonObject out, String key, String value) {
        if (value != null && !value.isEmpty()) out.addProperty(key, value);
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    private static Double number(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer integer(JsonObject parent, String key) {
        Double d = number(parent, key);
        return d == null ? null : d.intValue();
    }
}
