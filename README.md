# QuPath Tiles to Pyramid Extension

> **Part of the [QPSC (QuPath Scope Control)](https://github.com/uw-loci/qupath-extension-qpsc) system.**
> For complete installation and setup instructions, see the [QPSC Installation Guide](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/INSTALLATION.md).

A basic image stitching extension for QuPath that combines multiple image tiles into seamless pyramidal images. This extension supports multiple stitching strategies, dual output formats (OME-TIFF and OME-ZARR), and is designed for high-throughput microscopy workflows.

## Features

- **Content-Based Tile Registration**: Optionally position tiles by correlating the image content in their overlap, instead of trusting nominal stage coordinates. Corrects backlash, encoder error, and thermal drift. One solve is measured on a reference subdirectory and reused by every angle/channel, so co-captured images stay registered to each other. Off by default. See [Tile registration](#tile-registration)
- **Z-stack and time-series stitching**: Assemble tiles into multi-plane pyramids (XY mosaic with multiple z-slices and timepoints) when planes are stored as separate files in `z{nn}/`/`t{nn}/` directories and read by the TileConfiguration.txt strategy. See [Dimensions and channels](#what-the-extension-can-handle-dimensions-and-channels) for exactly what each input supports
- **Multiple Stitching Strategies**: Support for filename-based coordinates, TileConfiguration.txt files, Vectra metadata, and MicroManager metadata (MMStack or single-plane TIFF series)
- **Dual Output Formats**: Choose between traditional OME-TIFF or cloud-native OME-ZARR
- **Pyramidal Output**: Generates multi-resolution pyramids for efficient viewing at all scales
- **Flexible Compression**: The compression dropdown offers QuPath's OME writer compression types (e.g. `LZW`, `JPEG`, `J2K`, `J2K_LOSSY`, `ZLIB`, `UNCOMPRESSED`); for OME-ZARR these map internally to Blosc codecs
- **Cloud-Native ZARR**: Directory-based format optimized for cloud storage and parallel access
- **Batch Processing**: Process multiple slides simultaneously with matching criteria
- **Multi-subdirectory Support**: Automatically creates separate outputs for each matched subdirectory
- **Robust Error Handling**: Comprehensive logging and validation for troubleshooting
- **Memory Efficient**: Direct tile stitcher uses ~40 MB steady state regardless of tile count (vs 2-4+ GB with legacy SparseImageServer approach)
- **Large Acquisition Support**: Handles 1600+ tiles without OOM via spatial indexing and bounded reader pool
- **Multichannel Merge**: Combine N same-shape single-channel pyramids (from per-channel stitching) into one multichannel image via a separate `ChannelMerger` step (see [Dimensions and channels](#what-the-extension-can-handle-dimensions-and-channels))

## What the extension can handle: dimensions and channels

Stitching operates on a **grid of 2D tile files**. Whether extra dimensions (Z, time, channels)
survive into the output depends on how the input encodes them and which strategy reads it. A few
combinations are only partially supported, so read this before planning an acquisition.

### Z-stacks (3D) and time series

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

### Channels and color

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
height, and pixel type. That merge is *not* part of the in-dialog stitch -- it is a public API driven
by the calling system (QPSC) or a script, with no menu item of its own. Co-registration across the
channels is why registration solves one reference subdirectory and reuses it for the rest (see
[Tile registration](#tile-registration)).

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
- **Reference subdirectory** -- which subdirectory to solve on (auto = the one with the most
  texture), reused by all the others.

**In QuPath Preferences -> "Tiles-to-pyramid" category** (persistent, shared with QPSC):

| Preference | Default | Meaning |
|---|---|---|
| Minimum match confidence | 0.30 | NCC below which a tile-pair match is not trusted |
| Max shift per step (% of tile) | 2.0 | largest per-neighbour correction searched for |
| Min shift search (px) | 24 | floor on the above so small tiles keep a usable window |
| Fill unregisterable tiles from neighbours | on | inherit neighbours' correction instead of nominal |
| Nominal pull (lambda) | 0.01 | how much the solve trusts stage vs image content |
| Outlier rejection passes | 2 | reweighting passes down-weighting inconsistent edges |
| Low-texture gate | 0.02 | robust coefficient of variation below which a band is featureless |
| Ambiguity ratio | 0.92 | reject when a rival peak scores this fraction of the best |
| Coarsest search downsample | 8 | starting scale of the coarse-to-fine search (power of two) |
| Candidate peaks kept | 3 | peaks carried between pyramid levels |
| Worker threads | 0 (auto) | 0 = half the cores |

Because the tuning lives in global QuPath preferences, QPSC reads the same values -- set them once
and they apply to both a standalone stitch and a QPSC acquisition. The settings actually used are
also written into the `TileRegistration.txt` header, so a solve is self-documenting.

### Why solve once and reuse

Polarization angles and fluorescence channels are captured at the **same stage position** for a
given tile. If each were registered independently, each would get its own corrections and the
angles would end up misregistered against *each other* -- the channels of one field would no longer
overlay, which is worse than leaving everything on a shared nominal grid.

So exactly one subdirectory is solved (the slow part) and every sibling reuses it (effectively
free). In an acquisition that means: stitch the reference angle first in `Solve` mode, then the
remaining angles, `.biref`/`.sum` outputs, or channels in `Apply` mode. If no reference is named,
the subdirectory with the most texture is chosen automatically.

The solution file is also durable -- a re-stitch can reuse a solve rather than repeat it, and it
can be read when a mosaic looks wrong. It records the pixel size, downsample, flip flags and tile
size it was solved for, and refuses to be applied to a run that does not match.

### Requirements and limits

- **Tiles must overlap.** At 0% overlap adjacent tiles share no content, so there is nothing to
  correlate: registration reports the grid as degenerate, warns, and changes nothing. ~10% is a
  reasonable starting point. (A 0%-overlap grid is also the most common cause of visible seams in
  the first place.)
- **Per-neighbour shifts are bounded to a plausible stage step**, so a low-texture band cannot lock
  onto a far-away wrong peak. The *cumulative* correction across a large grid can still be tens of
  pixels -- that is the running sum of many small per-step errors, and is legitimate -- but it is
  clamped to the overlap band, beyond which tiles would no longer overlap at all.
- **Unmeasurable tiles inherit their neighbours, not nominal.** A near-blank tile whose overlap has
  no texture to correlate is filled from the smooth correction field its registered neighbours
  define, rather than being pinned to its raw stage position. Pinning such a tile to nominal inside
  a grid that everything else shifted by tens of pixels is what used to produce a doubled edge with
  a bright gap; a fully disconnected region still falls back to nominal.
- **Translation only.** Rotation and scale are not corrected. A systematic scale error (e.g. a
  slightly wrong pixel size) is absorbed as a smooth field of per-tile translations -- it stitches
  correctly, but the corrections grow toward the edges of the grid; fixing the pixel-size
  calibration at the source shrinks them.
- **A correct nominal position beats a confident wrong correction.** Featureless bands, blown-out
  fields, lone dust specks, and repeating texture are all detected and refused rather than guessed
  at. The worst case is "no improvement", never "tiles thrown across the mosaic".
- `TileConfiguration.txt` is never modified; corrections are applied in memory, so re-running is
  safe.

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
2. Navigate to **Extensions** -> **Tiles to Pyramid** -> **Tiles-to-pyramid**
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
- Coordinates in the config represent pixel positions in the XY mosaic
- Automatically scaled based on pixel size and downsample settings
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
With matching string "." results in:
- `-5.0.ome.tif`
- `0.0.ome.tif`  
- `5.0.ome.tif`

#### 3. Vectra Tiles with Metadata
For Akoya/PerkinElmer Vectra imaging systems:
- Reads positioning information directly from TIFF metadata tags
- Uses `TAG_X_POSITION`, `TAG_Y_POSITION`, and resolution tags
- No additional configuration files required

#### 4. MicroManager metadata (MMStack or TIFF series)
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
- When you open the Stitch Images dialog or select an input folder, the pixel-size field is automatically filled from the first metadata file's `PixelSizeUm`
- The field is **locked by default** to prevent accidental edits — a label shows the source (`(from MicroManager metadata)` / `(no MicroManager metadata - tick 'Manually edit' to set)` / `(manual override)`)
- By default the metadata `PixelSizeUm` is authoritative, so an accidental dialog value cannot silently misalign a stitch when the metadata is correct
- Tick **"Manually edit pixel size"** to override. When ticked, your value **wins over the metadata** — this is required for scopes whose metadata pixel size is wrong (e.g. laser-scanning microscopes whose zoom factor is not reflected in MicroManager's pixel-size calibration). Symptom of a wrong metadata pixel size: tiles are placed too far apart and overlap regions appear **duplicated** along every seam.

**"Try calculating pixel size..." (measure from overlap):**
- When the metadata pixel size is untrustworthy, click this button to **measure** the true pixel size directly from the data. It phase-correlates (normalized cross-correlation) the overlapping content of neighbouring tiles, divides the recorded stage step (µm) by the measured pixel shift, and reports the median over several tile pairs.
- The measured value is written into the field **as a manual override** (so the stitcher uses it) and the source label shows the confidence. If confidence is low (low-texture or low-overlap tiles), verify the result and adjust manually.

### Configuration Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| **Input Folder** | Root directory containing image subdirectories | Required |
| **Output Folder** | Directory for stitched output files | Required |
| **Pixel Size (um)** | Physical size of each pixel in microns. Auto-detected from MMStack `*_metadata.txt` sidecars when available; field is locked by default. Tick "Manually edit pixel size" to override. | Detected from metadata; otherwise the last-used value (initially 7.2) |
| **Base Downsample** | Downsampling factor for output | 1.0 |
| **Compression** | QuPath OME writer compression type (`LZW`, `JPEG`, `J2K`, `J2K_LOSSY`, `ZLIB`, `UNCOMPRESSED`, `DEFAULT`); applies to both output formats (mapped to Blosc codecs for OME-ZARR) | J2K |
| **Output Format** | OME-TIFF (single file) or OME-ZARR (directory) | OME-TIFF |
| **Matching String** | Filter subdirectories by name pattern. Use "." to process all subdirectories separately | "" (all) |
| **Z-Spacing (um)** | Z-axis spacing for 3D datasets | 1.0 |
| **Solve tile overlaps (content-based registration)** | Checkbox to enable overlap measurement and correction. When enabled, measures the real overlap between neighbouring tiles and corrects their positions before stitching, closing seams caused by stage backlash and drift. Writes a `TileRegistration.txt` solution file beside the tiles. Choice is remembered between sessions. See [Tile registration](#tile-registration) for details. | Off (faster, nominal positions) |

### Output Format Options

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
- **Cause**: All acquisitions now use the memory-efficient direct stitcher, which uses ~40 MB steady state regardless of tile count. If memory issues occur, it may indicate a problem with the system environment or JVM configuration.
- **Solution**: Increase JVM heap size if needed, use higher downsample values for initial processing, or reduce the number of concurrent operations. The direct stitcher's bounded memory usage should handle most configurations.
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

#### Strategy Classes
- `FileNameStitchingStrategy`: Parse coordinates from filenames
- `TileConfigurationTxtStrategy`: Read ImageJ/Fiji tile configurations (and the `z{nn}/`/`t{nn}/` layouts)
- `VectraMetadataStrategy`: Extract Vectra TIFF metadata
- `MicroManagerMetadataStrategy`: Read MicroManager sidecar metadata (MMStack or single-plane TIFF series)

### Extension Points
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

<details>
<summary><h2>Changelog</h2></summary>

### Version 0.2.0 (Direct Stitcher + ZARR Support)
- **NEW**: Memory-efficient direct tile stitcher for all acquisitions
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

### Version 0.1.0
- Initial Java conversion from Groovy implementation
- Support for QuPath 0.6.0+
- Three stitching strategies implemented
- Multi-subdirectory batch processing with separate outputs
- Comprehensive error handling and logging
- Performance optimizations for large datasets

</details>

---

