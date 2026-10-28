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
import qupath.ext.basicstitching.stitching.MicroManagerFrameAverager;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * {@link MicroManagerFrameAverager}: a multi-frame MMStack acquisition (SHG collected as a time
 * series per position) becomes a one-frame copy that {@link MicroManagerMetadataStrategy} stitches.
 */
class MicroManagerFrameAveragerTest {

    private static final int W = 12;
    private static final int H = 8;
    private static final double PIXEL_UM = 0.5;

    /** Page value for position p, channel c, frame t: frames 0,1,2 of a plane differ by 0, 1, 3. */
    private static int value(int p, int c, int t) {
        return 1000 * (p + 1) + 100 * c + new int[] {0, 1, 3, 7}[t];
    }

    @Test
    void averagesEachChannelOverItsFramesAndStitchesAsOneFrame(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        int channels = 2, frames = 3;
        writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, channels, frames, true);
        writePosition(src, "Pos-1-000_001", 1, 4.0, 0.0, channels, frames, true);

        assertEquals(frames, MicroManagerFrameAverager.countFrames(src.toFile()));

        Path out = MicroManagerFrameAverager.defaultOutput(src);
        assertEquals(tmp.resolve("shg_averaged"), out);
        var result = MicroManagerFrameAverager.average(src, out);
        assertEquals(2, result.positions());
        assertEquals("3", result.frameSummary());
        assertTrue(Files.isRegularFile(out.resolve(MicroManagerFrameAverager.RECORD_FILENAME)));

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
        assertEquals(1, side.getAsJsonObject("Summary").get("Frames").getAsInt());
        assertEquals(
                3,
                side.getAsJsonObject("Summary")
                        .getAsJsonObject("FrameAveraging")
                        .get("FramesAveraged")
                        .getAsInt());
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

    @Test
    void pagesWithoutTheTagFollowTheSidecarFrameKeys(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 4, false);
        Path out = tmp.resolve("out");
        MicroManagerFrameAverager.average(src, out);
        // (0 + 1 + 3 + 7) / 4 = 2.75 -> 3
        for (int v : readPages(out.resolve("acq_MMStack_Pos0.ome.tif")).get(0)) assertEquals(1003, v);
    }

    @Test
    void refusesToWriteIntoAFolderItDidNotMake(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 2, true);
        Path out = Files.createDirectories(tmp.resolve("someone_elses"));
        Files.writeString(out.resolve("notes.txt"), "keep");
        assertThrows(IOException.class, () -> MicroManagerFrameAverager.average(src, out));

        // Its own folder can be rewritten.
        Path mine = tmp.resolve("mine");
        MicroManagerFrameAverager.average(src, mine);
        assertDoesNotThrow(() -> MicroManagerFrameAverager.average(src, mine));
    }

    /**
     * The whole path: average, stitch the copy through the workflow, and find the averaging in the
     * stitch record. A registration solve moves its solution beside the averaged tiles.
     */
    @Test
    void averagedCopyStitchesAndIsRecorded(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writePosition(src, "Pos-1-000_000", 0, 0.0, 0.0, 1, 3, true);
        writePosition(src, "Pos-1-000_001", 1, 4.0, 0.0, 1, 3, true);
        var result = MicroManagerFrameAverager.average(src, MicroManagerFrameAverager.defaultOutput(src));

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
        var averaged = config.withFolderPath(result.output().toString());
        assertEquals(
                result.output().resolve("TileRegistration.txt"),
                ((qupath.ext.basicstitching.registration.RegistrationMode.Solve) averaged.getRegistrationMode())
                        .solutionOut());
        averaged.setRegistrationMode(qupath.ext.basicstitching.registration.RegistrationMode.disabled());
        averaged.setPreprocessingRecord(qupath.ext.basicstitching.workflow.StitchInfoFile.Section.of(
                "frame averaging", java.util.Map.of("frames per position", result.frameSummary())));

        var run = qupath.ext.basicstitching.workflow.StitchingWorkflow.runDetailed(averaged);
        assertEquals(1, run.outputs().size(), "one single-channel mosaic");
        Path image = Path.of(run.outputs().get(0));
        assertTrue(image.getFileName().toString().startsWith("shg_averaged"), image.toString());
        String record = Files.readString(qupath.ext.basicstitching.workflow.StitchInfoFile.pathFor(image));
        assertTrue(record.contains("frame averaging"), record);
        assertTrue(record.contains("frames per position: 3"), record);
    }

    /** Saturated 16-bit frames: the sum runs far past 65535 and the mean must still be 65535. */
    @Test
    void saturatedFramesDoNotOverflow(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        saturate = true;
        try {
            writePosition(src, "Pos0", 0, 0.0, 0.0, 1, 4, true);
        } finally {
            saturate = false;
        }
        Path out = tmp.resolve("out");
        MicroManagerFrameAverager.average(src, out);
        for (int v : readPages(out.resolve("acq_MMStack_Pos0.ome.tif")).get(0)) assertEquals(65535, v);
    }

    private static boolean saturate = false;

    /**
     * Single-plane TIFF series, the layout of the 2026-10-27 SHG run: a folder per position with one
     * file per frame. Every frame used to be stitched as its own tile, stacked on the others.
     */
    @Test
    void singlePlaneSeriesAveragesIntoOneFilePerPosition(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("shg"));
        writeSinglePlanePosition(src, "Tile_R0_C0", 0, 0.0, 0.0, 3);
        writeSinglePlanePosition(src, "Tile_R0_C1", 1, 4.0, 0.0, 3);
        Files.writeString(src.resolve("comments.txt"), "{}");
        assertEquals(3, MicroManagerFrameAverager.countFrames(src.toFile()));
        assertEquals(
                6,
                new MicroManagerMetadataStrategy()
                        .prepareStitching(src.toString(), PIXEL_UM, 1.0, "")
                        .size(),
                "before: every frame is a tile");

        Path out = tmp.resolve("shg_averaged");
        var result = MicroManagerFrameAverager.average(src, out);
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

    // ---- synthetic MicroManager acquisition ----

    /** One single-plane position folder: one 16-bit file per frame, and a metadata.txt. */
    private static void writeSinglePlanePosition(Path root, String label, int p, double xUm, double yUm, int frames)
            throws IOException {
        Path dir = Files.createDirectories(root.resolve(label));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":{\"Channels\":1,\"Slices\":1,\"Frames\":").append(frames);
        sb.append(",\"ChNames\":[\"Default\"],\"IntendedDimensions\":{\"time\":")
                .append(frames)
                .append("}}");
        for (int t = 0; t < frames; t++) {
            String name = String.format("img_channel000_position%03d_time%09d_z000.tif", p, t);
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_GRAY);
            WritableRaster r = img.getRaster();
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) r.setSample(x, y, 0, value(p, 0, t));
            ImageIO.write(img, "TIFF", dir.resolve(name).toFile());
            String rel = label + "/" + name;
            sb.append(",\"Coords-")
                    .append(rel)
                    .append("\":{\"ChannelIndex\":0,\"FrameIndex\":")
                    .append(t)
                    .append("}");
            sb.append(",\"Metadata-").append(rel).append("\":{\"FileName\":\"").append(rel);
            sb.append("\",\"XPositionUm\":")
                    .append(xUm)
                    .append(",\"YPositionUm\":")
                    .append(yUm);
            sb.append(",\"PixelSizeUm\":")
                    .append(PIXEL_UM)
                    .append(",\"PositionName\":\"")
                    .append(label)
                    .append("\"}");
        }
        sb.append("}");
        Files.writeString(dir.resolve("metadata.txt"), sb.toString());
    }

    /** One position: pages written frame by frame (t outer, c inner), as MicroManager does. */
    private static void writePosition(
            Path dir, String label, int p, double xUm, double yUm, int channels, int frames, boolean tag)
            throws IOException {
        String file = "acq_MMStack_" + label + ".ome.tif";
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (var out = ImageIO.createImageOutputStream(dir.resolve(file).toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int t = 0; t < frames; t++) {
                for (int c = 0; c < channels; c++) {
                    BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_GRAY);
                    WritableRaster r = img.getRaster();
                    for (int y = 0; y < H; y++)
                        for (int x = 0; x < W; x++) r.setSample(x, y, 0, saturate ? 65535 : value(p, c, t));
                    IIOImage page = new IIOImage(img, null, null);
                    if (tag) {
                        TIFFDirectory d = new TIFFDirectory(new TIFFTagSet[] {BaselineTIFFTagSet.getInstance()}, null);
                        String json = "{\"ChannelIndex\":" + c + ",\"SliceIndex\":0,\"FrameIndex\":" + t
                                + ",\"PositionName\":\"" + label + "\"}";
                        TIFFTag mm = new TIFFTag("MicroManagerMetadata", 51123, 1 << TIFFTag.TIFF_ASCII);
                        d.addTIFFField(new TIFFField(mm, TIFFTag.TIFF_ASCII, 1, new String[] {json}));
                        page = new IIOImage(img, null, d.getAsMetadata());
                    }
                    writer.writeToSequence(page, null);
                }
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"Summary\":{\"Channels\":")
                .append(channels)
                .append(",\"Slices\":1,\"Frames\":")
                .append(frames);
        sb.append(",\"ChNames\":[\"SHG\"")
                .append(channels > 1 ? ",\"TPEF\"" : "")
                .append("]");
        sb.append(",\"IntendedDimensions\":{\"time\":").append(frames).append("}");
        sb.append(",\"StagePositions\":[{\"Label\":\"").append(label).append("\",\"DefaultXYStage\":\"XY\",");
        sb.append("\"DevicePositions\":[{\"Device\":\"XY\",\"Position_um\":[")
                .append(xUm)
                .append(",")
                .append(yUm);
        sb.append("]}]}]}");
        for (int t = 0; t < frames; t++) {
            for (int c = 0; c < channels; c++) {
                sb.append(",\"FrameKey-").append(t).append("-").append(c).append("-0\":{");
                sb.append("\"FileName\":\"")
                        .append(file)
                        .append("\",\"XPositionUm\":")
                        .append(xUm);
                sb.append(",\"YPositionUm\":")
                        .append(yUm)
                        .append(",\"PixelSizeUm\":")
                        .append(PIXEL_UM)
                        .append("}");
            }
        }
        sb.append("}");
        Files.writeString(dir.resolve("acq_MMStack_" + label + "_metadata.txt"), sb.toString());
    }

    private static List<int[]> readPages(Path tif) throws IOException {
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
