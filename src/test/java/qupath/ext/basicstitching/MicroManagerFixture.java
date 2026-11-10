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
        MM14
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
            ImageIO.write(plane(value(c, z, t)), "TIFF", dir.resolve(name).toFile());
            if (noPlaneBlocks) continue;
            sb.append(",\"FrameKey-")
                    .append(t)
                    .append('-')
                    .append(c)
                    .append('-')
                    .append(z)
                    .append("\":{\"FileName\":\"")
                    .append(name)
                    .append("\",\"ChannelIndex\":")
                    .append(c)
                    .append(",\"SliceIndex\":")
                    .append(z)
                    .append(",\"Slice\":")
                    .append(z)
                    .append(",\"FrameIndex\":")
                    .append(t)
                    .append(",\"Frame\":")
                    .append(t)
                    .append(",\"PositionIndex\":")
                    .append(p)
                    .append(",\"PositionName\":\"")
                    .append(label)
                    .append("\",\"Channel\":\"")
                    .append(names[c])
                    .append("\",\"XPositionUm\":")
                    .append(xUm)
                    .append(",\"YPositionUm\":")
                    .append(yUm)
                    .append(",\"PixelSizeUm\":")
                    .append(PX_UM)
                    .append(",\"Width\":")
                    .append(tile)
                    .append(",\"Height\":")
                    .append(tile)
                    .append("}");
        }
        sb.append("}");
        Files.writeString(dir.resolve("metadata.txt"), sb.toString());
    }

    /** A Micro-Manager 1.4 Summary (acq_engine.clj, mm1.4): no StagePositions, PixelSize_um not PixelSizeUm. */
    private String summary14(int p) {
        StringBuilder chNames = new StringBuilder();
        for (int c = 0; c < channels; c++) {
            chNames.append(c > 0 ? "," : "").append('"').append(names[c]).append('"');
        }
        return "{\"MicroManagerVersion\":\"1.4.23 20180220\",\"MetadataVersion\":10,\"Channels\":" + channels
                + ",\"Slices\":" + slices + ",\"Frames\":" + frames + ",\"Positions\":" + POSITIONS
                + ",\"z-step_um\":" + (slices > 1 ? "2.5" : "0.0") + ",\"PixelSize_um\":" + PX_UM
                + ",\"ChNames\":[" + chNames + "],\"Prefix\":\"MDA_region\",\"PositionIndex\":" + p
                + ",\"SlicesFirst\":true,\"TimeFirst\":false}";
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
