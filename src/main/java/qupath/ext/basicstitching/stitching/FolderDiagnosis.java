package qupath.ext.basicstitching.stitching;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a folder's contents look like, so a stitch that found no tiles can say why.
 *
 * <p>Every strategy reads positions from somewhere specific, and picking the wrong one produces
 * the same symptom -- zero tiles -- whatever the real cause. The method is also remembered between
 * runs, so the common way to hit this is to point a folder at whichever method you used last. That
 * is not something a user can debug from "no tile mappings produced by strategy" in a log.
 *
 * <p>This looks for each method's own evidence and reports what it found, so the failure can name
 * the method that would have worked. It is deliberately cheap and tolerant: a bounded scan, no
 * pixel reads, and any unreadable folder is simply evidence not found.
 */
public final class FolderDiagnosis {

    private static final Logger logger = LoggerFactory.getLogger(FolderDiagnosis.class);

    /** Depth matching the tile searches, so this sees what a stitch would see. */
    private static final int MAX_DEPTH = 3;

    /** {@code name[1234.5,678.9].tif} -- the Filename[x,y] method's coordinates. */
    private static final Pattern FILENAME_XY = Pattern.compile(".*\\[-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?].*");

    /**
     * One method whose evidence is present in the folder.
     *
     * @param method the dropdown identifier, exactly as {@code StitchingStrategyFactory} switches on it
     * @param evidence what was found, in the user's terms
     */
    public record Finding(String method, String evidence) {}

    private FolderDiagnosis() {}

    /**
     * @param folder the folder the user selected
     * @return every method whose evidence is present, or an empty list if none is
     */
    public static List<Finding> diagnose(File folder) {
        List<Finding> found = new ArrayList<>();
        if (folder == null || !folder.isDirectory()) {
            return found;
        }
        Path root = folder.toPath();

        List<Path> configs = scan(root, p -> p.getFileName().toString().equals("TileConfiguration.txt"));
        if (!configs.isEmpty()) {
            found.add(new Finding(
                    "Coordinates in TileConfiguration.txt file",
                    configs.size() == 1 ? "a TileConfiguration.txt" : configs.size() + " TileConfiguration.txt files"));
        }

        // MicroManager writes a sidecar per position in the flat layout, and one metadata.txt per
        // position folder in the single-plane layout. Either is enough to name the method.
        List<Path> mmMeta = scan(root, p -> {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return n.equals("metadata.txt") || n.endsWith("_metadata.txt");
        });
        if (!mmMeta.isEmpty()) {
            found.add(new Finding(
                    "MicroManager metadata (MMStack or TIFF series)",
                    mmMeta.size() + " MicroManager metadata file" + (mmMeta.size() == 1 ? "" : "s")));
        }

        List<Path> xy = scan(root, p -> {
            String n = p.getFileName().toString();
            return isTiff(n) && FILENAME_XY.matcher(n).matches();
        });
        if (!xy.isEmpty()) {
            found.add(new Finding(
                    "Filename[x,y] with coordinates in microns",
                    xy.size() + " file name" + (xy.size() == 1 ? "" : "s") + " carrying [x,y] coordinates"));
        }
        return found;
    }

    /**
     * @param folder the folder the user selected
     * @return how many TIFF files are anywhere beneath it; 0 means the folder holds no images
     */
    public static int countTiffs(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return 0;
        }
        return scan(folder.toPath(), p -> isTiff(p.getFileName().toString())).size();
    }

    private static boolean isTiff(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".tif") || n.endsWith(".tiff");
    }

    private static List<Path> scan(Path root, java.util.function.Predicate<Path> matches) {
        try {
            return FileScanner.find(root, MAX_DEPTH, matches);
        } catch (RuntimeException e) {
            // Diagnosis is a courtesy on a path that has already failed; it must never be the
            // reason the user sees nothing at all.
            logger.debug("Could not scan {} while diagnosing: {}", root, e.toString());
            return List.of();
        }
    }
}
