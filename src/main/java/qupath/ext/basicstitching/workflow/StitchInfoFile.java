package qupath.ext.basicstitching.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plain-text record, beside a stitched image, of how that image was produced.
 *
 * <h2>Why a sidecar file</h2>
 *
 * OME-TIFF and OME-Zarr can both carry custom key/value annotations, but not every reader shows
 * them, and a file a person can open in a text editor next to the image is the record most likely to
 * survive being copied, shared and read. The format is deliberately simple: {@code [section]}
 * headings followed by {@code key: value} lines, ASCII only (production runs on Windows cp1252), with
 * free-form lines allowed inside a section for verbatim material such as a solution header.
 *
 * <h2>Who writes what</h2>
 *
 * The stitcher writes the sections it knows -- source tiles, stitch settings, registration,
 * software -- when it writes the image. A host application (QPSC) appends its own sections, such as
 * the acquisition, and must call {@link #moveWith} whenever it renames the image, or the record
 * is orphaned under the old name.
 */
public final class StitchInfoFile {

    private static final Logger logger = LoggerFactory.getLogger(StitchInfoFile.class);

    /** Appended to the image's name stem. */
    public static final String SUFFIX = ".stitch-info.txt";

    private static final String[] IMAGE_EXTENSIONS = {".ome.tiff", ".ome.tif", ".ome.zarr", ".tiff", ".tif", ".zarr"};

    private StitchInfoFile() {}

    /**
     * One titled block of the file.
     *
     * @param title heading, written as {@code [title]}
     * @param entries {@code key: value} lines, in order
     * @param lines verbatim lines written after the entries; may be empty
     */
    public record Section(String title, Map<String, String> entries, List<String> lines) {
        public Section {
            entries = entries == null ? Map.of() : entries;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** @return a section of key/value entries only. */
        public static Section of(String title, Map<String, String> entries) {
            return new Section(title, entries, List.of());
        }
    }

    /**
     * @param image the stitched image (an OME-TIFF file or OME-Zarr directory)
     * @return where its record lives: the image's name without its extension, plus {@link #SUFFIX}
     */
    public static Path pathFor(Path image) {
        String name = image.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                name = name.substring(0, name.length() - ext.length());
                break;
            }
        }
        return image.resolveSibling(name + SUFFIX);
    }

    /**
     * Write a new record for {@code image}, replacing any existing one.
     *
     * <p>Never throws: a missing record must not fail a stitch that produced a good image.
     *
     * @param image the stitched image
     * @param sections the sections, in order
     */
    public static void write(Path image, List<Section> sections) {
        List<String> out = new ArrayList<>();
        out.add("# How " + ascii(image.getFileName().toString()) + " was produced.");
        out.add("# Sections: [heading] then 'key: value' lines. Written by the stitcher; hosts may append.");
        for (Section s : sections) {
            render(s, out);
        }
        try {
            Files.write(pathFor(image), out, StandardCharsets.US_ASCII);
            logger.info("Wrote stitch record {}", pathFor(image).getFileName());
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not write stitch record for {}: {}", image, e.toString());
        }
    }

    /**
     * Append sections to {@code image}'s record, creating it if absent. Never throws.
     *
     * @param image the stitched image
     * @param sections the sections to add
     */
    public static void append(Path image, List<Section> sections) {
        Path file = pathFor(image);
        if (!Files.exists(file)) {
            write(image, sections);
            return;
        }
        List<String> out = new ArrayList<>();
        for (Section s : sections) {
            render(s, out);
        }
        try {
            Files.write(file, out, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not append to stitch record {}: {}", file, e.toString());
        }
    }

    /**
     * Move a record to follow its image to a new name. A no-op when there is no record or the name is
     * unchanged. Never throws.
     *
     * @param from the image's old path
     * @param to the image's new path
     */
    public static void moveWith(Path from, Path to) {
        Path src = pathFor(from);
        Path dst = pathFor(to);
        if (src.equals(dst) || !Files.exists(src)) {
            return;
        }
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not move stitch record {} -> {}: {}", src, dst, e.toString());
        }
    }

    /**
     * @param image the stitched image
     * @return the record's lines, or empty when there is none or it cannot be read
     */
    public static List<String> read(Path image) {
        Path file = pathFor(image);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read stitch record {}: {}", file, e.toString());
            return List.of();
        }
    }

    private static void render(Section s, List<String> out) {
        out.add("");
        out.add("[" + ascii(s.title()) + "]");
        s.entries().forEach((k, v) -> out.add(ascii(k) + ": " + ascii(v)));
        for (String line : s.lines()) {
            out.add(ascii(line));
        }
    }

    /** Anything outside printable ASCII becomes '?', and line breaks become spaces. */
    static String ascii(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                b.append(' ');
            } else if (c == '\t' || (c >= 0x20 && c < 0x7f)) {
                b.append(c);
            } else {
                b.append('?');
            }
        }
        return b.toString();
    }
}
