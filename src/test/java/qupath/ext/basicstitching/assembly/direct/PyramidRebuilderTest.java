package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PyramidRebuilderTest {

    @Test
    void eightBitLevelsAreDamaged() {
        assertTrue(PyramidRebuilder.judge(10_000, 10_000).damaged());
        assertTrue(PyramidRebuilder.judge(10_000, 9_500).damaged());
    }

    @Test
    void realSixteenBitDataIsNot() {
        // About 1 value in 257 lands on a multiple by chance.
        assertFalse(PyramidRebuilder.judge(10_000, 40).damaged());
    }

    @Test
    void tooLittleSignalIsUndecided() {
        assertFalse(PyramidRebuilder.judge(10, 10).damaged());
    }

    @Test
    void outputSitsBesideTheInput() {
        assertEquals(
                Path.of("/data/a_retardance_013_rebuilt.ome.tif"),
                PyramidRebuilder.defaultOutput(Path.of("/data/a_retardance_013.ome.tif")));
    }
}
