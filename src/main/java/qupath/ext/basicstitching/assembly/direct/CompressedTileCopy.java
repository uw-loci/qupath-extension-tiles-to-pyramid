package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import loci.common.RandomAccessInputStream;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.out.TiffWriter;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffCompression;
import loci.formats.tiff.TiffParser;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;

/**
 * The compressed tiles of the OME-TIFFs a {@link ChannelStack} is made of, read as stored, so a
 * merge can write them without decoding and encoding them again.
 *
 * <p>{@link #open} returns one only when copying gives the pixels the decode path writes: each part
 * is a single-file OME-TIFF with one image, its tiles the output's size and compression (LZW, ZLIB
 * or none: lossless, so decoding and encoding again gives the same samples), 8- or 16-bit unsigned,
 * one sample per pixel, no predictor, big-endian like the output, and every output level stored as
 * a sub-resolution of exactly the output's size. The decode path reads those same stored levels
 * (see {@link NoDataPyramidServer#storesLevel}), so its tiles hold the same samples.
 */
final class CompressedTileCopy implements AutoCloseable {

    /** {@code -Dt2p.mergeTileCopy=false} turns the copy off (tests, and comparing on a machine). */
    static final String PROPERTY = "t2p.mergeTileCopy";

    /** One per part, for the tile reads (Bio-Formats' stream copies byte by byte: 58 % of a copy). */
    private final List<FileChannel> files;
    /** Per output plane (c + C * (z + Z * t)) and level: the stream, tile offsets and byte counts. */
    private final PlaneLevel[][] planes;

    private CompressedTileCopy(List<FileChannel> files, PlaneLevel[][] planes) {
        this.files = files;
        this.planes = planes;
    }

    private record PlaneLevel(FileChannel in, long[] offsets, long[] counts, int tilesAcross) {}

    /** Why a merge cannot copy compressed tiles. */
    static final class NotCopyable extends Exception {
        NotCopyable(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * Open the parts of {@code image} for copying, or say why their tiles cannot be copied.
     *
     * @param image the merged image being written
     * @param stack its parts
     * @param omeCompression the output's Bio-Formats compression name
     * @param tileSize the output's tile edge
     * @param downsamples the output's levels (level 0 must be 1)
     * @throws NotCopyable with the reason, when the tiles must be decoded and encoded
     */
    static CompressedTileCopy open(
            ImageServer<BufferedImage> image,
            ChannelStack stack,
            String omeCompression,
            int tileSize,
            double[] downsamples)
            throws NotCopyable {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) {
            throw new NotCopyable("-D" + PROPERTY + "=false");
        }
        int expectedCode = compressionCode(omeCompression);
        if (tileSize % 16 != 0) {
            // Bio-Formats rounds a writer's tile size to a multiple of 16.
            throw new NotCopyable("the tile size " + tileSize + " is not a multiple of 16");
        }
        PixelType type = image.getPixelType();
        if (type != PixelType.UINT8 && type != PixelType.UINT16) {
            throw new NotCopyable("the pixel type is " + type + " (only 8- and 16-bit unsigned are copied)");
        }
        for (int level = 0; level < downsamples.length; level++) {
            if (downsamples[level] != (double) (1L << level)) {
                throw new NotCopyable("the output levels are not successive halvings from full resolution");
            }
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int sizeZ = Math.max(1, image.nZSlices());
        int sizeT = Math.max(1, image.nTimepoints());
        int nChannels = image.nChannels();
        int bytesPerSample = type.getBytesPerPixel();

        List<FileChannel> files = new ArrayList<>();
        PlaneLevel[][] planes = new PlaneLevel[nChannels * sizeZ * sizeT][];
        boolean ok = false;
        try {
            int firstChannel = 0;
            for (ImageServer<BufferedImage> part : stack.getChannelSources()) {
                Path file = singleFile(part);
                String name = file.getFileName().toString();
                FileChannel channel;
                RandomAccessInputStream in;
                try {
                    channel = FileChannel.open(file, StandardOpenOption.READ);
                    files.add(channel);
                    in = new RandomAccessInputStream(file.toString());
                } catch (IOException e) {
                    throw new NotCopyable(name + " cannot be opened: " + e.getMessage());
                }
                try (RandomAccessInputStream parsed = in) {
                    TiffParser parser = new TiffParser(parsed);
                    Part p = readPart(parser, name, part.nChannels(), sizeZ, sizeT);
                    if (bytesPerSample > 1 && parsed.isLittleEndian()) {
                        throw new NotCopyable(name + " stores its samples little-endian");
                    }
                    for (int t = 0; t < sizeT; t++) {
                        for (int z = 0; z < sizeZ; z++) {
                            for (int band = 0; band < part.nChannels(); band++) {
                                IFD main = p.ifds.get(p.ifdOf(z, band, t));
                                int out = firstChannel + band + nChannels * (z + sizeZ * t);
                                planes[out] = levels(
                                        parser,
                                        channel,
                                        main,
                                        name,
                                        expectedCode,
                                        omeCompression,
                                        bytesPerSample,
                                        tileSize,
                                        width,
                                        height,
                                        downsamples.length);
                            }
                        }
                    }
                }
                firstChannel += part.nChannels();
            }
            ok = true;
            return new CompressedTileCopy(files, planes);
        } catch (IOException | FormatException e) {
            throw new NotCopyable("a part's TIFF structure could not be read: " + e.getMessage());
        } finally {
            if (!ok) {
                closeAll(files);
            }
        }
    }

    /** The compressed bytes of tile ({@code tx}, {@code ty}) of an output plane at a level. */
    byte[] readTile(int plane, int level, int tx, int ty) throws IOException {
        PlaneLevel pl = planes[plane][level];
        int i = ty * pl.tilesAcross() + tx;
        byte[] buf = new byte[(int) pl.counts()[i]];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        long position = pl.offsets()[i];
        while (bb.hasRemaining()) {
            int n = pl.in().read(bb, position);
            if (n < 0) {
                throw new EOFException("Tile ends beyond the end of its file");
            }
            position += n;
        }
        return buf;
    }

    @Override
    public void close() {
        closeAll(files);
    }

    private static void closeAll(List<FileChannel> files) {
        for (FileChannel in : files) {
            try {
                in.close();
            } catch (IOException e) {
                // Read-only; nothing to recover.
            }
        }
    }

    /** The TIFF compression code an output with this Bio-Formats compression name has. */
    private static int compressionCode(String omeCompression) throws NotCopyable {
        if (TiffWriter.COMPRESSION_LZW.equals(omeCompression)) {
            return TiffCompression.LZW.getCode();
        }
        if (TiffWriter.COMPRESSION_ZLIB.equals(omeCompression)) {
            return TiffCompression.DEFLATE.getCode();
        }
        if (TiffWriter.COMPRESSION_UNCOMPRESSED.equals(omeCompression)) {
            return TiffCompression.UNCOMPRESSED.getCode();
        }
        throw new NotCopyable("the output compression is " + omeCompression + " (only LZW, ZLIB and none are copied)");
    }

    private static Path singleFile(ImageServer<BufferedImage> part) throws NotCopyable {
        Collection<URI> uris = part.getURIs();
        if (uris.size() != 1) {
            throw new NotCopyable("a part is not one file (" + uris.size() + " URIs)");
        }
        URI uri = uris.iterator().next();
        if (!"file".equals(uri.getScheme())) {
            throw new NotCopyable("a part is not a local file (" + uri + ")");
        }
        Path file = Path.of(uri);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ome.tif") && !name.endsWith(".ome.tiff")) {
            throw new NotCopyable(file.getFileName() + " is not an OME-TIFF");
        }
        return file;
    }

    /** A part's main IFDs and which of them holds each plane. */
    private record Part(IFDList ifds, int[] ifdOfPlane, String order, int sizeZ, int sizeC, int sizeT) {
        int ifdOf(int z, int c, int t) {
            return ifdOfPlane[FormatTools.getIndex(order, sizeZ, sizeC, sizeT, ifdOfPlane.length, z, c, t)];
        }
    }

    /** The main IFD of every plane, from the file's own OME-XML TiffData. */
    private static Part readPart(TiffParser parser, String name, int sizeC, int sizeZ, int sizeT)
            throws NotCopyable, IOException {
        IFDList ifds = parser.getMainIFDs();
        String xml = parser.getComment();
        if (xml == null || !xml.contains("<OME")) {
            throw new NotCopyable(name + " has no OME-XML");
        }
        OMEXMLMetadata meta;
        try {
            meta = new ServiceFactory().getInstance(OMEXMLService.class).createOMEXMLMetadata(xml);
        } catch (Exception e) {
            throw new NotCopyable(name + " has OME-XML that cannot be parsed: " + e.getMessage());
        }
        if (meta.getImageCount() != 1) {
            throw new NotCopyable(name + " holds " + meta.getImageCount() + " images");
        }
        if (meta.getPixelsSizeC(0).getValue() != sizeC
                || meta.getPixelsSizeZ(0).getValue() != sizeZ
                || meta.getPixelsSizeT(0).getValue() != sizeT) {
            throw new NotCopyable(name + "'s OME-XML does not match the image QuPath opened");
        }
        String order = meta.getPixelsDimensionOrder(0).getValue();
        int n = sizeC * sizeZ * sizeT;
        int[] ifdOfPlane = new int[n];
        java.util.Arrays.fill(ifdOfPlane, -1);
        int blocks = meta.getTiffDataCount(0);
        if (blocks == 0) {
            throw new NotCopyable(name + "'s OME-XML does not say which IFD holds each plane");
        }
        try {
            for (int b = 0; b < blocks; b++) {
                String fileName = uuidFileName(meta, b);
                if (fileName != null && !fileName.equals(name)) {
                    throw new NotCopyable(name + " has planes in another file (" + fileName + ")");
                }
                int ifd = value(meta.getTiffDataIFD(0, b), 0);
                int fz = value(meta.getTiffDataFirstZ(0, b), 0);
                int fc = value(meta.getTiffDataFirstC(0, b), 0);
                int ft = value(meta.getTiffDataFirstT(0, b), 0);
                Integer count = meta.getTiffDataPlaneCount(0, b) == null
                        ? null
                        : meta.getTiffDataPlaneCount(0, b).getValue();
                int start = FormatTools.getIndex(order, sizeZ, sizeC, sizeT, n, fz, fc, ft);
                int planes = count != null ? count : blocks == 1 ? n - start : 1;
                for (int k = 0; k < planes && start + k < n; k++) {
                    ifdOfPlane[start + k] = ifd + k;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new NotCopyable(name + "'s TiffData cannot be read: " + e.getMessage());
        }
        for (int i : ifdOfPlane) {
            if (i < 0 || i >= ifds.size()) {
                throw new NotCopyable(name + "'s OME-XML leaves a plane without an IFD in the file");
            }
        }
        return new Part(ifds, ifdOfPlane, order, sizeZ, sizeC, sizeT);
    }

    private static String uuidFileName(OMEXMLMetadata meta, int block) {
        try {
            return meta.getUUIDFileName(0, block);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int value(ome.xml.model.primitives.NonNegativeInteger v, int fallback) {
        return v == null ? fallback : v.getValue();
    }

    /** Tile offsets and byte counts of one plane at every output level, after checking each IFD. */
    private static PlaneLevel[] levels(
            TiffParser parser,
            FileChannel in,
            IFD main,
            String name,
            int expectedCode,
            String omeCompression,
            int bytesPerSample,
            int tileSize,
            int width,
            int height,
            int numLevels)
            throws NotCopyable, IOException, FormatException {
        IFDList subs = parser.getSubIFDs(main);
        if (subs.size() < numLevels - 1) {
            throw new NotCopyable(name + " stores " + (subs.size() + 1) + " levels, the output has " + numLevels);
        }
        PlaneLevel[] out = new PlaneLevel[numLevels];
        for (int level = 0; level < numLevels; level++) {
            IFD ifd = level == 0 ? main : subs.get(level - 1);
            parser.fillInIFD(ifd);
            int w = width >> level;
            int h = height >> level;
            String where = name + " level " + (level + 1);
            if (ifd.getImageWidth() != w || ifd.getImageLength() != h) {
                throw new NotCopyable(where + " is " + ifd.getImageWidth() + " x " + ifd.getImageLength()
                        + ", the output's is " + w + " x " + h);
            }
            if (!ifd.isTiled() || ifd.getTileWidth() != tileSize || ifd.getTileLength() != tileSize) {
                throw new NotCopyable(where + " is not in tiles of " + tileSize + " px");
            }
            if (ifd.getIFDIntValue(IFD.COMPRESSION, 1) != expectedCode) {
                throw new NotCopyable(where + " uses TIFF compression " + ifd.getIFDIntValue(IFD.COMPRESSION, 1)
                        + ", the output " + omeCompression);
            }
            if (ifd.getSamplesPerPixel() != 1) {
                throw new NotCopyable(where + " has " + ifd.getSamplesPerPixel() + " samples per pixel");
            }
            int[] bits = ifd.getBitsPerSample();
            if (bits.length < 1 || bits[0] != 8 * bytesPerSample) {
                throw new NotCopyable(where + " has " + (bits.length < 1 ? "no" : bits[0]) + " bits per sample");
            }
            if (ifd.getIFDIntValue(IFD.SAMPLE_FORMAT, 1) != 1) {
                throw new NotCopyable(where + " is not unsigned integer");
            }
            if (ifd.getIFDIntValue(IFD.PREDICTOR, 1) != 1) {
                throw new NotCopyable(where + " uses a predictor");
            }
            int across = (w + tileSize - 1) / tileSize;
            int down = (h + tileSize - 1) / tileSize;
            long[] offsets = unsigned(ifd.getIFDLongArray(IFD.TILE_OFFSETS), parser.isBigTiff());
            long[] counts = unsigned(ifd.getIFDLongArray(IFD.TILE_BYTE_COUNTS), parser.isBigTiff());
            if (offsets == null
                    || counts == null
                    || offsets.length != (long) across * down
                    || counts.length != offsets.length) {
                throw new NotCopyable(where + " does not list one offset and byte count per tile");
            }
            long length = in.size();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] <= 0
                        || counts[i] > Integer.MAX_VALUE
                        || offsets[i] < 0
                        || offsets[i] + counts[i] > length) {
                    throw new NotCopyable(where + " has a tile with no data or beyond the end of the file");
                }
            }
            out[level] = new PlaneLevel(in, offsets, counts, across);
        }
        return out;
    }

    /** TIFF LONG values read as signed ints wrap; BigTIFF LONG8 values do not. */
    private static long[] unsigned(long[] values, boolean bigTiff) {
        if (values != null && !bigTiff) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] < 0) {
                    values[i] += 0x100000000L;
                }
            }
        }
        return values;
    }
}
