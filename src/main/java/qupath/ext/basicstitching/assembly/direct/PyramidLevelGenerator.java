package qupath.ext.basicstitching.assembly.direct;

import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates downsampled pyramid levels by reading 2x2 blocks from the previous
 * level, area-averaging, and writing the result.
 * <p>
 * Memory usage is bounded: only 4 source chunks are in memory at a time
 * (~12 MB for RGB 1024x1024 chunks).
 */
public class PyramidLevelGenerator {

    private static final Logger logger = LoggerFactory.getLogger(PyramidLevelGenerator.class);

    /**
     * Generate all pyramid levels from level 1 onwards by 2x downsampling.
     *
     * @param writer ZARR writer with level 0 already written
     * @param numLevels Total number of pyramid levels
     * @param baseWidth Full image width at level 0
     * @param baseHeight Full image height at level 0
     * @param chunkSize Chunk size in pixels
     * @param progressCallback Progress callback (0.0 to 1.0 across all levels)
     */
    public static void generateLevels(
            ZarrOutputWriter writer,
            int numLevels,
            int baseWidth,
            int baseHeight,
            int chunkSize,
            Consumer<Double> progressCallback)
            throws IOException {
        if (numLevels <= 1) {
            return;
        }

        int nChannels = writer.getNumChannels();
        boolean is16Bit = writer.getBitDepth() > 8;
        int sizeZ = writer.getSizeZ();
        int sizeT = writer.getSizeT();

        // Count total chunks across all levels and planes for progress tracking
        int totalChunks = 0;
        for (int level = 1; level < numLevels; level++) {
            int levelW = writer.getLevelWidth(level);
            int levelH = writer.getLevelHeight(level);
            totalChunks += (int) Math.ceil((double) levelW / chunkSize) * (int) Math.ceil((double) levelH / chunkSize);
        }
        totalChunks *= sizeZ * sizeT;

        int processedChunks = 0;

        for (int level = 1; level < numLevels; level++) {
            int prevW = writer.getLevelWidth(level - 1);
            int prevH = writer.getLevelHeight(level - 1);
            int currW = writer.getLevelWidth(level);
            int currH = writer.getLevelHeight(level);

            int chunksX = (int) Math.ceil((double) currW / chunkSize);
            int chunksY = (int) Math.ceil((double) currH / chunkSize);

            logger.info(
                    "Generating pyramid level {}: {}x{} ({} chunks x {} z x {} t)",
                    level,
                    currW,
                    currH,
                    chunksX * chunksY,
                    sizeZ,
                    sizeT);

            // Each (z, t) plane is downsampled independently; channels are carried
            // within the per-plane [C, H, W] block by downsample2x.
            for (int t = 0; t < sizeT; t++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int cy = 0; cy < chunksY; cy++) {
                        for (int cx = 0; cx < chunksX; cx++) {
                            int outW = Math.min(chunkSize, currW - cx * chunkSize);
                            int outH = Math.min(chunkSize, currH - cy * chunkSize);

                            // Source region in previous level (2x the output region)
                            int srcX = cx * chunkSize * 2;
                            int srcY = cy * chunkSize * 2;
                            int srcW = Math.min(outW * 2, prevW - srcX);
                            int srcH = Math.min(outH * 2, prevH - srcY);

                            if (srcW <= 0 || srcH <= 0) {
                                continue;
                            }

                            // Read source data from previous level for this plane
                            Object srcData = writer.readRawData(level - 1, z, t, srcY, srcX, srcH, srcW);

                            // Compute actual downsampled dimensions
                            int actualDstW = Math.min(outW, (srcW + 1) / 2);
                            int actualDstH = Math.min(outH, (srcH + 1) / 2);

                            // Downsample 2x via area averaging
                            Object dstData =
                                    downsample2x(srcData, srcW, srcH, actualDstW, actualDstH, nChannels, is16Bit);

                            // Write to current level for this plane
                            writer.writeRawData(
                                    dstData, level, z, t, cy * chunkSize, cx * chunkSize, actualDstH, actualDstW);

                            processedChunks++;
                            if (progressCallback != null && totalChunks > 0) {
                                progressCallback.accept((double) processedChunks / totalChunks);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Downsample data by 2x using area averaging.
     * For multi-channel data, the flat array is laid out as [C, H, W].
     */
    private static Object downsample2x(
            Object srcData, int srcW, int srcH, int dstW, int dstH, int nChannels, boolean is16Bit) {
        if (is16Bit) {
            short[] src = (short[]) srcData;
            short[] dst = new short[nChannels * dstH * dstW];
            downsampleShort(src, dst, srcW, srcH, dstW, dstH, nChannels);
            return dst;
        } else {
            byte[] src = (byte[]) srcData;
            byte[] dst = new byte[nChannels * dstH * dstW];
            downsampleByte(src, dst, srcW, srcH, dstW, dstH, nChannels);
            return dst;
        }
    }

    /**
     * Downsample 8-bit data by area-averaging 2x2 pixel blocks.
     */
    private static void downsampleByte(byte[] src, byte[] dst, int srcW, int srcH, int dstW, int dstH, int nChannels) {
        int srcPlane = srcH * srcW;
        int dstPlane = dstH * dstW;

        for (int c = 0; c < nChannels; c++) {
            int srcOff = c * srcPlane;
            int dstOff = c * dstPlane;

            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * 2;
                for (int dx = 0; dx < dstW; dx++) {
                    int sx = dx * 2;

                    // Average 2x2 block, handling edge where source may be 1 pixel short
                    int sum = 0;
                    int count = 0;

                    sum += src[srcOff + sy * srcW + sx] & 0xFF;
                    count++;

                    if (sx + 1 < srcW) {
                        sum += src[srcOff + sy * srcW + sx + 1] & 0xFF;
                        count++;
                    }
                    if (sy + 1 < srcH) {
                        sum += src[srcOff + (sy + 1) * srcW + sx] & 0xFF;
                        count++;
                    }
                    if (sx + 1 < srcW && sy + 1 < srcH) {
                        sum += src[srcOff + (sy + 1) * srcW + sx + 1] & 0xFF;
                        count++;
                    }

                    dst[dstOff + dy * dstW + dx] = (byte) (sum / count);
                }
            }
        }
    }

    /**
     * Downsample 16-bit data by area-averaging 2x2 pixel blocks.
     */
    private static void downsampleShort(
            short[] src, short[] dst, int srcW, int srcH, int dstW, int dstH, int nChannels) {
        int srcPlane = srcH * srcW;
        int dstPlane = dstH * dstW;

        for (int c = 0; c < nChannels; c++) {
            int srcOff = c * srcPlane;
            int dstOff = c * dstPlane;

            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * 2;
                for (int dx = 0; dx < dstW; dx++) {
                    int sx = dx * 2;

                    int sum = 0;
                    int count = 0;

                    sum += src[srcOff + sy * srcW + sx] & 0xFFFF;
                    count++;

                    if (sx + 1 < srcW) {
                        sum += src[srcOff + sy * srcW + sx + 1] & 0xFFFF;
                        count++;
                    }
                    if (sy + 1 < srcH) {
                        sum += src[srcOff + (sy + 1) * srcW + sx] & 0xFFFF;
                        count++;
                    }
                    if (sx + 1 < srcW && sy + 1 < srcH) {
                        sum += src[srcOff + (sy + 1) * srcW + sx + 1] & 0xFFFF;
                        count++;
                    }

                    dst[dstOff + dy * dstW + dx] = (short) (sum / count);
                }
            }
        }
    }
}
