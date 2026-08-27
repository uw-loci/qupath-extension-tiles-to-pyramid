package qupath.ext.basicstitching.assembly.direct;

import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.channel.ChannelSemantics;

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
        generateLevels(writer, numLevels, baseWidth, baseHeight, chunkSize, progressCallback, ChannelSemantics.LINEAR);
    }

    /**
     * As above, honouring a channel's declared resampling policy.
     *
     * <p>Area-averaging is only valid for continuous data. A label map averaged into a class the
     * pixel never had, or an axial angle averaged across its wrap, produces a pyramid that looks
     * right at every level and means nothing -- and nothing downstream can detect it. So a
     * non-combinable channel is decimated by selection instead, and an angular one is averaged
     * circularly when it declared the period needed to recover angles from its counts.
     *
     * @param declaration the tile's declared handling; use {@link ChannelSemantics#LINEAR} for
     *     ordinary continuous data
     */
    public static void generateLevels(
            ZarrOutputWriter writer,
            int numLevels,
            int baseWidth,
            int baseHeight,
            int chunkSize,
            Consumer<Double> progressCallback,
            ChannelSemantics.Declaration declaration)
            throws IOException {
        if (numLevels <= 1) {
            return;
        }
        if (declaration == null) {
            declaration = ChannelSemantics.LINEAR;
        }
        if (!declaration.policy().mayCombine()) {
            logger.info(
                    "Channel declares resample policy {}: pyramid levels will be built by {} "
                            + "instead of area-averaging.",
                    declaration.policy(),
                    declaration.canAverageCircularly() ? "circular averaging" : "decimation");
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
                            Object dstData = downsample2x(
                                    srcData, srcW, srcH, actualDstW, actualDstH, nChannels, is16Bit, declaration);

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
    // Package-private for testing: the wrap behaviour is the whole point.
    static Object downsample2x(
            Object srcData,
            int srcW,
            int srcH,
            int dstW,
            int dstH,
            int nChannels,
            boolean is16Bit,
            ChannelSemantics.Declaration declaration) {
        if (is16Bit) {
            short[] src = (short[]) srcData;
            short[] dst = new short[nChannels * dstH * dstW];
            if (declaration.canAverageCircularly()) {
                downsampleAngularShort(src, dst, srcW, srcH, dstW, dstH, nChannels, declaration);
            } else if (declaration.policy().mayCombine()) {
                downsampleShort(src, dst, srcW, srcH, dstW, dstH, nChannels);
            } else {
                decimateShort(src, dst, srcW, srcH, dstW, dstH, nChannels);
            }
            return dst;
        } else {
            byte[] src = (byte[]) srcData;
            byte[] dst = new byte[nChannels * dstH * dstW];
            if (declaration.policy().mayCombine()) {
                downsampleByte(src, dst, srcW, srcH, dstW, dstH, nChannels);
            } else {
                decimateByte(src, dst, srcW, srcH, dstW, dstH, nChannels);
            }
            return dst;
        }
    }

    /**
     * Decimate by taking the top-left pixel of each 2x2 block.
     *
     * <p>Every output value is a value that actually occurred in the input, which is the only
     * property that matters for labels, masks and object ids. Nearest-neighbour is also a safe
     * (if lossier) fallback for angular data whose period was not declared.
     */
    // Package-private for testing: the wrap behaviour is the whole point.
    static void decimateShort(short[] src, short[] dst, int srcW, int srcH, int dstW, int dstH, int nChannels) {
        for (int c = 0; c < nChannels; c++) {
            int srcOff = c * srcH * srcW;
            int dstOff = c * dstH * dstW;
            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * 2;
                for (int dx = 0; dx < dstW; dx++) {
                    dst[dstOff + dy * dstW + dx] = src[srcOff + sy * srcW + dx * 2];
                }
            }
        }
    }

    /** 8-bit counterpart of {@link #decimateShort}. */
    private static void decimateByte(byte[] src, byte[] dst, int srcW, int srcH, int dstW, int dstH, int nChannels) {
        for (int c = 0; c < nChannels; c++) {
            int srcOff = c * srcH * srcW;
            int dstOff = c * dstH * dstW;
            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * 2;
                for (int dx = 0; dx < dstW; dx++) {
                    dst[dstOff + dy * dstW + dx] = src[srcOff + sy * srcW + dx * 2];
                }
            }
        }
    }

    /**
     * Average an angular channel over 2x2 blocks, in the complex plane.
     *
     * <p>The counts are converted to angles, folded by the policy's harmonic so the wrap point
     * disappears (an axial angle repeats twice per revolution, so it is averaged at double
     * angle), averaged as unit vectors, and converted back. This is what makes 179 and 1 average
     * to 0 rather than to 90.
     */
    // Package-private for testing: the wrap behaviour is the whole point.
    static void downsampleAngularShort(
            short[] src,
            short[] dst,
            int srcW,
            int srcH,
            int dstW,
            int dstH,
            int nChannels,
            ChannelSemantics.Declaration declaration) {

        // The declared period is one full cycle of the STORED values, so counts map
        // onto a single turn regardless of whether the channel is axial or
        // directional -- the folding is already baked into what the period means.
        // (Multiplying by the harmonic here spans two turns for an axial channel,
        // and a mean landing a hair below zero then wraps to 2*pi, which decodes to
        // half the period: exactly the 90-degree answer this code exists to avoid.)
        double period = declaration.period();
        double toAngle = 2.0 * Math.PI / period;
        long periodCounts = Math.round(period);

        for (int c = 0; c < nChannels; c++) {
            int srcOff = c * srcH * srcW;
            int dstOff = c * dstH * dstW;
            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * 2;
                for (int dx = 0; dx < dstW; dx++) {
                    int sx = dx * 2;
                    double sumSin = 0;
                    double sumCos = 0;
                    int count = 0;
                    for (int oy = 0; oy < 2; oy++) {
                        int y = sy + oy;
                        if (y >= srcH) {
                            continue;
                        }
                        for (int ox = 0; ox < 2; ox++) {
                            int x = sx + ox;
                            if (x >= srcW) {
                                continue;
                            }
                            double angle = (src[srcOff + y * srcW + x] & 0xFFFF) * toAngle;
                            sumSin += Math.sin(angle);
                            sumCos += Math.cos(angle);
                            count++;
                        }
                    }
                    if (count == 0) {
                        continue;
                    }
                    double mean = Math.atan2(sumSin / count, sumCos / count);
                    if (mean < 0) {
                        mean += 2.0 * Math.PI;
                    }
                    // Rounding can land exactly on the period; fold back so the stored
                    // range stays half-open, as the writer declared it.
                    long value = Math.floorMod(Math.round(mean / toAngle), periodCounts);
                    dst[dstOff + dy * dstW + dx] = (short) value;
                }
            }
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
    // Package-private for testing: the wrap behaviour is the whole point.
    static void downsampleShort(short[] src, short[] dst, int srcW, int srcH, int dstW, int dstH, int nChannels) {
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
