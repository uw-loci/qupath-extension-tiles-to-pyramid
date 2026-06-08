package qupath.ext.basicstitching.stitching;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.regions.ImageRegion;

/**
 * Stitching strategy that reads a TileConfiguration.txt for image positions.
 * Only processes subfolders that match the given string and have a TileConfiguration.txt present.
 */
public class TileConfigurationTxtStrategy implements StitchingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(TileConfigurationTxtStrategy.class);

    /**
     * Optional caller-set flag: when {@code true}, the Y coordinate read from
     * TileConfiguration.txt is negated before converting to pixel space. This
     * is needed for microscopes whose stage Y convention is inverted relative
     * to the standard assumption (stage Y+ = pixel Y down). CAMM/PPM leave
     * this at {@code false} and the behaviour is unchanged.
     *
     * <p>Set this immediately before invoking the stitching workflow, and
     * reset it to {@code false} afterwards. It is intentionally static
     * because the {@link StitchingStrategy} interface does not carry a
     * configuration object through to the strategy implementations and
     * QuPath-side stitching calls are effectively serial.
     */
    public static volatile boolean flipStitchingY = false;

    /** Mirror of {@link #flipStitchingY} for the X axis. */
    public static volatile boolean flipStitchingX = false;

    /**
     * Directory-name patterns that encode the z-slice and timepoint of a tile
     * when a Z-stack and/or time series is preserved rather than projected. The
     * acquisition writes preserved planes under {@code z{zz}/} (single
     * timepoint) or {@code t{tt}/z{zz}/} (Z + T). Flat / projected layouts have
     * no such directories, so every tile resolves to z=0, t=0 and the output is
     * unchanged. See the 5D stitching design in the extension docs.
     */
    private static final Pattern Z_DIR = Pattern.compile("^z(\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern T_DIR = Pattern.compile("^t(\\d+)$", Pattern.CASE_INSENSITIVE);
    /**
     * Prepares tile mappings for image stitching based on coordinates in TileConfiguration.txt files.
     *
     * This method:
     * <ul>
     *     <li>Iterates through subdirectories in the specified root folder whose names contain the given matching string.</li>
     *     <li>For each such subdirectory, parses its TileConfiguration.txt to get image positions (in microns, downsampled as specified).</li>
     *     <li>Finds all TIFF files, matches them to config entries, and creates ImageRegion mappings.</li>
     *     <li>Uses absolute stage positions from TileConfiguration.txt directly (no Y-flip needed
     *         since coordinates are already in physical space).</li>
     * </ul>
     *
     * @param folderPath         The path to the root directory containing tile subdirectories.
     * @param pixelSizeInMicrons The pixel size in microns (used to scale coordinates to pixels).
     * @param baseDownsample     Downsampling factor applied to the coordinates.
     * @param matchingString     String to match in subdirectory names for inclusion.
     * @return A list of TileMapping objects representing each tile's file, image region, and subdirectory.
     */
    @Override
    public List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString) {
        logger.info("Preparing stitching using TileConfiguration.txt strategy for folder: {}", folderPath);
        List<TileMapping> mappings = new ArrayList<>();
        Path rootdir = Paths.get(folderPath);

        // First, check for matching subdirectories (multi-angle acquisitions).
        // This takes priority over root directory processing to avoid accidentally
        // lumping all angles together when the root directory name also matches.
        logger.info("Searching for subdirectories matching '{}' within: {}", matchingString, folderPath);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootdir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && path.getFileName().toString().contains(matchingString)) {
                    Path configPath = path.resolve("TileConfiguration.txt");
                    if (!Files.exists(configPath)) {
                        logger.warn("No TileConfiguration.txt in subdir: {}", path);
                        continue;
                    }
                    logger.info("Processing subdir: {} with config {}", path, configPath);
                    mappings.addAll(processDirectory(path, configPath, pixelSizeInMicrons, baseDownsample));
                }
            }
        } catch (Exception e) {
            logger.error("Error searching subdirectories in TileConfigurationTxtStrategy", e);
        }

        // If no matching subdirectories found, fall back to processing the root directory
        // directly (common for brightfield where tiles are in the folder itself)
        if (mappings.isEmpty()) {
            Path rootConfigPath = rootdir.resolve("TileConfiguration.txt");
            if (Files.exists(rootConfigPath)) {
                logger.info("No matching subdirectories found. Processing root directory directly: {}", rootdir);
                mappings.addAll(processDirectory(rootdir, rootConfigPath, pixelSizeInMicrons, baseDownsample));
            } else {
                logger.warn("No matching subdirectories and no TileConfiguration.txt in root: {}", rootdir);
            }
        }

        logger.info("Total tiles mapped from TileConfiguration.txt: {}", mappings.size());
        return mappings;
    }

    /**
     * Process a single directory containing TileConfiguration.txt and TIFF files.
     *
     * @param path Directory to process
     * @param configPath Path to TileConfiguration.txt file
     * @param pixelSizeInMicrons Pixel size for coordinate conversion
     * @param baseDownsample Downsample factor
     * @return List of tile mappings for this directory
     */
    private List<TileMapping> processDirectory(
            Path path, Path configPath, double pixelSizeInMicrons, double baseDownsample) {
        List<TileMapping> mappings = new ArrayList<>();

        try {
            // Parse positions from config
            Map<String, Position> positionMap = parseTileConfig(configPath, pixelSizeInMicrons, baseDownsample);

            if (positionMap.isEmpty()) {
                logger.warn("No tile positions found in config: {}", configPath);
                return mappings;
            }

            // First try to find TIFF files directly in the main directory (flat /
            // projected layout -- these are all z=0, t=0).
            List<Path> tiffFiles = new ArrayList<>();
            try (DirectoryStream<Path> tifStream = Files.newDirectoryStream(path, "*.tif*")) {
                for (Path tifPath : tifStream) {
                    tiffFiles.add(tifPath);
                }
            }

            // If none found at the top level, the tiles live in subdirectories:
            // either an angle subdir (legacy one-level nesting) or preserved
            // Z/T planes under z{zz}/ or t{tt}/z{zz}/. Walk recursively; the
            // plane indices are derived from the directory names per tile, so an
            // angle-only nesting still resolves to z=0, t=0 (unchanged).
            if (tiffFiles.isEmpty()) {
                logger.info("No TIFF files at top level of {}, searching subdirectories (angle and/or z/t)", path);
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(TileConfigurationTxtStrategy::isTiff)
                            .forEach(tiffFiles::add);
                }
            }

            // Process all found TIFF files
            int tileCount = tiffFiles.size();
            logger.info("Processing {} TIFF files for dimensions and mapping...", tileCount);
            int processed = 0;
            for (Path tifPath : tiffFiles) {
                String filename = tifPath.getFileName().toString();
                Position pos = positionMap.get(filename);
                Map<String, Integer> dims = UtilityFunctions.getTiffDimensions(tifPath.toFile());
                processed++;
                if (processed % 500 == 0 || processed == tileCount) {
                    logger.info("Tile dimension progress: {}/{} files processed", processed, tileCount);
                }
                if (pos != null && dims != null) {
                    int[] zt = parseZT(path.relativize(tifPath));
                    ImageRegion region = ImageRegion.createInstance(
                            (int) Math.round(pos.x),
                            (int) Math.round(pos.y),
                            dims.get("width"),
                            dims.get("height"),
                            zt[0],
                            zt[1]);
                    mappings.add(new TileMapping(
                            tifPath.toFile(), region, path.getFileName().toString()));
                    logger.debug("Mapped {} at ({}, {}) z={} t={} from config", filename, pos.x, pos.y, zt[0], zt[1]);
                } else {
                    logger.warn("Missing config position or TIFF dimensions for {}", filename);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing directory in TileConfigurationTxtStrategy: {}", path, e);
        }

        return mappings;
    }

    /** True if the path's file name ends in .tif or .tiff (case-insensitive). */
    private static boolean isTiff(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    /**
     * Derive the (z, t) plane indices for a tile from its path relative to the
     * group directory. Directory segments matching {@code z{zz}} / {@code t{tt}}
     * set the respective index; any other segments (e.g. an angle subdir) and
     * the file name itself are ignored, so a flat or angle-only layout yields
     * {@code {0, 0}}.
     *
     * @param relative tile path relative to the group directory
     * @return a two-element array {@code {z, t}}
     */
    private static int[] parseZT(Path relative) {
        int z = 0;
        int t = 0;
        for (Path seg : relative) {
            String name = seg.toString();
            Matcher mz = Z_DIR.matcher(name);
            if (mz.matches()) {
                z = Integer.parseInt(mz.group(1));
                continue;
            }
            Matcher mt = T_DIR.matcher(name);
            if (mt.matches()) {
                t = Integer.parseInt(mt.group(1));
            }
        }
        return new int[] {z, t};
    }

    /**
     * Parse a TileConfiguration.txt file and return a mapping of file names to positions.
     */
    private static Map<String, Position> parseTileConfig(
            Path configPath, double pixelSizeInMicrons, double baseDownsample) {
        Map<String, Position> map = new HashMap<>();
        boolean flipY = flipStitchingY;
        boolean flipX = flipStitchingX;
        if (flipY) {
            logger.info("flipStitchingY=true: negating Y coordinates for stage-inverted scope");
        }
        if (flipX) {
            logger.info("flipStitchingX=true: negating X coordinates for stage-inverted scope");
        }
        try {
            List<String> lines = Files.readAllLines(configPath);
            for (String line : lines) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] parts = line.split(";");
                if (parts.length >= 3) {
                    String imageName = parts[0].trim();
                    String[] coord = parts[2].replaceAll("[(){}]", "").split(",");
                    if (coord.length >= 2) {
                        double rawX = Double.parseDouble(coord[0].trim());
                        double rawY = Double.parseDouble(coord[1].trim());
                        if (flipX) {
                            rawX = -rawX;
                        }
                        if (flipY) {
                            rawY = -rawY;
                        }
                        double x = rawX / (pixelSizeInMicrons * baseDownsample);
                        double y = rawY / (pixelSizeInMicrons * baseDownsample);
                        map.put(imageName, new Position(x, y));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing TileConfiguration.txt at {}", configPath, e);
        }
        return map;
    }

    /** Holds a 2D position. */
    private static class Position {
        final double x, y;

        Position(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
