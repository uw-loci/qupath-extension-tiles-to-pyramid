package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.regions.RegionRequest;

/**
 * Rebuilds the downsampled levels of an OME-TIFF from its full-resolution level.
 *
 * <p>For 16-bit images stitched by tiles-to-pyramid 0.2.0 through 0.7.10, whose downsampled
 * levels hold only 8 bits (every value a multiple of 257). Full resolution in those files is
 * correct, so it is copied as it is and every coarser level is made again from it by averaging.
 * The input is never modified; the result goes to a new file.
 */
public final class PyramidRebuilder {

    private static final Logger logger = LoggerFactory.getLogger(PyramidRebuilder.class);

    /** Suffix of the default output, before {@code .ome.tif}. */
    public static final String SUFFIX = "_rebuilt";

    private PyramidRebuilder() {}

    /** What the inspection found about one file's first downsampled level. */
    public record Diagnosis(boolean damaged, String summary) {}

    /**
     * Whether {@code image}'s first downsampled level shows the 8-bit damage: a 16-bit,
     * non-RGB image whose level-1 non-zero values are almost all multiples of 257.
     */
    public static Diagnosis inspect(Path image) throws IOException {
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(image.toUri())) {
            if (server.isRGB() || server.getPixelType() != PixelType.UINT16) {
                return new Diagnosis(
                        false, "not affected: " + (server.isRGB() ? "RGB" : server.getPixelType()) + " image");
            }
            if (server.nResolutions() < 2) {
                return new Diagnosis(false, "not affected: no downsampled levels");
            }
            double d = server.getDownsampleForResolution(1);
            long nonzero = 0;
            long multiples = 0;
            // Sample a grid of regions across the first downsampled level.
            int n = 4;
            int size = (int) Math.min(512 * d, Math.min(server.getWidth(), server.getHeight()));
            for (int gy = 0; gy < n; gy++) {
                for (int gx = 0; gx < n; gx++) {
                    int x = (int) ((server.getWidth() - size) * (gx + 0.5) / n);
                    int y = (int) ((server.getHeight() - size) * (gy + 0.5) / n);
                    BufferedImage img =
                            server.readRegion(RegionRequest.createInstance(server.getPath(), d, x, y, size, size));
                    for (int c = 0; c < server.nChannels(); c++) {
                        int[] v = img.getRaster().getSamples(0, 0, img.getWidth(), img.getHeight(), c, (int[]) null);
                        for (int s : v) {
                            if (s > 0) {
                                nonzero++;
                                if (s % 257 == 0) {
                                    multiples++;
                                }
                            }
                        }
                    }
                }
            }
            return judge(nonzero, multiples);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not inspect " + image + ": " + e.getMessage(), e);
        }
    }

    /** The verdict from level-1 counts; separate so it can be tested without a file. */
    static Diagnosis judge(long nonzero, long multiplesOf257) {
        if (nonzero < 100) {
            return new Diagnosis(false, "cannot tell: almost no signal in the sampled regions");
        }
        double fraction = (double) multiplesOf257 / nonzero;
        String pct = String.format("%.0f%%", fraction * 100);
        // Real 16-bit data has about 1 value in 257 on a multiple (0.4%).
        if (fraction > 0.9) {
            return new Diagnosis(true, "damaged: " + pct + " of zoomed-out values are multiples of 257");
        }
        return new Diagnosis(false, "not damaged: " + pct + " of zoomed-out values are multiples of 257");
    }

    /** {@code <stem>_rebuilt.ome.tif} beside {@code image}. */
    public static Path defaultOutput(Path image) {
        String name = image.getFileName().toString();
        String stem = name.toLowerCase(java.util.Locale.ROOT).endsWith(".ome.tif")
                ? name.substring(0, name.length() - ".ome.tif".length())
                : name.replaceFirst("\\.[^.]+$", "");
        return image.resolveSibling(stem + SUFFIX + ".ome.tif");
    }

    /**
     * Write {@code output} with {@code input}'s full-resolution level and newly made downsampled
     * levels, LZW-compressed, and a record of the rebuild beside it.
     *
     * @throws IOException if {@code output} exists or the write fails (a partial output is removed)
     */
    public static void rebuild(Path input, Path output, Consumer<Double> progress) throws IOException {
        if (Files.exists(output)) {
            throw new IOException("Output already exists: " + output);
        }
        long t0 = System.nanoTime();
        try (ImageServer<BufferedImage> base = ImageServers.buildServer(input.toUri())) {
            logger.info(
                    "Rebuilding pyramid levels of {} ({}x{}, {} channel(s), {}) -> {}",
                    input.getFileName(),
                    base.getWidth(),
                    base.getHeight(),
                    base.nChannels(),
                    base.getPixelType(),
                    output.getFileName());
            ImageServer<BufferedImage> full = new FullResolutionServer(base);
            try {
                DirectTiffOutputWriter.write(
                        full, output.toString(), OMEPyramidWriter.CompressionType.LZW, 512, 1.0, progress);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(output);
                throw e;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(output);
            throw new IOException("Could not rebuild " + input + ": " + e.getMessage(), e);
        }
        writeRecord(input, output);
        logger.info("Rebuilt {} in {} s", output.getFileName(), String.format("%.1f", (System.nanoTime() - t0) / 1e9));
    }

    private static void writeRecord(Path input, Path output) {
        List<StitchInfoFile.Section> sections = new ArrayList<>();
        Map<String, String> rebuild = new LinkedHashMap<>();
        rebuild.put("file", output.toString());
        rebuild.put("written", OffsetDateTime.now().withNano(0).toString());
        rebuild.put("from", input.toString());
        rebuild.put("method", "full resolution copied; every downsampled level made again from it by averaging");
        rebuild.put("why", "16-bit downsampled levels written by tiles-to-pyramid 0.2.0 to 0.7.10 held only 8 bits");
        rebuild.put("compression", "LZW");
        sections.add(StitchInfoFile.Section.of("pyramid rebuild", rebuild));
        List<String> original = StitchInfoFile.read(input);
        if (!original.isEmpty()) {
            sections.add(new StitchInfoFile.Section(
                    "original record",
                    Map.of(),
                    original.stream().map(l -> "    " + l).toList()));
        }
        StitchInfoFile.write(output, sections);
    }
}
