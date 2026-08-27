package qupath.ext.basicstitching.assembly.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.channel.ChannelSemantics;
import qupath.ext.basicstitching.channel.ResamplePolicy;

/**
 * Downsampling behaviour per declared resample policy.
 *
 * <p>Orientation counts here use the convention the acquisition writes: 0..18000 spanning
 * 0..180 degrees, so one count is a hundredth of a degree.
 */
class PyramidAxialDownsampleTest {

    private static final double PERIOD = 18000;

    private static short deg(double degrees) {
        return (short) Math.round(degrees * 100.0);
    }

    private static double toDeg(short raw) {
        return (raw & 0xFFFF) / 100.0;
    }

    /** One 2x2 block of angles, downsampled to a single value under the given declaration. */
    private static double downsampleOneBlock(ChannelSemantics.Declaration declaration, double... degrees) {
        short[] src = new short[] {deg(degrees[0]), deg(degrees[1]), deg(degrees[2]), deg(degrees[3])};
        Object dst = PyramidLevelGenerator.downsample2x(src, 2, 2, 1, 1, 1, true, declaration);
        return toDeg(((short[]) dst)[0]);
    }

    @Test
    @DisplayName("the trap: averaging 179 and 1 as plain numbers gives 90 degrees")
    void plainAveragingIsWrongAcrossTheWrap() {
        // LINEAR is what an undeclared channel gets, and what the generator did
        // unconditionally before policies existed. 90 degrees is perpendicular to
        // the truth, and looks entirely plausible in the output.
        double got = downsampleOneBlock(ChannelSemantics.LINEAR, 179, 1, 179, 1);
        assertEquals(90.0, got, 0.02);
    }

    @Test
    @DisplayName("angular180 averages 179 and 1 to 0, the physically correct answer")
    void circularAveragingHandlesTheWrap() {
        ChannelSemantics.Declaration axial = new ChannelSemantics.Declaration(ResamplePolicy.ANGULAR_180, PERIOD);
        double got = downsampleOneBlock(axial, 179, 1, 179, 1);
        double err = Math.min(Math.abs(got - 0.0), Math.abs(got - 180.0));
        assertTrue(err < 0.02, "expected ~0 or ~180, got " + got);
    }

    @Test
    @DisplayName("circular averaging still agrees with the plain mean away from the wrap")
    void circularAveragingMatchesAwayFromTheWrap() {
        ChannelSemantics.Declaration axial = new ChannelSemantics.Declaration(ResamplePolicy.ANGULAR_180, PERIOD);
        assertEquals(45.0, downsampleOneBlock(axial, 40, 50, 40, 50), 0.02);
    }

    @Test
    @DisplayName("nearest emits only values that actually occurred")
    void decimationInventsNothing() {
        // The property that matters for labels and object ids: no output value may be
        // one that no input pixel had.
        ChannelSemantics.Declaration labels = new ChannelSemantics.Declaration(ResamplePolicy.NEAREST, 0);
        short[] src = new short[] {3, 7, 7, 3};
        short[] dst = (short[]) PyramidLevelGenerator.downsample2x(src, 2, 2, 1, 1, 1, true, labels);
        assertTrue(Set.of((short) 3, (short) 7).contains(dst[0]), "invented value " + dst[0]);
    }

    @Test
    @DisplayName("an unrecognised policy decimates rather than averaging")
    void unknownPolicyDecimates() {
        ChannelSemantics.Declaration unknown = new ChannelSemantics.Declaration(ResamplePolicy.UNKNOWN, 0);
        short[] src = new short[] {3, 7, 7, 3};
        short[] dst = (short[]) PyramidLevelGenerator.downsample2x(src, 2, 2, 1, 1, 1, true, unknown);
        assertTrue(Set.of((short) 3, (short) 7).contains(dst[0]));
    }

    @Test
    @DisplayName("an angular channel with no declared period falls back to decimation")
    void angularWithoutPeriodFallsBackSafely() {
        // Safe but lossier than circular averaging -- and far better than a wrong mean.
        ChannelSemantics.Declaration noPeriod = new ChannelSemantics.Declaration(ResamplePolicy.ANGULAR_180, 0);
        double got = downsampleOneBlock(noPeriod, 179, 1, 179, 1);
        assertTrue(got == 179.0 || got == 1.0, "expected a value that occurred, got " + got);
    }

    @Test
    @DisplayName("stored values stay inside the half-open range the writer declared")
    void resultStaysInRange() {
        ChannelSemantics.Declaration axial = new ChannelSemantics.Declaration(ResamplePolicy.ANGULAR_180, PERIOD);
        // A block straddling the wrap can round up to exactly the period; it must fold to 0.
        for (double base : new double[] {179.99, 179.995, 0.005, 90.0}) {
            double got = downsampleOneBlock(axial, base, base, base, base);
            assertTrue(got >= 0 && got < 180.0, "out of range: " + got);
        }
    }
}
