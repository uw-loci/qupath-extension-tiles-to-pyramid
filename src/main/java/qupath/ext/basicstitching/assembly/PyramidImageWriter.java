package qupath.ext.basicstitching.assembly;

import com.bc.zarr.Compressor;
import com.bc.zarr.CompressorFactory;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.images.writers.ome.zarr.OMEZarrWriter;

/**
 * Writes an assembled SparseImageServer to disk as either:
 * - Pyramidal OME-TIFF file (traditional format)
 * - OME-ZARR directory structure (cloud-native format)
 */
public class PyramidImageWriter {
    private static final Logger logger = LoggerFactory.getLogger(PyramidImageWriter.class);

    /**
     * Retry policy for the OME-TIFF write when Windows reports the temp file
     * is held by another process. BioFormats' two-phase OME-TIFF write reopens
     * the file in {@code PyramidOMETiffWriter.close()} to patch in IFD offsets
     * and the OME-XML footer; if a real-time antivirus, Windows Search
     * indexer, or Explorer preview is scanning the just-flushed file at that
     * moment, the reopen throws {@code FileNotFoundException}: "The process
     * cannot access the file because it is being used by another process."
     * Total backoff sums to ~50s -- enough to outlast typical AV scan windows
     * on multi-GB files without trapping users in indefinite retries.
     */
    private static final int OMETIFF_MAX_ATTEMPTS = 3;

    private static final long[] OMETIFF_RETRY_BACKOFF_MS = {5_000L, 15_000L, 30_000L};

    /**
     * Write the server using the specified output format.
     * Delegates to format-specific methods based on outputFormat parameter.
     *
     * @param server The assembled image server
     * @param outputPath Folder for output
     * @param filename Filename (no extension - will be added based on format)
     * @param compressionType Compression type for TIFF (e.g. "LZW") or ZARR algorithm (e.g. "zstd")
     * @param baseDownsample Downsample factor (typically 1)
     * @param outputFormat Output format (OME_TIFF or OME_ZARR)
     * @return Absolute path to output file/directory, or null on failure
     */
    public static String write(
            ImageServer<BufferedImage> server,
            String outputPath,
            String filename,
            String compressionType,
            double baseDownsample,
            StitchingConfig.OutputFormat outputFormat) {
        return write(server, outputPath, filename, compressionType, baseDownsample, outputFormat, null);
    }

    /**
     * Write the server using the specified output format with progress callback.
     *
     * @param server The assembled image server
     * @param outputPath Folder for output
     * @param filename Filename (no extension - will be added based on format)
     * @param compressionType Compression type
     * @param baseDownsample Downsample factor (typically 1)
     * @param outputFormat Output format (OME_TIFF or OME_ZARR)
     * @param progressCallback Optional callback for progress updates (0.0 to 1.0), primarily for ZARR
     * @return Absolute path to output file/directory, or null on failure
     */
    public static String write(
            ImageServer<BufferedImage> server,
            String outputPath,
            String filename,
            String compressionType,
            double baseDownsample,
            StitchingConfig.OutputFormat outputFormat,
            Consumer<Double> progressCallback) {
        if (outputFormat.stitchAsZarr()) {
            return writeOMEZARR(server, outputPath, filename, compressionType, baseDownsample, progressCallback);
        } else if (outputFormat == StitchingConfig.OutputFormat.OME_TIFF) {
            return writeOMETIFF(server, outputPath, filename, compressionType, baseDownsample);
        } else {
            logger.error("Unsupported output format: {}", outputFormat);
            return null;
        }
    }

    /**
     * Write the server as a pyramidal OME-TIFF using the specified options.
     * Maintained for backward compatibility - defaults to OME-TIFF format.
     *
     * @param server The assembled image server
     * @param outputPath Folder for output
     * @param filename Filename (no extension)
     * @param compressionType Compression type (QuPath enum or String, e.g. "LZW")
     * @param baseDownsample Downsample factor (typically 1)
     * @return Absolute path to output file, or null on failure
     * @deprecated Use write() method with explicit outputFormat parameter
     */
    @Deprecated
    public static String write(
            ImageServer<BufferedImage> server,
            String outputPath,
            String filename,
            String compressionType,
            double baseDownsample) {
        return write(
                server,
                outputPath,
                filename,
                compressionType,
                baseDownsample,
                StitchingConfig.OutputFormat.OME_TIFF,
                null);
    }

    /**
     * Write as pyramidal OME-TIFF (original implementation).
     *
     * @param server The assembled image server
     * @param outputPath Folder for output
     * @param filename Filename (no extension)
     * @param compressionType Compression type (e.g. "LZW", "JPEG")
     * @param baseDownsample Downsample factor
     * @return Absolute path to output TIFF file, or null on failure
     */
    private static String writeOMETIFF(
            ImageServer<BufferedImage> server,
            String outputPath,
            String filename,
            String compressionType,
            double baseDownsample) {
        // Determine the final output path (unique, won't collide with existing files)
        Path outFile = (baseDownsample == 1)
                ? Paths.get(outputPath).resolve(filename + ".ome.tif")
                : Paths.get(outputPath).resolve(filename + "_" + (int) baseDownsample + "x_downsample.ome.tif");
        String finalOutput = UtilityFunctions.getUniqueFilePath(outFile.toString());

        // Write to a temp file first, then rename on success.
        // This prevents destroying an existing good file if stitching fails partway through
        // (e.g. OOM, disk full) -- the OME writer truncates the file on open.
        // The temp file must end in .ome.tif for Bio-Formats to recognize the format.
        String tempOutput = finalOutput.replace(".ome.tif", ".writing.ome.tif");

        // Concurrent OME-TIFF writes were serialized JVM-wide from 2026-04-12
        // through 2026-05-12 because a multi-angle PPM acquisition that ran
        // two stitches in parallel produced a "neither valid JP2 file nor
        // valid JPEG 2000 codestream" decode error on the affected outputs.
        // The 2026-05-12 concurrent-write diagnostic
        // (`claude-reports/2026-05-12_concurrent-tiff-write-test.md` and
        // `scripts/test_concurrent_writes.groovy`) ran 64 J2K_LOSSY writes at
        // parallelism 8 across 8 trials with multi-level pixel verification
        // and saw zero corruption, zero NPEs, zero failed opens, so the
        // serializing gate is gone. If the historical decode failure ever
        // returns, BioFormats surfaces it immediately when QuPath opens the
        // file -- no silent failure mode -- and the gate can be reinstated
        // from git history.
        try {
            OMEPyramidWriter.CompressionType comp = UtilityFunctions.getCompressionType(compressionType);

            int imgW = server.getWidth();
            int imgH = server.getHeight();
            int tileSize = 512;
            int estTiles = (int) Math.ceil((double) imgW / tileSize) * (int) Math.ceil((double) imgH / tileSize);
            logger.info(
                    "Writing pyramid OME-TIFF: {} (compression={}, tileSize={}, downsample={})",
                    finalOutput,
                    comp,
                    tileSize,
                    baseDownsample);
            logger.info(
                    "Image dimensions: {}x{} pixels, ~{} tiles at level 0, server type: {}",
                    imgW,
                    imgH,
                    estTiles,
                    server.getServerType());
            logger.debug("Using temp file during write: {}", tempOutput);

            // Precompute the downsample list so both the pyramidalized source
            // server AND the OME writer see *identical* levels. If they don't
            // match, OMEPyramidWriter$OMEPyramidSeries.writeSeries crashes with
            // AIOOBE at `server.getMetadata().getLevel(level)` when the writer's
            // level index exceeds the server's level count. ImageServers.pyramidalize
            // with no args builds levels by multiplying downsamples by 4.0 each
            // step, while scaledDownsampling(1, 2.0) would have built them by 2.0 --
            // the mismatch produced ArrayIndexOutOfBoundsException: Index 4 out of
            // bounds for length 4 on large PPM acquisitions.
            double[] downsamples = computePyramidDownsamples(imgW, imgH, baseDownsample, tileSize);
            logger.info(
                    "Pyramid levels: {} (downsamples={})", downsamples.length, java.util.Arrays.toString(downsamples));
            // Pyramidalize with a preferred tile size of 1024 (different from
            // the writer's 512). Why: OMEPyramidWriter has an optimization
            // branch (lines ~740-758) that uses the source server's native
            // TileRequestManager output directly when ALL of these match
            // simultaneously: downsample, x/y origin, level width, level
            // height, and BOTH preferred tile dimensions. On PyramidGenerating
            // (pyramidalize-wrapped) servers, that branch produces tile counts
            // that disagree with the per-level dimensions stored in the
            // TiffWriter at any level whose width or height is not a clean
            // multiple of the tile size (very common for stitched mosaics).
            // The TiffWriter then rejects overflow tiles with
            // FormatException: X:1024 must be < 854 or similar, and the upper
            // pyramid levels of the output .ome.tif end up all-black.
            //
            // Reporting a server preferred tile size of 1024 while the writer
            // is configured with tileSize(512) causes the optimization to be
            // bypassed -- writer.tileWidth != server.preferredTileWidth -- and
            // OMEPyramidWriter falls through to the safe Math.min(w-xx,
            // tileWidth) iteration that never overflows. Pixel reads still
            // serve any requested 512-px tile; only the iteration metadata
            // hint differs.
            ImageServer<BufferedImage> pyramidServer =
                    ImageServers.pyramidalizeTiled(server, 1024, 1024, downsamples);

            long t0 = System.currentTimeMillis();

            // channelsInterleaved() packs all channels into a single BIP plane per
            // tile, which is the right representation for RGB brightfield (where
            // the three "channels" are samples-per-pixel in a single chroma stream).
            // For multi-channel non-RGB content (stitched fluorescence, channel-
            // merged IF), it must NOT be enabled: Bio-Formats will write only the
            // first channel's data as a single-channel stream under most codecs
            // (JPEG-2000 especially), so the reader only sees 1 channel even though
            // the source server reports N. Keep the interleaved path for RGB only.
            OMEPyramidWriter.Builder builder = new OMEPyramidWriter.Builder(pyramidServer)
                    .tileSize(tileSize)
                    .parallelize(true)
                    .compression(comp)
                    .downsamples(downsamples);
            // channelsInterleaved() is only valid for a true RGB raster: 3
            // samples-per-pixel packed BIP. A misclassified non-3-channel
            // image (e.g. a 4-channel fluorescence server that reports
            // isRGB=true) would otherwise have all-but-the-first channel
            // silently dropped by BioFormats' codec layer. Require both
            // conditions explicitly.
            if (pyramidServer.isRGB() && pyramidServer.nChannels() == 3) {
                builder.channelsInterleaved();
            } else if (pyramidServer.isRGB()) {
                logger.warn(
                        "Server reports isRGB=true but nChannels={} (not 3). "
                                + "Skipping channelsInterleaved() to avoid silent channel loss.",
                        pyramidServer.nChannels());
            }

            // Retry the writeSeries call on Windows file-lock failures. The
            // BioFormats close() reopens the temp file to patch metadata,
            // which fails if antivirus or Search indexer is scanning the
            // just-finished multi-GB file. Each attempt rewrites from scratch
            // (BioFormats doesn't expose a partial-recovery API); we clean up
            // the incomplete temp file between attempts.
            //
            // CRITICAL ORDER: rename runs BEFORE pyramidServer.close() so a
            // close-time exception cannot delete a successfully-written
            // pyramid. The BioFormats writer has already released its own
            // file handle by the time writeSeries returns; the source
            // server's close() does not touch the output file.
            try {
                Exception lastException = null;
                for (int attempt = 1; attempt <= OMETIFF_MAX_ATTEMPTS; attempt++) {
                    if (attempt > 1) {
                        long backoff = OMETIFF_RETRY_BACKOFF_MS[attempt - 2];
                        logger.warn(
                                "Retrying OME-TIFF write (attempt {}/{}) after {} ms backoff to outlast file-lock holder",
                                attempt,
                                OMETIFF_MAX_ATTEMPTS,
                                backoff);
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during OME-TIFF write retry backoff", ie);
                        }
                        cleanupTempFile(tempOutput);
                    }
                    try {
                        builder.build().writeSeries(tempOutput);
                        lastException = null;
                        break;
                    } catch (Exception e) {
                        lastException = e;
                        if (!isWindowsFileLockException(e)) {
                            // Not a retryable failure -- rethrow immediately.
                            throw e;
                        }
                        if (attempt >= OMETIFF_MAX_ATTEMPTS) {
                            logger.error(
                                    "OME-TIFF write still blocked by file lock after {} attempts. "
                                            + "Another process (antivirus, Windows Search indexer, Explorer preview, "
                                            + "or a cloud-sync client) is holding {} open during the BioFormats "
                                            + "close()/IFD-patch step. Exclude the SlideImages folder from real-time "
                                            + "scanning to fix.",
                                    OMETIFF_MAX_ATTEMPTS,
                                    tempOutput,
                                    e);
                            throw e;
                        }
                        logger.warn(
                                "OME-TIFF write attempt {}/{} blocked by file lock on '{}': {}. "
                                        + "Most likely cause: real-time antivirus / Windows Search indexer / Explorer "
                                        + "preview scanning the just-flushed file. Retrying after backoff.",
                                attempt,
                                OMETIFF_MAX_ATTEMPTS,
                                tempOutput,
                                e.getMessage());
                    }
                }
                if (lastException != null) {
                    // Defensive: every catch above either breaks the loop on success
                    // or throws on retry exhaustion; this branch should be unreachable.
                    throw lastException;
                }

                // Write succeeded -- rename temp file to final output BEFORE
                // we close the source server. Same retry policy: AV scanners
                // routinely open the just-finished multi-GB file the moment
                // its handle is released, so the rename can hit the same
                // "used by another process" failure as the close-time IFD
                // patch did.
                renameTempToFinalWithRetry(tempOutput, finalOutput);
            } finally {
                // Source-server close is best-effort: at this point either the
                // output is renamed into place (success) or the temp file has
                // already been cleaned up by the catch below, so a close
                // failure cannot corrupt user data. Demote to a warning.
                try {
                    pyramidServer.close();
                } catch (Exception closeEx) {
                    logger.warn(
                            "Source pyramid server close() threw after a successful pyramid write; "
                                    + "output is already on disk at {}: {}",
                            finalOutput,
                            closeEx.getMessage());
                }
            }

            logger.info(
                    "Finished writing pyramid in {}s: {}",
                    String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0),
                    finalOutput);
            return finalOutput;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-TIFF", e);
            cleanupTempFile(tempOutput);
            return null;
        }
    }

    /**
     * True if {@code throwable} (or any cause in its chain) indicates that the
     * OS refused to open a file because another process holds a lock on it.
     * Distinguishes the Windows AV / Search-indexer race during BioFormats'
     * close()-time IFD patch from real I/O failures (disk full, missing
     * directory) so we only retry the former.
     *
     * <p>Windows surfaces this as {@link FileNotFoundException} with the
     * message "The process cannot access the file because it is being used by
     * another process." -- the BioFormats stack wraps it as it propagates up
     * from {@code RandomAccessFile.open0}, so we walk the cause chain. The NIO
     * path also surfaces {@link AccessDeniedException} for the same condition
     * under some Java versions.
     */
    static boolean isWindowsFileLockException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof AccessDeniedException) {
                return true;
            }
            if (cause instanceof FileNotFoundException) {
                String message = cause.getMessage();
                if (message != null
                        && (message.contains("being used by another process")
                                || message.contains("Access is denied"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Compute the list of downsamples for the pyramid.
     *
     * Halves the dimensions each level (scale factor 2.0) and stops once the
     * next level would be smaller than the tile size in either dimension.
     * Always contains at least baseDownsample.
     *
     * Must be used symmetrically for pyramidalize() and OMEPyramidWriter to
     * keep their level counts in sync -- the OME writer indexes into the source
     * server's metadata by writer-level, so any mismatch causes
     * ArrayIndexOutOfBoundsException in OMEPyramidWriter.writeSeries.
     */
    private static double[] computePyramidDownsamples(int imgW, int imgH, double baseDownsample, int tileSize) {
        java.util.List<Double> levels = new java.util.ArrayList<>();
        double d = baseDownsample;
        levels.add(d);
        while (true) {
            double next = d * 2.0;
            // Use Math.ceil so dimensions that would otherwise truncate to
            // exactly tileSize-1 don't drop a pyramid level we could have
            // kept. The downstream writer uses the same ceil-rounding when
            // it allocates rasters, so this keeps the two in sync.
            int nextW = (int) Math.ceil(imgW / next);
            int nextH = (int) Math.ceil(imgH / next);
            if (nextW < tileSize || nextH < tileSize) {
                break;
            }
            levels.add(next);
            d = next;
            // Hard safety cap in case of degenerate inputs
            if (levels.size() >= 16) {
                break;
            }
        }
        double[] out = new double[levels.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = levels.get(i);
        }
        return out;
    }

    /**
     * Write as OME-ZARR directory structure (cloud-native format).
     *
     * @param server The assembled image server
     * @param outputPath Folder for output
     * @param filename Filename (no extension - ".ome.zarr" will be added)
     * @param compressionType Compression algorithm (e.g. "zstd", "lz4", "lz4hc", "blosclz", "zlib")
     * @param baseDownsample Downsample factor
     * @param progressCallback Optional callback for per-tile progress (0.0 to 1.0)
     * @return Absolute path to output ZARR directory, or null on failure
     */
    private static String writeOMEZARR(
            ImageServer<BufferedImage> server,
            String outputPath,
            String filename,
            String compressionType,
            double baseDownsample,
            Consumer<Double> progressCallback) {
        // Determine the final output path (unique, won't collide with existing directories)
        Path outDir = (baseDownsample == 1)
                ? Paths.get(outputPath).resolve(filename + ".ome.zarr")
                : Paths.get(outputPath).resolve(filename + "_" + (int) baseDownsample + "x_downsample.ome.zarr");

        // For ZARR, ensure unique directory path (don't use getUniqueFilePath which adds .ome.tif)
        String finalOutput = outDir.toString();
        int counter = 2;
        while (Files.exists(Paths.get(finalOutput))) {
            String baseFilename = filename.replaceAll("\\.ome\\.zarr$", "");
            if (baseDownsample == 1) {
                finalOutput = Paths.get(outputPath)
                        .resolve(baseFilename + "_" + counter + ".ome.zarr")
                        .toString();
            } else {
                finalOutput = Paths.get(outputPath)
                        .resolve(baseFilename + "_" + (int) baseDownsample + "x_downsample_" + counter + ".ome.zarr")
                        .toString();
            }
            counter++;
        }

        // Write to a temp directory first, then rename on success.
        // This prevents destroying an existing good ZARR if stitching fails partway through.
        String tempOutput = finalOutput.replace(".ome.zarr", ".writing.ome.zarr");

        ImageServer<BufferedImage> pyramidServer = null;
        try {
            // ZARR compression setup - more flexible than TIFF
            Compressor compressor = createZarrCompressor(compressionType);

            logger.info(
                    "Writing pyramid OME-ZARR: {} (compression={}, tileSize=1024, downsample={})",
                    finalOutput,
                    compressionType,
                    baseDownsample);
            logger.debug("Using temp directory during write: {}", tempOutput);

            // Pyramidalize server (in case original was not)
            pyramidServer = ImageServers.pyramidalize(server);

            long t0 = System.currentTimeMillis();

            // Build ZARR writer with configuration
            OMEZarrWriter.Builder builder = new OMEZarrWriter.Builder(pyramidServer)
                    .tileSize(1024, 1024) // ZARR can handle larger chunks efficiently
                    .compression(compressor)
                    .parallelize(Runtime.getRuntime().availableProcessors());

            // Add scaled downsampling if needed
            if (baseDownsample != 1) {
                builder.downsamples(baseDownsample, baseDownsample * 2, baseDownsample * 4, baseDownsample * 8);
            }

            // Note: Progress tracking via onTileWritten() is not available in QuPath 0.6.0-rc4
            // This feature may be available in future QuPath versions
            if (progressCallback != null) {
                logger.debug("Progress callback provided but not supported by current OMEZarrWriter API");
            }

            // CRITICAL ORDER: rename runs BEFORE pyramidServer.close() so a
            // close-time exception cannot delete a successfully-written ZARR.
            // The writer's own resources are released by its writeImage() call;
            // the source server's close() does not touch the output directory.
            // Same Windows AV-lock retry on the rename as the OME-TIFF path --
            // although ZARR rename is a directory move, Windows still serialises
            // it through the file system and AV can hold individual chunk files.
            OMEZarrWriter writer = builder.build(tempOutput);
            writer.writeImage();
            renameTempToFinalWithRetry(tempOutput, finalOutput);

            logger.info(
                    "Finished writing pyramid in {}s: {}",
                    String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0),
                    finalOutput);
            return finalOutput;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-ZARR", e);
            cleanupTempPath(tempOutput);
            return null;
        } finally {
            if (pyramidServer != null) {
                try {
                    pyramidServer.close();
                } catch (Exception closeEx) {
                    logger.warn(
                            "Source pyramid server close() threw after OME-ZARR write attempt: {}",
                            closeEx.getMessage());
                }
            }
        }
    }

    /**
     * Create a ZARR compressor from compression type string.
     * Supports Blosc algorithms: zstd, lz4, lz4hc, blosclz, zlib
     * Also maps common TIFF compression types to ZARR equivalents.
     *
     * @param compressionType Compression algorithm name
     * @return Configured Compressor for JZarr
     */
    public static Compressor createZarrCompressor(String compressionType) {
        if (compressionType == null || compressionType.isEmpty()) {
            compressionType = "zstd"; // Default to zstd (good balance of speed/compression)
        }

        // Map common TIFF compression types to ZARR equivalents
        String algorithm = compressionType.toLowerCase();
        switch (algorithm) {
            case "lzw":
            case "deflate":
            case "zlib":
                algorithm = "zlib";
                break;
            case "uncompressed":
            case "none":
                return CompressorFactory.create("null"); // No compression
            case "jpeg":
            case "j2k":
            case "j2k_lossy":
            case "jpeg-2000":
            case "jpeg-2000-lossy":
                logger.warn("JPEG/J2K compression not supported in ZARR, using zstd instead");
                algorithm = "zstd";
                break;
            case "default":
                algorithm = "zstd";
                break;
            default:
                // Use as-is, assuming it's a valid Blosc algorithm
                // Valid: zstd, lz4, lz4hc, blosclz, zlib
                break;
        }

        try {
            return CompressorFactory.create(
                    "blosc",
                    "cname",
                    algorithm, // Compression algorithm
                    "clevel",
                    5, // Compression level (0-9, 5 is balanced)
                    "shuffle",
                    1 // Byte shuffle (improves compression for scientific data)
                    );
        } catch (Exception e) {
            logger.warn("Failed to create compressor '{}', using default zstd", algorithm, e);
            return CompressorFactory.create("blosc", "cname", "zstd", "clevel", 5, "shuffle", 1);
        }
    }

    /**
     * Rename a completed temp file/directory to its final output path.
     * Uses File.renameTo first (fast, same-filesystem), then falls back to
     * Files.move with REPLACE_EXISTING if renameTo fails.
     *
     * @param tempPath Path to the temp file or directory
     * @param finalPath Desired final path
     * @throws Exception if the rename fails
     */
    private static void renameTempToFinal(String tempPath, String finalPath) throws Exception {
        File tempFile = new File(tempPath);
        File finalFile = new File(finalPath);
        if (!tempFile.exists()) {
            throw new IllegalStateException("Temp output does not exist after write: " + tempPath);
        }
        // Safety: getUniqueFilePath should prevent collisions, but be defensive
        if (finalFile.exists()) {
            logger.warn("Final output already exists (unexpected), removing before rename: {}", finalPath);
            deleteRecursively(finalFile);
        }
        boolean renamed = tempFile.renameTo(finalFile);
        if (!renamed) {
            // Fallback: try Files.move (handles cross-filesystem edge cases)
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        logger.debug("Renamed temp output to final: {}", finalPath);
    }

    /**
     * Same as {@link #renameTempToFinal} but retries on Windows file-lock
     * exceptions. The just-finished multi-GB temp file is a common AV scan
     * target the moment its handle is released, so a rename initiated milliseconds
     * later can hit the same "used by another process" failure that the
     * BioFormats close()-time IFD patch hits. Same retry budget as the write
     * phase: 3 attempts with 5 / 15 / 30 second backoff.
     */
    private static void renameTempToFinalWithRetry(String tempPath, String finalPath) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= OMETIFF_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                long backoff = OMETIFF_RETRY_BACKOFF_MS[attempt - 2];
                logger.warn(
                        "Retrying temp -> final rename (attempt {}/{}) after {} ms backoff: '{}' -> '{}'",
                        attempt,
                        OMETIFF_MAX_ATTEMPTS,
                        backoff,
                        tempPath,
                        finalPath);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during rename retry backoff", ie);
                }
            }
            try {
                renameTempToFinal(tempPath, finalPath);
                return;
            } catch (Exception e) {
                lastException = e;
                if (!isWindowsFileLockException(e)) {
                    // Not a retryable failure -- rethrow immediately so the
                    // caller's cleanup logic runs.
                    throw e;
                }
                if (attempt >= OMETIFF_MAX_ATTEMPTS) {
                    logger.error(
                            "Temp -> final rename still blocked by file lock after {} attempts. "
                                    + "Another process is holding '{}' open during the rename. "
                                    + "Exclude the output folder from real-time scanning to fix.",
                            OMETIFF_MAX_ATTEMPTS,
                            tempPath,
                            e);
                    throw e;
                }
                logger.warn(
                        "Rename attempt {}/{} blocked by file lock on '{}': {}. "
                                + "Most likely cause: antivirus / Search indexer / Explorer preview "
                                + "scanning the just-finished file. Retrying.",
                        attempt,
                        OMETIFF_MAX_ATTEMPTS,
                        tempPath,
                        e.getMessage());
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }

    /**
     * Clean up a temp file on write failure. Logs a warning if the file existed.
     *
     * @param tempPath Path to the temp file to delete
     */
    private static void cleanupTempFile(String tempPath) {
        try {
            File tempFile = new File(tempPath);
            if (tempFile.exists()) {
                if (tempFile.delete()) {
                    logger.warn("Deleted incomplete temp file: {}", tempPath);
                } else {
                    logger.error("Failed to delete incomplete temp file: {}", tempPath);
                }
            }
        } catch (Exception cleanupEx) {
            logger.error("Error cleaning up temp file: {}", tempPath, cleanupEx);
        }
    }

    /**
     * Clean up a temp path (file or directory tree) on write failure.
     * For ZARR output this may be a directory tree that needs recursive deletion.
     *
     * @param tempPath Path to the temp file or directory to delete
     */
    private static void cleanupTempPath(String tempPath) {
        try {
            File tempFile = new File(tempPath);
            if (tempFile.exists()) {
                deleteRecursively(tempFile);
                logger.warn("Deleted incomplete temp output: {}", tempPath);
            }
        } catch (Exception cleanupEx) {
            logger.error("Error cleaning up temp output: {}", tempPath, cleanupEx);
        }
    }

    /**
     * Recursively delete a file or directory tree.
     *
     * @param file File or directory to delete
     */
    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            logger.warn("Could not delete: {}", file.getAbsolutePath());
        }
    }

    /**
     * Estimate total number of tiles across all pyramid levels.
     * Used for progress tracking in ZARR writing.
     *
     * @param server The image server
     * @return Estimated total number of tiles
     */
    private static int estimateTotalTiles(ImageServer<BufferedImage> server) {
        int tileWidth = 1024;
        int tileHeight = 1024;
        int totalTiles = 0;

        for (int level = 0; level < server.nResolutions(); level++) {
            double downsample = server.getDownsampleForResolution(level);
            int levelWidth = (int) (server.getWidth() / downsample);
            int levelHeight = (int) (server.getHeight() / downsample);

            int tilesX = (int) Math.ceil((double) levelWidth / tileWidth);
            int tilesY = (int) Math.ceil((double) levelHeight / tileHeight);

            totalTiles += tilesX * tilesY * server.nChannels() * server.nZSlices() * server.nTimepoints();
        }

        return totalTiles;
    }
}
