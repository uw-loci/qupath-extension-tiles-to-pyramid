package qupath.ext.basicstitching.stitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

/**
 * NDTiff datasets whose tiles are {@code row}/{@code column} axes without stage coordinates, as
 * Micro-Manager Magellan's explore acquisitions write them: each tile goes at
 * {@code (column * (width - GridPixelOverlapX), row * (height - GridPixelOverlapY))} pixels. Stage
 * coordinates win when every position has them.
 */
class NdTiffGridTest {

    private static final int W = 12;
    private static final int H = 8;

    /** The value of every pixel of the tile at row, column. */
    private static int value(int row, int column) {
        return 1000 + 100 * (row + 5) + column + 5;
    }

    /**
     * A tiled dataset: one image per (row, column), 16-bit {@code W x H}.
     *
     * @param overlap GridPixelOverlapX/Y in the summary, or null for none
     * @param stageEvery stage step in um written as XPositionUm/YPositionUm on every tile (0 for none,
     *     negative for the first tile only)
     */
    private static Path tiled(Path dir, List<int[]> rowCols, int[] overlap, double stageEvery) throws IOException {
        JsonObject summary = JsonParser.parseString("{\"PixelSize_um\":0.5,\"TiledImageStorage\":true}")
                .getAsJsonObject();
        if (overlap != null) {
            summary.addProperty("GridPixelOverlapX", overlap[0]);
            summary.addProperty("GridPixelOverlapY", overlap[1]);
        }
        try (NdTiffWriter w = new NdTiffWriter(dir, "explore", summary)) {
            boolean first = true;
            for (int[] rc : rowCols) {
                ByteBuffer px = ByteBuffer.allocate(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < W * H; i++) px.putShort((short) value(rc[0], rc[1]));
                JsonObject axes = new JsonObject();
                axes.addProperty("channel", 0);
                axes.addProperty("row", rc[0]);
                axes.addProperty("column", rc[1]);
                JsonObject md = new JsonObject();
                md.addProperty("PixelSizeUm", 0.5);
                md.addProperty("GridRowIndex", rc[0]);
                md.addProperty("GridColumnIndex", rc[1]);
                if (stageEvery > 0 || (stageEvery < 0 && first)) {
                    md.addProperty("XPositionUm", rc[1] * Math.abs(stageEvery));
                    md.addProperty("YPositionUm", rc[0] * Math.abs(stageEvery));
                }
                first = false;
                w.add(axes, W, H, true, px.array(), md);
            }
        }
        return dir;
    }

    private static StitchingWorkflow.StitchingResult stitch(Path folder, Path out, boolean invertX) throws IOException {
        Files.createDirectories(out);
        StitchingConfig cfg = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                folder.toString(),
                out.toString(),
                "LZW",
                0.5,
                1.0,
                "",
                0.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        cfg.setOverlapBlend(OverlapBlend.LAST_WINS);
        boolean before = MicroManagerMetadataStrategy.flipStitchingX;
        MicroManagerMetadataStrategy.flipStitchingX = invertX;
        try {
            return StitchingWorkflow.runDetailed(cfg);
        } finally {
            MicroManagerMetadataStrategy.flipStitchingX = before;
        }
    }

    private static List<int[]> grid(int rows, int columns, int row0, int column0) {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) out.add(new int[] {row0 + r, column0 + c});
        }
        return out;
    }

    /** Check the pixel at the center of each tile. */
    private static void assertTilesAt(Path image, List<int[]> rowCols, int stepX, int stepY, int row0, int col0)
            throws Exception {
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(image.toUri())) {
            Raster r = server.readRegion(RegionRequest.createInstance(
                            server.getPath(), 1, 0, 0, server.getWidth(), server.getHeight(), 0, 0))
                    .getRaster();
            for (int[] rc : rowCols) {
                int x = (rc[1] - col0) * stepX + W / 2;
                int y = (rc[0] - row0) * stepY + H / 2;
                assertEquals(value(rc[0], rc[1]), r.getSample(x, y, 0), "row " + rc[0] + " column " + rc[1]);
            }
        }
    }

    @Test
    void theGridPixelIsColumnAndRowTimesTheStep() {
        assertArrayEquals(new int[] {0, 0}, MicroManagerMetadataStrategy.ndTiffGridPixel(0, 0, 512, 512, 0, 0));
        assertArrayEquals(new int[] {28, 0}, MicroManagerMetadataStrategy.ndTiffGridPixel(0, 1, 32, 32, 4, 4));
        assertArrayEquals(new int[] {-16, -12}, MicroManagerMetadataStrategy.ndTiffGridPixel(-2, -2, 12, 8, 4, 2));
    }

    @Test
    void tilesWithoutStageCoordinatesArePlacedOnTheirGrid(@TempDir Path tmp) throws Exception {
        List<int[]> rc = grid(2, 3, 0, 0);
        Path src = tiled(tmp.resolve("explore"), rc, new int[] {4, 2}, 0);
        var result = stitch(src, tmp.resolve("out"), false);
        assertEquals(List.of(), result.warnings());
        Path out = Path.of(result.outputs().get(0));
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(2 * (W - 4) + W, server.getWidth());
            assertEquals(1 * (H - 2) + H, server.getHeight());
        }
        assertTilesAt(out, rc, W - 4, H - 2, 0, 0);

        // The stage inversion is about stage coordinates; it does not mirror a pixel grid.
        var inverted = stitch(src, tmp.resolve("out-inverted"), true);
        assertTilesAt(Path.of(inverted.outputs().get(0)), rc, W - 4, H - 2, 0, 0);
    }

    @Test
    void negativeAndSparseIndicesKeepTheirPlaces(@TempDir Path tmp) throws Exception {
        List<int[]> rc = List.of(new int[] {-1, -1}, new int[] {0, 0}, new int[] {2, 1});
        Path src = tiled(tmp.resolve("explore"), rc, new int[] {0, 0}, 0);
        var result = stitch(src, tmp.resolve("out"), false);
        Path out = Path.of(result.outputs().get(0));
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(out.toUri())) {
            assertEquals(3 * W, server.getWidth(), "columns -1 to 1");
            assertEquals(4 * H, server.getHeight(), "rows -1 to 2");
        }
        assertTilesAt(out, rc, W, H, -1, -1);
    }

    @Test
    void stageCoordinatesOnEveryTileWin(@TempDir Path tmp) throws Exception {
        List<int[]> rc = grid(1, 3, 0, 0);
        // 5 um = 10 px apart, against a grid step of 12 - 4 = 8 px.
        Path src = tiled(tmp.resolve("explore"), rc, new int[] {4, 0}, 5.0);
        var result = stitch(src, tmp.resolve("out"), false);
        assertEquals(List.of(), result.warnings());
        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(Path.of(result.outputs().get(0)).toUri())) {
            assertEquals(2 * 10 + W, server.getWidth(), "placed by the stage");
        }
    }

    @Test
    void withSomeStageCoordinatesTheGridPlacesAllAndSaysSo(@TempDir Path tmp) throws Exception {
        List<int[]> rc = grid(1, 3, 0, 0);
        Path src = tiled(tmp.resolve("explore"), rc, new int[] {4, 0}, -5.0);
        var result = stitch(src, tmp.resolve("out"), false);
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).contains("1 of 3 NDTiff positions record stage coordinates"));
        assertTilesAt(Path.of(result.outputs().get(0)), rc, W - 4, H, 0, 0);
    }

    @Test
    void withoutOverlapFieldsTheTilesAreLeftOutWithAWarning(@TempDir Path tmp) throws Exception {
        Path src = tiled(tmp.resolve("explore"), grid(1, 2, 0, 0), null, 0);
        var result = stitch(src, tmp.resolve("out"), false);
        assertEquals(List.of(), result.outputs());
        assertTrue(
                result.warnings().stream().anyMatch(w -> w.contains("2 NDTiff position(s) were left out")),
                result.warnings().toString());
    }

    @Test
    void aSinglePositionNeedsNoCoordinates(@TempDir Path tmp) throws Exception {
        Path src = tiled(tmp.resolve("one"), List.<int[]>of(new int[] {3, 4}), null, 0);
        var result = stitch(src, tmp.resolve("out"), false);
        assertEquals(List.of(), result.warnings());
        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(Path.of(result.outputs().get(0)).toUri())) {
            assertEquals(W, server.getWidth());
        }
    }

    /**
     * Opt-in: the tiled datasets of the NDTiff test data (doi:10.5281/zenodo.7065552), which have
     * {@code row}/{@code column} axes and no {@code XPositionUm}: two Magellan explore acquisitions
     * (3 channels on a 3 x 4 grid; one channel at rows and columns -1, 0, 3 / -1, 0, 2 with 7
     * z-slices and overwritten images) and the 2-tile v2 and v3 test grids (overlap 4 px). Every
     * tile's own part of each output plane must equal its page. Run with
     * {@code T2P_NDTIFF_DATA=<the NDTiff_test_data folder>}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "T2P_NDTIFF_DATA", matches = ".+")
    void realTiledDatasetsAreLaidOutOnTheirGrid(@TempDir Path tmp) throws Exception {
        Path root = Path.of(System.getenv("T2P_NDTIFF_DATA"));
        int n = 0;
        for (String name : List.of(
                "v3/Magellan_expolore_multi_channel",
                "v3/Magellan_expolore_negative_indices_and_overwritten",
                "v3/ndtiffv3.0_stitched_test",
                "v2/ndtiffv2.0_stitched_test")) {
            Path src = root.resolve(name);
            NdTiffDataset ds = NdTiffDataset.read(NdTiffDataset.locate(src));
            int ox = ds.summary().get("GridPixelOverlapX").getAsInt();
            int oy = ds.summary().get("GridPixelOverlapY").getAsInt();
            long minRow = Long.MAX_VALUE, minCol = Long.MAX_VALUE;
            for (NdTiffDataset.Image im : ds.images()) {
                JsonObject a = im.entry().axes();
                minRow = Math.min(minRow, a.get("row").getAsLong());
                minCol = Math.min(minCol, a.get("column").getAsLong());
            }
            var result = stitch(src, tmp.resolve("out" + n++), false);
            assertEquals(List.of(), result.warnings(), name);
            assertEquals(Math.max(1, ds.channelCount()), result.outputs().size(), name + " " + result.outputs());
            for (NdTiffDataset.Image im : ds.images()) {
                int c = im.coord().c();
                JsonObject a = im.entry().axes();
                int w = im.entry().width();
                int h = im.entry().height();
                int x0 = (int) (a.get("column").getAsLong() - minCol) * (w - ox);
                int y0 = (int) (a.get("row").getAsLong() - minRow) * (h - oy);
                Raster want = page(im.file(), im.page());
                try (ImageServer<BufferedImage> server = ImageServers.buildServer(
                        Path.of(result.outputs().get(c)).toUri())) {
                    // The part no neighbor overlaps: the overlaps go to whichever tile is drawn last.
                    int ownW = w - 2 * ox;
                    int ownH = h - 2 * oy;
                    Raster got = server.readRegion(RegionRequest.createInstance(
                                    server.getPath(),
                                    1,
                                    x0 + ox,
                                    y0 + oy,
                                    ownW,
                                    ownH,
                                    im.coord().z(),
                                    im.coord().t()))
                            .getRaster();
                    for (int y = 0; y < ownH; y += 7) {
                        for (int x = 0; x < ownW; x += 5) {
                            assertEquals(
                                    want.getSample(x + ox, y + oy, 0),
                                    got.getSample(x, y, 0),
                                    name + " " + a + " at " + x + "," + y);
                        }
                    }
                }
            }
            System.out.println(name + ": " + ds.images().size() + " image(s) laid out on their grid");
        }
    }

    /**
     * Opt-in, same data: the Magellan explore acquisition with 7 z-slices, max-projected first. The
     * copy keeps the row/column axes and the summary's overlap, so it is laid out on the same grid,
     * and each tile must be the maximum over z of its pages.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "T2P_NDTIFF_DATA", matches = ".+")
    void aRealTiledDatasetProjectedFirstKeepsItsGrid(@TempDir Path tmp) throws Exception {
        Path src = Path.of(System.getenv("T2P_NDTIFF_DATA"))
                .resolve("v3/Magellan_expolore_negative_indices_and_overwritten");
        NdTiffDataset ds = NdTiffDataset.read(NdTiffDataset.locate(src));
        var options = new MicroManagerPreprocessor.Options(false, null, true);
        Path copy =
                MicroManagerPreprocessor.run(src, tmp.resolve("mip"), options).output();
        var result = stitch(copy, tmp.resolve("out"), false);
        assertEquals(List.of(), result.warnings());
        java.util.Map<String, int[]> max = new java.util.HashMap<>();
        for (NdTiffDataset.Image im : ds.images()) {
            Raster r = page(im.file(), im.page());
            int[] m = max.computeIfAbsent(im.position(), k -> new int[512 * 512]);
            for (int i = 0; i < m.length; i++) m[i] = Math.max(m[i], r.getSample(i % 512, i / 512, 0));
        }
        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(Path.of(result.outputs().get(0)).toUri())) {
            assertEquals(1, server.nZSlices());
            assertEquals(4 * 512, server.getWidth(), "columns -1 to 2");
            assertEquals(5 * 512, server.getHeight(), "rows -1 to 3");
            for (var e : max.entrySet()) {
                JsonObject a = JsonParser.parseString(e.getKey()).getAsJsonObject();
                int x0 = (int) (a.get("column").getAsLong() + 1) * 512;
                int y0 = (int) (a.get("row").getAsLong() + 1) * 512;
                Raster got = server.readRegion(
                                RegionRequest.createInstance(server.getPath(), 1, x0, y0, 512, 512, 0, 0))
                        .getRaster();
                for (int i = 0; i < 512 * 512; i += 97) {
                    assertEquals(e.getValue()[i], got.getSample(i % 512, i / 512, 0), e.getKey() + " sample " + i);
                }
            }
        }
    }

    private static Raster page(Path file, int page) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader r = ImageIO.getImageReaders(in).next();
            try {
                r.setInput(in, false, true);
                return r.read(page).getRaster();
            } finally {
                r.dispose();
            }
        }
    }
}
