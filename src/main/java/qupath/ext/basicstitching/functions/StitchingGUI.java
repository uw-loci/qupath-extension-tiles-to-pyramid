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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
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
import qupath.ext.basicstitching.stitching.AcquisitionRecord;
import qupath.ext.basicstitching.stitching.FileScanner;
import qupath.ext.basicstitching.stitching.FlatfieldProfile;
import qupath.ext.basicstitching.stitching.FolderDiagnosis;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.stitching.MicroManagerOutputMetadata;
import qupath.ext.basicstitching.stitching.MicroManagerPreprocessor;
import qupath.ext.basicstitching.stitching.PlaneProjection;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.stitching.TileDirectories;
import qupath.ext.basicstitching.utilities.QPPreferences;
import qupath.ext.basicstitching.utilities.RegistrationPreferences;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.basicstitching.workflow.StitchPreflight;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;

/**
 * GUI class for the Basic Stitching Extension.
 * Provides a dialog interface for configuring stitching parameters and executing stitching operations.
 *
 * TODO: Estimate size of stitched image to predict necessary memory
 * TODO: Warn user if size exceeds QuPath's allowed limits.
 */
public class StitchingGUI {

    private static final Logger logger = LoggerFactory.getLogger(StitchingGUI.class);

    // One dialog-launched stitch at a time: it runs in the background, so the menu stays live.
    private static final AtomicBoolean STITCH_RUNNING = new AtomicBoolean(false);
    /** The running stitch's progress window; null when none runs. FX thread only. */
    private static StitchProgressWindow progressWindow;
    /** The open options dialog, so choosing the menu item again brings it forward. FX thread only. */
    private static Dialog<ButtonType> openDialog;

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
    // Shown only for a MicroManager acquisition with more than one time point per position.
    private final Label timeProjectionLabel = new Label("Combine time points:");
    private final ComboBox<PlaneProjection> timeProjectionBox = new ComboBox<>();
    private final HBox timeProjectionRow = new HBox(8, timeProjectionLabel, timeProjectionBox);
    // Shown only for a MicroManager acquisition with more than one z-slice per position.
    private final Label zProjectionLabel = new Label("Combine z-slices:");
    private final ComboBox<PlaneProjection> zProjectionBox = new ComboBox<>();
    private final HBox zProjectionRow = new HBox(8, zProjectionLabel, zProjectionBox);
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
    private static final String MM_METHOD = "MicroManager metadata (MMStack or TIFF series)";
    /** What the dropdown shows for {@link #MM_METHOD}; the identifier itself stays as QPSC and saved preferences know it. */
    private static final String MM_LABEL = "MicroManager (MMStack, TIFF series or NDTiff)";

    private static final String TILECONFIG_LABEL = "TileConfiguration.txt file";
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
    private final Label matchStringLabel = new Label("Sub-folders to stitch (* = all):");
    private final Label zSpacingLabel = new Label("Z spacing (um):");
    private final TextField zSpacingField = new TextField();
    /** Folder and sub-folder text the z spacing field was last filled for. */
    private String zSpacingFilledFor;

    private final Hyperlink githubLink = new Hyperlink("GitHub ReadMe");
    private final Label xFudgeLabel = new Label("X fudge factor:");
    private final Label yFudgeLabel = new Label("Y fudge factor:");

    // Map to hold the positions of each GUI element
    private final CheckBox flatfieldCheckbox = new CheckBox("Correct uneven brightness with a flat-field profile");
    private final TextField flatfieldField = new TextField();
    private final HBox flatfieldRow = new HBox(8);
    private final Map<Node, Integer> guiElementPositions = new HashMap<>();

    /**
     * Creates and displays the main GUI dialog for stitching configuration.
     * Handles user input validation, preference saving, and initiates stitching process.
     */
    public static void createGUI() {
        if (STITCH_RUNNING.get()) {
            if (progressWindow != null) {
                // The progress window is the way back to a stitch whose window was closed.
                progressWindow.show();
            } else {
                showAlertDialog("A stitch is already running. Wait for it to finish before starting another.");
            }
            return;
        }
        if (openDialog != null && openDialog.isShowing()) {
            Window w = openDialog.getDialogPane().getScene().getWindow();
            if (w instanceof Stage stage) {
                stage.setIconified(false);
                stage.toFront();
            }
            return;
        }
        new StitchingGUI().show();
    }

    private void show() {
        Dialog<ButtonType> dlg = new Dialog<>();
        // Not modal: the user can look at images or the project while choosing options.
        dlg.initModality(Modality.NONE);
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

        dlg.setOnHidden(e -> {
            openDialog = null;
            if (dlg.getResult() == stitchType) {
                processDialogResult();
            }
        });
        openDialog = dlg;
        dlg.show();
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
            // A MicroManager sidecar records the z-step; a TileConfiguration.txt stack takes it from
            // the dialog's field. 0 leaves the output's z spacing unset rather than claiming a value
            // nobody measured (the stitch still fills it from an acquisition record it finds).
            double zSpacingMicrons = 0.0;
            if (stitchingType != null && stitchingType.startsWith("MicroManager")) {
                Double step = MicroManagerMetadataStrategy.detectZStepUm(new File(folderPath.trim()));
                zSpacingMicrons = step != null ? step : 0.0;
            } else if (zSpacingField.isVisible()) {
                zSpacingMicrons = parseZSpacing(zSpacingField.getText());
            }

            QPPreferences.setFolderLocationSaved(folderPath);
            QPPreferences.setStitchingMethodSaved(stitchingType);
            QPPreferences.setCompressionTypeSaved(compressionType);
            QPPreferences.setDownsampleSaved(downsampleField.getText());
            QPPreferences.setSearchStringSaved(matchingString);
            QPPreferences.setPixelSizeOverrideSaved(pixelSizeOverrideCheckbox.isSelected());
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
            PlaneProjection timeProjection =
                    timeProjectionRow.isVisible() ? orNone(timeProjectionBox.getValue()) : PlaneProjection.NONE;
            PlaneProjection zProjection =
                    zProjectionRow.isVisible() ? orNone(zProjectionBox.getValue()) : PlaneProjection.NONE;
            if (timeProjectionRow.isVisible()) {
                QPPreferences.setTimeProjectionSaved(timeProjection);
            }
            if (zProjectionRow.isVisible()) {
                QPPreferences.setZProjectionSaved(zProjection);
            }
            boolean applyFlatfield = flatfieldRow.isVisible() && flatfieldCheckbox.isSelected();
            if (flatfieldRow.isVisible()) {
                QPPreferences.setApplyFlatfieldSaved(flatfieldCheckbox.isSelected());
                QPPreferences.setFlatfieldProfileSaved(flatfieldField.getText().trim());
            }
            FlatfieldProfile flatfield = applyFlatfield
                    ? FlatfieldProfile.load(Paths.get(flatfieldField.getText().trim()))
                    : null;
            MicroManagerPreprocessor.Options preprocess =
                    timeProjection.reduces() || zProjection.reduces() || applyFlatfield
                            ? new MicroManagerPreprocessor.Options(timeProjection, flatfield, zProjection)
                            : null;
            boolean merge = mergeOffered && mergeChannelsCheckbox.isSelected();
            runInBackground(config, merge, merge ? countChannelFolders() : 0, preprocess, invertX, invertY);

        } catch (Exception e) {
            logger.error("Error processing dialog result", e);
            showAlertDialog("Error processing input: " + e.getMessage());
        }
    }

    /**
     * Run the stitch (and the optional channel merge) off the FX thread so QuPath stays responsive,
     * reporting the outcome in a dialog when it finishes.
     *
     * @param expectedChannels channel stitches the dialog said it would merge; 0 when not merging
     * @param preprocess reduce each MicroManager position (average time points, then max-project
     *     z-slices) into a copy first, and stitch the copy; null for neither
     * @param invertX negate stage X before placing MicroManager tiles
     * @param invertY negate stage Y before placing MicroManager tiles
     */
    private static void runInBackground(
            StitchingConfig requested,
            boolean mergeChannels,
            int expectedChannels,
            MicroManagerPreprocessor.Options preprocess,
            boolean invertX,
            boolean invertY) {
        if (!STITCH_RUNNING.compareAndSet(false, true)) {
            showAlertDialog("A stitch is already running. Wait for it to finish before starting another.");
            return;
        }
        StitchProgressWindow window = new StitchProgressWindow(new File(requested.folderPath.trim()).getName());
        progressWindow = window;
        window.show();
        int stepCount = 2 + (preprocess != null ? 1 : 0) + (mergeChannels ? 1 : 0);
        int first = preprocess != null ? 2 : 1;
        String preprocessStep = "Step 1 of " + stepCount + ": Preprocessing (" + preprocess(preprocess) + ")";
        String checkStep = "Step " + first + " of " + stepCount + ": Checking the images to write";
        String stitchStep = "Step " + (first + 1) + " of " + stepCount + ": Stitching";
        String mergeStep = "Step " + stepCount + " of " + stepCount + ": Merging channels";
        Thread worker = new Thread(
                () -> {
                    boolean ok = false;
                    boolean backToSettings = false;
                    boolean[] nothing = {false};
                    StringBuilder message = new StringBuilder();
                    StitchingConfig config = requested;
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
                    // Labeled so "Back to settings" at the pre-run check can leave it, through the
                    // finally that restores the axis flags.
                    stitch:
                    try {
                        String reduced = null;
                        if (preprocess != null) {
                            window.update(preprocessStep, -1, "", "");
                            config = preprocessedCopy(requested, preprocess, window, preprocessStep);
                            reduced = "Preprocessed (" + preprocess.describe() + ") into " + config.folderPath;
                        }
                        window.update(checkStep, -1, "reading every tile's header", "");
                        StitchPreflight.Report check = StitchPreflight.run(config);
                        if ((check.rows().size() >= 2 || check.hasProblems()) && !confirmPreflight(check, config)) {
                            backToSettings = true;
                            break stitch;
                        }
                        window.update(stitchStep, -1, "", "");
                        config.setProgressListener(p -> window.update(stitchStep, p));
                        StitchingWorkflow.StitchingResult result = StitchingWorkflow.runDetailed(config);
                        if (reduced != null) {
                            message.append(reduced).append("\n\n");
                        }
                        List<String> outputs = result.outputs();
                        // Tiles the strategy left out or read only in part change what was
                        // stitched; the log alone is where nobody looks.
                        for (String w : result.warnings()) {
                            message.append("WARNING: ").append(w).append("\n\n");
                        }
                        if (outputs.isEmpty() && result.failedSubdirs().isEmpty()) {
                            nothing[0] = true;
                            message.append(explainNoTiles(config));
                        } else {
                            ok = result.warnings().isEmpty();
                            // Geometry that cannot be right does not fail the stitch, so the
                            // result is demoted to a warning and the reason put at the top,
                            // where it is read before the list of files. One per image, named.
                            for (String geometry : config.getGeometryWarnings()) {
                                ok = false;
                                message.append("WARNING: ").append(geometry).append("\n\n");
                            }
                            // What registration did is the thing people want to know and the thing
                            // they should not have to open the log to read.
                            String reg = describeRegistration(config);
                            if (reg != null) {
                                message.append(reg).append("\n\n");
                            }
                            if (!outputs.isEmpty()) {
                                message.append("Output files:");
                                outputs.forEach(o -> message.append("\n  ").append(o));
                            }
                            if (!result.failedSubdirs().isEmpty()) {
                                ok = false;
                                int total =
                                        outputs.size() + result.failedSubdirs().size();
                                message.append(outputs.isEmpty() ? "" : "\n\n")
                                        .append("Failed: ")
                                        .append(result.failedSubdirs().size())
                                        .append(" of ")
                                        .append(total)
                                        .append(total == 1 ? " image" : " images")
                                        .append(outputs.isEmpty() ? "" : " (the others were written)")
                                        .append(":");
                                result.failureReasons()
                                        .forEach(r -> message.append("\n  ").append(r));
                            }
                            String shortfall = mergeShortfall(mergeChannels, expectedChannels, outputs.size());
                            if (shortfall != null) {
                                ok = false;
                                logger.warn(shortfall);
                                message.append("\n\nWARNING: ").append(shortfall);
                            }
                            if (mergeChannels && outputs.size() >= 2) {
                                // A size or pixel-type mismatch throws from ChannelMergeImageServer; catch
                                // it here so the stitched outputs listed above still reach the user.
                                String merged = null;
                                String reason = "See the log for details.";
                                try {
                                    window.update(mergeStep, 0, "", "");
                                    merged = mergeChannelOutputs(
                                            outputs, result.acquisitionOrder(), config, window, mergeStep);
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
                    boolean none = nothing[0];
                    boolean back = backToSettings;
                    String outputDir = config.outputPath;
                    Platform.runLater(() -> {
                        if (progressWindow == window) {
                            progressWindow = null;
                        }
                        window.close();
                        if (back) {
                            // The settings come back holding what was just tried, from the
                            // preferences the run saved.
                            createGUI();
                        } else {
                            showResultDialog(success, none, message.toString(), outputDir);
                        }
                    });
                },
                "tiles-to-pyramid-stitch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Show the pre-run check and wait for the answer; call from the stitch thread, never the FX
     * thread.
     *
     * @return true to stitch, false to go back to the settings
     */
    private static boolean confirmPreflight(StitchPreflight.Report report, StitchingConfig config) {
        java.util.concurrent.CompletableFuture<Boolean> answer = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                showPreflightDialog(report, config, answer);
            } catch (RuntimeException e) {
                logger.error("Could not show the pre-run check", e);
                answer.complete(true);
            }
        });
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return true;
        }
    }

    /** The pre-run check window: one row per image, flags, and what the tile search left out. */
    private static void showPreflightDialog(
            StitchPreflight.Report report,
            StitchingConfig config,
            java.util.concurrent.CompletableFuture<Boolean> answer) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initModality(Modality.NONE);
        DialogOwner.own(dlg);
        dlg.setTitle("Tiles to Pyramid - Check before stitching");
        int n = report.rows().size();
        long flagged = report.rows().stream().filter(r -> !r.flags().isEmpty()).count();
        dlg.setHeaderText(n + (n == 1 ? " image" : " images") + " will be written, every one with pixel size "
                + config.pixelSizeInMicrons + " um/px and " + config.compressionType + " compression."
                + (flagged > 0
                        ? "\n" + flagged
                                + (flagged == 1
                                        ? " image differs from the rest or looks wrong: see Check."
                                        : " images differ from the rest or look wrong: see Check.")
                        : report.warnings().isEmpty() ? "\nNothing stands out." : ""));

        javafx.scene.control.TableView<StitchPreflight.Row> table = new javafx.scene.control.TableView<>();
        table.getItems().setAll(report.rows());
        table.getColumns().add(column("Image", 150, StitchPreflight.Row::image));
        table.getColumns().add(column("Tiles", 50, r -> String.valueOf(r.tiles())));
        table.getColumns().add(column("Tile size", 85, StitchPreflight.Row::tileSize));
        table.getColumns().add(column("Pixels", 125, StitchPreflight.Row::pixels));
        table.getColumns().add(column("Planes", 55, r -> String.valueOf(r.planes())));
        table.getColumns().add(column("Image size (px)", 125, StitchPreflight.Row::size));
        // The notes are the point of the window, so they wrap onto more lines instead of "...".
        var check = column("Check", 340, r -> r.flags().isEmpty() ? "" : String.join("\n", r.flags()));
        check.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.text.Text text = new javafx.scene.text.Text();

            {
                text.wrappingWidthProperty().bind(col.widthProperty().subtract(12));
                text.setStyle("-fx-font-weight: bold;");
                text.getStyleClass().add("text");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null || item.isEmpty()) {
                    setGraphic(null);
                } else {
                    text.setText(item);
                    text.fillProperty().bind(textFillProperty());
                    setGraphic(text);
                }
            }
        });
        table.getColumns().add(check);
        table.setColumnResizePolicy(javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        int noteLines = report.rows().stream()
                .mapToInt(r -> Math.max(1, r.flags().size()))
                .sum();
        table.setPrefHeight(Math.min(460, 34 + 22 * noteLines + 6 * n));
        table.setPrefWidth(930);

        VBox content = new VBox(8, table);
        if (!report.warnings().isEmpty()) {
            TextArea warnings = new TextArea(String.join("\n", report.warnings()));
            warnings.setEditable(false);
            warnings.setWrapText(true);
            warnings.setPrefRowCount(Math.min(6, report.warnings().size() + 1));
            content.getChildren().addAll(new Label("The tile search left out or could not read:"), warnings);
        }
        dlg.getDialogPane().setContent(content);
        dlg.setResizable(true);

        ButtonType stitch = new ButtonType("Stitch", ButtonBar.ButtonData.OK_DONE);
        ButtonType back = new ButtonType("Back to settings", ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().setAll(back, stitch);
        // A long batch should start only on an explicit click, not on Enter.
        ((Button) dlg.getDialogPane().lookupButton(stitch)).setDefaultButton(false);
        dlg.setOnHidden(e -> answer.complete(dlg.getResult() == stitch));
        dlg.show();
    }

    private static javafx.scene.control.TableColumn<StitchPreflight.Row, String> column(
            String title, double width, java.util.function.Function<StitchPreflight.Row, String> value) {
        javafx.scene.control.TableColumn<StitchPreflight.Row, String> c = new javafx.scene.control.TableColumn<>(title);
        c.setPrefWidth(width);
        if (!"Check".equals(title)) {
            // Fixed: the space left over goes to the notes.
            c.setMinWidth(width);
            c.setMaxWidth(width * 1.5);
        }
        c.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cd.getValue())));
        return c;
    }

    /** "mean over time points, then ..." without the rounding note, for the step line. */
    private static String preprocess(MicroManagerPreprocessor.Options options) {
        if (options == null) {
            return "";
        }
        String d = options.describe();
        int cut = d.indexOf(';');
        return cut > 0 ? d.substring(0, cut) : d;
    }

    private static PlaneProjection orNone(PlaneProjection p) {
        return p == null ? PlaneProjection.NONE : p;
    }

    /**
     * What to tell the user when a merge was asked for and the stitch did not produce one output per
     * channel.
     *
     * @param mergeChannels whether the merge was asked for
     * @param expectedChannels channels the dialog offered to merge
     * @param outputs stitched outputs
     * @return the message, or null when there is nothing to say
     */
    static String mergeShortfall(boolean mergeChannels, int expectedChannels, int outputs) {
        if (!mergeChannels || outputs >= expectedChannels) {
            return null;
        }
        if (outputs < 2) {
            return "Channels not merged: the acquisition has " + expectedChannels
                    + " channels, but the stitch produced " + outputs + " image" + (outputs == 1 ? "" : "s")
                    + ", so there was nothing to merge.";
        }
        return "Only " + outputs + " of the " + expectedChannels + " channels were stitched, so the merged image has "
                + outputs + " channels.";
    }

    /**
     * Reduce every position (average time points, then max-project z-slices, as asked) into a copy
     * beside the folder, and return the config that stitches that copy, carrying a record of what
     * was done for the stitch-info file.
     */
    private static StitchingConfig preprocessedCopy(
            StitchingConfig config, MicroManagerPreprocessor.Options options, StitchProgressWindow window, String step)
            throws IOException {
        Path source = Paths.get(config.folderPath.trim());
        long start = System.nanoTime();
        MicroManagerPreprocessor.Result r = MicroManagerPreprocessor.run(
                source, MicroManagerPreprocessor.defaultOutput(source, options), options, (done, total) -> {
                    double f = total > 0 ? (double) done / total : -1;
                    window.update(
                            step,
                            f,
                            done + " of " + total + " positions",
                            StitchProgressWindow.timeText((System.nanoTime() - start) / 1e9, f));
                });
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("method", options.describe());
        entries.put("acquisition folder", source.toString());
        entries.put("preprocessed copy", r.output().toString());
        entries.put("positions", String.valueOf(r.positions()));
        entries.put("time points per position", r.frameSummary());
        entries.put("z-slices per position", r.sliceSummary());
        StitchingConfig reduced = config.withFolderPath(r.output().toString());
        reduced.setPreprocessingRecord(new StitchInfoFile.Section("preprocessing", entries, r.notes()));
        return reduced;
    }

    /**
     * Combine the per-subdirectory stitches into one multichannel image named after the selected
     * folder, each channel named by its output's file stem.
     *
     * @param acquisitionOrder the outputs are in the acquisition's channel order (Micro-Manager's
     *     {@code Summary.ChNames}) and are merged in that order; otherwise they are merged in file
     *     name order, since channel folders record no acquisition order
     */
    private static String mergeChannelOutputs(
            List<String> outputs,
            boolean acquisitionOrder,
            StitchingConfig config,
            StitchProgressWindow window,
            String step) {
        // window: shows the merge's progress; null for none
        List<String> ordered = new ArrayList<>(outputs);
        if (!acquisitionOrder) {
            Collections.sort(ordered);
        }
        List<String> names =
                ordered.stream().map(p -> channelNameOf(new File(p).getName())).toList();
        String stem = new File(config.folderPath).getName() + "_merged";
        logger.info("Merging {} channel stitches {} into {}", ordered.size(), names, stem);
        long start = System.nanoTime();
        String detail = ordered.size() + " channels into " + stem;
        String merged = ChannelMerger.merge(
                ordered,
                names,
                null,
                config.outputPath,
                stem,
                config.compressionType,
                config.outputFormat,
                window == null
                        ? null
                        : f -> window.update(
                                step, f, detail, StitchProgressWindow.timeText((System.nanoTime() - start) / 1e9, f)));
        if (merged != null) {
            writeMergedAcquisitionMetadata(Paths.get(merged), names, config);
            tidyChannelStitches(ordered, config, stem);
        }
        return merged;
    }

    /**
     * For a MicroManager merge, the merged image's {@code .mm-metadata.json} (its channels in merged
     * order) and a pointer to it in its stitch record.
     *
     * @param channelNames the merged image's channel names, which are the channels' output names
     */
    private static void writeMergedAcquisitionMetadata(Path merged, List<String> channelNames, StitchingConfig config) {
        if (config.stitchingType == null || !config.stitchingType.startsWith("MicroManager")) {
            return;
        }
        List<String> outputNames = MicroManagerMetadataStrategy.channelOutputNames(new File(config.folderPath));
        List<Integer> channels = new ArrayList<>();
        for (String name : channelNames) {
            int c = outputNames.indexOf(name);
            if (c < 0) {
                logger.warn(
                        "Merged channel '{}' is not one of {}; no Micro-Manager metadata written", name, outputNames);
                return;
            }
            channels.add(c);
        }
        Path written = MicroManagerOutputMetadata.write(merged, Paths.get(config.folderPath), channels);
        if (written != null) {
            StitchInfoFile.append(merged, List.of(StitchingWorkflow.acquisitionMetadataSection(written)));
        }
    }

    /** A trailing "_2x_downsample", with an optional "_3" uniqueness counter after it. */
    private static final java.util.regex.Pattern OUTPUT_SUFFIX =
            java.util.regex.Pattern.compile("_\\d+x_downsample(_\\d+)?$|_(\\d+)$");

    /**
     * The channel's own name, recovered from the file the stitch wrote.
     *
     * <p>Outputs are named after the channel, but the writer appends "_2x_downsample" when the
     * downsample is not 1 and "_2" when a file of that name already exists. Neither belongs in a
     * channel name: they would show in QuPath's channel list, and they stop a channel called
     * "385" being recognized as a wavelength when its colour is chosen.
     *
     * @param fileName the output file's name, with extension
     * @return the channel name
     */
    private static String channelNameOf(String fileName) {
        String stem = GeneralTools.stripExtension(fileName);
        String trimmed = OUTPUT_SUFFIX.matcher(stem).replaceFirst("");
        // Never strip everything: a channel genuinely called "2" keeps its name.
        return trimmed.isBlank() ? stem : trimmed;
    }

    /**
     * Move the per-channel stitches into a sub-folder once they have been merged.
     *
     * <p>Output lands beside the tiles, which is right for the image you asked for and wrong for
     * the ones on the way to it: a four-channel merge otherwise leaves ten files -- five images and
     * five records -- shuffled in among the acquisition's own tiles, and the one you actually want
     * is not distinguishable by eye. They are moved rather than deleted, because a per-channel
     * stitch is a legitimate thing to want; they just should not be lying in the tile folder.
     *
     * <p>Best-effort by design. A file that cannot be moved is left exactly where it is and the
     * merge still counts as a success -- tidying must never turn a finished stitch into a failure.
     */
    private static void tidyChannelStitches(List<String> channelOutputs, StitchingConfig config, String mergedStem) {
        // Strip the "_merged" SUFFIX only: replace() would also eat it out of the middle of a
        // folder genuinely called something like "run_merged_v2".
        String base = mergedStem.endsWith("_merged")
                ? mergedStem.substring(0, mergedStem.length() - "_merged".length())
                : mergedStem;
        Path dir = Paths.get(config.outputPath).resolve(base + "_channels");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.warn("Could not create {}; leaving the per-channel stitches in place: {}", dir, e.getMessage());
            return;
        }
        int moved = 0;
        for (String out : channelOutputs) {
            Path from = Paths.get(out);
            Path to = dir.resolve(from.getFileName());
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                // The record has to travel with its image or it describes a file that is not there.
                StitchInfoFile.moveWith(from, to);
                MicroManagerOutputMetadata.moveWith(from, to);
                moved++;
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not move {} into {}: {}", from.getFileName(), dir.getFileName(), e.getMessage());
            }
        }
        if (moved > 0) {
            logger.info("Moved {} per-channel stitch(es) into {}", moved, dir.getFileName());
        }
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
                        + "MicroManager channels keep the acquisition's channel order; channel\n"
                        + "folders are merged in alphabetical order.\n"
                        + "The per-channel images are kept, in a <folder>_channels sub-folder.\n"
                        + "Not offered for RGB tiles."));
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

    /**
     * Adds the z spacing field for the TileConfiguration.txt method, shown when the tile folders hold
     * z-slices ({@code z{nn}/} or {@code t{nn}/z{nn}/}): a TileConfiguration.txt has no field for it.
     * It is filled from the acquisition's {@code acquisition_metadata.json} when there is one; left
     * empty, the spacing is unknown and the output records none.
     */
    private void addZSpacingComponents(GridPane pane) {
        zSpacingField.setPromptText("unknown");
        Tooltip tip = new Tooltip("Distance between z-slices, in microns, written to the output image.\n"
                + "A TileConfiguration.txt does not record it. It is filled in from the\n"
                + "acquisition_metadata.json a QP Scope acquisition writes, when there is one.\n"
                + "Left empty, the spacing is unknown: it is taken from that file if the\n"
                + "stitch finds one, and otherwise the image records no z spacing.");
        zSpacingLabel.setTooltip(tip);
        zSpacingField.setTooltip(tip);
        addToGrid(pane, zSpacingLabel, zSpacingField);
        zSpacingLabel.setVisible(false);
        zSpacingField.setVisible(false);
        folderField.textProperty().addListener((obs, o, n) -> refreshZSpacing());
        matchStringField.textProperty().addListener((obs, o, n) -> refreshZSpacing());
        stitchingGridBox.valueProperty().addListener((obs, o, n) -> refreshZSpacing());
    }

    private void refreshZSpacing() {
        String method = stitchingGridBox.getValue();
        String path = folderField.getText();
        int slices = "Coordinates in TileConfiguration.txt file".equals(method) && path != null && !path.isBlank()
                ? TileConfigurationTxtStrategy.countZSlices(Paths.get(path.trim()), matchStringField.getText())
                : 0;
        boolean show = slices > 1;
        zSpacingLabel.setVisible(show);
        zSpacingField.setVisible(show);
        if (!show) {
            return;
        }
        zSpacingLabel.setText("Z spacing (um), " + slices + " z-slices:");
        String key = path.trim() + "|" + matchStringField.getText();
        if (!key.equals(zSpacingFilledFor)) {
            zSpacingFilledFor = key;
            Double recorded = AcquisitionRecord.zStepUm(Paths.get(path.trim()));
            zSpacingField.setText(recorded != null ? String.valueOf(recorded) : "");
        }
    }

    /** The z spacing typed in the dialog: 0 (unknown) when empty, not a number, or not positive. */
    public static double parseZSpacing(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        try {
            double v = Double.parseDouble(text.trim());
            return v > 0 && Double.isFinite(v) ? v : 0.0;
        } catch (NumberFormatException e) {
            logger.warn("Z spacing '{}' is not a number; the spacing is left unknown", text);
            return 0.0;
        }
    }

    /**
     * Adds the preprocessing options for a MicroManager acquisition with several time points or
     * z-slices per position (e.g. SHG collected as a short time series, or a z-stack), in either
     * MicroManager layout. Time points are combined first (mean, maximum or minimum, or each kept),
     * then z-slices; the result is written beside the folder (e.g. {@code <folder>_averaged},
     * {@code _mip} or {@code _averaged_mip}) in the same MicroManager layout, and that copy is what
     * gets stitched.
     */
    private void addPreprocessComponents(GridPane pane) {
        timeProjectionBox.getItems().setAll(PlaneProjection.values());
        timeProjectionBox.setValue(QPPreferences.getTimeProjectionSaved());
        Tooltip timeTip = new Tooltip("Each position holds several time points. Mean, Max Intensity or Min\n"
                + "Intensity combines every channel and z-slice over them, per pixel\n"
                + "(rounded to the original bit depth), into a copy of the acquisition\n"
                + "beside the original, in the same MicroManager layout. That copy is\n"
                + "stitched.\n"
                + "None keeps them: each time point is stitched as a time point of the\n"
                + "output image (and z-slices, if combined, are combined per time point).");
        timeProjectionLabel.setTooltip(timeTip);
        timeProjectionBox.setTooltip(timeTip);
        zProjectionBox.getItems().setAll(PlaneProjection.values());
        zProjectionBox.setValue(QPPreferences.getZProjectionSaved());
        Tooltip zTip = new Tooltip("Each position holds a z-stack. Max Intensity keeps the brightest\n"
                + "value at every pixel over the z-slices (standard for fluorescence and\n"
                + "SHG), Min Intensity the darkest, Mean their average. This runs after\n"
                + "the time points are combined and after any flat-field correction, and\n"
                + "is written to the copy that is stitched.\n"
                + "None keeps them: each z-slice is stitched as a z-slice of the output image.");
        zProjectionLabel.setTooltip(zTip);
        zProjectionBox.setTooltip(zTip);
        for (HBox row : List.of(timeProjectionRow, zProjectionRow)) {
            row.setAlignment(Pos.CENTER_LEFT);
        }
        flatfieldCheckbox.setSelected(QPPreferences.getApplyFlatfieldSaved());
        flatfieldCheckbox.setTooltip(new Tooltip("For a detector with no background image, such as a PMT. Each plane\n"
                + "is corrected with a profile fitted from tiles in QP Scope, before any\n"
                + "z projection, and written to the copy that is stitched. A profile fits\n"
                + "one detector, objective, zoom and channel. Corrected corners carry more\n"
                + "noise: this evens out brightness, it does not improve signal to noise."));
        flatfieldField.setText(QPPreferences.getFlatfieldProfileSaved());
        flatfieldField.setPromptText("profile folder");
        flatfieldField
                .disableProperty()
                .bind(flatfieldCheckbox.selectedProperty().not());
        HBox.setHgrow(flatfieldField, Priority.ALWAYS);
        Button flatfieldBrowse = new Button("Browse...");
        flatfieldBrowse
                .disableProperty()
                .bind(flatfieldCheckbox.selectedProperty().not());
        flatfieldBrowse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Flat-field profile folder");
            File dir = chooser.showDialog(flatfieldBrowse.getScene().getWindow());
            if (dir != null) {
                flatfieldField.setText(dir.getAbsolutePath());
            }
        });
        flatfieldRow.getChildren().addAll(flatfieldCheckbox, flatfieldField, flatfieldBrowse);
        for (Node node : List.of(timeProjectionRow, flatfieldRow, zProjectionRow)) {
            Integer row = guiElementPositions.get(node);
            if (row != null) {
                pane.add(node, 0, row, 2, 1);
            } else {
                logger.error("Row index not found for {}", node);
            }
        }
        folderField.textProperty().addListener((obs, o, n) -> refreshPreprocessVisibility());
    }

    private void refreshPreprocessVisibility() {
        String method = stitchingGridBox.getValue();
        String path = folderField.getText();
        MicroManagerPreprocessor.Shape shape =
                method != null && method.startsWith("MicroManager") && path != null && !path.isBlank()
                        ? MicroManagerPreprocessor.shape(new File(path.trim()))
                        : new MicroManagerPreprocessor.Shape(0, 0);
        flatfieldRow.setVisible(shape.frames() > 0);
        timeProjectionRow.setVisible(shape.frames() > 1);
        if (shape.frames() > 1) {
            timeProjectionLabel.setText("Combine the " + shape.frames() + " time points of each position:");
        }
        zProjectionRow.setVisible(shape.slices() > 1);
        if (shape.slices() > 1) {
            zProjectionLabel.setText("Combine the " + shape.slices() + " z-slices of each position"
                    + (shape.frames() > 1 ? " (after time points):" : ":"));
        }
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

    /**
     * The first TIFF in a channel folder, looking two levels down as well: an acquisition that kept
     * its z-slices and time points puts the tiles in {@code t000/z000/} sub-folders.
     */
    static File firstTiff(Path dir) {
        return FileScanner.find(dir, 2, p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return !n.contains(":") && (n.endsWith(".tif") || n.endsWith(".tiff"));
                })
                .stream()
                .sorted(java.util.Comparator.comparingInt(Path::getNameCount).thenComparing(p -> p))
                .findFirst()
                .map(Path::toFile)
                .orElse(null);
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
        addZSpacingComponents(pane);
        addStageInvertComponents(pane);
        addPreprocessComponents(pane);
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
        refreshZSpacing();
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
                        + "Auto (best match): measures the same seams on every folder and solves on the one\n"
                        + "where the tiles fit together only one way. A folder of small repeated features can\n"
                        + "line up in several places that all look about equally good; one with large distinct\n"
                        + "structure usually cannot, and it is the safer thing to measure. Weak seams are then\n"
                        + "re-measured on the other folders.\n"
                        + "Normalized merge of all: scales each folder once for the whole dataset, then averages\n"
                        + "them.\n"
                        + "Reads every folder at every seam, so it takes longer than a single folder.\n"
                        + "A named folder: solve on that folder only.\n\n"
                        + "MicroManager: the choices are the acquisition's channels. Every position is\n"
                        + "measured on the same plane of a channel, the middle z-slice of the first\n"
                        + "time point, and every plane of a position gets that position's correction."));
        referenceBox.setTooltip(referenceLabel.getTooltip());
        refreshReferenceChoices();
        folderField.textProperty().addListener((obs, o, n) -> refreshReferenceChoices());
        // The candidates are the folders that will be stitched, so they change with the match too.
        matchStringField.textProperty().addListener((obs, o, n) -> refreshReferenceChoices());

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
     * Populate the reference-subdirectory choices with the folders this run will actually stitch,
     * keeping {@link #AUTO_REFERENCE} first and {@link #PROJECTION_REFERENCE} second when there are
     * at least two, and preserving the current selection when it survives.
     *
     * <p>The candidates come from {@link TileDirectories#resolve}, the same call the stitch makes,
     * so the list cannot disagree with what is stitched. Listing every immediate subdirectory
     * instead offered folders that are not channels at all: after a merge the tile folder contains
     * our own {@code <folder>_channels} output, which duly appeared in the dropdown. The
     * MicroManager method has no channel subdirectories: its channels are offered by the names
     * their stitches take ({@link MicroManagerMetadataStrategy#channelOutputNames}).
     *
     * <p>Fewer than two directories means there is nothing to choose between, so only
     * {@code Auto} is offered.
     */
    private void refreshReferenceChoices() {
        String previous = referenceBox.getValue();
        java.util.List<String> choices = new java.util.ArrayList<>();
        choices.add(AUTO_REFERENCE);
        String path = folderField.getText();
        String method = stitchingGridBox.getValue();
        if (path != null && !path.trim().isEmpty() && method != null && method.startsWith("MicroManager")) {
            // MicroManager channels are pages or files inside each position, not folders; offer them
            // by the names their stitches take.
            java.util.List<String> channels = MicroManagerMetadataStrategy.channelOutputNames(new File(path.trim()));
            if (channels.size() >= 2) {
                choices.add(PROJECTION_REFERENCE);
                choices.addAll(channels);
            }
        } else if (path != null && !path.trim().isEmpty()) {
            try {
                java.util.List<java.nio.file.Path> dirs =
                        TileDirectories.resolve(new File(path.trim()).toPath(), matchStringField.getText());
                if (dirs.size() >= 2) {
                    choices.add(PROJECTION_REFERENCE);
                    for (java.nio.file.Path dir : dirs) {
                        choices.add(dir.getFileName().toString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable or half-typed folder is not worth a dialog; Auto still works.
                logger.debug("Could not list reference choices for {}: {}", path, e.toString());
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
        guiElementPositions.put(zSpacingLabel, currentPosition++);
        guiElementPositions.put(invertLabel, currentPosition++);
        guiElementPositions.put(timeProjectionRow, currentPosition++);
        guiElementPositions.put(flatfieldRow, currentPosition++);
        guiElementPositions.put(zProjectionRow, currentPosition++);
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
                if ("Coordinates in TileConfiguration.txt file".equals(method)) return TILECONFIG_LABEL;
                if (MM_METHOD.equals(method)) return MM_LABEL;
                return method;
            }

            @Override
            public String fromString(String label) {
                if (TILECONFIG_LABEL.equals(label)) return "Coordinates in TileConfiguration.txt file";
                if (MM_LABEL.equals(label)) return MM_METHOD;
                return label;
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
        pixelSizeSourceLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #666;");

        // Restored, like every other control in this dialog. The field already reloads the number
        // typed last time, so resetting the box to unticked left that number visible but unused --
        // and TileConfiguration.txt tiles carry no pixel size to fall back on, making it look like
        // the value had simply been forgotten.
        boolean manualAtOpen = QPPreferences.getPixelSizeOverrideSaved();
        pixelSizeOverrideCheckbox.setSelected(manualAtOpen);
        pixelSizeField.setEditable(manualAtOpen);
        if (manualAtOpen) {
            pixelSizeSourceLabel.setText("(manual override)");
        }
        // Checking the box unlocks the field; unchecking restores the auto-detected value (or the
        // saved default if no MMStack metadata was found in the current folder).
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
        // MicroManager only. The scan looks three levels down, so on any other method the
        // "MicroManager metadata" it finds can belong to an unrelated acquisition inside the
        // folder you picked -- selecting a parent holding both a MicroManager set and a
        // polarized set filled in the MicroManager pixel size and stitched the other one at
        // nearly four times the right scale, with the label claiming the value was measured.
        // A TileConfiguration.txt carries no pixel size, so there is nothing honest to fill.
        String method = stitchingGridBox.getValue();
        if (method == null || !method.startsWith("MicroManager")) {
            pixelSizeSourceLabel.setText("");
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
        Tooltip matchStringTooltip = new Tooltip("Which folders to stitch, and how many images come out.\n\n"
                + "  (empty)   the selected folder itself -- one image\n"
                + "  *         every sub-folder -- one image each\n"
                + "  some text sub-folders whose name contains it -- one image each\n\n"
                + "Matching is case-sensitive. Use * for a set of channel or angle folders;\n"
                + "leaving it empty would pile all of their tiles into a single image.");
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
        // "Measure from tiles..." estimates the pixel size from the overlap between neighbors,
        // and it finds which tiles neighbor which by reading MicroManager metadata. On any other
        // method it can only report "Need at least two tiles with stage positions", so offering it
        // there is an invitation to a dead end.
        boolean canMeasurePixelSize = selectedValue != null && selectedValue.startsWith("MicroManager");
        estimatePixelSizeButton.setVisible(!hidePixelSize && canMeasurePixelSize);

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

        // Re-evaluate on a method change too, not only a folder change: switching away from
        // MicroManager must drop a value that was auto-filled from its metadata, along with the
        // label claiming where it came from.
        autoFillPixelSizeFromFolder();
        refreshMergeVisibility();
        refreshPreprocessVisibility();
        refreshReferenceChoices();
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
    private static void showResultDialog(boolean success, boolean nothingProduced, String message, String outputDir) {
        Alert alert = new Alert(success ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        // Distinct from the stitch dialog's title: the Dialog Manager extension remembers window
        // size per title, so sharing one made the stitch dialog reopen at this alert's small size.
        alert.setTitle("Tiles to Pyramid - Result");
        // "did not fully succeed" promises a partial result; when nothing came out, say so.
        alert.setHeaderText(
                success
                        ? "Stitching complete"
                        : nothingProduced ? "No tiles were stitched" : "Stitching did not fully succeed");
        TextArea text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefColumnCount(70);
        // +2: one spare row, one for the horizontal scroll bar a long path brings up.
        text.setPrefRowCount((int) Math.min(12, message.lines().count() + 2));
        alert.getDialogPane().setContent(text);
        alert.setResizable(true);

        // Copying a path out of the text area and pasting it into a file browser is what everybody
        // does next. Offered only when there is a folder to open and something to open it with:
        // Desktop is unsupported on a headless JVM and on some Linux sessions.
        ButtonType openFolder = new ButtonType("Open output folder", ButtonBar.ButtonData.LEFT);
        ButtonType backToSettings = new ButtonType("Back to settings", ButtonBar.ButtonData.LEFT);
        File dir = outputDir == null || outputDir.isBlank() ? null : new File(outputDir);
        boolean canOpen = dir != null
                && dir.isDirectory()
                && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);

        // A failed stitch is nearly always a setting to change, so put the user back in front of
        // the settings rather than leaving them to find the menu again. Success keeps the folder
        // button instead: what you want next is the files.
        if (!success) {
            alert.getButtonTypes().setAll(backToSettings, ButtonType.CLOSE);
        } else if (canOpen) {
            alert.getButtonTypes().setAll(openFolder, ButtonType.OK);
        }

        DialogOwner.own(alert);
        // Not modal: a stitch can end while the user is working in QuPath, and the result should
        // not block that work until it is read.
        alert.initModality(Modality.NONE);
        alert.setOnHidden(e -> {
            ButtonType choice = alert.getResult();
            if (choice == openFolder) {
                openDirectory(dir);
            } else if (choice == backToSettings) {
                // Every field is restored from the preferences the run just saved, so the dialog
                // comes back holding what they tried, ready to be corrected.
                Platform.runLater(StitchingGUI::createGUI);
            }
        });
        alert.show();
    }

    /**
     * What registration actually did, for the result window.
     *
     * <p>The engine records this in the log and in the stitch record beside the image, and nowhere
     * a user will look. Asking someone to open the log and pick "12/12 edges accepted" out of a few
     * hundred INFO lines is not telling them: the accepted-seam count is how you know whether the
     * stage was trusted or corrected, so it belongs in front of them.
     *
     * <p>Built from {@link StitchingConfig#getRegistrationRecord()}, which carries {@code mode},
     * {@code aligned on} and {@code tile placements moved} as entries, and the solved counts in the
     * solution-file header lines it copies ({@code # edgesAccepted: 12   edgesTotal: 12   ...}).
     *
     * @return a short report, or null when registration was not asked for
     */
    private static String describeRegistration(StitchingConfig config) {
        if (config.getRegistrationMode() instanceof RegistrationMode.Disabled) {
            return null;
        }
        StitchInfoFile.Section record = config.getRegistrationRecord();
        if (record == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Tile registration");
        String seams = seamCounts(record.lines());
        if (seams != null) {
            sb.append(": ").append(seams).append(" accepted");
        } else {
            String mode = record.entries().get("mode");
            sb.append(": ").append(mode == null ? "ran" : mode);
        }
        String on = record.entries().get("aligned on");
        if (on != null) {
            sb.append(", aligned on ").append(on);
        }
        String moved = record.entries().get("tile placements moved");
        if (moved != null) {
            sb.append(", moved ").append(moved).append(" tile placements");
        }
        return sb.append('.').toString();
    }

    /** "12 of 12 seams" from the solution header, or null if it is not there. */
    private static String seamCounts(List<String> headerLines) {
        for (String line : headerLines) {
            java.util.regex.Matcher m = SEAM_COUNTS.matcher(line);
            if (m.find()) {
                return m.group(1) + " of " + m.group(2) + " seams";
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern SEAM_COUNTS =
            java.util.regex.Pattern.compile("edgesAccepted:\\s*(\\d+)\\s+edgesTotal:\\s*(\\d+)");

    /** Where a user should report a folder we cannot read but believe we should. */
    private static final String ISSUES_URL = "https://github.com/uw-loci/qupath-extension-tiles-to-pyramid/issues";

    /**
     * Say why a stitch found no tiles, in terms a user can act on.
     *
     * <p>Every wrong choice produces the same symptom -- zero tiles -- and the method is remembered
     * between runs, so the usual cause is a folder pointed at whichever method was used last. The
     * log line ("No tile mappings produced by strategy") names none of that. This looks at what is
     * actually in the folder and, where the evidence is there, names the method that would have
     * worked.
     */
    private static String explainNoTiles(StitchingConfig config) {
        File folder = new File(config.folderPath);
        StringBuilder sb = new StringBuilder();
        sb.append("No tiles were found in this folder using the method you chose.\n\n")
                .append("Folder:  ")
                .append(config.folderPath)
                .append('\n')
                .append("Method:  ")
                .append(config.stitchingType)
                .append('\n');
        if (!TileDirectories.isSingleFolder(config.matchingString)) {
            sb.append("Sub-folder text:  '").append(config.matchingString).append("'\n");
        }

        int tiffs = FolderDiagnosis.countTiffs(folder);
        if (tiffs == 0) {
            sb.append("\nThere are no TIFF files in this folder or the folders inside it. ")
                    .append("Check that you selected the right folder.");
            return sb.toString();
        }

        List<String> unsupported = FolderDiagnosis.unsupported(folder);
        if (!unsupported.isEmpty()) {
            sb.append("\nNot supported:\n");
            unsupported.forEach(u -> sb.append("  - ").append(u).append('\n'));
        }

        List<FolderDiagnosis.Finding> found = FolderDiagnosis.diagnose(folder);
        FolderDiagnosis.Finding mine = found.stream()
                .filter(f -> f.method().equals(config.stitchingType))
                .findFirst()
                .orElse(null);
        List<FolderDiagnosis.Finding> others = found.stream()
                .filter(f -> !f.method().equals(config.stitchingType))
                .toList();

        sb.append("\nIt holds ")
                .append(tiffs)
                .append(" TIFF file")
                .append(tiffs == 1 ? "" : "s")
                .append(", so the images are there -- what is missing is the position information ")
                .append("this method reads.\n");

        // Right method, wrong folder: the evidence is here but one level down, which is what
        // selecting an acquisition's parent rather than the acquisition itself looks like. The
        // symptom is identical to picking the wrong method, so it has to be named separately.
        if (mine != null && mine.onlyInSubFolders()) {
            List<String> subs = mine.subFolders();
            sb.append("\nThis method's own ")
                    .append(mine.evidence())
                    .append(subs.size() == 1 ? " is" : " are")
                    .append(" here, but one level down, in:\n");
            for (String sub : subs) {
                sb.append("  ").append(sub).append("/\n");
            }
            sb.append("\nThis method reads only the folder you pick, so select ");
            if (subs.size() == 1) {
                sb.append("that one instead.");
            } else {
                sb.append("one of those instead, or type text they all share into ")
                        .append("'Stitch sub-folders with text string'.");
            }
            return sb.toString();
        }

        if (!others.isEmpty()) {
            sb.append("\nThis folder looks like it wants a different method:\n");
            for (FolderDiagnosis.Finding f : others) {
                sb.append("  - ")
                        .append(f.method())
                        .append("\n        (found ")
                        .append(f.evidence())
                        .append(f.onlyInSubFolders() ? ", in " + String.join("/, ", f.subFolders()) + "/" : "")
                        .append(")\n");
            }
            sb.append("\nChange Stitching Method to one of those and stitch again.");
        } else if (!TileDirectories.isSingleFolder(config.matchingString)) {
            sb.append("\nNothing else here matches another method either. The likeliest cause is the ")
                    .append("sub-folder text: it selects only sub-folders whose name contains it, and it ")
                    .append("is case-sensitive. Clear it to stitch the selected folder itself.");
        } else {
            sb.append("\nNothing here matches any of the other methods either. If you believe this ")
                    .append("folder should stitch, please open an issue with a small sample of the data:\n  ")
                    .append(ISSUES_URL);
        }
        return sb.toString();
    }

    /**
     * Show a folder in the system file browser.
     *
     * <p>Off the FX thread: {@code Desktop.open} blocks until the browser has launched, which on
     * Windows is long enough to freeze the UI visibly.
     */
    private static void openDirectory(File dir) {
        Thread opener = new Thread(
                () -> {
                    try {
                        Desktop.getDesktop().open(dir);
                    } catch (IOException | RuntimeException e) {
                        logger.error("Could not open {}: {}", dir, e.getMessage(), e);
                        Platform.runLater(() ->
                                showAlertDialog("Could not open the folder. It is here:\n" + dir.getAbsolutePath()));
                    }
                },
                "tiles-to-pyramid-open-folder");
        opener.setDaemon(true);
        opener.start();
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
