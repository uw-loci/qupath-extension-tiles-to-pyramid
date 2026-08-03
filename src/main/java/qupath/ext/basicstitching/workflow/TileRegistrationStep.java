package qupath.ext.basicstitching.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationResult;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.registration.TileRegistrationEngine;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.stitching.TileMapping;
import qupath.lib.regions.ImageRegion;

/**
 * Applies content-based position corrections to tile mappings, between the strategy that produced
 * them and the stitcher that consumes them.
 *
 * <h2>Where this sits, and why</h2>
 *
 * Everything downstream -- the spatial index, the compositor, both writers -- reads tile positions
 * only through {@link TileMapping#region}. Correcting the mappings here therefore needs no changes
 * to any of it.
 *
 * <p>Corrections are applied <b>in memory</b>. TileConfiguration.txt is left exactly as the
 * acquisition wrote it, which keeps it the nominal record and makes re-running idempotent by
 * construction: there is no on-disk state to double-apply. It also avoids colliding with the
 * separate backup that the acquisition side already keeps of that file.
 */
public final class TileRegistrationStep {

    private static final Logger logger = LoggerFactory.getLogger(TileRegistrationStep.class);

    /** Tiles sampled per candidate subdirectory when choosing a reference automatically. */
    private static final int REFERENCE_SAMPLE_SIZE = 5;

    private TileRegistrationStep() {}

    /**
     * Correct tile positions according to the config's {@link RegistrationMode}.
     *
     * <p>Never throws. Any failure logs and returns the mappings untouched, so the stitch proceeds
     * at nominal positions -- registration improves a mosaic, it is not a precondition for one.
     *
     * @param mappings tiles at their nominal positions, for every subdirectory in this stitch
     * @param config the stitch configuration, carrying the mode
     * @return corrected mappings, or the originals if registration is disabled or not possible
     */
    public static List<TileMapping> applyTo(List<TileMapping> mappings, StitchingConfig config) {
        RegistrationMode mode = config.getRegistrationMode();
        if (mode instanceof RegistrationMode.Disabled || mappings.isEmpty()) {
            return mappings;
        }
        try {
            if (mode instanceof RegistrationMode.Solve solve) {
                return solveAndApply(mappings, config, solve);
            }
            if (mode instanceof RegistrationMode.Apply apply) {
                return readAndApply(mappings, config, apply.solutionIn());
            }
            return mappings;
        } catch (RuntimeException e) {
            logger.error("Tile registration step failed; stitching at nominal positions", e);
            return mappings;
        }
    }

    // ------------------------------------------------------------------ solve

    private static List<TileMapping> solveAndApply(
            List<TileMapping> mappings, StitchingConfig config, RegistrationMode.Solve solve) {

        Map<String, List<TileMapping>> bySubdir = groupBySubdir(mappings);
        Map<String, List<TileNode>> nodesBySubdir = new LinkedHashMap<>();
        bySubdir.forEach((name, tiles) -> nodesBySubdir.put(name, toNodes(tiles)));

        String reference = solve.reference();
        if (reference == null || !nodesBySubdir.containsKey(reference)) {
            reference = nodesBySubdir.size() == 1
                    ? nodesBySubdir.keySet().iterator().next()
                    : TileRegistrationEngine.chooseReference(nodesBySubdir, REFERENCE_SAMPLE_SIZE);
        }
        if (reference == null) {
            logger.warn("Could not choose a registration reference; stitching at nominal positions");
            return mappings;
        }

        List<TileNode> refNodes = nodesBySubdir.get(reference);
        logger.info("Solving tile registration on '{}' ({} tiles)", reference, refNodes.size());

        RegistrationResult result = TileRegistrationEngine.register(
                new qupath.ext.basicstitching.registration.RegistrationRequest(reference, refNodes, solve.settings()));

        if (result.degenerate()) {
            logger.warn("Tile registration produced no corrections: {}", result.summary());
            return mappings;
        }

        writeSolution(result, config, reference, refNodes, solve.solutionOut(), solve.settings());
        return applyDeltas(mappings, result.deltaPxByFilename());
    }

    private static void writeSolution(
            RegistrationResult result,
            StitchingConfig config,
            String reference,
            List<TileNode> refNodes,
            Path out,
            qupath.ext.basicstitching.registration.RegistrationSettings settings) {
        if (out == null) {
            return;
        }
        try {
            TileNode first = refNodes.get(0);
            TileRegistrationSolution.from(
                            result,
                            reference,
                            config.pixelSizeInMicrons,
                            config.baseDownsample,
                            TileConfigurationTxtStrategy.flipStitchingX,
                            TileConfigurationTxtStrategy.flipStitchingY,
                            first.widthPx(),
                            first.heightPx())
                    .write(out, settings);
        } catch (IOException e) {
            // The solve is already in hand and about to be applied to this stitch; only the sharing
            // with sibling angles is lost. Worth a loud warning, not a failed stitch.
            logger.warn("Could not write registration solution to {}: {}", out, e.toString());
        }
    }

    // ------------------------------------------------------------------ apply

    private static List<TileMapping> readAndApply(List<TileMapping> mappings, StitchingConfig config, Path solutionIn) {
        TileRegistrationSolution solution;
        try {
            solution = TileRegistrationSolution.read(solutionIn);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read registration solution {}: {}", solutionIn, e.toString());
            return mappings;
        }

        ImageRegion sample = mappings.get(0).region;
        String why = solution.incompatibilityReason(
                config.pixelSizeInMicrons,
                config.baseDownsample,
                TileConfigurationTxtStrategy.flipStitchingX,
                TileConfigurationTxtStrategy.flipStitchingY,
                sample.getWidth(),
                sample.getHeight());
        if (why != null) {
            // Applying a solution solved for different geometry would displace every tile by a
            // wrong-but-plausible amount -- a silent corruption. Nominal is strictly better.
            logger.warn("Ignoring registration solution {}: {}", solutionIn, why);
            return mappings;
        }

        logger.info(
                "Applying registration solution from '{}' ({} tiles, {} of {} edges accepted)",
                solution.header().reference(),
                solution.deltaPxByFilename().size(),
                solution.header().edgesAccepted(),
                solution.header().edgesTotal());
        return applyDeltas(mappings, solution.deltaPxByFilename());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Shift every mapping by its tile's correction.
     *
     * <p>Keyed by filename, so a tile that appears once per Z-slice or timepoint receives the same
     * correction on every plane -- which is right, since they were all captured at one stage
     * position. Positions round to whole pixels because {@link ImageRegion} is integer-only; at
     * sub-micron pixel sizes that is well under the stage error being corrected.
     */
    private static List<TileMapping> applyDeltas(List<TileMapping> mappings, Map<String, double[]> deltas) {
        List<TileMapping> out = new ArrayList<>(mappings.size());
        int shifted = 0;
        for (TileMapping m : mappings) {
            double[] d = deltas.get(m.file.getName());
            if (d == null || (d[0] == 0 && d[1] == 0)) {
                out.add(m);
                continue;
            }
            ImageRegion r = m.region;
            ImageRegion moved = ImageRegion.createInstance(
                    (int) Math.round(r.getX() + d[0]),
                    (int) Math.round(r.getY() + d[1]),
                    r.getWidth(),
                    r.getHeight(),
                    r.getZ(),
                    r.getT());
            out.add(new TileMapping(m.file, moved, m.subdirName, m.seriesIndex));
            shifted++;
        }
        logger.info("Registration moved {} of {} tile placements", shifted, mappings.size());
        return out;
    }

    private static Map<String, List<TileMapping>> groupBySubdir(List<TileMapping> mappings) {
        Map<String, List<TileMapping>> bySubdir = new LinkedHashMap<>();
        for (TileMapping m : mappings) {
            bySubdir.computeIfAbsent(m.subdirName, k -> new ArrayList<>()).add(m);
        }
        return bySubdir;
    }

    /**
     * One node per tile position.
     *
     * <p>Deduplicated by filename: a Z-stack or time series repeats the same filename once per
     * plane under {@code z*}/{@code t*} directories, and those are all the same stage position. The
     * grid must be solved once, not once per plane.
     */
    private static List<TileNode> toNodes(List<TileMapping> tiles) {
        Map<String, TileNode> byName = new LinkedHashMap<>();
        for (TileMapping m : tiles) {
            ImageRegion r = m.region;
            byName.computeIfAbsent(
                    m.file.getName(),
                    name -> new TileNode(name, m.file, r.getX(), r.getY(), r.getWidth(), r.getHeight()));
        }
        return new ArrayList<>(byName.values());
    }
}
