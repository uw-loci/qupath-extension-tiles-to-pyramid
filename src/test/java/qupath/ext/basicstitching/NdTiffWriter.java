package qupath.ext.basicstitching;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes an NDTiff v3 dataset for {@link MicroManagerFixture}, following the specification in the
 * NDTiffStorage README (github.com/micro-manager/NDTiffStorage, "Specification for NDTiff v3.x")
 * and the IFD layout of its {@code NDTiffWriter.writeIFD}: per image an IFD (width, height, bits
 * per sample, compression 1, photometric, one strip, samples per pixel, rows per strip, strip byte
 * count, Micro-Manager tag 51123 pointing at the metadata), then the pixels, then the metadata JSON.
 * Little-endian throughout.
 */
final class NdTiffWriter {

    static final int MAGIC = 483729;
    static final int SUMMARY_HEADER = 2355492;

    private NdTiffWriter() {}

    static void write(MicroManagerFixture f, Path dir) throws IOException {
        String prefix = "MDA_region";
        List<int[]> order = f.planeOrder();
        int perPosition = order.size();
        int total = perPosition * MicroManagerFixture.POSITIONS;
        int split = f.ndSplitAfter > 0 && f.ndSplitAfter < total ? f.ndSplitAfter : total;
        byte[] summary = summary(f).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream index = new ByteArrayOutputStream();
        Stack[] stacks = {
            new Stack(prefix + "_NDTiffStack.tif", summary), new Stack(prefix + "_NDTiffStack_1.tif", summary)
        };
        int n = 0;
        // Micro-Manager's MDA order: time outermost, then position, then the plane order.
        for (int t = 0; t < f.frames; t++) {
            for (int p = 0; p < MicroManagerFixture.POSITIONS; p++) {
                double x = (p % 2) * f.step() * MicroManagerFixture.PX_UM;
                double y = (p / 2) * f.step() * MicroManagerFixture.PX_UM;
                for (int[] k : order) {
                    if (k[2] != t) continue;
                    Stack s = stacks[n++ < split ? 0 : 1];
                    String md = "{\"XPositionUm\":" + x + ",\"YPositionUm\":" + y + ",\"PixelSizeUm\":"
                            + MicroManagerFixture.PX_UM + ",\"PositionName\":\"" + MicroManagerFixture.label(p)
                            + "\",\"Camera\":\"Camera\"}";
                    long[] at = s.add(
                            f.tile, MicroManagerFixture.value(k[0], k[1], k[2]), md.getBytes(StandardCharsets.UTF_8));
                    String channel = f.ndChannelNames ? "\"" + f.names[k[0]] + "\"" : String.valueOf(k[0]);
                    String axes = "{\"channel\":" + channel + ",\"z\":" + k[1] + ",\"time\":" + k[2] + ",\"position\":"
                            + p + "}";
                    entry(index, axes, s.name, at[0], f.tile, at[1], at[2]);
                }
            }
        }
        for (Stack s : stacks) {
            if (s.images > 0) Files.write(dir.resolve(s.name), s.bytes());
        }
        Files.write(dir.resolve("NDTiff.index"), index.toByteArray());
    }

    private static String summary(MicroManagerFixture f) {
        StringBuilder ch = new StringBuilder();
        for (int c = 0; c < f.channels; c++)
            ch.append(c > 0 ? "," : "").append('"').append(f.names[c]).append('"');
        return "{\"MicroManagerVersion\":\"2.0.3\",\"Prefix\":\"MDA_region\",\"Channels\":" + f.channels
                + ",\"Slices\":" + f.slices + ",\"Frames\":" + f.frames + ",\"Positions\":"
                + MicroManagerFixture.POSITIONS + ",\"z-step_um\":" + (f.slices > 1 ? "2.5" : "0") + ",\"ChNames\":["
                + ch + "],\"AxisOrder\":[\"z\",\"channel\",\"position\",\"time\"],\"IntendedDimensions\":{\"channel\":"
                + f.channels + ",\"z\":" + f.slices + ",\"time\":" + f.frames + ",\"position\":"
                + MicroManagerFixture.POSITIONS + "}}";
    }

    private static void entry(
            ByteArrayOutputStream out,
            String axes,
            String file,
            long pixelOffset,
            int tile,
            long mdOffset,
            long mdLength) {
        byte[] a = axes.getBytes(StandardCharsets.UTF_8);
        byte[] fn = file.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + a.length + 4 + fn.length + 32).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(a.length).put(a).putInt(fn.length).put(fn);
        b.putInt((int) pixelOffset).putInt(tile).putInt(tile).putInt(1).putInt(0);
        b.putInt((int) mdOffset).putInt((int) mdLength).putInt(0);
        out.writeBytes(b.array());
    }

    /** One stack file being built in memory. */
    private static final class Stack {
        final String name;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int images = 0;
        /** Where the previous IFD's next-IFD field sits, to patch. */
        int lastNextField;
        /** (byte position, value) of each next-IFD field, written once the file is complete. */
        final java.util.List<int[]> patches = new java.util.ArrayList<>();

        Stack(String name, byte[] summary) {
            this.name = name;
            ByteBuffer h = ByteBuffer.allocate(28 + summary.length).order(ByteOrder.LITTLE_ENDIAN);
            h.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(0); // first IFD patched later
            h.putInt(MAGIC)
                    .putInt(3)
                    .putInt(2)
                    .putInt(SUMMARY_HEADER)
                    .putInt(summary.length)
                    .put(summary);
            out.writeBytes(h.array());
            lastNextField = 4;
        }

        /** @return pixel offset, metadata offset, metadata length */
        long[] add(int tile, int value, byte[] md) {
            int ifd = out.size() + (out.size() % 2); // word aligned
            if (ifd > out.size()) out.write(0);
            int entries = 10;
            int pixelsAt = ifd + 2 + entries * 12 + 4;
            int pixelBytes = tile * tile * 2;
            int mdAt = pixelsAt + pixelBytes;
            ByteBuffer b = ByteBuffer.allocate(2 + entries * 12 + 4 + pixelBytes + md.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) entries);
            e(b, 256, 4, 1, tile);
            e(b, 257, 4, 1, tile);
            e(b, 258, 3, 1, 16);
            e(b, 259, 3, 1, 1);
            e(b, 262, 3, 1, 1);
            e(b, 273, 4, 1, pixelsAt);
            e(b, 277, 3, 1, 1);
            e(b, 278, 3, 1, tile);
            e(b, 279, 4, 1, pixelBytes);
            e(b, 51123, 2, md.length, mdAt);
            int nextField = ifd + 2 + entries * 12;
            b.putInt(0);
            for (int i = 0; i < tile * tile; i++) b.putShort((short) value);
            b.put(md);
            patches.add(new int[] {lastNextField, ifd});
            out.writeBytes(b.array());
            lastNextField = nextField;
            images++;
            return new long[] {pixelsAt, mdAt, md.length};
        }

        private static void e(ByteBuffer b, int tag, int type, int count, int value) {
            b.putShort((short) tag).putShort((short) type).putInt(count);
            if (type == 3 && count == 1) {
                b.putShort((short) value).putShort((short) 0);
            } else {
                b.putInt(value);
            }
        }

        byte[] bytes() {
            byte[] bytes = out.toByteArray();
            ByteBuffer w = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int[] p : patches) w.putInt(p[0], p[1]);
            return bytes;
        }
    }
}
