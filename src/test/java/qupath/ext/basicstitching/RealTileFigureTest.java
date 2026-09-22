package qupath.ext.basicstitching;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationRequest;
import qupath.ext.basicstitching.registration.RegistrationResult;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.regions.ImageRegion;

/**
 * Produces the stitched figures used in the I2K deck from real acquisition tiles,
 * using the extension's own registration and compositing rather than a stand-in.
 *
 * <pre>
 *   ./gradlew test --tests "*RealTileFigureTest*" -PrealFig \
 *       -PrealFigIn=/path/to/bounds -PrealFigOut=/path/to/out
 * </pre>
 */
@EnabledIfSystemProperty(named = "realFig", matches = "true")
public class RealTileFigureTest {

    private static final int TILE = 2048;
    private static final double OVERLAP = 0.1001;

    @Test
    public void stitchNominalAndRegistered() throws Exception {
        Path in = Paths.get(System.getProperty("realFigIn"));
        Path out = Paths.get(System.getProperty("realFigOut"));
        Files.createDirectories(out);

        // Nominal grid, in output pixels. 2x2, origins one step apart.
        int step = (int) Math.round(TILE * (1 - OVERLAP));
        int[][] cell = {{0, 0}, {1, 0}, {1, 1}, {0, 1}}; // 0.tif, 1.tif, 2.tif, 3.tif

        List<TileNode> dapi = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            File f = in.resolve("DAPI").resolve(n + ".tif").toFile();
            dapi.add(new TileNode(n + ".tif", f, cell[n][0] * step, cell[n][1] * step, TILE, TILE));
        }

        // The extension solves the grid on DAPI.
        RegistrationResult res = TileRegistrationEngineAccess.register(
                new RegistrationRequest("DAPI", dapi, RegistrationSettings.defaults()));
        System.out.println("=== REGISTRATION (DAPI) ===");
        System.out.println("  max |delta| = " + String.format("%.2f", res.maxAbsDeltaPx()) + " px");
        for (int n = 0; n < 4; n++) {
            double[] d = res.deltaFor(n + ".tif");
            System.out.println(String.format("  %d.tif  dx=%+.3f  dy=%+.3f", n, d[0], d[1]));
        }

        // Composite every channel twice: on stage positions, then on the solved ones.
        // The solve is reused unchanged across channels -- that is the claim on the slide.
        for (String ch : new String[] {"DAPI", "FITC", "TRITC"}) {
            for (String mode : new String[] {"nominal", "registered"}) {
                List<TileMapping> maps = new ArrayList<>();
                for (int n = 0; n < 4; n++) {
                    double[] d = mode.equals("registered") ? res.deltaFor(n + ".tif") : new double[] {0, 0};
                    int x = (int) Math.round(cell[n][0] * step + d[0]);
                    int y = (int) Math.round(cell[n][1] * step + d[1]);
                    maps.add(new TileMapping(
                            in.resolve(ch).resolve(n + ".tif").toFile(),
                            ImageRegion.createInstance(x, y, TILE, TILE, 0, 0),
                            "."));
                }
                StitchingConfig cfg = new StitchingConfig(
                        "filename",
                        out.toString(),
                        out.toString(),
                        "LZW",
                        0.653,
                        1.0,
                        ".",
                        1.0,
                        StitchingConfig.OutputFormat.OME_TIFF);
                String written =
                        DirectTileStitcher.stitch(maps, out.toString(), ch.toLowerCase() + "_" + mode, cfg, null);
                System.out.println("WROTE " + ch + " " + mode + " -> " + written);
            }
        }
    }
}
