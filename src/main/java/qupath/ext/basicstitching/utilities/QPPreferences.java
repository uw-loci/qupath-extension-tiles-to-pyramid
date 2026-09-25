// =======================================================================================
// 2. QPPreferences.java (renamed and restructured)
// =======================================================================================
package qupath.ext.basicstitching.utilities;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Utility class for managing persistent preferences in the Basic Stitching extension.
 * Handles saving and retrieving user preferences across application sessions.
 */
public class QPPreferences {

    // Private static properties for persistent storage
    private static final StringProperty folderLocationSaved =
            PathPrefs.createPersistentPreference("folderLocation", System.getProperty("user.home", "."));

    /**
     * Whether the saved folder was put there by a person, rather than being the default.
     *
     * <p>Opening the dialog reads the folder's MicroManager metadata to pre-fill the pixel size.
     * That is helpful for a folder the user chose and wrong for one they did not: the default is
     * their home directory, and a scan of it finds some unrelated acquisition's metadata and
     * silently pre-fills ITS pixel size, which stitches at the wrong scale.
     */
    private static final javafx.beans.property.BooleanProperty folderChosenSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.folderChosen", false);

    /** @return whether the saved folder was chosen by the user. */
    public static boolean isFolderChosen() {
        return folderChosenSaved.get();
    }

    /** @param chosen whether the folder now in the dialog was put there by the user */
    public static void setFolderChosen(boolean chosen) {
        folderChosenSaved.set(chosen);
    }

    private static final StringProperty imagePixelSizeInMicronsSaved =
            PathPrefs.createPersistentPreference("imagePixelSizeInMicrons", "7.2");

    private static final StringProperty downsampleSaved = PathPrefs.createPersistentPreference("downsample", "1");

    // Blank by default: an empty string stitches the selected folder on its own, which is what
    // someone pointing at one folder of tiles wants. The old default of "20x" was a QPSC habit --
    // it matched that project's objective-named sub-folders and matched nothing in anyone else's
    // data, so a first-time user saw a pre-filled field they had to know to clear.
    private static final StringProperty searchStringSaved = PathPrefs.createPersistentPreference("searchString", "");

    private static final StringProperty compressionTypeSaved =
            PathPrefs.createPersistentPreference("compressionType", "J2K");

    private static final StringProperty stitchingMethodSaved =
            PathPrefs.createPersistentPreference("stitchingMethod", "Coordinates in TileConfiguration.txt file");

    // Content-based overlap resolution (tile registration). Off by default: nominal stage placement
    // is the historical behavior and the faster path.
    private static final BooleanProperty resolveOverlapsSaved =
            PathPrefs.createPersistentPreference("resolveOverlaps", false);

    // Per-run overlap choice for registration, remembered across sessions like the other dialog
    // fields. These are dialog conveniences, distinct from the persistent tuning in the
    // "Tiles-to-pyramid" Preferences category (see RegistrationPreferences).
    private static final BooleanProperty regOverlapAutoSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.regOverlapAuto", true);
    private static final StringProperty regOverlapXSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.regOverlapX", "10");
    private static final StringProperty regOverlapYSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.regOverlapY", "10");

    // Output format, stored as the StitchingConfig.OutputFormat enum name.
    private static final StringProperty outputFormatSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.outputFormat", "OME_TIFF");

    // Merge per-channel stitches into one multichannel image (only offered when there are channels).
    private static final BooleanProperty mergeChannelsSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.mergeChannels", true);

    // Negate the stage axes before converting stage positions to pixels, for the MicroManager and
    // TileConfiguration.txt methods. A property of the microscope, not of the acquisition, so it is
    // remembered rather than re-chosen each stitch.
    private static final BooleanProperty stageInvertXSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.stageInvertX", false);

    private static final BooleanProperty stageInvertYSaved =
            PathPrefs.createPersistentPreference("basicstitching.dialog.stageInvertY", false);

    // Folder Location
    public static String getFolderLocationSaved() {
        return folderLocationSaved.getValue();
    }

    public static void setFolderLocationSaved(final String folderLocation) {
        folderLocationSaved.setValue(folderLocation);
    }

    // Image Pixel Size
    public static String getImagePixelSizeInMicronsSaved() {
        return imagePixelSizeInMicronsSaved.getValue();
    }

    public static void setImagePixelSizeInMicronsSaved(final String imagePixelSizeInMicrons) {
        imagePixelSizeInMicronsSaved.setValue(imagePixelSizeInMicrons);
    }

    // Downsample
    public static String getDownsampleSaved() {
        return downsampleSaved.getValue();
    }

    public static void setDownsampleSaved(final String downsample) {
        downsampleSaved.setValue(downsample);
    }

    // Search String
    public static String getSearchStringSaved() {
        return searchStringSaved.getValue();
    }

    public static void setSearchStringSaved(final String searchString) {
        searchStringSaved.setValue(searchString);
    }

    // Compression Type
    public static String getCompressionTypeSaved() {
        return compressionTypeSaved.getValue();
    }

    public static void setCompressionTypeSaved(final String compressionType) {
        compressionTypeSaved.setValue(compressionType);
    }

    // Stitching Method
    public static String getStitchingMethodSaved() {
        return stitchingMethodSaved.getValue();
    }

    public static void setStitchingMethodSaved(final String stitchingMethod) {
        stitchingMethodSaved.setValue(stitchingMethod);
    }

    // Content-based overlap resolution (tile registration)
    public static boolean getResolveOverlapsSaved() {
        return resolveOverlapsSaved.getValue();
    }

    public static void setResolveOverlapsSaved(final boolean resolveOverlaps) {
        resolveOverlapsSaved.setValue(resolveOverlaps);
    }

    // Per-run overlap choice for registration
    public static boolean getRegOverlapAutoSaved() {
        return regOverlapAutoSaved.getValue();
    }

    public static void setRegOverlapAutoSaved(final boolean auto) {
        regOverlapAutoSaved.setValue(auto);
    }

    public static String getRegOverlapXSaved() {
        return regOverlapXSaved.getValue();
    }

    public static void setRegOverlapXSaved(final String x) {
        regOverlapXSaved.setValue(x);
    }

    public static String getRegOverlapYSaved() {
        return regOverlapYSaved.getValue();
    }

    public static void setRegOverlapYSaved(final String y) {
        regOverlapYSaved.setValue(y);
    }

    public static String getOutputFormatSaved() {
        return outputFormatSaved.getValue();
    }

    public static void setOutputFormatSaved(final String format) {
        outputFormatSaved.setValue(format);
    }

    public static boolean getMergeChannelsSaved() {
        return mergeChannelsSaved.getValue();
    }

    public static void setMergeChannelsSaved(final boolean merge) {
        mergeChannelsSaved.setValue(merge);
    }

    public static boolean getStageInvertXSaved() {
        return stageInvertXSaved.getValue();
    }

    public static void setStageInvertXSaved(final boolean invert) {
        stageInvertXSaved.setValue(invert);
    }

    public static boolean getStageInvertYSaved() {
        return stageInvertYSaved.getValue();
    }

    public static void setStageInvertYSaved(final boolean invert) {
        stageInvertYSaved.setValue(invert);
    }
}
