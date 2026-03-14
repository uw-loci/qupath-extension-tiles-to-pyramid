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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Writes an assembled SparseImageServer to disk as either:
 * - Pyramidal OME-TIFF file (traditional format)
 * - OME-ZARR directory structure (cloud-native format)
 */
public class PyramidImageWriter {
    private static final Logger logger = LoggerFactory.getLogger(PyramidImageWriter.class);

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
        try {
            Path outFile = (baseDownsample == 1)
                    ? Paths.get(outputPath).resolve(filename + ".ome.tif")
                    : Paths.get(outputPath).resolve(filename + "_" + (int)baseDownsample + "x_downsample.ome.tif");
            String output = UtilityFunctions.getUniqueFilePath(outFile.toString());

            OMEPyramidWriter.CompressionType comp = UtilityFunctions.getCompressionType(compressionType);
            logger.info("Writing pyramid OME-TIFF: {} (compression={}, tileSize=512, downsample={})",
                    output, comp, baseDownsample);

            // Pyramidalize server (in case original was not)
            ImageServer<BufferedImage> pyramidServer = ImageServers.pyramidalize(server);

            long t0 = System.currentTimeMillis();

            new OMEPyramidWriter.Builder(pyramidServer)
                    .tileSize(512)
                    .channelsInterleaved()
                    .parallelize(true)
                    .compression(comp)
                    .scaledDownsampling(baseDownsample, 4)
                    .build()
                    .writeSeries(output);

            pyramidServer.close();

            logger.info("Finished writing pyramid in {}s: {}", String.format("%.1f", (System.currentTimeMillis() - t0)/1000.0), output);
            return output;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-TIFF", e);
            return null;
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
        try {
            Path outDir = (baseDownsample == 1)
                    ? Paths.get(outputPath).resolve(filename + ".ome.zarr")
                    : Paths.get(outputPath).resolve(filename + "_" + (int)baseDownsample + "x_downsample.ome.zarr");

            // For ZARR, ensure unique directory path (don't use getUniqueFilePath which adds .ome.tif)
            String output = outDir.toString();
            int counter = 2;
            while (Files.exists(Paths.get(output))) {
                String baseFilename = filename.replaceAll("\\.ome\\.zarr$", "");
                if (baseDownsample == 1) {
                    output = Paths.get(outputPath).resolve(baseFilename + "_" + counter + ".ome.zarr").toString();
                } else {
                    output = Paths.get(outputPath).resolve(baseFilename + "_" + (int)baseDownsample + "x_downsample_" + counter + ".ome.zarr").toString();
                }
                counter++;
            }

            // ZARR compression setup - more flexible than TIFF
            Compressor compressor = createZarrCompressor(compressionType);

            logger.info("Writing pyramid OME-ZARR: {} (compression={}, tileSize=1024, downsample={})",
                    output, compressionType, baseDownsample);

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

            OMEZarrWriter writer = builder.build(output);
            writer.writeImage();
            writer.close();

            pyramidServer.close();

            logger.info("Finished writing pyramid in {}s: {}", String.format("%.1f", (System.currentTimeMillis() - t0)/1000.0), output);
            return output;
        } catch (Exception e) {
            logger.error("Failed to write pyramid OME-ZARR", e);
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
    private static Compressor createZarrCompressor(String compressionType) {
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
            case "jpeg-2000":
            case "jpeg-2000-lossy":
                logger.warn("JPEG compression not directly supported in ZARR, using zstd instead");
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
