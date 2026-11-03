package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.TileRegistrationStep;
import qupath.lib.regions.ImageRegion;

/**
 * Registration on Micro-Manager input measures a representative plane of every position, the same
 * way in both layouts: the middle z-slice of the first time point, of the channel registration is
 * aligned on. Until 2026-11 an OME-TIFF per position was always read on its first page (first
 * channel, first slice), so an empty first channel or an out-of-focus first slice gave nothing to
 * measure; and on a single-plane series the channels could not be compared, because their files are
 * named apart.
 */
class MicroManagerRegistrationPlaneTest {

    private static final int TILE_W = 256;
    private static final int TILE_H = 192;

    /** Every plane is written in one of these; which one carries the texture is the test. */
    private enum Layout {
        MMSTACK,
        SINGLE_PLANE
    }

    /**
     * Two channels; the first (page 0 of every position) is empty. Auto must align on the second,
     * recover the injected stage error, and move both channels of a position together.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MMSTACK", "SINGLE_PLANE"})
    void anEmptyFirstChannelDoesNotStopRegistration(String layoutName, @TempDir Path tmp) throws IOException {
        Layout layout = Layout.valueOf(layoutName);
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(
                Files.createDirectories(tmp.resolve("tex")), 3, 3, TILE_W, TILE_H, 0.15, 3.0, 42);
        // Planes per position, in page order: (channel, z) = (0, 0) empty, (1, 0) textured.
        String[] channel = {"EMPTY", "TEXTURED"};
        int[][] planes = {{0, 0}, {1, 0}};
        boolean[] textured = {false, true};
        List<TileMapping> before = build(tmp, g, layout, channel, planes, textured);

        List<TileMapping> after = solve(tmp, before, RegistrationReference.auto());
        assertRecovered(g, before, after, planes.length);
        String header = String.join("\n", Files.readAllLines(tmp.resolve(TileRegistrationSolution.DEFAULT_FILENAME)));
        assertTrue(header.contains("TEXTURED"), "aligned on the channel with structure:\n" + header);
    }

    /**
     * One channel, three z-slices, of which only the middle one is in focus (the others are empty):
     * every position is measured on the middle slice.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MMSTACK", "SINGLE_PLANE"})
    void theMiddleSliceIsMeasured(String layoutName, @TempDir Path tmp) throws IOException {
        Layout layout = Layout.valueOf(layoutName);
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(
                Files.createDirectories(tmp.resolve("tex")), 3, 3, TILE_W, TILE_H, 0.15, 3.0, 7);
        String[] channel = {"SHG"};
        int[][] planes = {{0, 0}, {0, 1}, {0, 2}};
        boolean[] textured = {false, true, false};
        List<TileMapping> before = build(tmp, g, layout, channel, planes, textured);

        List<TileMapping> after = solve(tmp, before, RegistrationReference.auto());
        assertRecovered(g, before, after, planes.length);
        String header = String.join("\n", Files.readAllLines(tmp.resolve(TileRegistrationSolution.DEFAULT_FILENAME)));
        assertTrue(header.contains("measured plane: z-slice 1 (middle of 3)"), header);
    }

    /**
     * Mappings as the Micro-Manager strategy makes them: one per plane, z from the plane, grouped by
     * channel name. MMSTACK puts a position's planes on pages of one file; SINGLE_PLANE writes a file
     * per plane, named apart per channel and slice.
     */
    private static List<TileMapping> build(
            Path tmp, SyntheticGridFixture.Grid g, Layout layout, String[] channel, int[][] planes, boolean[] textured)
            throws IOException {
        Path acq = Files.createDirectories(tmp.resolve("acq"));
        List<TileMapping> out = new ArrayList<>();
        int p = 0;
        for (TileNode t : g.nominal()) {
            BufferedImage tex = ImageIO.read(t.file());
            BufferedImage empty = new BufferedImage(TILE_W, TILE_H, BufferedImage.TYPE_USHORT_GRAY);
            int[] flat = new int[TILE_W * TILE_H];
            Arrays.fill(flat, 20000);
            empty.getRaster().setSamples(0, 0, TILE_W, TILE_H, 0, flat);
            ImageRegion region = ImageRegion.createInstance(
                    (int) Math.round(t.xPx()), (int) Math.round(t.yPx()), TILE_W, TILE_H, 0, 0);
            if (layout == Layout.MMSTACK) {
                Path file = acq.resolve("acq_MMStack_Pos" + p + ".ome.tif");
                List<BufferedImage> pages = new ArrayList<>();
                for (boolean b : textured) pages.add(b ? tex : empty);
                writePages(file, pages);
                for (int i = 0; i < planes.length; i++) {
                    out.add(new TileMapping(file.toFile(), at(region, planes[i][1]), channel[planes[i][0]], i));
                }
            } else {
                Path dir = Files.createDirectories(acq.resolve("Pos" + p));
                for (int i = 0; i < planes.length; i++) {
                    Path file = dir.resolve(String.format(
                            "img_channel%03d_position%03d_time000000000_z%03d.tif", planes[i][0], p, planes[i][1]));
                    ImageIO.write(textured[i] ? tex : empty, "TIFF", file.toFile());
                    out.add(new TileMapping(file.toFile(), at(region, planes[i][1]), channel[planes[i][0]], 0));
                }
            }
            p++;
        }
        return out;
    }

    private static ImageRegion at(ImageRegion r, int z) {
        return ImageRegion.createInstance(r.getX(), r.getY(), r.getWidth(), r.getHeight(), z, 0);
    }

    private static List<TileMapping> solve(Path tmp, List<TileMapping> before, RegistrationReference reference) {
        StitchingConfig config = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                tmp.toString(),
                tmp.toString(),
                "LZW",
                0.5,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        config.setRegistrationMode(new RegistrationMode.Solve(
                tmp.resolve(TileRegistrationSolution.DEFAULT_FILENAME),
                RegistrationSettings.defaults().withThreads(1),
                reference));
        return TileRegistrationStep.applyTo(before, config);
    }

    /** Every position moved to within a pixel of its true place, every plane of it together. */
    private static void assertRecovered(
            SyntheticGridFixture.Grid g, List<TileMapping> before, List<TileMapping> after, int planesPerPosition) {
        assertEquals(before.size(), after.size());
        int moved = 0;
        for (int p = 0; p < g.nominal().size(); p++) {
            double[] truth = g.trueJitterPx().get(g.nominal().get(p).filename());
            int first = p * planesPerPosition;
            double[] got = shift(before, after, first);
            if (got[0] != 0 || got[1] != 0) moved++;
            assertEquals(truth[0], got[0], 1.0, "position " + p + " x");
            assertEquals(truth[1], got[1], 1.0, "position " + p + " y");
            for (int i = 1; i < planesPerPosition; i++) {
                assertArrayEquals(got, shift(before, after, first + i), 1e-9, "plane " + i + " of position " + p);
            }
        }
        assertTrue(moved > 0, "nothing moved, so the comparison proves nothing");
    }

    private static double[] shift(List<TileMapping> before, List<TileMapping> after, int i) {
        return new double[] {
            after.get(i).region.getX() - before.get(i).region.getX(),
            after.get(i).region.getY() - before.get(i).region.getY()
        };
    }

    private static void writePages(Path file, List<BufferedImage> pages) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (BufferedImage img : pages) {
                writer.writeToSequence(new IIOImage(img, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }
}
