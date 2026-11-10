package qupath.ext.basicstitching.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.Options;

/**
 * Averaging, flat-field correction and z projection of an NDTiff dataset: the copy is an NDTiff
 * dataset whose images carry their source's axes (no {@code time} when averaged, no {@code z} when
 * projected) and metadata. The stitch and merge of every option over C3 x Z x T are in
 * {@code MicroManagerCztStitchTest}; these check the copy itself, the flat-field order, an
 * unfinished last time point, the dialog's shape and the writer's file roll-over.
 *
 * <p>Inputs are written with {@link NdTiffWriter} (12 x 8 tiles, the size of the flat-field
 * fixture); its output is read back here by byte offset from the index, not by the code under
 * test.
 */
class NdTiffPreprocessTest {

    private static final int W = 12;
    private static final int H = 8;

    /** Distinct per plane; the x term makes the flat-field correction visible. */
    private static int value(int c, int z, int t, int x) {
        return 1000 + 300 * c + 40 * z + t * t + (z == 1 ? 2 * x : 0);
    }

    /**
     * One NDTiff dataset: {@code positions} positions (a {@code position} axis) of C x Z x T
     * 16-bit 12 x 8 images, XPositionUm/YPositionUm in each image's metadata.
     *
     * @param skip "t,c,z" of an image to leave out, or null
     */
    private static Path dataset(Path dir, int positions, int channels, int slices, int frames, String skip)
            throws IOException {
        JsonObject summary = JsonParser.parseString("{\"Prefix\":\"acq\",\"ChNames\":[\"SHG\",\"TPEF\",\"X\"],"
                        + "\"Frames\":" + frames + ",\"Slices\":" + slices + ",\"z-step_um\":2.0}")
                .getAsJsonObject();
        try (NdTiffWriter w = new NdTiffWriter(dir, "acq", summary)) {
            for (int t = 0; t < frames; t++) {
                for (int p = 0; p < positions; p++) {
                    for (int c = 0; c < channels; c++) {
                        for (int z = 0; z < slices; z++) {
                            if ((t + "," + c + "," + z).equals(skip)) continue;
                            ByteBuffer px = ByteBuffer.allocate(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < W * H; i++) px.putShort((short) value(c, z, t, i % W));
                            JsonObject axes = new JsonObject();
                            axes.addProperty("channel", c);
                            axes.addProperty("z", z);
                            axes.addProperty("time", t);
                            axes.addProperty("position", p);
                            JsonObject md = new JsonObject();
                            md.addProperty("XPositionUm", 100.0 * p);
                            md.addProperty("YPositionUm", 0.0);
                            md.addProperty("PixelSizeUm", 1.0);
                            md.addProperty("Frame", t);
                            w.add(axes, W, H, true, px.array(), md);
                        }
                    }
                }
            }
        }
        return dir;
    }

    private static FlatfieldProfile profile() throws Exception {
        return FlatfieldProfile.load(
                Path.of(Objects.requireNonNull(NdTiffPreprocessTest.class.getResource("/flatfield-profile/profile.yml"))
                                .toURI())
                        .getParent());
    }

    /** As FlatfieldPreprocessingTest: flat-field 0.8 for x below 6, 1.25 from there, offset 100, channel 0. */
    private static int corrected(double raw, int x) {
        double flatfield = x < 6 ? 0.8 : 1.25;
        return (int) Math.floor((raw - 100) / flatfield + 100 + 0.5);
    }

    /** Each image of a dataset, keyed by its axes as compact JSON with sorted keys, read by offset. */
    private static Map<String, int[]> pixels(Path dir) throws IOException {
        Map<String, int[]> out = new HashMap<>();
        for (NdTiffDataset.Entry e : NdTiffDataset.readIndex(dir.resolve(NdTiffDataset.INDEX))) {
            try (FileChannel ch = FileChannel.open(dir.resolve(e.fileName()), StandardOpenOption.READ)) {
                ByteBuffer b = ByteBuffer.allocate(e.width() * e.height() * 2).order(ByteOrder.LITTLE_ENDIAN);
                while (b.hasRemaining() && ch.read(b, e.pixelOffset() + b.position()) > 0) {}
                b.flip();
                int[] v = new int[e.width() * e.height()];
                for (int i = 0; i < v.length; i++) v[i] = Short.toUnsignedInt(b.getShort());
                out.put(key(e.axes()), v);
            }
        }
        return out;
    }

    private static String key(JsonObject axes) {
        StringBuilder sb = new StringBuilder();
        new java.util.TreeSet<>(axes.keySet())
                .forEach(k -> sb.append(k).append('=').append(axes.get(k)).append(';'));
        return sb.toString();
    }

    @Test
    void averageFlatfieldAndProjectWriteAnNdTiffCopy(@TempDir Path tmp) throws Exception {
        Path src = dataset(tmp.resolve("acq_1"), 2, 2, 2, 3, null);
        Options options = new Options(true, profile(), true);
        Path out = MicroManagerPreprocessor.defaultOutput(src, options);
        var result = MicroManagerPreprocessor.run(src, out, options);
        assertEquals(2, result.positions());
        assertEquals("3", result.frameSummary());
        assertEquals("2", result.sliceSummary());
        assertTrue(Files.isRegularFile(out.resolve("acq_NDTiffStack.tif")));

        Map<String, int[]> px = pixels(out);
        assertEquals(4, px.size(), "2 positions x 2 channels, one time point, one slice");
        // Mean over t of t*t for t = 0, 1, 2 is 5/3; z1 is brighter by 40 + 2x, so it wins the max.
        double mean = 1000 + 40 + 5 / 3.0;
        for (int p = 0; p < 2; p++) {
            int[] c0 = px.get("channel=0;position=" + p + ";");
            int[] c1 = px.get("channel=1;position=" + p + ";");
            for (int i = 0; i < W * H; i++) {
                int x = i % W;
                assertEquals(corrected(mean + 2 * x, x), c0[i], "channel 0 corrected before projection, x " + x);
                assertEquals(Math.round(mean + 300 + 2 * x), c1[i], "channel 1 has no profile, x " + x);
            }
        }

        NdTiffDataset copy = NdTiffDataset.read(out);
        NdTiffDataset.Image first = copy.images().get(0);
        JsonObject md = copy.metadata(first);
        assertEquals(3, md.get("FramesAveraged").getAsInt());
        assertEquals(2, md.get("SlicesMaxProjected").getAsInt());
        assertEquals(0, md.get("Frame").getAsInt(), "the first frame's metadata");
        assertEquals(1, copy.summary().get("Frames").getAsInt());
        assertEquals(1, copy.summary().get("Slices").getAsInt());
        assertTrue(copy.summary().has("Preprocessing"));
        assertEquals(List.of("SHG", "TPEF", "X"), copy.channelNames());
        assertTrue(Files.isRegularFile(out.resolve(MicroManagerPreprocessor.RECORD_FILENAME)));
    }

    @Test
    void projectionAloneKeepsEachTimePointWithItsOwnMetadata(@TempDir Path tmp) throws Exception {
        Path src = dataset(tmp.resolve("acq_1"), 1, 2, 3, 2, null);
        Path out = tmp.resolve("mip");
        MicroManagerPreprocessor.run(src, out, new Options(false, null, true));
        Map<String, int[]> px = pixels(out);
        assertEquals(4, px.size(), "2 channels x 2 time points");
        for (int t = 0; t < 2; t++) {
            for (int c = 0; c < 2; c++) {
                int[] v = px.get("channel=" + c + ";position=0;time=" + t + ";");
                // z2 is 80 brighter; z1 is 40 + 2x brighter: the max is z2 below x = 20.
                assertEquals(value(c, 2, t, 0), v[0], "c" + c + " t" + t);
            }
        }
        NdTiffDataset copy = NdTiffDataset.read(out);
        for (NdTiffDataset.Image im : copy.images()) {
            assertEquals(im.coord().t(), copy.metadata(im).get("Frame").getAsInt(), "its own time point's block");
            assertFalse(im.entry().axes().has("z"), "no z axis after projection");
        }
    }

    @Test
    void anUnfinishedLastTimePointIsLeftOutAndNoted(@TempDir Path tmp) throws Exception {
        Path src = dataset(tmp.resolve("acq_1"), 1, 2, 2, 3, "2,1,1");
        Path out = tmp.resolve("mip");
        var result = MicroManagerPreprocessor.run(src, out, new Options(false, null, true));
        assertTrue(
                result.notes().stream().anyMatch(n -> n.contains("time point 2 has 3 of 4 planes")),
                result.notes().toString());
        assertEquals(4, pixels(out).size(), "time points 0 and 1, two channels each");
    }

    @Test
    void averagingCountsTheFramesAnImageHas(@TempDir Path tmp) throws Exception {
        Path src = dataset(tmp.resolve("acq_1"), 1, 1, 1, 3, "2,0,0");
        var result = MicroManagerPreprocessor.run(src, tmp.resolve("avg"), new Options(true, null, false));
        int[] v = pixels(tmp.resolve("avg")).get("channel=0;position=0;z=0;");
        assertEquals(Math.round(1000 + 0.5), v[0], "mean over the two frames present");
        assertEquals("2", result.frameSummary());
    }

    @Test
    void channelNamesAsAxisValuesStayWithTheirImages(@TempDir Path tmp) throws Exception {
        // NDTiff 3.2: the channel axis holds names, here written in the opposite order to ChNames.
        Path src = tmp.resolve("named");
        JsonObject summary =
                JsonParser.parseString("{\"ChNames\":[\"SHG\",\"TPEF\"]}").getAsJsonObject();
        try (NdTiffWriter w = new NdTiffWriter(src, "named", summary)) {
            for (int t = 0; t < 2; t++) {
                for (String ch : List.of("TPEF", "SHG")) {
                    ByteBuffer px = ByteBuffer.allocate(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
                    int v = (ch.equals("SHG") ? 500 : 900) + 10 * t;
                    for (int i = 0; i < W * H; i++) px.putShort((short) v);
                    JsonObject axes = new JsonObject();
                    axes.addProperty("channel", ch);
                    axes.addProperty("time", t);
                    JsonObject md = new JsonObject();
                    md.addProperty("XPositionUm", 0.0);
                    md.addProperty("YPositionUm", 0.0);
                    w.add(axes, W, H, true, px.array(), md);
                }
            }
        }
        Path out = tmp.resolve("avg");
        MicroManagerPreprocessor.run(src, out, new Options(true, null, false));
        Map<String, int[]> px = pixels(out);
        assertEquals(505, px.get("channel=\"SHG\";")[0]);
        assertEquals(905, px.get("channel=\"TPEF\";")[0]);
        NdTiffDataset copy = NdTiffDataset.read(out);
        for (NdTiffDataset.Image im : copy.images()) {
            String name = im.entry().axes().get("channel").getAsString();
            assertEquals(name.equals("SHG") ? 0 : 1, im.coord().c(), "ordered by Summary.ChNames");
        }
    }

    @Test
    void theDialogSeesTheShapeFromTheIndex(@TempDir Path tmp) throws Exception {
        Path src = dataset(tmp.resolve("acq_1"), 2, 2, 3, 4, null);
        assertEquals(new MicroManagerPreprocessor.Shape(4, 3), MicroManagerPreprocessor.shape(src.toFile()));
        Path flat = dataset(tmp.resolve("flat"), 2, 1, 1, 1, null);
        assertEquals(new MicroManagerPreprocessor.Shape(1, 1), MicroManagerPreprocessor.shape(flat.toFile()));
    }

    @Test
    void aStackFileThatWouldPassTheLimitContinuesInTheNext(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("rolled");
        JsonObject summary = JsonParser.parseString("{\"ChNames\":[\"A\"]}").getAsJsonObject();
        try (NdTiffWriter w = new NdTiffWriter(dir, "x", summary, 600)) {
            for (int t = 0; t < 5; t++) {
                byte[] px = new byte[W * H];
                java.util.Arrays.fill(px, (byte) (10 + t));
                JsonObject axes = new JsonObject();
                axes.addProperty("time", t);
                w.add(axes, W, H, false, px, new JsonObject());
            }
        }
        assertTrue(Files.isRegularFile(dir.resolve("x_NDTiffStack_1.tif")));
        NdTiffDataset ds = NdTiffDataset.read(dir);
        assertEquals(5, ds.images().size());
        for (NdTiffDataset.Image im : ds.images()) {
            assertTrue(Files.size(im.file()) <= 600);
            try (FileChannel ch = FileChannel.open(im.file(), StandardOpenOption.READ)) {
                ByteBuffer b = ByteBuffer.allocate(1);
                ch.read(b, im.entry().pixelOffset());
                assertEquals(10 + im.coord().t(), b.get(0), "time " + im.coord().t());
            }
            assertEquals(
                    im.entry().pixelOffset(),
                    TileHeaders.pageDataOffsets(im.file()).get(im.page()),
                    "the page's strip is the indexed pixels");
        }
    }
}
