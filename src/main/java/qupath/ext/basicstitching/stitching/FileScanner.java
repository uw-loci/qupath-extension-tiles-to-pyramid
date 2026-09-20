package qupath.ext.basicstitching.stitching;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finding files under a folder, without letting the folder decide whether the extension survives.
 *
 * <h2>Why not {@code Files.walk}</h2>
 *
 * {@code Files.walk} reports a directory it cannot open as an {@link java.io.UncheckedIOException}
 * thrown while the <i>stream is consumed</i>, so the {@code catch (IOException)} wrapped around the
 * try-with-resources never sees it and the exception escapes to whatever called the scan. On
 * Windows this is not a corner case: {@code C:\$Recycle.Bin\S-1-5-18} and
 * {@code C:\System Volume Information} are unreadable by any ordinary process, so a scan that
 * starts near the top of a drive always hits one. The stitch dialog crashed on open that way, with
 * the folder still at its initial {@code C:\}.
 *
 * <p>A visitor gets {@code visitFileFailed} for exactly those entries, which lets the scan skip
 * them and carry on. Every scan is also depth-bounded, so a mis-selected parent folder costs time
 * proportional to the layouts we actually support rather than to the size of the disk.
 */
public final class FileScanner {

    private static final Logger logger = LoggerFactory.getLogger(FileScanner.class);

    private FileScanner() {}

    /**
     * Regular files under {@code root}, at most {@code maxDepth} directories down, that the
     * predicate accepts.
     *
     * <p>Never throws: an unreadable entry is skipped and logged, and an unusable root returns an
     * empty list. A caller that needs to tell "nothing matched" from "could not look" should check
     * the root itself first.
     *
     * @param root directory to search
     * @param maxDepth how many levels below {@code root} to descend; 0 searches {@code root} only
     * @param matches tested against each file's name
     * @return matching files, in directory-walk order; empty if none or the root cannot be read
     */
    public static List<Path> find(Path root, int maxDepth, Predicate<Path> matches) {
        List<Path> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && matches.test(file)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    logger.debug("Skipping unreadable {}: {}", file, e.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            logger.warn("Error scanning {}: {}", root, e.toString());
        }
        return result;
    }
}
