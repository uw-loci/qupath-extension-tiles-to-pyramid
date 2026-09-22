# QuPath Tiles-to-Pyramid Extension: Workflow Overview

How a folder of tiles becomes a pyramidal OME-TIFF or OME-ZARR. The `TileConfigurationTxt`
strategy is used as the running example; the others differ only in step 3.

```
MenuStartup -> StitchingGUI -> StitchingWorkflow.runDetailed(config)   (background thread)
   |                                                                  -> ChannelMerger.merge (optional)
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
downsample, and builds a `StitchingConfig`. Whenever the pixel-size field is shown (every method except
Vectra) it offers a "Measure from tiles..." button, which measures pixel
size from tile overlap by normalized cross-correlation rather than trusting the metadata; it reads
MicroManager stage positions, so it only works on MicroManager datasets.

Each open builds fresh controls (JavaFX nodes cannot be re-parented); values persist through
`QPPreferences`. On **Stitch** it runs `StitchingWorkflow.runDetailed` on a background thread (one
at a time), sets `RegistrationMode.Solve` when **Solve tile overlaps** is ticked, and, when the
merge checkbox is shown and ticked, passes the outputs to `ChannelMerger.merge`. The outcome is
reported in a "Tiles to Pyramid - Result" alert; titles differ because the Dialog Manager
extension remembers window size per title.

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

The filename, TileConfiguration and Vectra strategies find their tile folders through
`TileDirectories.resolve`: a blank matching string means the selected folder itself and nothing
else; otherwise the immediate sub-folders whose names contain the string. QPSC never passes a blank
string (it passes an annotation or angle name, or `"."`). The dialog uses the same method to decide
whether to offer the channel merge.

Each returns `TileMapping(file, region, subdirName, seriesIndex)`, where `region` is an
`ImageRegion` in **output-pixel space** (stage microns divided by pixel size, with any
`flipStitchingX`/`flipStitchingY` already applied).

## 4. TileRegistrationStep (optional position correction)

`workflow/TileRegistrationStep.java`. A **no-op unless the caller sets a `RegistrationMode`** on
the config. The dialog sets `Solve` when **Solve tile overlaps** is ticked; QPSC sets `Solve` or
`Apply`. Corrections are keyed by tile file name, so co-captured channels must share file names.

Stage coordinates are nominal: real stages have backlash, finite encoder resolution, and thermal
drift across a long acquisition. Registration measures where neighbouring tiles actually line up,
by correlating the content in their overlap, and solves for globally consistent corrections.

Three modes:

| Mode | Behaviour |
|---|---|
| `Disabled` (default) | place tiles at nominal stage positions |
| `Solve(out, settings, reference)` | measure the overlaps, solve, write `TileRegistration.txt`, apply |
| `Apply(in)` | reuse a previous solve |

**Why two active modes.** Polarization angles and fluorescence channels are captured at the *same*
stage position for a given tile. Solving each independently would give each its own corrections and
misregister the angles against *each other* -- worse than leaving them all on a shared nominal grid.
So the grid is solved once and every sibling reuses that result.

`reference` is a `RegistrationReference`:

- `Single(subdir)` measures one subdirectory.
- `Projection(subdirs)` measures a normalized mean of several. `ChannelNormalizer` gives each
  channel one scale for the whole dataset from a bounded sample.
- `Auto` picks the subdirectory with the most decisive matches on a sample of seams
  (`TileRegistrationEngine.chooseReference`), then re-measures weak or rejected seams on the other
  subdirectories and keeps the best (`rescueWeakEdges`).

The engine reads overlap bands through the `RegistrationChannel` list on the `RegistrationRequest`,
not through `TileNode.file`. `StitchingWorkflow.solveRegistration(config, subdirs)` runs the solve
alone, with every named subdirectory in view, for callers that stitch subdirectories one at a time. The solution file is
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

The design constraint is **bounded memory: the footprint follows the chunk, not the mosaic**,
against the 2-4+ GB the retired `SparseImageServer` path needed. Measured on 1024 px 16-bit tiles
at 10% overlap (OME-TIFF, LZW), the smallest heap that completes is 96 MB at 36 tiles (32 MP) and
128 MB at both 100 tiles (87 MP) and 196 tiles (169 MP) -- 5.3x the mosaic for 1.33x the heap.
Three mechanisms hold that:

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
image via `ChannelMergeImageServer`. The stitch dialog calls it after the stitch when its "Merge the
N channel stitches..." box is ticked (`StitchingGUI.mergeChannelOutputs`: outputs sorted by path,
channels named by file stem, no colours passed, written as `<folder>_merged` with the dialog's
compression and format). QPSC and scripts call it directly. Validation lives in
`ChannelMergeImageServer.validateSourceCompatibility`: width, height and pixel type must match
(otherwise it throws), and a level-count mismatch only warns.
