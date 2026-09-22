package qupath.ext.basicstitching.stitching;

import java.io.File;
import qupath.lib.regions.ImageRegion;

public class TileMapping {
    public final File file;
    public final ImageRegion region;
    public final String subdirName;

    /**
     * Which image inside the file this tile is, as {@code javax.imageio} counts them:
     * the IFD (page) index, not the OME series number.
     *
     * <p>The distinction matters, and was got wrong once. A MicroManager MMStack file
     * carries OME-XML describing every position in the acquisition as a separate
     * series, so it is tempting to store the series index here. But the read path is
     * {@link qupath.ext.basicstitching.assembly.direct.TileReaderPool}, which uses
     * {@code javax.imageio} and addresses pages. In the common "separate file per
     * position" layout each file holds one position's planes, so the pages are the
     * CHANNELS and the series number means nothing to the reader: a 4-channel
     * acquisition stitched to a single channel because every tile was read from page 0.
     *
     * <p>Whoever builds the mapping converts to a page index and checks it against the
     * file's real page count. Defaults to 0, which is right for any single-page tile.
     */
    public final int ifdIndex;

    public TileMapping(File file, ImageRegion region, String subdirName) {
        this(file, region, subdirName, 0);
    }

    public TileMapping(File file, ImageRegion region, String subdirName, int ifdIndex) {
        this.file = file;
        this.region = region;
        this.subdirName = subdirName;
        this.ifdIndex = ifdIndex;
    }
}
