# QuPath Basic Stitching Extension

> **Part of the [QPSC (QuPath Scope Control)](https://github.com/uw-loci/qupath-extension-qpsc) system.**
> For complete installation and setup instructions, see the [QPSC Installation Guide](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/INSTALLATION.md).

A basic image stitching extension for QuPath that combines multiple image tiles into seamless pyramidal images. This extension supports multiple stitching strategies, dual output formats (OME-TIFF and OME-ZARR), and is designed for high-throughput microscopy workflows.

## Features

- **Multiple Stitching Strategies**: Support for filename-based coordinates, TileConfiguration.txt files, Vectra metadata, and MicroManager MMStack metadata
- **Dual Output Formats**: Choose between traditional OME-TIFF or cloud-native OME-ZARR
- **Pyramidal Output**: Generates multi-resolution pyramids for efficient viewing at all scales
- **Flexible Compression**: Supports various compression formats (TIFF: JPEG, LZW, ZIP; ZARR: zstd, lz4, blosc)
- **Cloud-Native ZARR**: Directory-based format optimized for cloud storage and parallel access
- **Batch Processing**: Process multiple slides simultaneously with matching criteria
- **Multi-subdirectory Support**: Automatically creates separate outputs for each matched subdirectory
- **Robust Error Handling**: Comprehensive logging and validation for troubleshooting
- **Memory Efficient**: Direct tile stitcher for 500+ tiles uses ~40 MB steady state (vs 2-4+ GB)
- **Parallel Writing**: ZARR format utilizes multi-threaded tile writing for faster output
- **Large Acquisition Support**: Handles 1600+ tiles without OOM via spatial indexing and bounded reader pool
- **Multichannel Merge**: Combine N same-shape single-channel pyramids (from per-channel stitching) into one multichannel OME-TIFF via `ChannelMerger` / `ChannelMergeImageServer`

## Requirements

- **QuPath**: Version 0.7.0 or greater
- **Java**: Java 25 or higher
- **Memory**: Recommended 8GB+ RAM for large image datasets

## Installation

### Option 1: Download Release
1. Download the latest `.jar` file from the [Releases](../../releases) page
2. Copy the JAR file to your QuPath extensions directory:
   - **Windows**: `%USERPROFILE%/QuPath/extensions`
   - **macOS**: `~/QuPath/extensions`
   - **Linux**: `~/QuPath/extensions`
3. Restart QuPath

Alternatively, drag and drop the extension into QuPath. 

### Option 2: Build from Source
```bash
git clone https://github.com/yourusername/qupath-basic-stitching.git
cd qupath-basic-stitching
./gradlew build
# Copy build/libs/qupath-extension-basic-stitching-*.jar to your QuPath extensions directory
```
Developers of qpsc may want to also run the following to enable working with qpsc in IntelliJ.
```
./gradlew publishToMavenLocal
```


<details>
<summary><h2>Usage</h2></summary>

### Accessing the Extension
1. Open QuPath
2. Navigate to **Extensions** -> **Basic Stitching** -> **Stitch Images**
3. The stitching dialog will open

### Stitching Strategies

#### 1. Filename[x,y] with Coordinates in Microns
For images with coordinates embedded in filenames:
```
image_tile[1000,2000].tif
image_tile[1500,2000].tif
image_tile[1000,2500].tif
```

**Usage:**
- Select folder containing subdirectories with tiles
- Coordinates in brackets represent physical positions in microns
- Extension automatically calculates tile positions and overlaps

#### 2. TileConfiguration.txt File
For ImageJ/Fiji tile configuration format:
```
# Define the number of dimensions we are working on
dim = 2

# Define the image coordinates
tile_001.tif; ; (0.0, 0.0)
tile_002.tif; ; (1024.0, 0.0)
tile_003.tif; ; (0.0, 1024.0)
tile_004.tif; ; (1024.0, 1024.0)
```

**Usage:**
- Each subdirectory must contain a `TileConfiguration.txt` file
- Coordinates represent pixel positions
- Automatically scaled based on pixel size and downsample settings

**Batch Processing Multiple Subdirectories:**
When the matching string matches multiple subdirectories, each subdirectory is stitched independently:
```
input_folder/bounds/
+-- -5.0/
|   +-- TileConfiguration.txt
|   +-- [tile files]
+-- 0.0/
|   +-- TileConfiguration.txt
|   +-- [tile files]
+-- 5.0/
    +-- TileConfiguration.txt
    +-- [tile files]
```
With matching string "." results in:
- `-5.0.ome.tif`
- `0.0.ome.tif`  
- `5.0.ome.tif`

#### 3. Vectra Tiles with Metadata
For Akoya/PerkinElmer Vectra imaging systems:
- Reads positioning information directly from TIFF metadata tags
- Uses `TAG_X_POSITION`, `TAG_Y_POSITION`, and resolution tags
- No additional configuration files required

#### 4. MicroManager Metadata (MMStack)
For MicroManager 2 multi-position acquisitions with sidecar metadata:
- Reads tile positions from `*_metadata.txt` JSON sidecar files
- Uses authoritative per-tile stage coordinates (FrameKey-0-0-0.XPositionUm / YPositionUm)
- Falls back to Summary.StagePositions labels if a sidecar is missing or malformed
- Auto-detects pixel size from `FrameKey-0-0-0.PixelSizeUm` in the metadata
- No additional configuration files required

**Usage:**
- Tiles and their `_metadata.txt` sidecars should be in the same folder
- Example filenames: `acq_MMStack_Pos-0_000.ome.tif` and `acq_MMStack_Pos-0_000_metadata.txt`
- For stage-inverted scopes, use the `flipStitchingX` and `flipStitchingY` flags to negate coordinates

**Pixel Size Auto-fill:**
- When you open the Stitch Images dialog or select an input folder, the pixel-size field is automatically filled from the first `*_metadata.txt` sidecar's `FrameKey-0-0-0.PixelSizeUm`
- The field is **locked by default** to prevent accidental edits — a label shows the source (`(from MMStack metadata)` / `(no MMStack metadata - tick 'Manually edit' to set)` / `(manual override)`)
- Tick **"Manually edit pixel size"** if you need to override the detected value; unticking it restores the auto-detected value
- The stitching strategy treats the MMStack metadata as authoritative for pixel size — an incorrect dialog value cannot silently produce misaligned stitches when the metadata has the correct answer

### Configuration Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| **Input Folder** | Root directory containing image subdirectories | Required |
| **Output Folder** | Directory for stitched output files | Required |
| **Pixel Size (um)** | Physical size of each pixel in microns. Auto-detected from MMStack `*_metadata.txt` sidecars when available; field is locked by default. Tick "Manually edit pixel size" to override. | 0.5 (or detected from metadata) |
| **Base Downsample** | Downsampling factor for output | 1.0 |
| **Compression** | Compression algorithm (TIFF: LZW, JPEG; ZARR: zstd, lz4) | LZW |
| **Output Format** | OME-TIFF (single file) or OME-ZARR (directory) | OME-TIFF |
| **Matching String** | Filter subdirectories by name pattern. Use "." to process all subdirectories separately | "" (all) |
| **Z-Spacing (um)** | Z-axis spacing for 3D datasets | 1.0 |

### Output Format Options

#### OME-TIFF (Traditional)
- **Structure**: Single pyramidal TIFF file
- **Compatibility**: Widely supported by QuPath, ImageJ, and most imaging software
- **Use Case**: General purpose, local storage, maximum compatibility
- **Extension**: `.ome.tif`
- **Compression**: LZW, JPEG, JPEG-2000, Uncompressed
- **Best For**: Desktop workflows, maximum software compatibility

#### OME-ZARR (Cloud-Native)
- **Structure**: Directory containing chunked arrays
- **Compatibility**: QuPath 0.6.0+, napari, Python imaging libraries
- **Use Case**: Cloud storage, large datasets, parallel processing
- **Extension**: `.ome.zarr` (directory)
- **Compression**: zstd (best balance), lz4 (fastest), lz4hc, blosclz, zlib
- **Best For**: Cloud storage, collaborative access, very large images (> 10GB)

**Key Advantages of ZARR:**
1. **Faster Writing**: Multi-threaded tile writing (typically 2-3x faster than TIFF)
2. **Better Compression**: Blosc/zstd compression achieves 20-30% smaller files than LZW
3. **Cloud-Optimized**: Native support for S3, Azure Blob, Google Cloud Storage
4. **Partial Access**: Read specific regions without downloading entire file
5. **Parallel Processing**: Multiple processes can read different regions simultaneously
6. **Progress Tracking**: Per-tile progress callbacks for better user feedback

**Compression Recommendations:**
- **zstd** (default): Best balance of speed and compression ratio
- **lz4**: Fastest compression/decompression, slightly larger files
- **lz4hc**: Better compression than lz4, slower writing
- **zlib**: Compatible with standard zip compression
- **blosclz**: Optimized for binary data

**When to Use ZARR:**
- Stitched images > 5GB in size
- Cloud storage or collaborative workflows
- High-throughput batch processing
- Need for parallel data access
- Long-term archival with cloud backup

**When to Use OME-TIFF:**
- Need maximum software compatibility
- Working with legacy analysis pipelines
- Smaller images (< 2GB)
- Desktop-only workflows
- Sharing with users without ZARR support

### Example Workflows

#### Basic Stitching (OME-TIFF)
```java
// Programmatic usage example - traditional OME-TIFF output
StitchingConfig config = new StitchingConfig(
    "Filename[x,y] with coordinates in microns",  // Strategy
    "/path/to/input/folder",                      // Input path
    "/path/to/output/folder",                     // Output path
    "LZW",                                        // Compression
    0.5,                                          // Pixel size (um)
    1.0,                                          // Base downsample
    "slide",                                      // Matching string
    1.0,                                          // Z-spacing (um)
    StitchingConfig.OutputFormat.OME_TIFF         // Output format
);
String result = StitchingWorkflow.run(config);
```

#### Cloud-Native ZARR Output
```java
// High-performance ZARR output with fast compression
StitchingConfig config = new StitchingConfig(
    "Coordinates in TileConfiguration.txt file",
    "/data/microscopy/slides",
    "/data/output/stitched",
    "zstd",                                       // ZARR compression (fast + good ratio)
    0.25,                                         // 0.25 um/pixel
    1.0,                                          // Base downsample
    ".",                                          // Process all subdirectories
    1.0,                                          // Z-spacing
    StitchingConfig.OutputFormat.OME_ZARR         // ZARR format
);
String result = StitchingWorkflow.run(config);
// Output: multiple .ome.zarr directories, one per subdirectory
```

#### Batch Processing with Downsampling (TIFF)
```java
StitchingConfig config = new StitchingConfig(
    "Coordinates in TileConfiguration.txt file",
    "/data/microscopy/slides",
    "/data/output/stitched",
    "JPEG",                                       // TIFF compression
    0.25,                                         // 0.25 um/pixel
    4.0,                                          // 4x downsample
    "H&E",                                        // Process only H&E slides
    1.0,
    StitchingConfig.OutputFormat.OME_TIFF         // Traditional TIFF
);
String result = StitchingWorkflow.run(config);
```

### Special Use Cases

#### Rotation Angle Processing
For workflows with multiple rotation angles stored in separate folders:
```
bounds/
+-- -5.0/
|   +-- TileConfiguration.txt
|   +-- [9 tiles]
+-- 0.0/
|   +-- TileConfiguration.txt
|   +-- [9 tiles]
+-- 5.0/
    +-- TileConfiguration.txt
    +-- [9 tiles]
```
Using matching string "." will create three separate stitched images, one for each angle.

</details>

<details>
<summary><h2>Directory Structure</h2></summary>

### Input Directory Structure
```
input_folder/
+-- slide001_tumor/
|   +-- tile_001[0,0].tif
|   +-- tile_002[1000,0].tif
|   +-- tile_003[0,1000].tif
+-- slide002_normal/
|   +-- tile_001[0,0].tif
|   +-- tile_002[1000,0].tif
+-- slide003_control/
    +-- TileConfiguration.txt
    +-- image_001.tif
    +-- image_002.tif
```

### Output Structure
Output files are named based on the subdirectory being processed:
- When matching string equals folder name: uses folder name
- When processing multiple subdirectories: each gets its own output file named after the subdirectory

```
output_folder/
+-- slide001_tumor.ome.tif
+-- slide002_normal.ome.tif
+-- slide003_control.ome.tif
```

When processing subdirectories:
```
output_folder/
+-- -5.0.ome.tif
+-- 0.0.ome.tif
+-- 5.0.ome.tif
```

</details>

<details>
<summary><h2>Performance Optimization</h2></summary>

### Memory Management
- **Large Datasets**: Use higher downsample values (2x, 4x) for initial processing
- **RAM Usage**: Monitor memory usage; increase JVM heap size if needed:
  ```bash
  java -Xmx16G -jar QuPath.jar
  ```

### Processing Speed
- **Parallel Processing**: Extension automatically uses multiple CPU cores
- **SSD Storage**: Use SSD drives for input/output to improve I/O performance
- **Network Storage**: Avoid network drives for temporary processing

### Tile Size Recommendations
- **Small Tiles** (< 2048px): Fast processing, more metadata overhead
- **Large Tiles** (> 8192px): Slower processing, less overhead
- **Optimal Range**: 2048-4096 pixels per tile dimension

</details>

<details>
<summary><h2>Troubleshooting</h2></summary>

### Common Issues

#### "No valid tile configurations found"
- **Cause**: Directory structure doesn't match expected format
- **Solution**: Verify subdirectory naming and tile file patterns
- **Check**: Enable debug logging to see which directories are processed

#### "Could not retrieve dimensions for image"
- **Cause**: Corrupted or unsupported image format
- **Solution**: Verify TIFF files are valid and readable
- **Check**: Test individual files in QuPath or ImageJ

#### "Mismatch between tile configuration file names"
- **Cause**: TileConfiguration.txt references files not present in directory
- **Solution**: Ensure all referenced files exist and names match exactly
- **Check**: Case sensitivity on Linux/macOS systems

#### Multiple subdirectories stitched into one file
- **Cause**: Using substring matching that matches unintended folders
- **Solution**: Use exact matching or "." to process each subdirectory separately
- **Example**: "5.0" matches both "5.0" and "-5.0"; use "." instead

#### Out of Memory Errors
- **Cause**: For < 500 tiles, insufficient JVM heap space. For 500+ tiles, the direct stitcher activates automatically and uses ~40 MB regardless of tile count.
- **Solution**: For smaller acquisitions, increase heap size or use higher downsample values. For large acquisitions (500+ tiles), the memory-efficient direct path is used automatically.
- **Command**: `java -Xmx16G -jar QuPath.jar`

### Debug Logging
Enable detailed logging by setting log level to DEBUG:
```properties
# In QuPath logging configuration
logger.qupath.ext.basicstitching=DEBUG
```

### Validation Steps
1. **File Integrity**: Verify all input TIFF files open correctly
2. **Coordinate Extraction**: Check log output for parsed coordinates
3. **Directory Matching**: Confirm subdirectories match the filtering criteria
4. **Output Verification**: Open resulting OME-TIFF in QuPath to verify stitching quality

</details>

<details>
<summary><h2>API Documentation</h2></summary>

### Core Classes

#### `StitchingWorkflow`
Main orchestration class for stitching operations.

**Key Methods:**
- `run(StitchingConfig)`: Execute workflow, returns last successful output path (backward compatible)
- `runDetailed(StitchingConfig)`: Execute workflow, returns detailed `StitchingResult` with per-subdirectory success/failure tracking

**Example - Backward Compatible (Single Output):**
```java
StitchingConfig config = new StitchingConfig(
    "Filename[x,y] with coordinates in microns",
    "/path/to/input",
    "/path/to/output",
    "LZW",
    0.5,
    1.0,
    ".",
    1.0,
    StitchingConfig.OutputFormat.OME_TIFF
);
String lastPath = StitchingWorkflow.run(config);
```

**Example - Detailed Results (Multi-Angle Workflows):**
```java
// For multi-angle acquisitions where one angle may legitimately fail
StitchingResult result = StitchingWorkflow.runDetailed(config);
if (result.hasAnyOutput()) {
    result.outputs().forEach(path -> logger.info("Stitched: {}", path));
}
if (result.failureCount() > 0) {
    logger.warn("Failed subdirectories: {}", result.failedSubdirs());
}
```

#### `StitchingResult`
Detailed result record returned by `runDetailed()`.

**Fields:**
- `outputs()`: List of successfully written file paths (insertion order preserved)
- `successCount()`: Number of successfully stitched subdirectories
- `failureCount()`: Number of failed subdirectories
- `failedSubdirs()`: Names of subdirectories that failed to stitch

**Convenience Methods:**
- `hasAnyOutput()`: Returns true if at least one subdirectory succeeded
- `lastOutput()`: Returns the last successful output path (for backward compatibility)

#### `StitchingConfig`
Configuration class for stitching operations.

**Typed Accessors for Output Filename:**
- `getOutputFilename()`: Retrieve the configured output filename base
- `setOutputFilename(String)`: Set the output filename base (preferred over direct field access)

#### `StitchingImplementations`
Main coordination class for stitching operations.

**Key Methods:**
- `stitchCore()`: Primary stitching method
- `setStitchingStrategy()`: Configure stitching algorithm

#### Strategy Classes
- `FileNameStitchingStrategy`: Parse coordinates from filenames
- `TileConfigurationTxtStrategy`: Read ImageJ tile configurations  
- `VectraMetadataStrategy`: Extract Vectra TIFF metadata

### Extension Points
The extension supports custom stitching strategies by implementing the `StitchingStrategy` interface:

```java
public interface StitchingStrategy {
    List<Map<String, Object>> prepareStitching(
        String folderPath, 
        double pixelSizeInMicrons,
        double baseDownsample, 
        String matchingString
    );
}
```

</details>

<details>
<summary><h2>Contributing</h2></summary>

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

### Development Setup
```bash
git clone https://github.com/yourusername/qupath-basic-stitching.git
cd qupath-basic-stitching
./gradlew build
./gradlew test
```

### Code Style
- Follow standard Java conventions
- Add comprehensive logging for debugging
- Include unit tests for new functionality
- Update documentation for API changes

</details>

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

- **General support and feature requests**: post on the [image.sc forum](https://forum.image.sc/) with the `#qupath` tag and mention `@Mike_Nelson` to flag the topic for my attention
- **Bug reports**: file via [GitHub Issues](../../issues)
- **Discussions**: [GitHub Discussions](../../discussions)

## Acknowledgments

The OME-ZARR writing approach in this extension was informed by Leo Leplat's ZARR implementation in QuPath core (`qupath.lib.images.writers.ome.zarr`) and the [qupath-extension-stitching](https://github.com/qupath/qupath-extension-stitching).

## AI-Assisted Development

This project was developed with assistance from [Claude](https://claude.ai) (Anthropic). Claude was used as a development tool for code generation, architecture design, debugging, and documentation throughout the project.

<details>
<summary><h2>Changelog</h2></summary>

### Version 0.2.0 (Direct Stitcher + ZARR Support)
- **NEW**: Memory-efficient direct tile stitcher for large acquisitions (500+ tiles)
  - Bypasses SparseImageServer entirely -- prevents OOM with 1600+ tiles
  - TileSpatialIndex: O(1) tile lookup via grid (replaces O(N) linear scan)
  - TileReaderPool: LRU cache with max 8 open files (vs 1600 open servers)
  - ChunkCompositor: On-demand pixel compositing from source tiles
  - ~40 MB steady-state memory regardless of tile count
- **NEW**: OME-ZARR output format support (cloud-native, directory-based)
  - Direct JZarr chunk writing with NGFF 0.4 metadata
  - PyramidLevelGenerator: 2x area-averaged downsampling from written chunks
  - Blosc compression: zstd, lz4, lz4hc, blosclz, zlib
- **NEW**: CompositorImageServer -- enables OME-TIFF output for large acquisitions
  - Read-only ImageServer backed by compositor + spatial index
  - Feeds existing PyramidImageWriter/OMEPyramidWriter with bounded memory
- **NEW**: BlendStrategy interface for extensible overlap handling
- **ENHANCED**: GUI now includes output format selection dropdown
- **ENHANCED**: Automatic compression mapping (TIFF types to ZARR equivalents)
- Backward compatible: acquisitions < 500 tiles use existing SparseImageServer path

### Version 0.1.0
- Initial Java conversion from Groovy implementation
- Support for QuPath 0.6.0+
- Three stitching strategies implemented
- Multi-subdirectory batch processing with separate outputs
- Comprehensive error handling and logging
- Performance optimizations for large datasets

</details>

---

