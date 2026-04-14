package qupath.ext.basicstitching.assembly;

import com.bc.zarr.Compressor;
import com.bc.zarr.CompressorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.images.writers.ome.zarr.OMEZarrWriter;
import qupath.ext.basicstitching.utilities.UtilityFunctions;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Writes an assembled SparseImageServer to disk as either:
 * - Pyramidal OME-TIFF file (traditional format)
 * - OME-ZARR directory structure (cloud-native format)
 */
public class PyramidImageWriter {
    private static final Logger logger = LoggerFactory.getLogger(PyramidImageWriter.class);

    /**
     * Global gate: only one OME-TIFF pyramid write at a time.
     * BioFormats' TiffWriter has internal state (initialized array, J2K codec)
     * that is not safe for concurrent use across multiple writer instances.
     * Concurrent writes cause NPE at high pyramid levels (downsample=64).
     */
    private static final Semaphore TIFF_WRITE_GATE = new Semaphore(1);

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
    public static String write(ImageServer<BufferedImage> server, String outputPath, String filename,
                               String compressionType, double baseDownsample,
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
    public static String write(ImageServer<BufferedImage> server, String outputPath, String filename,
                               String compressionType, double baseDownsample,
                               StitchingConfig.OutputFormat outputFormat,
                               Consumer<Double> progressCallback) {
        switch (outputFormat) {
            case OME_TIFF:
                return writeOMETIFF(server, outputPath, filename, compressionType, baseDownsample);
            case OME_ZARR:
                return writeOMEZARR(server, outputPath, filename, compressionType, baseDownsample, progressCallback);
            default:
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
    public static String write(ImageServer<BufferedImage> server, String outputPath, String filename,
                               String compressionType, double baseDownsample) {
        return write(server, outputPath, filename, compressionType, baseDownsample,
                    StitchingConfig.OutputFormat.OME_TIFF, null);
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
    private static String writeOMETIFF(ImageServer<BufferedImage> server, String outputPath,
                                       String filename, String compressionType, double baseDownsample) {
        // Determine the final output path (unique, won't collide with existing files)
        Path outFile = (baseDownsample == 1)
                ? Paths.get(outputPath).resolve(filename + ".ome.tif")
                : Paths.get(outputPath).resolve(filename + "_" + (int)baseDownsample + "x_downsample.ome.tif");
        String finalOutput = UtilityFunctions.getUniqueFilePath(outFile.toString());

        // Write to a temp file first, then rename on success.
        // This prevents destroying an existing good file if stitching fails partway through
        // (e.g. OOM, disk full) -- the OME writer truncates the file on open.
        // The temp file must end in .ome.tif for Bio-Formats to recognize the format.
        String tempOutput = finalOutput.replace(".ome.tif", ".writing.ome.tif");

        // Serialize OME-TIFF writes: BioFormats' TiffWriter NPEs when multiple
        // writers are active concurrently (corrupts pyramid level 3+).
        try {
            TIFF_WRITE_GATE.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted waiting for TIFF write gate");
            return null;
        }

        logger.info("Acquired TIFF write gate for: {}", filename);

        try {
            OMEPyramidWriter.CompressionType comp = UtilityFunctions.getCompressionType(compressionType);

            int imgW = server.getWidth();
            int imgH = server.getHeight();
            int tileSize = 512;
            int estTiles = (int) Math.ceil((double) imgW / tileSize) * (int) Math.ceil((double) imgH / tileSize);
            logger.info("Writing pyramid OME-TIFF: {} (compression={}, tileSize={}, downsample={})",
                    finalOutput, comp, tileSize, baseDownsample);
            logger.info("Image dimensions: {}x{} pixels, ~{} tiles at level 0, server type: {}",
                    imgW, imgH, estTiles, server.getServerType());
            logger.debug("Using temp file during write: {}", tempOutput);

            // Pyramidalize server (in case original was not)
            ImageServer<BufferedImage> pyramidServer = ImageServers.pyramidalize(server);

            long t0 = System.currentTimeMillis();

            // scaledDownsampling(baseDownsample, scaleFactor) wants a multiplicative
            // scale between successive pyramid levels (2.0 = halving each step).
            // The builder stops adding levels when the next level would be smaller
            // than the tile size, so we don't have to cap the count ourselves.
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
                    .scaledDownsampling(baseDownsample, 2.0);
            if (pyramidServer.isRGB()) {
                builder.channelsInterleaved();
            }
            builder.build().writeSeries(tempOutput);

            pyramidServer.close();

            // Write succeeded -- rename temp file to final output
            renameTempToFinal(tempOutput, finalOutput);

            logger.info("Finished writing pyramid in {}s: {}",
                    String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0), finalOutput);
            return finalOutput;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-TIFF", e);
            cleanupTempFile(tempOutput);
            return null;
        } finally {
            TIFF_WRITE_GATE.release();
            logger.info("Released TIFF write gate for: {}", filename);
        }
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
    private static String writeOMEZARR(ImageServer<BufferedImage> server, String outputPath,
                                       String filename, String compressionType, double baseDownsample,
                                       Consumer<Double> progressCallback) {
        // Determine the final output path (unique, won't collide with existing directories)
        Path outDir = (baseDownsample == 1)
                ? Paths.get(outputPath).resolve(filename + ".ome.zarr")
                : Paths.get(outputPath).resolve(filename + "_" + (int)baseDownsample + "x_downsample.ome.zarr");

        // For ZARR, ensure unique directory path (don't use getUniqueFilePath which adds .ome.tif)
        String finalOutput = outDir.toString();
        int counter = 2;
        while (Files.exists(Paths.get(finalOutput))) {
            String baseFilename = filename.replaceAll("\\.ome\\.zarr$", "");
            if (baseDownsample == 1) {
                finalOutput = Paths.get(outputPath).resolve(baseFilename + "_" + counter + ".ome.zarr").toString();
            } else {
                finalOutput = Paths.get(outputPath).resolve(baseFilename + "_" + (int)baseDownsample + "x_downsample_" + counter + ".ome.zarr").toString();
            }
            counter++;
        }

        // Write to a temp directory first, then rename on success.
        // This prevents destroying an existing good ZARR if stitching fails partway through.
        String tempOutput = finalOutput.replace(".ome.zarr", ".writing.ome.zarr");

        try {
            // ZARR compression setup - more flexible than TIFF
            Compressor compressor = createZarrCompressor(compressionType);

            logger.info("Writing pyramid OME-ZARR: {} (compression={}, tileSize=1024, downsample={})",
                    finalOutput, compressionType, baseDownsample);
            logger.debug("Using temp directory during write: {}", tempOutput);

            // Pyramidalize server (in case original was not)
            ImageServer<BufferedImage> pyramidServer = ImageServers.pyramidalize(server);

            long t0 = System.currentTimeMillis();

            // Build ZARR writer with configuration
            OMEZarrWriter.Builder builder = new OMEZarrWriter.Builder(pyramidServer)
                    .tileSize(1024, 1024)  // ZARR can handle larger chunks efficiently
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

            OMEZarrWriter writer = builder.build(tempOutput);
            writer.writeImage();
            writer.close();

            pyramidServer.close();

            // Write succeeded -- rename temp directory to final output
            renameTempToFinal(tempOutput, finalOutput);

            logger.info("Finished writing pyramid in {}s: {}",
                    String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0), finalOutput);
            return finalOutput;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-ZARR", e);
            cleanupTempPath(tempOutput);
            return null;
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
            compressionType = "zstd";  // Default to zstd (good balance of speed/compression)
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
                return CompressorFactory.create("null");  // No compression
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
                "cname", algorithm,   // Compression algorithm
                "clevel", 5,          // Compression level (0-9, 5 is balanced)
                "shuffle", 1          // Byte shuffle (improves compression for scientific data)
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
