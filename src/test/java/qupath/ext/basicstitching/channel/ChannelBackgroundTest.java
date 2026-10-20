package qupath.ext.basicstitching.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fill colour for areas no tile reaches: white for RGB unless the tile says black. */
class ChannelBackgroundTest {

    private static String ome(String key, String value) {
        return "<OME><StructuredAnnotations><MapAnnotation><Value><M K=\"" + key + "\">" + value
                + "</M></Value></MapAnnotation></StructuredAnnotations></OME>";
    }

    @Test
    void parsesTheDeclaration() {
        assertEquals(Boolean.FALSE, ChannelSemantics.declaredWhiteBackground(ome("qpsc.background", "black")));
        assertEquals(Boolean.TRUE, ChannelSemantics.declaredWhiteBackground(ome("qpsc.background", " White ")));
        assertNull(ChannelSemantics.declaredWhiteBackground(ome("qpsc.background", "grey")));
        assertNull(ChannelSemantics.declaredWhiteBackground(ome("qpsc.resample", "linear")));
        assertNull(ChannelSemantics.declaredWhiteBackground(null));
    }

    @Test
    void rgbTileDeclaringBlackIsFilledBlack(@TempDir Path dir) throws Exception {
        File tile = writeRgbTile(dir.resolve("0.tif").toFile(), ome("qpsc.background", "black"));
        assertFalse(ChannelSemantics.whiteBackground(tile, true));
    }

    @Test
    void undeclaredTilesKeepTheDefault(@TempDir Path dir) throws Exception {
        File tile = writeRgbTile(dir.resolve("0.tif").toFile(), ome("qpsc.resample", "linear"));
        assertTrue(ChannelSemantics.whiteBackground(tile, true));
        assertFalse(ChannelSemantics.whiteBackground(tile, false));
        assertTrue(ChannelSemantics.whiteBackground(dir.resolve("missing.tif").toFile(), true));
    }

    private static File writeRgbTile(File file, String description) throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata md = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
        TIFFDirectory tiffDir = TIFFDirectory.createFromMetadata(md);
        TIFFTag tag = BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_IMAGE_DESCRIPTION);
        tiffDir.addTIFFField(new TIFFField(tag, TIFFTag.TIFF_ASCII, 1, new String[] {description}));
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, tiffDir.getAsMetadata()), param);
        } finally {
            writer.dispose();
        }
        return file;
    }
}
