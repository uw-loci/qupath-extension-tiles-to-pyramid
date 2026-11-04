package qupath.ext.basicstitching.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Channel names read from a tile's OME-XML, so a stitched image keeps them. */
class ChannelNamesTest {

    private static String ome(String prefix, String... names) {
        StringBuilder sb = new StringBuilder(
                "<" + prefix + "OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">" + "<" + prefix
                        + "Image ID=\"Image:0\"><" + prefix + "Pixels ID=\"Pixels:0\" SizeC=\"" + names.length + "\">");
        for (int i = 0; i < names.length; i++) {
            sb.append("<")
                    .append(prefix)
                    .append("Channel ID=\"Channel:0:")
                    .append(i)
                    .append("\" Name=\"")
                    .append(names[i])
                    .append("\" SamplesPerPixel=\"1\" />");
        }
        return sb.append("</")
                .append(prefix)
                .append("Pixels></")
                .append(prefix)
                .append("Image></")
                .append(prefix)
                .append("OME>")
                .toString();
    }

    @Test
    void readsTheNamesInOrder() {
        assertEquals(
                List.of("Retardance_nm_x_100"),
                ChannelSemantics.channelNamesFromDescription(ome("", "Retardance_nm_x_100"), 1));
        assertEquals(
                List.of("State0", "State1"),
                ChannelSemantics.channelNamesFromDescription(ome("", "State0", "State1"), 2));
    }

    @Test
    void readsPrefixedElementsAndUnescapes() {
        assertEquals(List.of("A & B"), ChannelSemantics.channelNamesFromDescription(ome("ns0:", "A &amp; B"), 1));
    }

    @Test
    void ignoresMissingBlankOrMiscountedNames() {
        assertTrue(ChannelSemantics.channelNamesFromDescription(null, 1).isEmpty());
        assertTrue(
                ChannelSemantics.channelNamesFromDescription("ImageJ=1.54", 1).isEmpty());
        assertTrue(ChannelSemantics.channelNamesFromDescription(ome("", " "), 1).isEmpty());
        assertTrue(ChannelSemantics.channelNamesFromDescription(ome("", "a", "b"), 1)
                .isEmpty());
        assertTrue(
                ChannelSemantics.channelNames(new File("does-not-exist.tif"), 1).isEmpty());
    }
}
