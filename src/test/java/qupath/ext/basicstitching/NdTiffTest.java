package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.MicroManagerFixture.Layout;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.FolderDiagnosis;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.NdTiffDataset;
import qupath.ext.basicstitching.stitching.PlaneProjection;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

/**
 * Micro-Manager NDTiff datasets: the index read, each image mapped to its file, page and plane,
 * and the folder diagnosis. The full stitch and merge of this layout run in
 * {@link MicroManagerCztStitchTest} and {@link MicroManagerChannelOrderTest}.
 */
class NdTiffTest {

    @Test
    void everyImageMapsToItsPageAndPlane(@TempDir Path tmp) throws Exception {
        // 4 positions x 3 channels x 2 z x 2 t = 48 images, split over two stack files.
        Path src = MicroManagerFixture.of(Layout.NDTIFF, 3, 2, 2)
                .tile(64)
                .ndSplitAfter(30)
                .write(tmp);
        assertTrue(Files.isRegularFile(src.resolve("MDA_region_NDTiffStack_1.tif")), "a second stack file");
        NdTiffDataset ds = NdTiffDataset.read(src);
        assertEquals(48, ds.images().size());
        assertEquals(List.of("DAPI", "FITC", "TRITC"), ds.channelNames());
        assertEquals(4, ds.firstImageOfEachPosition().size());

        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertEquals(List.of(), strategy.warnings());
        assertEquals(List.of("DAPI", "FITC", "TRITC"), strategy.groupOrder());
        Map<String, Integer> groups = new TreeMap<>();
        mappings.forEach(m -> groups.merge(m.subdirName, 1, Integer::sum));
        assertEquals(Map.of("DAPI", 16, "FITC", 16, "TRITC", 16), groups);

        // Read each mapped page back: its pixels must be the plane the mapping says it is.
        String[] names = {"DAPI", "FITC", "TRITC"};
        Set<String> seen = new HashSet<>();
        for (TileMapping m : mappings) {
            int c = List.of(names).indexOf(m.subdirName);
            int want = MicroManagerFixture.value(c, m.region.getZ(), m.region.getT());
            assertEquals(want, pixel(m.file.toPath(), m.ifdIndex), m.file.getName() + " page " + m.ifdIndex);
            assertTrue(seen.add(m.file.getName() + "#" + m.ifdIndex), "each page used once");
        }
        int step = 64 * 9 / 10;
        assertTrue(mappings.stream().anyMatch(m -> m.region.getX() == step && m.region.getY() == step));
    }

    @Test
    void channelAxisNamesFollowTheSummary(@TempDir Path tmp) throws Exception {
        // NDTiff 3.2 lets an axis hold names; Summary.ChNames still orders them.
        Path src = MicroManagerFixture.of(Layout.NDTIFF, 3, 1, 1)
                .tile(32)
                .names("TRITC", "DAPI", "FITC")
                .ndChannelNames()
                .write(tmp);
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertEquals(List.of("TRITC", "DAPI", "FITC"), strategy.groupOrder());
        for (TileMapping m : mappings) {
            int c = List.of("TRITC", "DAPI", "FITC").indexOf(m.subdirName);
            assertEquals(MicroManagerFixture.value(c, 0, 0), pixel(m.file.toPath(), m.ifdIndex));
        }
    }

    @Test
    void theDialogReadsChannelsZStepAndPixelSize(@TempDir Path tmp) throws Exception {
        Path src = MicroManagerFixture.of(Layout.NDTIFF, 3, 2, 1).tile(32).write(tmp);
        assertEquals(3, MicroManagerMetadataStrategy.countChannels(src.toFile()));
        assertEquals(2.5, MicroManagerMetadataStrategy.detectZStepUm(src.toFile()));
        assertEquals(MicroManagerFixture.PX_UM, MicroManagerMetadataStrategy.detectPixelSizeUm(src.toFile()));
    }

    @Test
    void diagnosisNamesTheMethodAndRefusesAStackWithoutIndex(@TempDir Path tmp) throws Exception {
        Path src = MicroManagerFixture.of(Layout.NDTIFF, 1, 1, 1).tile(32).write(tmp);
        List<FolderDiagnosis.Finding> found = FolderDiagnosis.diagnose(src.toFile());
        assertTrue(
                found.stream()
                        .anyMatch(f -> f.method().equals("MicroManager metadata (MMStack or TIFF series)")
                                && f.evidence().contains("NDTiff")
                                && !f.onlyInSubFolders()),
                found.toString());
        assertEquals(List.of(), FolderDiagnosis.unsupported(src.toFile()));

        Files.delete(src.resolve(NdTiffDataset.INDEX));
        List<String> unsupported = FolderDiagnosis.unsupported(src.toFile());
        assertEquals(1, unsupported.size());
        assertTrue(unsupported.get(0).contains("no NDTiff.index"), unsupported.get(0));
        assertTrue(new MicroManagerMetadataStrategy()
                .prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "")
                .isEmpty());
    }

    /**
     * Opt-in: a real Micro-Manager NDTiff dataset, {@code mm_mda_tcz_15} from the NDTiff test data
     * (doi:10.5281/zenodo.7065552): one position, 2 channels, 7 z-slices, 8 time points of 512 x 512
     * 16-bit. Every plane of the stitched output must equal the page it came from. Run with
     * {@code T2P_NDTIFF_SAMPLE=<path to mm_mda_tcz_15>}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "T2P_NDTIFF_SAMPLE", matches = ".+")
    void aRealMicroManagerDatasetStitchesPlaneForPlane(@TempDir Path tmp) throws Exception {
        Path src = Path.of(System.getenv("T2P_NDTIFF_SAMPLE"));
        NdTiffDataset ds = NdTiffDataset.read(src);
        assertEquals(112, ds.images().size());
        assertEquals(List.of("Cy5", "DAPI"), ds.channelNames());

        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingConfig cfg = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                src.toString(),
                out.toString(),
                "LZW",
                1.0,
                1.0,
                "",
                5.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        cfg.setOverlapBlend(OverlapBlend.LAST_WINS);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
        assertEquals(2, result.outputs().size(), result.outputs().toString());
        for (int c = 0; c < 2; c++) {
            String output = result.outputs().get(c);
            try (ImageServer<BufferedImage> server =
                    ImageServers.buildServer(Path.of(output).toUri())) {
                assertEquals(512, server.getWidth());
                assertEquals(7, server.nZSlices());
                assertEquals(8, server.nTimepoints());
                for (NdTiffDataset.Image im : ds.images()) {
                    if (im.coord().c() != c) continue;
                    Raster got = server.readRegion(RegionRequest.createInstance(
                                    server.getPath(),
                                    1,
                                    0,
                                    0,
                                    512,
                                    512,
                                    im.coord().z(),
                                    im.coord().t()))
                            .getRaster();
                    Raster want = page(im.file(), im.page());
                    for (int y = 0; y < 512; y += 37) {
                        for (int x = 0; x < 512; x += 41) {
                            assertEquals(
                                    want.getSample(x, y, 0),
                                    got.getSample(x, y, 0),
                                    im.entry().axes() + " at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }

    /**
     * Opt-in, same dataset: averaged over time then max-projected over z, and projected alone (each
     * time point kept), through the NDTiff preprocessing copy and the stitch. Every sampled pixel must
     * equal the value computed here from the source pages. With {@code T2P_NDTIFF_KEEP=<folder>} the
     * copies are kept there, for a check with another NDTiff reader.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "T2P_NDTIFF_SAMPLE", matches = ".+")
    void aRealDatasetAveragedAndProjectedMatchesItsPages(@TempDir Path tmp) throws Exception {
        Path src = Path.of(System.getenv("T2P_NDTIFF_SAMPLE"));
        Path keep = System.getenv("T2P_NDTIFF_KEEP") != null ? Path.of(System.getenv("T2P_NDTIFF_KEEP")) : tmp;
        NdTiffDataset ds = NdTiffDataset.read(src);
        int step = 23;
        int n = (512 + step - 1) / step;
        // source[c][z][t] sampled at (x, y) = (i % n * step, i / n * step)
        int[][][][] source = new int[2][7][8][n * n];
        for (NdTiffDataset.Image im : ds.images()) {
            Raster r = page(im.file(), im.page());
            var k = im.coord();
            for (int i = 0; i < n * n; i++) {
                source[k.c()][k.z()][k.t()][i] = r.getSample(i % n * step, i / n * step, 0);
            }
        }
        for (boolean average : new boolean[] {true, false}) {
            var options = new qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.Options(
                    (average ? PlaneProjection.MEAN : PlaneProjection.NONE), null, PlaneProjection.MAX);
            Path copy = qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.run(
                            src, keep.resolve("mm_mda_tcz_15" + options.folderSuffix()), options)
                    .output();
            Path out = Files.createDirectories(tmp.resolve("out" + average));
            StitchingConfig cfg = new StitchingConfig(
                    "MicroManager metadata (MMStack or TIFF series)",
                    copy.toString(),
                    out.toString(),
                    "LZW",
                    1.0,
                    1.0,
                    "",
                    0.0,
                    StitchingConfig.OutputFormat.OME_TIFF);
            cfg.setOverlapBlend(OverlapBlend.LAST_WINS);
            StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
            assertEquals(List.of(), result.warnings());
            assertEquals(2, result.outputs().size(), result.outputs().toString());
            int tOut = average ? 1 : 8;
            for (int c = 0; c < 2; c++) {
                try (ImageServer<BufferedImage> server = ImageServers.buildServer(
                        Path.of(result.outputs().get(c)).toUri())) {
                    assertEquals(1, server.nZSlices());
                    assertEquals(tOut, server.nTimepoints());
                    for (int t = 0; t < tOut; t++) {
                        Raster got = server.readRegion(
                                        RegionRequest.createInstance(server.getPath(), 1, 0, 0, 512, 512, 0, t))
                                .getRaster();
                        for (int i = 0; i < n * n; i++) {
                            double max = Double.NEGATIVE_INFINITY;
                            for (int z = 0; z < 7; z++) {
                                double v = 0;
                                if (average) {
                                    for (int f = 0; f < 8; f++) v += source[c][z][f][i];
                                    v /= 8;
                                } else {
                                    v = source[c][z][t][i];
                                }
                                max = Math.max(max, v);
                            }
                            assertEquals(
                                    Math.round(max),
                                    got.getSample(i % n * step, i / n * step, 0),
                                    "c" + c + " t" + t + " sample " + i);
                        }
                    }
                }
            }
        }
    }

    /**
     * Opt-in: {@code Large_aborted} from the same NDTiff test data, an acquisition stopped part way
     * (2 channels, 16 z-slices, 368 complete time points and a 369th with 20 of its 32 images,
     * across two stack files, 6 GB). Projected with each time point kept, the unfinished time point
     * is left out and noted. Run with {@code T2P_NDTIFF_ABORTED=<path to Large_aborted>}; with
     * {@code T2P_NDTIFF_KEEP} the copy is kept.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "T2P_NDTIFF_ABORTED", matches = ".+")
    void aRealAbortedDatasetLeavesItsUnfinishedTimePointOut(@TempDir Path tmp) throws Exception {
        Path src = Path.of(System.getenv("T2P_NDTIFF_ABORTED"));
        Path keep = System.getenv("T2P_NDTIFF_KEEP") != null ? Path.of(System.getenv("T2P_NDTIFF_KEEP")) : tmp;
        var options = new qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.Options(
                PlaneProjection.NONE, null, PlaneProjection.MAX);
        long t0 = System.nanoTime();
        var result = qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.run(
                src, keep.resolve("Large_aborted_mip"), options);
        System.out.printf(
                "Large_aborted projected in %.1f s; notes %s%n", (System.nanoTime() - t0) / 1e9, result.notes());
        assertTrue(
                result.notes().stream().anyMatch(n -> n.contains("time point 368 has 20 of 32 planes")),
                result.notes().toString());
        NdTiffDataset copy = NdTiffDataset.read(result.output());
        assertEquals(368 * 2, copy.images().size(), "368 time points x 2 channels");
        // Spot check against the source: time points 0 and 367, max over z.
        NdTiffDataset ds = NdTiffDataset.read(src);
        for (int t : new int[] {0, 367}) {
            for (int c = 0; c < 2; c++) {
                int[] max = new int[64];
                for (NdTiffDataset.Image im : ds.images()) {
                    if (im.coord().t() != t || im.coord().c() != c) continue;
                    Raster r = page(im.file(), im.page());
                    for (int i = 0; i < 64; i++) max[i] = Math.max(max[i], r.getSample(i * 8, i * 7, 0));
                }
                NdTiffDataset.Image written = null;
                for (NdTiffDataset.Image im : copy.images()) {
                    if (im.coord().t() == t && im.coord().c() == c) written = im;
                }
                Raster got = page(written.file(), written.page());
                for (int i = 0; i < 64; i++) {
                    assertEquals(max[i], got.getSample(i * 8, i * 7, 0), "t" + t + " c" + c + " sample " + i);
                }
            }
        }
    }

    private static int pixel(Path file, int page) throws Exception {
        return page(file, page).getSample(1, 1, 0);
    }

    private static Raster page(Path file, int page) throws Exception {
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
