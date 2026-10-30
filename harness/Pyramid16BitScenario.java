import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;

/**
 * Real-QuPath scenario: a 16-bit stitch keeps its values in the downsampled pyramid levels.
 *
 * <p>Until 2026-10-29 the first downsampled level of every 16-bit OME-TIFF was rendered through
 * Java2D's 8-bit path: every value came back as a multiple of 257, so a retardance image of a few
 * hundred counts read as zero once zoomed out. Stitch tiles of low 16-bit values and compare each
 * level's mean with full resolution, and count the values that are multiples of 257.
 *
 * <p>args: tileFolder (holding subfolder {@code ret} with TileConfiguration.txt) outputFolder
 */
public final class Pyramid16BitScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path tiles = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Files.createDirectories(Path.of(args[1]));
        var config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tiles.toString(),
                out.toString(),
                "LZW",
                0.25,
                1.0,
                "ret",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        var run = StitchingWorkflow.runDetailed(config);
        check(run.outputs().size() == 1, "one mosaic written");
        if (run.outputs().size() != 1) {
            System.out.println("SCENARIO FAILED");
            System.exit(1);
        }
        try (ImageServer<BufferedImage> server =
                ImageServerProvider.buildServer(run.outputs().get(0), BufferedImage.class)) {
            double base = -1;
            for (int level = 0; level < server.nResolutions(); level++) {
                double d = server.getDownsampleForResolution(level);
                BufferedImage img = server.readRegion(
                        RegionRequest.createInstance(server.getPath(), d, 0, 0, server.getWidth(), server.getHeight()));
                int[] v = img.getRaster().getSamples(0, 0, img.getWidth(), img.getHeight(), 0, (int[]) null);
                double mean = Arrays.stream(v).average().orElse(0);
                long nonzero = Arrays.stream(v).filter(x -> x > 0).count();
                long mult257 = Arrays.stream(v).filter(x -> x > 0 && x % 257 == 0).count();
                System.out.printf(
                        "[....] level %d (downsample %.0f): mean %.1f, multiples of 257 %.1f%%%n",
                        level, d, mean, 100.0 * mult257 / Math.max(1, nonzero));
                if (level == 0) {
                    base = mean;
                } else {
                    check(Math.abs(mean - base) < 0.05 * base, "level " + level + " mean within 5% of full resolution");
                    check(mult257 < 0.05 * nonzero, "level " + level + " is not quantized to 8 bits");
                }
            }
        }
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }
}
