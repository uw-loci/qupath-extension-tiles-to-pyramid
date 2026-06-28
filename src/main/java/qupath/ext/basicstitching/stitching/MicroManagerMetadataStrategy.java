package qupath.ext.basicstitching.stitching;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.regions.ImageRegion;

/**
 * Stitching strategy that reads tile positions directly from MicroManager 2
 * sidecar metadata, supporting the two on-disk layouts MicroManager produces
 * for a multi-position acquisition:
 *
 * <ol>
 *   <li><b>Flat MMStack</b> ({@code MULTIPAGE_TIFF} / "separate file per
 *       position"): one {@code <prefix>_MMStack_<label>.ome.tif} per position
 *       plus a co-located {@code <prefix>_MMStack_<label>_metadata.txt} sidecar,
 *       all directly in the selected folder. The per-tile stage position lives
 *       in a {@code FrameKey-0-0-0} block, and each OME-TIFF carries OME-XML
 *       describing every position as a separate <em>series</em>, so a per-label
 *       series index is needed to read the right plane.</li>
 *   <li><b>Single-plane TIFF series</b> ({@code SINGLEPLANE_TIFF_SERIES}): each
 *       position is its own subfolder (e.g. {@code Pos-1-000_000/}) containing a
 *       single-image TIFF ({@code img_channelNNN_positionNNN_..._zNNN.tif}) and
 *       a {@code metadata.txt}. The per-tile stage position lives in a
 *       {@code Metadata-<relative/path/to.tif>} block whose key encodes the file
 *       name. Each TIFF is a genuine single-image file, so its series index is
 *       always 0.</li>
 * </ol>
 *
 * <p>Both layouts also carry a global {@code Summary.StagePositions} list that
 * maps labels to nominal positions; it is used as a fallback when a TIFF's
 * per-tile block is missing/malformed, and (for the flat MMStack layout) to
 * recover the series index for each label.
 *
 * <p>All tiles found under the selected folder are grouped into a single
 * stitched output named after the selected folder. The {@code matchingString}
 * argument is unused (MicroManager folders are not multi-angle).
 *
 * <p><b>Pixel size.</b> The metadata's {@code PixelSizeUm} is used by default,
 * but some scopes (notably laser-scanning microscopes whose zoom factor is not
 * reflected in MicroManager's pixel-size calibration) record a value that does
 * not match the true scale, which spreads tiles wrongly and duplicates overlap
 * regions. When the caller signals a deliberate manual override (see the
 * {@code manualPixelSizeOverride} constructor argument), the caller's pixel size
 * wins over the metadata. {@link #estimatePixelSizeUm(File)} can recover the
 * true value from the actual tile overlap when the metadata cannot be trusted.
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

    /**
     * Max depth (relative to the selected folder) walked when scanning for
     * metadata and TIFF files. Covers the flat MMStack layout (files at depth 1)
     * and the single-plane series layout (per-position subfolder at depth 2),
     * with one extra level of tolerance for an enclosing acquisition folder.
     */
    private static final int MAX_SCAN_DEPTH = 3;

    /**
     * When {@code true}, the pixel size passed to {@link #prepareStitching} is
     * authoritative and overrides any {@code PixelSizeUm} read from the
     * metadata. Set from the dialog's "Manually edit pixel size" checkbox so a
     * user can correct a scope whose metadata pixel-size calibration is wrong.
     */
    private final boolean manualPixelSizeOverride;

    /** Default: metadata {@code PixelSizeUm} is preferred over the caller value. */
    public MicroManagerMetadataStrategy() {
        this(false);
    }

    /**
     * @param manualPixelSizeOverride when {@code true}, the caller's pixel size
     *     wins over the metadata's {@code PixelSizeUm}
     */
    public MicroManagerMetadataStrategy(boolean manualPixelSizeOverride) {
        this.manualPixelSizeOverride = manualPixelSizeOverride;
    }

    /** Per-tile record resolved from a MicroManager metadata block. */
    private static final class TileRecord {
        final double xUm;
        final double yUm;
        /**
         * {@code true} for flat-MMStack tiles (multi-series OME-TIFFs whose
         * series index is the StagePositions array index); {@code false} for
         * single-plane TIFF-series tiles (always series 0).
         */
        final boolean multiSeries;

        TileRecord(double xUm, double yUm, boolean multiSeries) {
            this.xUm = xUm;
            this.yUm = yUm;
            this.multiSeries = multiSeries;
        }
    }

    /** Result of parsing all MicroManager metadata under a folder. */
    private static final class ParsedMetadata {
        /** Tile path (absolute, normalized) -> recorded stage position. */
        final Map<Path, TileRecord> pathToRecord = new LinkedHashMap<>();
        /** Summary.StagePositions label -> (xUm, yUm), fallback when a block is missing. */
        final Map<String, double[]> labelToPosUm = new HashMap<>();
        /** Summary.StagePositions label -> series index (flat-MMStack only). */
        final Map<String, Integer> labelToSeriesIndex = new HashMap<>();
        /** First usable PixelSizeUm found, or {@code null}. */
        Double detectedPixelSizeUm = null;
        /** Whether any flat-MMStack (multi-series) tile was seen. */
        boolean sawMultiSeries = false;
    }

    @Override
    public List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString) {
        logger.info("Preparing stitching using MicroManager metadata for folder: {}", folderPath);
        List<TileMapping> mappings = new ArrayList<>();
        Path rootdir = Paths.get(folderPath).toAbsolutePath().normalize();
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

        ParsedMetadata pm = parseMetadata(rootdir);
        if (pm.pathToRecord.isEmpty() && pm.labelToPosUm.isEmpty()) {
            logger.warn("No usable MicroManager metadata (metadata.txt / *_metadata.txt) found under {}", folderPath);
            return mappings;
        }

        // Decide which pixel size drives the um->px conversion.
        double effectivePixelSize;
        if (manualPixelSizeOverride && pixelSizeInMicrons > 0) {
            effectivePixelSize = pixelSizeInMicrons;
            if (pm.detectedPixelSizeUm != null && Math.abs(pm.detectedPixelSizeUm - pixelSizeInMicrons) > 1e-9) {
                logger.info(
                        "Manual pixel-size override: using {} um/px (metadata value {} ignored)",
                        pixelSizeInMicrons,
                        pm.detectedPixelSizeUm);
            } else {
                logger.info("Manual pixel-size override: using {} um/px", pixelSizeInMicrons);
            }
        } else if (pm.detectedPixelSizeUm != null) {
            effectivePixelSize = pm.detectedPixelSizeUm;
            if (Math.abs(pm.detectedPixelSizeUm - pixelSizeInMicrons) > 1e-9) {
                logger.info(
                        "Using MicroManager metadata pixel size {} um/px (caller value {} ignored -- "
                                + "the sidecar is authoritative; tick 'Manually edit pixel size' to override)",
                        pm.detectedPixelSizeUm,
                        pixelSizeInMicrons);
            } else {
                logger.info("Using MicroManager metadata pixel size {} um/px", pm.detectedPixelSizeUm);
            }
        } else {
            effectivePixelSize = pixelSizeInMicrons;
            logger.info(
                    "No PixelSizeUm in MicroManager metadata; falling back to caller pixel size {} um/px",
                    pixelSizeInMicrons);
        }
        if (effectivePixelSize <= 0) {
            logger.error(
                    "Effective pixel size is {} um/px (must be > 0); cannot map tiles. "
                            + "Provide a valid pixel size via the dialog override.",
                    effectivePixelSize);
            return mappings;
        }

        // Enumerate TIFFs and build TileMappings, falling back to the
        // label-based map for TIFFs whose per-tile block was missing/malformed.
        List<Path> tiffFiles = findTiffFiles(rootdir);
        if (tiffFiles.isEmpty()) {
            logger.warn("No *.tif* files found under {}", folderPath);
            return mappings;
        }
        logger.info("Found {} TIFF file(s) under {}", tiffFiles.size(), folderPath);

        // All tiles in one MicroManager acquisition stitch into a single output
        // named after the selected folder.
        String subdirName =
                rootdir.getFileName() != null ? rootdir.getFileName().toString() : "tiles";
        int processed = 0;
        int totalTiles = tiffFiles.size();
        for (Path tif : tiffFiles) {
            String filename = tif.getFileName().toString();
            // The label is the MMStack token in the filename, or (single-plane
            // layout) the per-position subfolder name -- both match the
            // Summary.StagePositions "Label".
            String label = extractMMStackLabel(filename);
            if (label == null) {
                label = parentFolderLabel(tif);
            }

            double[] posUm;
            int seriesIndex;
            TileRecord record = pm.pathToRecord.get(tif);
            if (record != null) {
                posUm = new double[] {record.xUm, record.yUm};
                seriesIndex = record.multiSeries ? pm.labelToSeriesIndex.getOrDefault(label, 0) : 0;
            } else {
                posUm = label != null ? pm.labelToPosUm.get(label) : null;
                if (posUm == null) {
                    logger.warn("No MicroManager position found for {} -- skipping", filename);
                    continue;
                }
                logger.debug("Tile {} resolved via Summary.StagePositions label '{}'", filename, label);
                // Without a per-tile block we cannot tell single- from
                // multi-series, so only apply the StagePositions series index
                // when the acquisition is known to use multi-series OME-TIFFs.
                seriesIndex = pm.sawMultiSeries ? pm.labelToSeriesIndex.getOrDefault(label, 0) : 0;
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

            mappings.add(new TileMapping(tif.toFile(), region, subdirName, seriesIndex));
            logger.debug(
                    "Mapped {} at stage ({}, {}) um -> pixel ({}, {}) series {}",
                    filename,
                    posUm[0],
                    posUm[1],
                    x,
                    y,
                    seriesIndex);
        }

        logger.info("Total tiles mapped from MicroManager metadata: {}", mappings.size());
        return mappings;
    }

    /**
     * Parse every MicroManager metadata file under {@code rootdir} into per-tile
     * records plus the Summary.StagePositions fallback maps.
     */
    private static ParsedMetadata parseMetadata(Path rootdir) {
        ParsedMetadata pm = new ParsedMetadata();
        List<Path> metadataFiles = findMetadataFiles(rootdir);
        if (metadataFiles.isEmpty()) {
            return pm;
        }
        logger.info("Found {} MicroManager metadata file(s) under {}", metadataFiles.size(), rootdir);

        for (Path metaPath : metadataFiles) {
            Path metaParent = metaPath.getParent();
            try (Reader reader = Files.newBufferedReader(metaPath)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root == null) {
                    logger.warn("Empty or unparseable metadata file: {}", metaPath);
                    continue;
                }

                // Cache Summary.StagePositions for label-based fallback (once).
                JsonObject summary = optObject(root, "Summary");
                if (summary != null && pm.labelToPosUm.isEmpty()) {
                    JsonArray positions = optArray(summary, "StagePositions");
                    if (positions != null) {
                        for (int idx = 0; idx < positions.size(); idx++) {
                            JsonElement el = positions.get(idx);
                            if (!el.isJsonObject()) continue;
                            JsonObject entry = el.getAsJsonObject();
                            String label = optString(entry, "Label");
                            if (label == null) continue;
                            double[] xy = extractDevicePositionUm(entry);
                            if (xy != null) {
                                pm.labelToPosUm.put(label, xy);
                            }
                            pm.labelToSeriesIndex.put(label, idx);
                        }
                        logger.debug(
                                "Cached {} StagePositions labels, {} series indices",
                                pm.labelToPosUm.size(),
                                pm.labelToSeriesIndex.size());
                    }
                }

                // Walk every top-level entry; pick up per-tile blocks from
                // either layout. "FrameKey-*" -> flat MMStack (multi-series),
                // "Metadata-*" -> single-plane TIFF series (the key after the
                // prefix is the tile's path relative to the acquisition root).
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    String key = entry.getKey();
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject block = entry.getValue().getAsJsonObject();

                    String relFile;
                    boolean multiSeries;
                    if (key.startsWith("Metadata-")) {
                        relFile = key.substring("Metadata-".length());
                        multiSeries = false;
                    } else if (key.startsWith("FrameKey-")) {
                        relFile = optString(block, "FileName");
                        multiSeries = true;
                    } else {
                        continue;
                    }
                    if (relFile == null || relFile.isBlank()) continue;

                    Double xUm = optDouble(block, "XPositionUm");
                    Double yUm = optDouble(block, "YPositionUm");
                    if (xUm == null || yUm == null) {
                        logger.debug("Block {} in {} missing XPositionUm / YPositionUm", key, metaPath.getFileName());
                        continue;
                    }

                    if (pm.detectedPixelSizeUm == null) {
                        Double ps = optDouble(block, "PixelSizeUm");
                        if (ps != null && ps > 0) {
                            pm.detectedPixelSizeUm = ps;
                        }
                    }
                    if (multiSeries) {
                        pm.sawMultiSeries = true;
                    }

                    Path tilePath = resolveTilePath(rootdir, metaParent, relFile);
                    pm.pathToRecord.put(tilePath, new TileRecord(xUm, yUm, multiSeries));
                }
            } catch (Exception e) {
                logger.warn("Failed to parse {}: {}", metaPath.getFileName(), e.getMessage());
            }
        }
        return pm;
    }

    /**
     * Recursively find MicroManager metadata files under {@code rootdir}:
     * single-plane series {@code metadata.txt} and flat-MMStack
     * {@code *_metadata.txt} sidecars. Skips Windows NTFS alternate-data-stream
     * artifacts that WSL surfaces as phantom files (names containing ':').
     */
    private static List<Path> findMetadataFiles(Path rootdir) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootdir, MAX_SCAN_DEPTH)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.contains(":")) return;
                if (name.equals("metadata.txt") || name.endsWith("_metadata.txt")) {
                    result.add(p);
                }
            });
        } catch (IOException e) {
            logger.error("Error scanning for MicroManager metadata files in {}: {}", rootdir, e.getMessage());
        }
        return result;
    }

    /** Recursively find {@code *.tif*} files under {@code rootdir}, skipping ':' ADS artifacts. */
    private static List<Path> findTiffFiles(Path rootdir) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootdir, MAX_SCAN_DEPTH)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.contains(":")) return;
                String lower = name.toLowerCase();
                if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                    result.add(p);
                }
            });
        } catch (IOException e) {
            logger.error("Error listing TIFFs in {}: {}", rootdir, e.getMessage());
        }
        return result;
    }

    /**
     * Resolve a tile path referenced from a metadata block to an absolute,
     * normalized path. The reference may be a bare filename (flat MMStack,
     * co-located with its sidecar) or a path relative to the acquisition root
     * (single-plane series, e.g. {@code Pos-1-000_000/img_..._z000.tif}). Tries
     * the plausible resolutions and returns the first that exists; if none does,
     * returns the root-relative resolution as a best-effort key.
     */
    private static Path resolveTilePath(Path rootdir, Path metaParent, String relFile) {
        String normalized = relFile.replace('\\', '/');
        Path rootRelative = rootdir.resolve(normalized).normalize();
        if (Files.isRegularFile(rootRelative)) {
            return rootRelative;
        }
        if (metaParent != null) {
            Path beside = metaParent.resolve(normalized).normalize();
            if (Files.isRegularFile(beside)) {
                return beside;
            }
            String baseName =
                    normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            Path besideBase = metaParent.resolve(baseName).normalize();
            if (Files.isRegularFile(besideBase)) {
                return besideBase;
            }
        }
        return rootRelative;
    }

    /**
     * Scan a folder (recursively) for MicroManager metadata and return the
     * first usable {@code PixelSizeUm} found in any per-tile block (flat-MMStack
     * {@code FrameKey-*} or single-plane {@code Metadata-*}). Used by the dialog
     * to auto-fill the pixel-size field before stitching runs.
     *
     * @param folder directory to scan
     * @return detected pixel size in microns ({@code > 0}), or {@code null}
     *         if no metadata reports a usable value
     */
    public static Double detectPixelSizeUm(File folder) {
        if (folder == null || !folder.isDirectory()) return null;
        Path rootdir = folder.toPath().toAbsolutePath().normalize();
        for (Path p : findMetadataFiles(rootdir)) {
            try (Reader reader = Files.newBufferedReader(p)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root == null) continue;
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    String key = entry.getKey();
                    if (!entry.getValue().isJsonObject()) continue;
                    if (!key.startsWith("FrameKey-") && !key.startsWith("Metadata-")) continue;
                    Double ps = optDouble(entry.getValue().getAsJsonObject(), "PixelSizeUm");
                    if (ps != null && ps > 0) {
                        return ps;
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not parse {} for pixel size: {}", p.getFileName(), e.getMessage());
            }
        }
        return null;
    }

    /** Outcome of {@link #estimatePixelSizeUm(File)}. */
    public static final class PixelSizeEstimate {
        /** Estimated pixel size in microns, or {@code <= 0} if estimation failed. */
        public final double pixelSizeUm;
        /** Correlation strength of the best matches, 0..1 (higher is more reliable). */
        public final double confidence;
        /** Human-readable summary suitable for a status label or dialog. */
        public final String message;

        PixelSizeEstimate(double pixelSizeUm, double confidence, String message) {
            this.pixelSizeUm = pixelSizeUm;
            this.confidence = confidence;
            this.message = message;
        }

        /** @return {@code true} if a usable pixel size was estimated. */
        public boolean ok() {
            return pixelSizeUm > 0;
        }
    }

    /**
     * Estimate the true pixel size from the actual tile overlap, independent of
     * the (possibly wrong) {@code PixelSizeUm} in the metadata. For each pair of
     * neighbouring tiles the recorded stage step (um) is divided by the pixel
     * shift recovered by normalized cross-correlation of the overlapping image
     * content. The median over several pairs is returned.
     *
     * <p>Intended for scopes whose metadata pixel-size calibration is unreliable
     * (e.g. laser-scanning microscopes). Requires textured tiles with genuine
     * overlap; returns a failed estimate (with a message) when it cannot find a
     * confident match.
     *
     * @param folder the acquisition folder
     * @return the estimate; check {@link PixelSizeEstimate#ok()}
     */
    public static PixelSizeEstimate estimatePixelSizeUm(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return new PixelSizeEstimate(-1, 0, "No folder selected.");
        }
        Path rootdir = folder.toPath().toAbsolutePath().normalize();
        ParsedMetadata pm = parseMetadata(rootdir);
        if (pm.pathToRecord.size() < 2) {
            return new PixelSizeEstimate(-1, 0, "Need at least two tiles with stage positions to estimate pixel size.");
        }

        // Build a flat list of (path, xUm, yUm) for neighbour search.
        List<Path> paths = new ArrayList<>(pm.pathToRecord.keySet());
        int n = paths.size();
        double[][] xy = new double[n][2];
        for (int i = 0; i < n; i++) {
            TileRecord r = pm.pathToRecord.get(paths.get(i));
            xy[i][0] = r.xUm;
            xy[i][1] = r.yUm;
        }

        // Tolerance (um) for two tiles being in the same row/column. Use a small
        // fraction of the smallest non-zero stage step seen on each axis.
        double tolX = 0.25 * smallestPositiveStep(xy, 0);
        double tolY = 0.25 * smallestPositiveStep(xy, 1);

        List<Double> estimates = new ArrayList<>();
        List<Double> peaks = new ArrayList<>();
        int maxPairsPerAxis = 4;

        // Horizontal neighbours: same row (|dy| < tolY), adjacent in X.
        collectAxisEstimates(paths, xy, true, tolY, maxPairsPerAxis, estimates, peaks);
        // Vertical neighbours: same column (|dx| < tolX), adjacent in Y.
        collectAxisEstimates(paths, xy, false, tolX, maxPairsPerAxis, estimates, peaks);

        if (estimates.isEmpty()) {
            return new PixelSizeEstimate(
                    -1, 0, "Could not find a confident tile overlap to measure. Tiles may lack texture or overlap.");
        }
        double median = median(estimates);
        double medianPeak = median(peaks);
        String msg = String.format(
                "Estimated %.4f um/px from %d tile pair(s) (confidence %.2f).", median, estimates.size(), medianPeak);
        logger.info("Pixel-size estimate: {}", msg);
        return new PixelSizeEstimate(median, medianPeak, msg);
    }

    /**
     * Find up to {@code maxPairs} adjacent tile pairs along one axis and append
     * one pixel-size estimate per confident match. When {@code horizontal} is
     * true, pairs share a row (Y within {@code tol}) and step in X; otherwise
     * they share a column (X within {@code tol}) and step in Y.
     */
    private static void collectAxisEstimates(
            List<Path> paths,
            double[][] xy,
            boolean horizontal,
            double tol,
            int maxPairs,
            List<Double> estimates,
            List<Double> peaks) {
        int along = horizontal ? 0 : 1; // axis that varies between neighbours
        int across = horizontal ? 1 : 0; // axis that stays constant within a line
        int n = paths.size();
        int found = 0;
        for (int i = 0; i < n && found < maxPairs; i++) {
            // Find the nearest tile with a larger 'along' coord on the same line.
            int best = -1;
            double bestGap = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                if (Math.abs(xy[j][across] - xy[i][across]) > tol) continue;
                double gap = xy[j][along] - xy[i][along];
                if (gap <= 0) continue;
                if (gap < bestGap) {
                    bestGap = gap;
                    best = j;
                }
            }
            if (best < 0 || bestGap <= 0 || bestGap == Double.MAX_VALUE) continue;

            float[][] a = loadGray(paths.get(i).toFile());
            float[][] b = loadGray(paths.get(best).toFile());
            if (a == null || b == null) continue;

            double[] shiftPeak = bestOverlapShift(a, b, horizontal);
            double shiftPx = shiftPeak[0];
            double peak = shiftPeak[1];
            if (shiftPx <= 0 || peak < 0.3) continue; // require a real, confident overlap

            double pixelSize = bestGap / shiftPx;
            if (pixelSize > 0 && Double.isFinite(pixelSize)) {
                estimates.add(pixelSize);
                peaks.add(peak);
                found++;
            }
        }
    }

    /** Smallest strictly-positive pairwise difference along {@code axis}; 1.0 if none. */
    private static double smallestPositiveStep(double[][] xy, int axis) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < xy.length; i++) {
            for (int j = i + 1; j < xy.length; j++) {
                double d = Math.abs(xy[i][axis] - xy[j][axis]);
                if (d > 1e-6 && d < min) min = d;
            }
        }
        return (min == Double.MAX_VALUE) ? 1.0 : min;
    }

    /**
     * Recover the pixel step between two adjacent tiles by normalized
     * cross-correlation of their overlapping content. For horizontal neighbours
     * the right strip of {@code a} overlaps the left strip of {@code b}; the
     * returned step is the X offset of {@code b} relative to {@code a}. Search is
     * done on 2x-downsampled images for speed and scaled back.
     *
     * @return {@code [stepPx, peakNcc]}; {@code stepPx <= 0} if no match
     */
    private static double[] bestOverlapShift(float[][] a, float[][] b, boolean horizontal) {
        float[][] da = downsample2(a);
        float[][] db = downsample2(b);
        int h = Math.min(da.length, db.length);
        int w = Math.min(da[0].length, db[0].length);
        int along = horizontal ? w : h; // dimension along the step direction
        int perpRange = 8; // +/- search across the seam (downsampled px)

        int minStep = Math.max(2, (int) Math.round(0.03 * along)); // up to ~97% overlap
        int maxStep = (int) Math.round(0.97 * along); // down to ~3% overlap
        int minOverlap = Math.max(8, (int) Math.round(0.12 * along));

        double bestNcc = -2;
        int bestStep = -1;
        for (int step = minStep; step <= maxStep; step++) {
            for (int perp = -perpRange; perp <= perpRange; perp++) {
                int ox = horizontal ? step : perp;
                int oy = horizontal ? perp : step;
                if (along - (horizontal ? ox : oy) < minOverlap) continue;
                double ncc = nccAtShift(da, db, ox, oy, w, h);
                if (ncc > bestNcc) {
                    bestNcc = ncc;
                    bestStep = step;
                }
            }
        }
        if (bestStep < 0) return new double[] {-1, 0};
        return new double[] {bestStep * 2.0, bestNcc}; // undo 2x downsample
    }

    /**
     * Normalized cross-correlation between {@code a} and {@code b} where pixel
     * {@code a[y][x]} is matched against {@code b[y-oy][x-ox]} over their
     * overlapping region. Returns a value in roughly [-1, 1]; -2 if the overlap
     * is empty.
     */
    private static double nccAtShift(float[][] a, float[][] b, int ox, int oy, int w, int h) {
        int x0 = Math.max(0, ox);
        int y0 = Math.max(0, oy);
        int x1 = Math.min(w, w + ox);
        int y1 = Math.min(h, h + oy);
        int count = 0;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        for (int y = y0; y < y1; y++) {
            int by = y - oy;
            float[] arow = a[y];
            float[] brow = b[by];
            for (int x = x0; x < x1; x++) {
                float av = arow[x];
                float bv = brow[x - ox];
                sa += av;
                sb += bv;
                saa += av * av;
                sbb += bv * bv;
                sab += av * bv;
                count++;
            }
        }
        if (count < 16) return -2;
        double na = saa - sa * sa / count;
        double nb = sbb - sb * sb / count;
        double denom = Math.sqrt(na * nb);
        if (denom <= 1e-6) return -2;
        return (sab - sa * sb / count) / denom;
    }

    /** Load a TIFF as a single-band float grayscale array, or {@code null} on failure. */
    private static float[][] loadGray(File file) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                logger.debug("ImageIO could not read {} for pixel-size estimate", file.getName());
                return null;
            }
            Raster raster = img.getRaster();
            int w = raster.getWidth();
            int h = raster.getHeight();
            int bands = raster.getNumBands();
            float[][] out = new float[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (bands == 1) {
                        out[y][x] = raster.getSampleFloat(x, y, 0);
                    } else {
                        // Average bands for color tiles.
                        double s = 0;
                        for (int bnd = 0; bnd < bands; bnd++) s += raster.getSampleFloat(x, y, bnd);
                        out[y][x] = (float) (s / bands);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            logger.debug("Failed to load {} for pixel-size estimate: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    /** Average-pool a grayscale array by 2x in each dimension. */
    private static float[][] downsample2(float[][] src) {
        int h = src.length / 2;
        int w = src[0].length / 2;
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            int sy = y * 2;
            for (int x = 0; x < w; x++) {
                int sx = x * 2;
                out[y][x] = 0.25f * (src[sy][sx] + src[sy][sx + 1] + src[sy + 1][sx] + src[sy + 1][sx + 1]);
            }
        }
        return out;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int m = sorted.size() / 2;
        return (sorted.size() % 2 == 1) ? sorted.get(m) : 0.5 * (sorted.get(m - 1) + sorted.get(m));
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
     * Position label for a single-plane TIFF-series tile: the name of the
     * per-position subfolder that contains it (e.g. {@code Pos-1-000_000}),
     * which matches the {@code Summary.StagePositions} "Label".
     */
    private static String parentFolderLabel(Path tif) {
        Path parent = tif.getParent();
        return (parent != null && parent.getFileName() != null)
                ? parent.getFileName().toString()
                : null;
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
