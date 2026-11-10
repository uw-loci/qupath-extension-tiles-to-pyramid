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
    void withNoPositionAnywhereEveryFileIsLeftOutAndSaysSo(@TempDir Path tmp) throws Exception {
        // No FrameKey blocks, nothing saved in the files, no InitialPositionList: no position for
        // any file. They must be left out with a visible warning, not stitched at the origin.
        Path src = MicroManagerFixture.of(Layout.MM14, 3, 2, 1).noPlaneBlocks().write(tmp);
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertTrue(mappings.isEmpty(), "no position known for any file: " + mappings.size());
        assertEquals(1, strategy.warnings().size(), strategy.warnings().toString());
        assertTrue(strategy.warnings().get(0).startsWith("24 file(s) were left out because no stage position"));
    }

    @Test
    void withoutBlocksTheMetadataSavedInEachFilePlacesIt(@TempDir Path tmp) throws Exception {
        checkMapping(MicroManagerFixture.of(Layout.MM14, 3, 3, 2)
                .noPlaneBlocks()
                .mm14Embedded(0)
                .write(tmp));
    }

    @Test
    void withoutBlocksOrEmbeddedMetadataTheInitialPositionListPlacesEachFolder(@TempDir Path tmp) throws Exception {
        checkMapping(MicroManagerFixture.of(Layout.MM14, 3, 3, 2)
                .noPlaneBlocks()
                .mm14InitialPositions(0)
                .write(tmp));
    }

    @Test
    void theBlockWinsOverTheFileAndTheFileOverThePositionList(@TempDir Path tmp) throws Exception {
        // Each source further down the order is written 1000 um off: the mapping (one position at
        // the origin) shows which was used.
        checkMapping(MicroManagerFixture.of(Layout.MM14, 3, 3, 2)
                .mm14Embedded(1000)
                .mm14InitialPositions(2000)
                .write(tmp.resolve("blocks")));
        checkMapping(MicroManagerFixture.of(Layout.MM14, 3, 3, 2)
                .noPlaneBlocks()
                .mm14Embedded(0)
                .mm14InitialPositions(2000)
                .write(tmp.resolve("embedded")));
    }

    @Test
    void anUnfinishedMetadataFileIsReadUpToItsBreak(@TempDir Path tmp) throws Exception {
        // The last file of each position loses its block; the metadata saved in it places it.
        Path src = MicroManagerFixture.of(Layout.MM14, 3, 3, 2)
                .mm14Truncated()
                .mm14Embedded(0)
                .write(tmp);
        MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
        List<TileMapping> mappings = strategy.prepareStitching(src.toString(), MicroManagerFixture.PX_UM, 1.0, "");
        assertEquals(4, strategy.warnings().size(), strategy.warnings().toString());
        assertTrue(
                strategy.warnings().get(0).contains("metadata.txt: ends part way"),
                strategy.warnings().get(0));
        assertEquals(72, mappings.size(), "every file placed");
    }

    @Test
    void theInfoPropertyIsReadInEitherByteOrder(@TempDir Path tmp) throws Exception {
        String json = "{\"XPositionUm\":12.5,\"Channel\":\"Cy5\"}";
        Path be = tmp.resolve("be.tif");
        MicroManagerFixture.writeImageJTiff(
                be, MicroManagerFixture.of(Layout.MM14, 1, 1, 1).tile(8).plane(7), json);
        assertEquals(json, imageJInfo(be));
        Path le = tmp.resolve("le.tif");
        java.nio.file.Files.write(le, littleEndianImageJTiff(json));
        assertEquals(json, imageJInfo(le));
        Path none = tmp.resolve("none.tif");
        javax.imageio.ImageIO.write(
                MicroManagerFixture.of(Layout.MM14, 1, 1, 1).tile(8).plane(7), "TIFF", none.toFile());
        assertNull(imageJInfo(none));
    }

    @Test
    void aPreprocessedCopyKeepsThePositionsOfFilesWithoutBlocks(@TempDir Path tmp) throws Exception {
        for (boolean embedded : new boolean[] {true, false}) {
            MicroManagerFixture f = MicroManagerFixture.of(Layout.MM14, 3, 3, 2).noPlaneBlocks();
            Path src = (embedded ? f.mm14Embedded(0) : f.mm14InitialPositions(0)).write(tmp.resolve("e" + embedded));
            var options = new qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.Options(true, null, true);
            Path copy = qupath.ext.basicstitching.stitching.MicroManagerPreprocessor.run(
                            src, tmp.resolve("copy" + embedded), options)
                    .output();
            MicroManagerMetadataStrategy strategy = new MicroManagerMetadataStrategy();
            List<TileMapping> mappings = strategy.prepareStitching(copy.toString(), MicroManagerFixture.PX_UM, 1.0, "");
            assertEquals(List.of(), strategy.warnings(), "embedded " + embedded);
            assertEquals(12, mappings.size(), "4 positions x 3 channels, embedded " + embedded);
            assertEquals(
                    3,
                    mappings.stream()
                            .filter(m -> m.region.getX() == 0 && m.region.getY() == 0)
                            .count(),
                    "one position at the origin, embedded " + embedded);
        }
    }

    private static String imageJInfo(Path file) throws Exception {
        var m = qupath.ext.basicstitching.stitching.TileHeaders.class.getDeclaredMethod("imageJInfo", Path.class);
        m.setAccessible(true);
        return (String) m.invoke(null, file);
    }

    /** A little-endian 2 x 1 8-bit TIFF with ImageJ's metadata tags, laid out by hand. */
    private static byte[] littleEndianImageJTiff(String info) {
        byte[] text = info.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        int entries = 10;
        int ifd = 8;
        int after = ifd + 2 + entries * 12 + 4;
        int countsAt = after;
        int dataAt = countsAt + 8;
        int pixelsAt = dataAt + 12 + text.length;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(pixelsAt + 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        b.putShort((short) entries);
        int[][] tags = {
            {256, 3, 1, 2},
            {257, 3, 1, 1},
            {258, 3, 1, 8},
            {259, 3, 1, 1},
            {262, 3, 1, 1},
            {273, 4, 1, pixelsAt},
            {278, 3, 1, 1},
            {279, 4, 1, 2},
            {50838, 4, 2, countsAt},
            {50839, 1, 12 + text.length, dataAt}
        };
        for (int[] t : tags) {
            b.putShort((short) t[0]).putShort((short) t[1]).putInt(t[2]);
            if (t[1] == 3) {
                b.putShort((short) t[3]).putShort((short) 0);
            } else {
                b.putInt(t[3]);
            }
        }
        b.putInt(0);
        b.putInt(12).putInt(text.length);
        b.putInt(0x494a494a).putInt(0x696e666f).putInt(1).put(text);
        b.put((byte) 1).put((byte) 2);
        return b.array();
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
