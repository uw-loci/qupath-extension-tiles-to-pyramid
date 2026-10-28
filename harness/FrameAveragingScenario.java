import java.nio.file.Files;
import java.nio.file.Path;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.MicroManagerFrameAverager;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.images.servers.ImageServerProvider;

/**
 * Real-QuPath scenario: a MicroManager acquisition with several frames per position (SHG taken as a
 * time series) is averaged into {@code <folder>_averaged}, and the copy stitches with one tile per
 * position instead of one per frame.
 *
 * <p>args: acquisitionFolder outputFolder [expectedPositions expectedFrames]
 */
public final class FrameAveragingScenario {

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

        int frames = MicroManagerFrameAverager.countFrames(src.toFile());
        System.out.println("[....] frames reported by metadata: " + frames);
        if (expectFrames > 0) check(frames == expectFrames, "countFrames = " + expectFrames);

        long t0 = System.nanoTime();
        var result = MicroManagerFrameAverager.average(src, MicroManagerFrameAverager.defaultOutput(src));
        System.out.printf("[....] averaged %d positions (%s frames each) in %.1f s -> %s%n",
                result.positions(), result.frameSummary(), (System.nanoTime() - t0) / 1e9, result.output());
        result.notes().forEach(n -> System.out.println("[....] note: " + n));
        if (expectPositions > 0) check(result.positions() == expectPositions, "positions = " + expectPositions);
        if (expectFrames > 0) check(result.frameSummary().equals(String.valueOf(expectFrames)), "frames per position");
        check(MicroManagerFrameAverager.countFrames(result.output().toFile()) == 1, "the copy reports 1 frame");

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
                "frame averaging", java.util.Map.of("frames per position", result.frameSummary())));
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
            check(record.contains("[frame averaging]"), "stitch record names the averaging");
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
