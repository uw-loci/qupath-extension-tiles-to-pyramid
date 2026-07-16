package qupath.ext.basicstitching.config;

import qupath.ext.basicstitching.registration.RegistrationMode;

/**
 * Configuration for stitching workflows.
 * Supports both OME-TIFF and OME-ZARR output formats.
 */
public class StitchingConfig {
    public final String stitchingType;
    public final String folderPath;
    public final String outputPath;
    public final String compressionType;
    public final double pixelSizeInMicrons;
    public final double baseDownsample;
    public final String matchingString;
    public final double zSpacingMicrons;
    public final double xFudgeFactor;
    public final double yFudgeFactor;
    public final OutputFormat outputFormat;

    /**
     * Optional output filename base. When set, the stitched output file will be named
     * {@code outputFilename + "_" + subdirName} instead of just {@code subdirName}.
     * This allows callers to include the sample name in the output filename.
     *
     * <p>Field kept public for source compatibility with existing callers; new
     * call sites should prefer {@link #setOutputFilename(String)} /
     * {@link #getOutputFilename()} so the dependency is visible to the
     * compiler. The workflow accesses this via the typed getter -- the
     * previous reflection-based access has been removed.
     */
    public String outputFilename;

    /**
     * When {@code true}, {@link #pixelSizeInMicrons} is a deliberate manual
     * override and must win over any pixel size read from acquisition metadata
     * (e.g. MicroManager {@code PixelSizeUm}). Set from the dialog's
     * "Manually edit pixel size" checkbox. Defaults to {@code false}, which
     * keeps the metadata authoritative.
     */
    private boolean manualPixelSizeOverride = false;

    /**
     * How tile positions should be determined: nominal stage coordinates (the default), a fresh
     * content-based solve, or a solve carried over from a sibling angle/channel.
     *
     * <p>Set via {@link #setRegistrationMode(RegistrationMode)}. Defaults to
     * {@link RegistrationMode.Disabled}, so existing callers keep the historical behaviour without
     * change.
     */
    private RegistrationMode registrationMode = RegistrationMode.disabled();

    /** @return how tile positions should be determined; never null. */
    public RegistrationMode getRegistrationMode() {
        return registrationMode;
    }

    /**
     * Set how tile positions should be determined.
     *
     * @param registrationMode the mode; null is treated as {@link RegistrationMode.Disabled}
     */
    public void setRegistrationMode(RegistrationMode registrationMode) {
        this.registrationMode = registrationMode == null ? RegistrationMode.disabled() : registrationMode;
    }

    /** @return whether {@link #pixelSizeInMicrons} should override metadata pixel size. */
    public boolean isManualPixelSizeOverride() {
        return manualPixelSizeOverride;
    }

    /** Set whether {@link #pixelSizeInMicrons} should override metadata pixel size. */
    public void setManualPixelSizeOverride(boolean manualPixelSizeOverride) {
        this.manualPixelSizeOverride = manualPixelSizeOverride;
    }

    /** @return the optional output filename base, or {@code null} if unset. */
    public String getOutputFilename() {
        return outputFilename;
    }

    /**
     * Set the optional output filename base. Typed setter that replaces
     * direct field access -- in production code this is the preferred path.
     */
    public void setOutputFilename(String outputFilename) {
        this.outputFilename = outputFilename;
    }

    /**
     * Output format options for stitched images.
     */
    public enum OutputFormat {
        /**
         * OME-TIFF format - single pyramidal TIFF file.
         * Traditional format with wide tool support.
         */
        OME_TIFF,

        /**
         * OME-ZARR format - directory-based cloud-native format.
         * Provides better compression, parallel writing, and cloud storage compatibility.
         */
        OME_ZARR;

        /** True if the initial stitch should produce ZARR output. */
        public boolean stitchAsZarr() {
            return this == OME_ZARR;
        }

        /** True if the final delivered format is OME-TIFF. */
        public boolean finalFormatIsTiff() {
            return this == OME_TIFF;
        }
    }

    /**
     * Full constructor with all parameters including fudge factors and output format.
     * Used primarily for Vectra workflows that need fudge factor adjustments.
     *
     * @param stitchingType Strategy type for stitching (e.g., "filename", "vectra")
     * @param folderPath Root folder containing tile subdirectories
     * @param outputPath Destination folder for stitched output
     * @param compressionType Compression algorithm (TIFF: "LZW", "JPEG", etc.; ZARR: "zstd", "lz4", etc.)
     * @param pixelSizeInMicrons Pixel size in microns for metadata
     * @param baseDownsample Initial downsampling factor
     * @param matchingString Pattern to match subdirectory names
     * @param zSpacingMicrons Z-spacing in microns for metadata
     * @param xFudgeFactor X-axis coordinate adjustment factor
     * @param yFudgeFactor Y-axis coordinate adjustment factor
     * @param outputFormat Output format (OME_TIFF or OME_ZARR)
     */
    public StitchingConfig(
            String stitchingType,
            String folderPath,
            String outputPath,
            String compressionType,
            double pixelSizeInMicrons,
            double baseDownsample,
            String matchingString,
            double zSpacingMicrons,
            double xFudgeFactor,
            double yFudgeFactor,
            OutputFormat outputFormat) {
        this.stitchingType = stitchingType;
        this.folderPath = folderPath;
        this.outputPath = outputPath;
        this.compressionType = compressionType;
        this.pixelSizeInMicrons = pixelSizeInMicrons;
        this.baseDownsample = baseDownsample;
        this.matchingString = matchingString;
        this.zSpacingMicrons = zSpacingMicrons;
        this.xFudgeFactor = xFudgeFactor;
        this.yFudgeFactor = yFudgeFactor;
        this.outputFormat = outputFormat;
    }

    /**
     * Constructor with fudge factors, defaulting to OME-TIFF format.
     * Maintained for backward compatibility.
     *
     * @deprecated Use constructor with explicit outputFormat parameter
     */
    @Deprecated
    public StitchingConfig(
            String stitchingType,
            String folderPath,
            String outputPath,
            String compressionType,
            double pixelSizeInMicrons,
            double baseDownsample,
            String matchingString,
            double zSpacingMicrons,
            double xFudgeFactor,
            double yFudgeFactor) {
        this(
                stitchingType,
                folderPath,
                outputPath,
                compressionType,
                pixelSizeInMicrons,
                baseDownsample,
                matchingString,
                zSpacingMicrons,
                xFudgeFactor,
                yFudgeFactor,
                OutputFormat.OME_TIFF);
    }

    /**
     * Constructor without fudge factors, with explicit output format.
     * Sets fudge factors to 1.0 (no adjustment).
     *
     * @param stitchingType Strategy type for stitching
     * @param folderPath Root folder containing tile subdirectories
     * @param outputPath Destination folder for stitched output
     * @param compressionType Compression algorithm
     * @param pixelSizeInMicrons Pixel size in microns
     * @param baseDownsample Initial downsampling factor
     * @param matchingString Pattern to match subdirectory names
     * @param zSpacingMicrons Z-spacing in microns
     * @param outputFormat Output format (OME_TIFF or OME_ZARR)
     */
    public StitchingConfig(
            String stitchingType,
            String folderPath,
            String outputPath,
            String compressionType,
            double pixelSizeInMicrons,
            double baseDownsample,
            String matchingString,
            double zSpacingMicrons,
            OutputFormat outputFormat) {
        this(
                stitchingType,
                folderPath,
                outputPath,
                compressionType,
                pixelSizeInMicrons,
                baseDownsample,
                matchingString,
                zSpacingMicrons,
                1.0,
                1.0,
                outputFormat);
    }

    /**
     * Constructor without fudge factors, defaulting to OME-TIFF format.
     * Sets fudge factors to 1.0 (no adjustment).
     * Maintained for backward compatibility.
     *
     * @deprecated Use constructor with explicit outputFormat parameter
     */
    @Deprecated
    public StitchingConfig(
            String stitchingType,
            String folderPath,
            String outputPath,
            String compressionType,
            double pixelSizeInMicrons,
            double baseDownsample,
            String matchingString,
            double zSpacingMicrons) {
        this(
                stitchingType,
                folderPath,
                outputPath,
                compressionType,
                pixelSizeInMicrons,
                baseDownsample,
                matchingString,
                zSpacingMicrons,
                1.0,
                1.0,
                OutputFormat.OME_TIFF);
    }
}
