package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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

    @Test
    void asteriskSelectsEverySubFolder(@TempDir Path root) throws IOException {
        // The case the letter "I" was covering for: three channel folders sharing no useful text.
        Files.createDirectories(root.resolve("DAPI"));
        Files.createDirectories(root.resolve("FITC"));
        Files.createDirectories(root.resolve("TRITC"));
        Files.writeString(root.resolve("notAFolder.txt"), "x");

        assertEquals(
                List.of(root.resolve("DAPI"), root.resolve("FITC"), root.resolve("TRITC")),
                TileDirectories.resolve(root, "*"));
    }

    @Test
    void asteriskIsRecognizedEvenWithStrayWhitespace(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a"));
        assertTrue(TileDirectories.isAllSubFolders(" * "));
        assertEquals(List.of(root.resolve("a")), TileDirectories.resolve(root, " * "));
    }

    @Test
    void asteriskIsNotTreatedAsOrdinaryText(@TempDir Path root) throws IOException {
        // No folder is named "*", so a literal contains() match would return nothing.
        Files.createDirectories(root.resolve("plain"));
        assertFalse(TileDirectories.resolve(root, "*").isEmpty());
    }

    @Test
    void blankStillMeansTheSelectedFolderAlone(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("DAPI"));
        assertTrue(TileDirectories.isSingleFolder(""));
        assertFalse(TileDirectories.isAllSubFolders(""));
        assertEquals(List.of(root), TileDirectories.resolve(root, ""));
    }
}
