// =======================================================================================
// 4. StitchingGUI.java
// =======================================================================================
package qupath.ext.basicstitching.functions;

import static qupath.ext.basicstitching.utilities.UtilityFunctions.getCompressionTypeList;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;
import qupath.ext.basicstitching.utilities.QPPreferences;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
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

    // GUI Components - static fields for persistence across dialog instances
    static TextField folderField = new TextField(QPPreferences.getFolderLocationSaved());
    static ComboBox<String> compressionBox = new ComboBox<>();
    static ComboBox<StitchingConfig.OutputFormat> outputFormatBox = new ComboBox<>();
    static TextField pixelSizeField = new TextField(QPPreferences.getImagePixelSizeInMicronsSaved());
    static CheckBox pixelSizeOverrideCheckbox = new CheckBox("Manually edit pixel size");
    static Label pixelSizeSourceLabel = new Label("");
    static Button estimatePixelSizeButton = new Button("Try calculating pixel size...");
    static TextField downsampleField = new TextField(QPPreferences.getDownsampleSaved());
    static TextField matchStringField = new TextField(QPPreferences.getSearchStringSaved());
    static ComboBox<String> stitchingGridBox = new ComboBox<>();
    static Button folderButton = new Button("Select Folder");
    static CheckBox useFudgeFactorCheckbox = new CheckBox("Apply fudge factor to adjust for gaps between tiles");
    static TextField xFudgeField = new TextField("1.0");
    static TextField yFudgeField = new TextField("1.0");
    static Hyperlink vectraForumLink = new Hyperlink("See forum discussion");
    // Labels
    static Label stitchingGridLabel = new Label("Stitching Method:");
    static Label folderLabel = new Label("Folder location:");
    static Label compressionLabel = new Label("Compression type:");
    static Label outputFormatLabel = new Label("Output format:");
    static Label pixelSizeLabel = new Label("Pixel size, microns:");
    static Label downsampleLabel = new Label("Downsample:");
    static Label matchStringLabel = new Label("Stitch sub-folders with text string:");
    static Hyperlink githubLink = new Hyperlink("GitHub ReadMe");
    static Label xFudgeLabel = new Label("X fudge factor:");
    static Label yFudgeLabel = new Label("Y fudge factor:");

    // Map to hold the positions of each GUI element
    private static Map<Node, Integer> guiElementPositions = new HashMap<>();

    /**
     * Creates and displays the main GUI dialog for stitching configuration.
     * Handles user input validation, preference saving, and initiates stitching process.
     */
    public static void createGUI() {
        // Create the dialog
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Input Stitching Method and Options");
        dlg.setHeaderText("Enter your settings below:");

        // Set the content
        dlg.getDialogPane().setContent(createContent());

        // Add Okay and Cancel buttons
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Show the dialog and capture the response
        Optional<ButtonType> result = dlg.showAndWait();

        // Handling the response
        if (result.isPresent() && result.get() == ButtonType.OK) {
            processDialogResult();
        }
    }

    private static void processDialogResult() {
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

            // Use the new workflow
            String finalImageName = StitchingWorkflow.run(config);

            // Optionally: display success or error dialog
            if (finalImageName != null) {
                showAlertDialog("Stitching complete: " + finalImageName);
            } else {
                showAlertDialog("Stitching failed. See logs for details.");
            }

        } catch (Exception e) {
            logger.error("Error processing dialog result", e);
            showAlertDialog("Error processing input: " + e.getMessage());
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
    private static GridPane createContent() {
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
        addCompressionComponents(pane);
        addOutputFormatComponents(pane);
        addPixelSizeComponents(pane);
        addDownsampleComponents(pane);
        addGitHubLinkComponent(pane);

        // Initial autofill from the restored folder preference. The textProperty
        // listener only fires on later changes; this seeds the pixel size +
        // source label from whatever folder is already in the field.
        autoFillPixelSizeFromFolder();

        // Update the components' visibility based on the current selection
        updateComponentsBasedOnSelection(pane);
        addFudgeFactorComponents(pane);
        return pane;
    }

    /**
     * Adds a label and its associated control to the specified GridPane.
     */
    private static void addToGrid(GridPane pane, Node label, Node control) {
        Integer rowIndex = guiElementPositions.get(label);
        if (rowIndex != null) {
            pane.add(label, 0, rowIndex);
            pane.add(control, 1, rowIndex);
        } else {
            logger.error("Row index not found for component: {}", label);
        }
    }

    /**
     * Adds a GitHub repository hyperlink to the GridPane.
     */
    private static void addGitHubLinkComponent(GridPane pane) {
        githubLink.setOnAction(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://github.com/MichaelSNelson/BasicStitching"));
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
    private static void initializePositions() {
        int currentPosition = 0;

        guiElementPositions.put(stitchingGridLabel, currentPosition++);
        guiElementPositions.put(folderLabel, currentPosition++);
        guiElementPositions.put(compressionLabel, currentPosition++);
        guiElementPositions.put(outputFormatLabel, currentPosition++);
        guiElementPositions.put(pixelSizeLabel, currentPosition++);
        guiElementPositions.put(pixelSizeOverrideCheckbox, currentPosition++);
        guiElementPositions.put(downsampleLabel, currentPosition++);
        guiElementPositions.put(matchStringLabel, currentPosition++);
        guiElementPositions.put(githubLink, currentPosition++);
        guiElementPositions.put(useFudgeFactorCheckbox, currentPosition++);
        guiElementPositions.put(xFudgeLabel, currentPosition++);
        guiElementPositions.put(yFudgeLabel, currentPosition++);
        guiElementPositions.put(vectraForumLink, currentPosition++);
    }

    /**
     * Adds stitching grid components to the specified GridPane.
     */
    private static void addStitchingGridComponents(GridPane pane) {
        stitchingGridBox.getItems().clear();
        stitchingGridBox
                .getItems()
                .addAll(
                        "Vectra tiles with metadata",
                        "Filename[x,y] with coordinates in microns",
                        "Coordinates in TileConfiguration.txt file",
                        "MicroManager metadata (MMStack or TIFF series)");

        stitchingGridBox.setValue(QPPreferences.getStitchingMethodSaved());
        stitchingGridBox.setOnAction(e -> updateComponentsBasedOnSelection(pane));

        Tooltip stitchingTooltip = new Tooltip("Method used to determine tile positions for stitching.");
        stitchingGridLabel.setTooltip(stitchingTooltip);
        stitchingGridBox.setTooltip(stitchingTooltip);

        addToGrid(pane, stitchingGridLabel, stitchingGridBox);
    }

    /**
     * Adds components for folder selection to the specified GridPane.
     */
    private static void addFolderSelectionComponents(GridPane pane) {
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
        folderField.textProperty().addListener((obs, oldVal, newVal) -> autoFillPixelSizeFromFolder());

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
    private static void addCompressionComponents(GridPane pane) {
        List<String> compressionTypes = getCompressionTypeList();
        compressionBox.getItems().clear();
        compressionBox.getItems().addAll(compressionTypes);

        compressionBox.setValue(QPPreferences.getCompressionTypeSaved());

        Tooltip compressionTooltip = new Tooltip("Select the type of image compression.");
        compressionLabel.setTooltip(compressionTooltip);
        compressionBox.setTooltip(compressionTooltip);

        addToGrid(pane, compressionLabel, compressionBox);
    }

    /**
     * Adds output format selection components to the specified GridPane.
     */
    private static void addOutputFormatComponents(GridPane pane) {
        outputFormatBox.getItems().clear();
        outputFormatBox.getItems().addAll(StitchingConfig.OutputFormat.values());

        // Default to OME-TIFF for backward compatibility
        outputFormatBox.setValue(StitchingConfig.OutputFormat.OME_TIFF);

        Tooltip formatTooltip = new Tooltip("OME-TIFF: Traditional single-file format (widely compatible)\n"
                + "OME-ZARR: Cloud-native directory format (better compression, parallel writing, cloud storage)");
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
    private static void addPixelSizeComponents(GridPane pane) {
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

        // "Try calculating pixel size..." estimates the true pixel size from the
        // actual tile overlap (phase correlation of neighbouring tiles), for
        // scopes whose metadata PixelSizeUm is wrong. The result is written into
        // the field as a manual override so the stitcher actually uses it.
        estimatePixelSizeButton.setTooltip(new Tooltip(
                "Measure the pixel size from the overlap between neighbouring tiles in the selected folder.\n"
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
    private static void estimatePixelSizeFromFolder() {
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
    private static void autoFillPixelSizeFromFolder() {
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
    private static void addDownsampleComponents(GridPane pane) {
        Tooltip downsampleTooltip =
                new Tooltip("The amount by which the highest resolution plane will be initially downsampled.");
        downsampleLabel.setTooltip(downsampleTooltip);
        downsampleField.setTooltip(downsampleTooltip);

        addToGrid(pane, downsampleLabel, downsampleField);
    }

    /**
     * Adds matching string input components to the specified GridPane.
     */
    private static void addMatchStringComponents(GridPane pane) {
        Tooltip matchStringTooltip = new Tooltip("Only stitch sub-folders whose names contain this text.");
        matchStringLabel.setTooltip(matchStringTooltip);
        matchStringField.setTooltip(matchStringTooltip);

        addToGrid(pane, matchStringLabel, matchStringField);
    }

    /**
     * Updates the visibility of certain GUI components based on the current selection
     * in the stitching method combo box.
     */
    private static void updateComponentsBasedOnSelection(GridPane pane) {
        String selectedValue = stitchingGridBox.getValue();
        boolean hidePixelSize = "Vectra tiles with metadata".equals(selectedValue)
                || "Coordinates in TileConfiguration.txt file".equals(selectedValue);

        pixelSizeLabel.setVisible(!hidePixelSize);
        pixelSizeField.setVisible(!hidePixelSize);
        pixelSizeOverrideCheckbox.setVisible(!hidePixelSize);
        pixelSizeSourceLabel.setVisible(!hidePixelSize);

        // Show fudge factor components only for Vectra
        boolean showFudgeFactor = "Vectra tiles with metadata".equals(selectedValue);
        useFudgeFactorCheckbox.setVisible(showFudgeFactor);
        if (showFudgeFactor && useFudgeFactorCheckbox.isSelected()) {
            setFudgeFactorVisibility(true);
        } else {
            setFudgeFactorVisibility(false);
        }

        adjustLayout(pane);
    }

    /**
     * Adjusts the layout of the GridPane based on the current positions.
     */
    private static void adjustLayout(GridPane pane) {
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
    public static void showAlertDialog(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning!");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.showAndWait();
    }

    private static void addFudgeFactorComponents(GridPane pane) {
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

    private static void setFudgeFactorVisibility(boolean visible) {
        xFudgeLabel.setVisible(visible);
        xFudgeField.setVisible(visible);
        yFudgeLabel.setVisible(visible);
        yFudgeField.setVisible(visible);
        vectraForumLink.setVisible(visible);
    }
}
