package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;

/**
 * End-to-end checks that the blending choice actually reaches the compositor.
 *
 * <p>{@code ChunkCompositorBlendTest} proves the blend arithmetic against a compositor built by
 * hand. That says nothing about whether a real stitch ever passes the choice down -- and the whole
 * feature had been constructed, handed to the compositor, and never called for exactly that kind of
 * reason. These run the workflow the dialog and QPSC both run.
 */
class BlendWiringEndToEndTest {

    @TempDir
    Path tempDir;

    private static final int TILE_W = 128;
    private static final int TILE_H = 128;

    private StitchingConfig config(Path tiles, Path out) {
        return new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tiles.toString(),
                out.toString(),
                "LZW",
                // 1.0 so the fixture's TileConfiguration coordinates, which are already in pixels,
                // pass through the strategy's micron-to-pixel division unchanged. At any other value
                // the tiles land further apart than they were written and stop overlapping, which
                // leaves nothing for a feather to act on.
                1.0,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
    }

    @Test
    void aFeatheredStitchProducesADifferentImageFromTheDefault() throws IOException {
        // Two runs over the same tiles, differing only in the blend. If the choice were dropped
        // anywhere between the config and the compositor -- which is what happened to this whole
        // feature before -- the two outputs would be identical and this fails.
        Path tiles = Files.createDirectory(tempDir.resolve("tiles"));
        SyntheticGridFixture.write(tiles, 3, 3, TILE_W, TILE_H, 0.2, 1.0, 3);

        byte[] sharp = stitchBytes(tiles, "sharp", OverlapBlend.LAST_WINS);
        byte[] feathered = stitchBytes(tiles, "feathered", OverlapBlend.LINEAR_FEATHER);

        assertFalse(
                java.util.Arrays.equals(sharp, feathered),
                "the feathered stitch must differ from the hard-cut one, or the choice never reached the compositor");
    }

    @Test
    void anUnsetBlendResolvesWithoutPreferencesAvailable() {
        // The workflow fills an unset blend from QuPath's preference pane, which does not exist in a
        // headless run. That lookup is guarded; this pins the guard, because the failure mode would
        // otherwise be a stitch that dies on a cosmetic setting.
        StitchingConfig config = config(tempDir, tempDir);

        assertFalse(config.isOverlapBlendSet(), "a fresh config must not claim a choice it was never given");
        assertEquals(
                OverlapBlend.LAST_WINS,
                config.getOverlapBlend(),
                "and must read as the hard cut until something sets it");

        // No tiles, so this returns empty rather than stitching -- the point is that resolving the
        // preference on the way there does not throw.
        assertDoesNotThrow(() -> StitchingWorkflow.runDetailed(config));
        assertTrue(config.isOverlapBlendSet(), "the workflow must resolve the blend even when it falls back");
    }

    private byte[] stitchBytes(Path tiles, String name, OverlapBlend blend) throws IOException {
        Path out = Files.createDirectory(tempDir.resolve("out-" + name));
        StitchingConfig config = config(tiles, out);
        config.setOutputFilename(name);
        config.setOverlapBlend(blend);

        String written = StitchingWorkflow.run(config);
        assertNotNull(written, "stitch '" + name + "' produced no output");
        return Files.readAllBytes(Path.of(written));
    }
}
