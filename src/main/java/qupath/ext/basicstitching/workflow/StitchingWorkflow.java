package qupath.ext.basicstitching.workflow;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.assembly.ImageAssembler;
import qupath.ext.basicstitching.assembly.PyramidImageWriter;
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.StitchingStrategy;
import qupath.ext.basicstitching.stitching.StitchingStrategyFactory;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.ImageServer;

/**
 * Orchestrates the complete stitching workflow:
 * <ul>
 *     <li>Selects the appropriate {@link StitchingStrategy} based on user configuration.</li>
 *     <li>Prepares tile-to-position mappings for all relevant image tiles.</li>
 *     <li>Assembles the tiles into a virtual sparse image server.</li>
 *     <li>Writes the resulting image as a multi-resolution OME-TIFF pyramid.</li>
 * </ul>
 *
 * <p>
 * To run a stitching job, provide a {@link StitchingConfig} object describing the workflow parameters
 * (stitching type, input/output paths, compression, pixel size, downsampling, filter, etc).
 * </p>
 *
 * <p>
 * The workflow logs all major steps and errors to facilitate debugging. The final stitched image is written
 * to the specified output directory. On failure, <code>null</code> is returned.
 * </p>
 *
 * <b>Example usage:</b>
 * <pre>
 *     StitchingConfig config = new StitchingConfig(...);
 *     String outputPath = StitchingWorkflow.run(config);
 *     if (outputPath != null) {
 *         System.out.println("Stitching complete: " + outputPath);
 *     }
 * </pre>
 */
public class StitchingWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(StitchingWorkflow.class);

    /**
     * Runs the entire stitching pipeline from tile mapping to OME-TIFF export.
     *
     * This method orchestrates the complete stitching workflow:
     * <ol>
     *   <li>Selects the appropriate {@link StitchingStrategy} based on configuration</li>
     *   <li>Prepares tile mappings for all matching subdirectories</li>
     *   <li>Groups tiles by subdirectory to create separate outputs</li>
     *   <li>For each subdirectory group:
     *       <ul>
     *         <li>Assembles tiles into a virtual sparse image server</li>
     *         <li>Writes the image as a multi-resolution OME-TIFF pyramid</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>When multiple subdirectories match the configuration's matching string,
     * this method creates separate output files for each subdirectory, preserving
     * the subdirectory name in the output filename.</p>
     *
     * <p>The method handles errors gracefully, logging detailed information at each
     * step to facilitate debugging. If any subdirectory fails to process, the method
     * continues with the remaining subdirectories.</p>
     *
     * @param config The {@link StitchingConfig} specifying workflow parameters including:
     *               <ul>
     *                 <li>stitchingType - The strategy to use for tile mapping</li>
     *                 <li>folderPath - Root folder containing tile subdirectories</li>
     *                 <li>outputPath - Destination folder for stitched images</li>
     *                 <li>matchingString - Pattern to match subdirectory names</li>
     *                 <li>compressionType - OME-TIFF compression method</li>
     *                 <li>pixelSizeInMicrons - Pixel size for metadata</li>
     *                 <li>baseDownsample - Initial downsampling factor</li>
     *               </ul>
     * @return The absolute path to the last successfully written OME-TIFF,
     *         or {@code null} if all subdirectories failed to process.
     *         When multiple subdirectories are processed, only the last path is returned
     *         for backward compatibility, though all files are created successfully.
     */
    public static String run(StitchingConfig config) {
        try {
            logger.info("=== STITCHING WORKFLOW STARTING ===");
            String extVersion = GeneralTools.getPackageVersion(StitchingWorkflow.class);
            logger.info("Tiles-to-Pyramid version: {}", extVersion != null ? extVersion : "dev");
            logger.info("QuPath version: {}", GeneralTools.getVersion());
            logger.info("Configuration:");
            logger.info("  - Stitching type: {}", config.stitchingType);
            logger.info("  - Folder path: {}", config.folderPath);
            logger.info("  - Matching string: '{}'", config.matchingString);
            logger.info("  - Output path: {}", config.outputPath);
            logger.info("  - Compression: {}", config.compressionType);
            logger.info("  - Pixel size: {} um", config.pixelSizeInMicrons);
            logger.info("  - Downsample: {}", config.baseDownsample);

            // 1. Select the appropriate strategy for this stitching type
            StitchingStrategy strategy = StitchingStrategyFactory.getStrategy(config);
            if (strategy == null) {
                logger.error("No valid stitching strategy for type: {}", config.stitchingType);
                return null;
            }
            logger.info("Selected strategy: {}", strategy.getClass().getSimpleName());

            // 2. Prepare tile mappings (tile file, region, and group info)
            logger.info("Preparing tile mappings...");
            List<TileMapping> allMappings = strategy.prepareStitching(
                    config.folderPath, config.pixelSizeInMicrons, config.baseDownsample, config.matchingString);

            if (allMappings == null || allMappings.isEmpty()) {
                logger.error("No tile mappings produced by strategy");
                return null;
            }
            logger.info("Total tile mappings created: {}", allMappings.size());

            // 3. Group tiles by subdirectory
            Map<String, List<TileMapping>> groupedMappings =
                    allMappings.stream().collect(Collectors.groupingBy(mapping -> mapping.subdirName));

            logger.info("Tiles grouped into {} subdirectories:", groupedMappings.size());
            groupedMappings.forEach((subdir, tiles) -> logger.info("  - '{}': {} tiles", subdir, tiles.size()));

            // 4. Process each subdirectory group separately
            String lastSuccessfulPath = null;
            int successCount = 0;
            int failureCount = 0;

            for (Map.Entry<String, List<TileMapping>> entry : groupedMappings.entrySet()) {
                String subdirName = entry.getKey();
                List<TileMapping> subdirMappings = entry.getValue();

                logger.info(""); // Blank line for readability
                logger.info("=== Processing subdirectory: '{}' ({} tiles) ===", subdirName, subdirMappings.size());

                try {
                    // Direct stitching for large tile counts (bypasses SparseImageServer)
                    if (DirectTileStitcher.shouldUseDirectStitching(subdirMappings.size())) {
                        logger.info(
                                "Using direct tile stitcher for {} tiles (threshold: {})", subdirMappings.size(), 500);
                        String outBase;
                        if (config.outputFilename != null && !config.outputFilename.isBlank()) {
                            outBase = config.outputFilename + "_" + subdirName;
                        } else {
                            outBase = subdirName;
                        }
                        String written = DirectTileStitcher.stitch(
                                subdirMappings,
                                config.outputPath,
                                outBase,
                                config,
                                progress -> logger.debug(
                                        "Direct stitch progress: {}%", String.format("%.1f", progress * 100)));
                        if (written != null) {
                            logger.info("Successfully wrote (direct): {}", written);
                            lastSuccessfulPath = written;
                            successCount++;
                        } else {
                            logger.error("Direct stitching failed for subdirectory: {}", subdirName);
                            failureCount++;
                        }
                        continue;
                    }

                    // 4a. Assemble image server for this subdirectory (standard path)
                    // Note: For RGB images, this automatically wraps with white background
                    logger.info("Assembling image server...");
                    ImageServer<BufferedImage> server =
                            ImageAssembler.assemble(subdirMappings, config.pixelSizeInMicrons, config.zSpacingMicrons);

                    if (server == null) {
                        logger.error("Failed to assemble image server for subdirectory: {}", subdirName);
                        failureCount++;
                        continue;
                    }
                    logger.info(
                            "Successfully assembled {} tiles into image server (type: {})",
                            subdirMappings.size(),
                            server.getServerType());

                    // 4b. Determine output filename
                    String outBase;
                    try {
                        // Try to use config.outputFilename if it exists
                        java.lang.reflect.Field f = config.getClass().getField("outputFilename");
                        String candidate = (String) f.get(config);
                        if (candidate != null && !candidate.isBlank()) {
                            // If outputFilename is specified, append subdirectory name
                            outBase = candidate + "_" + subdirName;
                            logger.info("Using configured output filename with subdir: {}", outBase);
                        } else {
                            // Use subdirectory name as base
                            outBase = subdirName;
                            logger.info("Using subdirectory name as output base: {}", outBase);
                        }
                    } catch (NoSuchFieldException nsfe) {
                        // Field doesn't exist in config; use subdirectory name
                        outBase = subdirName;
                        logger.info("No outputFilename field, using subdirectory name: {}", outBase);
                    } catch (Exception e) {
                        logger.warn("Error accessing outputFilename field, using subdirectory name", e);
                        outBase = subdirName;
                    }

                    // 4c. Write output pyramid (TIFF or ZARR based on config)
                    String formatName = config.outputFormat == null
                            ? "OME-TIFF"
                            : (config.outputFormat == StitchingConfig.OutputFormat.OME_ZARR ? "OME-ZARR" : "OME-TIFF");
                    logger.info("Writing {} pyramid for '{}'...", formatName, subdirName);

                    String written = PyramidImageWriter.write(
                            server,
                            config.outputPath,
                            outBase,
                            config.compressionType,
                            config.baseDownsample,
                            config.outputFormat != null ? config.outputFormat : StitchingConfig.OutputFormat.OME_TIFF,
                            progress -> logger.debug(
                                    "Write progress for '{}': {}%", subdirName, String.format("%.1f", progress * 100)));

                    if (written != null) {
                        logger.info("Successfully wrote: {}", written);
                        lastSuccessfulPath = written;
                        successCount++;
                    } else {
                        logger.error("Failed to write pyramid for subdirectory: {}", subdirName);
                        failureCount++;
                    }

                    // Close the server to free resources
                    server.close();

                } catch (Exception e) {
                    logger.error("Exception processing subdirectory '{}': {}", subdirName, e.getMessage(), e);
                    failureCount++;
                }
            }

            // 5. Summary and return
            logger.info("");
            logger.info("=== STITCHING WORKFLOW COMPLETE ===");
            logger.info(
                    "Processed {} subdirectories: {} successful, {} failed",
                    groupedMappings.size(),
                    successCount,
                    failureCount);

            if (successCount > 0) {
                logger.info("Last successful output: {}", lastSuccessfulPath);
            } else {
                logger.warn("No subdirectories were successfully processed");
            }

            return lastSuccessfulPath;

        } catch (Exception e) {
            logger.error("Critical exception in StitchingWorkflow", e);
            return null;
        }
    }
}
