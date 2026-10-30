package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compositor's downsampled read keeps 16-bit values. It used Graphics2D, which renders
 * USHORT_GRAY through 8 bits: every first-level pyramid value of a 16-bit stitch came out as
 * a multiple of 257, and a retardance image of a few hundred counts went to zero.
 */
class CompositorDownscaleTest {

    private static BufferedImage ushort(int w, int h, int... values) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        img.getRaster().setPixels(0, 0, w, h, values);
        return img;
    }

    @Test
    @DisplayName("small 16-bit values survive a 2x downsample (were rounded to 0 or 257)")
    void smallValuesSurvive() {
        BufferedImage src = ushort(2, 2, 100, 200, 300, 400);
        BufferedImage out = CompositorImageServer.areaAverage(src, 1, 1);
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, out.getType());
        assertEquals(250, out.getRaster().getSample(0, 0, 0));
    }

    @Test
    @DisplayName("each output pixel is the mean of the block it covers")
    void blockMeans() {
        BufferedImage src = ushort(4, 2, 0, 2, 1000, 1002, 4, 6, 1004, 1006);
        WritableRaster out = CompositorImageServer.areaAverage(src, 2, 1).getRaster();
        assertEquals(3, out.getSample(0, 0, 0));
        assertEquals(1003, out.getSample(1, 0, 0));
    }

    @Test
    @DisplayName("odd sizes and the full 16-bit range")
    void oddSizesAndFullRange() {
        BufferedImage src = ushort(3, 1, 65535, 65535, 65535);
        WritableRaster out = CompositorImageServer.areaAverage(src, 2, 1).getRaster();
        assertEquals(65535, out.getSample(0, 0, 0));
        assertEquals(65535, out.getSample(1, 0, 0));
    }

    @Test
    @DisplayName("zero as no data: an edge block keeps the tissue value instead of fading to black")
    void zeroIsLeftOutOfTheMean() {
        // Half canvas (0), half background (6800): plain averaging gives 3400, a value no pixel had.
        BufferedImage src = ushort(2, 2, 0, 6800, 0, 6800);
        assertEquals(
                3400, CompositorImageServer.areaAverage(src, 1, 1).getRaster().getSample(0, 0, 0));
        assertEquals(
                6800,
                CompositorImageServer.areaAverage(src, 1, 1, true).getRaster().getSample(0, 0, 0));
    }

    @Test
    @DisplayName("zero as no data: an all-zero block stays zero")
    void emptyStaysEmpty() {
        BufferedImage src = ushort(2, 2, 0, 0, 0, 0);
        assertEquals(
                0,
                CompositorImageServer.areaAverage(src, 1, 1, true).getRaster().getSample(0, 0, 0));
    }
}
