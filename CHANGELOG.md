# Changelog

All notable changes to the Tiles to Pyramid QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Micro-Manager NDTiff datasets stitch with the MicroManager method.** Micro-Manager 2's NDTiff format (and Pycro-Manager's) keeps every image as a page of `<prefix>_NDTiffStack.tif` (continued in `_1`, `_2`, ... past 4 GB) and lists each image's axes, file and byte offsets in `NDTiff.index`. t2p reads the index directly, written from the format specification in the NDTiffStorage README ("Specification for NDTiff v3.x", micro-manager/NDTiffStorage) without using its code: NDTiffStorage's Java reader needs MMCoreJ. Each image's `channel`, `z` and `time` axes give its plane (a v3.2 channel name is ordered by `Summary.ChNames`); all other axes (`position`, or `row`/`column`) name its position, placed by the `XPositionUm`/`YPositionUm` of that position's first image (else `Summary.StagePositions`); its page is the one whose pixels start at the index's pixel offset. Multi-channel datasets stitch to one output per channel, which the dialog offers to merge, and the dialog reads the pixel size and z-step from the dataset; a `.mm-metadata.json` is written beside each output as for the other layouts. Versions 2 and 3 are read (a `Full resolution` sub-folder is looked in too); uncompressed images only, which is all the format defines. Time-point averaging and z projection are not offered for NDTiff: its planes are stitched as they are. A folder of `*_NDTiffStack*.tif` without an `NDTiff.index` (NDTiff 1.x, or a lost index) is named as not supported when a stitch finds no tiles. The dropdown now reads "MicroManager (MMStack, TIFF series or NDTiff)"; the method's identifier used by QPSC, scripts and saved preferences is unchanged. Tested with datasets written by a test helper that follows the specification byte for byte (two stack files, channel names as axis values, every plane checked by pixel value), and against `mm_mda_tcz_15` from the NDTiff test data (doi:10.5281/zenodo.7065552; one position, 2 channels, 7 z, 8 t), whose 112 planes the stitch reproduces (opt-in test, `T2P_NDTIFF_SAMPLE`). No multi-position NDTiff from a real microscope was available.
- **Micro-Manager 1.4 single-plane acquisitions stitch with every channel, z-slice and time point.** Micro-Manager 1.4 writes a folder per position of `img_TTTTTTTTT_<channel name>_ZZZ.tif` files (`String.format("img_%09d_%s_%03d.tif", frame, channel, slice)` in `TaggedImageStorageDiskDefault.createFileName`, `mm1.4` branch of micro-manager/micro-manager), with a `metadata.txt` holding one `FrameKey-t-c-z` block per file that names it in `FileName`. Only a one-channel, one-plane 1.4 acquisition stitched before; any other left every file out ("channel, z-slice and time point could not be read"). Each file's plane now comes from its `FrameKey` block (its `ChannelIndex`/`SliceIndex`/`FrameIndex`, else the key's own numbers), else from its name with the channel looked up in `Summary.ChNames`; positions come from each block's `XPositionUm`/`YPositionUm`, since a 1.4 Summary lists no `StagePositions`. Averaging time points and max-projecting z-slices work on these folders too, and the copy they write keeps only the `FrameKey` blocks of the files it wrote. Tested with synthetic 1.4-shaped folders (3 channels, z and t, every preprocessing combination, stitch and merge); no real 1.4 acquisition was available.

### Changed
- **Tile sizes are read from the TIFF header directly, on several threads, and once per file per session.** Every strategy read each tile's width and height through `javax.imageio`, which picks a reader among all installed plugins and parses the whole first image directory, serially, and again on every call: QPSC's registration solve over five channels followed by one stitch per channel read every file twice (5 x 1810 files, about 11 s per channel, before registration on the user's run). `TileHeaders` now parses the TIFF or BigTIFF header and first image directory itself (falling back to `javax.imageio` for anything it does not understand), reads up to 8 files at once, and remembers each size while the file keeps its length and modification time, so the stitches after a solve only check the files' dates. Every file is still opened and read: a tile set of mixed sizes comes out right (tested). Measured on a synthetic set of 5 channel folders x 1810 tiles written like QPSC's (tifffile, 16-bit), registration pass then stitch pass: on the Windows drive from WSL (NTFS through 9P, a slow per-file open like an acquisition PC's) 0.9.1's reads took 208-331 s and now 4.8-5.7 s (registration pass 3.5-4.3 s); on the Linux disk from the page cache 1.1-1.9 s and now 0.15-0.42 s. Not measured on Windows itself. Used by the TileConfiguration.txt, Filename[x,y] and Micro-Manager methods.
- **Merging channel stitches copies their compressed tiles instead of decoding and encoding them again, about 15 to 20 times faster.** When every per-channel file is a single-file OME-TIFF stored the way the merged image will be (same size, tile size, levels, 8- or 16-bit unsigned, and the same LZW, ZLIB or no compression), each tile's compressed bytes go straight into the merged file through Bio-Formats' own precompressed-tile writing. That is what QPSC's LC-PolScope and multichannel merges get, since QPSC stitches the channels with the compression it merges with. On a synthetic 5-channel merge (18635 x 18635 px per channel, 16-bit, LZW, 6 levels, inputs in the page cache) the merge took 207.6 and 223.5 s and now takes 9.6 to 14.4 s (levels 1-6: 152.5, 38.9, 10.6, 2.8, 0.9, 0.3 s before; 5.7, 1.9, 0.3, 0.1, 0.0, 0.0 s now). Every sample of every level and channel is the same as the decoded merge's, read with Bio-Formats and with tifffile, and the OME-XML is the same apart from its UUIDs; the tile width and length tags are stored as 8-byte integers (Bio-Formats writes them so when given compressed tiles) instead of 2-byte ones. Anything else (another compression for the merge, JPEG or JPEG-2000, other tile sizes, levels or pixel types, a file that is not an OME-TIFF) is merged as before, and an INFO line says which file and what differed. The copy is now limited by disk speed, not the processor; at the size of the user's LC-PolScope region (5 x 101864 x 87085 px) it should take minutes instead of the ~2.5 h estimated for 0.8.4, but that is not measured on the microscope PC. `-Dt2p.mergeTileCopy=false` turns it off.

### Fixed
- **A TileConfiguration.txt z-stack no longer claims 1 um between slices.** A stack in `z{nn}/` (or `t{nn}/z{nn}/`) folders was written with a z spacing of 1 um whatever was acquired, because a TileConfiguration.txt has no field for it and the dialog passed 1.0. The dialog now shows **Z spacing (um), N z-slices** for the TileConfiguration.txt method when the tile folders hold two or more z folders, filled from the `acquisition_metadata.json` a QP Scope acquisition writes beside its tile folders (`acquisition.z_stack.z_step_um`, looked for in the selected folder and then its parent). Empty means unknown. A stitch whose caller gave no spacing (0 or less) takes it from that file, or for MicroManager input from `Summary.z-step_um`; with neither, the OME-TIFF has no `PhysicalSizeZ`, as MicroManager input has had since 0.8.2. A spacing the caller gives is kept (a disagreeing record is logged as a warning). The `.stitch-info.txt` record names where a spacing read from the acquisition came from. QPSC now passes 0 rather than 1.0 when its z-stack preferences give no step and in its stitching recovery, so those stitches read the acquisition's record. `StitchingConfig.zSpacingMicrons` is no longer `final` (filled in by `useRecordedZSpacing`).
- **OME-Zarr stitches open in QuPath with their pixel size, and an unknown z spacing is no longer written as 1 um.** QuPath opens an `.ome.zarr` through Bio-Formats' Zarr reader, which takes the physical sizes only from `OME/METADATA.ome.xml` and keeps the NGFF `multiscales` scale as text. t2p's own Zarr writer wrote no such file, so every OME-Zarr stitch opened in QuPath uncalibrated (pixel size in pixels, z in slices). It now writes the file as QuPath's own OME-Zarr writer does, with `PhysicalSizeX/Y` and, only when the z spacing is known, `PhysicalSizeZ`. In `.zattrs`, an unknown z spacing is now a z scale of 1 with no `unit` on the z axis (it said micrometer), as NGFF 0.4 allows: every axis needs a scale value, 1 when no physical scale is known, while a unit is only recommended ("multiscales" and "axes" metadata, 0.4 specification). The time axis, whose interval t2p never knows, likewise keeps scale 1 and loses its `second` unit. A merge of OME-Zarr channels (written by QuPath's writer, which gives z the pixel size's unit and a scale of 1 when the spacing is unknown) gets the same unit removed afterwards. The `.stitch-info.txt` line reads `z spacing (um): unknown (not written: OME-Zarr z scale 1 with no unit, no PhysicalSizeZ)`. Tested by reading each case back through QuPath's `ImageServers` (z spacing, pixel size, 8- and 16-bit values) and with an OME-Zarr inspector (0 errors; the missing z unit is reported as a warning, which is intended).

## [0.9.1] - 2026-11-07

### Fixed
- **Registration no longer splits a slightly rotated tile grid into blocks that never align to each other.** A slide that sits a little rotated on the stage gives a tile grid rotated in stage coordinates (0.105 deg on the PPM 20x run of 2026-11-06), so each column's X drifts by a couple of hundred pixels from top to bottom. The neighbor graph estimated the grid step from the sorted X and Y positions, which on such a grid reads short (1729.5 px against a true 1860 px there), skipped a column or row index every dozen or so, and so never proposed the overlaps across those lines. The region broke into blocks of about 150 tiles: each block registered within itself, but the blocks kept their stage positions relative to each other, leaving offset seams along whole rows and columns. That run logged "2223 tile(s) have no path to the registered mosaic" with 4200 of 4226 edges accepted. The step is now measured as two vectors from each tile's nearest right and lower neighbors, and tiles are indexed in that rotated lattice. The grid geometry log line now also reports the grid's rotation. `NeighborGraphBuilderTest` rebuilds the 2026-11-06 grid: the old builder gives a 1726.9 px step and a 29-tile piece around the first tile; the new one gives one piece holding every tile.

### Changed
- **The stranded-tiles message counts pieces.** It reads "N tile(s) in K separate piece(s) of the region share no overlap with the largest piece (M tiles); each piece is registered within itself and placed relative to the others by the stage positions". It is an info line when the pieces are smaller than the largest one, as for separate pieces of tissue, and a warning when the largest piece holds fewer tiles than the stranded ones, which a contiguous region never produces.

## [0.9.0] - 2026-11-06

### Added
- **Every stitch says how long it has left.** The log gets a line at each step (reading tile headers, measuring tile overlaps, writing the image) and every five minutes, with the time left and the clock time it should end. The estimate is built from what the stitch has to do (tiles, overlap pixels when registration solves, and uncompressed output bytes over every pyramid level, so image size, channels and bit depth count) at speeds this computer reached in earlier stitches with the same compression, then from the stitch's own measured speed once a step has run 30 seconds; the steps still ahead are scaled by how much slower or faster than expected it is running, which covers stitches running side by side. `StitchingConfig.setProgressListener` hands the same numbers to scripts and to QPSC. The OME-TIFF writer's progress now counts bytes, not tiles (a thumbnail tile weighed as much as a full-resolution one), and actually reaches the caller: it was dropped on the way to the writer.

### Changed
- **RGB OME-TIFF pyramids are made from staged levels too.** Only 8- and 16-bit non-RGB images were staged; an RGB image's zoomed-out levels went through QuPath's pyramidalized server, and on a stitched mosaic every level below the second cost about the same however small it was. On 2026-11-06 a 111932 x 97619 PPM angle (2,381 tiles, J2K) took 5,448 s for full resolution, 1,996 s for level 2 and then 1,297, 1,185, 1,150, 1,139, 1,146 and 1,149 s for levels 3 to 8, the last of them 874 x 762 px; the 16-bit birefringence image of the same tiles, staged, took 940, 237, 57, 15, 4, 1 and 1 s for levels 2 to 8. An RGB image is now staged as one plane of interleaved R, G, B bytes, each level the 2 x 2 mean of the one before (every pixel counts, the canvas included, as before). On a synthetic 12000 x 10000 RGB image (LZW, tile 512, in memory) levels 3 to 5 went from 2.8-4.4 s each to 0.1-0.9 s and the write from 24-29 s to 17 s. Full resolution is identical; zoomed-out levels are resampled by a plain 2 x 2 mean in place of QuPath's resize and stay within a few counts of the old ones. The free-space check counts 3 bytes per pixel (a 2,381-tile PPM angle needs about 10.8 GB free beside the output). The rig's per-level saving is estimated from the log above, not measured.

### Fixed
- **A stitched image keeps its tiles' channel names.** Single-channel and multi-channel stitches named every channel "Channel 0", "Channel 1", ... and dropped the names in the tiles' OME-XML. They now take them from the first tile, in OME-TIFF and OME-Zarr output, so an LC-PolScope retardance image reads `Retardance_nm_x_100` in any project that opens the file. Tiles without names, RGB tiles, or tiles whose name count differs from the channel count keep the old defaults.

## [0.8.4] - 2026-11-03

### Fixed
- **Stitching one channel to OME-TIFF is about 3.5 times faster, four channels at once about 4 times.** Every zoomed-out level composited the whole mosaic again from the tiles, because each level was made through QuPath's tile cache and full-resolution tiles pushed out the level written just before; a zoomed-out level cost about as much as full resolution, even the thumbnail (the user's 101864 x 87085 16-bit channel: 12-13.5 min for full resolution, then 5-6 min for each of 7 more levels). Full resolution is now read from the tiles once per plane, and each zoomed-out level is made from an uncompressed copy of the level before, staged on disk while that level is written. On a synthetic channel (10 x 10 tiles of 2048 px, 16-bit, 10 % overlap, 18635 x 18635 px, LZW, 6 levels, 300 MB tile cache) one channel took 62 s and now takes 18 s (levels 1-6: 15, 9.3, 9.5, 9.1, 7.9, 7.8 s before; 13.9, 2.6, 0.7, 0.2, 0.1, 0.0 s now), and four channels stitched at once 86 s and now 22 s (levels 18.7, 13.0, 13.7, 11.3, 12.8, 12.0 s each before; 15.5, 3.2, 0.9, 0.3, 0.1, 0.0 s now). Every level of every output is identical to 0.8.3's, compared sample by sample; tests check the same for 8-bit, for C x Z x T images and for a stitch with a z-stack and time points. Scaled to the user's LC-PolScope run (five channels on a pool of four), a channel should take about 12-16 min instead of 53-62 min, and the five channel stitches about 25-31 min instead of 115 min; the staged copy's disk traffic (about 6 GB written and read back per such channel) is not in the synthetic numbers, which ran from the page cache. RGB images and OME-ZARR are unchanged (OME-ZARR already makes each level from the written one before).

### Changed
- **An OME-TIFF stitch needs disk space beside the output for the staged levels.** They are kept in a hidden folder `.t2p-level-staging-<id>` in the output folder, deleted when the write ends (succeeded or failed) and when QuPath is closed during a write, or by the next stitch into that folder after a crash or a killed QuPath. Before writing, the drive must have the staged levels (at most `S(L) + S(L+1)`, where `S(L)` is the uncompressed size of level `L` over all planes; for a stitch `S(1) + S(2)`, about 0.31 x the uncompressed full-resolution image: 5.5 GB for the 101864 x 87085 16-bit channel) plus 512 MiB free, counting other stitches staging at the same time: about 6.1 GB for that channel, 24.3 GB for four at once. The output file is not counted, since it is the same file whether the levels are staged or not. Without that space the image is written the old way, with the same pixels and several times slower, and a **WARNING** says so in the log, at the top of the result window and in the `.stitch-info.txt` record (`-Dt2p.stagingFreeBytes=0` forces this path). A channel merge of finished pyramids reads their levels and stages nothing.
- **`GlobalPositionSolverTest` checks that the 10,000-tile solve does not build the matrix by what it allocates** (3 MB; a dense matrix alone is 800 MB), not by a 250 ms wall-clock budget that shared CI runners missed (255 and 308 ms).

## [0.8.3] - 2026-11-02

### Added
- **`<image stem>.mm-metadata.json` beside every MicroManager stitch** (each per-channel stitch and the merged image), re-adding the file QPSC's removed "Stitch MicroManager Folder" wrote: pretty-printed UTF-8 JSON with `source_folder`, `source_folder_name`, `stitched_output`, `pixel_size_um`, `pixel_size_affine`, `z_position_um`, `z_step_um`, `exposure_ms`, `bit_depth`, `pixel_type`, `tile_width`, `tile_height`, `channels`, `start_time`, `micro_manager_version`, `tile_count`, `grid_rows`, `grid_cols` and `comments_txt`, in that order, each left out when the metadata has no value. Read from the first position's sidecar (`Summary.StagePositions[0]`), not whichever one a folder listing gives first. `exposure_ms` is the channel's own exposure, or for a merged image an object keyed by channel name. The operator's user and computer names are not written. A stitched preprocessed copy names its source folder and adds `preprocessed_copy`. The `.stitch-info.txt` record names the file in an `[acquisition metadata]` section, and after a merge the per-channel files move into `<folder>_channels` with their images.

### Fixed
- **Merging channel stitches is about 15 times faster.** Two causes. Since 0.8.1 (`82f6f9b`) every zoomed-out level of the merged image was made again from full resolution, re-reading and decoding every per-channel file at full resolution for each level, although those files already hold the same levels; and since 0.4.3 (`b2c01a6`) writing each channel of the merged image read all the channels, so a 5-channel merge decoded every source tile 5 times per level. A level a source file already holds is now read from it, and each channel is read only from its own file. On a synthetic 5-channel merge (18635 x 18635 px per channel, 16-bit, LZW, 6 levels) the write took 3490 s and now takes 233 s (levels 1-6: 610, 570, 571, 599, 571, 568 s before; 170, 44, 13, 3.7, 1.0, 0.4 s now), and the merged image is identical at every level and channel. The per-channel files' levels are copied as they are, so merging files written by 0.8.0 or earlier carries their own zoomed-out levels into the merged image (0.8.0 averaged the empty canvas in; 0.2.0 to 0.7.10 kept 8 bits of 16, see **Rebuild pyramid levels...**). Stitching a single channel is not changed.
- **Merged MicroManager channels came out in alphabetical order.** The merge sorted the per-channel stitches by file name, so channels acquired as TRITC, DAPI, FITC became DAPI, FITC, TRITC. They now keep the acquisition's order (`Summary.ChNames`) and names. Channel folders (TileConfiguration.txt and the other folder methods) record no acquisition order and still merge alphabetically.
- **Max-intensity projection without averaging kept only the first time point.** With **Max-intensity project** ticked and **Average** clear, every time point after the first was dropped, so C x Z x T came out C x 1 x 1. Each time point is now projected on its own and kept (C x 1 x T), in both MicroManager layouts. The same holds for flat-field correction without averaging. A time point the acquisition stopped part way through, and any after it, is left out and the stitch record says so.
- **Registration on an OME-TIFF per position measured only its first page** (the first channel's first slice), so an empty first channel or an out-of-focus first slice gave nothing to measure, and on a single-plane series the channels could not be compared (Auto silently used the first). Every position is now measured on the same plane of the chosen channel in both layouts: the middle z-slice of the first time point in the stitch, which is named in `TileRegistration.txt`. Every plane of a position still receives that position's correction.

### Changed
- **Reference subdirectory lists the acquisition's channels for the MicroManager method**, so a channel can be chosen to register on; Auto compares them as it does sub-folders. It used to list nothing for MicroManager input.
- **Registration on `t{nn}/z{nn}/` channel folders measures the middle z-slice of the first time point** too, where it measured whichever slice the folder walk found first. 2D stitches are unchanged.

## [0.8.2] - 2026-11-02

### Fixed
- **A multichannel MicroManager acquisition with z-slices or time points stitched to one channel.** Two causes, both in how the MicroManager method decided which plane each page or file holds. A **single-plane TIFF series** (a folder per position of `img_channelCCC_..._zZZZ.tif` files) never separated its channels: every channel's file, and without preprocessing every z-slice and time point too, was stitched into one image at the same positions, so each tile showed whichever file was drawn last -- on Windows, the last channel. Averaging and projection did not help, because the copy they write is single-plane too. An **OME-TIFF per position** (`_MMStack_`) with more pages than channels (no preprocessing, or averaging without projection on a z-stack) stitched only page 0. In both cases the merge was then skipped without a word. Present since 0.5.0 (single-plane series) and 0.7.4 (MMStack channels). Every plane is now placed by the channel, z-slice and time point MicroManager recorded for it: the MicroManager tag on each page, else the order of the sidecar's `FrameKey-t-c-z` entries, else (one page per channel and no z or time) page `i` as channel `i`; for a single-plane file, its name or its `Metadata-`/`Coords-` block. Each channel stitches to its own image, with every z-slice and time point it has, and the merge combines them, so C x Z x T in either layout now comes out C x Z x T (or C x 1 x 1 with averaging and projection). Checked by unit tests on synthetic Micro-Manager 2.0.3-shaped data, 3 channels x {2D, Z, T, Z+T} x {no preprocessing, average, project, both} in both layouts, every plane and pyramid level of the merged image; 21 of those 32 cases failed before the fix. 2D acquisitions that stitched correctly before stitch to the same image (same tile mappings and same pixels at every level, checked against 0.8.1).
- **A MicroManager position continued past 4 GB** (`<label>_1.ome.tif`) is no longer read as a tile of its own: its planes join that position, continuing its page order.
- **Registration on a single-plane TIFF series** solved every z-slice and time point file as a tile of its own, stacked on the others, and left other channels' files at their nominal positions. Each stage position is now one tile to solve, and every plane of it, in every channel, receives its correction.

### Changed
- **Nothing the MicroManager method leaves out is log-only any more.** When a page's plane cannot be established, the position is read from its first page (always the first channel, slice and time point) and a single-plane file is left out; either is now a **WARNING** at the top of the result window and a line in the `.stitch-info.txt` record, and the result is shown as a warning.
- **A merge that cannot be done is reported.** With **Merge the N channel stitches** ticked, the result window says when the stitch produced fewer images than N channels, instead of skipping the merge silently.
- **The merge is offered for channel folders that keep their z-slices and time points** in `t{nn}/z{nn}/` sub-folders (the check for RGB looked only at the top of the first channel folder, found no tile, and hid the option).
- **MicroManager z-stacks carry the acquisition's z-step** (`Summary.z-step_um`) as their z spacing; with no step recorded the spacing is left unset rather than written as 1 um.

## [0.8.1] - 2026-10-31

### Added
- **Correct uneven brightness with a flat-field profile** (MicroManager method). For a detector with no background image, such as a PMT: each plane is corrected with a profile fitted from tiles, after averaging and before any z projection, and the background keeps its value. The stitched copy gets `_ff` in its name and the stitch record names the profile.

### Changed
- **Zoomed-out levels leave the empty canvas out of their averages** (non-RGB OME-TIFF). Zero is where no tile landed; averaging it in pulled every zoomed-out pixel along a mosaic's edges and gaps toward black, and put a row of spikes at fixed fractions of the background into QuPath's histogram, which is built from the smallest level. Each level is now made from the one before it by `NoDataPyramidServer`, averaging only non-zero pixels at full bit depth; a block of only zeros stays zero. On the SHG MIP from the LC-PolScope project the smallest level had 3,505 pixels between 0 and the background; it now has none. Full resolution is unchanged, and RGB images still use QuPath's pyramid. OME-Zarr output is not changed.

### Added
- **Rebuild pyramid levels...** (Extensions > Tiles to Pyramid) repairs 16-bit OME-TIFFs written by 0.2.0 through 0.7.10 without stitching them again. It checks each chosen file (are its zoomed-out values multiples of 257?), and writes each damaged one to a new `_rebuilt.ome.tif` with full resolution copied and every zoomed-out level made again from it; the original is not changed. Checked on real LC-PolScope retardance and SHG images by `harness/PyramidRebuildScenario.java`: full resolution identical, every rebuilt level's mean equal to full resolution (retardance: 92.5, where the damaged levels read 6.6).

## [0.8.0] - 2026-10-29

> **IMPORTANT -- if you stitched 16-bit images with an earlier version, read this.**
> **Every 16-bit OME-TIFF written by tiles-to-pyramid 0.2.0 through 0.7.10 has damaged pyramid levels below full resolution.** The full-resolution level is correct; every zoomed-out level holds only 8 bits of the data (every value a multiple of 257). What you saw in QuPath when zoomed out, and **any analysis run at a downsample** (pixel classifiers, thresholders or measurements at a lower resolution), used the damaged values. **Stitch those images again with 0.8.0** to repair them. RGB images and OME-Zarr output are not affected.

### Fixed
- **16-bit pyramids kept only 8 bits below full resolution.** The stitched image's downsampled reads were scaled with Java2D, which renders a 16-bit grayscale image through 8 bits, so every value in the first downsampled pyramid level was a multiple of 257, and every coarser level, built from that one, inherited the loss. Zoomed out, an LC-PolScope retardance image of a few hundred counts went 99% black, and an SHG image showed 16 gray levels where full resolution had over 2,000. The downsampled reads now average the covered pixels on the raster at full bit depth. Checked end to end by `harness/Pyramid16BitScenario.java`: on 0.7.10 the first downsampled level's mean was 9.5 against 27.8 at full resolution, with 100% of its values multiples of 257; now every level matches full resolution.

## [0.7.10] - 2026-10-29

### Added
- **Max-intensity projection of z-stacks before stitching (MicroManager).** A z-stack per position used to stitch only page 0 of an OME-TIFF per position, or every slice as its own stacked tile in a single-plane TIFF series. When the metadata reports more than one z-slice, the dialog now offers **Max-intensity project the N z-slices of each position**. It runs after time-point averaging when both are ticked (average first, then the per-pixel maximum over the averaged slices, rounded to the source bit depth once at the end), writes the same MicroManager layout with `Slices` set to 1, and the copy is named after what was done (`_averaged`, `_mip`, `_averaged_mip`). Projection with averaging clear uses the first time point.

### Changed
- `MicroManagerFrameAverager` (0.7.9) is now `MicroManagerPreprocessor`: `run(source, output, Options)`, `shape(folder)` and `defaultOutput(source, options)` replace `average`, `countFrames` and `defaultOutput(source)`. The copy's record file is `preprocessing.txt` and the sidecar key `Summary.Preprocessing`; a folder 0.7.9 wrote (holding `frame_averaging.txt`) is still recognized and may be overwritten. The stitch record's section is *preprocessing*.

## [0.7.9] - 2026-10-28

### Added
- **Average time points before stitching (MicroManager).** An acquisition that takes several frames at each position -- SHG collected as a time series to average out noise -- stitched badly in both MicroManager layouts: an OME-TIFF per position stitched only its first frame, and a single-plane TIFF series stitched every frame as its own tile, stacked at the same stage position (a 500-position, 3-frame run placed 1500 tiles and kept the last frame of each). When the metadata reports more than one frame, the dialog now offers **Average the N time points of each position before stitching**. Each channel is averaged over its frames (mean, rounded to the source bit depth, summed in double precision so saturated frames cannot overflow) into `<folder>_averaged`, beside the acquisition, in the same MicroManager layout with `Frames` set to 1; that copy is then stitched, and the stitch record says how many frames were averaged. Frames are identified from MicroManager's per-page tag, the sidecar's frame keys, or (single-plane series) the file name. Checked on that 500-position SHG run: 500 tiles placed, and every averaged plane equal to an independent numpy mean. `MicroManagerFrameAverager` is public for scripts.

### Fixed
- **A block of tiles that registered only to itself no longer sits where the stage said.** When every seam between a group of tiles and the rest of the mosaic is rejected, the group is its own piece of the accepted-edge graph and the solve pins it at its own stage mean, while the main piece's correction may have grown to hundreds of pixels by that seam. On a U-shaped PPM region (2026-10-26) whose right arm narrowed to three tiles, the three seam edges were rejected and the arm above them was placed with a white gap and a sideways step, with real overlap on both sides. Such a group is now shifted as one body to meet its neighbors, through the same relaxation that already fills blank tiles, and the log warns `Attached N block(s) ... largest shift X px`. Tiles with no grid path to the main piece are reported as stranded.
- **Rejected seams are logged by name.** Each rejected edge now gets an INFO line with both tile names, the reason, the NCC, both texture scores, the second-peak ratio and the band size, so a torn mosaic can be read from the log without re-running at DEBUG.

## [0.7.8] - 2026-10-21

### Added
- **A tile can say its empty background is black.** The area no tile reaches is filled white for RGB, on the assumption that RGB is brightfield, and black otherwise. An RGB image that is not brightfield -- an LC-PolScope orientation colour map, where no signal is black -- came out as dark tissue in a white frame. A tile now declares `qpsc.background` = `black` (or `white`) in its OME metadata, beside `qpsc.resample`, and the stitcher fills with that instead. Tiles that declare nothing are stitched as before.

## [0.7.7] - 2026-09-26

### Fixed
- **The pixel size had to be retyped on every run.** The field reloaded the number typed last time, but the "Manually edit pixel size" tick-box beside it always reopened unticked -- so that number sat in the dialog, greyed out, while the run used an auto-detected value instead. A `TileConfiguration.txt` carries no pixel size to detect, so on that method the value simply looked forgotten. The tick is now remembered like every other control in the dialog, and the field reopens editable when it was.
- **The registration solution recorded the wrong stage-axis flips.** `TileRegistration.txt` read the `TileConfiguration.txt` strategy's flip flags whatever method was running, so a MicroManager stitch that negated both axes wrote `flipX: false  flipY: false`. That header is not a comment: a cached solution is rejected when its flips disagree with the run, so a wrong one could both discard a usable solution and accept one solved for a mirrored layout. One helper now answers which pair of flags a run used, and both call sites defer to it.
- **The stitch record claimed a blending override on every run.** Each `.stitch-info.txt` appended "a channel declaring a non-combinable resample policy forces last-tile-wins" whether or not anything had been overridden. Read as an account of the run in hand it is false, and it sent a reader hunting for an override that had not happened -- while the real reason for the seams they were chasing, a hard cut they had simply never changed, went unstated. The record now names the blend actually used, and mentions the policy only when one really forced the fall-back.
- **The pixel size auto-filled from an unrelated acquisition.** The field is filled by scanning the selected folder for MicroManager metadata, the scan looks three levels down, and it ran whatever method was chosen. Selecting a folder that holds both a MicroManager acquisition and something else filled in the MicroManager pixel size and labelled it "(from MicroManager metadata)" -- so a polarized set needing 0.1732 um/px was stitched at 0.653, giving 76% overlap where the acquisition used 10%, and a scrambled image. Auto-fill now happens only for the MicroManager method, where the selected folder is the acquisition; a `TileConfiguration.txt` carries no pixel size, so there was never anything honest to fill. It is also re-evaluated when the method changes, so switching away drops both the value and the label.
- **The reference-subdirectory dropdown offered folders that cannot be stitched.** It listed every immediate subdirectory of the tile folder, which is not the set of folders a run will stitch. On the MicroManager method it is not close: those channels are pages inside each file, so there are no channel subdirectories, and the only candidate the dropdown found was `<folder>_channels/` -- our own output from a previous merge. Picking it does not fail, which is the problem: the name is not among the run's stitch targets, so it is dropped with a warning in the log and the reference is chosen automatically, leaving a user who thought they had pinned a channel with no sign in the dialog that they had not. The dropdown now lists what `TileDirectories.resolve` returns, the same call the stitch makes, and refreshes when the sub-folder match changes; fewer than two targets means there is nothing to choose and only `Auto` is offered.

### Added
- **A stitch whose tiles overlap implausibly says so, in the result window.** A wrong pixel size cannot fail a stitch: positions are micrometers divided by it, so too large a value packs the tiles together and a mosaic that looks like a mosaic is written. When the measured overlap exceeds 45% of a tile -- real acquisitions use 5-20% -- the result window leads with the overlap, the pixel size that produced it, and the advice to check the image. It warns rather than refuses: an odd overlap is a strong hint, not proof.

### Changed
- The merge tooltip said the per-channel images are "kept" without saying they move; it now names `<folder>_channels`.

## [0.7.6] - 2026-09-25

### Fixed
- **A merged image opened as grayscale.** Every source of a merge is a single-channel OME-TIFF, and those report their channel colour as near-white (`#FFFDFE`), so the merged image inherited four white channels and QuPath had nothing to colour them with. Channels whose source colour carries no information now get one: `DAPI`, `FITC`, `TRITC` and `Cy5` by name, a bare number in 340-800 read as the excitation wavelength and given the colour its emission is conventionally shown in (385 blue, 475 green, 550 red, 621 magenta), and anything unrecognized from QuPath's own palette by position so the channels are at least tellable apart. A source that does supply a real colour keeps it.
- **Merged channel names carried the writer's suffixes.** They are taken from the files the stitch wrote, and the writer appends `_2x_downsample` when the downsample is not 1 and `_2` when the name is already taken -- so QuPath's channel list read `385_2x_downsample`, and `385` was no longer recognizable as a wavelength. Both suffixes are stripped, and a channel genuinely named `2` keeps its name.
- **"Measure from tiles..." was offered where it cannot work.** It estimates the pixel size from the overlap between neighbors and identifies neighbors from MicroManager metadata, so on any other method it could only answer "Need at least two tiles with stage positions". It is now shown for the MicroManager method only.
- **Downsample greater than 1 built the mosaic wrongly.** Both strategies that read stage coordinates divided the tile POSITIONS by the downsample factor while leaving the tile WIDTH and HEIGHT at full resolution, so the grid shrank underneath fixed-size tiles. At downsample 2 a 10% overlap became 55%, a 9-tile mosaic came out 3891 x 3891 instead of 5754 x 5749, registration matched 0 of 12 seams, and the image was scrambled. Downsampling is the writer's job -- it already applies `baseDownsample` when writing the pyramid -- so the positions were being scaled twice, once correctly and once not. Positions are now full-resolution pixels. Verified on a 3x3 four-channel acquisition: at downsample 2 the geometry is again 10% overlap with 12 of 12 seams accepted, and the written image is 2877 x 2874, exactly half the full-resolution mosaic.

## [0.7.5] - 2026-09-25

### Added
- **`*` in the sub-folder field selects every sub-folder.** Selecting a set of channel or angle folders previously meant finding a substring they happened to share: `DAPI`, `FITC` and `TRITC` are selected by the letter `I`, which works, teaches nothing, and would have failed on almost any other three channel names. Leaving the field empty is not the alternative -- that stitches the parent as one folder, and since the tile search recurses, every channel's tiles land in one image with the last one winning. The field is now labelled **Sub-folders to stitch (* = all)** and its tooltip states all three cases: empty for the selected folder, `*` for every sub-folder, or text to match. Existing values keep working; `*` is new, not a replacement.

### Changed
- **The per-channel stitches move into a sub-folder once they are merged.** A four-channel merge wrote ten files -- five images and five records -- into the folder holding the acquisition's own tiles, and the one you actually asked for was not distinguishable by eye. The merged image and its record stay where they were; the channels that fed it go to `<folder>_channels/`. Moved, not deleted, because a per-channel stitch is a legitimate thing to want. Best-effort: a file that cannot be moved stays put and the merge still counts as a success.
- **The result window reports what registration did.** "12 of 12 seams accepted, aligned on 385, moved 36 of 36 tile placements" was written to the log and to the stitch record, and nowhere a user looks. Reading it meant finding one line among a few hundred INFO lines.

### Fixed
- **A stitch that finds no tiles now says why, in the dialog, and offers a way back.** Picking the wrong method for a folder produced one line in the log ("No tile mappings produced by strategy") and a dialog reading "Stitching failed. See the log for details." Since the method is remembered between runs, the usual way to hit this is to point a new folder at whichever method you used last -- which is exactly the case with the least information to debug it from. The failure now reports the folder, the method, how many TIFFs are actually there, and, where the evidence is present, which method would have worked ("This folder looks like it wants a different method: MicroManager metadata (MMStack or TIFF series) -- found 9 MicroManager metadata files"). An empty folder says so instead. A non-blank sub-folder filter that matched nothing is named as the likely cause, since that field is case-sensitive. When nothing at all matches, it points at the issue tracker and asks for a sample. It also separates "wrong method" from "right method, wrong folder": selecting an acquisition's parent puts the evidence one level too deep for the strategy to see, and the symptom is identical, so the message names the sub-folders it found and says to select one of those. The window is headed "No tiles were stitched" rather than "did not fully succeed", which promised a partial result that does not exist, and carries a **Back to settings** button that reopens the dialog with your choices intact.

## [0.7.4] - 2026-09-25

### Added
- **"Invert X axis" / "Invert Y axis" in the stitch dialog, for the two methods whose positions are stage coordinates: MicroManager and TileConfiguration.txt.** MMStack sidecars carry absolute stage coordinates, a TileConfiguration.txt written by an acquisition carries stage micrometers, and whether a rising stage coordinate moves right and down in the camera image depends on how the stage is wired and the camera mounted. The strategy assumes rising stage equals rising pixel; where an axis runs the other way every tile landed in its mirrored slot, and the dialog offered no way to say so -- the `flipStitchingX` / `flipStitchingY` flags existed, but only QPSC ever set them, so stitching the same folder from this extension's own dialog produced a mosaic in which no seam matched. The failure reads as plausible, because the mosaic is still the right size and the overlap still measures correctly; with registration on it is unmistakable, since every edge is rejected ("no edge survived the confidence gates"). The checkboxes are not offered for Vectra or Filename[x,y], whose positions are image-space by construction, and they default to clear -- which is also what a TileConfiguration.txt written by Fiji's Grid/Collection stitching wants. They are remembered between stitches, and the axes actually used are recorded in the `.stitch-info.txt` written beside the output. Measured on a 3x3 four-channel acquisition from an inverted-stage scope: with the axes wrong, overlap-band correlation ran -0.04 to +0.12 and 0 of 12 seams were accepted; with them right, 0.52 to 0.74 and 12 of 12. The two conventions really do differ between scopes: on the same workshop dataset the QPSC fluorescence acquisition needs both axes negated (4/4 seams accepted, 0/4 without) while the polarized-light one needs neither (17/17 accepted as written, 0/17 with them negated).

### Added
- **An "Open output folder" button on the stitch result window.** Stitched files are written beside the tiles, and the only way to reach them was to read the path out of the result text and paste it into a file browser. The button appears whenever the output folder exists and the platform can open one, and it opens the folder off the FX thread so the dialog does not freeze while the browser starts. Dialog only; scripted and QPSC-driven stitches are unchanged.

### Changed
- **Bio-Formats pinned to 8.4.0, the version QuPath 0.7.0 actually loads.** The build compiled against `ome:formats-gpl:7.1.0` while `qupath-extension-bioformats:0.7.0` declares 8.4.0 at runtime. The dependency is provided rather than bundled, so the runtime version was always QuPath's -- this line only ever chose which API we compile against, and it was the wrong one. Dependabot periodically offers 8.5.0; taking it would compile against an API newer than the runtime, which is the direction that produces NoSuchMethodError.
- **"Stitch sub-folders with text string" now starts empty.** It shipped pre-filled with `20x`, which matched the objective-named sub-folders of the project this extension grew out of and nothing in anyone else's data. A first-time user therefore had to know to clear a field before their own folder would stitch. Empty means "stitch the folder you selected, and only that folder", which is what pointing at a folder of tiles should do. Only new installs are affected: the value is persistent, so anyone who has already run a stitch keeps whatever they last used.

### Fixed
- **Multi-channel MicroManager acquisitions only stitched the first channel.** MicroManager packs a position's channels into consecutive pages of one TIFF file. The stitcher was always reading page 0 (the first channel) and silently dropping the rest, so a 4-channel acquisition stitched to a single channel with correct geometry but incorrect content. Now each channel becomes its own tile, named after the channel from `Summary.ChNames`, and the workflow's per-channel-folder merging combines them into one multichannel output. Splitting only happens when page count equals channel count; z-stacks or time series interleave those axes and read the first page only with a warning. The dialog now shows channel merging options for multi-channel MicroManager acquisitions.
- **Registration limit on MicroManager input:** Seam measurement (the "Align-on" choice in the dialog) is now always performed on the first channel only, because all channels in a position come from the same file and the registration path identifies the same grid position across channels by finding the same file name in sibling folders -- a structure MicroManager does not provide. The limitation is documented in the README and in the code.

## [0.7.3] - 2026-09-20

### Fixed
- **The dialog pre-filled a pixel size from a folder you never chose.** With the folder at its default (your home directory), opening the dialog scanned it, found some unrelated acquisition's MicroManager metadata and pre-filled that pixel size, labeled "(from MicroManager metadata)" -- which would stitch at the wrong scale. The pixel size is now read only from a folder you actually chose; the scan itself is unchanged once you pick one.
- **The "Try calculating pixel size..." button was clipped** to "Try calculating pixel si...". It now reads "Measure from tiles...", with the explanation in its tooltip.

## [0.7.2] - 2026-09-19

### Fixed
- **The dialog crashed on open when the folder was a drive root (Windows).** Opening it scans the selected folder for MicroManager metadata; with the folder at its old default of `C:/` that scan reached `C:\$Recycle.Bin`, which Windows lets no ordinary process read, and the dialog died with `AccessDeniedException`. Scans now skip what they cannot read, never scan a filesystem root, and the folder defaults to your home directory. The TileConfiguration.txt tile search had the same fault and no depth limit at all -- it would have failed a stitch, not just a dialog -- and now shares one bounded, fault-tolerant scanner (`FileScanner`).
- **The Reference subdirectory choice was cut off in the dialog** -- it rendered as "Auto (best matching folder, ..." so the user could not read which option was selected. The entries are now "Auto (best match)" and "Normalized merge of all", and the combo is wide enough for a sub-folder name; the tooltip still explains each.

### Changed
- **The stitch dialog names the OME-Zarr versions it writes** -- the Output format list now reads "OME-TIFF (single file)" and "OME-Zarr (NGFF 0.4, Zarr v2)", taken from the writer's own constants so the label cannot drift from the file. Display only: the stored preference and the value QPSC passes are the unchanged enum names.
- **The Compression type tooltip explains each option** instead of saying "Select the type of image compression": which are lossless, that `JPEG` is 8-bit RGB only, that `J2K_LOSSY` and `JPEG` change pixel values, and how each maps to a Blosc codec when the output is OME-Zarr.

## [0.7.1] - 2026-09-18

### Added
- **A stitch record beside every stitched image.** `<image stem>.stitch-info.txt` records how the image was produced: the source tile folder, tile count and size, stitch method, pixel size, downsample, stage-axis negation, overlap blending, format and compression; what registration did (off, solved, reused a sibling's solve, or refused one), with the solution file's header copied in (aligned-on channel, edges accepted, settings, normalization scales); and the tiles-to-pyramid, QuPath, Java and OS versions plus the computer name. A channel merge writes its own record listing the per-channel inputs and embedding each one's record. Plain ASCII `[section]` / `key: value` text. Hosts can append sections (`StitchInfoFile.append`) and must call `StitchInfoFile.moveWith` when they rename the image.

## [0.7.0] - 2026-09-18

### Added
- **Registration on a normalized merge of channels.** The reference can be a projection of several subdirectories. Each is scaled by one factor for the whole dataset (from a bounded sample of at most 64 tile centers), so a dim channel counts as much as a bright one and a feature looks the same in both tiles of a seam. The scales are recorded in `TileRegistration.txt`. In the dialog: "Normalized merge of all folders".
- **`StitchingWorkflow.solveRegistration(config, subdirs)`** solves across the named subdirectories and writes the solution without stitching, for callers (QPSC) that stitch each channel separately but need the solve to see all of them.

### Changed
- **Auto reference is chosen by how well seams match, not by texture.** It measures about 24 seams on each subdirectory and solves on the one whose matches are most decisive. The old texture score (spread over median) ranked a clean nuclear stain last on a real three-channel set, because a dark uniform background makes that ratio small.
- **Auto re-measures weak seams on the other subdirectories.** A seam that is rejected, or whose runner-up peak is within 80% of the winner, is re-measured on every other subdirectory and the most decisive match is kept. Only weak seams are re-read, so the extra cost scales with how many seams are weak.
- **API:** `RegistrationMode.Solve` takes a `RegistrationReference` (`Auto`, `Single`, `Projection`), and `RegistrationRequest` carries the channels to read. The old `Solve(Path, RegistrationSettings, String)` constructor is kept so an installed QPSC built against it keeps registering when this version is installed first. Pass `RegistrationReference.auto()` instead of `null`, which is now ambiguous between the two constructors.

### Fixed
- **The TileConfiguration.txt method stitched with a hidden pixel size.** Its coordinates are micrometers divided by the pixel size, but the dialog hid the pixel-size field for this method, so a stitch used whatever the invisible field held (the last manually entered value, initially 7.2, or one auto-filled from MicroManager metadata) and could place tiles at the wrong spacing with no way to see why. The field is now shown for every method except Vectra, whose tiles carry pixel positions.
- **A failed channel merge hid the stitched files.** A width, height or pixel-type mismatch between channels threw past the merge's error handling, so the result window read "Stitching failed" and dropped the list of per-channel files that had been written. The window now lists them and says why the merge failed.

## [0.6.8] - 2026-09-14

### Changed
- The stitching method "Coordinates in TileConfiguration.txt file" is shown in the dialog as "TileConfiguration.txt file". Display only: the underlying identifier is unchanged, because `StitchingStrategyFactory` switches on it, QPSC passes it, and saved preferences store it.

## [0.6.7] - 2026-09-14

### Fixed
- **The stitch result dialog showed "..." instead of the output paths.** An alert's label wraps only at spaces, so a long Windows path collapsed to an ellipsis. The paths now appear in a read-only, resizable text area that can be selected and copied.
- **The stitch dialog reopened tiny after a stitch.** The result dialog shared its window title, and the Dialog Manager extension remembers window size per title, so the stitch dialog inherited the result dialog's small size. The result dialog is now titled "Tiles to Pyramid - Result", and the stitch dialog refits its height to its content when shown, which also repairs a stale size already saved.
- **Ticking "Solve tile overlaps" scrolled inside a window that never grew.** The window now resizes to fit whenever the form changes height (still capped at 70% of the screen, beyond which it scrolls), keeping any extra width the user dragged in.
- **The stitching method, compression and output format were only remembered after a stitch.** They are now saved as soon as they are changed, so Cancel keeps them.
- "Try calculating pixel size..." no longer floats in an empty row for methods that hide the pixel size, and labels are no longer truncated when the scroll bar appears.

## [0.6.6] - 2026-09-14

### Added
- **Channel merge in the stitch dialog.** When the selected folder holds two or more matching sub-folders of single-channel tiles, a "Merge the N channel stitches into one multichannel image" option appears; ticked, the per-channel stitches are combined with `ChannelMerger` into `<folder>_merged`, channels named after their sub-folders. The option is hidden for RGB tiles (one image, not channels), for a single tile folder, and for MicroManager input. The per-channel images are kept. The choice is remembered.
- **The stitch dialog remembers its settings.** Output format, stitching method, compression, downsample, sub-folder text and the merge choice persist between runs; the output format used to reset to OME-TIFF on every open.
- **Stitcher honours a tile's declared resample policy.** Tiles declare their channel semantics in OME metadata under `qpsc.resample` (written by microscope_imageprocessing): `linear` for continuous data, `nearest` for labels/masks/object ids, or `angular180`/`angular360` for angles. The stitcher now respects these declarations and adapts its processing:
  - **Overlap blending**: When a tile declares a non-combinable policy, the overlap blend strategy is automatically overridden to LAST_WINS (hard cut at the seam) regardless of the user's preference. This prevents the corruption that averaging would cause: a label class that the pixel never belonged to, or an angle averaged across its wrap. The log reports when an override occurs.
  - **Pyramid downsampling**: Each pyramid level uses the appropriate strategy for the declared policy: decimation (selecting actual pixel values) for `nearest`, circular averaging (via complex-plane unit vectors) for angular channels, and area-averaging for `linear`. An angular channel with a declared period is averaged correctly across the wrap; one without a period falls back to decimation. This prevents pyramids that look right at every level but are meaningless.
  - The feature is fail-safe: unrecognized policies are treated as non-combinable, preserving data rather than silently destroying it.
- **Overlap blending options: last tile wins (default), linear feather, cosine feather.** Chosen in QuPath Preferences under "Tiles-to-pyramid", and shared with QPSC like every other setting there. Until now every overlap was a hard cut: `BlendStrategy` and its one implementation were constructed and handed to the compositor, but neither of the interface's methods was ever called, so the whole mechanism had never run.
  - The default remains the hard cut, and deliberately so. Feathering averages two views of the same feature, so wherever the tiles disagree -- and after registration they still disagree by a pixel or two -- the average is a soft double image. It is a trade, not an improvement.
  - What a feather does fix is an intensity *step*: uneven illumination or exposure drift leaves neighboring tiles at different brightness, and correct positioning cannot remove the line between them.
  - The overlap width the feather spans is measured from where tiles actually ended up, so it follows any registration corrections instead of assuming the acquisition-time overlap percentage.
  - The default path is untouched and byte-identical: last-tile-wins reports that it needs no overlap detection, so the compositor keeps its direct raster-copy path and allocates no accumulator. The feathers add a float accumulator plus a weight plane per chunk (about 16 MB on a full-size RGB chunk), released with the chunk; a test asserts nothing outlives it.

### Changed
- **An empty sub-folder text now stitches the selected folder itself, and only that folder.** It used to match every sub-folder. Applies to the filename-coordinate, TileConfiguration.txt and Vectra methods (`TileDirectories`). Non-empty text is unchanged, including QPSC's `"."`, which still selects sub-folders whose names contain a dot.
- **Stitching runs in the background.** The dialog closes and QuPath stays responsive; a dialog reports the outputs (and the merged image) when it finishes. A second stitch cannot be started while one is running.
- **The start button is labeled "Stitch", and Enter no longer triggers it.** Pressing Enter in a text field used to start a stitch.
- The extension description now reflects what it does (registration, OME-ZARR, channel merge), and the dialog's GitHub link points at `uw-loci/qupath-extension-tiles-to-pyramid` instead of the retired BasicStitching repo.
- **`memoryStaysBounded` now measures retained memory instead of live heap.** It compared heap usage before and after the solve without collecting first, so it counted transient garbage, including garbage left by other tests sharing the JVM. The same code read anywhere from 9 MB to 101 MB against a 100 MB bound. It now takes both readings after a settled collection and asserts that nothing scaling with tile count survives the solve, which is the property that actually matters (overlap bands must not be cached across edges). Peak transient use is no longer asserted, because this fixture's tiles total under 10 MB -- a bound it could pass while reading every tile whole proved nothing.
- **Clarified registration preference labels and descriptions.** Text only; solver behavior is unchanged from 0.6.5.
  - "Min shift search (px)" renamed to "Max shift per step, floor (px)". The two settings define one search window -- half-width = max(floor px, percent x tile size) -- and the old pair of names read as two independent knobs.
  - "Nominal pull (lambda)" now describes a gauge pin that fixes where a connected piece sits as a whole, rather than a pull that shrinks real measured corrections. Lambda stopped doing the latter in 0.6.5.
  - "Outlier rejection passes" now says edges are down-weighted, never cut. Cutting an edge un-ties its seam, which then drifts open by the whole accumulated error -- that was the 0.6.3 bug, so the text must not describe it.

### Fixed
- **The stitch dialog could not be opened a second time.** Its controls were static and reused, so the second open threw "Children: duplicate children added" and nothing appeared. Each open now builds its own controls.
- **The Stitch/Cancel buttons could be pushed off-screen.** The form sits in a scroll pane capped at 70% of the screen height, hidden rows no longer reserve space, and the dialog is resizable.
- **A run of unregisterable tiles between two tissue regions was placed as a staircase rather than interpolated across.** Tiles with no measurable overlap content (blank slide) inherit their position from the tiles around them. That diffusion used to freeze each tile the first time it was reached from a registered neighbor, so it only ever saw the side it was reached from: a span of blank tiles took the left boundary's correction on its left half and the right boundary's on its right half, with a step in the middle. It now relaxes to convergence (Laplace's equation, registered tiles as fixed boundary), which places each tile at the average of everything around it and turns that step into a smooth ramp. A single isolated blank tile is unaffected -- both approaches already gave it its neighbors' mean -- and a tile with no path to any registered tile still stays at nominal, since there is nothing to interpolate from. Registration logs now report how many tiles were placed this way and the largest such correction.

### Removed
- **`OverwriteBlendStrategy`.** Folded into `OverlapBlend.LAST_WINS` alongside the two feathers, so all three modes live in one place rather than one being a separate class.
- **`DirectTileStitcher.shouldUseDirectStitching(int)` method and `TILE_COUNT_THRESHOLD` constant.** `StitchingWorkflow` routes every tile count through the direct stitcher, so nothing called the predicate, and the `SparseImageServer` path it chose between no longer exists. Removed rather than left in place, where a threshold implies a fallback path a reader would go looking for.

## [0.6.5] - 2026-08-04

### Added
- **Per-edge shift usage diagnostics in log output.** Every registration run now logs the maximum per-edge shift actually needed and compares it against what the "Max shift per step" preference allowed. Helps users calibrate the setting from evidence rather than guessing.
  - Reports in pixels and as a percentage of tile size, accounting for the fact that the same percentage means different physical distances on different objectives.
  - Warns when the actual shift approaches (≥80% of) the search allowance, signaling that real corrections may be clipped and the preference should be raised.
  - Makes the "Max shift per step" tuning workflow actionable: run once to measure, read the log, adjust the preference, and re-run.

### Fixed
- **Long acquisitions had their cumulative corrections silently truncated.** The solver was clamping per-tile corrections to the overlap-band width, which is a bound on a *per-edge* measurement (one stage step), not a *cumulative* correction (many steps summed along a long line). A systematic scale error of a fraction of a percent reaches hundreds of pixels across a few hundred tiles, and clamping this truncated exactly the long acquisitions that needed it most. Now only per-edge measurements are bounded (before solving, to reject false matches), while cumulative per-tile corrections are preserved intact. The global solver uses per-component gauge pinning instead of per-tile pinning, so each connected piece stays where the stage said it was while internal geometry comes purely from measurements.
  - Per-edge corrections are validated before solving; edges whose correction exceeds the overlap band are rejected as out-of-band.
  - Cumulative per-tile corrections are no longer clamped and scale linearly with mosaic size, as they should.
  - Robust reweighting (down-weighting outlier edges rather than cutting them) ensures bad measurements do not dominate.

## [0.6.4] - 2026-08-04

### Fixed
- **A weak-but-correct match was worth 1/56th of a strong one, so its seam was positioned by its
  neighbors instead of by its own overlap.** Edge weight was `(ncc - minNcc)^2`, which collapses to
  zero exactly at the acceptance threshold: at the default `minNcc = 0.30`, an edge at `ncc = 0.34`
  scored 0.0016 against 0.09 for a typical 0.60 edge and 0.28 for a strong 0.83 one. Measured on a
  real acquisition, one such edge correctly measured a `(-2, -10)` px correction while the solve
  applied `(+25, +16)` -- 37 px away, in the opposite direction -- leaving obvious duplication at a
  seam whose own measurement had been right all along. (Confirmed against the whole-slide image:
  the tile needed to move up and left, exactly as that edge had measured.) Meanwhile the median
  edge residual was 1.53 px, so the rest of the mosaic was fine; only the low-confidence seams were
  being overruled.
  - Weight is now `ncc^2` -- the squared correlation, i.e. the fraction of variance the match
    explains -- with no threshold subtraction. The weak edge above goes from about a fiftieth of a
    typical edge's influence to about a sixth, enough to hold its own seam.
  - This is safe because the weight is no longer the defence against bad matches: 0.6.3's robust
    reweighting already suppresses any edge that disagrees with the global consensus, whatever its
    correlation. Penalising low confidence here as well was double counting, and it fell hardest on
    exactly the low-contrast seams that most need a measurement.
  - Same failure class as the 0.6.3 hard-drop: that removed a good edge's constraint entirely, this
    removed 98% of it.

## [0.6.3] - 2026-08-04

### Fixed
- **The global solve was manufacturing its own seams by cutting edges.** On-scope diagnosis (a
  360-tile PPM acquisition, instrumented per-edge) found the visible tears were *not* wrong matches,
  low texture, scale, or a too-small search window -- every per-edge shift measured a clean ~6 px.
  They were the solver's own doing: its outlier pass *hard-dropped* 66 edges whose residual exceeded
  a robust threshold, and cutting an edge un-ties that seam, so the two tiles then drift apart
  through other paths and the seam gaps open to the whole accumulated drift. Measured directly:
  kept (`NONE`) seams sat at a 5 px median gap, while cut (`OUTLIER_IRLS`) seams -- same 6 px
  measured shift -- had torn open to a **19/50/55 px** median/p90/max, concentrated in a straight
  line at one grid row (a fly-back that settled badly, whose entire row of edges the pass then
  amputated). This is why larger regions were worse (more accumulation to gap open) and small ones
  were clean.
  - The outlier pass now **down-weights** inconsistent edges (a redescending `(delta/r)^2` robust
    weight) instead of removing them, so every seam stays tied to its measurement and the
    unavoidable loop-closure error spreads thinly across the mosaic instead of concentrating into a
    few visible tears. A genuinely-wrong edge still collapses to near-zero influence, but is never
    cut. Removed the now-unused `OUTLIER_IRLS` reject reason.

## [0.6.2] - 2026-08-03

### Added
- **Registration is now tunable, not just on/off.** The single "solve tile overlaps" checkbox hid a
  large set of hard-coded values; they are now surfaced.
  - **New "Tiles-to-pyramid" category in QuPath Preferences** holds the persistent tuning knobs:
    minimum match confidence, max shift per step (% and px floor), fill-unregisterable-tiles toggle,
    nominal pull (lambda), outlier passes, low-texture gate, ambiguity ratio, coarsest search
    downsample, candidate peaks, and worker threads. Each shows its default and a description. This
    is the single source of truth, shared with QPSC (which reads the same settings), so a value set
    here applies to both a standalone stitch and a QPSC-driven acquisition.
  - **The stitch dialog** gains the two genuinely per-run choices, shown when registration is on:
    overlap percent (derive from the grid, or set X/Y by hand) and which subdirectory to solve on
    (auto = most texture, or pick one). A hint points to the Preferences category for everything
    else.
  - **`RegistrationSettings`** gained `maxStepErrorFrac`, `minStepErrorPx`, and `fillUnregistered`
    (previously fixed constants in the engine), so the whole tuning surface is one configurable
    object.
  - **The `TileRegistration.txt` header now records the settings used** (confidence, shift bound,
    lambda, gates, fill, threads), so a solve is self-documenting.

### Documentation
- **README accuracy pass against the code.** Added a "What the extension can handle: dimensions and
  channels" section stating plainly what survives stitching: Z-stacks and time series are preserved
  only as separate files in `z{nn}/`/`t{nn}/` directories via the TileConfiguration.txt strategy
  (the other three strategies are 2D-only, and planes *inside* a multi-page/MMStack file are read as
  a single plane, first image only); RGB and single-channel tiles are fully supported, multichannel
  tiles in one file collapse in a single stitch, and multiplex data is handled as per-channel
  stitches recombined by the separate `ChannelMerger` step. Corrected several stale claims: runtime
  Java is 21 (not 25 -- 25 is build-only); the menu item is "Tiles-to-pyramid" (not "Stitch
  Images"); default pixel size is the last-used value (initially 7.2, not 0.5) and default
  compression is J2K (not LZW); the compression dropdown lists QuPath's OME writer types for both
  formats (ZARR Blosc codecs are an internal mapping, not selectable); ZARR writing is serial, not
  multi-threaded; `StitchingStrategy.prepareStitching` returns `List<TileMapping>`; removed the
  nonexistent `StitchingImplementations` class from the API section and added
  `MicroManagerMetadataStrategy` to the strategy list; refreshed the registration limits to match
  the 0.6.1 neighbor-field fill and bounded per-edge search.

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
    at nominal -- stranding it a doubled ~20 px from its neighbors. Such a tile now inherits the
    correction field its registered neighbors define (a diffusion fill over the grid); a wholly
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
- **Manual pixel size override for MicroManager stitching**: When the metadata pixel size is untrustworthy (e.g. laser-scanning microscopes whose zoom is not reflected in MicroManager's calibration), tick "Manually edit pixel size" in the dialog to force your value over the metadata. A new "Try calculating pixel size..." button uses normalized cross-correlation of neighboring tile overlap to measure the true pixel size directly from the data, independent of metadata.
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
