package qupath.ext.basicstitching.registration;

import java.nio.file.Path;

/**
 * How a stitch should treat tile positions: trust the stage, measure the overlap, or reuse a
 * previous measurement.
 *
 * <h2>Why two active modes rather than one</h2>
 *
 * A polarization or multi-channel acquisition captures several images at the <b>same</b> stage
 * position for every tile. Registering each of them independently would give each angle its own
 * corrections, misregistering the angles against each other -- worse than leaving them all on a
 * shared nominal grid. So exactly one subdirectory is measured ({@link Solve}, the slow part) and
 * every sibling reuses that measurement ({@link Apply}, effectively free).
 *
 * <p>Splitting it this way also means the correction is a durable artifact: a re-stitch can reuse a
 * solve instead of repeating it, and the file can be inspected when a mosaic looks wrong.
 */
public sealed interface RegistrationMode {

    /** Place tiles at their nominal stage positions. The historical behaviour, and the default. */
    record Disabled() implements RegistrationMode {}

    /**
     * Measure the overlaps and solve for corrected positions, writing the result for siblings to
     * reuse.
     *
     * @param solutionOut where to write the solved corrections
     * @param settings tuning; {@link RegistrationSettings#defaults()} unless there is a reason
     * @param reference subdirectory to solve on, or null to pick the one with the most texture
     */
    record Solve(Path solutionOut, RegistrationSettings settings, String reference) implements RegistrationMode {}

    /**
     * Reuse corrections from a previous {@link Solve}.
     *
     * <p>The solution is refused if it was solved for a run with different geometry; a mismatched
     * solution would shift tiles by a wrong-but-plausible amount, which is worse than not
     * registering at all.
     *
     * @param solutionIn the solution file to read
     */
    record Apply(Path solutionIn) implements RegistrationMode {}

    /** @return the default: place tiles at nominal stage positions. */
    static RegistrationMode disabled() {
        return new Disabled();
    }

    /**
     * @param solutionOut where to write the solved corrections
     * @return a solve using default tuning and an automatically chosen reference
     */
    static RegistrationMode solve(Path solutionOut) {
        return new Solve(solutionOut, RegistrationSettings.defaults(), null);
    }

    /**
     * @param solutionIn the solution file to read
     * @return a mode that reuses a previous solve
     */
    static RegistrationMode apply(Path solutionIn) {
        return new Apply(solutionIn);
    }
}
