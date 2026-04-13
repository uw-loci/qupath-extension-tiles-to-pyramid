# QuPath Tiles-to-Pyramid Extension: Workflow Overview

This document describes the complete workflow for stitching tiled images into a pyramidal OME-TIFF using QuPath, particularly using the `TileConfigurationTxt` strategy.

## Workflow Steps

### 1. **MenuStartup** (Entry Point)

File: `MenuStartup.java`

Registers a new menu option in QuPath:

```java
fileNameStitching.setOnAction(e -> {
    logger.info("GUI menu click detected");
    StitchingGUI.createGUI();
});
```

### 2. **StitchingGUI** (User Dialog)

File: `StitchingGUI.java`

Displays a dialog to collect parameters (e.g., tile folder, compression, pixel size):

```java
String finalImageName = StitchingWorkflow.run(
    stitchingType,
    folderPath,
    outputPath,
    compressionType,
    pixelSize,
    downsample,
    matchingString,
    1.0,
    null
);
```

### 3. **StitchingWorkflow** (Orchestrator)

File: `StitchingWorkflow.java`

Executes the workflow logic:

```java
StitchingStrategy strategy = StitchingStrategyFactory.getStrategy(stitchingType);
List<TileMapping> mappings = strategy.prepareStitching(folderPath, pixelSizeMicrons, baseDownsample, matchingString);
SparseImageServer server = ImageAssembler.assemble(mappings, pixelSizeMicrons, zSpacingMicrons);
String written = PyramidImageWriter.write(server, outputPath, outBase, compressionType, baseDownsample);
```

### 4. **TileConfigurationTxtStrategy** (Mapping Tiles)

File: `TileConfigurationTxtStrategy.java`

- Parses `TileConfiguration.txt`.
- Matches TIFF files to tile configuration entries.
- Creates `TileMapping` objects.

### 5. **ImageAssembler** (Building Stitched Image)

File: `ImageAssembler.java`

Converts tile mappings into a stitched `SparseImageServer`:

```java
SparseImageServer server = ImageAssembler.assemble(mappings, pixelSizeMicrons, zSpacingMicrons);
```

### 6. **PyramidImageWriter** (OME-TIFF Output)

File: `PyramidImageWriter.java`

Writes the assembled image as a pyramidal OME-TIFF:

```java
String written = PyramidImageWriter.write(
    server,
    outputPath,
    outBase,
    compressionType,
    baseDownsample
);
```

### ChannelMergeImageServer

File: `ChannelMergeImageServer.java`

A lightweight, read-only multi-channel view over a list of N already-written source `ImageServer` instances. It does not re-stitch anything -- it just fans `readRegion` calls out to each source and assembles the returned tiles into a single multi-band `BufferedImage`, so the existing `PyramidImageWriter` can treat the composite as a normal server and write a standard multichannel OME-TIFF pyramid.

Key properties:

- `nChannels()` is the sum of `source.nChannels()` across all sources, with channels concatenated in source order.
- All sources must share pixel dimensions, pixel type, and pyramid structure -- the constructor validates width, height, and pixel type and throws `IllegalArgumentException` on a mismatch (differing resolution counts are logged as a warning and tolerated).
- `getBuilder()` returns `null` on purpose. The merged server is an intermediate assembly object, not a persistent source meant to round-trip through a QuPath project.
- `close()` closes all wrapped sources, aggregating any exceptions.

### 7. **ChannelMerger** (optional post-step)

File: `ChannelMerger.java`

Runs after per-channel stitching, when two or more per-channel pyramids have been produced for the same acquisition (for example the widefield immunofluorescence and BF+IF paths -- see `../QPSC/docs/multichannel-if-overview.md` for the broader pipeline context). It opens each per-channel OME-TIFF, wraps them in a `ChannelMergeImageServer`, and hands that to `PyramidImageWriter` to produce one combined multichannel output.

Signature:

```java
String outPath = ChannelMerger.merge(
    inputPaths,        // List<String>: per-channel pyramid files, in output channel order
    channelNames,      // List<String> or null: display names per output channel
    outputDirectory,   // String: directory to write the merged output into
    outputFilename,    // String: filename stem (no extension -- .ome.tif is appended)
    compression,       // String: e.g. "LZW"
    outputFormat       // StitchingConfig.OutputFormat: OME_TIFF or OME_ZARR
);
```

Behavior notes:

- The caller is responsible for supplying the ordered channel-name list. In the QPSC integration, `StitchingHelper.stitchChannelDirectories` passes the channel ids from the modality library so the output channel order matches the acquisition plan. Passing `null` falls back to each source's own channel name.
- All inputs must be compatible (same pixel dimensions, same pixel type, same pyramid structure). Incompatibility is detected in the `ChannelMergeImageServer` constructor and surfaces as an `IllegalArgumentException` rather than a silent misalignment.
- If fewer than two sources successfully open, `merge` logs a warning and returns `null` without writing anything. Missing input files are skipped with a warning, not treated as fatal.
- On success the source per-channel pyramids are left in place so the user can inspect each channel individually.
- Round-trip semantics are covered by `ChannelMergerTest` in `src/test/java/qupath/ext/basicstitching/`.

## Workflow Call Sequence

```
MenuStartup (menu click)
├─ StitchingGUI (collect input)
│  └─ processDialogResult
│     └─ StitchingWorkflow.run()
│        ├─ StitchingStrategyFactory.getStrategy()
│        ├─ TileConfigurationTxtStrategy.prepareStitching()
│        ├─ ImageAssembler.assemble()
│        └─ PyramidImageWriter.write()
└─ Final output: OME-TIFF file
```

## Component Responsibilities

| Component                  | Responsibility                        |
|----------------------------|---------------------------------------|
| **MenuStartup**            | Menu entry, GUI initialization        |
| **StitchingGUI**           | User input dialog                     |
| **StitchingWorkflow**      | Workflow orchestration                |
| **StitchingStrategyFactory**| Strategy selection                    |
| **TileConfigurationTxtStrategy** | Mapping tiles via configuration file|
| **ImageAssembler**         | Image server assembly                 |
| **PyramidImageWriter**     | Writing the pyramidal OME-TIFF        |
| **ChannelMergeImageServer**| Multi-channel view over N same-shape sources |
| **ChannelMerger**          | Optional post-step: combine per-channel pyramids into one multichannel OME-TIFF |

## Extending the Workflow

- **New strategies:** Implement `StitchingStrategy`.
- **Custom writers:** Substitute `PyramidImageWriter`.
- **Integration:** Callable from GUI, CLI, or scripting.

---

For detailed examples and documentation, refer to the [GitHub repository](https://github.com/MichaelSNelson/qupath-extension-tiles-to-pyramid).
