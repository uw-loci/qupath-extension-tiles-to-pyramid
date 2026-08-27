package qupath.ext.basicstitching.channel;

import java.io.File;
import java.io.FileInputStream;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a tile's declared resampling policy from its OME metadata.
 *
 * <p>The writing half lives in {@code microscope_imageprocessing.io.channel_semantics}, which
 * emits the keys as an OME {@code MapAnnotation} inside the TIFF's ImageDescription. Only two
 * keys are read here, by regex rather than by parsing the whole OME-XML: the values are simple
 * text and a stitcher should not fail to stitch because a schema detail moved.
 *
 * <p>Anything unreadable resolves to {@link ResamplePolicy#LINEAR}, matching a tile that declares
 * nothing. That is deliberate: every tile written before this convention existed is continuous,
 * and treating an ordinary channel as non-combinable would quietly degrade every pyramid built
 * from it.
 */
public final class ChannelSemantics {

    private static final Logger logger = LoggerFactory.getLogger(ChannelSemantics.class);

    private static final Pattern RESAMPLE =
            Pattern.compile("<M\\s+K=\"qpsc\\.resample\"\\s*>([^<]*)</M>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD =
            Pattern.compile("<M\\s+K=\"qpsc\\.resample_period\"\\s*>([^<]*)</M>", Pattern.CASE_INSENSITIVE);

    private ChannelSemantics() {}

    /** A tile's declared handling: its policy and, for angular policies, its period. */
    public record Declaration(ResamplePolicy policy, double period) {

        /** Whether this channel can actually be averaged circularly. */
        public boolean canAverageCircularly() {
            return policy.isAngular() && period > 0;
        }
    }

    /** The default for a tile that declares nothing, or that could not be read. */
    public static final Declaration LINEAR = new Declaration(ResamplePolicy.LINEAR, 0);

    /**
     * Read the declaration from a TIFF tile.
     *
     * @param file tile to inspect; may be null
     * @return the declaration, never null
     */
    public static Declaration read(File file) {
        if (file == null || !file.exists()) {
            return LINEAR;
        }
        String description = readImageDescription(file);
        if (description == null) {
            return LINEAR;
        }
        Matcher m = RESAMPLE.matcher(description);
        if (!m.find()) {
            return LINEAR;
        }
        ResamplePolicy policy = ResamplePolicy.fromDeclared(m.group(1));
        if (policy == ResamplePolicy.UNKNOWN) {
            logger.warn(
                    "Tile {} declares resample policy '{}', which this build does not recognise. "
                            + "Treating it as non-combinable so the data is preserved rather than averaged.",
                    file.getName(),
                    m.group(1).trim());
        }
        double period = 0;
        Matcher p = PERIOD.matcher(description);
        if (p.find()) {
            try {
                period = Double.parseDouble(p.group(1).trim());
            } catch (NumberFormatException e) {
                logger.warn("Tile {} has an unparseable qpsc.resample_period '{}'", file.getName(), p.group(1));
            }
        }
        if (policy.isAngular() && period <= 0) {
            logger.warn(
                    "Tile {} declares angular policy '{}' but no usable period, so its angles "
                            + "cannot be recovered from the stored counts. Falling back to "
                            + "nearest-neighbour, which is safe but loses the correct averaging.",
                    file.getName(),
                    policy);
        }
        return new Declaration(policy, period);
    }

    private static String readImageDescription(File file) {
        try (FileInputStream fis = new FileInputStream(file);
                ImageInputStream iis = ImageIO.createImageInputStream(fis)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("TIFF");
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                IIOMetadata metadata = reader.getImageMetadata(0);
                TIFFDirectory dir = TIFFDirectory.createFromMetadata(metadata);
                TIFFField field = dir.getTIFFField(BaselineTIFFTagSet.TAG_IMAGE_DESCRIPTION);
                return field == null ? null : field.getAsString(0);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            logger.debug("Could not read ImageDescription from {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }
}
