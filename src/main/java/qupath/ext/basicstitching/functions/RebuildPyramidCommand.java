package qupath.ext.basicstitching.functions;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.DialogOwner;
import qupath.ext.basicstitching.assembly.direct.PyramidRebuilder;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;

/**
 * Menu command: rebuild the zoomed-out levels of OME-TIFFs whose 16-bit pyramid was written with
 * only 8 bits (tiles-to-pyramid 0.2.0 to 0.7.10). Checks each chosen file first, rebuilds only the
 * damaged ones, and writes each result to a new {@code _rebuilt.ome.tif} beside the original.
 */
public final class RebuildPyramidCommand {

    private static final Logger logger = LoggerFactory.getLogger(RebuildPyramidCommand.class);
    private static final String TITLE = "Rebuild pyramid levels";

    private RebuildPyramidCommand() {}

    public static void run() {
        List<File> files = FileChoosers.promptForMultipleFiles(
                DialogOwner.mainStage(),
                "Choose OME-TIFF images to check and rebuild",
                FileChoosers.createExtensionFilter("OME-TIFF", "*.ome.tif", "*.ome.tiff"));
        if (files == null || files.isEmpty()) {
            return;
        }
        Thread worker = new Thread(() -> checkThenAsk(files), "pyramid-rebuild");
        worker.setDaemon(true);
        worker.start();
    }

    private static void checkThenAsk(List<File> files) {
        List<Path> damaged = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (File f : files) {
            Path p = f.toPath();
            String line;
            try {
                PyramidRebuilder.Diagnosis d = PyramidRebuilder.inspect(p);
                line = d.summary();
                if (d.damaged()) {
                    damaged.add(p);
                }
            } catch (Exception e) {
                logger.error("Could not check {}", p, e);
                line = "could not be checked: " + e.getMessage();
            }
            logger.info("Pyramid check {}: {}", p.getFileName(), line);
            report.append(p.getFileName()).append(": ").append(line).append('\n');
        }
        Platform.runLater(() -> {
            if (damaged.isEmpty()) {
                Dialogs.showPlainMessage(TITLE, report + "\nNothing to rebuild.");
                return;
            }
            boolean go = Dialogs.showConfirmDialog(
                    TITLE,
                    report + "\nRebuild the " + damaged.size() + " damaged image(s)? Each is written to a new file"
                            + " ending in " + PyramidRebuilder.SUFFIX + ".ome.tif beside the original, which is"
                            + " not changed. Full resolution is copied; the zoomed-out levels are made again"
                            + " from it. Large images take minutes each.");
            if (go) {
                Thread worker = new Thread(() -> rebuildAll(damaged), "pyramid-rebuild");
                worker.setDaemon(true);
                worker.start();
            }
        });
    }

    private static void rebuildAll(List<Path> images) {
        StringBuilder done = new StringBuilder();
        int failures = 0;
        for (Path in : images) {
            Path out = PyramidRebuilder.defaultOutput(in);
            Platform.runLater(() -> Dialogs.showInfoNotification(TITLE, "Rebuilding " + in.getFileName() + "..."));
            try {
                PyramidRebuilder.rebuild(in, out, null);
                done.append(in.getFileName())
                        .append(" -> ")
                        .append(out.getFileName())
                        .append('\n');
            } catch (Exception e) {
                failures++;
                logger.error("Pyramid rebuild failed for {}", in, e);
                done.append(in.getFileName())
                        .append(": FAILED, ")
                        .append(e.getMessage())
                        .append('\n');
            }
        }
        String summary = done
                + "\nOpen the new files in place of the originals (add them to the project, or replace the"
                + " originals once QuPath has closed them).";
        boolean anyFailed = failures > 0;
        Platform.runLater(() -> {
            if (anyFailed) {
                Dialogs.showErrorMessage(TITLE, summary);
            } else {
                Dialogs.showPlainMessage(TITLE, summary);
            }
        });
    }
}
