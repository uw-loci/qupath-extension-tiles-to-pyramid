package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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
 * (fresh, non-colliding) output path, which the caller deletes.
 *
 * <p>The pixel data is read from the supplied server through
 * {@link ImageServers#pyramidalizeTiled} so downsampled levels are generated in
 * bounded, tiled reads. The Bio-Formats writer still performs a single
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

        int fullWidth = source.getWidth();
        int fullHeight = source.getHeight();
        PixelType pixelType = source.getPixelType();
        int bytesPerPixel = pixelType.getBytesPerPixel();
        int nChannels = source.nChannels();

        // RGB is exported interleaved (3 samples-per-pixel in one plane); anything
        // else is planar (one IFD plane per channel). Mirrors the guard in the
        // former PyramidImageWriter path so a misclassified non-3-channel server
        // never silently drops channels under an interleaved codec.
        boolean exportRGB = source.isRGB() && nChannels == 3 && pixelType == PixelType.UINT8;

        double[] downsamples = computePyramidDownsamples(fullWidth, fullHeight, baseDownsample, tileSize);
        int numLevels = downsamples.length;

        logger.info(
                "Direct OME-TIFF: {}x{}, {} channels, {} bit, RGB={}, {} levels, tile={}, compression={}",
                fullWidth,
                fullHeight,
                nChannels,
                bytesPerPixel * 8,
                exportRGB,
                numLevels,
                tileSize,
                compression);

        // Pyramidalize the source so downsampled levels are produced in bounded
        // tiled reads (a single high-level tile would otherwise read a huge
        // full-resolution region from the compositor).
        ImageServer<BufferedImage> pyramidServer =
                ImageServers.pyramidalizeTiled(source, tileSize, tileSize, downsamples);
        String serverPath = pyramidServer.getPath();

        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        initializeMetadata(meta, source, pixelType, exportRGB, fullWidth, fullHeight, downsamples);

        long estimatedBytes = estimatePixelBytes(fullWidth, fullHeight, nChannels, bytesPerPixel, downsamples);
        boolean bigTiff = estimatedBytes >= (Integer.MAX_VALUE - 1024L * 1024L * 100L);

        // Number of TIFF planes per resolution level: 1 for interleaved RGB,
        // nChannels for planar.
        int planesPerLevel = exportRGB ? 1 : nChannels;
        long totalTiles = countTiles(downsamples, fullWidth, fullHeight, tileSize) * planesPerLevel;
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
                double d = downsamples[level];
                int levelW = (int) (fullWidth / d);
                int levelH = (int) (fullHeight / d);
                try {
                    tiffWriter.setSeries(0);
                    tiffWriter.setResolution(level);
                } catch (FormatException e) {
                    throw new IOException("Failed to set resolution level " + level, e);
                }

                for (int plane = 0; plane < planesPerLevel; plane++) {
                    // One IFD per (level, plane), reused across all tiles of that
                    // plane so Bio-Formats accumulates the tile offsets into it.
                    IFD ifd = new IFD();
                    ifd.put(IFD.TILE_WIDTH, tileSize);
                    ifd.put(IFD.TILE_LENGTH, tileSize);

                    for (int yy = 0; yy < levelH; yy += tileSize) {
                        int hh = Math.min(tileSize, levelH - yy);
                        for (int xx = 0; xx < levelW; xx += tileSize) {
                            int ww = Math.min(tileSize, levelW - xx);
                            writeTile(
                                    tiffWriter,
                                    pyramidServer,
                                    serverPath,
                                    ifd,
                                    plane,
                                    level,
                                    d,
                                    xx,
                                    yy,
                                    ww,
                                    hh,
                                    exportRGB,
                                    bytesPerPixel);

                            writtenTiles++;
                            if (progressCallback != null && totalTiles > 0) {
                                progressCallback.accept((double) writtenTiles / totalTiles);
                            }
                        }
                    }
                }
                logger.info("Wrote resolution level {}/{} ({}x{})", level + 1, numLevels, levelW, levelH);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Direct OME-TIFF write failed for " + finalOutputPath, e);
        } finally {
            try {
                pyramidServer.close();
            } catch (Exception closeEx) {
                logger.warn("Pyramidalized source close() threw after OME-TIFF write: {}", closeEx.getMessage());
            }
        }

        File out = new File(finalOutputPath);
        if (!out.exists() || out.length() == 0) {
            throw new IOException("OME-TIFF writer produced no output at " + finalOutputPath);
        }
        logger.info("Direct OME-TIFF complete: {}", finalOutputPath);
    }

    /** Read one tile from the pyramidalized server and hand its packed bytes to Bio-Formats. */
    private static void writeTile(
            TiffWriter tiffWriter,
            ImageServer<BufferedImage> pyramidServer,
            String serverPath,
            IFD ifd,
            int plane,
            int level,
            double downsample,
            int xx,
            int yy,
            int ww,
            int hh,
            boolean exportRGB,
            int bytesPerPixel)
            throws IOException, FormatException {

        ImageRegion region = ImageRegion.createInstance(xx, yy, ww, hh, 0, 0);
        TileRequest tile = TileRequest.createInstance(serverPath, level, downsample, region);
        RegionRequest request = tile.getRegionRequest();
        BufferedImage img = pyramidServer.readRegion(request);

        if (img == null) {
            int samples = exportRGB ? 3 : 1;
            byte[] zeros = new byte[ww * hh * bytesPerPixel * samples];
            tiffWriter.saveBytes(plane, zeros, ifd, xx, yy, ww, hh);
            return;
        }

        int aw = img.getWidth();
        int ah = img.getHeight();
        byte[] buf = exportRGB ? packInterleavedRGB(img, aw, ah) : packPlane(img, plane, aw, ah, bytesPerPixel);
        tiffWriter.saveBytes(plane, buf, ifd, xx, yy, aw, ah);
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
        int[] samples = img.getRaster().getSamples(0, 0, w, h, channel, (int[]) null);
        if (bytesPerPixel == 2) {
            byte[] buf = new byte[w * h * 2];
            for (int i = 0; i < samples.length; i++) {
                int v = samples[i];
                buf[2 * i] = (byte) ((v >> 8) & 0xFF);
                buf[2 * i + 1] = (byte) (v & 0xFF);
            }
            return buf;
        }
        byte[] buf = new byte[w * h];
        for (int i = 0; i < samples.length; i++) {
            buf[i] = (byte) samples[i];
        }
        return buf;
    }

    /**
     * Populate the OME metadata for a single-series, single-Z, single-T image.
     *
     * <p>The required OME fields (Image/Pixels IDs, endianness, dimension order,
     * pixel type, X/Y/Z/C/T sizes, per-channel IDs and samples-per-pixel) are set
     * through Bio-Formats' own {@link MetadataTools#populateMetadata} helper, so
     * they follow the OME data model / Bio-Formats conventions rather than being
     * hand-rolled. The fields the helper does not cover -- channel interleaving,
     * channel colours and names, physical pixel size, and pyramid resolution sizes
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
                1, // sizeZ
                nChannels, // sizeC
                1, // sizeT
                samplesPerPixel);

        // Re-assert big-endian explicitly: packPlane() writes 16-bit samples
        // big-endian, so the OME metadata must agree regardless of the helper's
        // internal default.
        meta.setPixelsBigEndian(Boolean.TRUE, series);

        // Fields populateMetadata does not set: interleaving, channel colours and
        // names. (Channel IDs and samples-per-pixel are already set by the helper.)
        meta.setPixelsInterleaved(exportRGB ? Boolean.TRUE : Boolean.FALSE, series);
        if (!exportRGB) {
            for (int c = 0; c < nChannels; c++) {
                ImageChannel channel = source.getChannel(c);
                Integer color = channel.getColor();
                if (color != null) {
                    // OME Color packs RGBA; an alpha of 0 is the OME-TIFF convention
                    // used by Bio-Formats readers for opaque channel colours.
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
