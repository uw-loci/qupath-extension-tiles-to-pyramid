package qupath.ext.basicstitching;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.plugins.tiff.TIFFTagSet;
import javax.imageio.stream.ImageOutputStream;

/**
 * Synthetic Micro-Manager 2 multi-position acquisitions, shaped like what Micro-Manager 2.0.3
 * writes (checked against two real acquisitions: a 4-channel MMStack and the OWS3 single-plane SHG
 * time series).
 *
 * <p>A 2 x 2 grid of 16-bit tiles, 10% overlap. Every plane is the constant
 * {@link #value(int, int, int)}, so any plane read back names its own channel, slice and frame.
 */
final class MicroManagerFixture {

    static final String[] NAMES = {"DAPI", "FITC", "TRITC", "Cy5"};
    static final double PX_UM = 0.5;
    static final int POSITIONS = 4;

    enum Layout {
        /** One {@code <prefix>_MMStack_<label>.ome.tif} per position plus its {@code _metadata.txt}. */
        MMSTACK,
        /** A folder per position of {@code img_channelCCC_positionPPP_timeTTTTTTTTT_zZZZ.tif} files. */
        SINGLE_PLANE,
        /**
         * Micro-Manager 1.4: a folder per position of {@code img_TTTTTTTTT_<channel>_ZZZ.tif} files and
         * a metadata.txt of {@code FrameKey-t-c-z} blocks, each naming its file
         * ({@code TaggedImageStorageDiskDefault}, {@code mm1.4} branch). Its Summary has no
         * StagePositions; positions come from each block's XPositionUm / YPositionUm.
         */
        MM14,
        /**
         * Micro-Manager 2 NDTiff (v3): {@code <prefix>_NDTiffStack.tif} holding every image as a page,
         * and {@code NDTiff.index} listing each image's axes, file and byte offsets, written byte for
         * byte from the NDTiffStorage README's v3 specification. Stage positions are in each image's
         * metadata; the Summary lists none.
         */
        NDTIFF
    }

    final Layout layout;
    final int channels;
    final int slices;
    final int frames;
    int tile = 300;
    boolean tags = true;
    boolean zFastest = false;
    /** Pages in the first file of each position before a {@code _1} continuation file; 0 for none. */
    int rolloverAfter = 0;
    /** Write only {@code FrameKey-0-0-0} in an MMStack sidecar, as a minimal sidecar does. */
    boolean firstFrameKeyOnly = false;
    /** Write a 1.4 metadata.txt with its Summary only: planes must be read from the file names. */
    boolean noPlaneBlocks = false;
    /** 1.4: save each file's metadata in it as ImageJ's "Info" property, as Micro-Manager 1.4 does. */
    boolean mm14Embedded = false;
    /** 1.4: the embedded metadata's stage position is this far (um, X and Y) off the block's. */
    double mm14EmbeddedOffsetUm = 0;
    /** 1.4: list the positions in Summary.InitialPositionList, this far (um) off the blocks' positions. */
    Double mm14InitialPositionsOffsetUm = null;
    /** 1.4: metadata.txt stops part way through the last file's block, as an unfinished acquisition leaves it. */
    boolean mm14Truncated = false;
    /** NDTiff: the channel axis holds names (format 3.2) rather than indices. */
    boolean ndChannelNames = false;
    /** NDTiff: images after this many go to a second stack file ({@code _1}); 0 for one file. */
    int ndSplitAfter = 0;
    /** Channel names in acquisition order ({@code Summary.ChNames}). */
    String[] names = NAMES;

    private MicroManagerFixture(Layout layout, int channels, int slices, int frames) {
        this.layout = layout;
        this.channels = channels;
        this.slices = slices;
        this.frames = frames;
    }

    static MicroManagerFixture of(Layout layout, int channels, int slices, int frames) {
        return new MicroManagerFixture(layout, channels, slices, frames);
    }

    /** Tiles {@code px} wide and high; 540 gives a mosaic with a zoomed-out pyramid level. */
    MicroManagerFixture tile(int px) {
        tile = px;
        return this;
    }

    /** Grid step: 10% overlap. */
    int step() {
        return tile * 9 / 10;
    }

    /** Mosaic edge: two tiles one step apart. */
    int mosaic() {
        return step() + tile;
    }

    /** No 51123 tag on the pages: coordinates must come from the sidecar. */
    MicroManagerFixture withoutTags() {
        tags = false;
        return this;
    }

    /** Pages ordered time, channel, then z fastest (Micro-Manager's "slices first" off). */
    MicroManagerFixture zFastest() {
        zFastest = true;
        return this;
    }

    MicroManagerFixture rolloverAfter(int pages) {
        rolloverAfter = pages;
        return this;
    }

    /** Channel names in acquisition order, e.g. not alphabetical. */
    MicroManagerFixture names(String... channelNames) {
        names = channelNames;
        return this;
    }

    MicroManagerFixture ndChannelNames() {
        ndChannelNames = true;
        return this;
    }

    MicroManagerFixture ndSplitAfter(int images) {
        ndSplitAfter = images;
        return this;
    }

    MicroManagerFixture mm14Embedded(double offsetUm) {
        mm14Embedded = true;
        mm14EmbeddedOffsetUm = offsetUm;
        return this;
    }

    MicroManagerFixture mm14InitialPositions(double offsetUm) {
        mm14InitialPositionsOffsetUm = offsetUm;
        return this;
    }

    MicroManagerFixture mm14Truncated() {
        mm14Truncated = true;
        return this;
    }

    MicroManagerFixture noPlaneBlocks() {
        noPlaneBlocks = true;
        return this;
    }

    MicroManagerFixture firstFrameKeyOnly() {
        firstFrameKeyOnly = true;
        return this;
    }

    /** Distinct per (c, z, t), the same at every position, so per-plane means are exact. */
    static int value(int c, int z, int t) {
        return 1000 * (c + 1) + 100 * z + 10 * t + 1;
    }

    static String label(int p) {
        return "Pos-1-00" + (p / 2) + "_00" + (p % 2);
    }

    /**
     * Write the acquisition into {@code root/MDA_region}.
     *
     * @return the acquisition folder
     */
    Path write(Path root) throws IOException {
        Path src = Files.createDirectories(root.resolve("MDA_region"));
        if (layout == Layout.NDTIFF) {
            NdTiffWriter.write(this, src);
            return src;
        }
        for (int p = 0; p < POSITIONS; p++) {
            double x = (p % 2) * step() * PX_UM;
            double y = (p / 2) * step() * PX_UM;
            if (layout == Layout.MMSTACK) {
                writeMmStack(src, "MDA_region", label(p), p, x, y);
            } else if (layout == Layout.MM14) {
                writeMm14(src, label(p), p, x, y);
            } else {
                writeSinglePlane(src, label(p), p, x, y);
            }
        }
        return src;
    }

    /** Planes in the order Micro-Manager writes them: time outermost. */
    List<int[]> planeOrder() {
        List<int[]> out = new ArrayList<>();
        for (int t = 0; t < frames; t++) {
            if (zFastest) {
                for (int c = 0; c < channels; c++) for (int z = 0; z < slices; z++) out.add(new int[] {c, z, t});
            } else {
                for (int z = 0; z < slices; z++) for (int c = 0; c < channels; c++) out.add(new int[] {c, z, t});
            }
        }
        return out;
    }

    private void writeMmStack(Path dir, String prefix, String label, int p, double xUm, double yUm) throws IOException {
        String base = prefix + "_MMStack_" + label;
        List<int[]> order = planeOrder();
        int split = rolloverAfter > 0 && rolloverAfter < order.size() ? rolloverAfter : order.size();
        writePages(dir.resolve(base + ".ome.tif"), order.subList(0, split), p, label);
        if (split < order.size()) {
            writePages(dir.resolve(base + "_1.ome.tif"), order.subList(split, order.size()), p, label);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":").append(summary());
        for (int i = 0; i < order.size(); i++) {
            int[] k = order.get(i);
            if (firstFrameKeyOnly && i > 0) break;
            String file = i < split ? base + ".ome.tif" : base + "_1.ome.tif";
            sb.append(",\"FrameKey-")
                    .append(k[2])
                    .append('-')
                    .append(k[0])
                    .append('-')
                    .append(k[1])
                    .append("\":{\"FileName\":\"")
                    .append(file)
                    .append("\",\"XPositionUm\":")
                    .append(xUm)
                    .append(",\"YPositionUm\":")
                    .append(yUm)
                    .append(",\"PixelSizeUm\":")
                    .append(PX_UM)
                    .append(",\"PositionName\":\"")
                    .append(label)
                    .append("\"}");
        }
        sb.append("}");
        Files.writeString(dir.resolve(base + "_metadata.txt"), sb.toString());
    }

    private void writePages(Path file, List<int[]> planes, int p, String label) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int[] k : planes) {
                BufferedImage img = plane(value(k[0], k[1], k[2]));
                if (tags) {
                    TIFFDirectory d = new TIFFDirectory(new TIFFTagSet[] {BaselineTIFFTagSet.getInstance()}, null);
                    String json = "{\"ChannelIndex\":" + k[0] + ",\"SliceIndex\":" + k[1] + ",\"FrameIndex\":" + k[2]
                            + ",\"PositionIndex\":" + p + ",\"PositionName\":\"" + label + "\"}";
                    TIFFTag mm = new TIFFTag("MicroManagerMetadata", 51123, 1 << TIFFTag.TIFF_ASCII);
                    d.addTIFFField(new TIFFField(mm, TIFFTag.TIFF_ASCII, 1, new String[] {json}));
                    writer.writeToSequence(new IIOImage(img, null, d.getAsMetadata()), null);
                } else {
                    writer.writeToSequence(new IIOImage(img, null, null), null);
                }
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private void writeSinglePlane(Path root, String label, int p, double xUm, double yUm) throws IOException {
        Path dir = Files.createDirectories(root.resolve(label));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":").append(summary());
        for (int[] k : planeOrder()) {
            int c = k[0], z = k[1], t = k[2];
            String name = String.format("img_channel%03d_position%03d_time%09d_z%03d.tif", c, p, t, z);
            ImageIO.write(plane(value(c, z, t)), "TIFF", dir.resolve(name).toFile());
            String rel = label + "/" + name;
            sb.append(",\"Coords-")
                    .append(rel)
                    .append("\":{\"ChannelIndex\":")
                    .append(c)
                    .append(",\"SliceIndex\":")
                    .append(z)
                    .append(",\"FrameIndex\":")
                    .append(t)
                    .append(",\"PositionIndex\":")
                    .append(p)
                    .append("}");
            sb.append(",\"Metadata-")
                    .append(rel)
                    .append("\":{\"FileName\":\"")
                    .append(rel)
                    .append("\",\"ChannelIndex\":")
                    .append(c)
                    .append(",\"SliceIndex\":")
                    .append(z)
                    .append(",\"FrameIndex\":")
                    .append(t)
                    .append(",\"PositionIndex\":")
                    .append(p)
                    .append(",\"Channel\":\"")
                    .append(names[c])
                    .append("\",\"XPositionUm\":")
                    .append(xUm)
                    .append(",\"YPositionUm\":")
                    .append(yUm)
                    .append(",\"PixelSizeUm\":")
                    .append(PX_UM)
                    .append(",\"PositionName\":\"")
                    .append(label)
                    .append("\"}");
        }
        sb.append("}");
        Files.writeString(dir.resolve("metadata.txt"), sb.toString());
    }

    private void writeMm14(Path root, String label, int p, double xUm, double yUm) throws IOException {
        Path dir = Files.createDirectories(root.resolve(label));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":").append(summary14(p));
        for (int[] k : planeOrder()) {
            int c = k[0], z = k[1], t = k[2];
            String name = String.format("img_%09d_%s_%03d.tif", t, names[c], z);
            if (mm14Embedded) {
                writeImageJTiff(
                        dir.resolve(name),
                        plane(value(c, z, t)),
                        mm14Block(name, label, p, c, z, t, xUm + mm14EmbeddedOffsetUm, yUm + mm14EmbeddedOffsetUm));
            } else {
                ImageIO.write(plane(value(c, z, t)), "TIFF", dir.resolve(name).toFile());
            }
            if (noPlaneBlocks) continue;
            sb.append(",\"FrameKey-")
                    .append(t)
                    .append('-')
                    .append(c)
                    .append('-')
                    .append(z)
                    .append("\":")
                    .append(mm14Block(name, label, p, c, z, t, xUm, yUm));
        }
        String text = sb.append("}").toString();
        if (mm14Truncated) {
            // TaggedImageStorageDiskDefault closes the brace only when the acquisition finishes.
            text = text.substring(0, text.length() - 40);
        }
        Files.writeString(dir.resolve("metadata.txt"), text);
    }

    /** One image's metadata, as 1.4 writes it both in its FrameKey block and inside the file. */
    private String mm14Block(String name, String label, int p, int c, int z, int t, double xUm, double yUm) {
        return "{\"FileName\":\"" + name + "\",\"ChannelIndex\":" + c + ",\"SliceIndex\":" + z + ",\"Slice\":" + z
                + ",\"FrameIndex\":" + t + ",\"Frame\":" + t + ",\"PositionIndex\":" + p + ",\"PositionName\":\""
                + label + "\",\"Channel\":\"" + names[c] + "\",\"XPositionUm\":" + xUm + ",\"YPositionUm\":" + yUm
                + ",\"PixelSizeUm\":" + PX_UM + ",\"Width\":" + tile + ",\"Height\":" + tile + "}";
    }

    /**
     * A TIFF carrying {@code info} as ImageJ's "Info" property: tag 50838 the byte counts (header,
     * then the text), tag 50839 the "IJIJ" header, one "info" entry and the text in UTF-16, in the
     * file's byte order ({@code ij.io.TiffEncoder.writeMetaData}). The JDK writer writes big-endian.
     */
    static void writeImageJTiff(Path file, BufferedImage img, String info) throws IOException {
        byte[] text = info.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(12 + text.length);
        data.putInt(0x494a494a).putInt(0x696e666f).putInt(1).put(text);
        TIFFDirectory d = new TIFFDirectory(new TIFFTagSet[] {BaselineTIFFTagSet.getInstance()}, null);
        TIFFTag counts = new TIFFTag("MetaDataByteCounts", 50838, 1 << TIFFTag.TIFF_LONG);
        TIFFTag meta = new TIFFTag("MetaData", 50839, 1 << TIFFTag.TIFF_BYTE);
        d.addTIFFField(new TIFFField(counts, TIFFTag.TIFF_LONG, 2, new long[] {12, text.length}));
        d.addTIFFField(new TIFFField(meta, TIFFTag.TIFF_BYTE, data.capacity(), data.array()));
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.write(new IIOImage(img, null, d.getAsMetadata()));
        } finally {
            writer.dispose();
        }
    }

    /** A Micro-Manager 1.4 Summary (acq_engine.clj, mm1.4): no StagePositions, PixelSize_um not PixelSizeUm. */
    String summary14(int p) {
        StringBuilder chNames = new StringBuilder();
        for (int c = 0; c < channels; c++) {
            chNames.append(c > 0 ? "," : "").append('"').append(names[c]).append('"');
        }
        return "{\"MicroManagerVersion\":\"1.4.23 20180220\",\"MetadataVersion\":10,\"Channels\":" + channels
                + ",\"Slices\":" + slices + ",\"Frames\":" + frames + ",\"Positions\":" + POSITIONS
                + ",\"z-step_um\":" + (slices > 1 ? "2.5" : "0.0") + ",\"PixelSize_um\":" + PX_UM
                + ",\"ChNames\":[" + chNames + "],\"Prefix\":\"MDA_region\",\"PositionIndex\":" + p
                + ",\"SlicesFirst\":true,\"TimeFirst\":false" + initialPositionList14() + "}";
    }

    /**
     * Summary.InitialPositionList as 1.4's {@code summarize-position-list} writes it (acq_engine.clj,
     * mm1.4): Label, GridRowIndex, GridColumnIndex and DeviceCoordinatesUm, an XY stage with two
     * coordinates and a focus drive with one.
     */
    private String initialPositionList14() {
        if (mm14InitialPositionsOffsetUm == null) return "";
        StringBuilder sb = new StringBuilder(",\"InitialPositionList\":[");
        for (int p = 0; p < POSITIONS; p++) {
            double x = (p % 2) * step() * PX_UM + mm14InitialPositionsOffsetUm;
            double y = (p / 2) * step() * PX_UM + mm14InitialPositionsOffsetUm;
            sb.append(p > 0 ? "," : "")
                    .append("{\"Label\":\"")
                    .append(label(p))
                    .append("\",\"GridRowIndex\":")
                    .append(p / 2)
                    .append(",\"GridColumnIndex\":")
                    .append(p % 2)
                    .append(",\"DeviceCoordinatesUm\":{\"XYStage\":[")
                    .append(x)
                    .append(',')
                    .append(y)
                    .append("],\"Z\":[12.5]}}");
        }
        return sb.append("]").toString();
    }

    /** The acquisition's Summary; as Micro-Manager writes it, the same in every sidecar, listing every position. */
    private String summary() {
        StringBuilder chNames = new StringBuilder();
        for (int c = 0; c < channels; c++) {
            chNames.append(c > 0 ? "," : "").append('"').append(names[c]).append('"');
        }
        StringBuilder positions = new StringBuilder();
        for (int p = 0; p < POSITIONS; p++) {
            positions
                    .append(p > 0 ? "," : "")
                    .append("{\"Label\":\"")
                    .append(label(p))
                    .append("\",\"DefaultXYStage\":\"XY\",\"DevicePositions\":[{\"Device\":\"XY\",\"Position_um\":[")
                    .append((p % 2) * step() * PX_UM)
                    .append(',')
                    .append((p / 2) * step() * PX_UM)
                    .append("]}]}");
        }
        String axes = zFastest ? "\"z\",\"channel\"" : "\"channel\",\"z\"";
        return "{\"MicroManagerVersion\":\"2.0.3\",\"Channels\":" + channels + ",\"Slices\":" + slices
                + ",\"Frames\":" + frames + ",\"z-step_um\":" + (slices > 1 ? "2.5" : "0.0")
                + ",\"Positions\":4,\"ChNames\":[" + chNames + "],\"ChannelGroup\":\"Filter\""
                + ",\"AxisOrder\":[" + axes + ",\"time\",\"position\"],\"IntendedDimensions\":{\"time\":"
                + frames + ",\"position\":4,\"z\":" + slices + ",\"channel\":" + channels + "}"
                + ",\"StagePositions\":[" + positions + "]}";
    }

    BufferedImage plane(int v) {
        BufferedImage img = new BufferedImage(tile, tile, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster r = img.getRaster();
        int[] row = new int[tile];
        java.util.Arrays.fill(row, v);
        for (int y = 0; y < tile; y++) r.setSamples(0, y, tile, 1, 0, row);
        return img;
    }
}
