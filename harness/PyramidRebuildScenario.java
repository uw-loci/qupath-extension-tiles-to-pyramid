import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import qupath.ext.basicstitching.assembly.direct.PyramidRebuilder;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;

/**
 * Real-QuPath scenario: rebuild the pyramid of a 16-bit OME-TIFF written by tiles-to-pyramid
 * 0.7.10 or earlier, whose zoomed-out levels hold only 8 bits.
 *
 * <p>Checks the input is diagnosed as damaged, the output as not, full resolution is identical,
 * and each rebuilt level's mean of non-zero pixels matches full resolution.
 *
 * <p>args: damagedImage.ome.tif outputFolder
 */
public final class PyramidRebuildScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static int[] read(ImageServer<BufferedImage> s, double d, int x, int y, int w, int h) throws Exception {
        BufferedImage img = s.readRegion(RegionRequest.createInstance(s.getPath(), d, x, y, w, h));
        return img.getRaster().getSamples(0, 0, img.getWidth(), img.getHeight(), 0, (int[]) null);
    }

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]).toAbsolutePath();
        Path out = Files.createDirectories(Path.of(args[1])).resolve(PyramidRebuilder.defaultOutput(in).getFileName());
        Files.deleteIfExists(out);

        var before = PyramidRebuilder.inspect(in);
        System.out.println("[....] input: " + before.summary());
        check(before.damaged(), "input is diagnosed as damaged");

        long t0 = System.nanoTime();
        PyramidRebuilder.rebuild(in, out, null);
        System.out.printf("[....] rebuilt in %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, out);

        var after = PyramidRebuilder.inspect(out);
        System.out.println("[....] output: " + after.summary());
        check(!after.damaged(), "output is not damaged");

        try (ImageServer<BufferedImage> a = ImageServerProvider.buildServer(in.toString(), BufferedImage.class);
                ImageServer<BufferedImage> b = ImageServerProvider.buildServer(out.toString(), BufferedImage.class)) {
            check(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "same size");
            check(a.nResolutions() == b.nResolutions(), "same number of levels: " + b.nResolutions());
            int w = Math.min(4096, a.getWidth());
            int h = Math.min(4096, a.getHeight());
            int x = (a.getWidth() - w) / 2;
            int y = (a.getHeight() - h) / 2;
            int[] full = read(a, 1, x, y, w, h);
            check(Arrays.equals(full, read(b, 1, x, y, w, h)), "full resolution identical (4096 px square)");
            // Zeros are the empty canvas, left out of the zoomed-out averages; compare the rest.
            double base = Arrays.stream(full).filter(v -> v > 0).average().orElse(0);
            for (int level = 1; level < b.nResolutions(); level++) {
                double d = b.getDownsampleForResolution(level);
                double m = Arrays.stream(read(b, d, x, y, w, h)).filter(v -> v > 0).average().orElse(0);
                double old = Arrays.stream(read(a, d, x, y, w, h)).filter(v -> v > 0).average().orElse(0);
                System.out.printf("[....] level %d: mean %.1f (was %.1f; full resolution %.1f)%n", level, m, old, base);
                check(Math.abs(m - base) <= Math.max(1.0, 0.05 * base), "level " + level + " mean matches full resolution");
            }
        }
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }
}
