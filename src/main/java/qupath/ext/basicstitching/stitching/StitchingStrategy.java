package qupath.ext.basicstitching.stitching;

import java.util.List;

/**
 * Strategy interface for tile-mapping methods.
 * Each implementation produces a list of TileMapping objects from input folder/config params.
 */
public interface StitchingStrategy {
    List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString);
}
