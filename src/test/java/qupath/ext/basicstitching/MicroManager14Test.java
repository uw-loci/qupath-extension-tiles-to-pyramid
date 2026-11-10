package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.MicroManagerFixture.Layout;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerPlanes;
import qupath.ext.basicstitching.stitching.MicroManagerPlanes.Coord;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * Micro-Manager 1.4 single-plane acquisitions: {@code img_TTTTTTTTT_<channel>_ZZZ.tif} in a folder
 * per position, with {@code FrameKey-t-c-z} blocks in each position's metadata.txt. Before
 * 2026-11 only a one-channel, one-plane 1.4 acquisition stitched; any other left every file out.
 * The full stitch, merge and preprocessing of this layout run in {@link MicroManagerCztStitchTest}.
 */
class MicroManager14Test {

    private static final List<String> NAMES = List.of("DAPI", "FITC", "Cy5_long");

    @Test
    void theNameGivesFrameChannelAndSlice() {
        assertEquals(new Coord(1, 4, 12), MicroManagerPlanes.fromMm14FileName("img_000000012_FITC_004.tif", NAMES));
        assertEquals(
                new Coord(2, 0, 0),
                MicroManagerPlanes.fromMm14FileName("img_000000000_Cy5_long_000.tif", NAMES),
                "a channel name holding an underscore");
        assertNull(MicroManagerPlanes.fromMm14FileName("img_000000000_TRITC_000.tif", NAMES), "unlisted channel");
        assertNull(
                MicroManagerPlanes.fromMm14FileName("img_channel000_position000_time000000000_z000.tif", NAMES),
                "a Micro-Manager 2 name is not a 1.4 name");
        assertNull(MicroManagerPlanes.fromMm14FileName("img_00000001_DAPI_000.tif", NAMES), "8-digit frame");
    }

    @Test
    void framesKeysPlaceEveryPlane(@TempDir Path tmp) throws Exception {
        checkMapping(MicroManagerFixture.of(Layout.MM14, 3, 3, 2).write(tmp));
    }

    @Test
    void withoutPlaneBlocksTheNamesPlaceEveryPlane(@TempDir Path tmp) throws Exception {
        // No FrameKey blocks, so no stage positions per file either: the strategy needs some
        // position for each folder. 1.4 records none in its Summary, so this layout without blocks
        // cannot be placed, and must say so rather than stitch everything at the origin.
        Path src = MicroManagerFixture.of(Layout.MM14, 3, 2, 1).noPlaneBlocks().write(tmp);
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertTrue(mappings.isEmpty(), "no position known for any file: " + mappings.size());
    }

    private static void checkMapping(Path src) {
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertEquals(List.of(), strategy.warnings());
        Map<String, Integer> groups = new TreeMap<>();
        mappings.forEach(m -> groups.merge(m.subdirName, 1, Integer::sum));
        assertEquals(Map.of("DAPI", 24, "FITC", 24, "TRITC", 24), groups, "4 positions x 3 z x 2 t per channel");
        assertEquals(List.of("DAPI", "FITC", "TRITC"), strategy.groupOrder());
        for (TileMapping m : mappings) {
            Coord k = MicroManagerPlanes.fromMm14FileName(m.file.getName(), List.of(MicroManagerFixture.NAMES));
            assertEquals(MicroManagerFixture.NAMES[k.c()], m.subdirName, m.file.getName());
            assertEquals(k.z(), m.region.getZ(), m.file.getName());
            assertEquals(k.t(), m.region.getT(), m.file.getName());
            assertEquals(0, m.ifdIndex);
        }
        long origins = mappings.stream()
                .filter(m -> m.region.getX() == 0 && m.region.getY() == 0)
                .count();
        assertEquals(18, origins, "one position of four at the origin, 18 planes each");
    }
}
