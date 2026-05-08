package qupath.ext.basicstitching.assembly.direct;

import com.bc.zarr.ArrayParams;
import com.bc.zarr.Compressor;
import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes OME-ZARR output directly using JZarr, bypassing QuPath's OMEZarrWriter
 * and SparseImageServer. Produces NGFF 0.4 compliant metadata.
 * <p>
 * The output format matches what QuPath's OMEZarrWriter produces:
 * <pre>
 * filename.ome.zarr/
 *     .zgroup
 *     .zattrs          (NGFF 0.4 multiscales + omero metadata)
 *     s0/              (level 0 - full resolution)
 *         .zarray
 *         .zattrs
 *         chunk files...
 *     s1/              (level 1 - 2x downsample)
 *     ...
 * </pre>
 */
public class ZarrOutputWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ZarrOutputWriter.class);

    private final Path outputPath;
    private final Compressor compressor;
    private ZarrGroup rootGroup;
    private ZarrArray[] levelArrays;
    private int imageWidth;
    private int imageHeight;
    private int nChannels;
    private boolean isRGB;
    private int bitDepth;
    private int chunkSize;

    /**
     * Create a ZARR writer.
     *
     * @param outputPath Full path for the .ome.zarr directory
     * @param compressor JZarr compressor (from PyramidImageWriter.createZarrCompressor)
     */
    public ZarrOutputWriter(String outputPath, Compressor compressor) {
        this.outputPath = Paths.get(outputPath);
        this.compressor = compressor;
    }

    /**
     * Initialize the ZARR structure: create group, arrays for all pyramid levels,
     * and write NGFF 0.4 metadata.
     *
     * @param imageWidth Full image width in pixels
     * @param imageHeight Full image height in pixels
     * @param nChannels Number of channels (3 for RGB, 1 for grayscale)
     * @param isRGB Whether the image is RGB
     * @param bitDepth Bits per sample (8 or 16)
     * @param pixelSizeMicrons Pixel size for coordinate metadata
     * @param zSpacingMicrons Z-spacing for coordinate metadata
     * @param chunkSize Chunk size in pixels (typically 1024)
     * @param numPyramidLevels Number of pyramid levels to create
     */
    public void initialize(
            int imageWidth,
            int imageHeight,
            int nChannels,
            boolean isRGB,
            int bitDepth,
            double pixelSizeMicrons,
            double zSpacingMicrons,
            int chunkSize,
            int numPyramidLevels)
            throws IOException {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.nChannels = nChannels;
        this.isRGB = isRGB;
        this.bitDepth = bitDepth;
        this.chunkSize = chunkSize;

        logger.info(
                "Initializing ZARR writer: {}x{}, {} channels, {} bit, {} levels, chunk={}",
                imageWidth,
                imageHeight,
                nChannels,
                bitDepth,
                numPyramidLevels,
                chunkSize);

        // Create root group
        rootGroup = ZarrGroup.create(outputPath);

        // Determine data type
        DataType dataType = (bitDepth > 8) ? DataType.u2 : DataType.u1;

        // Create arrays for each pyramid level
        levelArrays = new ZarrArray[numPyramidLevels];

        for (int level = 0; level < numPyramidLevels; level++) {
            int scale = 1 << level;
            int levelW = Math.max(1, imageWidth / scale);
            int levelH = Math.max(1, imageHeight / scale);
            int levelChunkW = Math.min(chunkSize, levelW);
            int levelChunkH = Math.min(chunkSize, levelH);

            int[] shape;
            int[] chunks;
            if (nChannels > 1) {
                shape = new int[] {nChannels, levelH, levelW};
                chunks = new int[] {nChannels, levelChunkH, levelChunkW};
            } else {
                shape = new int[] {levelH, levelW};
                chunks = new int[] {levelChunkH, levelChunkW};
            }

            ArrayParams params = new ArrayParams()
                    .shape(shape)
                    .chunks(chunks)
                    .dataType(dataType)
                    .compressor(compressor)
                    .fillValue(0);

            Path arrayPath = outputPath.resolve("s" + level);
            levelArrays[level] = ZarrArray.create(arrayPath, params);

            // Write array dimension attributes
            Map<String, Object> arrayAttrs = new LinkedHashMap<>();
            if (nChannels > 1) {
                arrayAttrs.put("_ARRAY_DIMENSIONS", List.of("c", "y", "x"));
            } else {
                arrayAttrs.put("_ARRAY_DIMENSIONS", List.of("y", "x"));
            }
            levelArrays[level].writeAttributes(arrayAttrs);

            logger.debug(
                    "Created level {} array: shape={}, chunks={}",
                    level,
                    Arrays.toString(shape),
                    Arrays.toString(chunks));
        }

        // Write NGFF 0.4 metadata to root .zattrs
        writeNGFFMetadata(pixelSizeMicrons, numPyramidLevels);

        logger.info("ZARR structure initialized at: {}", outputPath);
    }

    /**
     * Write NGFF 0.4 multiscales and omero metadata to root .zattrs.
     */
    private void writeNGFFMetadata(double pixelSizeMicrons, int numPyramidLevels) throws IOException {
        // Build axes
        List<Map<String, Object>> axes = new ArrayList<>();
        if (nChannels > 1) {
            axes.add(Map.of("name", "c", "type", "channel"));
        }
        axes.add(Map.of("name", "y", "type", "space", "unit", "micrometer"));
        axes.add(Map.of("name", "x", "type", "space", "unit", "micrometer"));

        // Build datasets with coordinate transformations
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (int level = 0; level < numPyramidLevels; level++) {
            double scale = Math.pow(2, level);

            List<Double> scaleValues = new ArrayList<>();
            if (nChannels > 1) scaleValues.add(1.0);
            scaleValues.add(pixelSizeMicrons * scale);
            scaleValues.add(pixelSizeMicrons * scale);

            Map<String, Object> transform = new LinkedHashMap<>();
            transform.put("type", "scale");
            transform.put("scale", scaleValues);

            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("path", "s" + level);
            dataset.put("coordinateTransformations", List.of(transform));

            datasets.add(dataset);
        }

        // Build multiscale entry
        Map<String, Object> multiscale = new LinkedHashMap<>();
        multiscale.put("version", "0.4");
        multiscale.put("axes", axes);
        multiscale.put("datasets", datasets);
        multiscale.put("name", "");
        multiscale.put("type", "local");

        // Build omero metadata for channel visualization
        List<Map<String, Object>> channels = new ArrayList<>();
        int maxVal = (1 << Math.min(bitDepth, 16)) - 1;
        if (isRGB) {
            channels.add(buildChannelInfo("Red", "FF0000", 0, maxVal));
            channels.add(buildChannelInfo("Green", "00FF00", 0, maxVal));
            channels.add(buildChannelInfo("Blue", "0000FF", 0, maxVal));
        } else {
            channels.add(buildChannelInfo("Channel 0", "FFFFFF", 0, maxVal));
        }

        Map<String, Object> rdefs = new LinkedHashMap<>();
        rdefs.put("defaultT", 0);
        rdefs.put("defaultZ", 0);
        rdefs.put("model", isRGB ? "color" : "greyscale");

        Map<String, Object> omero = new LinkedHashMap<>();
        omero.put("channels", channels);
        omero.put("rdefs", rdefs);

        // Write to root .zattrs
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("multiscales", List.of(multiscale));
        attrs.put("omero", omero);

        rootGroup.writeAttributes(attrs);
    }

    private Map<String, Object> buildChannelInfo(String label, String color, int start, int end) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", start);
        window.put("end", end);
        window.put("min", start);
        window.put("max", end);

        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("active", true);
        ch.put("color", color);
        ch.put("label", label);
        ch.put("window", window);
        return ch;
    }

    /**
     * Write a composited chunk (BufferedImage) to the ZARR array at the specified level.
     *
     * @param image Chunk image data
     * @param level Pyramid level (0 = full resolution)
     * @param chunkX X position in pixels within the level
     * @param chunkY Y position in pixels within the level
     */
    public void writeChunk(BufferedImage image, int level, int chunkX, int chunkY) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        ZarrArray array = levelArrays[level];

        try {
            if (isRGB && nChannels > 1) {
                writeRGBChunk(array, image, w, h, chunkX, chunkY);
            } else if (bitDepth > 8) {
                write16BitChunk(array, image, w, h, chunkX, chunkY);
            } else {
                write8BitChunk(array, image, w, h, chunkX, chunkY);
            }
        } catch (Exception e) {
            throw new IOException("Error writing chunk at level=" + level + " (" + chunkX + "," + chunkY + ")", e);
        }
    }

    /**
     * Write RGB chunk as [C=3, Y, X] byte data.
     */
    private void writeRGBChunk(ZarrArray array, BufferedImage image, int w, int h, int chunkX, int chunkY)
            throws Exception {
        byte[] data = new byte[nChannels * h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int idx = y * w + x;
                data[idx] = (byte) ((rgb >> 16) & 0xFF); // R -> channel 0
                data[h * w + idx] = (byte) ((rgb >> 8) & 0xFF); // G -> channel 1
                data[2 * h * w + idx] = (byte) (rgb & 0xFF); // B -> channel 2
            }
        }
        array.write(data, new int[] {nChannels, h, w}, new int[] {0, chunkY, chunkX});
    }

    /**
     * Write 16-bit single-channel chunk.
     */
    private void write16BitChunk(ZarrArray array, BufferedImage image, int w, int h, int chunkX, int chunkY)
            throws Exception {
        int[] samples = new int[h * w];
        image.getRaster().getSamples(0, 0, w, h, 0, samples);
        short[] data = new short[h * w];
        for (int i = 0; i < samples.length; i++) {
            data[i] = (short) samples[i];
        }

        if (nChannels > 1) {
            array.write(data, new int[] {1, h, w}, new int[] {0, chunkY, chunkX});
        } else {
            array.write(data, new int[] {h, w}, new int[] {chunkY, chunkX});
        }
    }

    /**
     * Write 8-bit single-channel chunk.
     */
    private void write8BitChunk(ZarrArray array, BufferedImage image, int w, int h, int chunkX, int chunkY)
            throws Exception {
        int[] samples = new int[h * w];
        image.getRaster().getSamples(0, 0, w, h, 0, samples);
        byte[] data = new byte[h * w];
        for (int i = 0; i < samples.length; i++) {
            data[i] = (byte) samples[i];
        }

        if (nChannels > 1) {
            array.write(data, new int[] {1, h, w}, new int[] {0, chunkY, chunkX});
        } else {
            array.write(data, new int[] {h, w}, new int[] {chunkY, chunkX});
        }
    }

    /**
     * Write raw array data to a ZARR level. Used by {@link PyramidLevelGenerator}
     * for downsampled pyramid levels.
     *
     * @param data Flat array (byte[] for 8-bit, short[] for 16-bit)
     * @param level Pyramid level
     * @param offsetY Y offset in pixels
     * @param offsetX X offset in pixels
     * @param height Data height
     * @param width Data width
     */
    public void writeRawData(Object data, int level, int offsetY, int offsetX, int height, int width)
            throws IOException {
        try {
            ZarrArray array = levelArrays[level];
            if (nChannels > 1) {
                array.write(data, new int[] {nChannels, height, width}, new int[] {0, offsetY, offsetX});
            } else {
                array.write(data, new int[] {height, width}, new int[] {offsetY, offsetX});
            }
        } catch (Exception e) {
            throw new IOException(
                    "Error writing raw data at level " + level + " offset=(" + offsetX + "," + offsetY + ")", e);
        }
    }

    /**
     * Read raw array data from a ZARR level. Used by {@link PyramidLevelGenerator}
     * to read source data for downsampling.
     *
     * @param level Pyramid level to read from
     * @param offsetY Y offset in pixels
     * @param offsetX X offset in pixels
     * @param height Read height
     * @param width Read width
     * @return Flat array (byte[] for 8-bit, short[] for 16-bit)
     */
    public Object readRawData(int level, int offsetY, int offsetX, int height, int width) throws IOException {
        try {
            ZarrArray array = levelArrays[level];
            if (nChannels > 1) {
                return array.read(new int[] {nChannels, height, width}, new int[] {0, offsetY, offsetX});
            } else {
                return array.read(new int[] {height, width}, new int[] {offsetY, offsetX});
            }
        } catch (Exception e) {
            throw new IOException(
                    "Error reading data at level " + level + " offset=(" + offsetX + "," + offsetY + ")", e);
        }
    }

    /** Get the width at a specific pyramid level. */
    public int getLevelWidth(int level) {
        return Math.max(1, imageWidth / (1 << level));
    }

    /** Get the height at a specific pyramid level. */
    public int getLevelHeight(int level) {
        return Math.max(1, imageHeight / (1 << level));
    }

    public int getNumChannels() {
        return nChannels;
    }

    public boolean isRGB() {
        return isRGB;
    }

    public int getBitDepth() {
        return bitDepth;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    @Override
    public void close() throws IOException {
        // JZarr does not require explicit close for arrays/groups
        logger.info("ZARR writer closed: {}", outputPath);
    }
}
