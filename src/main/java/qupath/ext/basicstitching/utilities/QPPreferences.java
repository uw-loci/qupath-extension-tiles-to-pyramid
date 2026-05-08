// =======================================================================================
// 2. QPPreferences.java (renamed and restructured)
// =======================================================================================
package qupath.ext.basicstitching.utilities;

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
}
