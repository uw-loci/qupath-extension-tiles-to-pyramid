package qupath.ext.basicstitching.stitching;

import java.io.File;
import qupath.lib.regions.ImageRegion;

public class TileMapping {
    public final File file;
    public final ImageRegion region;
    public final String subdirName;

    public TileMapping(File file, ImageRegion region, String subdirName) {
        this.file = file;
        this.region = region;
        this.subdirName = subdirName;
    }
}
