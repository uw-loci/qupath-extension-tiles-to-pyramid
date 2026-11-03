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
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.registration.Ncc;
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
 *       in a {@code FrameKey-0-0-0} block. Each OME-TIFF also carries OME-XML
 *       describing every position as a separate <em>series</em>, but that is not
 *       what the read path uses: tiles are read with {@code javax.imageio},
 *       which addresses PAGES, and the pages of a per-position file are its
 *       CHANNELS.</li>
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
 * <p><b>Channels, z-slices and time points.</b> Every plane is placed by its own channel,
 * slice and time point, read by {@link MicroManagerPlanes} (the page's Micro-Manager tag, the
 * sidecar's frame keys, or a single-plane file's name or metadata block). A single-channel
 * acquisition stitches into one output named after the selected folder; a multi-channel one into
 * one output per channel, named from {@code Summary.ChNames}, which the dialog's merge combines.
 * Each output carries every z-slice and time point it was given. A position file continued past
 * 4 GB ({@code <label>_1.ome.tif}) adds its planes to that position, not a tile of its own. A page
 * whose plane cannot be established is never guessed: an {@code _MMStack_} position falls back to
 * its first page (which is always the first channel, slice and time point), a single-plane file
 * is left out, and either is reported through {@link #warnings()} for the result dialog. The
 * {@code matchingString} argument is unused (MicroManager folders are not multi-angle).
 *
 * <p><b>Registration.</b> Each channel output is a channel to register on. Every position is
 * measured on the same plane of that channel, in both layouts: the middle z-slice of the first
 * time point (its page in an OME-TIFF per position, its file in a single-plane series), and every
 * plane of a position receives that position's correction ({@code TileRegistrationStep}).
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

        /** Summary.ChNames, in acquisition order; empty when the acquisition names none. */
        final List<String> channelNames = new ArrayList<>();

        /** Summary.Channels / Slices / Frames, for deciding whether pages are channels. */
        int nChannels = 1;

        int nSlices = 1;

        int nFrames = 1;
        /** Channel, slice and frame from single-plane files' {@code Metadata-} or {@code Coords-} blocks. */
        final Map<Path, MicroManagerPlanes.Coord> blockCoords = new HashMap<>();
        /** First usable PixelSizeUm found, or {@code null}. */
        Double detectedPixelSizeUm = null;
        /** Whether any flat-MMStack (multi-series) tile was seen. */
        boolean sawMultiSeries = false;
    }

    /** What the last {@link #prepareStitching} could not place as asked, for the result dialog. */
    private final List<String> warnings = new ArrayList<>();

    @Override
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** The channel outputs of the last {@link #prepareStitching}, in acquisition order; empty for one channel. */
    private List<String> groupOrder = List.of();

    @Override
    public List<String> groupOrder() {
        return groupOrder;
    }

    /** The folder the last {@link #prepareStitching} read; null before one ran. */
    private Path lastFolder;

    /** Writes {@code <output stem>.mm-metadata.json} ({@link MicroManagerOutputMetadata}). */
    @Override
    public Path writeOutputMetadata(Path output, String group) {
        if (lastFolder == null) {
            return null;
        }
        int channel = Math.max(0, groupOrder.indexOf(group));
        return MicroManagerOutputMetadata.write(output, lastFolder, List.of(channel));
    }

    @Override
    public List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString) {
        logger.info("Preparing stitching using MicroManager metadata for folder: {}", folderPath);
        warnings.clear();
        groupOrder = List.of();
        List<TileMapping> mappings = new ArrayList<>();
        Path rootdir = Paths.get(folderPath).toAbsolutePath().normalize();
        lastFolder = null;
        if (!Files.isDirectory(rootdir)) {
            logger.warn("Folder does not exist or is not a directory: {}", folderPath);
            return mappings;
        }
        lastFolder = rootdir;

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

        String folderName =
                rootdir.getFileName() != null ? rootdir.getFileName().toString() : "tiles";
        int nChannels = pm.channelNames.isEmpty() ? pm.nChannels : pm.channelNames.size();
        MicroManagerPlanes.Dims dims = new MicroManagerPlanes.Dims(nChannels, pm.nSlices, pm.nFrames);
        // One plane per file and no axis to place it on: every tile is page 0, as it always was.
        boolean onePlane = nChannels <= 1 && pm.nSlices <= 1 && pm.nFrames <= 1;
        Map<Path, List<Path>> stackFiles =
                MicroManagerPlanes.stackFilesByPosition(tiffFiles, pm.labelToSeriesIndex.keySet());
        java.util.Set<Path> continuations = new java.util.HashSet<>();
        stackFiles.values().forEach(files -> continuations.addAll(files.subList(1, files.size())));
        if (!continuations.isEmpty()) {
            logger.info(
                    "{} file(s) continue a position past 4 GB; their planes join that position", continuations.size());
        }

        List<String> firstPageOnly = new ArrayList<>();
        List<String> unplaced = new ArrayList<>();
        List<PendingTile> pending = new ArrayList<>();
        int processed = 0;
        int totalTiles = tiffFiles.size();
        for (Path tif : tiffFiles) {
            if (continuations.contains(tif)) continue;
            String filename = tif.getFileName().toString();
            // The label is the MMStack token in the filename, or (single-plane
            // layout) the per-position subfolder name -- both match the
            // Summary.StagePositions "Label".
            String label = extractMMStackLabel(filename);
            if (label == null) {
                label = parentFolderLabel(tif);
            }

            double[] posUm;
            TileRecord record = pm.pathToRecord.get(tif);
            if (record != null) {
                posUm = new double[] {record.xUm, record.yUm};
            } else {
                posUm = label != null ? pm.labelToPosUm.get(label) : null;
                if (posUm == null) {
                    logger.warn("No MicroManager position found for {} -- skipping", filename);
                    continue;
                }
                logger.debug("Tile {} resolved via Summary.StagePositions label '{}'", filename, label);
            }

            Map<String, Integer> tileDims = UtilityFunctions.getTiffDimensions(tif.toFile());
            processed++;
            if (processed % 500 == 0 || processed == totalTiles) {
                logger.info("Tile dimension progress: {}/{} files processed", processed, totalTiles);
            }
            if (tileDims == null) {
                logger.warn("Could not read dimensions for {} -- skipping", filename);
                continue;
            }

            double rawX = posUm[0];
            double rawY = posUm[1];
            if (flipX) rawX = -rawX;
            if (flipY) rawY = -rawY;
            // Positions are in FULL-RESOLUTION pixels, deliberately not divided by
            // baseDownsample. The tile's width and height below come from the file and are
            // full resolution too, so scaling only the position while leaving the size alone
            // shrinks the grid under fixed-size tiles: at downsample 2 a 10% overlap became
            // 55% and the mosaic was built wrong. Downsampling belongs to the writer, which
            // applies config.baseDownsample when it writes the pyramid.
            int x = (int) Math.round(rawX / effectivePixelSize);
            int y = (int) Math.round(rawY / effectivePixelSize);
            int w = tileDims.get("width");
            int h = tileDims.get("height");

            List<MicroManagerPlanes.Plane> planes =
                    planesOf(tif, stackFiles, pm, dims, onePlane, firstPageOnly, unplaced);
            for (MicroManagerPlanes.Plane plane : planes) {
                pending.add(new PendingTile(plane, x, y, w, h));
            }
            logger.debug(
                    "Mapped {} at stage ({}, {}) um -> pixel ({}, {}) as {} plane(s)",
                    filename,
                    posUm[0],
                    posUm[1],
                    x,
                    y,
                    planes.size());
        }

        int channelsSeen =
                pending.stream().mapToInt(t -> t.plane.coord().c() + 1).max().orElse(0);
        List<String> channelNames = channelGroupNames(pm.channelNames, Math.max(nChannels, channelsSeen));
        if (channelNames.size() > 1) {
            groupOrder = List.copyOf(channelNames);
        }
        for (PendingTile t : pending) {
            MicroManagerPlanes.Coord k = t.plane.coord();
            String subdir = channelNames.size() > 1 ? channelNames.get(k.c()) : folderName;
            ImageRegion region = ImageRegion.createInstance(t.x, t.y, t.w, t.h, k.z(), k.t());
            mappings.add(new TileMapping(t.plane.file().toFile(), region, subdir, t.plane.page()));
        }

        if (!firstPageOnly.isEmpty()) {
            warn(firstPageOnly.size() + " position file(s) gave no way to tell which channel, z-slice and time point"
                    + " each page holds, so only their first page (first channel, slice and time point) was"
                    + " stitched. First: " + firstPageOnly.get(0));
        }
        if (!unplaced.isEmpty()) {
            warn(unplaced.size() + " file(s) were left out because their channel, z-slice and time point could not"
                    + " be read from the file name, metadata.txt or the file itself. First: " + unplaced.get(0));
        }
        logger.info(
                "Total tiles mapped from MicroManager metadata: {} ({} channel(s), {} z-slice(s), {} time point(s))",
                mappings.size(),
                Math.max(1, channelNames.size()),
                pending.stream().mapToInt(t -> t.plane.coord().z() + 1).max().orElse(0),
                pending.stream().mapToInt(t -> t.plane.coord().t() + 1).max().orElse(0));
        return mappings;
    }

    /** A plane at its tile's place, before its channel's output name is known. */
    private record PendingTile(MicroManagerPlanes.Plane plane, int x, int y, int w, int h) {}

    private void warn(String message) {
        logger.warn(message);
        warnings.add(message);
    }

    /**
     * The planes of one file, or of one {@code _MMStack_} position with its continuation files.
     *
     * @param firstPageOnly collects positions read from their first page only, with the reason
     * @param unplaced collects files left out, with the reason
     */
    private static List<MicroManagerPlanes.Plane> planesOf(
            Path tif,
            Map<Path, List<Path>> stackFiles,
            ParsedMetadata pm,
            MicroManagerPlanes.Dims dims,
            boolean onePlane,
            List<String> firstPageOnly,
            List<String> unplaced) {
        if (onePlane) {
            return List.of(new MicroManagerPlanes.Plane(tif, 0, MicroManagerPlanes.Coord.ORIGIN));
        }
        String filename = tif.getFileName().toString();
        List<Path> stack = stackFiles.get(tif);
        if (stack == null) {
            MicroManagerPlanes.Coord k = MicroManagerPlanes.singlePlaneCoord(filename);
            if (k == null) k = pm.blockCoords.get(tif);
            if (k != null) {
                return List.of(new MicroManagerPlanes.Plane(tif, 0, k));
            }
            // Not a single-plane name and no block: the file may still carry the tag.
            stack = List.of(tif);
        }
        Path sidecar = MicroManagerPlanes.sidecarOf(tif);
        try {
            return MicroManagerPlanes.stackPlanes(
                    stack, () -> Files.isRegularFile(sidecar) ? MicroManagerPlanes.readJson(sidecar) : null, dims);
        } catch (IOException | RuntimeException e) {
            String reason = filename + ": " + e.getMessage();
            if (stackFiles.containsKey(tif)) {
                logger.warn("{}; stitching its first page only", reason);
                firstPageOnly.add(reason);
                return List.of(new MicroManagerPlanes.Plane(tif, 0, MicroManagerPlanes.Coord.ORIGIN));
            }
            logger.warn("{}; left out", reason);
            unplaced.add(reason);
            return List.of();
        }
    }

    /**
     * The output name of each channel: its {@code Summary.ChNames} entry, else {@code channel_<c>},
     * made safe as a file name and unique (two channels named alike would otherwise stitch into one).
     */
    static List<String> channelGroupNames(List<String> chNames, int channels) {
        List<String> out = new ArrayList<>(channels);
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int c = 0; c < channels; c++) {
            String name = c < chNames.size()
                            && chNames.get(c) != null
                            && !chNames.get(c).isBlank()
                    ? chNames.get(c).trim().replaceAll("[\\\\/:*?\"<>|]", "_")
                    : "channel_" + c;
            if (!used.add(name.toLowerCase(java.util.Locale.ROOT))) {
                name = name + "_" + c;
                used.add(name.toLowerCase(java.util.Locale.ROOT));
            }
            out.add(name);
        }
        return out;
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

                // Cache the acquisition's channel/z/t shape (once). Every sidecar in one
                // acquisition carries the same Summary, so the first readable one wins.
                JsonObject summaryDims = optObject(root, "Summary");
                if (summaryDims != null && pm.channelNames.isEmpty()) {
                    JsonArray chNames = optArray(summaryDims, "ChNames");
                    if (chNames != null) {
                        for (JsonElement el : chNames) {
                            pm.channelNames.add(el.isJsonPrimitive() ? el.getAsString() : null);
                        }
                    }
                    pm.nChannels = optInt(summaryDims, "Channels", pm.channelNames.size());
                    pm.nSlices = optInt(summaryDims, "Slices", 1);
                    pm.nFrames = optInt(summaryDims, "Frames", 1);
                    if (pm.nChannels > 1) {
                        logger.info(
                                "MicroManager acquisition reports {} channel(s) {}, {} z-slice(s), {} timepoint(s)",
                                pm.nChannels,
                                pm.channelNames,
                                pm.nSlices,
                                pm.nFrames);
                    }
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

                    if (key.startsWith("Coords-") || key.startsWith("Metadata-")) {
                        MicroManagerPlanes.Coord k = MicroManagerPlanes.fromBlock(block);
                        if (k != null) {
                            String rel = key.substring(key.indexOf('-') + 1);
                            pm.blockCoords.putIfAbsent(resolveTilePath(rootdir, metaParent, rel), k);
                        }
                    }

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
    static List<Path> findMetadataFiles(Path rootdir) {
        return findFiles(rootdir, name -> name.equals("metadata.txt") || name.endsWith("_metadata.txt"));
    }

    /** Recursively find {@code *.tif*} files under {@code rootdir}, skipping ':' ADS artifacts. */
    private static List<Path> findTiffFiles(Path rootdir) {
        return findFiles(rootdir, name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".tif") || lower.endsWith(".tiff");
        });
    }

    /**
     * Files under {@code rootdir}, at most {@link #MAX_SCAN_DEPTH} deep, whose name the predicate
     * accepts. Names containing ':' are skipped: those are NTFS alternate-data-stream artifacts
     * that WSL surfaces as phantom files.
     */
    private static List<Path> findFiles(Path rootdir, java.util.function.Predicate<String> nameMatches) {
        return FileScanner.find(rootdir, MAX_SCAN_DEPTH, p -> {
            String name = p.getFileName().toString();
            return !name.contains(":") && nameMatches.test(name);
        });
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
     * The {@code Summary} of the first sidecar under {@code folder} that has one. Every sidecar in
     * one acquisition carries the same Summary, and the dialog calls this on every keystroke in the
     * folder field, so it stops at the first.
     *
     * @return the Summary, or null when the folder holds no readable MicroManager metadata
     */
    private static JsonObject firstSummary(File folder) {
        if (folder == null || !folder.isDirectory()) return null;
        Path rootdir = folder.toPath().toAbsolutePath().normalize();
        if (rootdir.getParent() == null) return null;
        for (Path p : findMetadataFiles(rootdir)) {
            try (Reader reader = Files.newBufferedReader(p)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                JsonObject summary = root == null ? null : optObject(root, "Summary");
                if (summary != null) return summary;
            } catch (Exception e) {
                logger.debug("Could not read the Summary of {}: {}", p, e.toString());
            }
        }
        return null;
    }

    /**
     * How many channels a MicroManager acquisition in {@code folder} will stitch to.
     *
     * <p>For the dialog, which needs to know whether to offer channel merging before anything has
     * been stitched.
     *
     * @return the channel count, or 0 when the folder holds no readable MicroManager
     *     metadata or the acquisition has a single channel (nothing to merge)
     */
    public static int countChannels(File folder) {
        JsonObject summary = firstSummary(folder);
        if (summary == null) return 0;
        JsonArray names = optArray(summary, "ChNames");
        int n = optInt(summary, "Channels", names == null ? 0 : names.size());
        return n > 1 ? n : 0;
    }

    /**
     * The names the channels of a MicroManager acquisition in {@code folder} stitch under, in
     * acquisition order: the per-channel outputs, and the registration reference choices.
     *
     * @return the names; empty for a single-channel acquisition or a folder without metadata
     */
    public static List<String> channelOutputNames(File folder) {
        int n = countChannels(folder);
        if (n < 2) return List.of();
        List<String> names = new ArrayList<>();
        JsonArray chNames = optArray(firstSummary(folder), "ChNames");
        if (chNames != null) {
            for (JsonElement el : chNames) {
                names.add(el.isJsonPrimitive() ? el.getAsString() : null);
            }
        }
        return channelGroupNames(names, Math.max(n, names.size()));
    }

    /**
     * The z-step of a MicroManager acquisition in {@code folder}: {@code Summary.z-step_um} from the
     * first sidecar that has a Summary.
     *
     * @return the step in microns, or null when the folder holds no sidecar or the step is not
     *     positive (Micro-Manager writes 0 for an acquisition without z-slices)
     */
    public static Double detectZStepUm(File folder) {
        JsonObject summary = firstSummary(folder);
        Double step = summary == null ? null : optDouble(summary, "z-step_um");
        return step != null && step > 0 ? step : null;
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
        if (rootdir.getParent() == null) {
            // A filesystem root (C:\, /). Tiles never live directly at a drive root, and scanning
            // one means walking every top-level folder on the disk before the dialog can open.
            logger.info("Not scanning {} for MicroManager metadata: choose the tile folder itself", rootdir);
            return null;
        }
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
     * neighboring tiles the recorded stage step (um) is divided by the pixel
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

        // Build a flat list of (path, xUm, yUm) for neighbor search.
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

        // Horizontal neighbors: same row (|dy| < tolY), adjacent in X.
        collectAxisEstimates(paths, xy, true, tolY, maxPairsPerAxis, estimates, peaks);
        // Vertical neighbors: same column (|dx| < tolX), adjacent in Y.
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
        int along = horizontal ? 0 : 1; // axis that varies between neighbors
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
     * cross-correlation of their overlapping content. For horizontal neighbors
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
     *
     * <p>Delegates to {@link Ncc#atShift}, which shares this implementation with tile
     * registration. Kept as a private method so the estimator below reads unchanged.
     */
    private static double nccAtShift(float[][] a, float[][] b, int ox, int oy, int w, int h) {
        return Ncc.atShift(a, b, ox, oy, w, h);
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
    /** Delegates to {@link Ncc#downsample2}; shared with tile registration. */
    private static float[][] downsample2(float[][] src) {
        return Ncc.downsample2(src);
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

    /** Integer field, or {@code fallback} when absent, null, or not a number. */
    private static int optInt(JsonObject parent, String key, int fallback) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) return fallback;
        try {
            return e.getAsInt();
        } catch (Exception ex) {
            return fallback;
        }
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
