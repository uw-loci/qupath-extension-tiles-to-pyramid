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
import qupath.ext.basicstitching.assembly.direct.TileReaderPool;
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
 * <p><b>Channels.</b> A single-channel acquisition stitches into one output named
 * after the selected folder. A multi-channel one emits <em>one tile per channel</em>,
 * each carrying the page it lives on and a sub-folder name taken from
 * {@code Summary.ChNames}, so the workflow's existing per-sub-folder grouping stitches
 * each channel and the channel merger combines them. Splitting only happens when the
 * file's page count equals the channel count; a z-stack or time series interleaves
 * those axes into the same pages, so those read the first page and log why. The
 * {@code matchingString} argument is unused (MicroManager folders are not multi-angle).
 *
 * <p><b>Registration limit.</b> Seam measurement identifies the same grid position
 * across channels by finding the same file name in sibling sub-folders, which
 * MicroManager's one-file-per-position layout does not provide. Seams are therefore
 * always measured on the first channel, and the dialog's Align-on choice has no effect
 * for MicroManager input.
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

            // One mapping per CHANNEL, not per file. MicroManager packs a position's
            // channels into consecutive pages of one TIFF, so a single mapping per file
            // reads page 0 and silently discards every other channel -- which is what a
            // 4-channel acquisition used to stitch down to.
            List<ChannelPage> pages = channelPages(tif, pm, filename);
            for (ChannelPage page : pages) {
                mappings.add(new TileMapping(tif.toFile(), region, page.subdir(subdirName), page.ifd));
            }
            logger.debug(
                    "Mapped {} at stage ({}, {}) um -> pixel ({}, {}) as {} channel(s)",
                    filename,
                    posUm[0],
                    posUm[1],
                    x,
                    y,
                    pages.size());
        }

        logger.info("Total tiles mapped from MicroManager metadata: {}", mappings.size());
        return mappings;
    }

    /**
     * One page of a tile file, and the sub-folder name its output should carry.
     *
     * @param ifd page index within the file, as {@code javax.imageio} counts them
     * @param channelName channel name from {@code Summary.ChNames}, or null when the
     *     file holds a single plane and the acquisition has no channel to name
     */
    private record ChannelPage(int ifd, String channelName) {
        /**
         * Single-channel acquisitions keep stitching into one output named after the
         * folder, exactly as before. Multi-channel ones split per channel, because the
         * workflow groups by this name and merges the results.
         */
        String subdir(String folderName) {
            return channelName == null ? folderName : channelName;
        }
    }

    /**
     * Work out which pages of {@code tif} to stitch, and what to call each one.
     *
     * <p>MicroManager writes a position's planes as consecutive pages of one TIFF. With
     * a single channel that is one page and nothing changes. With several it is one page
     * per channel, and each has to become its own tile so the per-channel stitches can be
     * merged afterwards.
     *
     * <p>Deliberately conservative: it only splits when the page count is exactly the
     * channel count. A z-stack or time series interleaves those axes into the same pages,
     * and guessing the order would silently mis-assign planes -- so those fall back to
     * one page with a warning that names what was seen, rather than producing a
     * confidently wrong mosaic.
     */
    private static List<ChannelPage> channelPages(Path tif, ParsedMetadata pm, String filename) {
        List<String> names = pm.channelNames;
        int nChannels = names.isEmpty() ? pm.nChannels : names.size();
        if (nChannels <= 1) {
            return List.of(new ChannelPage(0, null));
        }

        int pages;
        try {
            pages = TileReaderPool.countPages(tif.toFile());
        } catch (IOException | RuntimeException e) {
            // A reader that cannot count pages must cost one channel, not the whole run.
            logger.warn("Could not count pages in {} ({}) -- reading its first page only", filename, e.toString());
            return List.of(new ChannelPage(0, null));
        }

        if (pages != nChannels) {
            logger.warn(
                    "{} has {} page(s) but the acquisition reports {} channel(s)"
                            + " ({} z-slice(s), {} timepoint(s)); stitching its first page only."
                            + " Per-channel stitching supports one plane per channel.",
                    filename,
                    pages,
                    nChannels,
                    pm.nSlices,
                    pm.nFrames);
            return List.of(new ChannelPage(0, null));
        }

        List<ChannelPage> out = new ArrayList<>(nChannels);
        for (int c = 0; c < nChannels; c++) {
            String name =
                    c < names.size() && names.get(c) != null && !names.get(c).isBlank() ? names.get(c) : "channel_" + c;
            out.add(new ChannelPage(c, name));
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
     * Scan a folder (recursively) for MicroManager metadata and return the
     * first usable {@code PixelSizeUm} found in any per-tile block (flat-MMStack
     * {@code FrameKey-*} or single-plane {@code Metadata-*}). Used by the dialog
     * to auto-fill the pixel-size field before stitching runs.
     *
     * @param folder directory to scan
     * @return detected pixel size in microns ({@code > 0}), or {@code null}
     *         if no metadata reports a usable value
     */
    /**
     * How many channels a MicroManager acquisition in {@code folder} will stitch to.
     *
     * <p>For the dialog, which needs to know whether to offer channel merging before
     * anything has been stitched. Reads {@code Summary} from the first sidecar it finds
     * and stops -- every sidecar in one acquisition carries the same Summary, and this
     * runs on every keystroke in the folder field, so it must not walk the whole set.
     *
     * @return the channel count, or 0 when the folder holds no readable MicroManager
     *     metadata or the acquisition has a single channel (nothing to merge)
     */
    public static int countChannels(File folder) {
        if (folder == null || !folder.isDirectory()) return 0;
        Path rootdir = folder.toPath().toAbsolutePath().normalize();
        if (rootdir.getParent() == null) return 0;
        for (Path p : findMetadataFiles(rootdir)) {
            try (Reader reader = Files.newBufferedReader(p)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root == null) continue;
                JsonObject summary = optObject(root, "Summary");
                if (summary == null) continue;
                JsonArray names = optArray(summary, "ChNames");
                int n = optInt(summary, "Channels", names == null ? 0 : names.size());
                return n > 1 ? n : 0;
            } catch (Exception e) {
                logger.debug("Could not read channel count from {}: {}", p, e.toString());
            }
        }
        return 0;
    }

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
