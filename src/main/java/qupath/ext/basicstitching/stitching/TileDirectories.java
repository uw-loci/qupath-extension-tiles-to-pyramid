package qupath.ext.basicstitching.stitching;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves which directories hold the tiles for one stitch, shared by every strategy that stitches
 * per-subdirectory (filename coordinates, TileConfiguration.txt, Vectra) and by the dialog, so the
 * dialog's preview of "what will be stitched" cannot disagree with the stitch itself.
 *
 * <p>Three cases, in the order a user meets them:
 *
 * <ul>
 *   <li><b>Blank</b> -- the selected folder itself, and only that folder. One output.
 *   <li><b>{@value #ALL_SUBFOLDERS}</b> -- every immediate subdirectory, one output each.
 *   <li><b>Any other text</b> -- the immediate subdirectories whose names contain it, one output
 *       each. Case-sensitive. QPSC relies on this, passing an angle or annotation name, or
 *       {@code "."}.
 * </ul>
 *
 * <p>The wildcard exists because "all of them" was otherwise unsayable. Selecting a set of channel
 * folders meant finding a substring they happened to share -- {@code DAPI}, {@code FITC} and
 * {@code TRITC} are selected by the letter {@code I}, which works and teaches nothing, and would
 * have failed on any other three channel names. Blank is not the answer either: it stitches the
 * parent as one folder, and because the tile search recurses, every channel's tiles land in the
 * same output, last one wins.
 */
public final class TileDirectories {

    /** Selects every immediate subdirectory. */
    public static final String ALL_SUBFOLDERS = "*";

    private TileDirectories() {}

    /** True when {@code matchingString} selects the root folder itself rather than subdirectories. */
    public static boolean isSingleFolder(String matchingString) {
        return matchingString == null || matchingString.isBlank();
    }

    /** True when {@code matchingString} selects every immediate subdirectory. */
    public static boolean isAllSubFolders(String matchingString) {
        return ALL_SUBFOLDERS.equals(matchingString == null ? null : matchingString.trim());
    }

    /**
     * @param root the selected folder
     * @param matchingString blank for {@code root} alone, {@value #ALL_SUBFOLDERS} for every
     *     immediate subdirectory, or text a subdirectory name must contain
     * @return the tile directories, sorted by name; empty when none match
     */
    public static List<Path> resolve(Path root, String matchingString) throws IOException {
        if (isSingleFolder(matchingString)) {
            return Files.isDirectory(root) ? List.of(root) : List.of();
        }
        boolean all = isAllSubFolders(matchingString);
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .filter(p -> all || p.getFileName().toString().contains(matchingString))
                    .sorted()
                    .toList();
        }
    }
}
