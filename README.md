# QuPath Tiles to Pyramid Extension

> **Part of the [QPSC (QuPath Scope Control)](https://github.com/uw-loci/qupath-extension-qpsc) system.**
> For complete installation and setup instructions, see the [QPSC Installation Guide](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/INSTALLATION.md).

Stitches a folder of microscope image tiles into one pyramidal whole-slide image that QuPath can
open. Point it at the folder, say how the tile positions are recorded, and it writes an OME-TIFF or
OME-Zarr beside the tiles.

It reads the tile positions from the acquisition's own records -- a `TileConfiguration.txt`,
coordinates in the filenames, Vectra metadata, or MicroManager metadata -- and can correct those
positions against the image content so the seams close. Memory use stays around 40 MB no matter how
many tiles there are, so thousands of tiles stitch on an ordinary machine.

## What it can stitch

- **Input:** a grid of 2D tile files (TIFF), positioned by `TileConfiguration.txt`, `filename[x,y]`
  coordinates in microns, Vectra metadata, or MicroManager metadata. One method per run.
- **Output:** a pyramidal `.ome.tif`, or an `.ome.zarr` directory (NGFF 0.4, Zarr v2), written
  beside the tiles, with a `.stitch-info.txt` record of how it was made.
- **Several folders at once:** stitch every sub-folder whose name contains some text, one output
  each -- one per channel, or one per polarization angle.
- **Channels:** single-channel folders can be merged into one multichannel image; RGB tiles stay
  RGB.
- **Z-stacks and time series:** only from separate files per plane, and only with the
  TileConfiguration.txt method. See [Z-stacks and time series](#z-stacks-and-time-series).
- **Closing seams:** optional content-based registration measures where tiles really overlap and
  corrects their positions. See [Tile registration](#tile-registration).

## Requirements

- **QuPath**: Version 0.7.0 or greater
- **Java**: Java 21 (the runtime QuPath 0.7 ships with; the extension's bytecode targets Java 21). Java 25 is only needed to *build* from source, not to run
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
git clone https://github.com/uw-loci/qupath-extension-tiles-to-pyramid.git
cd qupath-extension-tiles-to-pyramid
./gradlew shadowJar
# Copy build/libs/qupath-extension-tiles-to-pyramid-*-all.jar to your QuPath extensions directory
```
Developers of qpsc may want to also run the following to enable working with qpsc in IntelliJ.
```
./gradlew publishToMavenLocal
```

## Quick start

1. Open QuPath
2. Navigate to **Extensions** -> **Tiles to Pyramid** -> **Tiles-to-pyramid**
3. The **Tiles to Pyramid** dialog opens.
4. Under **Stitching Method**, choose how your tile positions are recorded (see "Stitching methods and input layouts" below).
5. Click **Select Folder** and choose the folder that holds your tiles.
6. In **Stitch sub-folders with text string**, type text that your tile sub-folder names contain, or
   leave it empty to stitch the selected folder on its own. On first use this field contains `20x`.
7. Click **Stitch**. Pressing Enter in a field does not start it.

A notification says the stitch has started. QuPath stays usable, but a second stitch cannot start
until this one finishes. When it does, a **Tiles to Pyramid - Result** window lists the full path of
each output file, and you can copy the paths from it. Output is written into the folder you selected.

<details>
<summary><b>Stitching methods and input layouts</b> -- how each method reads tile positions, and how to lay the folders out</summary>

## Stitching methods and input layouts

### 1. Filename[x,y] with coordinates in microns
For images with coordinates embedded in filenames:
```
image_tile[1000,2000].tif
image_tile[1500,2000].tif
image_tile[1000,2500].tif
```

**Usage:**
- Select the folder containing sub-folders of tiles, or select a single tile folder and leave the sub-folder text empty
- Coordinates in brackets represent physical positions in microns
- Extension automatically calculates tile positions and overlaps

### 2. TileConfiguration.txt file
For ImageJ/Fiji tile configuration format. The XY positions come from `TileConfiguration.txt`; optionally, z-slice and timepoint indices are derived from directory names if tiles are organized in `z{zz}/` or `t{tt}/z{zz}/` subdirectories.

**Basic 2D layout (flat or projected):**
```
# Define the number of dimensions we are working on
dim = 2

# Define the image coordinates
tile_001.tif; ; (0.0, 0.0)
tile_002.tif; ; (1024.0, 0.0)
tile_003.tif; ; (0.0, 1024.0)
tile_004.tif; ; (1024.0, 1024.0)
```

**5D layout with preserved Z-stack (single timepoint):**
Tiles are organized under `z{zz}/` subdirectories; the TileConfiguration.txt file lives in the root and defines the XY mosaic:
```
root/
+-- TileConfiguration.txt (defines XY positions)
+-- z00/
|   +-- tile_001.tif
|   +-- tile_002.tif
|   +-- tile_003.tif
|   +-- tile_004.tif
+-- z01/
|   +-- tile_001.tif
|   +-- tile_002.tif
|   +-- tile_003.tif
|   +-- tile_004.tif
+-- z02/
    +-- tile_001.tif
    +-- tile_002.tif
    +-- tile_003.tif
    +-- tile_004.tif
```

**5D layout with preserved Z-stack and time series:**
Tiles are organized under `t{tt}/z{zz}/` nested subdirectories; TileConfiguration.txt lives in the root:
```
root/
+-- TileConfiguration.txt (defines XY positions)
+-- t00/
|   +-- z00/
|   |   +-- tile_001.tif, tile_002.tif, ...
|   +-- z01/
|   |   +-- tile_001.tif, tile_002.tif, ...
+-- t01/
    +-- z00/
    |   +-- tile_001.tif, tile_002.tif, ...
    +-- z01/
        +-- tile_001.tif, tile_002.tif, ...
```

**Usage:**
- Each group must contain a `TileConfiguration.txt` file (at the root for z/t layouts, or in each angle subdirectory for flat/projected)
- Coordinates in the config are stage positions in micrometers; each is divided by the pixel size and the downsample to place the tile
- In the dialog, check **Pixel size, microns** before stitching. It is locked by default and shows the last manually entered value (initially 7.2), or one auto-filled from MicroManager metadata found in the folder; tick **Manually edit pixel size** to set it. QPSC and scripts pass the pixel size explicitly
- Tile filenames in the config must match across all z/t planes (the stitcher recursively finds tiles by name, regardless of z/t nesting)
- Flat / projected layouts (no z/t subdirectories) resolve to z=0, t=0 and produce 2D output, unchanged from prior behavior

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
With matching string "." (every folder name here contains a dot) results in:
- `-5.0.ome.tif`
- `0.0.ome.tif`  
- `5.0.ome.tif`

### 3. Vectra tiles with metadata
For Akoya/PerkinElmer Vectra imaging systems:
- Reads positioning information directly from TIFF metadata tags
- Uses `TAG_X_POSITION`, `TAG_Y_POSITION`, and resolution tags
- No additional configuration files required

### 4. MicroManager metadata (MMStack or TIFF series)
For MicroManager 2 multi-position acquisitions with sidecar metadata. Both on-disk layouts MicroManager produces are supported.

**When to use this strategy:** choose it whenever you acquired a multi-position
(XY-tiled) dataset in MicroManager 2 and let MicroManager write the standard
sidecar metadata. Tile positions come from the recorded **stage coordinates**, so
you do not need a `TileConfiguration.txt` or coordinates encoded in filenames.
Point the dialog at the acquisition's root folder and the strategy auto-detects
which of the two layouts you have:

| You have... | MicroManager "Save" setting that produced it | Files on disk |
|---|---|---|
| **Flat MMStack** | "Image stack file" (multi-page `MULTIPAGE_TIFF`) | one `<prefix>_MMStack_<pos>.ome.tif` + `<prefix>_MMStack_<pos>_metadata.txt` per position, all in one folder |
| **Single-plane TIFF series** | "Separate image files" (`SINGLEPLANE_TIFF_SERIES`) | one subfolder per position (`Pos-...`), each with a single-image `img_...tif` + a `metadata.txt` |

Both come out of the same MicroManager MDA acquisition; the only difference is the
"Save" radio button chosen at acquisition time. You do not pick the layout in the
dialog -- the strategy detects it. Detail on each:

**Flat MMStack** (one OME-TIFF + sidecar per position, all in one folder):
- Reads tile positions from `*_metadata.txt` JSON sidecar files
- Uses authoritative per-tile stage coordinates (`FrameKey-0-0-0.XPositionUm` / `YPositionUm`)
- Each OME-TIFF carries every position as a separate series; the per-label series index is recovered from `Summary.StagePositions`
- Example filenames: `acq_MMStack_Pos-0_000.ome.tif` and `acq_MMStack_Pos-0_000_metadata.txt`

**Single-plane TIFF series** (`SINGLEPLANE_TIFF_SERIES`; one subfolder per position):
- Each position is its own subfolder (e.g. `Pos-1-000_000/`) containing a single-image TIFF (`img_channelNNN_positionNNN_..._zNNN.tif`) and a `metadata.txt`
- Reads per-tile stage coordinates from the `Metadata-<relative/path/to.tif>` block (the JSON key encodes the file name)
- Each TIFF is a genuine single-image file (series 0)

Common to both:
- Falls back to `Summary.StagePositions` labels if a per-tile block is missing or malformed
- Auto-detects pixel size from the metadata's `PixelSizeUm`
- All tiles found under the selected folder stitch into a single output named after that folder
- No additional configuration files required

**Usage:**
- Select the acquisition's root folder (the folder containing the sidecars, or the folder containing the per-position subfolders). The strategy scans subfolders, so either layout works.
- For stage-inverted scopes, use the `flipStitchingX` and `flipStitchingY` flags to negate coordinates

**Pixel Size Auto-fill:**
- When you open the Tiles to Pyramid dialog or select an input folder, the pixel-size field is automatically filled from the first metadata file's `PixelSizeUm`
- The field is **locked by default** to prevent accidental edits — a label shows the source (`(from MicroManager metadata)` / `(no MicroManager metadata - tick 'Manually edit' to set)` / `(manual override)`)
- By default the metadata `PixelSizeUm` is authoritative, so an accidental dialog value cannot silently misalign a stitch when the metadata is correct
- Tick **"Manually edit pixel size"** to override. When ticked, your value **wins over the metadata** — this is required for scopes whose metadata pixel size is wrong (e.g. laser-scanning microscopes whose zoom factor is not reflected in MicroManager's pixel-size calibration). Symptom of a wrong metadata pixel size: tiles are placed too far apart and overlap regions appear **duplicated** along every seam.

**"Try calculating pixel size..." (measure from overlap):**
- When the metadata pixel size is untrustworthy, click this button to **measure** the true pixel size directly from the data. It phase-correlates (normalized cross-correlation) the overlapping content of neighbouring tiles, divides the recorded stage step (µm) by the measured pixel shift, and reports the median over several tile pairs.
- The measured value is written into the field **as a manual override** (so the stitcher uses it) and the source label shows the confidence. If confidence is low (low-texture or low-overlap tiles), verify the result and adjust manually.

### Input directory structure

One run uses one Stitching Method, so every sub-folder it selects must use that method's layout.

```
input_folder/             Method: Filename[x,y]; sub-folder text: slide
+-- slide001_tumor/
|   +-- tile_001[0,0].tif
|   +-- tile_002[1000,0].tif
|   +-- tile_003[0,1000].tif
+-- slide002_normal/
|   +-- tile_001[0,0].tif
|   +-- tile_002[1000,0].tif
+-- slide003_control/
    +-- tile_001[0,0].tif
    +-- tile_002[1000,0].tif
```

</details>

<details>
<summary><b>The stitch dialog, field by field</b> -- every control, what it does, and what it defaults to</summary>

## The stitch dialog, field by field

| Parameter | Description | Default |
|-----------|-------------|---------|
| **Stitching Method** | How tile positions are read: "Vectra tiles with metadata", "Filename[x,y] with coordinates in microns", "TileConfiguration.txt file", or "MicroManager metadata (MMStack or TIFF series)". Remembered as soon as it is changed | Last-used (initially TileConfiguration.txt file) |
| **Folder location** (**Select Folder**) | The folder that holds your tiles. Stitched images are written **into this same folder** | Last-used folder |
| **Pixel size, microns** | Physical size of each pixel in micrometers. Auto-detected from MMStack `*_metadata.txt` sidecars when available; field is locked by default. Tick "Manually edit pixel size" to override. Hidden for the Vectra method, whose tiles carry pixel positions | Detected from metadata; otherwise the last manually entered value (initially 7.2) |
| **Downsample** | Downsampling factor for output | Last-used (initially 1) |
| **Compression type** | How pixels are compressed. Lossless: `LZW` (widely readable), `ZLIB` (smaller, slower), `J2K` (smallest lossless, slow, handles 16-bit), `UNCOMPRESSED`, `DEFAULT` (the writer chooses: Bio-Formats picks the OME-TIFF codec, OME-Zarr uses zstd). Lossy: `J2K_LOSSY`, and `JPEG` which is 8-bit RGB only. For OME-Zarr these map to Blosc codecs: `LZW`/`ZLIB` to zlib, `UNCOMPRESSED` to none, everything else to zstd -- so choosing a lossy codec with OME-Zarr silently gives you a lossless one, and the log says so | Last-used (initially J2K) |
| **Output format** | **OME-TIFF (single file)**: one pyramidal `.ome.tif` with OME-XML metadata, widely readable. **OME-Zarr (NGFF 0.4, Zarr v2)**: an `.ome.zarr` directory of chunks, written in parallel, suited to cloud storage; the versions written are what QuPath's bundled reader opens, so check what your other tools accept | Last-used (initially OME-TIFF) |
| **Stitch sub-folders with text string** | Stitch each sub-folder whose name contains this text, one output per sub-folder. **Empty stitches the selected folder itself, and only that folder.** Not used by the MicroManager method | Last-used (initially "20x") |
| **Merge the N channel stitches into one multichannel image** | Shown only when 2+ matching sub-folders of single-channel (non-RGB) tiles will be stitched; not offered for the MicroManager method. Combines the per-channel stitches into one multichannel `<folder>_merged` image; the per-channel images are kept. See [Merging channels in the dialog](#merging-channels-in-the-dialog). Choice is remembered | On |
| **Z-Spacing (um)** | Scripts only (`StitchingConfig`); the dialog always records 1.0 | 1.0 |
| **Solve tile overlaps (content-based registration)** | Checkbox to enable overlap measurement and correction. When enabled, measures the real overlap between neighbouring tiles and corrects their positions before stitching, closing seams caused by stage backlash and drift. Writes a `TileRegistration.txt` solution file beside the tiles. Choice is remembered between sessions. See [Tile registration](#tile-registration) for details. | Off (faster, nominal positions) |
| **Reference subdirectory** | Shown when overlap solving is on: what the overlaps are measured on. **Auto (best match)**, a named sub-folder, or **Normalized merge of all** when there are two or more. Every sub-folder is then placed with that one result. See [Tile registration](#tile-registration) | Auto |

### Output format options

#### OME-TIFF (Traditional)
- **Structure**: Single pyramidal TIFF file
- **Compatibility**: Widely supported by QuPath, ImageJ, and most imaging software
- **Use Case**: General purpose, local storage, maximum compatibility
- **Extension**: `.ome.tif`
- **Compression**: chosen from QuPath's OME writer types -- `LZW`, `JPEG`, `J2K`, `J2K_LOSSY`, `ZLIB`, `UNCOMPRESSED`, `DEFAULT`
- **Best For**: Desktop workflows, maximum software compatibility

#### OME-ZARR (Cloud-Native)
- **Structure**: Directory containing chunked arrays
- **Compatibility**: QuPath 0.7.0+, napari, Python imaging libraries
- **Use Case**: Cloud storage, large datasets, parallel processing
- **Extension**: `.ome.zarr` (directory)
- **Compression**: the same OME writer type you pick is mapped to a Blosc codec internally (e.g. `LZW`/`ZLIB` -> zlib; `J2K`/`JPEG` -> zstd, since JPEG has no Blosc equivalent; otherwise zstd). You do not choose the Blosc codec directly
- **Best For**: Cloud storage, collaborative access, very large images (> 10GB)

**Key Advantages of ZARR:**
1. **Direct chunk writing**: writes each chunk as it is composited, without going through Bio-Formats' single-threaded TIFF writer (note: chunk compositing and writing are currently serial, not multi-threaded)
2. **Compression**: Blosc codecs (zstd by default) are often smaller than TIFF LZW for scientific data
3. **Cloud-Optimized**: Native support for S3, Azure Blob, Google Cloud Storage
4. **Partial Access**: Read specific regions without downloading the entire dataset
5. **Parallel Reads**: Multiple processes can read different regions simultaneously
6. **Progress Tracking**: Per-chunk progress callbacks for better user feedback

**About ZARR compression:** you do not select a Blosc codec directly. The OME compression type you
choose in the dialog is mapped to one when writing OME-ZARR: `LZW`/`ZLIB` -> zlib;
`UNCOMPRESSED` -> none; `JPEG`/`J2K`/`J2K_LOSSY` are not available for ZARR and are substituted with
zstd (logged as a warning); anything else -> zstd, a good speed/ratio default. Note this means an
OME-ZARR is always lossless even if you pick `J2K_LOSSY`; that lossy option only takes effect for
OME-TIFF output.

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

</details>

<details>
<summary><b>Tile registration</b> -- close seams by measuring the real overlap instead of trusting the stage</summary>

## Tile registration

Stage coordinates are *nominal*. Real stages have backlash, finite encoder resolution, and thermal
drift across a long acquisition, so tiles placed purely from reported coordinates can leave visible
seams or soft double images inside the overlap band. Registration measures where neighbouring tiles
actually line up and corrects the positions before compositing.

Registration is **off by default**. Enable it by setting a mode on the config:

```java
StitchingConfig config = new StitchingConfig(...);

// Measure this folder and write the result for siblings to reuse.
config.setRegistrationMode(RegistrationMode.solve(Path.of(folder, "TileRegistration.txt")));

// Or reuse a previous solve.
config.setRegistrationMode(RegistrationMode.apply(Path.of(folder, "TileRegistration.txt")));
```

### Controls: what you set, and where

Registration is split between two genuinely per-run choices in the stitch dialog and a set of
persistent tuning knobs in QuPath's Preferences.

**In the stitch dialog** (shown when "Solve tile overlaps" is ticked):

- **Overlap %** -- derive it from the tile grid (default) or set X/Y by hand for an acquisition
  whose overlap you know.
- **Reference subdirectory** -- what to measure the overlaps on. The solution is reused by every
  subdirectory. Choices:
  - **Auto (best match)** (default). Measures about 24 seams on every
    subdirectory and solves on the one whose matches are most decisive. Seams that come out weak or
    rejected on it are then re-measured on the other subdirectories, and the best match wins. Only
    the weak seams pay for this.
  - **Normalized merge of all** (shown when there are two or more sub-folders). Scales each subdirectory
    by one factor for the whole dataset, then averages them. A dim channel counts as much as a bright
    one, and a feature looks the same in both tiles of a seam. Every seam reads every subdirectory,
    so it takes about N times as long to measure as a single one.
  - **A named subdirectory.** Solve on that one only. No other subdirectory is consulted.

**In QuPath Preferences -> "Tiles-to-pyramid" category** (persistent, shared with QPSC):

| Preference | Default | Meaning |
|---|---|---|
| Minimum match confidence | 0.30 | NCC below which a tile-pair match is not trusted |
| Max shift per step, as % of tile | 2.0 | largest per-neighbour correction searched for |
| Max shift per step, floor (px) | 24 | floor on the above so small tiles keep a usable window |
| Fill unregisterable tiles from neighbours | on | inherit neighbours' correction instead of nominal |
| Nominal pull (lambda) | 0.01 | gauge pin that holds each mosaic piece near nominal stage position; does not shrink real corrections |
| Outlier rejection passes | 2 | iterative passes that down-weight edges disagreeing with global solution (never cut) |
| Low-texture gate | 0.02 | robust coefficient of variation below which a band is featureless |
| Ambiguity ratio | 0.92 | reject when a rival peak scores this fraction of the best |
| Coarsest search downsample | 8 | starting scale of the coarse-to-fine search (power of two) |
| Candidate peaks kept | 3 | peaks carried between pyramid levels |
| Worker threads | 0 (auto) | 0 = half the cores |
| Overlap blending | Last tile wins | how pixels covered by two tiles are resolved (see below) |

Because the tuning lives in global QuPath preferences, QPSC reads the same values -- set them once
and they apply to both a standalone stitch and a QPSC acquisition. The settings actually used are
also written into the `TileRegistration.txt` header, so a solve is self-documenting.

### Overlap blending: the seam, not the position

Registration decides *where* each tile goes. Blending decides what happens to the pixels where two
tiles land on top of each other -- a separate question, and it applies whether or not you register.

| Mode | What it does | Character |
|---|---|---|
| **Last tile wins** (default) | hard cut at the tile boundary | sharp everywhere; shows a visible step wherever two tiles differ in brightness |
| **Linear feather** | weight rises linearly from each tile's edge across the overlap | the common default elsewhere (Fiji Grid/Collection, ASHLAR); hides an intensity step, blurs a little along every join |
| **Cosine feather** | raised-cosine roll-off over the same span | no kink where the ramp ends, so no faint line of its own on smooth backgrounds; blurs slightly more |

**Channel-declared resample policies override your choice.** If a tile declares its channel semantics (`qpsc.resample` in OME metadata), and that policy forbids combining values (`nearest` for label maps, `angular180`/`angular360` for angles), the stitcher automatically switches to **Last tile wins** regardless of your preference. This prevents silent data corruption: a label class the pixel never had, or an angle averaged across its wrap. The log reports when an override occurs. Tiles that declare nothing, or declare `linear`, are stitched with your chosen blending mode.

**Reach for a feather to fix an *intensity* seam, not a positional one.** Uneven illumination, or
exposure that drifted across a long acquisition, leaves neighbouring tiles at different brightness,
and no amount of correct positioning removes the line between them -- that is what feathering is
for. It cannot fix a misplaced tile, and it makes one look worse: feathering averages two views of
the same feature, so wherever the tiles disagree the average is a soft double image. Register first,
then decide whether a seam remains that is worth trading sharpness for.

The overlap the feather spans is measured from where the tiles actually ended up, so it accounts for
any registration corrections rather than assuming the overlap percentage used at acquisition. The
default costs nothing: it takes the same direct raster-copy path it always did and produces
byte-identical output.

### Why solve once and reuse

Polarization angles and fluorescence channels are captured at the **same stage position** for a
given tile. If each were registered independently, each would get its own corrections and the
angles would end up misregistered against *each other* -- the channels of one field would no longer
overlay, which is worse than leaving everything on a shared nominal grid.

So exactly one subdirectory is solved (the slow part) and every sibling reuses it (effectively
free). In an acquisition that means: stitch the reference angle first in `Solve` mode, then the
remaining angles, `.biref`/`.sum` outputs, or channels in `Apply` mode. A caller that stitches
each subdirectory separately but wants the solve to see all of them (for a normalized merge, or the
automatic choice) calls `StitchingWorkflow.solveRegistration(config, subdirs)` first, then stitches
every subdirectory in `Apply` mode.

### Which channel to measure on

Mixing channels is safe because every channel of a tile was captured at the same stage position:
the tile-to-tile offset is the same in all of them, and a constant chromatic shift between channels
is shared by both tiles of a seam, so it cancels.

- **Auto** ranks candidates by how decisively the correlation peak beats its nearest rival on a
  sample of seams. It no longer uses a texture score (spread over median of the tile centre). That
  score answered a different question and ranked a clean nuclear stain *last*, because a dark,
  uniform background makes the ratio small even though it registers well.
- **Normalized merge**: each channel's scale is `1 / (white - black)`, where black and white are
  the 1st and 99.9th percentiles of up to 64 centre crops (512 px square, every 4th pixel) sampled
  evenly across the grid. The cost is the same for 40 tiles or 40,000. The black point is not
  subtracted, because correlation ignores a constant offset. The high white percentile keeps a
  sparse stain from being mistaken for background. The scales are written into
  `TileRegistration.txt`.
- **Channels stored in one file** (multi-band tiles) are still averaged band by band with equal,
  unscaled weights. Normalization applies only across subdirectories.

The solution file is also durable -- a re-stitch can reuse a solve rather than repeat it, and it
can be read when a mosaic looks wrong. It records the pixel size, downsample, flip flags and tile
size it was solved for, and refuses to be applied to a run that does not match.

### Log output and tuning feedback

During a registration run, the extension logs diagnostic information to help you calibrate the "Max shift per step" preference:

```
Per-edge shifts used: max X 12 px (1.17% of tile), max Y 8 px (0.78%); search allowed 20 px (1.95%) x 20 px (1.95%)
```

This tells you:
- The **largest correction** any edge actually needed (12 px in X, 8 px in Y)
- What **percentage of the tile** that represents (1.17% and 0.78%)
- The **search window** your "Max shift per step" preference allowed (20 px, 1.95%)

If the maximum shift approaches or exceeds 80% of the allowance, you'll see a warning:

```
Per-edge shifts reached 90% of the search allowance -- real corrections may be clipped. Raise 'Max shift per step' in the Tiles-to-pyramid preferences and re-run.
```

**How to use this feedback:** A shift well under the allowance (e.g., 12 px used out of 20 px allowed, or 60% of the window) means your setting is comfortable and could even be tightened. A shift approaching or exceeding 80% of the allowance means the search window may be clipping real corrections, and you should raise "Max shift per step" and re-run. Since the same percentage means different physical distances on different objectives (2% of a 40x tile ≠ 2% of a 10x tile), this per-run feedback lets you tune from evidence rather than guessing.

### Requirements and limits

- **Tiles must overlap.** At 0% overlap adjacent tiles share no content, so there is nothing to
  correlate: registration reports the grid as degenerate, warns, and changes nothing. ~10% is a
  reasonable starting point. (A 0%-overlap grid is also the most common cause of visible seams in
  the first place.)
- **Per-neighbour shifts are bounded to a plausible stage step**, so a low-texture band cannot lock
  onto a far-away wrong peak. Each per-edge correction is checked before solving to ensure it does
  not exceed the overlap band (indicating a false match rather than a real measurement). The
  *cumulative* correction across a large grid can be tens or hundreds of pixels -- that is the
  running sum of many small per-step errors, and is legitimate -- and is preserved intact. A
  systematic scale error of a fraction of a percent reaches hundreds of pixels across a long
  acquisition, which is why the per-edge bound (limiting individual overlap measurements) is
  distinct from the per-tile correction (which accumulates freely).
- **Unmeasurable tiles inherit their neighbours, not nominal.** A near-blank tile whose overlap has
  no texture to correlate is filled from the smooth correction field its registered neighbours
  define, rather than being pinned to its raw stage position. Pinning such a tile to nominal inside
  a grid that everything else shifted by tens of pixels is what used to produce a doubled edge with
  a bright gap; a fully disconnected region still falls back to nominal.
- **Translation only.** Rotation and scale are not corrected. A systematic scale error (e.g. a
  slightly wrong pixel size) is absorbed as a smooth field of per-tile translations -- it stitches
  correctly, but the corrections grow toward the edges of the grid; fixing the pixel-size
  calibration at the source shrinks them.
- **The grid itself may be rotated, and the overlap band follows it.** Corrections are translations,
  but the *nominal lattice* is often not axis-aligned: a fraction of a degree between stage and
  camera, or an alignment refinement that solved a small rotation, makes consecutive columns drift
  in Y and rows drift in X. Each edge's band is therefore read at the offset given by **both** axes.
  Reading it on the seam axis alone makes the two bands cover different regions of the slide, so the
  drift is measured as an offset and then applied on top of the nominal that already contained it --
  see the falsified guarantee below.
- **A correct nominal position beats a confident wrong correction.** Featureless bands, blown-out
  fields, lone dust specks, and repeating texture are all detected and refused rather than guessed
  at, so a *rejected* match degrades to nominal.

  This is a statement about the gates, and it was over-claimed until 2026-08-20: the gates cannot
  catch a match that is confident and correct *for the region it was given* when the wrong region
  was handed to it. Reading each edge's band on the seam axis only did exactly that on a rotated
  grid -- NCC 0.95-0.99, every edge accepted, empty reject histogram, and a spurious shift equal to
  the perpendicular drift applied to every seam, which is **worse than nominal**, not "no
  improvement". Fixed in the same release; `rotatedGrid_isNotMoved` now holds the line. The general
  lesson stands: when registration looks wrong, check what the band was pointed at before you
  suspect the solver.
- `TileConfiguration.txt` is never modified; corrections are applied in memory, so re-running is
  safe.

</details>

<details>
<summary><b>Channels and merging</b> -- what happens to colour and to per-channel folders</summary>

## Channels and merging

A tile's channel layout is detected from the **first tile's** pixel format and carried through on
every `(z, t)` plane:

| Input tile | Support | Result |
|---|---|---|
| **RGB brightfield** -- one 3-band 8-bit file per tile (e.g. H&E) | Full | stitched as RGB (any tile with >=3 bands at 8-bit is treated as RGB) |
| **Single channel** -- one 1-band file per tile (8- or 16-bit) | Full | stitched as grayscale |
| **Multichannel in one file** -- one file per tile with >3 bands, or >=3 bands at 16-bit | Not preserved in a single stitch | the compositor builds only a grayscale or RGB plane, so the extra channels are dropped. Split the channels into separate stitches instead (below) |
| **Highly multiplexed** (e.g. 8-40 channel fluorescence) | Via per-channel stitching + merge | see below |

**The multichannel / multiplex pattern.** Fluorescence and multiplex data are stitched **one channel
at a time**: each channel is its own input subdirectory, producing one single-channel pyramid per
channel. Those per-channel pyramids are then combined into a single multichannel OME-TIFF or OME-ZARR
by a separate **channel-merge** step (`ChannelMerger`), which requires them to share the same width,
height, and pixel type. In the stitch dialog it appears as a "Merge the N channel stitches..." checkbox
when the selected folder holds two or more matching sub-folders of single-channel tiles; it stays hidden
for RGB tiles, a single tile folder, or MicroManager input. The merged image is written as
`<folder>_merged` beside the per-channel images (which are kept), with channels named after the
sub-folders. QPSC and scripts call `ChannelMerger` directly. Co-registration across the
channels is why registration is solved once and reused by every channel (see
[Tile registration](#tile-registration)).

### Merging channels in the dialog

```
IF_run/                  Stitching Method: TileConfiguration.txt file; sub-folder text: 20x
+-- DAPI_20x/            TileConfiguration.txt + single-channel 16-bit tiles
+-- FITC_20x/            TileConfiguration.txt + tiles with the same file names as DAPI_20x
+-- TRITC_20x/           TileConfiguration.txt + tiles with the same file names
```

Select `IF_run`, tick **Merge the 3 channel stitches into one multichannel image**, and click
**Stitch**. Written into `IF_run/`: `DAPI_20x.ome.tif`, `FITC_20x.ome.tif`, `TRITC_20x.ome.tif`, and
`IF_run_merged.ome.tif` with channels `DAPI_20x`, `FITC_20x`, `TRITC_20x`.

What to know before relying on the merged image:

- **When the option appears.** Only when the folder and sub-folder text select two or more
  sub-folders, and the first `.tif`/`.tiff` (by name) of the first sub-folder (by name) is not RGB.
  "N" is the number of matching sub-folders; only that one tile is checked, so keep every matching
  sub-folder single-channel, with the same tile size and pixel type.
- **"RGB" means 3 or more bands at 8-bit** (or a packed RGB image type). 8-bit multichannel
  fluorescence tiles therefore count as RGB and hide the option; save one channel per file to use it.
- **Channel order and names.** Channels are ordered by the per-channel output file name (plain text
  order, case-sensitive, so `ch10` sorts before `ch2`; zero-pad numbers) and named after the file
  stem. With a Downsample other than 1 the stem includes `_<n>x_downsample`, and a re-run into a
  folder that already holds the outputs writes numbered copies whose suffix also appears in the name.
- **Colours are not set by the dialog.** Each channel keeps its per-channel file's default; set
  colours in QuPath afterwards, or call `ChannelMerger.merge(..., channelColors, ...)` from a script.
- **Width, height and pixel type must match.** If they differ, no merged image is written; the
  per-channel images remain in the folder.
- **Partial failures.** If one sub-folder fails to stitch, the rest are still merged, so the merged
  image lacks that channel. Check the "Failed:" list in the result window before using it.
- **With Solve tile overlaps.** All channels share one registration solve, applied by tile file
  name. Tiles must have the same file names in every channel folder; otherwise the non-reference
  channels stay at their nominal positions and will not line up in the merged image.

</details>

<details>
<summary><b>Output files and the stitch record</b> -- what is written, where, and how to see how an image was produced</summary>

## Output files and the stitch record

Output is written into the folder you selected, one file per stitched folder, named after it:
- When the sub-folder text is empty: the selected folder is stitched on its own and the output is named after it
- When processing multiple sub-folders: each gets its own output file named after the sub-folder

```
input_folder/             (the folder you selected)
+-- slide001_tumor/
+-- slide002_normal/
+-- slide003_control/
+-- slide001_tumor.ome.tif
+-- slide002_normal.ome.tif
+-- slide003_control.ome.tif
```

A single tile folder, sub-folder text left empty:
```
scan_042/                 (select scan_042 itself)
+-- tile[0,0].tif
+-- tile[1000,0].tif
+-- scan_042.ome.tif      (output)
```

When processing angle sub-folders with sub-folder text ".":
```
bounds/                   (the folder you selected)
+-- -5.0.ome.tif
+-- 0.0.ome.tif
+-- 5.0.ome.tif
```

### Stitch record

Every stitched image gets a plain-text `<image stem>.stitch-info.txt` beside it. It records the source tiles, the stitch settings (method, pixel size, downsample, stage-axis negation, blending, format, compression), what registration did, including the solution file's header, and the software versions. A merged multichannel image's record lists its per-channel inputs and includes each one's record. The file is ASCII `[section]` headings followed by `key: value` lines, so a host application such as QPSC can append its own sections.

</details>

<details>
<summary><b>Examples and special cases</b> -- scripted stitching, batch runs, rotation angles</summary>

## Examples and special cases

### Example workflows

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
    ".",                                          // Sub-folders whose names contain "." (e.g. 0.0, 5.0)
    1.0,                                          // Z-spacing
    StitchingConfig.OutputFormat.OME_ZARR         // ZARR format
);
String result = StitchingWorkflow.run(config);
// Output: one <subdir>.ome.zarr directory per sub-folder whose name contains "."
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

### Special use cases

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
<summary><b>Z-stacks and time series</b> -- preserved only from separate files per plane, with TileConfiguration.txt</summary>

## Z-stacks and time series

Z-slices and timepoints are preserved **only when each plane is a separate tile file**, placed in
`z{nn}/` (and optionally `t{nn}/`) subdirectories and stitched with the **TileConfiguration.txt**
strategy. That path builds a genuine multi-plane pyramid: each `(z, t)` plane is composited from
only the tiles at that plane, the Z-spacing is recorded, and both output formats declare the Z/T
sizes. There is no maximum-intensity projection or flattening -- planes are written through as-is.

| Input layout | Z | T |
|---|---|---|
| TileConfiguration.txt + `z{nn}/` directories | preserved | -- |
| TileConfiguration.txt + `t{nn}/z{nn}/` directories | preserved | preserved |
| TileConfiguration.txt, flat (no z/t directories) | 2D (z=0) | 2D (t=0) |
| MicroManager, Filename[x,y], Vectra | 2D only | 2D only |
| Z/T **inside** a multi-page file (e.g. an MMStack z-stack per position) | **collapsed** | **collapsed** |

Two limits worth stating plainly:

- **The MicroManager, Filename[x,y], and Vectra strategies are 2D only** -- they read the XY
  position of each tile and place it at z=0, t=0.
- **Planes inside a multi-page or multi-series file are not expanded.** The tile reader reads only
  the *first* image in each file, so an MMStack that stores a z-stack (or a time series, or several
  stage positions) inside one file is stitched as a single plane. To preserve those dimensions,
  export the acquisition to the separate-file `z{nn}/` / `t{nn}/` layout and use the
  TileConfiguration.txt strategy.

Directory names must be exactly `z00`, `z01`, `t00`, ... (a number after `z`/`t`, case-insensitive);
the two levels are matched independently, so `z{nn}/t{nn}/` nesting works as well as `t{nn}/z{nn}/`.

</details>

<details>
<summary><b>Performance and memory</b> -- what it costs, and the tile sizes that suit it</summary>

## Performance and memory

### Memory management
- **Large Datasets**: Use higher downsample values (2x, 4x) for initial processing
- **RAM Usage**: Monitor memory usage; increase JVM heap size if needed:
  ```bash
  java -Xmx16G -jar QuPath.jar
  ```

### Processing speed
- **Parallel Processing**: Extension automatically uses multiple CPU cores
- **SSD Storage**: Use SSD drives for input/output to improve I/O performance
- **Network Storage**: Avoid network drives for temporary processing

### Tile size recommendations
- **Small Tiles** (< 2048px): Fast processing, more metadata overhead
- **Large Tiles** (> 8192px): Slower processing, less overhead
- **Optimal Range**: 2048-4096 pixels per tile dimension

</details>

<details>
<summary><b>Troubleshooting</b> -- common failures, debug logging, what to check first</summary>

## Troubleshooting

### Common issues

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

#### More sub-folders stitched than intended
- **Cause**: The sub-folder text selects every sub-folder whose name *contains* it, and each match produces its own output
- **Solution**: To stitch one folder, select that folder itself and leave the sub-folder text empty. Otherwise use text that only the wanted folders contain
- **Example**: "5.0" matches both "5.0" and "-5.0"

#### Out of Memory Errors
- **Cause**: All acquisitions now use the memory-efficient direct stitcher, which uses ~40 MB steady state regardless of tile count. If memory issues occur, it may indicate a problem with the system environment or JVM configuration.
- **Solution**: Increase JVM heap size if needed, use higher downsample values for initial processing, or reduce the number of concurrent operations. The direct stitcher's bounded memory usage should handle most configurations.
- **Command**: `java -Xmx16G -jar QuPath.jar`

### Debug logging
Enable detailed logging by setting log level to DEBUG:
```properties
# In QuPath logging configuration
logger.qupath.ext.basicstitching=DEBUG
```

### Validation steps
1. **File Integrity**: Verify all input TIFF files open correctly
2. **Coordinate Extraction**: Check log output for parsed coordinates
3. **Directory Matching**: Confirm subdirectories match the filtering criteria
4. **Output Verification**: Open resulting OME-TIFF in QuPath to verify stitching quality

</details>

<details>
<summary><b>API and development</b> -- core classes, extension points, building and contributing</summary>

## API and development

### Core classes

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
Logger logger = LoggerFactory.getLogger("stitch");
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

#### Strategy Classes
- `FileNameStitchingStrategy`: Parse coordinates from filenames
- `TileConfigurationTxtStrategy`: Read ImageJ/Fiji tile configurations (and the `z{nn}/`/`t{nn}/` layouts)
- `VectraMetadataStrategy`: Extract Vectra TIFF metadata
- `MicroManagerMetadataStrategy`: Read MicroManager sidecar metadata (MMStack or single-plane TIFF series)

### Extension points
The extension supports custom stitching strategies by implementing the `StitchingStrategy` interface:

```java
public interface StitchingStrategy {
    List<TileMapping> prepareStitching(
        String folderPath,
        double pixelSizeInMicrons,
        double baseDownsample,
        String matchingString
    );
}
```

Each strategy returns `TileMapping(file, region, subdirName, seriesIndex)`, where `region` is an
`ImageRegion` in output-pixel space (stage microns divided by pixel size, with any
`flipStitchingX`/`flipStitchingY` already applied).

### Development setup
```bash
git clone https://github.com/uw-loci/qupath-extension-tiles-to-pyramid.git
cd qupath-extension-tiles-to-pyramid
./gradlew build
./gradlew test
```

### Code style
- Follow standard Java conventions
- Add comprehensive logging for debugging
- Include unit tests for new functionality
- Update documentation for API changes

</details>

## License

This project's own source is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

Note that this is a QuPath extension and is built to run inside QuPath, which is licensed under the [GPL v3](https://www.gnu.org/licenses/gpl-3.0.html). The Apache-2.0 license applies to this extension's new, independently written code (which uses QuPath's public API and does not subclass or copy QuPath core classes). A distribution that bundles this extension together with QuPath - including the combined "fat" jar that ships QuPath dependencies - is a combined work conveyed under the terms of the GPL.

## Support

- **General support and feature requests**: post on the [image.sc forum](https://forum.image.sc/) with the `#qupath` tag and mention `@Mike_Nelson` to flag the topic for my attention
- **Bug reports**: file via [GitHub Issues](../../issues)
- **Discussions**: [GitHub Discussions](../../discussions)

## Acknowledgments

The stitching approach in this extension originated with Pete Bankhead's QuPath script for merging TIFF fields of view into a single pyramidal OME-TIFF ([gist](https://gist.github.com/petebankhead/b5a86caa333de1fdcff6bdee72a20abe)), which is where the idea of reading tile positions from TIFF metadata started.

This extension evolved from the earlier `uw-loci/BasicStitching` and `uw-loci/basic-stitching` extensions; the stitching code was ported to Java and substantially extended (direct tile stitcher, OME-ZARR output, additional metadata strategies).

The OME-ZARR writing approach was informed by Leo Leplat's ZARR implementation in QuPath core (`qupath.lib.images.writers.ome.zarr`) and the [qupath-extension-stitching](https://github.com/qupath/qupath-extension-stitching).

The pyramidal OME-TIFF writer (`DirectTiffOutputWriter`) is an independent implementation written against the Bio-Formats `TiffWriter` API; it does not derive from QuPath's `OMEPyramidWriter` (it was written to avoid a silent edge-tile pyramid-corruption issue in that writer and references only its public `CompressionType` enum).

## AI-Assisted Development

This project was developed with assistance from [Claude](https://claude.ai) (Anthropic). Claude was used as a development tool for code generation, architecture design, debugging, and documentation throughout the project.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the release history.
