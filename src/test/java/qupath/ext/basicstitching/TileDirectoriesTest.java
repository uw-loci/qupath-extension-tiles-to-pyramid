package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.TileDirectories;

class TileDirectoriesTest {

    @Test
    void blankMatchSelectsOnlyTheRootFolder(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve("DAPI"));
        Files.createDirectory(root.resolve("FITC"));

        assertEquals(List.of(root), TileDirectories.resolve(root, ""));
        assertEquals(List.of(root), TileDirectories.resolve(root, "   "));
        assertEquals(List.of(root), TileDirectories.resolve(root, null));
    }

    @Test
    void nonBlankMatchSelectsContainingSubdirectoriesSorted(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve("20x_FITC"));
        Files.createDirectory(root.resolve("20x_DAPI"));
        Files.createDirectory(root.resolve("10x_DAPI"));
        Files.createFile(root.resolve("20x_notes.txt"));

        assertEquals(List.of(root.resolve("20x_DAPI"), root.resolve("20x_FITC")), TileDirectories.resolve(root, "20x"));
    }

    @Test
    void dotStillMatchesSubdirectoriesContainingADot(@TempDir Path root) throws Exception {
        // QPSC passes "." and relies on it NOT meaning the root folder.
        Files.createDirectory(root.resolve("7.0"));
        Files.createDirectory(root.resolve("bounds"));

        assertEquals(List.of(root.resolve("7.0")), TileDirectories.resolve(root, "."));
    }
}
