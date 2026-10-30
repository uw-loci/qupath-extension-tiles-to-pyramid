package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.plugins.tiff.TIFFTagSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.Options;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * {@link MicroManagerPreprocessor}: a MicroManager acquisition with several time points (SHG
 * collected as a time series) and/or z-slices per position becomes a copy with one plane per
 * channel and position -- time points averaged, then z-slices max-projected -- that
 * {@link MicroManagerMetadataStrategy} stitches.
 */
class MicroManagerPreprocessorTest {

    private static int W = 12;
    private static int H = 8;
    private static final double PIXEL_UM = 0.5;
    private static final Options AVERAGE = new Options(true, null, false);
    private static final Options BOTH = new Options(true, null, true);

    /** Frames 0..3 of a plane differ by 0, 1, 3, 7. */
    private static final int[] T_STEP = {0, 1, 3, 7};
    /** Slices 0..2 differ by 0, 30, 10, so the brightest slice is not the last. */
    private static final int[] Z_STEP = {0, 30, 10};

    private static boolean saturate = false;

    /**
     * Pixel value at column x for position p, channel c, slice z, frame t. A z-stack adds 40 to
     * every third column of a different slice, so the per-pixel maximum differs from any one slice.
     */
    private static int value(int p, int c, int z, int t, int x, int slices) {
        if (saturate) return 65535;
        int v = 1000 * (p + 1) + 100 * c + T_STEP[t] + Z_STEP[z];
        return slices > 1 && x % slices == z ? v + 40 : v;
    }

    @Test
    void averagesEachChannelOverItsFramesAndStitchesAsOneFrame(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        int channels = 2, frames = 3;
        writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, channels, 1, frames, true);
        writePosition(src, "Pos-1-000_001", 1, 4.0, 0.0, channels, 1, frames, true);

        assertEquals(new MicroManagerPreprocessor.Shape(frames, 1), MicroManagerPreprocessor.shape(src.toFile()));

        Path out = MicroManagerPreprocessor.defaultOutput(src, AVERAGE);
        assertEquals(tmp.resolve("shg_averaged"), out);
        var result = MicroManagerPreprocessor.run(src, out, AVERAGE);
        assertEquals(2, result.positions());
        assertEquals("3", result.frameSummary());
        assertTrue(Files.isRegularFile(out.resolve(MicroManagerPreprocessor.RECORD_FILENAME)));

        // Pages are channels, in order; each is the rounded mean: (0 + 1 + 3) / 3 = 1.33 -> 1
        Path tif = out.resolve("acq_MMStack_Pos-1-000_001.ome.tif");
        List<int[]> pages = readPages(tif);
        assertEquals(channels, pages.size(), "one page per channel, no frames left");
        for (int c = 0; c < channels; c++) {
            int expected = 1000 * 2 + 100 * c + 1;
            for (int v : pages.get(c)) assertEquals(expected, v, "channel " + c);
        }

        JsonObject side = JsonParser.parseString(
                        Files.readString(out.resolve("acq_MMStack_Pos-1-000_001_metadata.txt")))
                .getAsJsonObject();
        JsonObject summary = side.getAsJsonObject("Summary");
        assertEquals(1, summary.get("Frames").getAsInt());
        assertEquals(
                3,
                summary.getAsJsonObject("Preprocessing").get("FramesAveraged").getAsInt());
        assertEquals(
                "acq_MMStack_Pos-1-000_001.ome.tif",
                side.getAsJsonObject("FrameKey-0-1-0").get("FileName").getAsString());
        assertFalse(side.has("FrameKey-1-0-0"), "later frames are gone");

        // The copy stitches like a one-frame acquisition: every channel of every position, placed.
        List<TileMapping> mappings =
                new MicroManagerMetadataStrategy().prepareStitching(out.toString(), PIXEL_UM, 1.0, "");
        assertEquals(4, mappings.size());
        List<TileMapping> sorted = new ArrayList<>(mappings);
        sorted.sort(Comparator.comparingInt((TileMapping m) -> m.region.getX()).thenComparingInt(m -> m.ifdIndex));
        assertEquals(0, sorted.get(0).region.getX());
        assertEquals(8, sorted.get(3).region.getX(), "4 um at 0.5 um/px");
        assertEquals("SHG", sorted.get(0).subdirName);
        assertEquals("TPEF", sorted.get(1).subdirName);
    }

    /**
     * Time points averaged first, then the z-slices max-projected per pixel: 2 channels x 3 slices x
     * 3 frames become 2 pages, each the brightest of the three frame-averaged slices at every pixel.
     */
    @Test
    void averagesThenMaxProjectsAZStack(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos0", 0, 0.0, 0.0, 2, 3, 3, true);
        writePosition(src, "Pos1", 1, 4.0, 0.0, 2, 3, 3, true);
        assertEquals(new MicroManagerPreprocessor.Shape(3, 3), MicroManagerPreprocessor.shape(src.toFile()));
        assertEquals(
                1,
                new MicroManagerMetadataStrategy()
                        .prepareStitching(src.toString(), PIXEL_UM, 1.0, "").stream()
                                .filter(m -> m.file.getName().contains("Pos0"))
                                .count(),
                "before: 18 pages are not 2 channels, so only page 0 is stitched");

        Path out = MicroManagerPreprocessor.defaultOutput(src, BOTH);
        assertEquals(tmp.resolve("shg_averaged_mip"), out);
        var result = MicroManagerPreprocessor.run(src, out, BOTH);
        assertEquals("3", result.sliceSummary());

        List<int[]> pages = readPages(out.resolve("acq_MMStack_Pos1.ome.tif"));
        assertEquals(2, pages.size(), "one page per channel: no frames, no slices");
        int[] zBonus = {40, 70, 50}; // per column x % 3: max over z of Z_STEP[z] + (x % 3 == z ? 40 : 0)
        for (int c = 0; c < 2; c++) {
            int[] px = pages.get(c);
            for (int i = 0; i < px.length; i++) {
                int x = i % W;
                assertEquals(2000 + 100 * c + 1 + zBonus[x % 3], px[i], "channel " + c + ", x " + x);
            }
        }

        JsonObject side = JsonParser.parseString(Files.readString(out.resolve("acq_MMStack_Pos1_metadata.txt")))
                .getAsJsonObject();
        JsonObject summary = side.getAsJsonObject("Summary");
        assertEquals(1, summary.get("Slices").getAsInt());
        assertEquals(1, summary.get("Frames").getAsInt());
        assertEquals(
                3,
                summary.getAsJsonObject("Preprocessing")
                        .get("SlicesMaxProjected")
                        .getAsInt());
        assertTrue(side.has("FrameKey-0-1-0"));
        assertFalse(side.has("FrameKey-0-0-1"), "other slices are gone");

        List<TileMapping> after =
                new MicroManagerMetadataStrategy().prepareStitching(out.toString(), PIXEL_UM, 1.0, "");
        assertEquals(4, after.size(), "after: both channels of both positions");
    }

    /** Projection without averaging takes the first time point, and says so in the method. */
    @Test
    void projectionAloneUsesTheFirstTimePoint(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 3, 4, true);
        Options mipOnly = new Options(false, null, true);
        Path out = MicroManagerPreprocessor.defaultOutput(src, mipOnly);
        assertEquals(tmp.resolve("shg_mip"), out);
        var result = MicroManagerPreprocessor.run(src, out, mipOnly);
        assertTrue(mipOnly.describe().startsWith("first time point only"));
        int[] zBonus = {40, 70, 50};
        int[] px = readPages(out.resolve("acq_MMStack_Pos0.ome.tif")).get(0);
        for (int i = 0; i < px.length; i++) {
            assertEquals(1000 + zBonus[(i % W) % 3], px[i], "frame 0 only, no T_STEP added");
        }
        assertEquals("4", result.frameSummary(), "the source's time points are still reported");
    }

    @Test
    void pagesWithoutTheTagFollowTheSidecarFrameKeys(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 1, 4, false);
        Path out = tmp.resolve("out");
        MicroManagerPreprocessor.run(src, out, AVERAGE);
        // (0 + 1 + 3 + 7) / 4 = 2.75 -> 3
        for (int v : readPages(out.resolve("acq_MMStack_Pos0.ome.tif")).get(0)) assertEquals(1003, v);
    }

    @Test
    void refusesToWriteIntoAFolderItDidNotMake(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 1, 2, true);
        Path out = Files.createDirectories(tmp.resolve("someone_elses"));
        Files.writeString(out.resolve("notes.txt"), "keep");
        assertThrows(IOException.class, () -> MicroManagerPreprocessor.run(src, out, AVERAGE));

        // Its own folder can be rewritten, and so can one 0.7.9 wrote.
        Path mine = tmp.resolve("mine");
        MicroManagerPreprocessor.run(src, mine, AVERAGE);
        assertDoesNotThrow(() -> MicroManagerPreprocessor.run(src, mine, AVERAGE));
        Path old = Files.createDirectories(tmp.resolve("from_0_7_9"));
        Files.writeString(old.resolve("frame_averaging.txt"), "Frame averaging (tiles-to-pyramid)");
        assertDoesNotThrow(() -> MicroManagerPreprocessor.run(src, old, AVERAGE));
    }

    /**
     * The whole path: reduce, stitch the copy through the workflow, and find the preprocessing in
     * the stitch record. A registration solve moves its solution beside the reduced tiles.
     */
    @Test
    void preprocessedCopyStitchesAndIsRecorded(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, 1, 1, 3, true);
        writePosition(src, "Pos-1-000_001", 1, 4.0, 0.0, 1, 1, 3, true);
        var result = MicroManagerPreprocessor.run(src, MicroManagerPreprocessor.defaultOutput(src, AVERAGE), AVERAGE);

        var config = new qupath.ext.basicstitching.config.StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                src.toString(),
                tmp.toString(),
                "LZW",
                PIXEL_UM,
                1.0,
                "",
                1.0,
                qupath.ext.basicstitching.config.StitchingConfig.OutputFormat.OME_TIFF);
        config.setRegistrationMode(new qupath.ext.basicstitching.registration.RegistrationMode.Solve(
                src.resolve("TileRegistration.txt"), null, (String) null));
        var reduced = config.withFolderPath(result.output().toString());
        assertEquals(
                result.output().resolve("TileRegistration.txt"),
                ((qupath.ext.basicstitching.registration.RegistrationMode.Solve) reduced.getRegistrationMode())
                        .solutionOut());
        reduced.setRegistrationMode(qupath.ext.basicstitching.registration.RegistrationMode.disabled());
        reduced.setPreprocessingRecord(qupath.ext.basicstitching.workflow.StitchInfoFile.Section.of(
                "preprocessing", java.util.Map.of("time points per position", result.frameSummary())));

        var run = qupath.ext.basicstitching.workflow.StitchingWorkflow.runDetailed(reduced);
        assertEquals(1, run.outputs().size(), "one single-channel mosaic");
        Path image = Path.of(run.outputs().get(0));
        assertTrue(image.getFileName().toString().startsWith("shg_averaged"), image.toString());
        String record = Files.readString(qupath.ext.basicstitching.workflow.StitchInfoFile.pathFor(image));
        assertTrue(record.contains("[preprocessing]"), record);
        assertTrue(record.contains("time points per position: 3"), record);
    }

    /** Saturated 16-bit frames: the sum runs far past 65535 and the mean must still be 65535. */
    @Test
    void saturatedFramesDoNotOverflow(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        saturate = true;
        try {
            writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 1, 4, true);
        } finally {
            saturate = false;
        }
        Path out = tmp.resolve("out");
        MicroManagerPreprocessor.run(src, out, AVERAGE);
        for (int v : readPages(out.resolve("acq_MMStack_Pos0.ome.tif")).get(0)) assertEquals(65535, v);
    }

    /**
     * Single-plane TIFF series, the layout of the 2026-10-27 SHG run: a folder per position with one
     * file per frame. Every frame used to be stitched as its own tile, stacked on the others.
     */
    @Test
    void singlePlaneSeriesAveragesIntoOneFilePerPosition(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writeSinglePlanePosition(src, "Tile_R0_C0", 0, 0.0, 0.0, 1, 3);
        writeSinglePlanePosition(src, "Tile_R0_C1", 1, 4.0, 0.0, 1, 3);
        Files.writeString(src.resolve("comments.txt"), "{}");
        assertEquals(new MicroManagerPreprocessor.Shape(3, 1), MicroManagerPreprocessor.shape(src.toFile()));
        assertEquals(
                6,
                new MicroManagerMetadataStrategy()
                        .prepareStitching(src.toString(), PIXEL_UM, 1.0, "")
                        .size(),
                "before: every frame is a tile");

        Path out = tmp.resolve("shg_averaged");
        var result = MicroManagerPreprocessor.run(src, out, AVERAGE);
        assertEquals(2, result.positions());
        assertEquals("3", result.frameSummary());
        assertTrue(Files.isRegularFile(out.resolve("comments.txt")));

        Path pos = out.resolve("Tile_R0_C1");
        try (var files = Files.list(pos)) {
            assertEquals(
                    List.of("img_channel000_position001_time000000000_z000.tif", "metadata.txt"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        for (int v : readPages(pos.resolve("img_channel000_position001_time000000000_z000.tif"))
                .get(0)) {
            assertEquals(2001, v, "(0 + 1 + 3) / 3 rounds to 1");
        }
        JsonObject side = JsonParser.parseString(Files.readString(pos.resolve("metadata.txt")))
                .getAsJsonObject();
        assertEquals(1, side.getAsJsonObject("Summary").get("Frames").getAsInt());
        assertTrue(side.has("Metadata-Tile_R0_C1/img_channel000_position001_time000000000_z000.tif"));
        assertTrue(side.has("Coords-Tile_R0_C1/img_channel000_position001_time000000000_z000.tif"));
        assertFalse(side.has("Metadata-Tile_R0_C1/img_channel000_position001_time000000001_z000.tif"));

        List<TileMapping> after =
                new MicroManagerMetadataStrategy().prepareStitching(out.toString(), PIXEL_UM, 1.0, "");
        assertEquals(2, after.size(), "after: one tile per position");
        assertEquals(8, after.stream().mapToInt(m -> m.region.getX()).max().getAsInt());
    }

    /** A single-plane z-stack time series: 3 slices x 2 frames per position become one file. */
    @Test
    void singlePlaneSeriesAveragesThenProjects(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writeSinglePlanePosition(src, "Tile_R0_C0", 0, 0.0, 0.0, 3, 2);
        writeSinglePlanePosition(src, "Tile_R0_C1", 1, 4.0, 0.0, 3, 2);
        assertEquals(
                12,
                new MicroManagerMetadataStrategy()
                        .prepareStitching(src.toString(), PIXEL_UM, 1.0, "")
                        .size(),
                "before: every slice of every frame is a tile");

        Path out = MicroManagerPreprocessor.defaultOutput(src, BOTH);
        MicroManagerPreprocessor.run(src, out, BOTH);
        Path pos = out.resolve("Tile_R0_C0");
        try (var files = Files.list(pos)) {
            assertEquals(
                    List.of("img_channel000_position000_time000000000_z000.tif", "metadata.txt"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        int[] zBonus = {40, 70, 50};
        int[] px = readPages(pos.resolve("img_channel000_position000_time000000000_z000.tif"))
                .get(0);
        for (int i = 0; i < px.length; i++) {
            // (0 + 1) / 2 = 0.5 rounds up to 1
            assertEquals(1000 + 1 + zBonus[(i % W) % 3], px[i]);
        }
        JsonObject summary = JsonParser.parseString(Files.readString(pos.resolve("metadata.txt")))
                .getAsJsonObject()
                .getAsJsonObject("Summary");
        assertEquals(1, summary.get("Slices").getAsInt());
        assertEquals(
                2,
                new MicroManagerMetadataStrategy()
                        .prepareStitching(out.toString(), PIXEL_UM, 1.0, "")
                        .size(),
                "after: one tile per position");
    }

    // ---- synthetic MicroManager acquisition ----

    /** One single-plane position folder: one 16-bit file per slice and frame, and a metadata.txt. */
    private static void writeSinglePlanePosition(
            Path root, String label, int p, double xUm, double yUm, int slices, int frames) throws IOException {
        Path dir = Files.createDirectories(root.resolve(label));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":{\"Channels\":1,\"Slices\":")
                .append(slices)
                .append(",\"Frames\":")
                .append(frames);
        sb.append(",\"ChNames\":[\"Default\"],\"IntendedDimensions\":{\"time\":")
                .append(frames)
                .append(",\"z\":")
                .append(slices)
                .append("}}");
        for (int t = 0; t < frames; t++) {
            for (int z = 0; z < slices; z++) {
                String name = String.format("img_channel000_position%03d_time%09d_z%03d.tif", p, t, z);
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_GRAY);
                WritableRaster r = img.getRaster();
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < W; x++) r.setSample(x, y, 0, value(p, 0, z, t, x, slices));
                ImageIO.write(img, "TIFF", dir.resolve(name).toFile());
                String rel = label + "/" + name;
                sb.append(",\"Coords-")
                        .append(rel)
                        .append("\":{\"ChannelIndex\":0,\"SliceIndex\":")
                        .append(z);
                sb.append(",\"FrameIndex\":").append(t).append("}");
                sb.append(",\"Metadata-")
                        .append(rel)
                        .append("\":{\"FileName\":\"")
                        .append(rel);
                sb.append("\",\"XPositionUm\":")
                        .append(xUm)
                        .append(",\"YPositionUm\":")
                        .append(yUm);
                sb.append(",\"PixelSizeUm\":").append(PIXEL_UM);
                sb.append(",\"PositionName\":\"").append(label).append("\"}");
            }
        }
        sb.append("}");
        Files.writeString(dir.resolve("metadata.txt"), sb.toString());
    }

    /** One position: pages written frame by frame, slice by slice, channel fastest, as MicroManager does. */
    static void setSize(int width, int height) {
        W = width;
        H = height;
    }

    static void writePosition(
            Path dir, String label, int p, double xUm, double yUm, int channels, int slices, int frames, boolean tag)
            throws IOException {
        String file = "acq_MMStack_" + label + ".ome.tif";
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (var out = ImageIO.createImageOutputStream(dir.resolve(file).toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int t = 0; t < frames; t++) {
                for (int z = 0; z < slices; z++) {
                    for (int c = 0; c < channels; c++) {
                        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_GRAY);
                        WritableRaster r = img.getRaster();
                        for (int y = 0; y < H; y++)
                            for (int x = 0; x < W; x++) r.setSample(x, y, 0, value(p, c, z, t, x, slices));
                        IIOImage page = new IIOImage(img, null, null);
                        if (tag) {
                            TIFFDirectory d =
                                    new TIFFDirectory(new TIFFTagSet[] {BaselineTIFFTagSet.getInstance()}, null);
                            String json = "{\"ChannelIndex\":" + c + ",\"SliceIndex\":" + z + ",\"FrameIndex\":" + t
                                    + ",\"PositionName\":\"" + label + "\"}";
                            TIFFTag mm = new TIFFTag("MicroManagerMetadata", 51123, 1 << TIFFTag.TIFF_ASCII);
                            d.addTIFFField(new TIFFField(mm, TIFFTag.TIFF_ASCII, 1, new String[] {json}));
                            page = new IIOImage(img, null, d.getAsMetadata());
                        }
                        writer.writeToSequence(page, null);
                    }
                }
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":{\"Channels\":").append(channels);
        sb.append(",\"Slices\":").append(slices).append(",\"Frames\":").append(frames);
        sb.append(",\"ChNames\":[\"SHG\"")
                .append(channels > 1 ? ",\"TPEF\"" : "")
                .append("]");
        sb.append(",\"IntendedDimensions\":{\"time\":")
                .append(frames)
                .append(",\"z\":")
                .append(slices)
                .append("}");
        sb.append(",\"StagePositions\":[{\"Label\":\"").append(label).append("\",\"DefaultXYStage\":\"XY\",");
        sb.append("\"DevicePositions\":[{\"Device\":\"XY\",\"Position_um\":[")
                .append(xUm)
                .append(",")
                .append(yUm);
        sb.append("]}]}]}");
        for (int t = 0; t < frames; t++) {
            for (int z = 0; z < slices; z++) {
                for (int c = 0; c < channels; c++) {
                    sb.append(",\"FrameKey-")
                            .append(t)
                            .append("-")
                            .append(c)
                            .append("-")
                            .append(z)
                            .append("\":{");
                    sb.append("\"FileName\":\"")
                            .append(file)
                            .append("\",\"XPositionUm\":")
                            .append(xUm);
                    sb.append(",\"YPositionUm\":")
                            .append(yUm)
                            .append(",\"PixelSizeUm\":")
                            .append(PIXEL_UM);
                    sb.append("}");
                }
            }
        }
        sb.append("}");
        Files.writeString(dir.resolve("acq_MMStack_" + label + "_metadata.txt"), sb.toString());
    }

    static List<int[]> readPages(Path tif) throws IOException {
        List<int[]> out = new ArrayList<>();
        try (var in = ImageIO.createImageInputStream(tif.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            int n = reader.getNumImages(true);
            for (int i = 0; i < n; i++) {
                var r = reader.read(i).getRaster();
                out.add(r.getSamples(0, 0, r.getWidth(), r.getHeight(), 0, (int[]) null));
            }
            reader.dispose();
        }
        return out;
    }
}
