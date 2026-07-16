package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.ext.basicstitching.workflow.TileRegistrationStep;
import qupath.lib.regions.ImageRegion;

/**
 * Tests for the Solve/Apply modes and, above all, the cross-angle invariant.
 *
 * <p>The invariant is the reason this feature is shaped the way it is. A polarization or
 * multi-channel acquisition captures several images at one stage position per tile. If each angle
 * were registered independently, each would get its own corrections and the angles would end up
 * misregistered against <i>each other</i> -- a worse outcome than leaving every angle on a shared
 * nominal grid, because the channels of one field would no longer overlay. Exactly one subdirectory
 * may be solved, and every sibling must receive the identical correction.
 */
class TileRegistrationStepTest {

    @TempDir
    Path tempDir;

    private static final int TILE_W = 256;
    private static final int TILE_H = 192;
    private static final double OVERLAP = 0.15;

    private static StitchingConfig config(Path folder) {
        return new StitchingConfig(
                "Coordinates in TileConfiguration.txt",
                folder.toString(),
                folder.toString(),
                "LZW",
                0.5,
                1.0,
                "",
                1.0,
                1.0,
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
    }

    /** Turn a fixture grid into mappings tagged with a subdirectory name. */
    private static List<TileMapping> mappings(SyntheticGridFixture.Grid grid, String subdir) {
        List<TileMapping> out = new ArrayList<>();
        for (TileNode t : grid.nominal()) {
            out.add(new TileMapping(
                    t.file(),
                    ImageRegion.createInstance(
                            (int) Math.round(t.xPx()), (int) Math.round(t.yPx()), t.widthPx(), t.heightPx(), 0, 0),
                    subdir));
        }
        return out;
    }

    /** Per-tile shift the step actually applied, relative to nominal. */
    private static double[] applied(List<TileMapping> before, List<TileMapping> after, int index) {
        return new double[] {
            after.get(index).region.getX() - before.get(index).region.getX(),
            after.get(index).region.getY() - before.get(index).region.getY()
        };
    }

    // ---------------------------------------------------------------- the invariant

    @Test
    void crossAngleInvariant_everySiblingGetsIdenticalDeltas() throws IOException {
        // Two angles over the same grid, with DELIBERATELY DIFFERENT pixel content: different seeds
        // mean an independent solve would produce visibly different corrections. Only a shared solve
        // can produce identical ones.
        Path angle0 = Files.createDirectories(tempDir.resolve("angle_0"));
        Path angle45 = Files.createDirectories(tempDir.resolve("angle_45"));
        SyntheticGridFixture.Grid g0 = SyntheticGridFixture.write(angle0, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        SyntheticGridFixture.Grid g45 = SyntheticGridFixture.write(angle45, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 999);

        List<TileMapping> before = new ArrayList<>();
        before.addAll(mappings(g0, "angle_0"));
        before.addAll(mappings(g45, "angle_45"));

        StitchingConfig config = config(tempDir);
        config.setRegistrationMode(new RegistrationMode.Solve(
                tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME),
                RegistrationSettings.defaults().withThreads(1),
                "angle_0"));

        List<TileMapping> after = TileRegistrationStep.applyTo(before, config);

        assertEquals(before.size(), after.size());
        int perAngle = g0.nominal().size();
        for (int i = 0; i < perAngle; i++) {
            double[] fromAngle0 = applied(before, after, i);
            double[] fromAngle45 = applied(before, after, perAngle + i);
            assertArrayEquals(
                    fromAngle0,
                    fromAngle45,
                    1e-9,
                    "angle_45 tile " + i + " must receive angle_0's correction exactly, not its own solve");
        }

        // And the corrections must be real, or the assertion above passes trivially on all-zeros.
        boolean anyMoved = false;
        for (int i = 0; i < perAngle; i++) {
            double[] d = applied(before, after, i);
            anyMoved |= d[0] != 0 || d[1] != 0;
        }
        assertTrue(anyMoved, "the reference solve produced no corrections, so the invariant proves nothing");
    }

    @Test
    void solveThenApply_acrossSeparateCalls_reproducesTheSameCorrections() throws IOException {
        // The acquisition path: the reference angle stitches first and writes the solution; sibling
        // angles stitch later, in separate calls, reusing it.
        Path angle0 = Files.createDirectories(tempDir.resolve("angle_0"));
        Path birefDir = Files.createDirectories(tempDir.resolve("sample_biref"));
        SyntheticGridFixture.Grid g0 = SyntheticGridFixture.write(angle0, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        SyntheticGridFixture.Grid gb = SyntheticGridFixture.write(birefDir, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 7);

        Path solutionFile = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);

        List<TileMapping> refBefore = mappings(g0, "angle_0");
        StitchingConfig solveConfig = config(tempDir);
        solveConfig.setRegistrationMode(new RegistrationMode.Solve(
                solutionFile, RegistrationSettings.defaults().withThreads(1), null));
        List<TileMapping> refAfter = TileRegistrationStep.applyTo(refBefore, solveConfig);

        assertTrue(Files.exists(solutionFile), "the solve must persist a solution for siblings to reuse");

        // A post-processing directory (.biref / .sum) is stitched separately from the angles but
        // shares their grid. Miss it and it would be the one output misregistered against the rest.
        List<TileMapping> birefBefore = mappings(gb, "sample_biref");
        StitchingConfig applyConfig = config(tempDir);
        applyConfig.setRegistrationMode(new RegistrationMode.Apply(solutionFile));
        List<TileMapping> birefAfter = TileRegistrationStep.applyTo(birefBefore, applyConfig);

        for (int i = 0; i < g0.nominal().size(); i++) {
            assertArrayEquals(
                    applied(refBefore, refAfter, i),
                    applied(birefBefore, birefAfter, i),
                    1e-9,
                    "a reused solution must reproduce the reference's correction for tile " + i);
        }
    }

    @Test
    void applyIsIdempotent() throws IOException {
        // Nominal positions are never rewritten on disk, so re-running cannot compound. This is the
        // property that a rewrite-the-config design would have had to defend with a backup file.
        Path angle0 = Files.createDirectories(tempDir.resolve("angle_0"));
        SyntheticGridFixture.Grid g0 = SyntheticGridFixture.write(angle0, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        Path solutionFile = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);

        List<TileMapping> before = mappings(g0, "angle_0");
        StitchingConfig solveConfig = config(tempDir);
        solveConfig.setRegistrationMode(new RegistrationMode.Solve(
                solutionFile, RegistrationSettings.defaults().withThreads(1), null));
        TileRegistrationStep.applyTo(before, solveConfig);

        StitchingConfig applyConfig = config(tempDir);
        applyConfig.setRegistrationMode(new RegistrationMode.Apply(solutionFile));
        List<TileMapping> once = TileRegistrationStep.applyTo(mappings(g0, "angle_0"), applyConfig);
        List<TileMapping> twice = TileRegistrationStep.applyTo(mappings(g0, "angle_0"), applyConfig);

        for (int i = 0; i < once.size(); i++) {
            assertEquals(once.get(i).region.getX(), twice.get(i).region.getX(), "tile " + i + " X drifted on re-run");
            assertEquals(once.get(i).region.getY(), twice.get(i).region.getY(), "tile " + i + " Y drifted on re-run");
        }
    }

    // ------------------------------------------------------------------- guards

    @Test
    void disabledModeLeavesMappingsUntouched() throws IOException {
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        List<TileMapping> before = mappings(g, "angle_0");

        List<TileMapping> after = TileRegistrationStep.applyTo(before, config(tempDir));

        assertSame(before, after, "the default must be a true no-op, not a rebuilt list");
    }

    @Test
    void mismatchedSolutionIsRefusedNotApplied() throws IOException {
        // The nastiest silent corruption available: a solution solved at a different pixel size is
        // perfectly parseable and would shift every tile by a wrong but plausible amount.
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        Path solutionFile = tempDir.resolve(TileRegistrationSolution.DEFAULT_FILENAME);

        StitchingConfig solveConfig = config(tempDir);
        solveConfig.setRegistrationMode(new RegistrationMode.Solve(
                solutionFile, RegistrationSettings.defaults().withThreads(1), null));
        TileRegistrationStep.applyTo(mappings(g, "angle_0"), solveConfig);

        StitchingConfig wrongPixelSize = new StitchingConfig(
                "Coordinates in TileConfiguration.txt",
                tempDir.toString(),
                tempDir.toString(),
                "LZW",
                0.25, // solved at 0.5
                1.0,
                "",
                1.0,
                1.0,
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        wrongPixelSize.setRegistrationMode(new RegistrationMode.Apply(solutionFile));

        List<TileMapping> before = mappings(g, "angle_0");
        List<TileMapping> after = TileRegistrationStep.applyTo(before, wrongPixelSize);

        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(new double[] {0, 0}, applied(before, after, i), 1e-9, "tile " + i + " must not move");
        }
    }

    @Test
    void missingSolutionFileFallsBackToNominal() throws IOException {
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(tempDir, 2, 2, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        StitchingConfig config = config(tempDir);
        config.setRegistrationMode(new RegistrationMode.Apply(tempDir.resolve("nope.txt")));

        List<TileMapping> before = mappings(g, "angle_0");
        List<TileMapping> after = TileRegistrationStep.applyTo(before, config);

        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(new double[] {0, 0}, applied(before, after, i), 1e-9);
        }
    }

    @Test
    void corruptSolutionFileFallsBackToNominal() throws IOException {
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(tempDir, 2, 2, TILE_W, TILE_H, OVERLAP, 3.0, 42);
        Path junk = tempDir.resolve("junk.txt");
        Files.write(junk, List.of("this is not a solution file"), StandardCharsets.US_ASCII);

        StitchingConfig config = config(tempDir);
        config.setRegistrationMode(new RegistrationMode.Apply(junk));

        List<TileMapping> before = mappings(g, "angle_0");
        List<TileMapping> after = TileRegistrationStep.applyTo(before, config);

        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(new double[] {0, 0}, applied(before, after, i), 1e-9);
        }
    }

    @Test
    void zeroOverlapSolveLeavesEverythingAtNominal() throws IOException {
        SyntheticGridFixture.Grid g = SyntheticGridFixture.write(tempDir, 3, 3, TILE_W, TILE_H, 0.0, 3.0, 42);
        StitchingConfig config = config(tempDir);
        config.setRegistrationMode(new RegistrationMode.Solve(
                tempDir.resolve("sol.txt"), RegistrationSettings.defaults().withThreads(1), null));

        List<TileMapping> before = mappings(g, "angle_0");
        List<TileMapping> after = TileRegistrationStep.applyTo(before, config);

        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(new double[] {0, 0}, applied(before, after, i), 1e-9);
        }
    }
}
