package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.functions.StitchingGUI;

/**
 * Merged channel names come from the files the stitch wrote, and the writer decorates those names:
 * "_2x_downsample" when the downsample is not 1, "_2" when the name is taken. Both would show in
 * QuPath's channel list, and the first stops "385" being recognized as a wavelength.
 */
class ChannelNameTest {

    private static String nameOf(String fileName) throws Exception {
        Method m = StitchingGUI.class.getDeclaredMethod("channelNameOf", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, fileName);
    }

    @Test
    void aPlainOutputKeepsItsName() throws Exception {
        assertEquals("DAPI", nameOf("DAPI.ome.tif"));
        assertEquals("385", nameOf("385.ome.tif"));
    }

    @Test
    void theDownsampleSuffixIsStripped() throws Exception {
        assertEquals("385", nameOf("385_2x_downsample.ome.tif"));
        assertEquals("TRITC", nameOf("TRITC_4x_downsample.ome.tif"));
    }

    @Test
    void theUniquenessCounterIsStripped() throws Exception {
        assertEquals("550", nameOf("550_2.ome.tif"));
        assertEquals("550", nameOf("550_2x_downsample_2.ome.tif"));
    }

    @Test
    void aChannelNamedOnlyByADigitSurvives() throws Exception {
        // Stripping "_2" must never leave nothing behind.
        assertEquals("2", nameOf("2.ome.tif"));
    }

    @Test
    void anObjectiveInTheNameIsNotADownsample() throws Exception {
        assertEquals("DAPI_20x", nameOf("DAPI_20x.ome.tif"));
    }
}
