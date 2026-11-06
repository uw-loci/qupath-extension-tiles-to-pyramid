package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.H;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.TILE;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.W;
import static qupath.ext.basicstitching.assembly.direct.PyramidTestFiles.mosaic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/** The writer's progress counts bytes, so full resolution is most of it, as it is of the time. */
class WriteProgressTest {

    @TempDir
    Path dir;

    @Test
    void progressIsWeightedByBytesNotTiles() throws Exception {
        List<Double> seen = new ArrayList<>();
        DirectTiffOutputWriter.write(
                new WrappedBufferedImageServer("m", mosaic(9)),
                dir.resolve("p.ome.tif").toString(),
                OMEPyramidWriter.CompressionType.LZW,
                TILE,
                1.0,
                seen::add,
                null,
                d -> Long.MAX_VALUE / 4);
        int fullResTiles = ((W + TILE - 1) / TILE) * ((H + TILE - 1) / TILE);
        double[] ds = DirectTiffOutputWriter.computePyramidDownsamples(W, H, 1.0, TILE);
        double expected = (double) W * H / DirectTiffOutputWriter.pyramidBytes(W, H, 1, 1, ds);
        assertEquals(expected, seen.get(fullResTiles - 1), 1e-9, "after full resolution");
        assertEquals(1.0, seen.get(seen.size() - 1), 1e-12);
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) >= seen.get(i - 1), "monotonic");
        }
        assertTrue(expected > 0.7, "full resolution is most of a pyramid: " + expected);
    }
}
