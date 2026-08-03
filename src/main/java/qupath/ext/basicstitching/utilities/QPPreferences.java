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
            PathPrefs.createPersistentPreference("folderLocation", "C:/");

    private static final StringProperty imagePixelSizeInMicronsSaved =
            PathPrefs.createPersistentPreference("imagePixelSizeInMicrons", "7.2");

    private static final StringProperty downsampleSaved = PathPrefs.createPersistentPreference("downsample", "1");

    private static final StringProperty searchStringSaved = PathPrefs.createPersistentPreference("searchString", "20x");

    private static final StringProperty compressionTypeSaved =
            PathPrefs.createPersistentPreference("compressionType", "J2K");

    private static final StringProperty stitchingMethodSaved =
            PathPrefs.createPersistentPreference("stitchingMethod", "Coordinates in TileConfiguration.txt file");

    // Content-based overlap resolution (tile registration). Off by default: nominal stage placement
    // is the historical behaviour and the faster path.
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
}
