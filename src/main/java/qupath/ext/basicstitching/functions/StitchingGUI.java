// =======================================================================================
// 4. StitchingGUI.java
// =======================================================================================
package qupath.ext.basicstitching.functions;

import static qupath.ext.basicstitching.utilities.UtilityFunctions.getCompressionTypeList;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.DialogOwner;
import qupath.ext.basicstitching.assembly.ChannelMerger;
import qupath.ext.basicstitching.assembly.direct.TileReaderPool;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.stitching.TileDirectories;
import qupath.ext.basicstitching.utilities.QPPreferences;
import qupath.ext.basicstitching.utilities.RegistrationPreferences;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;

/**
 * GUI class for the Basic Stitching Extension.
 * Provides a dialog interface for configuring stitching parameters and executing stitching operations.
 *
 * TODO: Progress bar for stitching
 * TODO: Estimate size of stitched image to predict necessary memory
 * TODO: Warn user if size exceeds QuPath's allowed limits.
 */
public class StitchingGUI {

    private static final Logger logger = LoggerFactory.getLogger(StitchingGUI.class);

    // One dialog-launched stitch at a time: it runs in the background, so the menu stays live.
    private static final AtomicBoolean STITCH_RUNNING = new AtomicBoolean(false);

    // GUI components are per-dialog: a JavaFX node can have only one parent, so reusing static
    // nodes threw "duplicate children added" on the second open. Values carry over through
    // QPPreferences instead (saved in processDialogResult).
    private final TextField folderField = new TextField(QPPreferences.getFolderLocationSaved());
    private final ComboBox<String> compressionBox = new ComboBox<>();
    private final ComboBox<StitchingConfig.OutputFormat> outputFormatBox = new ComboBox<>();
    private final TextField pixelSizeField = new TextField(QPPreferences.getImagePixelSizeInMicronsSaved());
    private final CheckBox pixelSizeOverrideCheckbox = new CheckBox("Manually edit pixel size");
    private final Label pixelSizeSourceLabel = new Label("");
    // Short enough not to be clipped at the dialog's width; it used to read "Try calculating
    // pixel si...". The tooltip says what it measures and how.
    private final Button estimatePixelSizeButton = new Button("Measure from tiles...");
    private final TextField downsampleField = new TextField(QPPreferences.getDownsampleSaved());
    private final TextField matchStringField = new TextField(QPPreferences.getSearchStringSaved());
    private final ComboBox<String> stitchingGridBox = new ComboBox<>();
    private final Button folderButton = new Button("Select Folder");
    private final CheckBox resolveOverlapsCheckbox = new CheckBox("Solve tile overlaps (content-based registration)");
    // Shown only when the folder holds 2+ matching single-channel subdirectories (RGB is not channels).
    private final CheckBox mergeChannelsCheckbox = new CheckBox("Merge channels into one multichannel image");
    // Only for the methods whose positions are stage coordinates: see addStageInvertComponents.
    private final CheckBox invertXCheckbox = new CheckBox("Invert X axis");
    private final CheckBox invertYCheckbox = new CheckBox("Invert Y axis");
    private final Label invertLabel = new Label("Stage axes:");
    private final HBox invertBox = new HBox(16, invertXCheckbox, invertYCheckbox);
    // Per-run registration controls (the tuning knobs live in Preferences -> Tiles-to-pyramid).
    /** Versions the ZARR writer actually stamps, so the dialog cannot name a different one. */
    private static final String OME_ZARR_NGFF_VERSION =
            qupath.ext.basicstitching.assembly.direct.ZarrOutputWriter.NGFF_VERSION;

    private static final String ZARR_FORMAT_VERSION =
            qupath.ext.basicstitching.assembly.direct.ZarrOutputWriter.ZARR_FORMAT_VERSION;

    // Short enough to read in the combo at its dialog width; the tooltip carries the detail.
    // The long form used to render as "Auto (best matching folder, ..." with the answer cut off.
    private static final String AUTO_REFERENCE = "Auto (best match)";
    private static final String PROJECTION_REFERENCE = "Normalized merge of all";
    private final CheckBox overlapAutoCheckbox = new CheckBox("Overlap %: derive from the tile grid");
    private final TextField overlapXField = new TextField("10");
    private final TextField overlapYField = new TextField("10");
    private final Label overlapXLabel = new Label("Overlap X %:");
    private final Label overlapYLabel = new Label("Overlap Y %:");
    private final Label referenceLabel = new Label("Reference subdirectory:");
    private final ComboBox<String> referenceBox = new ComboBox<>();
    private final Label registrationHintLabel = new Label("Advanced tuning: Preferences -> Tiles-to-pyramid");
    private final GridPane registrationOptionsPane = new GridPane();
    private final CheckBox useFudgeFactorCheckbox = new CheckBox("Apply fudge factor to adjust for gaps between tiles");
    private final TextField xFudgeField = new TextField("1.0");
    private final TextField yFudgeField = new TextField("1.0");
    private final Hyperlink vectraForumLink = new Hyperlink("See forum discussion");
    // Labels
    private final Label stitchingGridLabel = new Label("Stitching Method:");
    private final Label folderLabel = new Label("Folder location:");
    private final Label compressionLabel = new Label("Compression type:");
    private final Label outputFormatLabel = new Label("Output format:");
    private final Label pixelSizeLabel = new Label("Pixel size, microns:");
    private final Label downsampleLabel = new Label("Downsample:");
    private final Label matchStringLabel = new Label("Stitch sub-folders with text string:");
    private final Hyperlink githubLink = new Hyperlink("GitHub ReadMe");
    private final Label xFudgeLabel = new Label("X fudge factor:");
    private final Label yFudgeLabel = new Label("Y fudge factor:");

    // Map to hold the positions of each GUI element
    private final Map<Node, Integer> guiElementPositions = new HashMap<>();

    /**
     * Creates and displays the main GUI dialog for stitching configuration.
     * Handles user input validation, preference saving, and initiates stitching process.
     */
    public static void createGUI() {
        if (STITCH_RUNNING.get()) {
            showAlertDialog("A stitch is already running. Wait for it to finish before starting another.");
            return;
        }
        new StitchingGUI().show();
    }

    private void show() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initModality(Modality.APPLICATION_MODAL);
        var qupath = QuPathGUI.getInstance();
        if (qupath != null && qupath.getStage() != null) {
            dlg.initOwner(qupath.getStage());
        }
        dlg.setTitle("Tiles to Pyramid");
        dlg.setHeaderText("Choose the tile folder and stitching options, then click Stitch.");
        dlg.setResizable(true);

        // The form is taller than a laptop screen once registration options are shown; without a
        // scroll pane the button bar is pushed off the bottom and the dialog cannot be run.
        GridPane content = createContent();
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(Screen.getPrimary().getVisualBounds().getHeight() * 0.7);
        dlg.getDialogPane().setContent(scroll);
        // A dialog window keeps its first size, so rows appearing later (ticking "Solve tile
        // overlaps") would only scroll inside it. Refit the window whenever the form changes height;
        // the scroll pane cap still bounds it, and beyond that the form scrolls.
        content.heightProperty().addListener((obs, o, n) -> Platform.runLater(() -> fitWindowToContent(dlg)));
        // Also refit once shown: a window-size manager may restore a stale (too small) height saved
        // under this title, which would hide the buttons until something changed the form.
        dlg.setOnShown(e -> Platform.runLater(() -> fitWindowToContent(dlg)));

        ButtonType stitchType = new ButtonType("Stitch", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(stitchType, ButtonType.CANCEL);
        // OK_DONE makes Stitch the default button, so Enter in any text field would start a long
        // stitch. Only an explicit click should.
        ((Button) dlg.getDialogPane().lookupButton(stitchType)).setDefaultButton(false);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isPresent() && result.get() == stitchType) {
            processDialogResult();
        }
    }

    /**
     * Resize the dialog window to its content's preferred height, keeping any extra width the user
     * dragged in, and nudge it up if it would now run off the bottom of its screen.
     */
    private static void fitWindowToContent(Dialog<?> dlg) {
        Scene scene = dlg.getDialogPane().getScene();
        Window window = scene == null ? null : scene.getWindow();
        if (window == null || !window.isShowing()) {
            return;
        }
        double userWidth = window.getWidth();
        window.sizeToScene();
        window.setWidth(Math.max(userWidth, window.getWidth()));
        var screens =
                Screen.getScreensForRectangle(window.getX(), window.getY(), window.getWidth(), window.getHeight());
        Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
        if (window.getY() + window.getHeight() > bounds.getMaxY()) {
            window.setY(Math.max(bounds.getMinY(), bounds.getMaxY() - window.getHeight()));
        }
    }

    private void processDialogResult() {
        try {
            // Read values from dialog and save to persistent preferences
            String folderPath = folderField.getText();
            String outputPath = folderField.getText();
            String compressionType = compressionBox.getValue();
            StitchingConfig.OutputFormat outputFormat = outputFormatBox.getValue();
            double pixelSize = parseDoubleField(pixelSizeField.getText(), 0.0);
            double downsample = parseDoubleField(downsampleField.getText(), 1.0);
            String matchingString = matchStringField.getText();
            String stitchingType = stitchingGridBox.getValue();
            double zSpacingMicrons = 1.0;

            QPPreferences.setFolderLocationSaved(folderPath);
            QPPreferences.setStitchingMethodSaved(stitchingType);
            QPPreferences.setCompressionTypeSaved(compressionType);
            QPPreferences.setDownsampleSaved(downsampleField.getText());
            QPPreferences.setSearchStringSaved(matchingString);
            if (pixelSizeOverrideCheckbox.isSelected()) {
                QPPreferences.setImagePixelSizeInMicronsSaved(pixelSizeField.getText());
            }

            // Handle fudge factors for Vectra
            double xFudgeFactor = 1.0;
            double yFudgeFactor = 1.0;
            if ("Vectra tiles with metadata".equals(stitchingType) && useFudgeFactorCheckbox.isSelected()) {
                xFudgeFactor = parseDoubleField(xFudgeField.getText(), 1.0);
                yFudgeFactor = parseDoubleField(yFudgeField.getText(), 1.0);
            }

            // Create a config object with fudge factors and output format
            StitchingConfig config = new StitchingConfig(
                    stitchingType,
                    folderPath,
                    outputPath,
                    compressionType,
                    pixelSize,
                    downsample,
                    matchingString,
                    zSpacingMicrons,
                    xFudgeFactor,
                    yFudgeFactor,
                    outputFormat != null ? outputFormat : StitchingConfig.OutputFormat.OME_TIFF);

            // A ticked "Manually edit pixel size" makes the typed value
            // authoritative -- it must override the (possibly wrong) metadata
            // PixelSizeUm rather than be silently discarded by the strategy.
            config.setManualPixelSizeOverride(pixelSizeOverrideCheckbox.isSelected());

            // Content-based overlap resolution. When enabled, solve the tile registration on this
            // run and write a TileRegistration.txt beside the tiles; when disabled, leave every tile
            // at its nominal stage position exactly as before. The choice is remembered.
            boolean resolveOverlaps = resolveOverlapsCheckbox.isSelected();
            QPPreferences.setResolveOverlapsSaved(resolveOverlaps);
            if (resolveOverlaps) {
                // Tuning (confidence, max shift, lambda, gates, ...) comes from the persistent
                // "Tiles-to-pyramid" preferences; the dialog supplies only the two per-run choices.
                RegistrationSettings settings = RegistrationPreferences.toSettings();

                boolean overlapAuto = overlapAutoCheckbox.isSelected();
                QPPreferences.setRegOverlapAutoSaved(overlapAuto);
                if (!overlapAuto) {
                    double ox = parseDoubleField(overlapXField.getText(), 10.0);
                    double oy = parseDoubleField(overlapYField.getText(), 10.0);
                    QPPreferences.setRegOverlapXSaved(overlapXField.getText());
                    QPPreferences.setRegOverlapYSaved(overlapYField.getText());
                    settings = settings.withExplicitOverlap(ox, oy);
                }

                String choice = referenceBox.getValue();
                RegistrationReference reference;
                if (PROJECTION_REFERENCE.equals(choice)) {
                    reference = new RegistrationReference.Projection(referenceBox.getItems().stream()
                            .filter(c -> !AUTO_REFERENCE.equals(c) && !PROJECTION_REFERENCE.equals(c))
                            .toList());
                } else if (choice == null || AUTO_REFERENCE.equals(choice)) {
                    reference = RegistrationReference.auto();
                } else {
                    reference = new RegistrationReference.Single(choice);
                }

                Path solutionOut = Paths.get(folderPath).resolve(TileRegistrationSolution.DEFAULT_FILENAME);
                config.setRegistrationMode(new RegistrationMode.Solve(solutionOut, settings, reference));
                logger.info(
                        "Content-based overlap resolution enabled (overlap {}, reference {}); solution -> {}",
                        overlapAuto ? "auto" : (overlapXField.getText() + "%/" + overlapYField.getText() + "%"),
                        choice == null ? AUTO_REFERENCE : choice,
                        solutionOut);
            }

            QPPreferences.setOutputFormatSaved(config.outputFormat.name());
            boolean mergeOffered = mergeChannelsCheckbox.isVisible();
            if (mergeOffered) {
                QPPreferences.setMergeChannelsSaved(mergeChannelsCheckbox.isSelected());
            }

            // Only the MicroManager strategy reads stage coordinates, and the checkboxes are hidden
            // for every other method -- so take them as false rather than letting a remembered tick
            // apply to a method it was never chosen for.
            boolean invertX = invertBox.isVisible() && invertXCheckbox.isSelected();
            boolean invertY = invertBox.isVisible() && invertYCheckbox.isSelected();
            if (invertBox.isVisible()) {
                QPPreferences.setStageInvertXSaved(invertX);
                QPPreferences.setStageInvertYSaved(invertY);
            }
            runInBackground(config, mergeOffered && mergeChannelsCheckbox.isSelected(), invertX, invertY);

        } catch (Exception e) {
            logger.error("Error processing dialog result", e);
            showAlertDialog("Error processing input: " + e.getMessage());
        }
    }

    /**
     * Run the stitch (and the optional channel merge) off the FX thread so QuPath stays responsive,
     * reporting the outcome in a dialog when it finishes.
     *
     * @param invertX negate stage X before placing MicroManager tiles
     * @param invertY negate stage Y before placing MicroManager tiles
     */
    private static void runInBackground(
            StitchingConfig config, boolean mergeChannels, boolean invertX, boolean invertY) {
        if (!STITCH_RUNNING.compareAndSet(false, true)) {
            showAlertDialog("A stitch is already running. Wait for it to finish before starting another.");
            return;
        }
        Dialogs.showInfoNotification(
                "Tiles to Pyramid", "Stitching started in the background. You will be told when it finishes.");
        Thread worker = new Thread(
                () -> {
                    boolean ok = false;
                    StringBuilder message = new StringBuilder();
                    // The axis flags are process-global volatile statics on the two strategies that
                    // read stage coordinates, which is also how QPSC drives them. Both pairs are set
                    // because only the selected strategy reads its own, and the checkboxes are hidden
                    // (so both flags are false) for the methods that read neither. Restore whatever
                    // was there instead of clearing to false, so a stitch started from this dialog
                    // cannot strand a different caller's setting. STITCH_RUNNING keeps two dialog
                    // stitches from interleaving here.
                    boolean prevMmX = MicroManagerMetadataStrategy.flipStitchingX;
                    boolean prevMmY = MicroManagerMetadataStrategy.flipStitchingY;
                    boolean prevTcX = TileConfigurationTxtStrategy.flipStitchingX;
                    boolean prevTcY = TileConfigurationTxtStrategy.flipStitchingY;
                    MicroManagerMetadataStrategy.flipStitchingX = invertX;
                    MicroManagerMetadataStrategy.flipStitchingY = invertY;
                    TileConfigurationTxtStrategy.flipStitchingX = invertX;
                    TileConfigurationTxtStrategy.flipStitchingY = invertY;
                    try {
                        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config);
                        List<String> outputs = result.outputs();
                        if (outputs.isEmpty()) {
                            message.append("Stitching failed. See the log for details.");
                        } else {
                            ok = true;
                            message.append("Output files:");
                            outputs.forEach(o -> message.append("\n  ").append(o));
                            if (!result.failedSubdirs().isEmpty()) {
                                ok = false;
                                message.append("\n\nFailed: ").append(result.failedSubdirs());
                            }
                            if (mergeChannels && outputs.size() >= 2) {
                                // A size or pixel-type mismatch throws from ChannelMergeImageServer; catch
                                // it here so the stitched outputs listed above still reach the user.
                                String merged = null;
                                String reason = "See the log for details.";
                                try {
                                    merged = mergeChannelOutputs(outputs, config);
                                } catch (Exception e) {
                                    logger.error("Channel merge failed", e);
                                    if (e.getMessage() != null) {
                                        reason = e.getMessage();
                                    }
                                }
                                if (merged != null) {
                                    message.append("\n\nMerged channels into:\n  ")
                                            .append(merged);
                                } else {
                                    ok = false;
                                    message.append("\n\nChannel merge failed; the per-channel images were kept.\n")
                                            .append(reason);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        logger.error("Stitching failed", t);
                        message.setLength(0);
                        message.append("Stitching failed: ").append(t.getMessage());
                    } finally {
                        MicroManagerMetadataStrategy.flipStitchingX = prevMmX;
                        MicroManagerMetadataStrategy.flipStitchingY = prevMmY;
                        TileConfigurationTxtStrategy.flipStitchingX = prevTcX;
                        TileConfigurationTxtStrategy.flipStitchingY = prevTcY;
                        STITCH_RUNNING.set(false);
                    }
                    boolean success = ok;
                    Platform.runLater(() -> showResultDialog(success, message.toString()));
                },
                "tiles-to-pyramid-stitch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Combine the per-subdirectory stitches into one multichannel image named after the selected
     * folder. Channels are ordered and named by their subdirectory (the output file stem).
     */
    private static String mergeChannelOutputs(List<String> outputs, StitchingConfig config) {
        List<String> sorted = new ArrayList<>(outputs);
        Collections.sort(sorted);
        List<String> names = sorted.stream()
                .map(p -> GeneralTools.stripExtension(new File(p).getName()))
                .toList();
        String stem = new File(config.folderPath).getName() + "_merged";
        logger.info("Merging {} channel stitches {} into {}", sorted.size(), names, stem);
        return ChannelMerger.merge(sorted, names, config.outputPath, stem, config.compressionType, config.outputFormat);
    }

    /**
     * Adds the "merge channels" option. It is visible only when merging applies: two or more tile
     * folders will be stitched and their tiles are single-channel (RGB is one image, not channels).
     */
    private void addMergeChannelsComponent(GridPane pane) {
        mergeChannelsCheckbox.setSelected(QPPreferences.getMergeChannelsSaved());
        mergeChannelsCheckbox.setTooltip(
                new Tooltip("Each matching sub-folder is stitched to its own single-channel image.\n"
                        + "When ticked, those images are also combined into one multichannel image\n"
                        + "named <folder>_merged, with channels named after the sub-folders.\n"
                        + "The per-channel images are kept. Not offered for RGB tiles."));
        Integer row = guiElementPositions.get(mergeChannelsCheckbox);
        if (row != null) {
            pane.add(mergeChannelsCheckbox, 0, row, 2, 1);
        } else {
            logger.error("Row index not found for mergeChannelsCheckbox");
        }
        folderField.textProperty().addListener((obs, o, n) -> refreshMergeVisibility());
        matchStringField.textProperty().addListener((obs, o, n) -> refreshMergeVisibility());
    }

    /**
     * Adds the stage-axis inversion checkboxes to the GridPane.
     *
     * <p>Whether a rising stage coordinate moves right/down in the camera image is a property of the
     * microscope -- how the stage is wired and how the camera is mounted. Neither strategy that
     * reads stage coordinates can infer it, so both assume rising stage equals rising pixel; on a
     * scope where either axis runs the other way, every tile lands in its mirrored slot. The tiles
     * still form a grid of the right size and the overlaps still measure right, so the mosaic looks
     * plausible at a glance while no seam actually matches -- with registration on, every edge is
     * rejected and the log says so; with it off, nothing says anything.
     *
     * <p>Offered for MicroManager, whose sidecars carry absolute stage positions, and for
     * TileConfiguration.txt, whose coordinates are stage micrometers when an acquisition wrote the
     * file (QPSC does, and negates both axes on a stage-inverted scope). A TileConfiguration.txt
     * written by Fiji's Grid/Collection stitching is already in image space and wants both boxes
     * clear -- which is the default. Not offered for Vectra or Filename[x,y]: those positions are
     * image-space by construction, so there is no stage convention left to resolve.
     */
    private void addStageInvertComponents(GridPane pane) {
        invertXCheckbox.setSelected(QPPreferences.getStageInvertXSaved());
        invertYCheckbox.setSelected(QPPreferences.getStageInvertYSaved());
        Tooltip tip = new Tooltip("Negate the stage X and/or Y coordinate before placing tiles.\n"
                + "Set these to match the microscope: if the mosaic comes out mirrored, or\n"
                + "registration rejects every seam, one or both axes run the other way.\n"
                + "Leave both clear for a TileConfiguration.txt written by Fiji, whose\n"
                + "coordinates are already in image space.\n"
                + "The setting is remembered, because it belongs to the scope, not the run.");
        invertLabel.setTooltip(tip);
        invertXCheckbox.setTooltip(tip);
        invertYCheckbox.setTooltip(tip);
        addToGrid(pane, invertLabel, invertBox);
    }

    private void refreshMergeVisibility() {
        int channels = countChannelFolders();
        mergeChannelsCheckbox.setVisible(channels >= 2);
        if (channels >= 2) {
            mergeChannelsCheckbox.setText("Merge the " + channels + " channel stitches into one multichannel image");
        }
    }

    /** Number of tile folders that will stitch to separate single-channel images; 0 if merging does not apply. */
    private int countChannelFolders() {
        String method = stitchingGridBox.getValue();
        String path = folderField.getText();
        if (path == null || path.isBlank()) {
            return 0;
        }
        if (method != null && method.startsWith("MicroManager")) {
            // MicroManager packs a position's channels into pages of one file rather
            // than one folder per channel, so the count comes from the metadata.
            return MicroManagerMetadataStrategy.countChannels(new File(path.trim()));
        }
        try {
            List<Path> dirs = TileDirectories.resolve(Paths.get(path.trim()), matchStringField.getText());
            if (dirs.size() < 2) {
                return 0;
            }
            File tile = firstTiff(dirs.get(0));
            if (tile == null || TileReaderPool.getDimensions(tile).isRGB()) {
                return 0;
            }
            return dirs.size();
        } catch (Exception e) {
            logger.debug("Could not determine channel folders for {}: {}", path, e.getMessage());
            return 0;
        }
    }

    private static File firstTiff(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".tif") || n.endsWith(".tiff");
                    })
                    .sorted()
                    .findFirst()
                    .map(Path::toFile)
                    .orElse(null);
        }
    }

    /**
     * Safely parses a string to double with a default fallback value.
     */
    private static double parseDoubleField(String text, double defaultValue) {
        if (text == null || text.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid number format: {}, using default: {}", text, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Creates and returns a GridPane containing all the components for the GUI.
     * This method initializes the positions of each component in the grid,
     * adds the components to the grid, and sets their initial visibility.
     *
     * @return A GridPane containing all the configured components.
     */
    private GridPane createContent() {
        // Create a new GridPane for layout
        GridPane pane = new GridPane();

        // Set horizontal and vertical gaps between grid cells
        pane.setHgap(10);
        pane.setVgap(10);

        // Initialize the positions of each component in the grid
        initializePositions();

        // Add various components to the grid pane
        addStitchingGridComponents(pane);
        addFolderSelectionComponents(pane);
        addMatchStringComponents(pane);
        addStageInvertComponents(pane);
        addMergeChannelsComponent(pane);
        addCompressionComponents(pane);
        addOutputFormatComponents(pane);
        addPixelSizeComponents(pane);
        addDownsampleComponents(pane);
        addRegistrationComponent(pane);
        addGitHubLinkComponent(pane);

        // Initial autofill from the restored folder preference -- but ONLY when a person chose
        // that folder. The default is the user's home directory, and reading a pixel size out of
        // whatever acquisition happens to sit under it would pre-fill a value from unrelated data
        // and stitch at the wrong scale. The textProperty listener handles every later change.
        if (QPPreferences.isFolderChosen()) {
            autoFillPixelSizeFromFolder();
        }

        // Update the components' visibility based on the current selection
        updateComponentsBasedOnSelection(pane);
        addFudgeFactorComponents(pane);

        // Hidden rows (Vectra fudge factors, pixel size for other methods) must not reserve space.
        for (Node child : pane.getChildren()) {
            if (!child.managedProperty().isBound()) {
                child.managedProperty().bind(child.visibleProperty());
            }
            // Labels keep their full text; the scroll bar appearing must not truncate them.
            if (child instanceof Label label) {
                label.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        return pane;
    }

    /**
     * Adds a label and its associated control to the specified GridPane.
     */
    private void addToGrid(GridPane pane, Node label, Node control) {
        Integer rowIndex = guiElementPositions.get(label);
        if (rowIndex != null) {
            pane.add(label, 0, rowIndex);
            pane.add(control, 1, rowIndex);
        } else {
            logger.error("Row index not found for component: {}", label);
        }
    }

    /**
     * Adds the content-based overlap-resolution (tile registration) toggle to the GridPane.
     *
     * <p>When ticked, the stitch measures the true overlap between neighboring tiles and corrects
     * their positions before compositing, closing seams left by stage backlash and drift, and writes
     * a {@code TileRegistration.txt} solution beside the tiles. When unticked, tiles are placed at
     * their nominal stage positions -- the historical behavior and the faster path.
     */
    private void addRegistrationComponent(GridPane pane) {
        resolveOverlapsCheckbox.setSelected(QPPreferences.getResolveOverlapsSaved());
        resolveOverlapsCheckbox.setTooltip(
                new Tooltip("Measure the real overlap between neighboring tiles and correct their positions before\n"
                        + "stitching, closing seams caused by stage backlash and drift.\n"
                        + "Off: tiles are placed at their nominal stage positions (faster).\n"
                        + "Writes a TileRegistration.txt solution file beside the tiles."));

        Integer row = guiElementPositions.get(resolveOverlapsCheckbox);
        if (row != null) {
            pane.add(resolveOverlapsCheckbox, 0, row, 2, 1);
        } else {
            logger.error("Row index not found for resolveOverlapsCheckbox");
        }

        buildRegistrationOptions(pane);
    }

    /**
     * Build the per-run registration options that appear when overlap resolution is enabled: whether
     * to derive the overlap or set it by hand, and which subdirectory to solve on. Everything else
     * (confidence, max shift, solver knobs) is persistent tuning and lives in the Preferences pane
     * under "Tiles-to-pyramid", so it is not duplicated here; a hint points there.
     */
    private void buildRegistrationOptions(GridPane pane) {
        registrationOptionsPane.setHgap(8);
        registrationOptionsPane.setVgap(6);
        registrationOptionsPane.setStyle("-fx-padding: 4 0 4 18;"); // indent under the checkbox

        overlapAutoCheckbox.setSelected(QPPreferences.getRegOverlapAutoSaved());
        overlapAutoCheckbox.setTooltip(
                new Tooltip("Derive the overlap from the spacing of the nominal tile positions (recommended).\n"
                        + "Untick to type the overlap percentages your acquisition used."));
        overlapXField.setText(QPPreferences.getRegOverlapXSaved());
        overlapYField.setText(QPPreferences.getRegOverlapYSaved());
        overlapXField.setPrefColumnCount(4);
        overlapYField.setPrefColumnCount(4);

        referenceLabel.setTooltip(
                new Tooltip("What to measure tile overlaps on. The solution is reused by every subdirectory\n"
                        + "(angles/channels are co-captured, so they must share one solve).\n\n"
                        + "Auto (best match): tries a sample of seams on every folder, solves on the one that\n"
                        + "matches most decisively, and re-measures only the weak seams on the other folders.\n"
                        + "Normalized merge of all: scales each folder once for the whole dataset, then averages\n"
                        + "them.\n"
                        + "Reads every folder at every seam, so it takes longer than a single folder.\n"
                        + "A named folder: solve on that folder only."));
        referenceBox.setTooltip(referenceLabel.getTooltip());
        refreshReferenceChoices();
        folderField.textProperty().addListener((obs, o, n) -> refreshReferenceChoices());

        registrationHintLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #666;");
        registrationHintLabel.setTooltip(
                new Tooltip("Confidence, max shift, nominal pull, gates and other tuning are persistent settings.\n"
                        + "Set them in QuPath Preferences under the 'Tiles-to-pyramid' category; they are\n"
                        + "shared with QPSC."));

        // Enable the manual overlap fields only when not deriving.
        Runnable syncOverlapFields = () -> {
            boolean manual = !overlapAutoCheckbox.isSelected();
            overlapXField.setDisable(!manual);
            overlapYField.setDisable(!manual);
            overlapXLabel.setDisable(!manual);
            overlapYLabel.setDisable(!manual);
        };
        overlapAutoCheckbox.setOnAction(e -> syncOverlapFields.run());
        syncOverlapFields.run();

        registrationOptionsPane.add(overlapAutoCheckbox, 0, 0, 4, 1);
        registrationOptionsPane.add(overlapXLabel, 0, 1);
        registrationOptionsPane.add(overlapXField, 1, 1);
        registrationOptionsPane.add(overlapYLabel, 2, 1);
        registrationOptionsPane.add(overlapYField, 3, 1);
        registrationOptionsPane.add(referenceLabel, 0, 2);
        // Wide enough for the longest entry plus a sub-folder name; without it the combo takes its
        // width from the column and truncates the choice the user just made.
        referenceBox.setPrefWidth(240);
        registrationOptionsPane.add(referenceBox, 1, 2, 3, 1);
        registrationOptionsPane.add(registrationHintLabel, 0, 3, 4, 1);

        // The whole panel is visible only when overlap resolution is enabled.
        registrationOptionsPane.visibleProperty().bind(resolveOverlapsCheckbox.selectedProperty());
        registrationOptionsPane.managedProperty().bind(resolveOverlapsCheckbox.selectedProperty());

        Integer row = guiElementPositions.get(registrationOptionsPane);
        if (row != null) {
            pane.add(registrationOptionsPane, 0, row, 3, 1);
        } else {
            logger.error("Row index not found for registrationOptionsPane");
        }
    }

    /**
     * Populate the reference-subdirectory choices from the immediate subdirectories of the selected
     * folder, keeping {@link #AUTO_REFERENCE} first (and {@link #PROJECTION_REFERENCE} second when
     * there is more than one subdirectory to merge), preserving the current selection when it still
     * exists.
     */
    private void refreshReferenceChoices() {
        String previous = referenceBox.getValue();
        java.util.List<String> choices = new java.util.ArrayList<>();
        choices.add(AUTO_REFERENCE);
        String path = folderField.getText();
        if (path != null && !path.trim().isEmpty()) {
            File folder = new File(path.trim());
            File[] children = folder.listFiles(File::isDirectory);
            if (children != null) {
                java.util.Arrays.sort(children);
                if (children.length >= 2) {
                    choices.add(PROJECTION_REFERENCE);
                }
                for (File child : children) {
                    choices.add(child.getName());
                }
            }
        }
        referenceBox.getItems().setAll(choices);
        referenceBox.setValue(choices.contains(previous) ? previous : AUTO_REFERENCE);
    }

    /**
     * Adds a GitHub repository hyperlink to the GridPane.
     */
    private void addGitHubLinkComponent(GridPane pane) {
        githubLink.setOnAction(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://github.com/uw-loci/qupath-extension-tiles-to-pyramid"));
            } catch (Exception ex) {
                logger.error("Error opening link", ex);
            }
        });

        Integer rowIndex = guiElementPositions.get(githubLink);
        if (rowIndex != null) {
            pane.add(githubLink, 0, rowIndex, 2, 1);
        }
    }

    /**
     * Initializes the positions of GUI elements in the GridPane.
     */
    private void initializePositions() {
        int currentPosition = 0;

        guiElementPositions.put(stitchingGridLabel, currentPosition++);
        guiElementPositions.put(folderLabel, currentPosition++);
        guiElementPositions.put(compressionLabel, currentPosition++);
        guiElementPositions.put(outputFormatLabel, currentPosition++);
        guiElementPositions.put(pixelSizeLabel, currentPosition++);
        guiElementPositions.put(pixelSizeOverrideCheckbox, currentPosition++);
        guiElementPositions.put(downsampleLabel, currentPosition++);
        guiElementPositions.put(matchStringLabel, currentPosition++);
        guiElementPositions.put(invertLabel, currentPosition++);
        guiElementPositions.put(mergeChannelsCheckbox, currentPosition++);
        guiElementPositions.put(resolveOverlapsCheckbox, currentPosition++);
        guiElementPositions.put(registrationOptionsPane, currentPosition++);
        guiElementPositions.put(githubLink, currentPosition++);
        guiElementPositions.put(useFudgeFactorCheckbox, currentPosition++);
        guiElementPositions.put(xFudgeLabel, currentPosition++);
        guiElementPositions.put(yFudgeLabel, currentPosition++);
        guiElementPositions.put(vectraForumLink, currentPosition++);
    }

    /**
     * Adds stitching grid components to the specified GridPane.
     */
    private void addStitchingGridComponents(GridPane pane) {
        stitchingGridBox.getItems().clear();
        stitchingGridBox
                .getItems()
                .addAll(
                        "Vectra tiles with metadata",
                        "Filename[x,y] with coordinates in microns",
                        "Coordinates in TileConfiguration.txt file",
                        "MicroManager metadata (MMStack or TIFF series)");
        // The items are identifiers, not just labels: StitchingStrategyFactory switches on them, QPSC
        // passes them, and the saved preference stores them. Rename only what the user sees.
        stitchingGridBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String method) {
                return "Coordinates in TileConfiguration.txt file".equals(method)
                        ? "TileConfiguration.txt file"
                        : method;
            }

            @Override
            public String fromString(String label) {
                return "TileConfiguration.txt file".equals(label) ? "Coordinates in TileConfiguration.txt file" : label;
            }
        });

        stitchingGridBox.setValue(QPPreferences.getStitchingMethodSaved());
        // Remember a choice as soon as it is made, not only when Stitch is clicked (Cancel lost it).
        stitchingGridBox.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                QPPreferences.setStitchingMethodSaved(n);
            }
        });
        stitchingGridBox.setOnAction(e -> updateComponentsBasedOnSelection(pane));

        Tooltip stitchingTooltip = new Tooltip("Method used to determine tile positions for stitching.");
        stitchingGridLabel.setTooltip(stitchingTooltip);
        stitchingGridBox.setTooltip(stitchingTooltip);

        addToGrid(pane, stitchingGridLabel, stitchingGridBox);
    }

    /**
     * Adds components for folder selection to the specified GridPane.
     */
    private void addFolderSelectionComponents(GridPane pane) {
        // Only set default if the field is empty (preserve user's last selection)
        if (folderField.getText() == null || folderField.getText().trim().isEmpty()) {
            // Try saved preference first, then fall back to project folder
            String savedFolder = QPPreferences.getFolderLocationSaved();
            if (savedFolder != null && !savedFolder.isEmpty() && new File(savedFolder).isDirectory()) {
                folderField.setText(savedFolder);
            } else {
                try {
                    String defaultFolderPath = QPEx.buildPathInProject("Tiles");
                    logger.info("Default folder path: {}", defaultFolderPath);
                    folderField.setText(defaultFolderPath);
                } catch (Exception e) {
                    logger.info("Error setting default folder path, usually due to no project being open", e);
                }
            }
        }

        folderButton.setOnAction(e -> {
            try {
                DirectoryChooser dirChooser = new DirectoryChooser();
                dirChooser.setTitle("Select Folder");

                String initialDirPath = folderField.getText();
                if (initialDirPath != null && !initialDirPath.trim().isEmpty()) {
                    File initialDir = new File(initialDirPath.trim());
                    // Walk up to find the nearest existing parent directory
                    while (initialDir != null && !initialDir.isDirectory()) {
                        initialDir = initialDir.getParentFile();
                    }
                    if (initialDir != null && initialDir.isDirectory()) {
                        dirChooser.setInitialDirectory(initialDir);
                    }
                }

                File selectedDir = dirChooser.showDialog(null);
                if (selectedDir != null) {
                    folderField.setText(selectedDir.getAbsolutePath());
                    QPPreferences.setFolderLocationSaved(selectedDir.getAbsolutePath());
                    QPPreferences.setFolderChosen(true);
                    logger.info("Selected folder path: {}", selectedDir.getAbsolutePath());
                }
            } catch (Exception ex) {
                logger.error("Error selecting folder", ex);
            }
        });

        // Any folder-field change (browse, type, paste) re-auto-fills any
        // values we can read from the folder's MMStack metadata. The autofill
        // helper is a no-op when the manual-override checkbox is ticked, so
        // user edits to the pixel size are preserved while overriding.
        // A change here is always a person browsing, typing or pasting: the field is seeded from
        // the preference before this listener exists, so the default never trips it.
        folderField.textProperty().addListener((obs, oldVal, newVal) -> {
            QPPreferences.setFolderChosen(true);
            autoFillPixelSizeFromFolder();
        });

        Tooltip folderTooltip = new Tooltip("Root folder containing the tile images to stitch.");
        folderLabel.setTooltip(folderTooltip);
        folderField.setTooltip(folderTooltip);

        addToGrid(pane, folderLabel, folderField);

        Integer rowIndex = guiElementPositions.get(folderLabel);
        if (rowIndex != null) {
            pane.add(folderButton, 2, rowIndex);
        } else {
            logger.error("Row index not found for folderButton");
        }
    }

    /**
     * Adds compression selection components to the specified GridPane.
     */
    private void addCompressionComponents(GridPane pane) {
        List<String> compressionTypes = getCompressionTypeList();
        compressionBox.getItems().clear();
        compressionBox.getItems().addAll(compressionTypes);

        compressionBox.setValue(QPPreferences.getCompressionTypeSaved());
        compressionBox.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                QPPreferences.setCompressionTypeSaved(n);
            }
        });

        // Named per option: the list comes from QuPath's CompressionType and the names alone say
        // nothing about which are lossy, which need 8-bit RGB, or what OME-Zarr does with them.
        Tooltip compressionTooltip = new Tooltip(
                "How the pixels are compressed in the stitched image. All are lossless except where said.\n\n"
                        + "DEFAULT: let the writer choose -- Bio-Formats picks the OME-TIFF codec; OME-Zarr uses zstd.\n"
                        + "LZW: lossless, widely readable, modest compression. A safe default for OME-TIFF.\n"
                        + "ZLIB / deflate: lossless, smaller than LZW, slower to write and read.\n"
                        + "J2K: JPEG-2000, lossless, small files, slow. Works with 16-bit data.\n"
                        + "J2K_LOSSY: JPEG-2000, LOSSY. Smallest files; pixel values change, so avoid it for\n"
                        + "    measurements.\n"
                        + "JPEG: LOSSY, 8-bit RGB only. Fails on 16-bit or multichannel data.\n"
                        + "UNCOMPRESSED: no compression. Largest files, fastest to write and read.\n\n"
                        + "OME-Zarr uses Blosc instead and maps these: LZW/ZLIB -> zlib, UNCOMPRESSED -> none,\n"
                        + "anything else -> zstd. JPEG and J2K have no Zarr equivalent, so they become zstd\n"
                        + "(lossless) and the log says so.");
        compressionLabel.setTooltip(compressionTooltip);
        compressionBox.setTooltip(compressionTooltip);

        addToGrid(pane, compressionLabel, compressionBox);
    }

    /**
     * Adds output format selection components to the specified GridPane.
     */
    private void addOutputFormatComponents(GridPane pane) {
        outputFormatBox.getItems().clear();
        outputFormatBox.getItems().addAll(StitchingConfig.OutputFormat.values());
        // Display only. The enum NAME is what the preference stores and what QPSC passes, so the
        // label can say whatever is clearest without touching either.
        outputFormatBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(StitchingConfig.OutputFormat format) {
                if (format == null) {
                    return "";
                }
                return format == StitchingConfig.OutputFormat.OME_ZARR
                        ? "OME-Zarr (NGFF " + OME_ZARR_NGFF_VERSION + ", Zarr v" + ZARR_FORMAT_VERSION + ")"
                        : "OME-TIFF (single file)";
            }

            @Override
            public StitchingConfig.OutputFormat fromString(String label) {
                return label != null && label.startsWith("OME-Zarr")
                        ? StitchingConfig.OutputFormat.OME_ZARR
                        : StitchingConfig.OutputFormat.OME_TIFF;
            }
        });

        StitchingConfig.OutputFormat saved;
        try {
            saved = StitchingConfig.OutputFormat.valueOf(QPPreferences.getOutputFormatSaved());
        } catch (IllegalArgumentException | NullPointerException e) {
            saved = StitchingConfig.OutputFormat.OME_TIFF;
        }
        outputFormatBox.setValue(saved);
        outputFormatBox.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                QPPreferences.setOutputFormatSaved(n.name());
            }
        });

        Tooltip formatTooltip =
                new Tooltip("OME-TIFF: one pyramidal .ome.tif file with OME-XML metadata. Widely readable.\n\n"
                        + "OME-Zarr: a .ome.zarr DIRECTORY of chunks, written in parallel and suited to cloud\n"
                        + "storage. Written as NGFF " + OME_ZARR_NGFF_VERSION + " (multiscales + omero metadata)\n"
                        + "on Zarr format v" + ZARR_FORMAT_VERSION + ", which QuPath's bundled reader opens.\n"
                        + "Check what your other tools accept before choosing it: NGFF versions are still moving.");
        outputFormatLabel.setTooltip(formatTooltip);
        outputFormatBox.setTooltip(formatTooltip);

        addToGrid(pane, outputFormatLabel, outputFormatBox);
    }

    /**
     * Adds pixel size input components to the specified GridPane.
     *
     * <p>The field is uneditable by default so the value cannot be changed
     * by accident; when an input folder is selected the field is auto-filled
     * from the MMStack {@code FrameKey-0-0-0.PixelSizeUm} metadata (or left
     * as the saved preference when no MMStack metadata is present). Ticking
     * the "Manually edit pixel size" checkbox unlocks the field; unticking
     * it restores the auto-detected value.
     */
    private void addPixelSizeComponents(GridPane pane) {
        Tooltip pixelSizeTooltip = new Tooltip("Pixel size in microns for the tile images.\n"
                + "Auto-detected from MMStack metadata in the selected folder when available.\n"
                + "Tick 'Manually edit pixel size' to override.");
        pixelSizeLabel.setTooltip(pixelSizeTooltip);
        pixelSizeField.setTooltip(pixelSizeTooltip);
        pixelSizeField.setEditable(false);
        pixelSizeSourceLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #666;");

        // Default: field is locked. Checking the box unlocks it; unchecking
        // restores the auto-detected value (or the saved default if no MMStack
        // metadata was found in the current folder).
        pixelSizeOverrideCheckbox.setSelected(false);
        pixelSizeOverrideCheckbox.setOnAction(e -> {
            boolean manual = pixelSizeOverrideCheckbox.isSelected();
            pixelSizeField.setEditable(manual);
            if (manual) {
                pixelSizeSourceLabel.setText("(manual override)");
                pixelSizeField.requestFocus();
            } else {
                // Re-auto-fill from the currently selected folder so we don't
                // silently keep the user's last typed value while pretending
                // the field is locked.
                autoFillPixelSizeFromFolder();
            }
        });
        Tooltip overrideTooltip = new Tooltip("By default the pixel size is auto-detected from MMStack metadata "
                + "and cannot be edited. Tick this box to type a value manually.");
        pixelSizeOverrideCheckbox.setTooltip(overrideTooltip);

        // "Measure from tiles..." estimates the true pixel size from the
        // actual tile overlap (phase correlation of neighboring tiles), for
        // scopes whose metadata PixelSizeUm is wrong. The result is written into
        // the field as a manual override so the stitcher actually uses it.
        estimatePixelSizeButton.setTooltip(new Tooltip(
                "Measure the pixel size from the overlap between neighboring tiles in the selected folder.\n"
                        + "Use this when the metadata pixel size produces duplicated/misaligned tiles.\n"
                        + "The measured value is applied as a manual override."));
        estimatePixelSizeButton.setOnAction(e -> estimatePixelSizeFromFolder());

        addToGrid(pane, pixelSizeLabel, pixelSizeField);

        // Put the source label in column 2 next to the field.
        Integer pxRow = guiElementPositions.get(pixelSizeLabel);
        if (pxRow != null) {
            pane.add(pixelSizeSourceLabel, 2, pxRow);
        }

        // The override checkbox sits on its own row in column 1, with the
        // estimate button next to it in column 2.
        Integer chkRow = guiElementPositions.get(pixelSizeOverrideCheckbox);
        if (chkRow != null) {
            pane.add(pixelSizeOverrideCheckbox, 1, chkRow);
            pane.add(estimatePixelSizeButton, 2, chkRow);
        }
    }

    /**
     * Estimate the pixel size from tile overlap in the selected folder and, on
     * success, write it into the field as a manual override. Runs the
     * measurement off the FX thread so the dialog stays responsive.
     */
    private void estimatePixelSizeFromFolder() {
        String path = folderField.getText();
        if (path == null || path.trim().isEmpty()) {
            showAlertDialog("Select an input folder first.");
            return;
        }
        File folder = new File(path.trim());
        if (!folder.isDirectory()) {
            showAlertDialog("Input folder does not exist: " + path);
            return;
        }
        estimatePixelSizeButton.setDisable(true);
        pixelSizeSourceLabel.setText("(estimating from tile overlap...)");
        Thread worker = new Thread(
                () -> {
                    MicroManagerMetadataStrategy.PixelSizeEstimate est =
                            MicroManagerMetadataStrategy.estimatePixelSizeUm(folder);
                    Platform.runLater(() -> {
                        estimatePixelSizeButton.setDisable(false);
                        if (est.ok()) {
                            // Apply as a manual override so the stitcher uses it.
                            pixelSizeOverrideCheckbox.setSelected(true);
                            pixelSizeField.setEditable(true);
                            pixelSizeField.setText(String.valueOf(est.pixelSizeUm));
                            pixelSizeSourceLabel.setText(String.format(
                                    "(estimated %.4f um/px, confidence %.2f - applied as override)",
                                    est.pixelSizeUm, est.confidence));
                            logger.info("Pixel-size estimate applied: {}", est.message);
                            if (est.confidence < 0.5) {
                                showAlertDialog(est.message
                                        + "\n\nConfidence is low; verify the stitched result and adjust the "
                                        + "pixel size manually if tiles are mismatched.");
                            }
                        } else {
                            pixelSizeSourceLabel.setText("(estimate failed)");
                            showAlertDialog("Could not estimate the pixel size.\n\n" + est.message);
                        }
                    });
                },
                "pixel-size-estimate");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Read the MMStack metadata in the currently selected folder and update
     * the pixel size field accordingly. No-op when the override checkbox is
     * ticked (the user wants their value preserved). Safe to call from any
     * folder-state change.
     */
    private void autoFillPixelSizeFromFolder() {
        if (pixelSizeOverrideCheckbox.isSelected()) {
            return;
        }
        String path = folderField.getText();
        if (path == null || path.trim().isEmpty()) {
            pixelSizeSourceLabel.setText("");
            return;
        }
        File folder = new File(path.trim());
        if (!folder.isDirectory()) {
            pixelSizeSourceLabel.setText("");
            return;
        }
        Double mmPixelSize = MicroManagerMetadataStrategy.detectPixelSizeUm(folder);
        if (mmPixelSize != null && mmPixelSize > 0) {
            pixelSizeField.setText(String.valueOf(mmPixelSize));
            pixelSizeSourceLabel.setText("(from MicroManager metadata)");
            logger.info("Auto-filled pixel size {} um from MicroManager metadata in {}", mmPixelSize, folder);
        } else {
            pixelSizeSourceLabel.setText("(no MicroManager metadata - tick 'Manually edit' to set)");
        }
    }

    /**
     * Adds downsample input components to the specified GridPane.
     */
    private void addDownsampleComponents(GridPane pane) {
        Tooltip downsampleTooltip =
                new Tooltip("The amount by which the highest resolution plane will be initially downsampled.");
        downsampleLabel.setTooltip(downsampleTooltip);
        downsampleField.setTooltip(downsampleTooltip);

        addToGrid(pane, downsampleLabel, downsampleField);
    }

    /**
     * Adds matching string input components to the specified GridPane.
     */
    private void addMatchStringComponents(GridPane pane) {
        Tooltip matchStringTooltip = new Tooltip("Stitch each sub-folder whose name contains this text.\n"
                + "Leave empty to stitch the selected folder itself, and only that folder.");
        matchStringLabel.setTooltip(matchStringTooltip);
        matchStringField.setTooltip(matchStringTooltip);

        addToGrid(pane, matchStringLabel, matchStringField);
    }

    /**
     * Updates the visibility of certain GUI components based on the current selection
     * in the stitching method combo box.
     */
    private void updateComponentsBasedOnSelection(GridPane pane) {
        String selectedValue = stitchingGridBox.getValue();
        // Only Vectra tiles carry pixel positions. TileConfiguration.txt coordinates are micrometers
        // divided by this pixel size, so hiding the field there made the stitch use an invisible,
        // stale value.
        boolean hidePixelSize = "Vectra tiles with metadata".equals(selectedValue);

        pixelSizeLabel.setVisible(!hidePixelSize);
        pixelSizeField.setVisible(!hidePixelSize);
        pixelSizeOverrideCheckbox.setVisible(!hidePixelSize);
        pixelSizeSourceLabel.setVisible(!hidePixelSize);
        estimatePixelSizeButton.setVisible(!hidePixelSize);

        // Show fudge factor components only for Vectra
        boolean showFudgeFactor = "Vectra tiles with metadata".equals(selectedValue);
        useFudgeFactorCheckbox.setVisible(showFudgeFactor);
        if (showFudgeFactor && useFudgeFactorCheckbox.isSelected()) {
            setFudgeFactorVisibility(true);
        } else {
            setFudgeFactorVisibility(false);
        }

        // Stage-axis inversion only means anything where the positions ARE stage coordinates.
        boolean stagePositions = selectedValue != null
                && (selectedValue.startsWith("MicroManager")
                        || "Coordinates in TileConfiguration.txt file".equals(selectedValue));
        invertLabel.setVisible(stagePositions);
        invertBox.setVisible(stagePositions);

        refreshMergeVisibility();
        adjustLayout(pane);
    }

    /**
     * Adjusts the layout of the GridPane based on the current positions.
     */
    private void adjustLayout(GridPane pane) {
        for (Map.Entry<Node, Integer> entry : guiElementPositions.entrySet()) {
            Node node = entry.getKey();
            Integer newRow = entry.getValue();

            if (pane.getChildren().contains(node)) {
                GridPane.setRowIndex(node, newRow);
            }
        }
    }

    /**
     * Shows a warning alert dialog with the specified message.
     */
    /**
     * Report a finished stitch. The text goes in a read-only text area, not the alert's label: a
     * label wraps only at spaces, so a long output path collapsed to "..." and could not be copied.
     */
    private static void showResultDialog(boolean success, String message) {
        Alert alert = new Alert(success ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        // Distinct from the stitch dialog's title: the Dialog Manager extension remembers window
        // size per title, so sharing one made the stitch dialog reopen at this alert's small size.
        alert.setTitle("Tiles to Pyramid - Result");
        alert.setHeaderText(success ? "Stitching complete" : "Stitching did not fully succeed");
        TextArea text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefColumnCount(70);
        // +2: one spare row, one for the horizontal scroll bar a long path brings up.
        text.setPrefRowCount((int) Math.min(12, message.lines().count() + 2));
        alert.getDialogPane().setContent(text);
        alert.setResizable(true);
        DialogOwner.own(alert);
        alert.showAndWait();
    }

    public static void showAlertDialog(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning!");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initModality(Modality.APPLICATION_MODAL);
        DialogOwner.own(alert);
        alert.showAndWait();
    }

    private void addFudgeFactorComponents(GridPane pane) {
        // Checkbox with tooltip
        Tooltip fudgeTooltip =
                new Tooltip("Fudge factor to adjust for empty black lines between tiles (slightly less than 1.0).\n"
                        + "See forum discussion for details.");
        useFudgeFactorCheckbox.setTooltip(fudgeTooltip);

        // Set up the forum link
        vectraForumLink.setOnAction(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://forum.image.sc/t/vectra-polaris-tile-stitching/35739/5"));
            } catch (Exception ex) {
                logger.error("Error opening forum link", ex);
            }
        });

        // Add checkbox spanning two columns
        Integer checkboxRow = guiElementPositions.get(useFudgeFactorCheckbox);
        if (checkboxRow != null) {
            pane.add(useFudgeFactorCheckbox, 0, checkboxRow, 2, 1);
        }

        // Add fudge factor fields
        Tooltip xFudgeTooltip =
                new Tooltip("X-axis scale factor to adjust for gaps between tiles (typically slightly less than 1.0).");
        xFudgeLabel.setTooltip(xFudgeTooltip);
        xFudgeField.setTooltip(xFudgeTooltip);

        Tooltip yFudgeTooltip =
                new Tooltip("Y-axis scale factor to adjust for gaps between tiles (typically slightly less than 1.0).");
        yFudgeLabel.setTooltip(yFudgeTooltip);
        yFudgeField.setTooltip(yFudgeTooltip);

        addToGrid(pane, xFudgeLabel, xFudgeField);
        addToGrid(pane, yFudgeLabel, yFudgeField);

        // Add forum link
        Integer linkRow = guiElementPositions.get(vectraForumLink);
        if (linkRow != null) {
            pane.add(vectraForumLink, 0, linkRow, 2, 1);
        }

        // Initially hide these components
        setFudgeFactorVisibility(false);

        // Update visibility when checkbox changes
        useFudgeFactorCheckbox.setOnAction(e -> {
            setFudgeFactorVisibility(useFudgeFactorCheckbox.isSelected());
        });
    }

    private void setFudgeFactorVisibility(boolean visible) {
        xFudgeLabel.setVisible(visible);
        xFudgeField.setVisible(visible);
        yFudgeLabel.setVisible(visible);
        yFudgeField.setVisible(visible);
        vectraForumLink.setVisible(visible);
    }
}
