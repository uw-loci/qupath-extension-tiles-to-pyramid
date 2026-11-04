package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.ImageRegion;

/**
 * The stitched file keeps the channel name its tiles carry, so any project that opens it sees
 * what the pixel values mean (here retardance in nm x 100), not "Channel 0".
 */
class StitchedChannelNameTest {

    private static final int TILE = 32;

    @Test
    void namedTilesGiveANamedStitchedFile(@TempDir Path dir) throws Exception {
        assertEquals("Retardance_nm_x_100", stitchAndReadChannelName(dir, "Retardance_nm_x_100"));
    }

    @Test
    void unnamedTilesKeepTheDefault(@TempDir Path dir) throws Exception {
        assertEquals("Channel 0", stitchAndReadChannelName(dir, null));
    }

    private static String stitchAndReadChannelName(Path dir, String name) throws Exception {
        String description = name == null
                ? null
                : "<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\"><Image ID=\"Image:0\">"
                        + "<Pixels ID=\"Pixels:0\" SizeC=\"1\"><Channel ID=\"Channel:0:0\" Name=\"" + name
                        + "\" SamplesPerPixel=\"1\" /></Pixels></Image></OME>";
        List<TileMapping> mappings = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                File f = dir.resolve("tile_" + row + "_" + col + ".tif").toFile();
                writeGrayTile(f, 40 + 10 * (row * 2 + col), description);
                mappings.add(
                        new TileMapping(f, ImageRegion.createInstance(col * TILE, row * TILE, TILE, TILE, 0, 0), "."));
            }
        }
        StitchingConfig config = new StitchingConfig(
                "filename",
                dir.toString(),
                dir.toString(),
                "LZW",
                1.0,
                1.0,
                ".",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        String out = DirectTileStitcher.stitch(mappings, dir.toString(), "named", config, null);
        assertNotNull(out, "stitch produced no file");
        try (ImageServer<BufferedImage> read =
                ImageServers.buildServer(Path.of(out).toUri())) {
            return read.getChannel(0).getName();
        }
    }

    private static void writeGrayTile(File file, int value, String description) throws Exception {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                img.getRaster().setSample(x, y, 0, value);
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata md = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
        if (description != null) {
            TIFFDirectory tiffDir = TIFFDirectory.createFromMetadata(md);
            TIFFTag tag = BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_IMAGE_DESCRIPTION);
            tiffDir.addTIFFField(new TIFFField(tag, TIFFTag.TIFF_ASCII, 1, new String[] {description}));
            md = tiffDir.getAsMetadata();
        }
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, md), param);
        } finally {
            writer.dispose();
        }
    }
}
