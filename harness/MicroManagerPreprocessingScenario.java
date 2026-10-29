import java.nio.file.Files;
import java.nio.file.Path;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServerProvider;

/**
 * Real-QuPath scenario: a MicroManager acquisition with several frames (SHG taken as a time series)
 * and/or z-slices per position is reduced -- time points averaged, then z-slices max-projected --
 * into a copy beside it, and the copy stitches with one tile per position.
 *
 * <p>args: acquisitionFolder outputFolder [expectedPositions expectedFrames]
 */
public final class MicroManagerPreprocessingScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path src = Path.of(args[0]).toAbsolutePath().normalize();
        Path stitchOut = Files.createDirectories(Path.of(args[1]));
        int expectPositions = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int expectFrames = args.length > 3 ? Integer.parseInt(args[3]) : -1;

        var shape = MicroManagerPreprocessor.shape(src.toFile());
        System.out.println("[....] shape reported by metadata: " + shape);
        if (expectFrames > 0) check(shape.frames() == expectFrames, "frames = " + expectFrames);
        var options = new MicroManagerPreprocessor.Options(shape.frames() > 1, shape.slices() > 1);

        long t0 = System.nanoTime();
        var result = MicroManagerPreprocessor.run(src, MicroManagerPreprocessor.defaultOutput(src, options), options);
        System.out.printf("[....] %s: %d positions (%s frames, %s slices each) in %.1f s -> %s%n",
                options.describe(), result.positions(), result.frameSummary(), result.sliceSummary(),
                (System.nanoTime() - t0) / 1e9, result.output());
        result.notes().forEach(n -> System.out.println("[....] note: " + n));
        if (expectPositions > 0) check(result.positions() == expectPositions, "positions = " + expectPositions);
        if (expectFrames > 0) check(result.frameSummary().equals(String.valueOf(expectFrames)), "frames per position");
        check(MicroManagerPreprocessor.shape(result.output().toFile()).frames() == 1, "the copy reports 1 frame");
        if (options.maxProjectZ()) {
            check(MicroManagerPreprocessor.shape(result.output().toFile()).slices() == 1, "the copy reports 1 slice");
        }

        Double px = MicroManagerMetadataStrategy.detectPixelSizeUm(result.output().toFile());
        check(px != null && px > 0, "pixel size survives in the copy: " + px);

        var config = new StitchingConfig(
                "MicroManager metadata (MMStack or TIFF series)",
                result.output().toString(),
                stitchOut.toString(),
                "LZW",
                px == null ? 1.0 : px,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        config.setPreprocessingRecord(StitchInfoFile.Section.of(
                "preprocessing", java.util.Map.of("time points per position", result.frameSummary())));
        t0 = System.nanoTime();
        var run = StitchingWorkflow.runDetailed(config);
        System.out.printf("[....] stitched in %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, run.outputs());
        check(run.outputs().size() == 1, "one mosaic written");
        if (run.outputs().size() == 1) {
            Path image = Path.of(run.outputs().get(0));
            String record = Files.readString(StitchInfoFile.pathFor(image));
            System.out.println(record);
            if (expectPositions > 0) {
                check(record.contains("tile positions: " + expectPositions), "one tile per position, not per frame");
            }
            check(record.contains("[preprocessing]"), "stitch record names the preprocessing");
            try (var server = ImageServerProvider.buildServer(image.toUri().toString(), java.awt.image.BufferedImage.class)) {
                System.out.printf("[....] mosaic %d x %d, %d channel(s), %s, %d levels%n",
                        server.getWidth(), server.getHeight(), server.nChannels(), server.getPixelType(),
                        server.nResolutions());
                check(server.getWidth() > 0, "mosaic opens in QuPath");
            }
        }
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }
}
