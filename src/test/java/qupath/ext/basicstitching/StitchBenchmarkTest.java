package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * Reproducible stitch benchmark: wall time and peak heap for a fixed synthetic
 * tile grid, for both output formats.
 *
 * <p>This is opt-in and does NOT run in the normal suite -- it takes tens of
 * seconds and allocates a large source texture. Run it with:
 *
 * <pre>
 *   ./gradlew test --tests "*StitchBenchmarkTest*" -PstitchBench \
 *       -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
 * </pre>
 *
 * <p>Knobs (all Gradle {@code -P} properties, see build.gradle.kts):
 * <ul>
 *   <li>{@code -PstitchBenchGrid=8} -- grid is N x N tiles (default 8, so 64 tiles)</li>
 *   <li>{@code -PstitchBenchTile=1024} -- tile edge in pixels (default 1024)</li>
 *   <li>{@code -PstitchBenchReps=3} -- repeats; the report gives the median</li>
 *   <li>{@code -PstitchBenchQuPathCacheMb=0} -- size of the QuPath tile cache to
 *       install, mimicking the one QuPath's GUI installs at startup. See
 *       {@link #installTileCache(int)}.</li>
 *   <li>{@code -PstitchBenchHeap=2g} -- max heap for the test JVM. Lower this to
 *       prove the bounded-memory envelope: if the stitch still completes at
 *       {@code -PstitchBenchHeap=256m}, steady-state memory really is bounded and
 *       independent of tile count.</li>
 * </ul>
 *
 * <p>Two memory numbers are reported, and they mean different things:
 * <ul>
 *   <li><b>peak live</b> -- heap still reachable after a collection. This is the
 *       real steady-state footprint, and the number the ~40 MB envelope claim in
 *       {@link DirectTileStitcher} is about.</li>
 *   <li><b>peak used</b> -- includes not-yet-collected garbage, so it tracks the
 *       heap ceiling and allocation churn rather than the footprint. A large value
 *       here is not by itself a leak; a large <i>live</i> value is.</li>
 * </ul>
 *
 * <h2>What this harness has already established</h2>
 *
 * Measured on a 64-tile (8x8 x 1024 px, 16-bit, 10% overlap -> 7478x7478) grid,
 * 16-core WSL2, JDK 21, median of 3. Recorded here so the next person does not
 * re-run the same dead ends:
 *
 * <ul>
 *   <li><b>Baseline</b>: OME-TIFF ~9.0 s, OME-ZARR ~4.7 s.</li>
 *   <li><b>Where the time goes</b>: compositing is only 33% of the OME-TIFF stitch
 *       (2.5 s of 7.5 s; tile reads just 1.0 s of that) and 21% of OME-ZARR. The
 *       rest is the write path -- pyramid resizing, byte packing, and codec work.
 *       That caps any composite-side optimization at ~1.45x (TIFF) / ~1.25x (ZARR)
 *       even with perfect scaling, which is what makes parallel compositing a poor
 *       trade against its concurrency risk.</li>
 *   <li><b>A decoded-tile cache is a LOSS, not a win.</b> Tiles do get decoded
 *       ~19x each on the TIFF path (1204 decodes for 64 tiles), which looks like an
 *       obvious inefficiency. It is not: acquisition tiles are written uncompressed,
 *       so {@code ImageReadParam.setSourceRegion} decodes only the requested
 *       sub-region and each of those 19 decodes is small. Caching whole tiles
 *       instead cut decodes to 4x per tile but measured <b>48% SLOWER</b> (9.0 s ->
 *       13.4 s) and raised peak live heap 400 -> 493 MB, because it trades many
 *       cheap partial decodes for fewer full-tile decodes plus LRU thrash and GC
 *       pressure. Decode <i>count</i> is a misleading proxy for decode <i>cost</i>.</li>
 *   <li><b>The pyramid re-composites the whole mosaic once per level.</b>
 *       {@code PyramidGeneratingImageServer.readTile} picks its source level via
 *       {@code ServerTools.getPreferredResolutionLevel} on the <i>wrapped</i>
 *       server; {@link qupath.ext.basicstitching.assembly.direct.CompositorImageServer}
 *       is single-resolution, so that is always level 0 and every pyramid level is
 *       built from full-resolution source tiles rather than from the level above.
 *       Levels 1-3 cost 4.6 s of the 9.0 s to produce 33% of the pixels. Installing
 *       a 512 MB QuPath tile cache does NOT help (measured: 9.0 s, byte-identical
 *       decode count) because each level requests differently-keyed regions.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "stitchBench", matches = "true")
public class StitchBenchmarkTest {

    /** Fixed so runs are comparable to each other. */
    private static final long SEED = 424242L;

    /** Tile overlap fraction; 10% is typical of real acquisitions. */
    private static final double OVERLAP = 0.1;

    @Test
    public void benchmarkTiffStitch() throws Exception {
        runBenchmark(StitchingConfig.OutputFormat.OME_TIFF, "LZW");
    }

    @Test
    public void benchmarkZarrStitch() throws Exception {
        runBenchmark(StitchingConfig.OutputFormat.OME_ZARR, "zstd");
    }

    private void runBenchmark(StitchingConfig.OutputFormat format, String compression) throws Exception {
        if (format == StitchingConfig.OutputFormat.OME_ZARR) {
            // Same native-blosc dependency as the ZARR stitch tests; skip with the real reason where
            // it cannot load rather than failing the benchmark. See BloscSupport.
            org.junit.jupiter.api.Assumptions.assumeTrue(BloscSupport.isAvailable(), BloscSupport.unavailableReason());
        }
        int grid = Integer.getInteger("stitchBenchGrid", 8);
        int tile = Integer.getInteger("stitchBenchTile", 1024);
        int reps = Integer.getInteger("stitchBenchReps", 3);
        installTileCache(Integer.getInteger("stitchBenchQuPathCacheMb", 0));

        Path dir = Files.createTempDirectory("stitch-bench-");
        try {
            // Build the tile set. jitterSigma = 0: a stitch benchmark wants a clean
            // mosaic, not the misregistration the fixture exists to inject.
            SyntheticGridFixture.Grid g = SyntheticGridFixture.write(dir, grid, grid, tile, tile, OVERLAP, 0.0, SEED);
            List<TileMapping> mappings = toMappings(g);

            Path outDir = Files.createDirectory(dir.resolve("out"));
            StitchingConfig config = new StitchingConfig(
                    "filename", outDir.toString(), outDir.toString(), compression, 1.0, 1.0, ".", 1.0, format);

            long[] times = new long[reps];
            double peakLiveMb = 0;
            double peakUsedMb = 0;

            // Repeat and report the median: a single run of this is worth roughly
            // +/-25%, which is wider than several of the optimizations under
            // consideration. One number here would be a coin flip, not evidence.
            for (int rep = 0; rep < reps; rep++) {
                // Drop the fixture's source texture and the previous rep's garbage
                // before sampling, so the reported heap is this stitch's footprint.
                System.gc();
                Thread.sleep(200);

                HeapSampler sampler = new HeapSampler();
                long t0 = System.nanoTime();
                String out;
                try {
                    sampler.start();
                    out = DirectTileStitcher.stitch(mappings, outDir.toString(), "bench" + rep, config, null);
                } finally {
                    sampler.stop();
                }
                times[rep] = (System.nanoTime() - t0) / 1_000_000L;
                assertNotNull(out, format + " benchmark stitch should produce output");
                peakLiveMb = Math.max(peakLiveMb, sampler.peakLiveMb());
                peakUsedMb = Math.max(peakUsedMb, sampler.peakUsedMb());
            }

            long[] sorted = times.clone();
            java.util.Arrays.sort(sorted);
            long median = sorted[sorted.length / 2];

            int tiles = grid * grid;
            int mosaic = (int) Math.round(tile * (1 - OVERLAP)) * (grid - 1) + tile;
            System.out.println(String.format(
                    Locale.ROOT,
                    "%n=== STITCH BENCHMARK [%s] ===%n"
                            + "  grid          : %dx%d tiles (%d tiles) of %dx%d px, %.0f%% overlap%n"
                            + "  mosaic        : %dx%d px (%.1f MP)%n"
                            + "  wall time     : %d ms (median of %d: %s)%n"
                            + "  peak live heap: %.1f MB   (steady-state footprint)%n"
                            + "  peak used heap: %.1f MB   (incl. uncollected garbage)%n"
                            + "  max heap      : %.1f MB%n"
                            + "================================%n",
                    format,
                    grid,
                    grid,
                    tiles,
                    tile,
                    tile,
                    OVERLAP * 100,
                    mosaic,
                    mosaic,
                    mosaic / 1000.0 * mosaic / 1000.0,
                    median,
                    reps,
                    java.util.Arrays.toString(times),
                    peakLiveMb,
                    peakUsedMb,
                    Runtime.getRuntime().maxMemory() / 1048576.0));
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Install a QuPath tile cache of the given size, or none if {@code mb <= 0}.
     *
     * <p>This matters more than it looks. The OME-TIFF path wraps the compositor in
     * QuPath's {@code PyramidGeneratingImageServer}, whose {@code readTile} derives
     * pyramid level N by re-reading level N-1 <i>from itself</i>. That recursion is
     * absorbed by the tile cache that {@code AbstractImageServer} pulls from the
     * global {@link ImageServerProvider} registry. QuPath's GUI populates that
     * registry at startup; a headless JVM leaves it empty, so every level recurses
     * all the way down and re-composites the entire mosaic from source tiles.
     *
     * <p>So the same stitch has two very different cost profiles depending on
     * whether a cache happens to be installed, and a benchmark that did not control
     * for this would be measuring the harness rather than the stitcher.
     */
    private static void installTileCache(int mb) {
        if (mb <= 0) {
            ImageServerProvider.setCache(null, BufferedImage.class);
            return;
        }
        ImageServerProvider.setCache(new ByteBoundedTileCache(mb * 1024L * 1024L), BufferedImage.class);
    }

    /**
     * Byte-bounded LRU tile cache, standing in for the cache QuPath's GUI installs.
     * Bounded by pixel bytes rather than entry count because pyramid levels put
     * tiles of different sizes through the same cache.
     */
    private static final class ByteBoundedTileCache extends LinkedHashMap<RegionRequest, BufferedImage> {

        private static final long serialVersionUID = 1L;

        private final long budgetBytes;
        private long bytes;

        ByteBoundedTileCache(long budgetBytes) {
            super(64, 0.75f, true);
            this.budgetBytes = budgetBytes;
        }

        @Override
        public synchronized BufferedImage put(RegionRequest key, BufferedImage value) {
            BufferedImage previous = super.put(key, value);
            if (previous != null) {
                bytes -= sizeOf(previous);
            }
            bytes += sizeOf(value);
            Iterator<Map.Entry<RegionRequest, BufferedImage>> it = entrySet().iterator();
            while (bytes > budgetBytes && it.hasNext()) {
                Map.Entry<RegionRequest, BufferedImage> eldest = it.next();
                bytes -= sizeOf(eldest.getValue());
                it.remove();
            }
            return previous;
        }

        @Override
        public synchronized BufferedImage get(Object key) {
            return super.get(key);
        }

        private static long sizeOf(BufferedImage img) {
            DataBuffer buffer = img.getRaster().getDataBuffer();
            return (long) buffer.getSize()
                    * buffer.getNumBanks()
                    * (DataBuffer.getDataTypeSize(buffer.getDataType()) / 8L);
        }
    }

    private static List<TileMapping> toMappings(SyntheticGridFixture.Grid g) {
        List<TileMapping> mappings = new ArrayList<>();
        for (TileNode n : g.nominal()) {
            ImageRegion region = ImageRegion.createInstance(
                    (int) Math.round(n.xPx()), (int) Math.round(n.yPx()), n.widthPx(), n.heightPx(), 0, 0);
            mappings.add(new TileMapping(n.file(), region, "."));
        }
        return mappings;
    }

    /**
     * Polls heap usage on a background thread and keeps the high-water marks.
     *
     * <p>Sampling rather than instrumenting keeps the measurement off the stitch's
     * own code path, so the timing number stays honest.
     */
    private static final class HeapSampler {

        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private volatile boolean running = true;
        private volatile long peakUsed;
        private volatile long peakLive;
        private Thread thread;

        void start() {
            thread = new Thread(this::sampleLoop, "heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            if (thread != null) {
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void sampleLoop() {
            while (running) {
                peakUsed = Math.max(peakUsed, memoryBean.getHeapMemoryUsage().getUsed());
                peakLive = Math.max(peakLive, liveHeapBytes());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /**
         * Heap still reachable after the most recent collection, summed over heap pools.
         * Pools are collected at different times, so this is an estimate -- but a
         * conservative one, and it tracks the live set rather than allocation churn.
         */
        private static long liveHeapBytes() {
            long live = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    MemoryUsage usage = pool.getCollectionUsage();
                    if (usage != null) {
                        live += usage.getUsed();
                    }
                }
            }
            return live;
        }

        double peakUsedMb() {
            return peakUsed / 1048576.0;
        }

        double peakLiveMb() {
            return peakLive / 1048576.0;
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(StitchBenchmarkTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best-effort cleanup
        }
    }
}
