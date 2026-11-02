package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.functions.StitchingGUI;

/**
 * When the dialog offers the channel merge, and what it says when the stitch did not produce one
 * image per channel. Until 2026-11 a merge that had nothing to merge was skipped without a word,
 * and the offer was hidden for channel folders that keep their z-slices and time points in
 * sub-folders.
 */
class MergeOfferTest {

    private static String shortfall(boolean merge, int expected, int outputs) throws Exception {
        Method m = StitchingGUI.class.getDeclaredMethod("mergeShortfall", boolean.class, int.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, merge, expected, outputs);
    }

    private static File firstTiff(Path dir) throws Exception {
        Method m = StitchingGUI.class.getDeclaredMethod("firstTiff", Path.class);
        m.setAccessible(true);
        return (File) m.invoke(null, dir);
    }

    @Test
    void oneImageForThreeChannelsIsReported() throws Exception {
        String s = shortfall(true, 3, 1);
        assertNotNull(s);
        assertTrue(s.startsWith("Channels not merged: the acquisition has 3 channels"), s);
        assertTrue(s.contains("produced 1 image,"), s);
    }

    @Test
    void aMissingChannelIsReportedEvenWhenTheRestMerge() throws Exception {
        assertEquals(
                "Only 2 of the 3 channels were stitched, so the merged image has 2 channels.", shortfall(true, 3, 2));
    }

    @Test
    void nothingIsSaidWhenEveryChannelArrivedOrNoMergeWasAsked() throws Exception {
        assertNull(shortfall(true, 3, 3));
        assertNull(shortfall(false, 3, 1));
    }

    @Test
    void aChannelFolderWithItsTilesInTimeAndZFoldersIsFound(@TempDir Path tmp) throws Exception {
        Path deep = Files.createDirectories(tmp.resolve("DAPI/t000/z000"));
        Files.writeString(tmp.resolve("DAPI/TileConfiguration.txt"), "dim = 2\n");
        Files.write(deep.resolve("tile_0.tif"), new byte[] {0});
        assertEquals(deep.resolve("tile_0.tif").toFile(), firstTiff(tmp.resolve("DAPI")));
    }

    @Test
    void aTileAtTheTopOfTheFolderIsPreferred(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("DAPI/t000/z000"));
        Files.write(dir.resolve("a.tif"), new byte[] {0});
        Files.write(tmp.resolve("DAPI/tile_9.tif"), new byte[] {0});
        assertEquals(tmp.resolve("DAPI/tile_9.tif").toFile(), firstTiff(tmp.resolve("DAPI")));
    }
}
