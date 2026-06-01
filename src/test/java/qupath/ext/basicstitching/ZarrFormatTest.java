package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.config.StitchingConfig;

/**
 * Unit tests for ZARR format support in the tiles-to-pyramid extension.
 *
 * NOTE: These tests verify configuration and API compatibility.
 * Full integration testing requires actual tile data and should be performed
 * on the Windows system with JDK and QuPath available.
 */
public class ZarrFormatTest {

    /**
     * Test that OutputFormat enum contains expected values.
     */
    @Test
    public void testOutputFormatEnumValues() {
        StitchingConfig.OutputFormat[] formats = StitchingConfig.OutputFormat.values();

        assertEquals(2, formats.length, "Should have exactly 2 output formats");

        boolean hasOMETIFF = false;
        boolean hasOMEZARR = false;

        for (StitchingConfig.OutputFormat format : formats) {
            if (format == StitchingConfig.OutputFormat.OME_TIFF) {
                hasOMETIFF = true;
            } else if (format == StitchingConfig.OutputFormat.OME_ZARR) {
                hasOMEZARR = true;
            }
        }

        assertTrue(hasOMETIFF, "Should have OME_TIFF format");
        assertTrue(hasOMEZARR, "Should have OME_ZARR format");
    }

    /**
     * Test that StitchingConfig can be created with explicit output format.
     */
    @Test
    public void testStitchingConfigWithOutputFormat() {
        StitchingConfig config = new StitchingConfig(
                "filename",
                "/test/input",
                "/test/output",
                "zstd",
                0.5,
                1.0,
                "test",
                1.0,
                StitchingConfig.OutputFormat.OME_ZARR);

        assertNotNull(config, "Config should be created successfully");
        assertEquals(StitchingConfig.OutputFormat.OME_ZARR, config.outputFormat, "Output format should be OME_ZARR");
        assertEquals("zstd", config.compressionType, "Compression type should be zstd");
    }

    /**
     * Test that deprecated constructor defaults to OME_TIFF for backward compatibility.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void testDeprecatedConstructorDefaultsToOMETIFF() {
        StitchingConfig config =
                new StitchingConfig("filename", "/test/input", "/test/output", "LZW", 0.5, 1.0, "test", 1.0);

        assertNotNull(config, "Config should be created successfully");
        assertEquals(
                StitchingConfig.OutputFormat.OME_TIFF,
                config.outputFormat,
                "Deprecated constructor should default to OME_TIFF");
    }

    /**
     * Test that StitchingConfig with fudge factors supports output format.
     */
    @Test
    public void testStitchingConfigWithFudgeFactorsAndFormat() {
        StitchingConfig config = new StitchingConfig(
                "vectra",
                "/test/input",
                "/test/output",
                "lz4",
                0.5,
                1.0,
                "test",
                1.0,
                1.05, // xFudgeFactor
                1.02, // yFudgeFactor
                StitchingConfig.OutputFormat.OME_ZARR);

        assertNotNull(config, "Config should be created successfully");
        assertEquals(StitchingConfig.OutputFormat.OME_ZARR, config.outputFormat);
        assertEquals(1.05, config.xFudgeFactor, 0.001);
        assertEquals(1.02, config.yFudgeFactor, 0.001);
    }

    /**
     * Test TIFF compression types that should map to ZARR equivalents.
     */
    @Test
    public void testCompressionTypeMapping() {
        // Test that common TIFF compression types can be used with ZARR
        // The actual mapping is tested in integration tests that write files

        String[] tiffCompressions = {"LZW", "JPEG", "JPEG-2000", "Uncompressed"};
        String[] zarrCompressions = {"zstd", "lz4", "lz4hc", "blosclz", "zlib"};

        // Verify that config accepts both TIFF and ZARR compression types
        for (String compression : tiffCompressions) {
            StitchingConfig config = new StitchingConfig(
                    "filename",
                    "/in",
                    "/out",
                    compression,
                    0.5,
                    1.0,
                    "test",
                    1.0,
                    StitchingConfig.OutputFormat.OME_ZARR);
            assertNotNull(config, "Should accept TIFF compression: " + compression);
        }

        for (String compression : zarrCompressions) {
            StitchingConfig config = new StitchingConfig(
                    "filename",
                    "/in",
                    "/out",
                    compression,
                    0.5,
                    1.0,
                    "test",
                    1.0,
                    StitchingConfig.OutputFormat.OME_ZARR);
            assertNotNull(config, "Should accept ZARR compression: " + compression);
        }
    }

    /**
     * Test that null output format is handled safely.
     */
    @Test
    public void testNullOutputFormatHandling() {
        // The workflow should handle null outputFormat by defaulting to OME_TIFF
        // This is tested in integration tests, but we verify the config accepts null

        StitchingConfig config = new StitchingConfig(
                "filename",
                "/test/input",
                "/test/output",
                "LZW",
                0.5,
                1.0,
                "test",
                1.0,
                1.0,
                1.0,
                null // null output format
                );

        // The config should still be created, even with null format
        assertNotNull(config, "Config should accept null output format");
        assertNull(config.outputFormat, "Output format should be null as specified");
    }
}
