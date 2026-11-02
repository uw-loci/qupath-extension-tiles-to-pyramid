package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.MicroManagerFixture.Layout;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerPlanes;
import qupath.ext.basicstitching.stitching.MicroManagerPlanes.Coord;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * How {@link MicroManagerMetadataStrategy} places each plane of a multi-dimensional acquisition:
 * which channel's output it goes to, which page it is read from, and its z and t.
 */
class MicroManagerPlaneMappingTest {

    private static List<TileMapping> map(Path src) {
        return new MicroManagerMetadataStrategy().prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
    }

    /** subdir -> number of tiles. */
    private static Map<String, Integer> groups(List<TileMapping> mappings) {
        Map<String, Integer> out = new TreeMap<>();
        mappings.forEach(m -> out.merge(m.subdirName, 1, Integer::sum));
        return out;
    }

    @Test
    void singlePlaneChannelsGoToTheirOwnOutputs(@TempDir Path tmp) throws Exception {
        List<TileMapping> mappings =
                map(MicroManagerFixture.of(Layout.SINGLE_PLANE, 3, 1, 1).write(tmp));
        assertEquals(Map.of("DAPI", 4, "FITC", 4, "TRITC", 4), groups(mappings));
        for (TileMapping m : mappings) {
            Coord k = MicroManagerPlanes.singlePlaneCoord(m.file.getName());
            assertEquals(MicroManagerFixture.NAMES[k.c()], m.subdirName, m.file.getName());
            assertEquals(0, m.ifdIndex);
        }
    }

    @Test
    void singlePlaneZAndTComeFromTheFileName(@TempDir Path tmp) throws Exception {
        List<TileMapping> mappings =
                map(MicroManagerFixture.of(Layout.SINGLE_PLANE, 3, 3, 2).write(tmp));
        assertEquals(Map.of("DAPI", 24, "FITC", 24, "TRITC", 24), groups(mappings));
        Set<String> seen = new HashSet<>();
        for (TileMapping m : mappings) {
            Coord k = MicroManagerPlanes.singlePlaneCoord(m.file.getName());
            assertEquals(k.z(), m.region.getZ(), m.file.getName());
            assertEquals(k.t(), m.region.getT(), m.file.getName());
            assertTrue(seen.add(m.subdirName + m.region), "one tile per plane and position: " + m.region);
        }
    }

    @Test
    void mmStackPagesArePlacedByTheirTags(@TempDir Path tmp) throws Exception {
        MicroManagerFixture f = MicroManagerFixture.of(Layout.MMSTACK, 3, 3, 2).zFastest();
        List<TileMapping> mappings = map(f.write(tmp));
        assertEquals(Map.of("DAPI", 24, "FITC", 24, "TRITC", 24), groups(mappings));
        List<int[]> order = f.planeOrder();
        for (TileMapping m : mappings) {
            int[] k = order.get(m.ifdIndex);
            assertEquals(MicroManagerFixture.NAMES[k[0]], m.subdirName, "page " + m.ifdIndex);
            assertEquals(k[1], m.region.getZ(), "page " + m.ifdIndex);
            assertEquals(k[2], m.region.getT(), "page " + m.ifdIndex);
        }
    }

    @Test
    void aContinuationFileIsNotATileOfItsOwn(@TempDir Path tmp) throws Exception {
        MicroManagerFixture f = MicroManagerFixture.of(Layout.MMSTACK, 3, 3, 2).rolloverAfter(10);
        Path src = f.write(tmp);
        assertTrue(Files.exists(src.resolve("MDA_region_MMStack_Pos-1-000_000_1.ome.tif")));
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        // 4 positions x 18 planes, every (channel, z, t) once per position.
        assertEquals(72, mappings.size());
        Set<String> planes = new HashSet<>();
        for (TileMapping m : mappings) {
            assertTrue(planes.add(m.subdirName + m.region), "duplicate plane " + m.subdirName + " " + m.region);
            // The first file holds pages 0-9; the continuation holds the other 8 from its own page 0.
            int pages = m.file.getName().endsWith("_1.ome.tif") ? 8 : 10;
            assertTrue(m.ifdIndex < pages, m.file.getName() + " page " + m.ifdIndex);
        }
        assertEquals(List.of(), strategy.warnings());
    }

    @Test
    void untaggedPagesFollowTheSidecarFrameKeys(@TempDir Path tmp) throws Exception {
        MicroManagerFixture f =
                MicroManagerFixture.of(Layout.MMSTACK, 3, 3, 2).zFastest().withoutTags();
        List<TileMapping> mappings = map(f.write(tmp));
        List<int[]> order = f.planeOrder();
        assertEquals(72, mappings.size());
        for (TileMapping m : mappings) {
            int[] k = order.get(m.ifdIndex);
            assertEquals(MicroManagerFixture.NAMES[k[0]], m.subdirName);
            assertEquals(k[1], m.region.getZ());
            assertEquals(k[2], m.region.getT());
        }
    }

    /**
     * No tag, a sidecar listing only the first frame, and more pages than channels: nothing says
     * which plane a page holds. The position is read from page 0 only -- always the first channel,
     * slice and time point -- and the result dialog is told.
     */
    @Test
    void anUnreadablePageOrderFallsBackToPageZeroAndSaysSo(@TempDir Path tmp) throws Exception {
        Path src = MicroManagerFixture.of(Layout.MMSTACK, 3, 3, 1)
                .withoutTags()
                .firstFrameKeyOnly()
                .write(tmp);
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertEquals(4, mappings.size());
        for (TileMapping m : mappings) {
            assertEquals(0, m.ifdIndex);
            assertEquals("DAPI", m.subdirName, "page 0 is the first channel");
        }
        assertEquals(1, strategy.warnings().size(), strategy.warnings().toString());
        String w = strategy.warnings().get(0);
        assertTrue(w.startsWith("4 position file(s)"), w);
        assertTrue(w.contains("only their first page"), w);
    }

    @Test
    void aSinglePlaneFileWithNoCoordinatesIsLeftOutAndReported(@TempDir Path tmp) throws Exception {
        Path src = MicroManagerFixture.of(Layout.SINGLE_PLANE, 3, 1, 1).write(tmp);
        Path pos = src.resolve(MicroManagerFixture.label(0));
        Files.move(
                pos.resolve("img_channel001_position000_time000000000_z000.tif"), pos.resolve("renamed_by_hand.tif"));
        // The moved file's Metadata- and Coords- blocks still name the old file; nothing names this one.
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertEquals(Map.of("DAPI", 4, "FITC", 3, "TRITC", 4), groups(mappings));
        assertEquals(1, strategy.warnings().size(), strategy.warnings().toString());
        assertTrue(
                strategy.warnings().get(0).contains("renamed_by_hand.tif"),
                strategy.warnings().get(0));
    }

    @Test
    void singlePlaneNamesInEveryFormMicroManagerWrites() {
        assertEquals(
                new Coord(2, 4, 7),
                MicroManagerPlanes.singlePlaneCoord("img_channel002_position010_time000000007_z004.tif"));
        // Axes absent from the acquisition may be left out of the name; absent means 0.
        assertEquals(new Coord(1, 0, 3), MicroManagerPlanes.singlePlaneCoord("img_channel001_time000000003.tif"));
        assertEquals(new Coord(0, 5, 0), MicroManagerPlanes.singlePlaneCoord("img_position001_z005.tiff"));
        assertEquals(new Coord(1, 2, 0), MicroManagerPlanes.singlePlaneCoord("img_z002_channel001.TIF"));
        assertNull(MicroManagerPlanes.singlePlaneCoord("img_000000003_DAPI_000.tif"), "MM 1.4 names are not read");
        assertNull(MicroManagerPlanes.singlePlaneCoord("tile_3.tif"));
    }

    @Test
    void aMetadataBlockGivesCoordinatesWhenTheNameDoesNot() {
        var block = com.google.gson.JsonParser.parseString("{\"ChannelIndex\":2,\"SliceIndex\":1,\"FrameIndex\":3}");
        assertEquals(new Coord(2, 1, 3), MicroManagerPlanes.singlePlaneCoord("renamed.tif", null, block));
    }

    @Test
    void theZStepComesFromTheSummaryAndZeroMeansNone(@TempDir Path tmp) throws Exception {
        Path stack = MicroManagerFixture.of(Layout.MMSTACK, 3, 3, 1).write(tmp.resolve("z"));
        Path flat = MicroManagerFixture.of(Layout.SINGLE_PLANE, 3, 1, 1).write(tmp.resolve("flat"));
        assertEquals(2.5, MicroManagerMetadataStrategy.detectZStepUm(stack.toFile()));
        assertNull(MicroManagerMetadataStrategy.detectZStepUm(flat.toFile()), "0.0 is no z-step, not a step of 0");
    }
}
