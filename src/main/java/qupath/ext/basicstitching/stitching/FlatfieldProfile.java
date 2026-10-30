package qupath.ext.basicstitching.stitching;

import java.awt.image.Raster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * A flat-field profile fitted by {@code microscope_imageprocessing.correction.flatfield}:
 * {@code flatfield.tif} (float32, mean 1) and {@code profile.yml} in one folder.
 *
 * <p>The correction is {@code (raw - offset) / flatfield + offset}, as in the Python
 * {@code FlatfieldProfile.apply}. The offset is taken off first because a PMT's offset is not
 * shaded by the optics, and put back so the background keeps its value.
 */
public final class FlatfieldProfile {

    static final String FLATFIELD_FILE = "flatfield.tif";
    static final String PROFILE_FILE = "profile.yml";

    private final Path folder;
    private final int width;
    private final int height;
    private final double[] flatfield;
    private final double offset;
    private final Integer channel;
    private final String label;

    private FlatfieldProfile(
            Path folder, int width, int height, double[] flatfield, double offset, Integer channel, String label) {
        this.folder = folder;
        this.width = width;
        this.height = height;
        this.flatfield = flatfield;
        this.offset = offset;
        this.channel = channel;
        this.label = label;
    }

    /**
     * Reads a profile.
     *
     * @param folder the folder holding {@code flatfield.tif} and {@code profile.yml}
     * @return the profile
     * @throws IOException if either file is missing or unreadable
     */
    public static FlatfieldProfile load(Path folder) throws IOException {
        Path yaml = folder.resolve(PROFILE_FILE);
        Path image = folder.resolve(FLATFIELD_FILE);
        if (!Files.isRegularFile(yaml) || !Files.isRegularFile(image)) {
            throw new IOException(
                    folder + " is not a flat-field profile: it needs " + FLATFIELD_FILE + " and " + PROFILE_FILE);
        }
        String record = Files.readString(yaml, StandardCharsets.US_ASCII);
        String offsetText = scalar(record, "offset");
        if (offsetText == null) {
            throw new IOException(yaml + " has no offset");
        }
        String channelText = scalar(record, "channel");
        Integer channel = channelText == null || channelText.equals("null") ? null : Integer.valueOf(channelText);
        String label = scalar(record, "label");

        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            ImageReader reader = MicroManagerPreprocessor.tiffReader(in, image);
            try {
                reader.setInput(in, false, false);
                Raster raster = reader.read(0).getRaster();
                int w = raster.getWidth();
                int h = raster.getHeight();
                double[] values = raster.getSamples(raster.getMinX(), raster.getMinY(), w, h, 0, new double[w * h]);
                return new FlatfieldProfile(
                        folder, w, h, values, Double.parseDouble(offsetText), channel, label == null ? "" : label);
            } finally {
                reader.dispose();
            }
        } catch (NumberFormatException e) {
            throw new IOException(yaml + " has an offset that is not a number: " + offsetText, e);
        }
    }

    /** The value of a top-level {@code key: value} line, without quotes, or null. */
    private static String scalar(String yaml, String key) {
        Matcher m = Pattern.compile("^" + key + ":\\s*(.*?)\\s*$", Pattern.MULTILINE)
                .matcher(yaml);
        return m.find() ? m.group(1).replaceAll("^['\"]|['\"]$", "") : null;
    }

    /** @return true when this profile was fitted for the channel, or for every plane */
    boolean appliesTo(int planeChannel) {
        return channel == null || channel == planeChannel;
    }

    /**
     * Corrects a raw or averaged plane in place. Rounding is left to whoever writes the plane, so
     * it happens once.
     *
     * @throws IOException if the plane is not the profile's size
     */
    void correct(double[] plane, int planeWidth, int planeHeight) throws IOException {
        if (planeWidth != width || planeHeight != height) {
            throw new IOException("The flat-field profile is " + width + " x " + height + " but the planes are "
                    + planeWidth + " x " + planeHeight + ": it was fitted for another scan size");
        }
        for (int i = 0; i < plane.length; i++) {
            plane[i] = (plane[i] - offset) / flatfield[i] + offset;
        }
    }

    /** @return the profile in words, for records */
    public String describe() {
        String what = label.isEmpty() ? folder.toString() : label + " (" + folder + ")";
        return "flat-field " + what + ", offset " + offset + (channel == null ? "" : ", channel " + channel);
    }
}
