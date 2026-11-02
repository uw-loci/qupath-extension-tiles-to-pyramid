import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.functions.StitchingGUI;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;

/**
 * Real-QuPath scenario: a Micro-Manager acquisition stitched the way the Tiles to Pyramid dialog
 * does it -- optional preprocessing, the MicroManager method, then the dialog's own channel merge
 * (StitchingGUI.mergeChannelOutputs, called by reflection) -- must give one image per channel, no
 * strategy warnings, and a merged image whose channels and planes are all present and distinct.
 *
 * <p>args: acquisitionFolder outputFolder expectedChannels expectedZ expectedT [average] [project]
 *
 * <p>The preprocessed copy, when asked for, is written beside the acquisition as the dialog writes
 * it ({@code <folder>_averaged}, {@code _mip}, {@code _averaged_mip}).
 */
public final class MicroManagerChannelMergeScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path src = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Files.createDirectories(Path.of(args[1]));
        int channels = Integer.parseInt(args[2]);
        int sizeZ = Integer.parseInt(args[3]);
        int sizeT = Integer.parseInt(args[4]);
        List<String> flags = List.of(args).subList(5, args.length);
        boolean average = flags.contains("average");
        boolean project = flags.contains("project");

        int offered = MicroManagerMetadataStrategy.countChannels(src.toFile());
        System.out.println("[....] dialog offers to merge " + offered + " channel(s); shape "
                + MicroManagerPreprocessor.shape(src.toFile()));
        check(offered == (channels > 1 ? channels : 0), "merge offered for " + channels + " channels");

        Path folder = src;
        if (average || project) {
            var options = new MicroManagerPreprocessor.Options(average, null, project);
            long t0 = System.nanoTime();
            var r = MicroManagerPreprocessor.run(src, MicroManagerPreprocessor.defaultOutput(src, options), options);
            System.out.printf("[....] %s in %.1f s -> %s%n", options.describe(), (System.nanoTime() - t0) / 1e9, r.output());
            folder = r.output();
        }

        Double px = MicroManagerMetadataStrategy.detectPixelSizeUm(folder.toFile());
        StitchingConfig config = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                folder.toString(),
                out.toString(),
                "LZW",
                px == null ? 1.0 : px,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        long t0 = System.nanoTime();
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config);
        System.out.printf("[....] stitched in %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, result.outputs());
        result.warnings().forEach(w -> System.out.println("[....] warning: " + w));
        check(result.warnings().isEmpty(), "no tiles left out or read in part");
        check(result.outputs().size() == channels, channels + " stitched image(s), one per channel");

        Method shortfall = StitchingGUI.class.getDeclaredMethod("mergeShortfall", boolean.class, int.class, int.class);
        shortfall.setAccessible(true);
        Object said = shortfall.invoke(null, channels > 1, channels, result.outputs().size());
        check(said == null, "the result dialog reports no merge shortfall (" + said + ")");

        String image = result.outputs().isEmpty() ? null : result.outputs().get(0);
        if (channels > 1 && result.outputs().size() >= 2) {
            Method merge = StitchingGUI.class.getDeclaredMethod(
                    "mergeChannelOutputs", List.class, boolean.class, StitchingConfig.class);
            merge.setAccessible(true);
            image = (String) merge.invoke(null, result.outputs(), result.acquisitionOrder(), config);
            check(image != null, "the dialog's merge wrote " + image);
        }
        if (image == null) {
            System.out.println("SCENARIO FAILED (" + Math.max(1, failures) + ")");
            System.exit(1);
        }

        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(image, BufferedImage.class)) {
            System.out.printf("[....] %s: %d x %d, C %d, Z %d, T %d, %s, %d levels%n",
                    Path.of(image).getFileName(), server.getWidth(), server.getHeight(), server.nChannels(),
                    server.nZSlices(), server.nTimepoints(), server.getPixelType(), server.nResolutions());
            check(server.nChannels() == channels, "SizeC = " + channels);
            check(server.nZSlices() == sizeZ, "SizeZ = " + sizeZ);
            check(server.nTimepoints() == sizeT, "SizeT = " + sizeT);
            // Means of every plane at the first zoomed-out level (full resolution of a real mosaic
            // can be larger than the heap as one image) and at the smallest: a channel stacked into
            // another, or a plane left empty, shows as a repeated or zero mean.
            int last = server.nResolutions() - 1;
            int first = Math.min(1, last);
            List<String> fullRes = new ArrayList<>();
            for (int level : new int[] {first, last}) {
                double d = server.getDownsampleForResolution(level);
                for (int t = 0; t < server.nTimepoints(); t++) {
                    for (int z = 0; z < server.nZSlices(); z++) {
                        Raster r = server.readRegion(RegionRequest.createInstance(
                                        server.getPath(), d, 0, 0, server.getWidth(), server.getHeight(), z, t))
                                .getRaster();
                        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "[....] L%d z%d t%d:", level, z, t));
                        for (int c = 0; c < r.getNumBands(); c++) {
                            double mean = nonZeroMean(r, c);
                            line.append(String.format(Locale.ROOT, " %s=%.1f", server.getChannel(c).getName(), mean));
                            check(mean > 0, "L" + level + " c" + c + " z" + z + " t" + t + " has data");
                            if (level == first) fullRes.add(String.format(Locale.ROOT, "%.3f", mean));
                        }
                        System.out.println(line);
                    }
                }
            }
            check(fullRes.stream().distinct().count() == fullRes.size(), "every plane differs from every other: " + fullRes);
        }
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static double nonZeroMean(Raster r, int band) {
        double sum = 0;
        long n = 0;
        double[] row = new double[r.getWidth()];
        for (int y = 0; y < r.getHeight(); y++) {
            r.getSamples(0, y, r.getWidth(), 1, band, row);
            for (double v : row) {
                if (v != 0) {
                    sum += v;
                    n++;
                }
            }
        }
        return n == 0 ? 0 : sum / n;
    }
}
