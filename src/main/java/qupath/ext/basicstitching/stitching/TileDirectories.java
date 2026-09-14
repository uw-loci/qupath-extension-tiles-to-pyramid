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
 * <p>A blank matching string means the selected folder itself, and only that folder. A non-blank
 * one selects the immediate subdirectories whose names contain it (QPSC relies on this: it passes
 * an angle or annotation name, or {@code "."}).
 */
public final class TileDirectories {

    private TileDirectories() {}

    /** True when {@code matchingString} selects the root folder itself rather than subdirectories. */
    public static boolean isSingleFolder(String matchingString) {
        return matchingString == null || matchingString.isBlank();
    }

    /**
     * @param root           the selected folder
     * @param matchingString text a subdirectory name must contain; blank selects {@code root} alone
     * @return the tile directories, sorted by name; empty when none match
     */
    public static List<Path> resolve(Path root, String matchingString) throws IOException {
        if (isSingleFolder(matchingString)) {
            return Files.isDirectory(root) ? List.of(root) : List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains(matchingString))
                    .sorted()
                    .toList();
        }
    }
}
