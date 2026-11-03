import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.functions.StitchingGUI;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerOutputMetadata;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;

/**
 * Real-QuPath scenario: a Micro-Manager acquisition stitched the way the Tiles to Pyramid dialog
 * does it -- optional preprocessing, the MicroManager method, then the dialog's own channel merge
 * (StitchingGUI.mergeChannelOutputs, called by reflection) -- must give one image per channel, no
 * strategy warnings, and a merged image whose channels and planes are all present and distinct,
 * in the acquisition's channel order, with a {@code .mm-metadata.json} beside every image.
 * With {@code register}, content registration (Auto reference) runs on the stitch first and must
 * solve one tile per position.
 *
 * <p>args: acquisitionFolder outputFolder expectedChannels expectedZ expectedT [average] [project]
 * [register] [invertX] [invertY]
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
        boolean register = flags.contains("register");
        // The scope's stage axes, as the dialog's Invert X / Invert Y set them.
        MicroManagerMetadataStrategy.flipStitchingX = flags.contains("invertX");
        MicroManagerMetadataStrategy.flipStitchingY = flags.contains("invertY");

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
        Path solution = out.resolve("TileRegistration.txt");
        if (register) {
            config.setRegistrationMode(new RegistrationMode.Solve(
                    solution, RegistrationSettings.defaults(), RegistrationReference.auto()));
        }
        long t0 = System.nanoTime();
        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config);
        System.out.printf("[....] stitched in %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, result.outputs());
        result.warnings().forEach(w -> System.out.println("[....] warning: " + w));
        check(result.warnings().isEmpty(), "no tiles left out or read in part");
        check(result.outputs().size() == channels, channels + " stitched image(s), one per channel");
        List<String> acquired = MicroManagerMetadataStrategy.channelOutputNames(folder.toFile());
        if (channels > 1) {
            List<String> stems = result.outputs().stream()
                    .map(o -> Path.of(o).getFileName().toString().replaceAll("\\.ome\\.(tif|zarr)$", ""))
                    .toList();
            check(result.acquisitionOrder() && stems.equals(acquired),
                    "outputs in acquisition order " + acquired + ": " + stems);
        }
        for (String o : result.outputs()) {
            Path meta = MicroManagerOutputMetadata.pathFor(Path.of(o));
            check(Files.isRegularFile(meta), "acquisition metadata beside " + Path.of(o).getFileName());
            check(String.join("\n", StitchInfoFile.read(Path.of(o))).contains("file: " + meta.getFileName()),
                    "its stitch record names " + meta.getFileName());
        }
        if (register) {
            check(Files.isRegularFile(solution), "registration solution written");
            if (Files.isRegularFile(solution)) {
                List<String> lines = Files.readAllLines(solution);
                lines.stream().filter(l -> l.startsWith("#")).forEach(l -> System.out.println("[....] " + l));
                long solved = lines.stream().filter(l -> !l.startsWith("#") && !l.isBlank()).count();
                long positions = result.outputs().isEmpty() ? 0 : solvedPositions(folder);
                check(solved == positions, "one solved tile per position (" + solved + " of " + positions + ")");
            }
        }

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
        Path imageMeta = MicroManagerOutputMetadata.pathFor(Path.of(image));
        check(Files.isRegularFile(imageMeta), "acquisition metadata beside " + Path.of(image).getFileName());
        if (Files.isRegularFile(imageMeta)) {
            String text = Files.readString(imageMeta);
            System.out.println("[....] " + imageMeta.getFileName() + ":\n" + text);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            check(json.has("source_folder") && json.has("stitched_output") && json.has("tile_count"),
                    "core fields present: " + json.keySet());
            check(!json.has("user_name") && !json.has("computer_name"), "no user or computer name");
            List<String> names = new ArrayList<>();
            if (json.has("channels")) json.getAsJsonArray("channels").forEach(e -> names.add(e.getAsString()));
            check(channels < 2 || names.size() == channels, "channels listed: " + names);
            if (channels > 1) {
                check(json.has("exposure_ms") && json.get("exposure_ms").isJsonObject(),
                        "exposure per channel: " + json.get("exposure_ms"));
                Path channelDir = out.resolve(folder.getFileName() + "_channels");
                for (String o : result.outputs()) {
                    String name = Path.of(o).getFileName().toString();
                    check(Files.isRegularFile(MicroManagerOutputMetadata.pathFor(channelDir.resolve(name))),
                            "per-channel metadata moved with " + name);
                }
            }
        }

        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(image, BufferedImage.class)) {
            System.out.printf("[....] %s: %d x %d, C %d, Z %d, T %d, %s, %d levels%n",
                    Path.of(image).getFileName(), server.getWidth(), server.getHeight(), server.nChannels(),
                    server.nZSlices(), server.nTimepoints(), server.getPixelType(), server.nResolutions());
            check(server.nChannels() == channels, "SizeC = " + channels);
            if (channels > 1) {
                List<String> merged = new ArrayList<>();
                for (int c = 0; c < server.nChannels(); c++) merged.add(server.getChannel(c).getName());
                check(merged.equals(acquired), "merged channels in acquisition order " + acquired + ": " + merged);
            }
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

    /** Stage positions in the acquisition, from its Summary. */
    private static long solvedPositions(Path folder) throws Exception {
        try (var files = Files.walk(folder, 3)) {
            for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                String n = p.getFileName().toString();
                if (n.equals("metadata.txt") || n.endsWith("_metadata.txt")) {
                    JsonObject summary =
                            JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonObject("Summary");
                    if (summary != null && summary.has("StagePositions")) {
                        return summary.getAsJsonArray("StagePositions").size();
                    }
                }
            }
        }
        return -1;
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
