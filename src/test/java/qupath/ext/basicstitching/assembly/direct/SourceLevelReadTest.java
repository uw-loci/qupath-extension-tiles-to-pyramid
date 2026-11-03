package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.assertSameLevels;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.mosaic;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.open;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.write;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.WrappedBufferedImageServer;

/**
 * A level the source already stores is read from it, and gives the same pixels as making it
 * again from full resolution with zeros left out.
 */
class SourceLevelReadTest {

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void noTileCache() {
        ImageServerProvider.setCache(null, BufferedImage.class);
    }

    @Test
    void levelsReadFromTheSourceEqualLevelsMadeAgain() throws Exception {
        Path stitched = write(new WrappedBufferedImageServer("mosaic", mosaic(1)), dir, "stitched");
        Path copied;
        try (ImageServer<BufferedImage> file = open(stitched)) {
            assertEquals(4, file.nResolutions());
            for (int level = 1; level < 4; level++) {
                assertTrue(NoDataPyramidServer.storesLevel(file, file.getDownsampleForResolution(level)));
            }
            copied = write(file, dir, "copied");
        }
        // FullResolutionServer hides the stored levels, so every level is made again.
        Path remade;
        try (ImageServer<BufferedImage> full = new FullResolutionServer(open(stitched))) {
            remade = write(full, dir, "remade");
        }
        assertSameLevels(copied, 0, remade, 0);
        assertSameLevels(copied, 0, stitched, 0);
    }
}
