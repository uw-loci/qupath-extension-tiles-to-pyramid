package qupath.ext.basicstitching.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.StitchingStrategy;
import qupath.ext.basicstitching.stitching.StitchingStrategyFactory;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.utilities.RegistrationPreferences;
import qupath.lib.common.GeneralTools;

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
    /**
     * Detailed multi-output result of a stitching workflow run. Callers that
     * care about which specific subdirectories succeeded or failed (for
     * example: PPM multi-angle acquisitions where one angle may legitimately
     * fail while the others succeed) should consume this instead of the
     * legacy {@link #run(StitchingConfig)} which only returns the last
     * successful path. {@code outputs} preserves insertion order; it is
     * never null but may be empty when the entire workflow failed.
     */
    public record StitchingResult(
            List<String> outputs, int successCount, int failureCount, List<String> failedSubdirs) {
        public StitchingResult {
            outputs = List.copyOf(outputs);
            failedSubdirs = List.copyOf(failedSubdirs);
        }

        /** True if at least one subdirectory was stitched successfully. */
        public boolean hasAnyOutput() {
            return !outputs.isEmpty();
        }

        /** Convenience: the last successful output path, or {@code null} when none. */
        public String lastOutput() {
            return outputs.isEmpty() ? null : outputs.get(outputs.size() - 1);
        }

        /** An empty result (no outputs, no failures), used for early-exit paths. */
        public static StitchingResult empty() {
            return new StitchingResult(Collections.emptyList(), 0, 0, Collections.emptyList());
        }
    }

    /**
     * Backward-compatible entry point: returns the last successful output
     * path, or {@code null} if none. Prefer {@link #runDetailed(StitchingConfig)}
     * in new code so callers can report per-subdirectory success / failure.
     */
    public static String run(StitchingConfig config) {
        return runDetailed(config).lastOutput();
    }

    /**
     * Full workflow run that reports every output path and every failed
     * subdirectory. Same orchestration as {@link #run(StitchingConfig)} --
     * the only difference is the richer return value.
     */
    public static StitchingResult runDetailed(StitchingConfig config) {
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

            // Overlap blending is policy, not a per-run choice, so it is resolved here from the
            // shared preferences rather than by each caller. Doing it in one place is what makes a
            // QPSC-driven acquisition and a standalone stitch agree; a caller that has already set
            // an explicit mode (tests, and any future per-run override) keeps it.
            if (!config.isOverlapBlendSet()) {
                config.setOverlapBlend(preferredOverlapBlend());
            }
            logger.info("  - Overlap blending: {}", config.getOverlapBlend().label());

            // 1. Select the appropriate strategy for this stitching type
            StitchingStrategy strategy = StitchingStrategyFactory.getStrategy(config);
            if (strategy == null) {
                logger.error("No valid stitching strategy for type: {}", config.stitchingType);
                return StitchingResult.empty();
            }
            logger.info("Selected strategy: {}", strategy.getClass().getSimpleName());

            // 2. Prepare tile mappings (tile file, region, and group info)
            logger.info("Preparing tile mappings...");
            List<TileMapping> allMappings = strategy.prepareStitching(
                    config.folderPath, config.pixelSizeInMicrons, config.baseDownsample, config.matchingString);

            if (allMappings == null || allMappings.isEmpty()) {
                logger.error("No tile mappings produced by strategy");
                return StitchingResult.empty();
            }
            logger.info("Total tile mappings created: {}", allMappings.size());

            // 2b. Optionally correct the nominal stage positions against the tiles' own content.
            // This is the only place positions change; everything downstream reads them through
            // TileMapping.region and is unaffected. A no-op unless the caller set a mode.
            allMappings = TileRegistrationStep.applyTo(allMappings, config);

            // 3. Group tiles by subdirectory
            Map<String, List<TileMapping>> groupedMappings =
                    allMappings.stream().collect(Collectors.groupingBy(mapping -> mapping.subdirName));

            logger.info("Tiles grouped into {} subdirectories:", groupedMappings.size());
            groupedMappings.forEach((subdir, tiles) -> logger.info("  - '{}': {} tiles", subdir, tiles.size()));

            // 4. Process each subdirectory group separately
            List<String> outputs = new ArrayList<>();
            List<String> failedSubdirs = new ArrayList<>();
            int successCount = 0;
            int failureCount = 0;

            for (Map.Entry<String, List<TileMapping>> entry : groupedMappings.entrySet()) {
                String subdirName = entry.getKey();
                List<TileMapping> subdirMappings = entry.getValue();

                logger.info(""); // Blank line for readability
                logger.info("=== Processing subdirectory: '{}' ({} tiles) ===", subdirName, subdirMappings.size());

                try {
                    // All tile counts go through the direct tile stitcher: it
                    // bypasses SparseImageServer (bounded memory) and writes via
                    // DirectTiffOutputWriter (OME-TIFF) or the JZarr chunk writer.
                    logger.info("Stitching {} tiles via the direct tile stitcher", subdirMappings.size());
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
                            progress ->
                                    logger.debug("Direct stitch progress: {}%", String.format("%.1f", progress * 100)));
                    if (written != null) {
                        logger.info("Successfully wrote: {}", written);
                        outputs.add(written);
                        successCount++;
                    } else {
                        logger.error("Stitching failed for subdirectory: {}", subdirName);
                        failedSubdirs.add(subdirName);
                        failureCount++;
                    }

                } catch (Exception e) {
                    logger.error("Exception processing subdirectory '{}': {}", subdirName, e.getMessage(), e);
                    failedSubdirs.add(subdirName);
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
                logger.info("Successful outputs ({}):", outputs.size());
                for (String out : outputs) {
                    logger.info("  - {}", out);
                }
            } else {
                logger.warn("No subdirectories were successfully processed");
            }
            if (failureCount > 0) {
                logger.warn("Failed subdirectories: {}", failedSubdirs);
            }

            return new StitchingResult(outputs, successCount, failureCount, failedSubdirs);

        } catch (Exception e) {
            logger.error("Critical exception in StitchingWorkflow", e);
            return StitchingResult.empty();
        }
    }

    /**
     * The user's configured overlap blending, or the hard cut if it cannot be read.
     *
     * <p>Guarded the same way QPSC guards the registration settings: the preferences depend on
     * QuPath's GUI preference machinery, which is absent in a headless run and in tests, and a
     * cosmetic choice must never be the thing that fails a stitch.
     *
     * @return the mode to use; never null
     */
    private static OverlapBlend preferredOverlapBlend() {
        try {
            return RegistrationPreferences.overlapBlend();
        } catch (Throwable t) {
            logger.debug("Overlap blending preference unavailable ({}); using last-tile-wins", t.toString());
            return OverlapBlend.LAST_WINS;
        }
    }
}
