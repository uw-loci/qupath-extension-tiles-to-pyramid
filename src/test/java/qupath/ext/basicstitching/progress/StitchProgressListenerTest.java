package qupath.ext.basicstitching.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;

/** A real stitch reports its phases, in order, to the config's listener, and ends at 100 %. */
class StitchProgressListenerTest {

    @TempDir
    Path dir;

    @Test
    void aStitchReportsScanThenWriteToTheEnd() throws Exception {
        Path sub = Files.createDirectories(dir.resolve("tiles").resolve("s"));
        Random random = new Random(3);
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        int tile = 300, step = 270;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                BufferedImage img = new BufferedImage(tile, tile, BufferedImage.TYPE_BYTE_GRAY);
                for (int y = 0; y < tile; y++) {
                    for (int x = 0; x < tile; x++) {
                        img.getRaster().setSample(x, y, 0, 1 + random.nextInt(250));
                    }
                }
                String name = "tile_" + row + "_" + col + ".tif";
                ImageIO.write(img, "tif", sub.resolve(name).toFile());
                cfg.append(name)
                        .append("; ; (")
                        .append(col * step)
                        .append(", ")
                        .append(row * step)
                        .append(")\n");
            }
        }
        Files.writeString(sub.resolve("TileConfiguration.txt"), cfg.toString());
        Path out = Files.createDirectories(dir.resolve("out"));

        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                dir.resolve("tiles").toString(),
                out.toString(),
                "LZW",
                1.0,
                1.0,
                "s",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        List<StitchProgress> seen = Collections.synchronizedList(new ArrayList<>());
        config.setProgressListener(seen::add);

        String written = StitchingWorkflow.run(config);
        assertNotNull(written);
        assertTrue(seen.size() >= 2, "updates: " + seen.size());
        assertEquals(StitchPhase.SCAN, seen.get(0).phase());
        StitchProgress last = seen.get(seen.size() - 1);
        assertEquals(StitchPhase.WRITE, last.phase());
        assertEquals(1.0, last.phaseFraction(), 1e-9);
        assertEquals("s", last.target());
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i).phase().ordinal() >= seen.get(i - 1).phase().ordinal(), "phases in order");
            assertTrue(Double.isFinite(seen.get(i).remainingSeconds()), "an estimate at every update");
        }
    }
}
