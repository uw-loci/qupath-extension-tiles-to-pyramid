# Changelog

All notable changes to the Tiles to Pyramid QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.1] - 2026-08-02

### Added
- **"Solve tile overlaps (content-based registration)" toggle in the stitching dialog.** A checkbox
  turns content-based registration on or off for a run, mirroring the equivalent QPSC preference so
  the feature is reachable when stitching a folder directly through the extension. Off by default
  (nominal stage placement, the faster path); the choice is remembered. When on, the run writes a
  `TileRegistration.txt` solution beside the tiles.

### Fixed
- **Content-based registration damaged the tiles it could not measure, and searched too far for a
  match.** Two robustness fixes after on-scope testing against real H&E mosaics, where most seams
  closed perfectly but a few showed a doubled edge with a white gap:
  - A tile whose overlap bands were all too low-texture to register -- a near-blank sample-edge
    tile -- was pinned to its nominal stage position. But real acquisitions carry a smooth
    systematic error (a ~0.5% pixel-size/stage-step mismatch accumulates to tens of pixels across a
    wide grid), so the rest of the grid was corrected by ~20 px while the unmeasurable tile stayed
    at nominal -- stranding it a doubled ~20 px from its neighbours. Such a tile now inherits the
    correction field its registered neighbours define (a diffusion fill over the grid); a wholly
    disconnected region still falls back to nominal.
  - The pairwise NCC search covered the full overlap (hundreds of pixels), but a *per-edge* offset
    -- one stage step of backlash and drift -- is small, ~15 px p90 on real data. That over-wide
    window let a low-texture band lock onto a wrong peak and inflated the ambiguity-rejection rate.
    The per-edge search is now bounded to the plausible step error (2% of a tile, with a floor for
    small tiles) and decoupled from the per-tile clamp, which stays wide for the legitimately large
    *cumulative* correction.
- **Shipped a Linux-only blosc native to every platform.** The release jar bundled
  `linux-x86-64/libblosc.so` and no `blosc.dll`/`.dylib`, because the blosc artifact's platform is
  chosen from the **build machine's** OS and releases are built on ubuntu-latest. OME-ZARR only
  ever worked on Windows and macOS because QuPath's own installation supplies the correct native
  for the platform it is installed on; our bundled copy was inert, wrong-platform dead weight, and
  the whole arrangement was one QuPath packaging change away from breaking. Taking jzarr as
  provided means the native now always matches the host, by construction.
- **Bundled an older duplicate of an extension QuPath already ships.**
  `qupath-extension-bioformats` was an `implementation` dependency pinned at 0.6.0-rc4, so the jar
  carried a stale copy of a QuPath extension that QuPath 0.7.0 provides at 0.7.0 in its own `lib/`
  -- the same duplicate-class hazard the other `shadow(...)` entries exist to avoid.

### Changed
- **Minimum QuPath version now 0.7.0** (was 0.6.0). This aligns with the shift to provided
  dependencies: bioformats, jzarr, and other QuPath-supplied libraries are versioned for 0.7.0.
- **`qupath-extension-bioformats` and `jzarr` are now provided rather than bundled**, matching
  every other QuPath-supplied dependency (and what `qupath-extension-qpsc` already does). Bioformats
  is version-matched to QuPath 0.7.0. **The shadow jar drops from 163 MB to 0.2 MB** (38,670
  entries to 85). Verified with `jdeps` against a real QuPath install that every class this
  extension references resolves to a QuPath-shipped jar, and smoke-tested in QuPath itself: the
  0.2 MB extension loads and completes both an OME-TIFF stitch (with registration) and an OME-ZARR
  stitch, the latter exercising the blosc native from QuPath's `lib/`.

### Added
- **Stitch benchmark harness** (`StitchBenchmarkTest`, developer-only): a reproducible stitch of a fixed synthetic tile grid reporting wall time and peak heap for both output formats. Opt-in via `./gradlew test --tests "*StitchBenchmarkTest*" -PstitchBench`, so it stays out of the normal suite. No production code changed. It exists so stitching performance work is decided on measurements rather than on what looks slow -- the first three things it measured all contradicted the obvious guess:
  - Compositing is only **33%** of an OME-TIFF stitch and **21%** of an OME-ZARR one; the rest is the write path. That caps any composite-side optimization at ~1.45x / ~1.25x even with perfect parallel scaling.
  - Source tiles are decoded **~19x each** on the TIFF path, but caching decoded tiles to remove that measured **48% slower** and used ~25% more heap. Acquisition tiles are uncompressed, so partial region reads are already cheap; decode *count* is not decode *cost*.
  - Every pyramid level re-composites the whole mosaic from full-resolution source tiles, because `PyramidGeneratingImageServer` selects its source level from the wrapped (single-resolution) `CompositorImageServer`. Levels 1-3 cost 4.6 s of a 9.0 s stitch to produce 33% of the pixels. A QuPath tile cache does not help. This is the one real remaining prize, and it is recorded in the harness javadoc for whoever takes it on.

## [0.6.0] - 2026-07-16

### Added
- **Content-based tile registration (real stitching)**: tiles can now be positioned by correlating the image content in their overlap instead of trusting nominal stage coordinates alone. Stages have per-move error -- backlash, encoder resolution, thermal drift over a long acquisition -- and nominal placement leaves all of it in the output as seams or soft double images. Registration is **off by default**; set a `RegistrationMode` on `StitchingConfig` to enable it.
  - **Two modes, because one solve must serve every angle.** Polarization angles and fluorescence channels are captured at the *same* stage position for each tile, so solving each independently would misregister them against each other -- worse than a shared nominal grid. `RegistrationMode.Solve` measures one reference subdirectory and writes `TileRegistration.txt`; `RegistrationMode.Apply` reuses that file for every sibling. The solution file is also a durable artifact: a re-stitch can reuse a solve rather than repeat it.
  - **Bounded coarse-to-fine NCC search** (`CoarseToFineNccRegistrar`), behind a `PairwiseRegistrar` interface so the correlation backend can be swapped. No new dependencies. Constructing the search window as the physically-possible correction makes an out-of-band answer unrepresentable, rather than something to measure and then reject.
  - **Global least-squares solve** (`GlobalPositionSolver`) over *all* measured edges, with a pull toward nominal that pins gauge freedom and holds unregisterable tiles at nominal exactly. Iterative outlier removal drops edges that disagree with the global solution. Sparse conjugate gradient; the matrix is never materialized.
  - **Guards for the ways our data produces confident nonsense**: a robust (median-absolute-deviation) texture gate catches both bright low-contrast backgrounds and lone dust specks; an ambiguity gate catches repeating texture; corrections are clamped to the overlap band; 0%-overlap grids are detected and reported rather than silently doing nothing.
  - **Overlap is derived from the nominal tile step**, not passed in, so it describes the data on disk rather than whatever the acquisition preference happens to say at re-stitch time. An explicit X/Y override is available.
  - Corrections are applied **in memory**; `TileConfiguration.txt` is never rewritten, which keeps it the nominal record and makes re-runs idempotent by construction.
  - Memory is unaffected: only overlap bands are read, never whole tiles, and bands are not cached across edges. Each worker owns its own reader pool.

### Changed
- `Ncc` now hosts the normalized-cross-correlation primitives shared by tile registration and the MicroManager pixel-size estimator, which delegates to it so the two cannot drift apart.
- `Workflow.md` rewritten: it described `ImageAssembler` and the `SparseImageServer` path, both removed in 0.5.0.

## [0.5.0] - 2026-06-29

### Added
- **Single-plane TIFF series support for MicroManager**: The MicroManager metadata strategy now handles both on-disk layouts MicroManager produces: flat MMStack (one OME-TIFF per position with multi-series OME-XML) and single-plane TIFF series (one subfolder per position, each containing a single-image TIFF and `metadata.txt`). Per-tile stage coordinates are read from `FrameKey-*` blocks (flat MMStack) or `Metadata-<relpath>` blocks (single-plane series) in the sidecar JSON.
- **Manual pixel size override for MicroManager stitching**: When the metadata pixel size is untrustworthy (e.g. laser-scanning microscopes whose zoom is not reflected in MicroManager's calibration), tick "Manually edit pixel size" in the dialog to force your value over the metadata. A new "Try calculating pixel size..." button uses normalized cross-correlation of neighbouring tile overlap to measure the true pixel size directly from the data, independent of metadata.
- **5D Tiled Stitching (XY-mosaic × channel × Z × T)**: Full support for multi-dimensional image stitching. Tiles can now be tagged with z-slice and timepoint indices; the stitcher assembles them into complete 5D pyramids in both OME-TIFF and OME-ZARR formats. Each (z, t) plane is stitched independently and written in XYCZT dimension order. `DirectStitcher5DTest` provides comprehensive test coverage across grayscale, RGB, single-axis, and merged-multichannel cases.
- **Directory-encoded z/t for the TileConfiguration strategy**: `TileConfigurationTxtStrategy` now derives each tile's z-slice and timepoint from `z{zz}/` and `t{tt}/z{zz}/` subdirectory names (XY still comes from a 2D `TileConfiguration.txt`). This lets an acquisition preserve a Z-stack and/or time series into a single stitched file by laying preserved planes out in those directories. Flat / projected layouts have no such directories and resolve to z=0, t=0, so existing 2D output is unchanged. Covered by `TileConfigZTStrategyTest` (z-only, z+t, flat, and legacy dim=3 cases).

### Fixed
- **Silently-corrupt OME-TIFF pyramid levels**: stitched mosaics whose dimensions are not a clean multiple of the tile size could produce an OME-TIFF whose full-resolution level was intact but whose downsampled pyramid levels were black, with no thrown exception. Root cause was QuPath's `OMEPyramidWriter` tile-iteration optimization branch. The new `DirectTiffOutputWriter` drives Bio-Formats with only the correct `Math.min`-clamped tile loop, so partial edge tiles are handled correctly by construction. It also writes straight to the final path (no temp-file rename), removing the Windows "being used by another process" rename failures. Note: interleaved RGB requires the writer-level `TiffWriter.setInterleaved(true)` flag (separate from the OME `PixelsInterleaved` metadata).

### Changed
- **Unified stitching pipeline**: All tile counts now route through the direct tile stitcher (previously only used for 500+ tiles). This simplifies the code path while maintaining memory efficiency and performance benefits across all acquisition sizes. `PyramidImageWriter.writeOMETIFF` now delegates to `DirectTiffOutputWriter`; `ImageAssembler` and `WhiteBackgroundImageServer` (the SparseImageServer path) are removed.

### Removed
- **OME_TIFF_VIA_ZARR output format**: This accelerated ZARR-to-TIFF conversion mode has been removed. Use OME_ZARR for fast output to a cloud-native format, or OME_TIFF for single-file compatibility; the choice is made directly rather than through a hybrid intermediate step.

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
