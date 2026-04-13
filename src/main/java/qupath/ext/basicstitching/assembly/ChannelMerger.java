package qupath.ext.basicstitching.assembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;

/**
 * Merges N single-channel (or compatible multi-channel) stitched pyramids
 * into one multichannel pyramidal OME-TIFF. Used as a post-step after
 * {@link qupath.ext.basicstitching.workflow.StitchingWorkflow} has produced
 * one stitched output per channel, to yield a single image the QuPath
 * project can open as a multi-channel dataset without manual concatenation.
 *
 * <p>All inputs must share the same pixel dimensions, pixel type, and
 * pyramid structure -- that's the normal outcome when they come from the
 * same {@code TileConfiguration.txt} and the same acquisition's per-channel
 * subdirectories. The output channel names default to the file basenames
 * but can be supplied explicitly.
 *
 * <p>On success the source per-channel files can optionally be deleted
 * (the default: keep them, so the user can inspect each channel individually
 * if needed). Most of the heavy lifting is done by {@link ChannelMergeImageServer}
 * and {@link PyramidImageWriter} -- this class is just the glue that opens
 * inputs, validates, and calls the writer.
 */
public class ChannelMerger {

    private static final Logger logger = LoggerFactory.getLogger(ChannelMerger.class);

    private ChannelMerger() {}

    /**
     * Merge N single-channel pyramids into one multichannel pyramid.
     *
     * @param inputPaths      ordered list of per-channel pyramid files (OME-TIFF); must be non-empty
     * @param channelNames    display names for each channel in output order (same length as inputPaths).
     *                        Pass {@code null} to use the source files' own channel names
     * @param outputDirectory directory to write the merged output into
     * @param outputFilename  filename stem (no extension -- {@code .ome.tif} is appended)
     * @param compression     compression type (e.g. {@code "LZW"})
     * @param outputFormat    output format (OME-TIFF or OME-ZARR)
     * @return absolute path to the merged output, or {@code null} on failure
     */
    public static String merge(
            List<String> inputPaths,
            List<String> channelNames,
            String outputDirectory,
            String outputFilename,
            String compression,
            StitchingConfig.OutputFormat outputFormat) {
        if (inputPaths == null || inputPaths.isEmpty()) {
            logger.warn("ChannelMerger.merge called with no input paths");
            return null;
        }
        if (outputDirectory == null || outputFilename == null) {
            logger.warn("ChannelMerger.merge called with null output directory or filename");
            return null;
        }
        if (channelNames != null && channelNames.size() != inputPaths.size()) {
            logger.warn(
                    "ChannelMerger: channelNames size ({}) does not match inputPaths size ({}); "
                            + "channel names will fall back to source server defaults",
                    channelNames.size(),
                    inputPaths.size());
            channelNames = null;
        }

        // Open each source as an ImageServer
        List<ImageServer<java.awt.image.BufferedImage>> sources = new ArrayList<>();
        try {
            for (String path : inputPaths) {
                Path p = Paths.get(path);
                if (!Files.exists(p)) {
                    logger.warn("ChannelMerger: input file not found, skipping: {}", p);
                    continue;
                }
                try {
                    ImageServer<java.awt.image.BufferedImage> server = ImageServers.buildServer(p.toUri());
                    sources.add(server);
                    logger.debug(
                            "ChannelMerger: opened source {} ({}x{}, {} channels, {} resolutions)",
                            p.getFileName(),
                            server.getWidth(),
                            server.getHeight(),
                            server.nChannels(),
                            server.nResolutions());
                } catch (IOException e) {
                    logger.error("ChannelMerger: failed to open source {}: {}", p, e.getMessage());
                }
            }

            if (sources.size() < 2) {
                logger.warn(
                        "ChannelMerger: need at least 2 sources to merge, got {}. Skipping merge.",
                        sources.size());
                return null;
            }

            ChannelMergeImageServer merged = new ChannelMergeImageServer(sources, channelNames);
            logger.info(
                    "ChannelMerger: merging {} sources into {} (total channels: {})",
                    sources.size(),
                    outputFilename,
                    merged.nChannels());

            String outPath = PyramidImageWriter.write(
                    merged, outputDirectory, outputFilename, compression, 1.0, outputFormat, null);

            if (outPath != null) {
                logger.info("ChannelMerger: merge succeeded -> {}", outPath);
            } else {
                logger.error("ChannelMerger: PyramidImageWriter returned null -- merge failed");
            }
            return outPath;
        } finally {
            // Close source servers even if we returned early or threw
            for (ImageServer<java.awt.image.BufferedImage> s : sources) {
                try {
                    s.close();
                } catch (Exception e) {
                    logger.debug("ChannelMerger: error closing source: {}", e.getMessage());
                }
            }
        }
    }

}
