package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.FolderDiagnosis;

/**
 * A stitch that finds no tiles has to be able to say why. The case that matters is the one a user
 * actually hits: the method is remembered between runs, so a MicroManager folder gets pointed at
 * whichever method was used last.
 */
class FolderDiagnosisTest {

    private static void touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    void microManagerFolderNamesTheMicroManagerMethod(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("acq_MMStack_Pos-0_000.ome.tif"));
        touch(tmp.resolve("acq_MMStack_Pos-0_000_metadata.txt"));

        List<FolderDiagnosis.Finding> found = FolderDiagnosis.diagnose(tmp.toFile());

        assertEquals(1, found.size(), "only MicroManager evidence is present");
        assertEquals(
                "MicroManager metadata (MMStack or TIFF series)", found.get(0).method());
        assertTrue(
                found.get(0).evidence().contains("MicroManager"), found.get(0).evidence());
    }

    @Test
    void tileConfigurationFolderNamesItsOwnMethod(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("TileConfiguration.txt"));
        touch(tmp.resolve("0.tif"));

        List<FolderDiagnosis.Finding> found = FolderDiagnosis.diagnose(tmp.toFile());

        assertEquals(1, found.size());
        assertEquals("Coordinates in TileConfiguration.txt file", found.get(0).method());
    }

    @Test
    void coordinatesInFilenamesAreRecognized(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("tile[1000.0,2000.0].tif"));
        touch(tmp.resolve("tile[1500.0,2000.0].tif"));

        List<FolderDiagnosis.Finding> found = FolderDiagnosis.diagnose(tmp.toFile());

        assertEquals(1, found.size());
        assertEquals("Filename[x,y] with coordinates in microns", found.get(0).method());
        assertTrue(
                found.get(0).evidence().contains("2 file names"), found.get(0).evidence());
    }

    @Test
    void evidenceIsFoundInSubFolders(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("DAPI").resolve("TileConfiguration.txt"));
        touch(tmp.resolve("FITC").resolve("TileConfiguration.txt"));

        List<FolderDiagnosis.Finding> found = FolderDiagnosis.diagnose(tmp.toFile());

        assertEquals(1, found.size());
        assertTrue(
                found.get(0).evidence().contains("2 TileConfiguration.txt"),
                found.get(0).evidence());
    }

    @Test
    void evidenceOneLevelDownIsReportedAsSuch(@TempDir Path tmp) throws IOException {
        // Selecting an acquisition's parent instead of the acquisition: the method is right, the
        // folder is not, and the stitch fails exactly as it would for the wrong method.
        touch(tmp.resolve("bounds").resolve("TileConfiguration.txt"));
        touch(tmp.resolve("bounds").resolve("DAPI").resolve("TileConfiguration.txt"));

        FolderDiagnosis.Finding f = FolderDiagnosis.diagnose(tmp.toFile()).get(0);

        assertTrue(f.onlyInSubFolders(), "nothing sits in the selected folder itself");
        assertEquals(List.of("bounds", "bounds/DAPI"), f.subFolders());
    }

    @Test
    void evidenceInTheSelectedFolderIsNotReportedAsASubFolder(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("TileConfiguration.txt"));
        touch(tmp.resolve("sub").resolve("TileConfiguration.txt"));

        FolderDiagnosis.Finding f = FolderDiagnosis.diagnose(tmp.toFile()).get(0);

        assertFalse(f.onlyInSubFolders(), "one of them is in the selected folder, so the folder is right");
        assertTrue(f.subFolders().isEmpty());
    }

    @Test
    void anEmptyFolderIsReportedAsHavingNoImages(@TempDir Path tmp) {
        assertEquals(0, FolderDiagnosis.countTiffs(tmp.toFile()));
        assertTrue(FolderDiagnosis.diagnose(tmp.toFile()).isEmpty());
    }

    @Test
    void imagesWithoutAnyPositionSourceYieldNoFinding(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("a.tif"));
        touch(tmp.resolve("b.tif"));

        assertEquals(2, FolderDiagnosis.countTiffs(tmp.toFile()));
        assertTrue(
                FolderDiagnosis.diagnose(tmp.toFile()).isEmpty(),
                "no method's evidence is present, so none should be suggested");
    }

    @Test
    void aMissingFolderIsNotAnError() {
        assertTrue(FolderDiagnosis.diagnose(new java.io.File("no/such/folder")).isEmpty());
        assertEquals(0, FolderDiagnosis.countTiffs(new java.io.File("no/such/folder")));
    }
}
