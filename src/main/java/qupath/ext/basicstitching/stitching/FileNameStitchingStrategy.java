package qupath.ext.basicstitching.stitching;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.regions.ImageRegion;

/**
 * Stitching strategy that parses [x,y] coordinates from TIFF file names.
 * Builds TileMapping list for files matching the coordinate pattern in subfolders matching the provided string.
 */
public class FileNameStitchingStrategy implements StitchingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(FileNameStitchingStrategy.class);

    @Override
    public List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString) {
        logger.info("Preparing stitching using filename coordinate strategy for folder: {}", folderPath);
        List<TileMapping> mappings = new ArrayList<>();
        Path rootdir = Paths.get(folderPath);
        Pattern pattern = Pattern.compile(".*\\[(\\d+),(\\d+)\\].*\\.(tif|tiff|ome\\.tif)$");

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootdir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && path.getFileName().toString().contains(matchingString)) {
                    logger.info("Processing subdir: {}", path);
                    try (DirectoryStream<Path> tifStream = Files.newDirectoryStream(path, "*.tif*")) {
                        for (Path tifPath : tifStream) {
                            String filename = tifPath.getFileName().toString();
                            Matcher matcher = pattern.matcher(filename);
                            if (matcher.matches()) {
                                int x = Integer.parseInt(matcher.group(1));
                                int y = Integer.parseInt(matcher.group(2));
                                // Scale by pixel size if needed
                                int xPx = (int) Math.round(x / pixelSizeInMicrons);
                                int yPx = (int) Math.round(y / pixelSizeInMicrons);
                                Map<String, Integer> dims = UtilityFunctions.getTiffDimensions(tifPath.toFile());
                                if (dims != null) {
                                    ImageRegion region = ImageRegion.createInstance(
                                            xPx, yPx, dims.get("width"), dims.get("height"), 0, 0);
                                    mappings.add(new TileMapping(
                                            tifPath.toFile(),
                                            region,
                                            path.getFileName().toString()));
                                    logger.debug("Added mapping for file {} at ({}, {})", filename, xPx, yPx);
                                } else {
                                    logger.warn("Failed to get TIFF dimensions for {}", filename);
                                }
                            } else {
                                logger.debug("Filename does not match pattern: {}", filename);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in FileNameStitchingStrategy", e);
        }
        logger.info("Total tiles mapped: {}", mappings.size());
        return mappings;
    }
}
