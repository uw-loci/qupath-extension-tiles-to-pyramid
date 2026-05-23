package qupath.ext.basicstitching.stitching;

import qupath.ext.basicstitching.config.StitchingConfig;

public class StitchingStrategyFactory {
    public static StitchingStrategy getStrategy(String name) {
        switch (name) {
            case "Filename[x,y] with coordinates in microns":
                return new FileNameStitchingStrategy();
            case "Vectra tiles with metadata":
                return new VectraMetadataStrategy();
            case "Coordinates in TileConfiguration.txt file":
                return new TileConfigurationTxtStrategy();
            case "MicroManager metadata (MMStack)":
                return new MicroManagerMetadataStrategy();
            default:
                throw new IllegalArgumentException("Unknown stitching strategy: " + name);
        }
    }

    /**
     * Create a strategy with configuration parameters.
     * This overloaded method allows passing configuration-specific parameters.
     */
    public static StitchingStrategy getStrategy(StitchingConfig config) {
        switch (config.stitchingType) {
            case "Filename[x,y] with coordinates in microns":
                return new FileNameStitchingStrategy();
            case "Vectra tiles with metadata":
                return new VectraMetadataStrategy(config.xFudgeFactor, config.yFudgeFactor);
            case "Coordinates in TileConfiguration.txt file":
                return new TileConfigurationTxtStrategy();
            case "MicroManager metadata (MMStack)":
                return new MicroManagerMetadataStrategy();
            default:
                throw new IllegalArgumentException("Unknown stitching strategy: " + config.stitchingType);
        }
    }
}
