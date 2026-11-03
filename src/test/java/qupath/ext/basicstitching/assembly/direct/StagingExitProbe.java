package qupath.ext.basicstitching.assembly.direct;

import java.nio.file.Path;
import qupath.lib.images.servers.PixelType;

/**
 * Run in its own JVM by {@link LevelStagingTest}: starts a staged write into {@code args[0]} and
 * exits the JVM part way through, as closing QuPath during a stitch does.
 */
public final class StagingExitProbe {

    private StagingExitProbe() {}

    public static void main(String[] args) throws Exception {
        LevelStagingTest.PlanesServer server = new LevelStagingTest.PlanesServer(1, 1, 1, PixelType.UINT16);
        server.failAt = 60;
        server.beforeFailing = () -> {
            int folders;
            try {
                folders = PyramidTestFiles.stagingFolders(Path.of(args[0])).size();
            } catch (java.io.IOException e) {
                folders = -1;
            }
            System.out.println("staging folders before exit: " + folders);
            System.exit(0);
        };
        PyramidTestFiles.write(server, Path.of(args[0]), "exit", dir -> Long.MAX_VALUE / 4, null);
    }
}
