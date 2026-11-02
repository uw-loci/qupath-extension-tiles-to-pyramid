package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.basicstitching.MicroManagerFixture.Layout;
import qupath.ext.basicstitching.assembly.direct.OverlapBlend;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.functions.StitchingGUI;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

/**
 * The dialog's channel merge keeps a Micro-Manager acquisition's channel order and names. Until
 * 2026-11 the merge sorted the per-channel stitches by file name, so channels acquired as TRITC,
 * DAPI, FITC came out as DAPI, FITC, TRITC.
 */
class MicroManagerChannelOrderTest {

    /** Acquisition order that is not alphabetical. */
    private static final String[] ACQUIRED = {"TRITC", "DAPI", "FITC"};

    @ParameterizedTest
    @EnumSource(Layout.class)
    void mergedChannelsFollowTheAcquisition(Layout layout, @TempDir Path tmp) throws Exception {
        MicroManagerFixture fixture =
                MicroManagerFixture.of(layout, 3, 1, 1).tile(300).names(ACQUIRED);
        Path src = fixture.write(tmp);
        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingConfig cfg = config(src, out);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);

        assertTrue(result.acquisitionOrder());
        List<String> stems = result.outputs().stream()
                .map(p -> Path.of(p).getFileName().toString().replace(".ome.tif", ""))
                .toList();
        assertEquals(List.of(ACQUIRED), stems, "outputs in Summary.ChNames order");

        String merged = merge(result.outputs(), result.acquisitionOrder(), cfg);
        assertNotNull(merged);
        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(Path.of(merged).toUri())) {
            assertEquals(3, server.nChannels());
            Raster r = server.readRegion(RegionRequest.createInstance(server.getPath(), 1, 0, 0, 50, 50, 0, 0))
                    .getRaster();
            for (int c = 0; c < 3; c++) {
                assertEquals(ACQUIRED[c], server.getChannel(c).getName(), "channel " + c + " name");
                // Channel c of the merge holds the plane Micro-Manager recorded as channel c.
                assertEquals(MicroManagerFixture.value(c, 0, 0), r.getSample(10, 10, c), "channel " + c + " pixels");
            }
        }
    }

    /** Channel folders record no acquisition order: they still merge in file name order. */
    @Test
    void outputsWithoutARecordedOrderMergeAlphabetically(@TempDir Path tmp) throws Exception {
        assertTrue(new TileConfigurationTxtStrategy().groupOrder().isEmpty());
        MicroManagerFixture fixture =
                MicroManagerFixture.of(Layout.MMSTACK, 3, 1, 1).tile(300).names(ACQUIRED);
        Path src = fixture.write(tmp);
        Path out = Files.createDirectories(tmp.resolve("out"));
        StitchingConfig cfg = config(src, out);
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(cfg);
        String merged = merge(new ArrayList<>(result.outputs()), false, cfg);
        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(Path.of(merged).toUri())) {
            List<String> names = new ArrayList<>();
            for (int c = 0; c < server.nChannels(); c++) {
                names.add(server.getChannel(c).getName());
            }
            assertEquals(List.of("DAPI", "FITC", "TRITC"), names);
        }
    }

    @Test
    void anUnorderedResultSaysSo() {
        assertFalse(StitchingWorkflow.StitchingResult.empty().acquisitionOrder());
    }

    private static StitchingConfig config(Path src, Path out) {
        StitchingConfig cfg = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                src.toString(),
                out.toString(),
                "LZW",
                MicroManagerFixture.PX_UM,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        cfg.setOverlapBlend(OverlapBlend.LAST_WINS);
        return cfg;
    }

    private static String merge(List<String> outputs, boolean acquisitionOrder, StitchingConfig cfg) throws Exception {
        Method m = StitchingGUI.class.getDeclaredMethod(
                "mergeChannelOutputs", List.class, boolean.class, StitchingConfig.class);
        m.setAccessible(true);
        return (String) m.invoke(null, outputs, acquisitionOrder, cfg);
    }
}
