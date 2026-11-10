package qupath.ext.basicstitching.assembly.direct;

import com.bc.zarr.ArrayParams;
import com.bc.zarr.Compressor;
import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import loci.formats.MetadataTools;
import loci.formats.ome.OMEXMLMetadata;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
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
 *     OME/METADATA.ome.xml  (OME-XML: where QuPath's Bio-Formats Zarr reader takes the pixel size
 *                       and z spacing from; it does not read the multiscales scale)
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
    /**
     * NGFF (OME-Zarr) spec version stamped into {@code .zattrs}. Public so the dialog can name the
     * version it will write rather than hardcoding its own copy.
     */
    public static final String NGFF_VERSION = "0.4";

    /** Group and file QuPath's OME-Zarr writer keeps the OME-XML in; Bio-Formats' Zarr reader reads it. */
    static final String OME_GROUP = "OME";

    static final String OME_XML_FILE = "METADATA.ome.xml";

    /** Zarr array format written: JZarr produces v2 {@code .zarray} arrays. */
    public static final String ZARR_FORMAT_VERSION = "2";

    private final Compressor compressor;
    private ZarrGroup rootGroup;
    private ZarrArray[] levelArrays;
    private int imageWidth;
    private int imageHeight;
    private int nChannels;
    private boolean isRGB;
    private int bitDepth;
    private int chunkSize;
    private int sizeZ = 1;
    private int sizeT = 1;
    private double zSpacingMicrons;

    // --- Axis layout (NGFF TCZYX subset) ---
    // The arrays carry only the axes that are non-singleton, in TCZYX order:
    // t (if sizeT>1), c (if nChannels>1), z (if sizeZ>1), then always y, x. This
    // keeps the 2D / multichannel output identical to before and adds z/t only
    // when present.

    private boolean hasT() {
        return sizeT > 1;
    }

    private boolean hasC() {
        return nChannels > 1;
    }

    private boolean hasZ() {
        return sizeZ > 1;
    }

    /**
     * Build a present-axes coordinate array (shape or offset) in TCZYX order,
     * dropping any singleton t/c/z axis so the rank matches the JZarr arrays.
     */
    private int[] axes(int tv, int cv, int zv, int yv, int xv) {
        int n = 2 + (hasT() ? 1 : 0) + (hasC() ? 1 : 0) + (hasZ() ? 1 : 0);
        int[] a = new int[n];
        int i = 0;
        if (hasT()) {
            a[i++] = tv;
        }
        if (hasC()) {
            a[i++] = cv;
        }
        if (hasZ()) {
            a[i++] = zv;
        }
        a[i++] = yv;
        a[i] = xv;
        return a;
    }

    /** Present axis names in TCZYX order, matching {@link #axes}. */
    private List<String> axisNames() {
        List<String> names = new ArrayList<>();
        if (hasT()) {
            names.add("t");
        }
        if (hasC()) {
            names.add("c");
        }
        if (hasZ()) {
            names.add("z");
        }
        names.add("y");
        names.add("x");
        return names;
    }

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

    /** Names for the non-RGB channels' omero labels; empty gives "Channel 0", "Channel 1", ... */
    private List<String> channelNames = List.of();

    /**
     * Set the channel names written as omero labels. Call before {@link #initialize}; a list whose
     * size differs from the channel count is ignored.
     *
     * @param names one name per channel, in order; null for the defaults
     */
    public void setChannelNames(List<String> names) {
        this.channelNames = names == null ? List.of() : List.copyOf(names);
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
            int zCount,
            int tCount,
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
        this.sizeZ = Math.max(1, zCount);
        this.sizeT = Math.max(1, tCount);
        this.zSpacingMicrons = zSpacingMicrons;
        this.chunkSize = chunkSize;

        logger.info(
                "Initializing ZARR writer: {}x{}, {} channels, {} z, {} t, {} bit, {} levels, chunk={}",
                imageWidth,
                imageHeight,
                nChannels,
                sizeZ,
                sizeT,
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

            // Full extent on every axis; chunk one (t, z) plane at a time, whole
            // channel axis per chunk (matches the previous [c,y,x] chunking).
            int[] shape = axes(sizeT, nChannels, sizeZ, levelH, levelW);
            int[] chunks = axes(1, nChannels, 1, levelChunkH, levelChunkW);

            ArrayParams params = new ArrayParams()
                    .shape(shape)
                    .chunks(chunks)
                    .dataType(dataType)
                    .compressor(compressor)
                    .fillValue(0);

            Path arrayPath = outputPath.resolve("s" + level);
            levelArrays[level] = ZarrArray.create(arrayPath, params);

            // Write array dimension attributes (present axes, TCZYX order)
            Map<String, Object> arrayAttrs = new LinkedHashMap<>();
            arrayAttrs.put("_ARRAY_DIMENSIONS", axisNames());
            levelArrays[level].writeAttributes(arrayAttrs);

            logger.debug(
                    "Created level {} array: shape={}, chunks={}",
                    level,
                    Arrays.toString(shape),
                    Arrays.toString(chunks));
        }

        // Write NGFF 0.4 metadata to root .zattrs
        writeNGFFMetadata(pixelSizeMicrons, numPyramidLevels);
        writeOmeXml(pixelSizeMicrons);

        logger.info("ZARR structure initialized at: {}", outputPath);
    }

    /**
     * Write {@code OME/METADATA.ome.xml}, as QuPath's own OME-Zarr writer does. Bio-Formats' Zarr
     * reader (what QuPath opens a {@code .ome.zarr} with) takes the physical sizes from this file
     * only; without it QuPath shows the image uncalibrated. PhysicalSizeZ is written only when the
     * z spacing is known. A failure is logged, not thrown: the pixels are still readable.
     */
    private void writeOmeXml(double pixelSizeMicrons) {
        try {
            OMEXMLMetadata meta = (OMEXMLMetadata) MetadataTools.createOMEXMLMetadata();
            // One sample per channel (the Zarr c axis), XYZCT as QuPath's own OME-Zarr writer writes.
            MetadataTools.populateMetadata(
                    meta,
                    0,
                    stemOf(outputPath),
                    false,
                    DimensionOrder.XYZCT.getValue(),
                    (bitDepth > 8 ? ome.xml.model.enums.PixelType.UINT16 : ome.xml.model.enums.PixelType.UINT8)
                            .getValue(),
                    imageWidth,
                    imageHeight,
                    sizeZ,
                    nChannels,
                    sizeT,
                    1);
            boolean named = !isRGB && channelNames.size() == nChannels;
            for (int c = 0; c < nChannels; c++) {
                String name = isRGB
                        ? List.of("Red", "Green", "Blue").get(Math.min(c, 2))
                        : named ? channelNames.get(c) : "Channel " + c;
                meta.setChannelName(name, 0, c);
            }
            if (pixelSizeMicrons > 0 && Double.isFinite(pixelSizeMicrons)) {
                meta.setPixelsPhysicalSizeX(new Length(pixelSizeMicrons, UNITS.MICROMETER), 0);
                meta.setPixelsPhysicalSizeY(new Length(pixelSizeMicrons, UNITS.MICROMETER), 0);
            }
            if (zSpacingKnown()) {
                meta.setPixelsPhysicalSizeZ(new Length(zSpacingMicrons, UNITS.MICROMETER), 0);
            }
            rootGroup.createSubGroup(OME_GROUP);
            Files.writeString(
                    outputPath.resolve(OME_GROUP).resolve(OME_XML_FILE), meta.dumpXML(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Could not write the OME-XML of {}; QuPath will open it without a pixel size", outputPath, e);
        }
    }

    /**
     * Remove the unit of the z and/or time axis from an OME-Zarr written by QuPath's own
     * {@code OMEZarrWriter} (the channel merge), when that spacing is not known. QuPath's writer
     * gives the z axis the pixel size's unit with its default z spacing of 1, and the time axis
     * seconds with scale 1, so an unknown spacing reads as 1 micrometer / 1 second. The scale value
     * stays (NGFF 0.4 requires one per axis; 1 is its value for "no physical scale"); the OME-XML
     * QuPath writes beside it already leaves PhysicalSizeZ and TimeIncrement out in that case.
     *
     * @param zarr the {@code .ome.zarr} folder
     * @param zSpacingKnown whether the image has a z spacing in micrometers
     * @param timeIncrementKnown whether the image has a time increment
     * @return the axis names whose unit was removed
     * @throws IOException if the attributes cannot be read or written
     */
    public static List<String> dropUnknownAxisUnits(Path zarr, boolean zSpacingKnown, boolean timeIncrementKnown)
            throws IOException {
        ZarrGroup group = ZarrGroup.open(zarr);
        Map<String, Object> attrs = new LinkedHashMap<>(group.getAttributes());
        List<String> dropped = new ArrayList<>();
        if (!(attrs.get("multiscales") instanceof List<?> multiscales)) {
            return dropped;
        }
        List<Object> newMultiscales = new ArrayList<>();
        for (Object ms : multiscales) {
            if (!(ms instanceof Map<?, ?> msMap) || !(msMap.get("axes") instanceof List<?> axes)) {
                newMultiscales.add(ms);
                continue;
            }
            List<Object> newAxes = new ArrayList<>();
            for (Object axis : axes) {
                if (axis instanceof Map<?, ?> axisMap && axisMap.containsKey("unit")) {
                    Object name = axisMap.get("name");
                    boolean drop = ("z".equals(name) && !zSpacingKnown) || ("t".equals(name) && !timeIncrementKnown);
                    if (drop) {
                        Map<Object, Object> copy = new LinkedHashMap<>(axisMap);
                        copy.remove("unit");
                        newAxes.add(copy);
                        dropped.add(String.valueOf(name));
                        continue;
                    }
                }
                newAxes.add(axis);
            }
            Map<Object, Object> msCopy = new LinkedHashMap<>(msMap);
            msCopy.put("axes", newAxes);
            newMultiscales.add(msCopy);
        }
        if (!dropped.isEmpty()) {
            attrs.put("multiscales", newMultiscales);
            group.writeAttributes(attrs);
        }
        return dropped;
    }

    private static String stemOf(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".ome.zarr") ? name.substring(0, name.length() - ".ome.zarr".length()) : name;
    }

    private boolean zSpacingKnown() {
        return zSpacingMicrons > 0 && Double.isFinite(zSpacingMicrons);
    }

    /**
     * Write NGFF 0.4 multiscales and omero metadata to root .zattrs.
     *
     * <p>NGFF 0.4 ("multiscales" metadata) requires one scale value per axis and, when the physical
     * scale is not known, the scale relative to the first resolution, 1.0 for an axis that is not
     * downsampled. An axis "unit" is only a SHOULD ("axes" metadata). So an unknown z spacing is
     * written as scale 1 with no unit (a z-slice index), never as 1 micrometer, and the time axis,
     * whose interval is never known here, as scale 1 with no unit.
     */
    private void writeNGFFMetadata(double pixelSizeMicrons, int numPyramidLevels) throws IOException {
        // Build axes (present axes only, TCZYX order)
        List<Map<String, Object>> axes = new ArrayList<>();
        if (hasT()) {
            axes.add(Map.of("name", "t", "type", "time"));
        }
        if (hasC()) {
            axes.add(Map.of("name", "c", "type", "channel"));
        }
        if (hasZ()) {
            axes.add(
                    zSpacingKnown()
                            ? Map.of("name", "z", "type", "space", "unit", "micrometer")
                            : Map.of("name", "z", "type", "space"));
        }
        // An unknown pixel size the same way: scale 2^level, no unit (a scale of 0 is not valid).
        boolean pixelSizeKnown = pixelSizeMicrons > 0 && Double.isFinite(pixelSizeMicrons);
        double xyUnitScale = pixelSizeKnown ? pixelSizeMicrons : 1.0;
        for (String name : List.of("y", "x")) {
            axes.add(
                    pixelSizeKnown
                            ? Map.of("name", name, "type", "space", "unit", "micrometer")
                            : Map.of("name", name, "type", "space"));
        }

        // Build datasets with coordinate transformations
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (int level = 0; level < numPyramidLevels; level++) {
            double scale = Math.pow(2, level);

            List<Double> scaleValues = new ArrayList<>();
            if (hasT()) scaleValues.add(1.0);
            if (hasC()) scaleValues.add(1.0);
            if (hasZ()) scaleValues.add(zSpacingKnown() ? zSpacingMicrons : 1.0);
            scaleValues.add(xyUnitScale * scale);
            scaleValues.add(xyUnitScale * scale);

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
        multiscale.put("version", NGFF_VERSION);
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
            boolean named = channelNames.size() == nChannels;
            for (int c = 0; c < nChannels; c++) {
                channels.add(buildChannelInfo(named ? channelNames.get(c) : "Channel " + c, "FFFFFF", 0, maxVal));
            }
        }

        Map<String, Object> rdefs = new LinkedHashMap<>();
        rdefs.put("defaultT", 0);
        rdefs.put("defaultZ", 0);
        rdefs.put("model", isRGB ? "color" : "grayscale");

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
    public void writeChunk(BufferedImage image, int level, int z, int t, int chunkX, int chunkY) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        ZarrArray array = levelArrays[level];

        try {
            if (isRGB && nChannels > 1) {
                writeRGBChunk(array, image, w, h, z, t, chunkX, chunkY);
            } else if (bitDepth > 8) {
                write16BitChunk(array, image, w, h, z, t, chunkX, chunkY);
            } else {
                write8BitChunk(array, image, w, h, z, t, chunkX, chunkY);
            }
        } catch (Exception e) {
            throw new IOException(
                    "Error writing chunk at level=" + level + " z=" + z + " t=" + t + " (" + chunkX + "," + chunkY
                            + ")",
                    e);
        }
    }

    /**
     * Write RGB chunk as [C=3, Y, X] byte data into the (z, t) plane.
     */
    private void writeRGBChunk(ZarrArray array, BufferedImage image, int w, int h, int z, int t, int chunkX, int chunkY)
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
        array.write(data, axes(1, nChannels, 1, h, w), axes(t, 0, z, chunkY, chunkX));
    }

    /**
     * Write 16-bit single-channel chunk into the (z, t) plane.
     */
    private void write16BitChunk(
            ZarrArray array, BufferedImage image, int w, int h, int z, int t, int chunkX, int chunkY) throws Exception {
        int[] samples = new int[h * w];
        image.getRaster().getSamples(0, 0, w, h, 0, samples);
        short[] data = new short[h * w];
        for (int i = 0; i < samples.length; i++) {
            data[i] = (short) samples[i];
        }
        array.write(data, axes(1, 1, 1, h, w), axes(t, 0, z, chunkY, chunkX));
    }

    /**
     * Write 8-bit single-channel chunk into the (z, t) plane.
     */
    private void write8BitChunk(
            ZarrArray array, BufferedImage image, int w, int h, int z, int t, int chunkX, int chunkY) throws Exception {
        int[] samples = new int[h * w];
        image.getRaster().getSamples(0, 0, w, h, 0, samples);
        byte[] data = new byte[h * w];
        for (int i = 0; i < samples.length; i++) {
            data[i] = (byte) samples[i];
        }
        array.write(data, axes(1, 1, 1, h, w), axes(t, 0, z, chunkY, chunkX));
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
    public void writeRawData(Object data, int level, int z, int t, int offsetY, int offsetX, int height, int width)
            throws IOException {
        try {
            ZarrArray array = levelArrays[level];
            array.write(data, axes(1, nChannels, 1, height, width), axes(t, 0, z, offsetY, offsetX));
        } catch (Exception e) {
            throw new IOException(
                    "Error writing raw data at level " + level + " z=" + z + " t=" + t + " offset=(" + offsetX + ","
                            + offsetY + ")",
                    e);
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
    public Object readRawData(int level, int z, int t, int offsetY, int offsetX, int height, int width)
            throws IOException {
        try {
            ZarrArray array = levelArrays[level];
            return array.read(axes(1, nChannels, 1, height, width), axes(t, 0, z, offsetY, offsetX));
        } catch (Exception e) {
            throw new IOException(
                    "Error reading data at level " + level + " z=" + z + " t=" + t + " offset=(" + offsetX + ","
                            + offsetY + ")",
                    e);
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

    public int getSizeZ() {
        return sizeZ;
    }

    public int getSizeT() {
        return sizeT;
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
