package qupath.ext.basicstitching.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Java half of the {@code qpsc.resample} contract, whose Python half lives in
 * {@code microscope_imageprocessing.io.channel_semantics}. The two must agree.
 */
class ResamplePolicyTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "linear", "LINEAR", "  Linear  "})
    @DisplayName("absent or linear permits combining")
    void linearPermitsCombining(String declared) {
        assertTrue(ResamplePolicy.fromDeclared(declared).mayCombine());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nearest", "angular180", "angular360"})
    @DisplayName("every recognised non-linear policy forbids combining")
    void knownNonLinearForbidsCombining(String declared) {
        ResamplePolicy policy = ResamplePolicy.fromDeclared(declared);
        assertFalse(policy.mayCombine());
        assertFalse(policy == ResamplePolicy.UNKNOWN, "should have been recognised: " + declared);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quaternion", "label", "something-newer-than-this-build"})
    @DisplayName("an unrecognised policy fails towards preserving the data")
    void unknownForbidsCombining(String declared) {
        // The point of the design. A stitcher built today, handed a file from a newer
        // writer, must decline to average rather than average something it cannot
        // interpret -- the result would be a valid image that silently means nothing.
        assertEquals(ResamplePolicy.UNKNOWN, ResamplePolicy.fromDeclared(declared));
        assertFalse(ResamplePolicy.fromDeclared(declared).mayCombine());
    }

    @Test
    @DisplayName("angular policies are recognised as angular")
    void angularFlag() {
        assertTrue(ResamplePolicy.ANGULAR_180.isAngular());
        assertTrue(ResamplePolicy.ANGULAR_360.isAngular());
        assertFalse(ResamplePolicy.NEAREST.isAngular());
        assertFalse(ResamplePolicy.LINEAR.isAngular());
    }

    @Test
    @DisplayName("an angular channel without a period cannot be averaged circularly")
    void angularNeedsPeriod() {
        assertFalse(new ChannelSemantics.Declaration(ResamplePolicy.ANGULAR_180, 0).canAverageCircularly());
        assertTrue(new ChannelSemantics.Declaration(ResamplePolicy.ANGULAR_180, 18000).canAverageCircularly());
        assertFalse(new ChannelSemantics.Declaration(ResamplePolicy.NEAREST, 18000).canAverageCircularly());
    }
}
