package qupath.ext.basicstitching.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import qupath.ext.basicstitching.utilities.UtilityFunctions;

/**
 * Opt-in measurement of reading tile sizes the way 0.9.1 did (one {@code javax.imageio} read per
 * file, serially, again for every call) against {@link TileHeaders}, on a folder of
 * {@code State0..State4} channel folders each holding a TileConfiguration.txt and its tiles.
 *
 * <p>Run with {@code T2P_HEADER_BENCH_DIR=<folder> ./gradlew test --tests '*TileHeaderBenchTest'};
 * results go to the test's standard output. QPSC's pattern is modeled: a registration solve over
 * all five channels (one strategy call each), then one stitch per channel (one more each).
 */
@EnabledIfEnvironmentVariable(named = "T2P_HEADER_BENCH_DIR", matches = ".+")
class TileHeaderBenchTest {

    @Test
    void measure() throws Exception {
        Path root = Path.of(System.getenv("T2P_HEADER_BENCH_DIR"));
        List<Path> channels;
        try (Stream<Path> s = Files.list(root)) {
            channels = s.filter(Files::isDirectory).sorted().toList();
        }
        int reps = Integer.parseInt(System.getenv().getOrDefault("T2P_HEADER_BENCH_REPS", "3"));
        for (int rep = 0; rep < reps; rep++) {
            // 0.9.1: every call reads every file through javax.imageio, serially.
            long t0 = System.nanoTime();
            int n = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (Path ch : channels) {
                    for (Path tif : tiffs(ch)) {
                        Map<String, Integer> d = UtilityFunctions.getTiffDimensions(tif.toFile());
                        if (d != null) n++;
                    }
                }
            }
            long old = System.nanoTime() - t0;

            // Header parse alone, serially, no cache: what the parser is worth by itself.
            long t1 = System.nanoTime();
            for (Path ch : channels) {
                for (Path tif : tiffs(ch)) {
                    TileHeaders.parse(tif);
                }
            }
            long parseOnly = System.nanoTime() - t1;

            // Now: the strategy itself, registration pass then stitch pass, cache cleared first.
            TileHeaders.clearCache();
            long t2 = System.nanoTime();
            int mapped = 0;
            long firstPass = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (Path ch : channels) {
                    mapped += new TileConfigurationTxtStrategy()
                            .prepareStitching(
                                    root.toString(), 0.5, 1.0, ch.getFileName().toString())
                            .size();
                }
                if (pass == 0) firstPass = System.nanoTime() - t2;
            }
            long now = System.nanoTime() - t2;
            assertEquals(n, mapped, "every tile mapped");
            System.out.printf(
                    "HEADERBENCH rep %d: %d files x 2 passes | 0.9.1 imageio serial %.0f ms | parse serial (1 pass) %.0f ms"
                            + " | strategy now: registration pass %.0f ms, both passes %.0f ms%n",
                    rep, n / 2, old / 1e6, parseOnly / 1e6, firstPass / 1e6, now / 1e6);
        }
    }

    private static List<Path> tiffs(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tif"))
                    .sorted()
                    .toList();
        }
    }
}
