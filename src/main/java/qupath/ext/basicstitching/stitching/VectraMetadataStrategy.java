package qupath.ext.basicstitching.stitching;

import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.regions.ImageRegion;

/**
 * Stitching strategy for Vectra multiplex TIFFs using metadata for position.
 * Builds TileMapping list for all TIFF files in matching subfolders.
 * Supports fudge factors to correct for stage calibration issues.
 */
public class VectraMetadataStrategy implements StitchingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(VectraMetadataStrategy.class);
    private double xFudgeFactor = 1.0;
    private double yFudgeFactor = 1.0;

    // Constructor for setting fudge factors
    public VectraMetadataStrategy(double xFudgeFactor, double yFudgeFactor) {
        this.xFudgeFactor = xFudgeFactor;
        this.yFudgeFactor = yFudgeFactor;
    }

    // Default constructor
    public VectraMetadataStrategy() {
        this(1.0, 1.0);
    }

    @Override
    public List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString) {
        logger.info("Preparing stitching using Vectra metadata strategy for folder: {}", folderPath);
        logger.info("Using fudge factors - X: {}, Y: {}", xFudgeFactor, yFudgeFactor);
        List<TileMapping> mappings = new ArrayList<>();
        Path rootdir = Paths.get(folderPath);

        warnings.clear();
        List<Path> folders;
        try {
            folders = TileDirectories.resolve(rootdir, matchingString);
        } catch (Exception e) {
            logger.error("Error in VectraMetadataStrategy", e);
            warnings.add("The tile folders under " + rootdir.getFileName() + " could not be listed (" + e.getMessage()
                    + ").");
            return mappings;
        }
        for (Path path : folders) {
            String folder = path.getFileName().toString();
            List<TileMapping> found = new ArrayList<>();
            List<String> unreadable = new ArrayList<>();
            // One unreadable folder leaves out that folder, not every folder after it.
            try {
                logger.info("Processing tile folder: {}", path);
                try (DirectoryStream<Path> tifStream = Files.newDirectoryStream(path, "*.tif*")) {
                    for (Path tifPath : tifStream) {
                        String filename = tifPath.getFileName().toString();
                        UtilityFunctions.VectraRegionInfo info = UtilityFunctions.getVectraPositionAndDimensions(
                                tifPath.toFile(), xFudgeFactor, yFudgeFactor);
                        if (info != null) {
                            // If info.xPx/yPx are already in pixels, no scaling by pixelSizeInMicrons needed
                            ImageRegion region =
                                    ImageRegion.createInstance(info.xPx, info.yPx, info.width, info.height, 0, 0);
                            found.add(new TileMapping(
                                    tifPath.toFile(), region, path.getFileName().toString()));
                            logger.debug("Added mapping for Vectra file {} at ({}, {})", filename, info.xPx, info.yPx);
                        } else {
                            logger.warn("Failed to extract Vectra metadata for {}", filename);
                            unreadable.add(filename);
                        }
                    }
                }
                mappings.addAll(found);
                if (!unreadable.isEmpty()) {
                    warnings.add(folder + ": " + StitchingStrategy.countAndNames(unreadable)
                            + " had no readable Vectra position, so the image has holes there.");
                }
            } catch (Exception e) {
                logger.error("Error reading tile folder {} in VectraMetadataStrategy", path, e);
                warnings.add(folder + ": could not be read (" + e.getMessage() + "), so it was not stitched.");
            }
        }
        logger.info("Total Vectra tiles mapped: {}", mappings.size());
        return mappings;
    }

    /** What the last {@link #prepareStitching} left out or could not read, for the result window. */
    private final List<String> warnings = new ArrayList<>();

    @Override
    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
