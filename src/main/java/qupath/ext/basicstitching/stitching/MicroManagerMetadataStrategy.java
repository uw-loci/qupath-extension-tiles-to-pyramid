package qupath.ext.basicstitching.stitching;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.regions.ImageRegion;

/**
 * Stitching strategy that reads tile positions directly from MicroManager 2
 * MMStack sidecar metadata files (the {@code *_metadata.txt} JSON files saved
 * next to each {@code *.ome.tif} when MicroManager exports a multi-position
 * acquisition as one TIFF per position).
 *
 * <p>This bypasses the need for a separate TileConfiguration.txt -- the
 * MMStack metadata already carries authoritative per-tile stage coordinates
 * (FrameKey-0-0-0.XPositionUm / YPositionUm) and a global stage-position list
 * (Summary.StagePositions) that maps labels to nominal positions.
 *
 * <p>Layout assumption: tiles and their {@code _metadata.txt} sidecars live
 * directly in {@code folderPath}; the strategy does not recurse into
 * subdirectories. The {@code matchingString} argument is unused (MicroManager
 * folders are not multi-angle).
 */
public class MicroManagerMetadataStrategy implements StitchingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(MicroManagerMetadataStrategy.class);

    /**
     * Optional caller-set flag: when {@code true}, the Y coordinate read from
     * MMStack metadata is negated before converting to pixel space. Mirrors
     * {@link TileConfigurationTxtStrategy#flipStitchingY} so the QPSC
     * stage/camera transform path keeps working if this strategy is wired
     * into the regular acquisition flow.
     */
    public static volatile boolean flipStitchingY = false;

    /** Mirror of {@link #flipStitchingY} for the X axis. */
    public static volatile boolean flipStitchingX = false;

    private static final Gson GSON = new Gson();

    @Override
    public List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString) {
        logger.info("Preparing stitching using MicroManager MMStack metadata for folder: {}", folderPath);
        List<TileMapping> mappings = new ArrayList<>();
        Path rootdir = Paths.get(folderPath);
        if (!Files.isDirectory(rootdir)) {
            logger.warn("Folder does not exist or is not a directory: {}", folderPath);
            return mappings;
        }

        boolean flipY = flipStitchingY;
        boolean flipX = flipStitchingX;
        if (flipY) {
            logger.info("flipStitchingY=true: negating Y coordinates for stage-inverted scope");
        }
        if (flipX) {
            logger.info("flipStitchingX=true: negating X coordinates for stage-inverted scope");
        }

        // Pass 1: read all per-tile MMStack metadata sidecars. Each one
        // describes exactly one TIFF via FrameKey-0-0-0.FileName and gives the
        // authoritative recorded stage position for that tile.
        Map<String, double[]> filenameToPosUm = new LinkedHashMap<>();
        // Cached label -> (xUm, yUm) from any Summary.StagePositions block we
        // encounter. Used as a fallback when a TIFF lacks a sidecar.
        Map<String, double[]> labelToPosUm = new HashMap<>();

        List<Path> metadataFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootdir, "*_metadata.txt")) {
            for (Path p : stream) {
                if (p.getFileName().toString().contains(":")) continue;
                metadataFiles.add(p);
            }
        } catch (IOException e) {
            logger.error("Error scanning for MMStack metadata files in {}: {}", folderPath, e.getMessage());
            return mappings;
        }

        if (metadataFiles.isEmpty()) {
            logger.warn("No MMStack *_metadata.txt files found in {}", folderPath);
            return mappings;
        }
        logger.info("Found {} MMStack metadata file(s) in {}", metadataFiles.size(), folderPath);

        // Detected pixel size from any sidecar's FrameKey-0-0-0.PixelSizeUm.
        // When set, this OVERRIDES the caller's pixelSizeInMicrons argument --
        // the MMStack metadata is authoritative for pixel size. The argument is
        // kept as a fallback in case no sidecar reports PixelSizeUm.
        Double detectedPixelSizeUm = null;

        for (Path metaPath : metadataFiles) {
            try (Reader reader = Files.newBufferedReader(metaPath)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root == null) {
                    logger.warn("Empty or unparseable metadata file: {}", metaPath);
                    continue;
                }

                // Cache Summary.StagePositions for label-based fallback. Every
                // sidecar in a single acquisition carries the same list, so
                // this is idempotent.
                JsonObject summary = optObject(root, "Summary");
                if (summary != null && labelToPosUm.isEmpty()) {
                    JsonArray positions = optArray(summary, "StagePositions");
                    if (positions != null) {
                        for (JsonElement el : positions) {
                            if (!el.isJsonObject()) continue;
                            JsonObject entry = el.getAsJsonObject();
                            String label = optString(entry, "Label");
                            if (label == null) continue;
                            double[] xy = extractDevicePositionUm(entry);
                            if (xy != null) {
                                labelToPosUm.put(label, xy);
                            }
                        }
                        logger.debug("Cached {} StagePositions labels", labelToPosUm.size());
                    }
                }

                // FrameKey-0-0-0 holds the authoritative per-tile recording.
                JsonObject frame = optObject(root, "FrameKey-0-0-0");
                if (frame == null) {
                    logger.debug("No FrameKey-0-0-0 in {}, skipping", metaPath.getFileName());
                    continue;
                }

                // First-sidecar PixelSizeUm wins. Every sidecar in a single
                // acquisition carries the same value, so this is idempotent.
                if (detectedPixelSizeUm == null) {
                    Double frameSize = optDouble(frame, "PixelSizeUm");
                    if (frameSize != null && frameSize > 0) {
                        detectedPixelSizeUm = frameSize;
                    }
                }

                String tileFile = optString(frame, "FileName");
                Double xUm = optDouble(frame, "XPositionUm");
                Double yUm = optDouble(frame, "YPositionUm");
                if (tileFile == null || xUm == null || yUm == null) {
                    logger.debug(
                            "Sidecar {} missing FileName / XPositionUm / YPositionUm in FrameKey-0-0-0",
                            metaPath.getFileName());
                    continue;
                }
                filenameToPosUm.put(tileFile, new double[] {xUm, yUm});
            } catch (Exception e) {
                logger.warn("Failed to parse {}: {}", metaPath.getFileName(), e.getMessage());
            }
        }

        // Decide which pixel size drives the um->px conversion. MMStack
        // metadata wins when available; caller value is the fallback.
        double effectivePixelSize;
        if (detectedPixelSizeUm != null) {
            effectivePixelSize = detectedPixelSizeUm;
            if (Math.abs(detectedPixelSizeUm - pixelSizeInMicrons) > 1e-9) {
                logger.info(
                        "Using MMStack metadata pixel size {} um/px (caller value {} ignored -- "
                                + "MMStack sidecar is authoritative)",
                        detectedPixelSizeUm,
                        pixelSizeInMicrons);
            } else {
                logger.info("Using MMStack metadata pixel size {} um/px", detectedPixelSizeUm);
            }
        } else {
            effectivePixelSize = pixelSizeInMicrons;
            logger.info(
                    "No PixelSizeUm in MMStack sidecars; falling back to caller pixel size {} um/px",
                    pixelSizeInMicrons);
        }
        if (effectivePixelSize <= 0) {
            logger.error(
                    "Effective pixel size is {} um/px (must be > 0); cannot map tiles. "
                            + "Provide a valid pixel size via the dialog override.",
                    effectivePixelSize);
            return mappings;
        }

        // Pass 2: enumerate TIFFs and build TileMappings, falling back to the
        // label-based map for TIFFs whose sidecar was missing/malformed.
        List<Path> tiffFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootdir, "*.tif*")) {
            for (Path p : stream) {
                // Skip Windows NTFS alternate-data-stream artifacts that WSL
                // surfaces as phantom files (e.g. "foo.ome.tif:Zone.Identifier").
                if (p.getFileName().toString().contains(":")) continue;
                tiffFiles.add(p);
            }
        } catch (IOException e) {
            logger.error("Error listing TIFFs in {}: {}", folderPath, e.getMessage());
            return mappings;
        }

        if (tiffFiles.isEmpty()) {
            logger.warn("No *.tif* files found in {}", folderPath);
            return mappings;
        }
        logger.info("Found {} TIFF file(s) in {}", tiffFiles.size(), folderPath);

        String subdirName =
                rootdir.getFileName() != null ? rootdir.getFileName().toString() : "tiles";
        int processed = 0;
        int totalTiles = tiffFiles.size();
        for (Path tif : tiffFiles) {
            String filename = tif.getFileName().toString();
            double[] posUm = filenameToPosUm.get(filename);
            if (posUm == null) {
                String label = extractMMStackLabel(filename);
                if (label != null) {
                    posUm = labelToPosUm.get(label);
                    if (posUm != null) {
                        logger.debug("Tile {} resolved via Summary.StagePositions label '{}'", filename, label);
                    }
                }
            }
            if (posUm == null) {
                logger.warn("No MMStack position found for {} -- skipping", filename);
                continue;
            }

            Map<String, Integer> dims = UtilityFunctions.getTiffDimensions(tif.toFile());
            processed++;
            if (processed % 500 == 0 || processed == totalTiles) {
                logger.info("Tile dimension progress: {}/{} files processed", processed, totalTiles);
            }
            if (dims == null) {
                logger.warn("Could not read dimensions for {} -- skipping", filename);
                continue;
            }

            double rawX = posUm[0];
            double rawY = posUm[1];
            if (flipX) rawX = -rawX;
            if (flipY) rawY = -rawY;
            double x = rawX / (effectivePixelSize * baseDownsample);
            double y = rawY / (effectivePixelSize * baseDownsample);
            ImageRegion region = ImageRegion.createInstance(
                    (int) Math.round(x), (int) Math.round(y), dims.get("width"), dims.get("height"), 0, 0);
            mappings.add(new TileMapping(tif.toFile(), region, subdirName));
            logger.debug("Mapped {} at stage ({}, {}) um -> pixel ({}, {})", filename, posUm[0], posUm[1], x, y);
        }

        logger.info("Total tiles mapped from MMStack metadata: {}", mappings.size());
        return mappings;
    }

    /**
     * Scan a folder for MMStack sidecars and return the first non-null
     * {@code FrameKey-0-0-0.PixelSizeUm} value found. Used by the dialog
     * to auto-fill the pixel-size field before stitching runs.
     *
     * @param folder directory to scan; non-recursive
     * @return detected pixel size in microns ({@code > 0}), or {@code null}
     *         if no sidecar reports a usable value
     */
    public static Double detectPixelSizeUm(File folder) {
        if (folder == null || !folder.isDirectory()) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.toPath(), "*_metadata.txt")) {
            for (Path p : stream) {
                if (p.getFileName().toString().contains(":")) continue;
                try (Reader reader = Files.newBufferedReader(p)) {
                    JsonObject root = GSON.fromJson(reader, JsonObject.class);
                    if (root == null) continue;
                    JsonObject frame = optObject(root, "FrameKey-0-0-0");
                    if (frame == null) continue;
                    Double ps = optDouble(frame, "PixelSizeUm");
                    if (ps != null && ps > 0) {
                        return ps;
                    }
                } catch (Exception e) {
                    logger.debug("Could not parse {} for pixel size: {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.debug("Could not list MMStack sidecars in {}: {}", folder, e.getMessage());
        }
        return null;
    }

    /**
     * Extract the position label from a MicroManager MMStack TIFF filename.
     * Example: {@code prefix_MMStack_Pos-3-001_002.ome.tif} -> {@code Pos-3-001_002}.
     *
     * @return the label between {@code _MMStack_} and {@code .ome.tif}, or null
     *         if the filename doesn't match the MMStack convention
     */
    public static String extractMMStackLabel(String filename) {
        int idx = filename.indexOf("_MMStack_");
        if (idx < 0) return null;
        String tail = filename.substring(idx + "_MMStack_".length());
        int dot = tail.indexOf('.');
        return dot < 0 ? tail : tail.substring(0, dot);
    }

    /**
     * Find the first {@code DevicePositions} entry whose device is an XY stage
     * and return its (xUm, yUm) pair. Falls back to the first
     * {@code DevicePositions} entry with at least two coordinates if no entry
     * is explicitly marked as an XY stage.
     */
    private static double[] extractDevicePositionUm(JsonObject stageEntry) {
        JsonArray devices = optArray(stageEntry, "DevicePositions");
        if (devices == null) return null;
        String defaultXY = optString(stageEntry, "DefaultXYStage");
        double[] fallback = null;
        for (JsonElement de : devices) {
            if (!de.isJsonObject()) continue;
            JsonObject dev = de.getAsJsonObject();
            JsonArray posArr = optArray(dev, "Position_um");
            if (posArr == null || posArr.size() < 2) continue;
            double[] xy;
            try {
                xy = new double[] {posArr.get(0).getAsDouble(), posArr.get(1).getAsDouble()};
            } catch (Exception e) {
                continue;
            }
            String devName = optString(dev, "Device");
            if (defaultXY != null && defaultXY.equals(devName)) {
                return xy;
            }
            if (fallback == null) fallback = xy;
        }
        return fallback;
    }

    private static JsonObject optObject(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String optString(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double optDouble(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            return null;
        }
    }
}
