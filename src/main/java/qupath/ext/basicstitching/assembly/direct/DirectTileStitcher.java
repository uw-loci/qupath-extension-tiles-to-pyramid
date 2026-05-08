package qupath.ext.basicstitching.assembly.direct;

import com.bc.zarr.Compressor;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.assembly.PyramidImageWriter;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.stitching.TileMapping;

/**
 * Memory-efficient stitcher for large tile counts (500+) that bypasses
 * SparseImageServer entirely.
 * <p>
 * Supports both output formats:
 * <ul>
 *   <li><b>OME-ZARR</b>: Direct chunk writing via JZarr (most memory-efficient)</li>
 *   <li><b>OME-TIFF</b>: Creates a {@link CompositorImageServer} that feeds the
 *       existing {@link PyramidImageWriter} / {@code OMEPyramidWriter}. The compositor
 *       replaces SparseImageServer as the read path, keeping memory bounded.</li>
 * </ul>
 * <p>
 * Instead of opening all tile files simultaneously (which OOMs at 1600+ tiles),
 * this stitcher:
 * <ol>
 *   <li>Builds a spatial index from tile positions (metadata only, no pixels)</li>
 *   <li>For each output tile/chunk, queries the index for contributing tiles (typically 1-4)</li>
 *   <li>Opens only those tiles via a bounded reader pool (max 8 open files)</li>
 *   <li>Composites pixels into the output buffer</li>
 *   <li>Writes to the target format</li>
 * </ol>
 * <p>
 * Memory usage: ~40 MB steady state vs 2-4+ GB for the SparseImageServer path.
 */
public class DirectTileStitcher {

    private static final Logger logger = LoggerFactory.getLogger(DirectTileStitcher.class);

    private static final int DEFAULT_CHUNK_SIZE = 1024;
    private static final int DEFAULT_MAX_OPEN_READERS = 64;
    private static final int TILE_COUNT_THRESHOLD = 500;

    /**
     * Whether the direct stitcher should be used for the given tile count.
     * Threshold is 500 tiles -- below this, the existing SparseImageServer path is fine.
     *
     * @param tileCount Number of tiles to stitch
     * @return true if direct stitching should be used
     */
    public static boolean shouldUseDirectStitching(int tileCount) {
        return tileCount >= TILE_COUNT_THRESHOLD;
    }

    /**
     * Stitch tiles using the memory-efficient direct path.
     * Output format is determined by {@code config.outputFormat}:
     * OME_ZARR uses direct chunk writing, OME_TIFF uses the compositor-backed ImageServer
     * with the existing PyramidImageWriter.
     *
     * @param mappings Tile mappings for one subdirectory
     * @param outputPath Output directory
     * @param filename Base filename (no extension)
     * @param config Stitching configuration (for compression, pixel size, format, etc.)
     * @param progressCallback Progress callback (0.0 to 1.0), may be null
     * @return Absolute path to output file/directory, or null on failure
     */
    public static String stitch(
            List<TileMapping> mappings,
            String outputPath,
            String filename,
            StitchingConfig config,
            Consumer<Double> progressCallback) {
        StitchingConfig.OutputFormat format =
                config.outputFormat != null ? config.outputFormat : StitchingConfig.OutputFormat.OME_TIFF;

        if (format == StitchingConfig.OutputFormat.OME_ZARR) {
            return stitchToZarr(mappings, outputPath, filename, config, progressCallback);
        } else {
            return stitchToTiff(mappings, outputPath, filename, config, progressCallback);
        }
    }

    /**
     * Stitch to OME-TIFF using a CompositorImageServer as the read path
     * for the existing PyramidImageWriter.
     */
    private static String stitchToTiff(
            List<TileMapping> mappings,
            String outputPath,
            String filename,
            StitchingConfig config,
            Consumer<Double> progressCallback) {
        long t0 = System.currentTimeMillis();

        try {
            // 1. Read tile properties from first tile header
            TileReaderPool.TileDimensions dims = TileReaderPool.getDimensions(mappings.get(0).file);
            logger.info(
                    "Tile properties: {}x{}, {} channels, {} bit, RGB={}",
                    dims.width(),
                    dims.height(),
                    dims.nChannels(),
                    dims.bitDepth(),
                    dims.isRGB());

            // 2. Build spatial index
            TileSpatialIndex index = new TileSpatialIndex(mappings, DEFAULT_CHUNK_SIZE);
            int imageWidth = index.getImageWidth();
            int imageHeight = index.getImageHeight();
            logger.info("Full image: {}x{} pixels ({} tiles)", imageWidth, imageHeight, mappings.size());

            // 3. Create compositor and reader pool (try-with-resources for cleanup on exception)
            boolean whiteBackground = dims.isRGB();
            BlendStrategy blend = new OverwriteBlendStrategy();

            try (TileReaderPool readerPool = new TileReaderPool(DEFAULT_MAX_OPEN_READERS)) {
                ChunkCompositor compositor =
                        new ChunkCompositor(readerPool, index, blend, whiteBackground, dims.isRGB(), dims.bitDepth());

                // 4. Create CompositorImageServer (memory-efficient replacement for SparseImageServer)
                CompositorImageServer server = new CompositorImageServer(
                        compositor,
                        readerPool,
                        imageWidth,
                        imageHeight,
                        dims.nChannels(),
                        dims.isRGB(),
                        dims.bitDepth(),
                        config.pixelSizeInMicrons,
                        config.zSpacingMicrons);

                logger.info("Writing OME-TIFF via CompositorImageServer (memory-efficient read path)...");

                // 5. Delegate to existing PyramidImageWriter for TIFF output
                String written = PyramidImageWriter.write(
                        server,
                        outputPath,
                        filename,
                        config.compressionType,
                        config.baseDownsample,
                        StitchingConfig.OutputFormat.OME_TIFF,
                        progressCallback);

                server.close();

                long elapsed = System.currentTimeMillis() - t0;
                if (written != null) {
                    logger.info(
                            "Direct stitching (OME-TIFF) complete in {}s: {}",
                            String.format("%.1f", elapsed / 1000.0),
                            written);
                } else {
                    logger.error(
                            "Direct stitching (OME-TIFF) failed after {}s", String.format("%.1f", elapsed / 1000.0));
                }

                return written;
            }
            // Reader pool auto-closed here, even on exception

        } catch (Exception e) {
            logger.error("Direct stitching (OME-TIFF) failed", e);
            return null;
        }
    }

    /**
     * Stitch directly to OME-ZARR using chunk-by-chunk writing via JZarr.
     * Most memory-efficient path -- no ImageServer intermediate.
     */
    private static String stitchToZarr(
            List<TileMapping> mappings,
            String outputPath,
            String filename,
            StitchingConfig config,
            Consumer<Double> progressCallback) {
        long t0 = System.currentTimeMillis();

        try {
            // 1. Read tile properties from first tile header (no pixel data)
            TileReaderPool.TileDimensions dims = TileReaderPool.getDimensions(mappings.get(0).file);
            logger.info(
                    "Tile properties: {}x{}, {} channels, {} bit, RGB={}",
                    dims.width(),
                    dims.height(),
                    dims.nChannels(),
                    dims.bitDepth(),
                    dims.isRGB());

            // 2. Build spatial index from tile positions
            TileSpatialIndex index = new TileSpatialIndex(mappings, DEFAULT_CHUNK_SIZE);
            int imageWidth = index.getImageWidth();
            int imageHeight = index.getImageHeight();
            logger.info("Full image: {}x{} pixels ({} tiles)", imageWidth, imageHeight, mappings.size());

            // 3. Compute pyramid levels
            int numLevels = computePyramidLevels(imageWidth, imageHeight, DEFAULT_CHUNK_SIZE);
            logger.info("Pyramid levels: {}", numLevels);

            // 4. Determine unique output path
            String zarrPath = getUniqueZarrPath(outputPath, filename);
            logger.info("Output path: {}", zarrPath);

            // 5. Create ZARR compressor and writer
            Compressor compressor = PyramidImageWriter.createZarrCompressor(config.compressionType);

            try (ZarrOutputWriter writer = new ZarrOutputWriter(zarrPath, compressor)) {
                writer.initialize(
                        imageWidth,
                        imageHeight,
                        dims.nChannels(),
                        dims.isRGB(),
                        dims.bitDepth(),
                        config.pixelSizeInMicrons,
                        config.zSpacingMicrons,
                        DEFAULT_CHUNK_SIZE,
                        numLevels);

                // 6. Create compositor with bounded reader pool
                boolean whiteBackground = dims.isRGB();
                BlendStrategy blend = new OverwriteBlendStrategy();

                try (TileReaderPool readerPool = new TileReaderPool(DEFAULT_MAX_OPEN_READERS)) {
                    ChunkCompositor compositor = new ChunkCompositor(
                            readerPool, index, blend, whiteBackground, dims.isRGB(), dims.bitDepth());

                    // 7. Write level 0 chunks in scanline order
                    int chunksX = (int) Math.ceil((double) imageWidth / DEFAULT_CHUNK_SIZE);
                    int chunksY = (int) Math.ceil((double) imageHeight / DEFAULT_CHUNK_SIZE);
                    int totalChunks = chunksX * chunksY;

                    logger.info("Writing {} level-0 chunks ({}x{} grid)...", totalChunks, chunksX, chunksY);

                    int processed = 0;
                    for (int cy = 0; cy < chunksY; cy++) {
                        for (int cx = 0; cx < chunksX; cx++) {
                            int chunkX = cx * DEFAULT_CHUNK_SIZE;
                            int chunkY = cy * DEFAULT_CHUNK_SIZE;
                            int chunkW = Math.min(DEFAULT_CHUNK_SIZE, imageWidth - chunkX);
                            int chunkH = Math.min(DEFAULT_CHUNK_SIZE, imageHeight - chunkY);

                            BufferedImage chunk = compositor.compositeChunk(chunkX, chunkY, chunkW, chunkH);
                            writer.writeChunk(chunk, 0, chunkX, chunkY);

                            processed++;
                            if (progressCallback != null) {
                                // Level 0 is ~80% of total work
                                progressCallback.accept(0.8 * processed / totalChunks);
                            }
                            if (processed % 100 == 0) {
                                logger.info(
                                        "Level 0 progress: {}/{} chunks ({}%)",
                                        processed, totalChunks, String.format("%.1f", 100.0 * processed / totalChunks));
                            }
                        }
                    }

                    logger.info("Level 0 complete: {} chunks written", totalChunks);
                }
                // Reader pool closed here -- all tile file handles released

                // 8. Generate pyramid levels from already-written level 0
                if (numLevels > 1) {
                    logger.info("Generating {} pyramid levels...", numLevels - 1);
                    PyramidLevelGenerator.generateLevels(
                            writer, numLevels, imageWidth, imageHeight, DEFAULT_CHUNK_SIZE, progress -> {
                                if (progressCallback != null) {
                                    progressCallback.accept(0.8 + 0.2 * progress);
                                }
                            });
                }
            }
            // Writer closed here

            long elapsed = System.currentTimeMillis() - t0;
            logger.info(
                    "Direct stitching (OME-ZARR) complete in {}s: {}",
                    String.format("%.1f", elapsed / 1000.0),
                    zarrPath);

            return zarrPath;

        } catch (Exception e) {
            logger.error("Direct stitching (OME-ZARR) failed", e);
            return null;
        }
    }

    /**
     * Compute number of pyramid levels needed.
     * Levels continue until the smallest dimension fits in one chunk.
     *
     * @param width Image width
     * @param height Image height
     * @param chunkSize Chunk size
     * @return Number of pyramid levels (always >= 1)
     */
    static int computePyramidLevels(int width, int height, int chunkSize) {
        int maxDim = Math.max(width, height);
        int levels = 1;
        while (maxDim / (1 << levels) > chunkSize) {
            levels++;
        }
        return levels + 1; // Include the final level that fits in one chunk
    }

    /**
     * Get a unique .ome.zarr output path, avoiding overwriting existing directories.
     *
     * @param outputDir Parent directory for output
     * @param filename Base filename (no extension)
     * @return Unique path for the .ome.zarr directory
     */
    static String getUniqueZarrPath(String outputDir, String filename) {
        Path base = Paths.get(outputDir).resolve(filename + ".ome.zarr");
        if (!Files.exists(base)) {
            return base.toString();
        }

        int counter = 2;
        while (true) {
            Path candidate = Paths.get(outputDir).resolve(filename + "_" + counter + ".ome.zarr");
            if (!Files.exists(candidate)) {
                return candidate.toString();
            }
            counter++;
        }
    }
}
