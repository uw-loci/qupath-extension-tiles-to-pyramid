package qupath.ext.basicstitching.stitching;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

        warnings.clear();
        List<Path> folders;
        try {
            folders = TileDirectories.resolve(rootdir, matchingString);
        } catch (Exception e) {
            logger.error("Error in FileNameStitchingStrategy", e);
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
                        Matcher matcher = pattern.matcher(filename);
                        if (matcher.matches()) {
                            int x = Integer.parseInt(matcher.group(1));
                            int y = Integer.parseInt(matcher.group(2));
                            // Scale by pixel size if needed
                            int xPx = (int) Math.round(x / pixelSizeInMicrons);
                            int yPx = (int) Math.round(y / pixelSizeInMicrons);
                            TileHeaders.Size dims = readSize(tifPath);
                            if (dims != null) {
                                ImageRegion region =
                                        ImageRegion.createInstance(xPx, yPx, dims.width(), dims.height(), 0, 0);
                                found.add(new TileMapping(
                                        tifPath.toFile(),
                                        region,
                                        path.getFileName().toString()));
                                logger.debug("Added mapping for file {} at ({}, {})", filename, xPx, yPx);
                            } else {
                                logger.warn("Failed to get TIFF dimensions for {}", filename);
                                unreadable.add(filename);
                            }
                        } else {
                            logger.debug("Filename does not match pattern: {}", filename);
                        }
                    }
                }
                mappings.addAll(found);
                if (!unreadable.isEmpty()) {
                    warnings.add(folder + ": " + StitchingStrategy.countAndNames(unreadable)
                            + " could not be read (no TIFF size), so the image has holes there.");
                }
            } catch (Exception e) {
                logger.error("Error reading tile folder {} in FileNameStitchingStrategy", path, e);
                warnings.add(folder + ": could not be read (" + e.getMessage() + "), so it was not stitched.");
            }
        }
        logger.info("Total tiles mapped: {}", mappings.size());
        return mappings;
    }

    /** What the last {@link #prepareStitching} left out or could not read, for the result window. */
    private final List<String> warnings = new ArrayList<>();

    @Override
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    private static TileHeaders.Size readSize(Path tif) {
        try {
            return TileHeaders.read(tif);
        } catch (java.io.IOException e) {
            logger.debug("{}", e.getMessage());
            return null;
        }
    }
}
