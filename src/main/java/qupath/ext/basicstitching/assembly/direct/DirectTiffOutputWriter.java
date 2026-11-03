package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.IPyramidStore;
import loci.formats.out.TiffWriter;
import loci.formats.tiff.IFD;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.PositiveInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * Writes a pyramidal OME-TIFF directly through Bio-Formats, bypassing QuPath's
 * {@code OMEPyramidWriter}.
 *
 * <p><b>Provenance.</b> This is an independent implementation written against the
 * Bio-Formats {@code TiffWriter} API (BSD-2). It is not derived from QuPath's
 * GPL {@code OMEPyramidWriter}: the decomposition, the tile loop, and the
 * pixel-packing are written from scratch, and the only QuPath symbol referenced
 * is the public {@code OMEPyramidWriter.CompressionType} enum. It was written
 * specifically to avoid the edge-tile corruption described below.
 *
 * <p><b>Why this exists.</b> QuPath's {@code OMEPyramidWriter} has an
 * optimization branch that reuses a pyramidalized source server's native
 * tile-request grid when several conditions line up. For stitched mosaics whose
 * per-level dimensions are not a clean multiple of the tile size, that grid
 * disagrees with the level dimensions and Bio-Formats rejects the overflowing
 * tiles -- it logs {@code Error writing Tile} at ERROR level but does NOT throw,
 * so the full-resolution level is intact while the downsampled pyramid levels
 * come out black. This writer drives Bio-Formats with <i>only</i> the safe
 * explicit tile loop ({@code Math.min}-clamped edge tiles), so partial edge
 * tiles are correct by construction and the silent-corruption failure mode
 * cannot occur.
 *
 * <p><b>No temp file, no rename.</b> Output is written straight to its final
 * unique path. The previous OME-TIFF path wrote to a {@code .writing.ome.tif}
 * temp file and renamed on success; that rename was the source of the Windows
 * "being used by another process" failures (an external handle holder racing
 * the rename of a just-finished multi-GB file). Writing directly to the final
 * path removes the rename entirely. A failed write leaves a partial file at the
 * (fresh, non-colliding) output path, which the caller deletes. (The staged levels described
 * below are separate scratch files, deleted after the write and never renamed.)
 *
 * <p><b>Levels.</b> For 8- and 16-bit images that are not RGB, full resolution is read from the
 * source once per plane, and each coarser level the source does not store is made from the one
 * before it, averaging 2 x 2 blocks with zeros (empty canvas) left out. The level before is read
 * from a staged, uncompressed copy in a hidden folder beside the output ({@link LevelStaging}),
 * written while that level is written, so the mosaic is composited once. Without the disk space
 * for that copy (or for other images) the levels come from {@link NoDataPyramidServer} (non-RGB;
 * one per source image for a {@link ChannelStack}) or {@link ImageServers#pyramidalizeTiled}
 * (RGB), which make every level from full resolution through QuPath's tile cache: the same
 * pixels for non-RGB images, but each level then costs about as much as full resolution once the
 * cache cannot hold the level before. The Bio-Formats writer still performs a single
 * close-time reopen of the finished file to patch in the OME-XML footer; that
 * is internal to Bio-Formats and is not the rename failure addressed above.
 */
public final class DirectTiffOutputWriter {

    private static final Logger logger = LoggerFactory.getLogger(DirectTiffOutputWriter.class);

    /**
     * Bio-Formats writes the {@code (0,0)} tile first and then the rest; the
     * underlying {@code TiffWriter.saveBytes} is synchronized so tiles within a
     * plane may be written from multiple threads. We write serially for
     * simplicity and determinism -- read I/O is already serialized through the
     * synchronized {@link TileReaderPool}, so parallel tile writes would not
     * speed up the dominant cost here.
     */
    private DirectTiffOutputWriter() {}

    /**
     * Write {@code source} as a pyramidal OME-TIFF to {@code finalOutputPath}.
     *
     * @param source the assembled image to export (e.g. a
     *               {@link CompositorImageServer}); read for pixels and metadata
     * @param finalOutputPath absolute path ending in {@code .ome.tif}; must not
     *                        already exist (caller resolves a unique name)
     * @param compression Bio-Formats compression to use
     * @param tileSize output tile edge in pixels (typically 512)
     * @param baseDownsample downsample applied to the source for the
     *                       full-resolution (level 0) output; typically 1.0
     * @param progressCallback optional progress sink (0.0 - 1.0); may be null
     * @throws IOException if the write fails; the partial output file (if any) is
     *                     left for the caller to clean up
     */
    public static void write(
            ImageServer<BufferedImage> source,
            String finalOutputPath,
            OMEPyramidWriter.CompressionType compression,
            int tileSize,
            double baseDownsample,
            Consumer<Double> progressCallback)
            throws IOException {
        write(source, finalOutputPath, compression, tileSize, baseDownsample, progressCallback, null);
    }

    /**
     * As {@link #write(ImageServer, String, OMEPyramidWriter.CompressionType, int, double, Consumer)},
     * reporting what the user should be told about how the file was written.
     *
     * @param warnings receives each warning (also logged); may be null
     */
    public static void write(
            ImageServer<BufferedImage> source,
            String finalOutputPath,
            OMEPyramidWriter.CompressionType compression,
            int tileSize,
            double baseDownsample,
            Consumer<Double> progressCallback,
            Consumer<String> warnings)
            throws IOException {
        write(
                source,
                finalOutputPath,
                compression,
                tileSize,
                baseDownsample,
                progressCallback,
                warnings,
                LevelStaging.VOLUME);
    }

    /** As above, with the free disk space read from {@code space}. */
    static void write(
            ImageServer<BufferedImage> source,
            String finalOutputPath,
            OMEPyramidWriter.CompressionType compression,
            int tileSize,
            double baseDownsample,
            Consumer<Double> progressCallback,
            Consumer<String> warnings,
            LevelStaging.FreeSpace space)
            throws IOException {
        Consumer<String> warn = w -> {
            logger.warn(w);
            if (warnings != null) {
                warnings.accept(w);
            }
        };

        int fullWidth = source.getWidth();
        int fullHeight = source.getHeight();
        PixelType pixelType = source.getPixelType();
        int bytesPerPixel = pixelType.getBytesPerPixel();
        int nChannels = source.nChannels();
        int sizeZ = Math.max(1, source.nZSlices());
        int sizeT = Math.max(1, source.nTimepoints());

        // RGB is exported interleaved (3 samples-per-pixel in one plane); anything
        // else is planar (one IFD plane per channel). Mirrors the guard in the
        // former PyramidImageWriter path so a misclassified non-3-channel server
        // never silently drops channels under an interleaved codec.
        boolean exportRGB = source.isRGB() && nChannels == 3 && pixelType == PixelType.UINT8;

        double[] downsamples = computePyramidDownsamples(fullWidth, fullHeight, baseDownsample, tileSize);
        int numLevels = downsamples.length;

        logger.info(
                "Direct OME-TIFF: {}x{}, {} channels, {} z, {} t, {} bit, RGB={}, {} levels, tile={}, compression={}",
                fullWidth,
                fullHeight,
                nChannels,
                sizeZ,
                sizeT,
                bytesPerPixel * 8,
                exportRGB,
                numLevels,
                tileSize,
                compression);

        // Channel planes per (z, t): 1 for interleaved RGB (3 samples in one plane),
        // nChannels for planar. The total plane count per level is
        // channelPlanes * sizeZ * sizeT, ordered C-fastest then Z then T (XYCZT).
        int channelPlanes = exportRGB ? 1 : nChannels;

        long estimatedBytes = estimatePixelBytes(fullWidth, fullHeight, nChannels, bytesPerPixel, downsamples)
                * (long) sizeZ
                * (long) sizeT;
        boolean bigTiff = estimatedBytes >= (Integer.MAX_VALUE - 1024L * 1024L * 100L);

        // Staged levels: each plane read straight from its source image, each level the source
        // lacks made from a staged copy of the level before.
        PlaneSource[] sources = stagedSources(source, exportRGB, channelPlanes, tileSize, baseDownsample, downsamples);
        LevelStaging staging = null;
        StagingPlan plan = null;
        if (sources != null) {
            plan = new StagingPlan(sources, downsamples, fullWidth, fullHeight, sizeZ * sizeT, bytesPerPixel);
            if (plan.peakBytes() > 0) {
                Path outDir = Path.of(finalOutputPath).toAbsolutePath().getParent();
                staging = LevelStaging.open(outDir, plan.peakBytes(), estimatedBytes, space, warn);
                if (staging == null) {
                    sources = null;
                    plan = null;
                }
            }
        }
        try {
            writeLevels(
                    source,
                    finalOutputPath,
                    compression,
                    tileSize,
                    downsamples,
                    exportRGB,
                    channelPlanes,
                    bigTiff,
                    sources,
                    plan,
                    staging,
                    progressCallback);
        } finally {
            if (staging != null) {
                staging.close();
            }
        }
    }

    private static void writeLevels(
            ImageServer<BufferedImage> source,
            String finalOutputPath,
            OMEPyramidWriter.CompressionType compression,
            int tileSize,
            double[] downsamples,
            boolean exportRGB,
            int channelPlanes,
            boolean bigTiff,
            PlaneSource[] sources,
            StagingPlan plan,
            LevelStaging staging,
            Consumer<Double> progressCallback)
            throws IOException {
        int fullWidth = source.getWidth();
        int fullHeight = source.getHeight();
        PixelType pixelType = source.getPixelType();
        int bytesPerPixel = pixelType.getBytesPerPixel();
        int sizeZ = Math.max(1, source.nZSlices());
        int sizeT = Math.max(1, source.nTimepoints());
        int numLevels = downsamples.length;

        // Without staging, pyramidalize the source so downsampled levels are produced in bounded
        // tiled reads (a single high-level tile would otherwise read a huge full-resolution
        // region from the compositor).
        List<ImageServer<BufferedImage>> pyramids = new ArrayList<>();
        Plane[] planes = sources == null
                ? planePyramids(source, exportRGB, channelPlanes, tileSize, downsamples, pyramids)
                : null;
        LevelStaging.StagedLevel[] staged = new LevelStaging.StagedLevel[numLevels];
        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        initializeMetadata(meta, source, pixelType, exportRGB, fullWidth, fullHeight, downsamples);

        long totalTiles = countTiles(downsamples, fullWidth, fullHeight, tileSize)
                * (long) channelPlanes
                * (long) sizeZ
                * (long) sizeT;
        long writtenTiles = 0;

        try (ImageWriter imageWriter = new ImageWriter()) {
            imageWriter.setWriteSequentially(true);
            imageWriter.setMetadataRetrieve(meta);

            // getWriter resolves the .ome.tif extension to an OME-TIFF writer
            // (an OMETiffWriter, a TiffWriter subclass). BigTiff must be set on it
            // before setId().
            ((TiffWriter) imageWriter.getWriter(finalOutputPath)).setBigTiff(bigTiff);
            // Compression must be set before setId(), otherwise it is ignored.
            imageWriter.setCompression(compression.getOMEString(source));
            try {
                imageWriter.setId(finalOutputPath);
            } catch (FormatException e) {
                throw new IOException("Failed to open OME-TIFF writer for " + finalOutputPath, e);
            }
            // IMPORTANT: fetch the active writer AFTER setId(). setId() is where the
            // writer reads the metadata and configures interleaving; a reference
            // grabbed before setId() has interleaving unset and silently writes RGB
            // as planar (harmless for single-sample data, scrambles RGB).
            TiffWriter tiffWriter = (TiffWriter) imageWriter.getWriter();
            // The writer has its own interleaved flag (separate from the OME
            // PixelsInterleaved metadata) that governs how saveBytes interprets the
            // byte[]. RGB is supplied as interleaved R,G,B; planar channels are
            // supplied one channel-plane at a time.
            tiffWriter.setInterleaved(exportRGB);

            for (int level = 0; level < numLevels; level++) {
                long levelStart = System.nanoTime();
                double d = downsamples[level];
                int levelW = (int) (fullWidth / d);
                int levelH = (int) (fullHeight / d);
                try {
                    tiffWriter.setSeries(0);
                    tiffWriter.setResolution(level);
                } catch (FormatException e) {
                    throw new IOException("Failed to set resolution level " + level, e);
                }
                if (plan != null && level + 1 < numLevels && plan.stagedChannels(level + 1) > 0) {
                    staged[level + 1] = staging.create(
                            level + 1,
                            (int) (fullWidth / downsamples[level + 1]),
                            (int) (fullHeight / downsamples[level + 1]),
                            tileSize,
                            bytesPerPixel);
                }

                // Plane order must match the XYCZT dimension order declared in the
                // metadata: channel fastest, then z, then t. The sequential plane
                // index increments in exactly that nested order.
                int planeIndex = 0;
                for (int t = 0; t < sizeT; t++) {
                    for (int z = 0; z < sizeZ; z++) {
                        for (int c = 0; c < channelPlanes; c++) {
                            // One IFD per (level, plane), reused across all tiles of
                            // that plane so Bio-Formats accumulates the tile offsets.
                            IFD ifd = new IFD();
                            ifd.put(IFD.TILE_WIDTH, tileSize);
                            ifd.put(IFD.TILE_LENGTH, tileSize);

                            int inSlot = plan == null ? -1 : plan.slot(level, c, z + sizeZ * t);
                            int nextSlot = plan == null || level + 1 >= numLevels
                                    ? -1
                                    : plan.slot(level + 1, c, z + sizeZ * t);
                            for (int yy = 0; yy < levelH; yy += tileSize) {
                                int hh = Math.min(tileSize, levelH - yy);
                                for (int xx = 0; xx < levelW; xx += tileSize) {
                                    int ww = Math.min(tileSize, levelW - xx);
                                    if (sources != null) {
                                        writeStagedTile(
                                                tiffWriter,
                                                sources[c],
                                                ifd,
                                                planeIndex,
                                                z,
                                                t,
                                                level,
                                                d,
                                                xx,
                                                yy,
                                                ww,
                                                hh,
                                                tileSize,
                                                bytesPerPixel,
                                                staged[level],
                                                inSlot,
                                                level + 1 < numLevels ? staged[level + 1] : null,
                                                nextSlot);
                                    } else {
                                        writeTile(
                                                tiffWriter,
                                                planes[c],
                                                ifd,
                                                planeIndex,
                                                z,
                                                t,
                                                level,
                                                d,
                                                xx,
                                                yy,
                                                ww,
                                                hh,
                                                exportRGB,
                                                bytesPerPixel);
                                    }

                                    writtenTiles++;
                                    if (progressCallback != null && totalTiles > 0) {
                                        progressCallback.accept((double) writtenTiles / totalTiles);
                                    }
                                }
                            }
                            planeIndex++;
                        }
                    }
                }
                if (staged[level] != null) {
                    staging.release(level);
                    staged[level] = null;
                }
                logger.info(
                        "Wrote resolution level {}/{} ({}x{}) in {} s{}",
                        level + 1,
                        numLevels,
                        levelW,
                        levelH,
                        String.format("%.1f", (System.nanoTime() - levelStart) / 1e9),
                        sources == null
                                ? ""
                                : level == 0 || plan.stagedChannels(level) == 0
                                        ? ", from the source"
                                        : ", from the staged level before");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Direct OME-TIFF write failed for " + finalOutputPath, e);
        } finally {
            for (ImageServer<BufferedImage> pyramid : pyramids) {
                try {
                    pyramid.close();
                } catch (Exception closeEx) {
                    logger.warn("Pyramidalized source close() threw after OME-TIFF write: {}", closeEx.getMessage());
                }
            }
        }

        File out = new File(finalOutputPath);
        if (!out.exists() || out.length() == 0) {
            throw new IOException("OME-TIFF writer produced no output at " + finalOutputPath);
        }
        logger.info("Direct OME-TIFF complete: {}", finalOutputPath);
    }

    /**
     * The image one output channel plane is read from on the staged path, its band in it, and
     * which output levels that image stores itself.
     */
    private static final class PlaneSource {
        final ImageServer<BufferedImage> server;
        final int band;
        final boolean[] stored;
        /** Raster data type of the tiles read so far, -1 before the first. */
        int dataType = -1;

        PlaneSource(ImageServer<BufferedImage> server, int band, double[] downsamples) {
            this.server = server;
            this.band = band;
            this.stored = new boolean[downsamples.length];
            for (int level = 0; level < downsamples.length; level++) {
                stored[level] = level == 0 || NoDataPyramidServer.storesLevel(server, downsamples[level]);
            }
        }
    }

    /**
     * Where each staged plane of each level sits in its staged file, and the disk the staged
     * levels need at most.
     */
    private static final class StagingPlan {
        private final int[][] slotOfChannel;
        private final int[] channelsStaged;
        private final int zt;
        private final long peakBytes;

        StagingPlan(PlaneSource[] sources, double[] downsamples, int width, int height, int zt, int bytesPerPixel) {
            int levels = downsamples.length;
            this.zt = zt;
            slotOfChannel = new int[levels][sources.length];
            channelsStaged = new int[levels];
            long[] bytes = new long[levels + 1];
            for (int level = 0; level < levels; level++) {
                for (int c = 0; c < sources.length; c++) {
                    slotOfChannel[level][c] = sources[c].stored[level] ? -1 : channelsStaged[level]++;
                }
                bytes[level] = (long) channelsStaged[level]
                        * zt
                        * (long) (width / downsamples[level])
                        * (long) (height / downsamples[level])
                        * bytesPerPixel;
            }
            // Level L is read while level L + 1 is written, then deleted.
            long peak = 0;
            for (int level = 0; level < levels; level++) {
                peak = Math.max(peak, bytes[level] + bytes[level + 1]);
            }
            peakBytes = peak;
        }

        long peakBytes() {
            return peakBytes;
        }

        int stagedChannels(int level) {
            return channelsStaged[level];
        }

        /** Slot of channel {@code c} at {@code planeZT = z + sizeZ * t} in the level's file, or -1. */
        int slot(int level, int c, int planeZT) {
            int s = slotOfChannel[level][c];
            return s < 0 ? -1 : s + channelsStaged[level] * planeZT;
        }
    }

    /**
     * The image and band each channel plane is read from on the staged path, or null when the
     * image is RGB, not 8- or 16-bit unsigned, or the pyramid is not a plain halving from full
     * resolution with an even tile size.
     */
    private static PlaneSource[] stagedSources(
            ImageServer<BufferedImage> source,
            boolean exportRGB,
            int channelPlanes,
            int tileSize,
            double baseDownsample,
            double[] downsamples) {
        PixelType type = source.getPixelType();
        if (exportRGB
                || source.isRGB()
                || (type != PixelType.UINT8 && type != PixelType.UINT16)
                || tileSize % 2 != 0
                || baseDownsample != 1.0) {
            return null;
        }
        for (int level = 0; level < downsamples.length; level++) {
            if (downsamples[level] != (double) (1L << level)) {
                return null;
            }
        }
        PlaneSource[] sources = new PlaneSource[channelPlanes];
        if (source instanceof ChannelStack stack && isSplittable(source, stack)) {
            int c = 0;
            for (ImageServer<BufferedImage> part : stack.getChannelSources()) {
                for (int band = 0; band < part.nChannels(); band++) {
                    sources[c++] = new PlaneSource(part, band, downsamples);
                }
            }
            logger.info("Reading each of the {} channels from its own source image", channelPlanes);
        } else {
            for (int c = 0; c < channelPlanes; c++) {
                sources[c] = new PlaneSource(source, c, downsamples);
            }
        }
        return sources;
    }

    /**
     * Write one tile of one plane on the staged path: read it from the source, or from the staged
     * copy of this level when the source does not store it, and stage its 2 x 2 average for the
     * next level when that level is staged.
     *
     * @param in this level's staged copy, or null
     * @param inSlot this plane's slot in {@code in}, or -1 to read the source
     * @param next the next level's staged copy, or null
     * @param nextSlot this plane's slot in {@code next}, or -1 when the next level is not staged
     */
    private static void writeStagedTile(
            TiffWriter tiffWriter,
            PlaneSource ps,
            IFD ifd,
            int planeIndex,
            int z,
            int t,
            int level,
            double downsample,
            int xx,
            int yy,
            int ww,
            int hh,
            int tileSize,
            int bytesPerPixel,
            LevelStaging.StagedLevel in,
            int inSlot,
            LevelStaging.StagedLevel next,
            int nextSlot)
            throws IOException, FormatException {
        int tx = xx / tileSize;
        int ty = yy / tileSize;
        int[] samples;
        if (inSlot >= 0) {
            byte[] bytes = in.readTile(inSlot, tx, ty, ww, hh);
            tiffWriter.saveBytes(planeIndex, bytes, ifd, xx, yy, ww, hh);
            samples = nextSlot >= 0 ? unpackSamples(bytes, bytesPerPixel, ps.dataType == DataBuffer.TYPE_SHORT) : null;
        } else {
            ImageRegion region = ImageRegion.createInstance(xx, yy, ww, hh, z, t);
            RegionRequest request = TileRequest.createInstance(ps.server.getPath(), level, downsample, region)
                    .getRegionRequest();
            BufferedImage img = ps.server.readRegion(request);
            if (img == null) {
                tiffWriter.saveBytes(planeIndex, new byte[ww * hh * bytesPerPixel], ifd, xx, yy, ww, hh);
                samples = new int[ww * hh];
            } else {
                int type = img.getRaster().getDataBuffer().getDataType();
                if (type != DataBuffer.TYPE_BYTE && type != DataBuffer.TYPE_USHORT && type != DataBuffer.TYPE_SHORT) {
                    throw new IOException("Cannot stage pyramid levels of raster data type " + type);
                }
                if (ps.dataType >= 0 && ps.dataType != type) {
                    throw new IOException("Raster data type changed from " + ps.dataType + " to " + type);
                }
                ps.dataType = type;
                int aw = img.getWidth();
                int ah = img.getHeight();
                samples = img.getRaster().getSamples(0, 0, aw, ah, ps.band, (int[]) null);
                tiffWriter.saveBytes(planeIndex, packSamples(samples, bytesPerPixel), ifd, xx, yy, aw, ah);
                if (aw != ww || ah != hh) {
                    samples = fit(samples, aw, ah, ww, hh);
                }
            }
        }
        if (nextSlot >= 0) {
            next.writeBlock(nextSlot, tx, ty, ww / 2, hh / 2, packSamples(halve(samples, ww, hh), bytesPerPixel));
        }
    }

    /**
     * Each 2 x 2 block of {@code w x h} samples averaged, zeros left out, rounded as
     * {@link CompositorImageServer#areaAverage(BufferedImage, int, int, boolean)} rounds; an odd
     * last row or column is dropped, as the level below has {@code floor(w / 2)} columns.
     */
    static int[] halve(int[] samples, int w, int h) {
        int ow = w / 2;
        int oh = h / 2;
        int[] out = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int r0 = 2 * oy * w;
            int r1 = r0 + w;
            for (int ox = 0; ox < ow; ox++) {
                int x = 2 * ox;
                double sum = 0;
                int count = 0;
                int v = samples[r0 + x];
                if (v != 0) {
                    sum += v;
                    count++;
                }
                v = samples[r0 + x + 1];
                if (v != 0) {
                    sum += v;
                    count++;
                }
                v = samples[r1 + x];
                if (v != 0) {
                    sum += v;
                    count++;
                }
                v = samples[r1 + x + 1];
                if (v != 0) {
                    sum += v;
                    count++;
                }
                out[oy * ow + ox] = count == 0 ? 0 : (int) Math.rint(sum / count);
            }
        }
        return out;
    }

    /** The inverse of {@link #packSamples}: unsigned bytes, 16-bit signed when {@code signed}. */
    static int[] unpackSamples(byte[] bytes, int bytesPerPixel, boolean signed) {
        int n = bytes.length / bytesPerPixel;
        int[] out = new int[n];
        if (bytesPerPixel == 2) {
            for (int i = 0; i < n; i++) {
                int v = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
                out[i] = signed ? (short) v : v;
            }
        } else {
            for (int i = 0; i < n; i++) {
                out[i] = bytes[i] & 0xFF;
            }
        }
        return out;
    }

    /** {@code samples} of an {@code aw x ah} tile cropped or zero-padded to {@code w x h}. */
    private static int[] fit(int[] samples, int aw, int ah, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < Math.min(ah, h); y++) {
            System.arraycopy(samples, y * aw, out, y * w, Math.min(aw, w));
        }
        return out;
    }

    /** The pyramid one output channel plane is read from, and that channel's band in it. */
    private record Plane(ImageServer<BufferedImage> pyramid, int band) {}

    /**
     * The pyramid each output channel plane is read from. Non-RGB images get
     * {@link NoDataPyramidServer}: every level made from the one before at full bit depth, with
     * the mosaic's empty (zero) canvas left out of the averages. A {@link ChannelStack} gets one
     * such pyramid per source image, so plane c reads only the image holding channel c.
     *
     * @param pyramids receives every pyramid made, for closing
     */
    private static Plane[] planePyramids(
            ImageServer<BufferedImage> source,
            boolean exportRGB,
            int channelPlanes,
            int tileSize,
            double[] downsamples,
            List<ImageServer<BufferedImage>> pyramids) {
        Plane[] planes = new Plane[channelPlanes];
        if (!exportRGB && !source.isRGB() && source instanceof ChannelStack stack && isSplittable(source, stack)) {
            int c = 0;
            for (ImageServer<BufferedImage> part : stack.getChannelSources()) {
                ImageServer<BufferedImage> pyramid = new NoDataPyramidServer(part, tileSize, tileSize, downsamples);
                pyramids.add(pyramid);
                for (int band = 0; band < part.nChannels(); band++) {
                    planes[c++] = new Plane(pyramid, band);
                }
            }
            logger.info("Reading each of the {} channels from its own source image", channelPlanes);
            return planes;
        }
        ImageServer<BufferedImage> pyramid = exportRGB || source.isRGB()
                ? ImageServers.pyramidalizeTiled(source, tileSize, tileSize, downsamples)
                : new NoDataPyramidServer(source, tileSize, tileSize, downsamples);
        pyramids.add(pyramid);
        for (int c = 0; c < channelPlanes; c++) {
            planes[c] = new Plane(pyramid, c);
        }
        return planes;
    }

    /** Whether every channel of {@code source} can be read from the stack's sources as they are. */
    private static boolean isSplittable(ImageServer<BufferedImage> source, ChannelStack stack) {
        List<ImageServer<BufferedImage>> parts = stack.getChannelSources();
        int channels = 0;
        for (ImageServer<BufferedImage> part : parts) {
            if (part.isRGB()
                    || part.getWidth() != source.getWidth()
                    || part.getHeight() != source.getHeight()
                    || part.getPixelType() != source.getPixelType()
                    || Math.max(1, part.nZSlices()) != Math.max(1, source.nZSlices())
                    || Math.max(1, part.nTimepoints()) != Math.max(1, source.nTimepoints())) {
                return false;
            }
            channels += part.nChannels();
        }
        return channels == source.nChannels();
    }

    /**
     * Read one tile of one channel plane at (z, t) and hand its packed bytes to Bio-Formats.
     *
     * @param planeIndex sequential OME plane index (for {@code saveBytes})
     * @param plane the pyramid to read and the band to extract for planar output (ignored for RGB)
     * @param z z-slice to read
     * @param t timepoint to read
     */
    private static void writeTile(
            TiffWriter tiffWriter,
            Plane plane,
            IFD ifd,
            int planeIndex,
            int z,
            int t,
            int level,
            double downsample,
            int xx,
            int yy,
            int ww,
            int hh,
            boolean exportRGB,
            int bytesPerPixel)
            throws IOException, FormatException {

        ImageRegion region = ImageRegion.createInstance(xx, yy, ww, hh, z, t);
        TileRequest tile = TileRequest.createInstance(plane.pyramid().getPath(), level, downsample, region);
        RegionRequest request = tile.getRegionRequest();
        BufferedImage img = plane.pyramid().readRegion(request);

        if (img == null) {
            int samples = exportRGB ? 3 : 1;
            byte[] zeros = new byte[ww * hh * bytesPerPixel * samples];
            tiffWriter.saveBytes(planeIndex, zeros, ifd, xx, yy, ww, hh);
            return;
        }

        int aw = img.getWidth();
        int ah = img.getHeight();
        byte[] buf = exportRGB ? packInterleavedRGB(img, aw, ah) : packPlane(img, plane.band(), aw, ah, bytesPerPixel);
        tiffWriter.saveBytes(planeIndex, buf, ifd, xx, yy, aw, ah);
    }

    /** Pack an RGB tile as interleaved R,G,B bytes (3 samples per pixel, BIP). */
    private static byte[] packInterleavedRGB(BufferedImage img, int w, int h) {
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] buf = new byte[w * h * 3];
        int bi = 0;
        for (int p : rgb) {
            buf[bi++] = (byte) ((p >> 16) & 0xFF);
            buf[bi++] = (byte) ((p >> 8) & 0xFF);
            buf[bi++] = (byte) (p & 0xFF);
        }
        return buf;
    }

    /**
     * Pack a single channel plane. 8-bit -> one byte per pixel; 16-bit -> two
     * big-endian bytes per pixel (matching {@code setPixelsBigEndian(true)}).
     */
    private static byte[] packPlane(BufferedImage img, int channel, int w, int h, int bytesPerPixel) {
        return packSamples(img.getRaster().getSamples(0, 0, w, h, channel, (int[]) null), bytesPerPixel);
    }

    /** As {@link #packPlane}, from the samples. */
    static byte[] packSamples(int[] samples, int bytesPerPixel) {
        if (bytesPerPixel == 2) {
            byte[] buf = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                int v = samples[i];
                buf[2 * i] = (byte) ((v >> 8) & 0xFF);
                buf[2 * i + 1] = (byte) (v & 0xFF);
            }
            return buf;
        }
        byte[] buf = new byte[samples.length];
        for (int i = 0; i < samples.length; i++) {
            buf[i] = (byte) samples[i];
        }
        return buf;
    }

    /**
     * Populate the OME metadata for a single-series image with the source's
     * channel, z-slice, and timepoint counts.
     *
     * <p>The required OME fields (Image/Pixels IDs, endianness, dimension order,
     * pixel type, X/Y/Z/C/T sizes, per-channel IDs and samples-per-pixel) are set
     * through Bio-Formats' own {@link MetadataTools#populateMetadata} helper, so
     * they follow the OME data model / Bio-Formats conventions rather than being
     * hand-rolled. The fields the helper does not cover -- channel interleaving,
     * channel colors and names, physical pixel size, and pyramid resolution sizes
     * -- are set explicitly afterwards.
     */
    private static void initializeMetadata(
            IMetadata meta,
            ImageServer<BufferedImage> source,
            PixelType pixelType,
            boolean exportRGB,
            int width,
            int height,
            double[] downsamples) {

        int series = 0;
        int nChannels = source.nChannels();
        int sizeZ = Math.max(1, source.nZSlices());
        int sizeT = Math.max(1, source.nTimepoints());
        double base = downsamples[0];
        int sizeX = (int) (width / base);
        int sizeY = (int) (height / base);

        // Channels are written either as a single interleaved plane carrying N
        // samples (RGB) or as N planar single-sample channels. In OME terms that is
        // sizeC = N with samplesPerPixel = N (RGB) or 1 (planar).
        int samplesPerPixel = exportRGB ? nChannels : 1;

        // Required OME fields via the Bio-Formats helper. littleEndian = false
        // because packPlane() emits 16-bit samples big-endian; single Z, single T.
        MetadataTools.populateMetadata(
                meta,
                series,
                null, // image name
                false, // littleEndian -> big-endian
                DimensionOrder.XYCZT.getValue(),
                toOmePixelType(pixelType).getValue(),
                sizeX,
                sizeY,
                sizeZ,
                nChannels, // sizeC
                sizeT,
                samplesPerPixel);

        // Re-assert big-endian explicitly: packPlane() writes 16-bit samples
        // big-endian, so the OME metadata must agree regardless of the helper's
        // internal default.
        meta.setPixelsBigEndian(Boolean.TRUE, series);

        // Fields populateMetadata does not set: interleaving, channel colors and
        // names. (Channel IDs and samples-per-pixel are already set by the helper.)
        meta.setPixelsInterleaved(exportRGB ? Boolean.TRUE : Boolean.FALSE, series);
        if (!exportRGB) {
            for (int c = 0; c < nChannels; c++) {
                ImageChannel channel = source.getChannel(c);
                Integer color = channel.getColor();
                if (color != null) {
                    // OME Color packs RGBA; an alpha of 0 is the OME-TIFF convention
                    // used by Bio-Formats readers for opaque channel colors.
                    meta.setChannelColor(
                            new Color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 0), series, c);
                }
                meta.setChannelName(channel.getName(), series, c);
            }
        }

        var cal = source.getPixelCalibration();
        if (cal.hasPixelSizeMicrons()) {
            meta.setPixelsPhysicalSizeX(new Length(cal.getPixelWidthMicrons() * base, UNITS.MICROMETER), series);
            meta.setPixelsPhysicalSizeY(new Length(cal.getPixelHeightMicrons() * base, UNITS.MICROMETER), series);
        }
        if (!Double.isNaN(cal.getZSpacingMicrons())) {
            meta.setPixelsPhysicalSizeZ(new Length(cal.getZSpacingMicrons(), UNITS.MICROMETER), series);
        }

        for (int level = 0; level < downsamples.length; level++) {
            double d = downsamples[level];
            int w = (int) (width / d);
            int h = (int) (height / d);
            ((IPyramidStore) meta).setResolutionSizeX(new PositiveInteger(w), series, level);
            ((IPyramidStore) meta).setResolutionSizeY(new PositiveInteger(h), series, level);
        }
    }

    private static ome.xml.model.enums.PixelType toOmePixelType(PixelType pixelType) {
        switch (pixelType) {
            case UINT8:
                return ome.xml.model.enums.PixelType.UINT8;
            case INT8:
                return ome.xml.model.enums.PixelType.INT8;
            case UINT16:
                return ome.xml.model.enums.PixelType.UINT16;
            case INT16:
                return ome.xml.model.enums.PixelType.INT16;
            case UINT32:
                return ome.xml.model.enums.PixelType.UINT32;
            case INT32:
                return ome.xml.model.enums.PixelType.INT32;
            case FLOAT32:
                return ome.xml.model.enums.PixelType.FLOAT;
            case FLOAT64:
                return ome.xml.model.enums.PixelType.DOUBLE;
            default:
                throw new IllegalArgumentException("Unsupported pixel type for OME-TIFF: " + pixelType);
        }
    }

    /**
     * Halve the dimensions each level (scale 2.0) and stop once the next level
     * would be smaller than the tile size in either dimension. Always includes
     * downsample 1.0. Matches the level convention used by the ZARR direct path
     * ({@code width >> level}).
     */
    static double[] computePyramidDownsamples(int width, int height, double baseDownsample, int tileSize) {
        java.util.List<Double> levels = new java.util.ArrayList<>();
        double d = baseDownsample;
        levels.add(d);
        while (levels.size() < 16) {
            double next = d * 2.0;
            int nextW = (int) (width / next);
            int nextH = (int) (height / next);
            if (nextW < tileSize || nextH < tileSize) {
                break;
            }
            levels.add(next);
            d = next;
        }
        double[] out = new double[levels.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = levels.get(i);
        }
        return out;
    }

    private static long estimatePixelBytes(
            int width, int height, int nChannels, int bytesPerPixel, double[] downsamples) {
        long total = 0;
        for (double d : downsamples) {
            total += (long) (width / d) * (long) (height / d) * nChannels * bytesPerPixel;
        }
        return total;
    }

    private static long countTiles(double[] downsamples, int width, int height, int tileSize) {
        long total = 0;
        for (double d : downsamples) {
            int levelW = (int) (width / d);
            int levelH = (int) (height / d);
            long tilesX = (levelW + tileSize - 1) / tileSize;
            long tilesY = (levelH + tileSize - 1) / tileSize;
            total += tilesX * tilesY;
        }
        return total;
    }
}
