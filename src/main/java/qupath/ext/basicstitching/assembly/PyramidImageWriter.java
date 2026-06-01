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
import qupath.ext.basicstitching.assembly.direct.DirectTiffOutputWriter;
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
        // Write straight to the final unique path -- NO temp file, NO rename.
        // The previous temp -> final rename was the source of the Windows
        // "being used by another process" failures (an external handle holder
        // racing the rename of a just-finished multi-GB file). DirectTiffOutputWriter
        // also drives Bio-Formats with only the correct Math.min tile loop, so the
        // silent pyramid-corruption bug in QuPath's OMEPyramidWriter cannot occur.
        Path outFile = (baseDownsample == 1)
                ? Paths.get(outputPath).resolve(filename + ".ome.tif")
                : Paths.get(outputPath).resolve(filename + "_" + (int) baseDownsample + "x_downsample.ome.tif");
        String finalOutput = UtilityFunctions.getUniqueFilePath(outFile.toString());

        try {
            OMEPyramidWriter.CompressionType comp = UtilityFunctions.getCompressionType(compressionType);
            DirectTiffOutputWriter.write(server, finalOutput, comp, 512, baseDownsample, null);
            return finalOutput;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-TIFF", e);
            // Direct-to-final means a failed write leaves a partial file at the
            // (fresh, non-colliding) output path; remove it so no half-written
            // pyramid is mistaken for a good image.
            cleanupTempFile(finalOutput);
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
