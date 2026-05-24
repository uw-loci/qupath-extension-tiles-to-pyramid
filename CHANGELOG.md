# Changelog

All notable changes to the Tiles to Pyramid QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.2] - 2026-05-23

### Fixed
- **MMStack stitches showed the same tile content in every grid cell**: MicroManager MMStack OME-TIFFs are a multifile dataset -- every per-position `.ome.tif` carries OME-XML that describes the whole dataset, so when BioFormats opens any one file it presents it as N series (one per position). `ImageAssembler` was always picking `serverBuilders.get(0)`, which corresponds to the dataset's first position regardless of which physical file was opened. The result: 9 copies of Pos[0]'s pixels at 9 different grid locations, with correct positioning but identical content. `MicroManagerMetadataStrategy` now records each tile's series index (from its position in `Summary.StagePositions[]`), and `ImageAssembler` uses `serverBuilders.get(mapping.seriesIndex)`. Verified against a real 3x3 MMStack: 9 distinct pixel sums in the output now match the 9 per-series sums of the source dataset.

### Changed
- **`TileMapping` gains a `seriesIndex` field** (default 0; previous 3-arg constructor preserved for back-compat). Used by `ImageAssembler` to pick the correct series when a tile file exposes multiple series.

## [0.4.1] - 2026-05-23

### Added
- **MMStack pixel size auto-fill in StitchingGUI**: When the Stitch Images dialog opens or a folder is selected, the pixel-size field is auto-populated from the first `*_metadata.txt` sidecar's `FrameKey-0-0-0.PixelSizeUm`. A small label next to the field reports the source (`(from MMStack metadata)` / `(no MMStack metadata - tick 'Manually edit' to set)` / `(manual override)`).
- **"Manually edit pixel size" checkbox**: The pixel size field is now uneditable by default so users cannot accidentally type over the auto-detected value. Ticking the checkbox unlocks the field; unticking it re-runs auto-fill from the current folder.

### Changed
- **MicroManager strategy treats MMStack metadata as authoritative for pixel size**: `MicroManagerMetadataStrategy.prepareStitching` now reads `FrameKey-0-0-0.PixelSizeUm` from the first sidecar and uses it for the um->px conversion regardless of the value passed in by the caller. The caller value is kept as a fallback for sidecars that don't record `PixelSizeUm`. Divergent values are logged so the choice is visible. This means an incorrect (or zero) value typed in the dialog cannot silently produce a misaligned stitch when the metadata has the correct answer.

## [0.4.0] - 2026-05-23

### Added
- **New MicroManager metadata stitching strategy**: Read tile positions directly from MicroManager 2 `*_metadata.txt` JSON sidecars alongside `*.ome.tif` tiles. Uses authoritative per-tile stage coordinates (FrameKey-0-0-0.XPositionUm / YPositionUm) with fallback to Summary.StagePositions labels. Supports stage-coordinate flipping via `flipStitchingX` and `flipStitchingY` flags for stage-inverted scopes.

### Fixed
- **All-black upper pyramid levels in OME-TIFF output**: Fixed tile-overflow bug where pyramidalized servers with tile sizes not evenly divisible by the writer's tile size produced corrupted (all-black) higher pyramid levels. Now forces safe iteration that never overflows by using a 1024px preferred tile size during pyramidalization, bypassing an unsafe optimization in OMEPyramidWriter when tile dimensions mismatch.
- **Removed JVM-wide OME-TIFF write gate**: The serializing semaphore added in 0.3.0 as a workaround for BioFormats `TiffWriter` concurrency hazards (NPE at high pyramid levels) has been removed after diagnostic testing confirmed concurrent writes are now safe. 64 parallel J2K-compressed writes across 8 trials with multi-level pixel verification showed zero corruption, NPEs, or failed opens. Concurrent multi-angle stitches can now proceed without waiting for serialization.

### Notes
- Releases v0.3.1 and v0.3.2 were documented but never tagged or published; their content is included in v0.4.0.

## [0.3.2] - 2026-05-11

### Added
- **New API for detailed stitching results**: `StitchingResult` record and `runDetailed()` method allow callers to inspect per-subdirectory success/failure, improving integration with multi-angle workflows (e.g., PPM acquisitions where one angle may legitimately fail while others succeed).
- Typed getter/setter methods on `StitchingConfig.outputFilename` for type-safe programmatic configuration.

### Fixed
- **RGB channel validation**: Added explicit check that server is both `isRGB()` AND `nChannels() == 3` before calling `channelsInterleaved()`. Prevents silent channel loss on misclassified multi-channel images (e.g., 4-channel fluorescence incorrectly flagged as RGB).
- **TIFF write gate timeout**: Added 30-minute timeout to prevent indefinite hangs when the shared write gate is wedged by a stalled thread (network drive hang, antivirus file lock, etc.). Failed stitches now report a clear error instead of blocking all subsequent operations.
- **Windows file-lock retry on rename**: The just-finished multi-GB temp file is a common antivirus scan target the moment its handle closes. Added retry logic with backoff (5s, 15s, 30s) to `renameTempToFinal()` to match the existing write-phase retry behavior, improving reliability on Windows systems with aggressive scanning.
- **Improved resource cleanup**: Wrapped all ImageServer close operations in try-finally blocks to ensure cleanup even when write operations throw exceptions. Demoted close-time exceptions to warnings after a successful write (data is already on disk).

## [0.3.1] - 2026-05-11

### Fixed
- **OME-TIFF write robustness on Windows**: Added retry logic for file-lock failures that occur when antivirus, Windows Search indexer, or Explorer preview scans the temporary file during BioFormats' IFD-patching step. Failed writes now retry with backoff (5s, 15s, 30s) before reporting failure, with diagnostic messages directing users to exclude SlideImages folder from real-time scanning.

## [0.3.0] - 2026-05-07

### Added
- Automated GitHub release workflow + `notify-catalog` workflow that auto-bumps `qupath-catalog-qpsc` on release.
- **ChannelMerger**: post-stitch helper that combines N single-channel pyramids into one multichannel OME-TIFF, used after per-channel stitching for widefield IF and BF+IF acquisitions. Supports explicit per-channel colors.
- **ChannelMergeImageServer**: minimal multi-channel `ImageServer` adapter used internally by `ChannelMerger`; fans tile reads out to N same-shape source servers and concatenates their channels in source order.
- **OME_TIFF_VIA_ZARR output format**: accelerated stitching path that writes through Zarr.
- `flipStitchingX` flag to mirror `flipStitchingY` in `TileConfigurationTxtStrategy`.
- Tooltips on field labels in StitchingGUI that previously had none.
- SpotBugs static analysis (effort=MAX, confidence=HIGH).

### Changed
- Build upgraded to QuPath 0.7.0 / Java 25; Gradle wrapper bumped to 9.2.1.
- Foojay toolchain resolver added so Gradle auto-fetches the JDK.
- `PyramidImageWriter` matches server + writer pyramid levels; only enables `channelsInterleaved()` for RGB sources.
- `OMEZarrWriter` API call updated for QuPath 0.7.0.
- Global semaphore added to serialize OME-TIFF pyramid writes.

### Fixed
- `PyramidImageWriter.scaledDownsampling` scale-factor misuse.
- Asset URL field name in catalog notify (gh CLI uses `url` not `browser_download_url`).

## [0.2.0] - 2026-03-15

### Added
- **Direct tile stitcher for large acquisitions (500+ tiles)**: New memory-efficient code path that bypasses SparseImageServer entirely, preventing OOM errors that occurred with 1600+ tiles even on 64 GB systems
- **OME-ZARR output format**: Cloud-native directory-based format with Blosc compression (zstd, lz4, lz4hc, blosclz, zlib)
- **CompositorImageServer**: Read-only ImageServer backed by spatial index and bounded reader pool, enabling memory-efficient OME-TIFF output for large acquisitions via the existing PyramidImageWriter
- **TileSpatialIndex**: Grid-based O(1) tile lookup replacing the O(N) linear scan in SparseImageServer
- **TileReaderPool**: LRU cache of open ImageReader instances (max 8), bounding file handle usage regardless of tile count
- **ChunkCompositor**: On-demand pixel compositing from source tiles with white background support for RGB
- **ZarrOutputWriter**: Direct JZarr chunk writing with NGFF 0.4 metadata for the ZARR code path
- **PyramidLevelGenerator**: Generates downsampled pyramid levels by 2x area averaging from written chunks
- **BlendStrategy interface**: Extensible overlap handling (initial: OverwriteBlendStrategy matching existing behavior)
- GUI output format selection (OME-TIFF or OME-ZARR)
- Automatic compression mapping from TIFF types to ZARR equivalents
- Multi-threaded parallel tile writing for ZARR format

### Changed
- `PyramidImageWriter.createZarrCompressor()` changed from private to public for reuse by direct stitcher
- `StitchingWorkflow.run()` now routes to DirectTileStitcher when tile count >= 500
- Large acquisitions automatically use the direct path; small acquisitions use the existing SparseImageServer path unchanged

### Performance
- Memory usage for 1600-tile acquisitions: ~40 MB steady state (vs 2-4+ GB with SparseImageServer)
- Tile lookup: O(1) via spatial grid (vs O(N) linear scan per tile request)
- Maximum 8 open file handles regardless of tile count (vs 1600)
- Both OME-TIFF and OME-ZARR output supported for large acquisitions

## Guidelines for Release Notes

When creating a new release, add a section above with the version number and date:

```markdown
## [1.0.0] - 2026-01-XX

### Added
- Feature descriptions

### Changed
- Modification descriptions

### Fixed
- Bug fix descriptions

### Removed
- Removed feature descriptions
```

### Categories

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Now removed features
- **Fixed** - Bug fixes
- **Security** - Security improvements
