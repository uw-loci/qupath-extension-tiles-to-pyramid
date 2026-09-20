package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.stitching.MicroManagerMetadataStrategy;

/**
 * The dialog scans the selected folder for MicroManager metadata as soon as it opens, so that scan
 * must not throw, whatever it meets.
 *
 * <p>Regression: with the folder left at its initial {@code C:\} the scan walked the whole drive and
 * hit {@code C:\$Recycle.Bin\S-1-5-18}, which no ordinary process may read. {@code Files.walk}
 * raises that as an {@code UncheckedIOException} while the stream is consumed, so the
 * {@code catch (IOException)} around it never fired and the dialog died on open.
 */
class MetadataScanSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void anUnreadableSubdirectoryIsSkippedNotFatal() throws IOException {
        Path readable = Files.createDirectories(tempDir.resolve("tiles"));
        Files.writeString(
                readable.resolve("metadata.txt"),
                "{\"FrameKey-0-0-0\": {\"PixelSizeUm\": 0.653}}",
                StandardCharsets.UTF_8);

        Path forbidden = Files.createDirectories(tempDir.resolve("forbidden"));
        Files.writeString(forbidden.resolve("metadata.txt"), "{}", StandardCharsets.UTF_8);
        boolean madeUnreadable = false;
        try {
            Files.setPosixFilePermissions(forbidden, Set.of());
            madeUnreadable = true;
        } catch (UnsupportedOperationException | IOException e) {
            // Windows CI: no POSIX permissions. The rest of the assertion still holds.
        }

        try {
            Double pixelSize =
                    assertDoesNotThrow(() -> MicroManagerMetadataStrategy.detectPixelSizeUm(tempDir.toFile()));
            assertEquals(0.653, pixelSize, 1e-9, "the readable folder's metadata must still be found");
        } finally {
            if (madeUnreadable) {
                Files.setPosixFilePermissions(
                        forbidden, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    void metadataAnywhereUnderTheFolderIsFound() throws IOException {
        // The flip side of not scanning by default: once a folder IS chosen, metadata nested in
        // the MicroManager layouts must still be picked up. This is what makes the auto-fill worth
        // gating rather than deleting -- and what makes an unchosen home directory dangerous, since
        // any acquisition sitting under it answers too.
        Path nested = Files.createDirectories(tempDir.resolve("run_1").resolve("Pos0"));
        Files.writeString(
                nested.resolve("run_1_metadata.txt"),
                "{\"FrameKey-0-0-0\": {\"PixelSizeUm\": 0.2271}}",
                StandardCharsets.UTF_8);

        assertEquals(
                0.2271,
                MicroManagerMetadataStrategy.detectPixelSizeUm(tempDir.toFile()),
                1e-9,
                "metadata two levels down must still be found");
    }

    @Test
    void aFilesystemRootIsNotScanned() {
        // Scanning C:\ or / means walking every top-level folder on the disk before the dialog can
        // open, and tiles never sit at a drive root.
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            File rootFile = root.toFile();
            if (!rootFile.isDirectory()) {
                continue;
            }
            long start = System.nanoTime();
            assertNull(
                    assertDoesNotThrow(() -> MicroManagerMetadataStrategy.detectPixelSizeUm(rootFile)),
                    "a filesystem root must report no pixel size rather than scanning the disk");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 2_000, "the root was scanned rather than refused (" + elapsedMs + " ms)");
            return;
        }
    }
}
