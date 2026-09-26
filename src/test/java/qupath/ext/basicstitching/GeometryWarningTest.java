package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.config.StitchingConfig;

/**
 * A wrong pixel size cannot fail a stitch: positions are micrometers divided by it, so too large a
 * value just packs the tiles together and a plausible-looking mosaic is written. The geometry is
 * the only evidence, and it has to reach the user rather than the log.
 */
class GeometryWarningTest {

    private static StitchingConfig config(double pixelSize) {
        return new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                "in",
                "out",
                "UNCOMPRESSED",
                pixelSize,
                1.0,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
    }

    @Test
    void aFreshConfigCarriesNoWarning() {
        assertNull(config(0.1732).getGeometryWarning());
    }

    @Test
    void theWarningRoundTrips() {
        StitchingConfig cfg = config(0.653);
        cfg.setGeometryWarning("tiles overlap by 76%");
        assertEquals("tiles overlap by 76%", cfg.getGeometryWarning());
    }

    @Test
    void theWarningCanBeCleared() {
        StitchingConfig cfg = config(0.653);
        cfg.setGeometryWarning("something");
        cfg.setGeometryWarning(null);
        assertNull(cfg.getGeometryWarning(), "a later stitch on the same config must not inherit it");
    }
}
