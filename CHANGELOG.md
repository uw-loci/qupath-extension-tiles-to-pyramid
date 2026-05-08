# Changelog

All notable changes to the Tiles to Pyramid QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

*No unreleased changes.*

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
