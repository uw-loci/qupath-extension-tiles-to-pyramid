# QuPath Tiles-to-Pyramid Extension: Workflow Overview

How a folder of tiles becomes a pyramidal OME-TIFF or OME-ZARR. The `TileConfigurationTxt`
strategy is used as the running example; the others differ only in step 3.

```
MenuStartup -> StitchingGUI -> StitchingWorkflow.runDetailed(config)
   |
   +-- StitchingStrategyFactory.getStrategy(config)
   +-- strategy.prepareStitching(...)      -> List<TileMapping>   (nominal positions)
   +-- TileRegistrationStep.applyTo(...)   -> List<TileMapping>   (corrected; no-op by default)
   +-- group by subdirName
   +-- per subdir: DirectTileStitcher.stitch(...)
         +-- TileSpatialIndex     (positions only, no pixels)
         +-- ChunkCompositor      (reads tile sub-regions on demand)
         +-- OME-TIFF: CompositorImageServer -> PyramidImageWriter -> DirectTiffOutputWriter
             OME-ZARR: ZarrOutputWriter chunk loop -> PyramidLevelGenerator
```

## 1. MenuStartup (entry point)

`MenuStartup.java` registers the menu item that opens the dialog.

## 2. StitchingGUI (user dialog)

`functions/StitchingGUI.java` collects the folder, output format, compression, pixel size and
downsample, and builds a `StitchingConfig`. For MicroManager datasets it also offers a
"Try calculating pixel size..." button, which measures pixel size from tile overlap by normalized
cross-correlation rather than trusting the metadata.

## 3. StitchingStrategy (tile positions)

`stitching/StitchingStrategy.java` has one method:

```java
List<TileMapping> prepareStitching(String folderPath, double pixelSizeInMicrons,
                                   double baseDownsample, String matchingString);
```

Implementations:

| Strategy | Positions come from |
|---|---|
| `TileConfigurationTxtStrategy` | `TileConfiguration.txt` (stage microns); z/t from `z{zz}`/`t{tt}` directory names |
| `FileNameStitchingStrategy` | coordinates embedded in the filename |
| `VectraMetadataStrategy` | Vectra TIFF metadata |
| `MicroManagerMetadataStrategy` | MicroManager sidecar JSON (`XPositionUm`/`YPositionUm`) |

Each returns `TileMapping(file, region, subdirName, seriesIndex)`, where `region` is an
`ImageRegion` in **output-pixel space** (stage microns divided by pixel size, with any
`flipStitchingX`/`flipStitchingY` already applied).

## 4. TileRegistrationStep (optional position correction)

`workflow/TileRegistrationStep.java`. A **no-op unless the caller sets a `RegistrationMode`** on
the config.

Stage coordinates are nominal: real stages have backlash, finite encoder resolution, and thermal
drift across a long acquisition. Registration measures where neighbouring tiles actually line up,
by correlating the content in their overlap, and solves for globally consistent corrections.

Three modes:

| Mode | Behaviour |
|---|---|
| `Disabled` (default) | place tiles at nominal stage positions |
| `Solve(out, settings, reference)` | measure the reference subdirectory, solve, write `TileRegistration.txt`, apply |
| `Apply(in)` | reuse a previous solve |

**Why two active modes.** Polarization angles and fluorescence channels are captured at the *same*
stage position for a given tile. Solving each independently would give each its own corrections and
misregister the angles against *each other* -- worse than leaving them all on a shared nominal grid.
So exactly one subdirectory is solved and every sibling reuses that result. The solution file is
also durable: a re-stitch can reuse a solve instead of repeating it, and it can be inspected when a
mosaic looks wrong.

Corrections are applied **in memory**. `TileConfiguration.txt` is never rewritten, so it stays the
nominal record and re-running is idempotent by construction.

See `registration/` for the engine: `NeighborGraphBuilder` (4-connected grid; derives the overlap
from the nominal step rather than being told it), `CoarseToFineNccRegistrar` (bounded correlation
search behind the `PairwiseRegistrar` interface), `GlobalPositionSolver` (weighted least-squares
over all edges, plus a pull toward nominal), `TileRegistrationSolution` (the file format, whose
header refuses to be applied to a run it was not solved for).

## 5. DirectTileStitcher (assembly)

`assembly/direct/DirectTileStitcher.java`. Every tile count routes through here.

The design constraint is **bounded memory: roughly 40 MB regardless of tile count**, against the
2-4+ GB the retired `SparseImageServer` path needed. Three mechanisms hold that:

- **`TileSpatialIndex`** holds only `TileMapping` references -- a file handle and a rectangle. No
  pixels. Tiles are bucketed into chunk-sized cells and translated so the image starts at (0, 0).
- **`TileReaderPool`** keeps at most 64 `ImageReader`s open, LRU-evicting beyond that, and reads
  **sub-regions** via `ImageReadParam.setSourceRegion` so only the pixels a chunk needs are
  decoded. `getDimensions` reads the header without decoding pixels at all.
- **Streaming writes.** There is never a full-image buffer. Zarr composites one 1024x1024 chunk,
  writes it, and discards it. OME-TIFF wraps the compositor in `CompositorImageServer`, which
  composites on demand as the writer pulls tiles.

`ChunkCompositor.compositeChunk` is where pixels land: query the index, allocate one chunk buffer,
and for each intersecting tile read its sub-region and transfer it in.

Overlaps resolve according to `OverlapBlend`, taken from the shared preferences (default
`LAST_WINS`). There are two distinct paths, and the split is deliberate:

- **`LAST_WINS`** answers `false` to `requiresOverlapDetection()` and takes the direct raster
  transfer described above. No accumulator, byte-identical to what it has always produced.
- **The feathers** answer `true` and route to `compositeBlended`, which accumulates
  `weight * sample` into a float plane per band plus a weight plane, then normalises. Weights are
  separable, so they cost one strategy call per row and per column rather than one per pixel. The
  overlap width they taper across comes from `TileSpatialIndex.getOverlapPxX/Y`, measured from the
  tiles' final positions so registration corrections are included.

The accumulator is the one place the bounded-memory property is at risk -- about 16 MB on a
full-size RGB chunk -- so it is allocated per call and never held on the compositor; a pyramid write
has several chunks in flight.

## 6. Output writers

| Format | Path |
|---|---|
| OME-TIFF | `CompositorImageServer` to `PyramidImageWriter.write` to `DirectTiffOutputWriter` (Bio-Formats `TiffWriter`, explicit clamped tile loop) |
| OME-ZARR | `ZarrOutputWriter.writeChunk` loop to `PyramidLevelGenerator` (2x2 box downsample of the level already written) |

Writes are serial by design: Bio-Formats `TiffWriter` is not thread-safe, and `PyramidImageWriter`
holds a global semaphore around OME-TIFF writes.

## 7. Multichannel merge

`ChannelMerger.merge` combines separately-stitched single-channel outputs into one multichannel
image via `ChannelMergeImageServer`. Callers that split channels import them individually instead.
