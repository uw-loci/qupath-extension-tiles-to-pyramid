package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.assembly.ChannelMergeImageServer;

/**
 * A merged image whose channels are all grey opens in QuPath as grayscale however many channels it
 * has, which is what happened: each single-channel source reports near-white (#FFFDFE).
 */
class ChannelColorTest {

    private static Integer colorFor(String name, int index) throws Exception {
        Method m = ChannelMergeImageServer.class.getDeclaredMethod("defaultColorFor", String.class, int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, name, index);
    }

    private static boolean grey(Integer color) throws Exception {
        Method m = ChannelMergeImageServer.class.getDeclaredMethod("isGrey", Integer.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, color);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    @Test
    void nearWhiteCountsAsNoColor() throws Exception {
        assertTrue(grey(null));
        assertTrue(grey(0xFFFFFF));
        assertTrue(grey(0xFFFDFE), "what a single-channel OME-TIFF actually reports");
        assertTrue(grey(0x808080));
    }

    @Test
    void realColorsAreLeftAlone() throws Exception {
        assertFalse(grey(rgb(255, 0, 0)));
        assertFalse(grey(rgb(0, 255, 0)));
        assertFalse(grey(rgb(255, 0, 255)));
    }

    @Test
    void knownFluorophoresGetTheirConventionalColor() throws Exception {
        assertEquals(rgb(0, 0, 255), colorFor("DAPI", 0));
        assertEquals(rgb(0, 255, 0), colorFor("FITC", 1));
        assertEquals(rgb(255, 0, 0), colorFor("TRITC", 2));
        assertEquals(rgb(255, 0, 255), colorFor("Cy5", 3));
    }

    @Test
    void nameMatchingBeatsPosition() throws Exception {
        // DAPI stitched third must still be blue, not whatever index 2 happens to be.
        assertEquals(rgb(0, 0, 255), colorFor("DAPI", 2));
    }

    @Test
    void bareWavelengthsAreReadAsExcitation() throws Exception {
        assertEquals(rgb(0, 0, 255), colorFor("385", 0));
        assertEquals(rgb(0, 255, 0), colorFor("475", 1));
        assertEquals(rgb(255, 0, 0), colorFor("550", 2));
        assertEquals(rgb(255, 0, 255), colorFor("621", 3));
    }

    @Test
    void numbersOutsideTheOpticalRangeAreNotWavelengths() throws Exception {
        // Falls through to QuPath's palette rather than inventing a color.
        assertEquals(qupath.lib.images.servers.ImageChannel.getDefaultChannelColor(0), colorFor("12", 0));
        assertEquals(qupath.lib.images.servers.ImageChannel.getDefaultChannelColor(1), colorFor("9000", 1));
    }

    @Test
    void anUnrecognizedNameStillGetsADistinctColor() throws Exception {
        Integer a = colorFor("myStain", 0);
        Integer b = colorFor("otherStain", 1);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b, "channels must be tellable apart even when we cannot name them");
    }
}
