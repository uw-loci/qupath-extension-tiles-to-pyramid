package qupath.ext.basicstitching.workflow;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.assembly.direct.MosaicGeometry;
import qupath.ext.basicstitching.assembly.direct.TileReaderPool;
import qupath.ext.basicstitching.assembly.direct.TileSpatialIndex;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.StitchingStrategy;
import qupath.ext.basicstitching.stitching.StitchingStrategyFactory;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * Looks at what a stitch will write before anything is written: one row per output image (tile
 * count, tile size, pixel type, planes, size), with a note on anything that differs from the other
 * images or suggests a pixel size that does not belong to the tiles.
 *
 * <p>Every image in a batch is stitched with the same settings, so a folder acquired differently
 * from the rest (another objective, another camera, a channel short of tiles) is otherwise found
 * only by looking at the results. Nothing here refuses a stitch; it tells the user what to check.
 */
public final class StitchPreflight {

    private static final Logger logger = LoggerFactory.getLogger(StitchPreflight.class);

    private StitchPreflight() {}

    /**
     * The pixels of one image's tiles, from its first tile's header.
     *
     * @param bitDepth bits per sample; 0 when unread
     * @param channels samples per pixel
     * @param rgb whether the tiles are RGB
     */
    public record Pixels(int bitDepth, int channels, boolean rgb) {
        /** "16-bit, 1 channel", "8-bit RGB", or "unreadable". */
        public String describe() {
            if (bitDepth <= 0) {
                return "unreadable";
            }
            if (rgb) {
                return bitDepth + "-bit RGB";
            }
            return bitDepth + "-bit, " + channels + (channels == 1 ? " channel" : " channels");
        }
    }

    /**
     * One output image.
     *
     * @param image the image's name (sub-folder or channel)
     * @param tiles tiles placed in it, over all planes
     * @param tileSize the most common tile size, e.g. "2048 x 2048"
     * @param offSizeTiles tiles of another size
     * @param pixels the tiles' pixel type
     * @param planes z-slices times time points
     * @param size the image's width and height in pixels, e.g. "18635 x 18635"
     * @param flags what to check, in a few words each; empty when nothing stands out
     */
    public record Row(
            String image,
            int tiles,
            String tileSize,
            int offSizeTiles,
            String pixels,
            int planes,
            String size,
            List<String> flags) {}

    /**
     * What the check found.
     *
     * @param rows one per output image, in the order they will be written
     * @param warnings what the tile search left out or could not read (missing folders, holes)
     */
    public record Report(List<Row> rows, List<String> warnings) {
        /** Whether anything is flagged or warned about. */
        public boolean hasProblems() {
            return !warnings.isEmpty() || rows.stream().anyMatch(r -> !r.flags().isEmpty());
        }
    }

    /**
     * Find the tiles as the stitch will (same strategy, same settings) and summarize each image.
     * Reads every tile's header (cached for the stitch that follows) and one full header per image.
     */
    public static Report run(StitchingConfig config) {
        StitchingStrategy strategy = StitchingStrategyFactory.getStrategy(config);
        if (strategy == null) {
            return new Report(List.of(), List.of());
        }
        List<TileMapping> mappings = strategy.prepareStitching(
                config.folderPath, config.pixelSizeInMicrons, config.baseDownsample, config.matchingString);
        if (mappings == null || mappings.isEmpty()) {
            return new Report(List.of(), strategy.warnings());
        }
        Map<String, List<TileMapping>> groups = mappings.stream()
                .collect(Collectors.groupingBy(m -> m.subdirName, LinkedHashMap::new, Collectors.toList()));
        List<String> order = strategy.groupOrder();
        groups = order.isEmpty() ? new java.util.TreeMap<>(groups) : StitchingWorkflow.inGroupOrder(groups, order);
        Map<File, Pixels> headers = new HashMap<>();
        return new Report(
                summarize(groups, m -> headers.computeIfAbsent(m.file, StitchPreflight::readPixels)),
                strategy.warnings());
    }

    private static Pixels readPixels(File file) {
        try {
            TileReaderPool.TileDimensions d = TileReaderPool.getDimensions(file);
            return new Pixels(d.bitDepth(), d.nChannels(), d.isRGB());
        } catch (Exception e) {
            logger.debug("Could not read the header of {}: {}", file, e.toString());
            return new Pixels(0, 0, false);
        }
    }

    /**
     * One row per image, flagged against the others.
     *
     * @param groups each image's tiles, in the order they will be written
     * @param pixelsOf the pixel type of a tile (called once per image, on its first tile)
     */
    static List<Row> summarize(Map<String, List<TileMapping>> groups, Function<TileMapping, Pixels> pixelsOf) {
        record Facts(String name, List<TileMapping> tiles, int w, int h, int offSize, Pixels pixels) {}
        List<Facts> facts = new ArrayList<>();
        for (Map.Entry<String, List<TileMapping>> e : groups.entrySet()) {
            List<TileMapping> tiles = e.getValue();
            Map<Long, Integer> sizes = new HashMap<>();
            for (TileMapping m : tiles) {
                sizes.merge(((long) m.region.getWidth() << 32) | m.region.getHeight(), 1, Integer::sum);
            }
            long common = mostCommon(sizes);
            int w = (int) (common >>> 32);
            int h = (int) common;
            facts.add(
                    new Facts(e.getKey(), tiles, w, h, tiles.size() - sizes.get(common), pixelsOf.apply(tiles.get(0))));
        }

        // What most images have, when most of them agree; a batch of unrelated slides has no
        // common tile count, and nothing is flagged against a "most" that is not a majority.
        String majoritySize =
                majority(facts.stream().map(f -> f.w() + " x " + f.h()).toList());
        String majorityPixels =
                majority(facts.stream().map(f -> f.pixels().describe()).toList());
        String majorityCount = majority(
                facts.stream().map(f -> String.valueOf(f.tiles().size())).toList());

        List<Row> rows = new ArrayList<>();
        for (Facts f : facts) {
            List<String> flags = new ArrayList<>();
            String tileSize = f.w() + " x " + f.h();
            if (f.pixels().bitDepth() <= 0) {
                flags.add("first tile unreadable");
            }
            if (majoritySize != null && !majoritySize.equals(tileSize)) {
                flags.add("tile size differs (most: " + majoritySize + ")");
            }
            if (majorityPixels != null && !majorityPixels.equals(f.pixels().describe())) {
                flags.add("pixel type differs (most: " + majorityPixels + ")");
            }
            if (majorityCount != null
                    && !majorityCount.equals(String.valueOf(f.tiles().size()))) {
                flags.add("tile count differs (most: " + majorityCount + ")");
            }
            if (f.offSize() > 0) {
                flags.add(f.offSize() + " tile(s) of another size");
            }
            TileSpatialIndex index = new TileSpatialIndex(f.tiles(), 512);
            String geometry =
                    MosaicGeometry.brief(f.tiles(), index.getOverlapPxX(), index.getOverlapPxY(), f.w(), f.h());
            if (geometry != null) {
                flags.add(geometry);
            }
            long planes = f.tiles().stream()
                    .map(m -> m.region.getZ() + ":" + m.region.getT())
                    .distinct()
                    .count();
            rows.add(new Row(
                    f.name(),
                    f.tiles().size(),
                    tileSize,
                    f.offSize(),
                    f.pixels().describe(),
                    (int) planes,
                    String.format(Locale.ROOT, "%d x %d", index.getImageWidth(), index.getImageHeight()),
                    List.copyOf(flags)));
        }
        return rows;
    }

    private static long mostCommon(Map<Long, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow()
                .getKey();
    }

    /** The value more than half of {@code values} share, or null; null too for fewer than 3 values. */
    static String majority(List<String> values) {
        if (values.size() < 3) {
            // With two images that differ, neither is "the odd one out"; the table shows both.
            return null;
        }
        Map<String, Long> counts = values.stream().collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        return counts.entrySet().stream()
                .filter(e -> e.getValue() * 2 > values.size())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
