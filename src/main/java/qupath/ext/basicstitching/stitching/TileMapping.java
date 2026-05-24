package qupath.ext.basicstitching.stitching;

import java.io.File;
import qupath.lib.regions.ImageRegion;

public class TileMapping {
    public final File file;
    public final ImageRegion region;
    public final String subdirName;

    /**
     * Series index within the source file for multi-series OME-TIFFs
     * (e.g. MicroManager MMStack files where every per-position TIFF carries
     * OME-XML describing all positions as separate series). Strategies that
     * deal with single-series files should leave this at the default 0.
     */
    public final int seriesIndex;

    public TileMapping(File file, ImageRegion region, String subdirName) {
        this(file, region, subdirName, 0);
    }

    public TileMapping(File file, ImageRegion region, String subdirName, int seriesIndex) {
        this.file = file;
        this.region = region;
        this.subdirName = subdirName;
        this.seriesIndex = seriesIndex;
    }
}
