package qupath.ext.basicstitching.stitching;

import java.nio.file.Path;
import java.util.List;

/**
 * Strategy interface for tile-mapping methods.
 * Each implementation produces a list of TileMapping objects from input folder/config params.
 */
public interface StitchingStrategy {
    List<TileMapping> prepareStitching(
            String folderPath, double pixelSizeInMicrons, double baseDownsample, String matchingString);

    /**
     * Problems the last {@link #prepareStitching} call found that change what gets stitched (tiles
     * left out, planes read only in part), in words for the person who started the stitch.
     *
     * @return the problems; empty when there were none
     */
    default List<String> warnings() {
        return List.of();
    }

    /**
     * The order of the groups ({@link TileMapping#subdirName}) the last {@link #prepareStitching}
     * call produced, when the input records one: Micro-Manager's channel order. Folder-based
     * inputs record none.
     *
     * @return group names in acquisition order; empty when the input records no order
     */
    default List<String> groupOrder() {
        return List.of();
    }

    /**
     * The spacing between z-slices that the acquisition the last {@link #prepareStitching} call read
     * recorded itself, for a caller that does not know it.
     *
     * @return the spacing in microns; null when the input records none
     */
    default Double recordedZSpacingUm() {
        return null;
    }

    /**
     * Write what the input recorded about the acquisition beside one output of the last
     * {@link #prepareStitching} call. Never throws.
     *
     * @param output the stitched image
     * @param group the group ({@link TileMapping#subdirName}) it was stitched from
     * @return the file written, or null when the strategy has nothing to write
     */
    default Path writeOutputMetadata(Path output, String group) {
        return null;
    }
}
