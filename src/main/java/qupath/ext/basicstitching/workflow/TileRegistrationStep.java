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
import qupath.ext.basicstitching.registration.ChannelNormalizer;
import qupath.ext.basicstitching.registration.RegistrationChannel;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.registration.RegistrationRequest;
import qupath.ext.basicstitching.registration.RegistrationResult;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileNode;
import qupath.ext.basicstitching.registration.TileRegistrationEngine;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;
import qupath.ext.basicstitching.stitching.StageAxisFlips;
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
            record(config, "failed; tiles at nominal stage positions (" + e + ")", null, null, 0, 0);
            return mappings;
        }
    }

    /**
     * Note on the config what registration did, for the stitch record. The solution file's own
     * header (reference, geometry, edges accepted, settings, normalization) is copied in verbatim,
     * so the record says exactly what the file says.
     */
    private static void record(
            StitchingConfig config, String mode, Path solution, String reference, int moved, int total) {
        java.util.Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mode", mode);
        if (reference != null) {
            entries.put("aligned on", reference);
        }
        if (solution != null) {
            entries.put("solution file", solution.toAbsolutePath().toString());
        }
        if (total > 0) {
            entries.put("tile placements moved", moved + " of " + total);
        }
        List<String> lines = new ArrayList<>();
        if (solution != null && java.nio.file.Files.exists(solution)) {
            try {
                for (String line :
                        java.nio.file.Files.readAllLines(solution, java.nio.charset.StandardCharsets.US_ASCII)) {
                    if (line.startsWith("#") && !line.startsWith("# name;")) {
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                logger.debug("Could not copy solution header from {}: {}", solution, e.toString());
            }
        }
        config.setRegistrationRecord(new StitchInfoFile.Section("registration", entries, lines));
    }

    private static int countMoved(List<TileMapping> before, List<TileMapping> after) {
        int moved = 0;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) != after.get(i)) {
                moved++;
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------ solve

    /**
     * Solve the registration and write the solution file, without stitching anything.
     *
     * <p>For callers that stitch each subdirectory separately but must solve with all of them in
     * view -- a normalized projection needs every channel at once, and so does the automatic choice.
     * Solve here with the mappings for every subdirectory, then stitch each one with
     * {@link RegistrationMode.Apply} on the written file.
     *
     * <p>Never throws, for the same reason as {@link #applyTo}.
     *
     * @param mappings tiles at their nominal positions, for every subdirectory involved
     * @param config the stitch configuration; its mode must be {@link RegistrationMode.Solve} with a
     *     non-null {@code solutionOut}
     * @return whether a solution was written
     */
    public static boolean solveOnly(List<TileMapping> mappings, StitchingConfig config) {
        if (!(config.getRegistrationMode() instanceof RegistrationMode.Solve solve) || solve.solutionOut() == null) {
            logger.warn("solveOnly needs a Solve mode with an output file; nothing solved");
            return false;
        }
        if (mappings.isEmpty()) {
            return false;
        }
        try {
            return solve(mappings, config, solve) != null && java.nio.file.Files.exists(solve.solutionOut());
        } catch (RuntimeException e) {
            logger.error("Tile registration solve failed; siblings will stitch at nominal positions", e);
            return false;
        }
    }

    private static List<TileMapping> solveAndApply(
            List<TileMapping> mappings, StitchingConfig config, RegistrationMode.Solve solve) {
        RegistrationResult result = solve(mappings, config, solve);
        if (result == null) {
            record(config, "solve found no usable corrections; tiles at nominal stage positions", null, null, 0, 0);
            return mappings;
        }
        List<TileMapping> out = applyDeltas(mappings, result.deltaPxByFilename());
        record(config, "solved on this stitch", solve.solutionOut(), null, countMoved(mappings, out), mappings.size());
        return out;
    }

    /** @return the result, or null when nothing usable was solved. */
    private static RegistrationResult solve(
            List<TileMapping> mappings, StitchingConfig config, RegistrationMode.Solve solve) {

        Map<String, List<TileMapping>> bySubdir = groupBySubdir(mappings);
        MeasuredPlane plane = MeasuredPlane.of(mappings);
        Map<String, List<TileNode>> nodesBySubdir = new LinkedHashMap<>();
        bySubdir.forEach((name, tiles) -> nodesBySubdir.put(name, toNodes(tiles, plane)));

        Plan plan = plan(solve.reference(), nodesBySubdir, solve.settings());
        if (plane.describe() != null) {
            List<String> notes = new ArrayList<>(plan.notes());
            notes.add("measured plane: " + plane.describe());
            plan = new Plan(plan.label(), plan.grid(), plan.primary(), plan.alternates(), notes);
            logger.info("Registration measures every position on {}", plane.describe());
        }
        logger.info(
                "Solving tile registration on {} ({} tiles)",
                plan.label(),
                plan.grid().size());

        RegistrationResult result = TileRegistrationEngine.register(new RegistrationRequest(
                plan.label(), plan.grid(), solve.settings(), plan.primary(), plan.alternates()));

        if (result.degenerate()) {
            logger.warn("Tile registration produced no corrections: {}", result.summary());
            return null;
        }

        writeSolution(result, config, plan, solve.solutionOut(), solve.settings());
        return result;
    }

    /**
     * What a solve measures, resolved against the subdirectories actually present.
     *
     * @param label names what was solved on, for logs and the solution header
     * @param grid the tile positions solved; all subdirectories share them
     * @param primary channel(s) every seam is measured on
     * @param alternates channels weak seams are re-measured on
     * @param notes extra lines for the solution file
     */
    private record Plan(
            String label,
            List<TileNode> grid,
            List<RegistrationChannel> primary,
            List<RegistrationChannel> alternates,
            List<String> notes) {}

    private static Plan plan(
            RegistrationReference reference, Map<String, List<TileNode>> nodesBySubdir, RegistrationSettings settings) {

        if (reference instanceof RegistrationReference.Single single) {
            List<TileNode> nodes = nodesBySubdir.get(single.subdir());
            if (nodes != null) {
                return new Plan(
                        single.subdir(),
                        nodes,
                        List.of(channelOn(single.subdir(), nodes, nodes)),
                        List.of(),
                        List.of("reference choice: selected"));
            }
            logger.warn(
                    "Registration reference '{}' is not among {}; choosing automatically",
                    single.subdir(),
                    nodesBySubdir.keySet());
        }

        if (reference instanceof RegistrationReference.Projection projection) {
            List<String> present = new ArrayList<>();
            for (String name : projection.subdirs()) {
                if (nodesBySubdir.containsKey(name)) {
                    present.add(name);
                }
            }
            if (present.size() < projection.subdirs().size()) {
                logger.warn(
                        "Projection channels {} not found among {}; projecting {}",
                        projection.subdirs().stream()
                                .filter(n -> !nodesBySubdir.containsKey(n))
                                .toList(),
                        nodesBySubdir.keySet(),
                        present);
            }
            if (!present.isEmpty()) {
                List<TileNode> grid = nodesBySubdir.get(present.get(0));
                List<RegistrationChannel> raw = new ArrayList<>();
                for (String name : present) {
                    raw.add(channelOn(name, grid, nodesBySubdir.get(name)));
                }
                List<ChannelNormalizer.Scale> scales = new ArrayList<>();
                List<RegistrationChannel> normalized = ChannelNormalizer.normalize(raw, grid, scales);
                List<String> notes = new ArrayList<>();
                notes.add("reference choice: normalized projection");
                for (ChannelNormalizer.Scale sc : scales) {
                    notes.add("projection channel: " + sc.describe());
                }
                return new Plan("projection(" + String.join("+", present) + ")", grid, normalized, List.of(), notes);
            }
            logger.warn("None of the projection channels are present; choosing automatically");
        }

        // Automatic: with one subdirectory there is nothing to choose. Candidates are compared on one
        // grid, then the chosen one's own tiles become the grid it is solved on.
        List<TileNode> anyGrid = nodesBySubdir.values().iterator().next();
        List<RegistrationChannel> candidates = new ArrayList<>();
        nodesBySubdir.forEach((name, nodes) -> candidates.add(channelOn(name, anyGrid, nodes)));
        String chosenName = TileRegistrationEngine.chooseReference(anyGrid, candidates, settings)
                .name();
        List<TileNode> grid = nodesBySubdir.get(chosenName);
        RegistrationChannel chosen = channelOn(chosenName, grid, grid);
        List<RegistrationChannel> alternates = new ArrayList<>();
        nodesBySubdir.forEach((name, nodes) -> {
            if (!name.equals(chosenName)) {
                alternates.add(channelOn(name, grid, nodes));
            }
        });
        List<String> notes = new ArrayList<>();
        notes.add(
                alternates.isEmpty()
                        ? "reference choice: only subdirectory"
                        : "reference choice: auto (tiles fit only one way on this one; weak seams re-measured on "
                                + String.join(
                                        ", ",
                                        alternates.stream()
                                                .map(RegistrationChannel::name)
                                                .toList())
                                + ")");
        return new Plan(chosenName, grid, List.of(chosen), alternates, notes);
    }

    /**
     * A channel's tiles keyed by the grid it is measured on: the tile of the same file name, else
     * the tile at the same nominal position. Folder layouts share file names across channels; a
     * Micro-Manager single-plane series names each channel's files apart, so only the position
     * matches there.
     *
     * @param grid the tiles being solved
     * @param own this channel's tiles
     */
    private static RegistrationChannel channelOn(String name, List<TileNode> grid, List<TileNode> own) {
        Map<String, TileNode> byName = new java.util.HashMap<>();
        Map<String, TileNode> byPosition = new java.util.HashMap<>();
        for (TileNode t : own) {
            byName.putIfAbsent(t.filename(), t);
            byPosition.putIfAbsent(positionKey(t), t);
        }
        Map<String, TileNode> byGridName = new LinkedHashMap<>();
        for (TileNode g : grid) {
            TileNode t = byName.get(g.filename());
            if (t == null) {
                t = byPosition.get(positionKey(g));
            }
            if (t != null) {
                byGridName.put(g.filename(), t);
            }
        }
        return new RegistrationChannel(name, byGridName, 1.0);
    }

    private static void writeSolution(
            RegistrationResult result, StitchingConfig config, Plan plan, Path out, RegistrationSettings settings) {
        if (out == null) {
            return;
        }
        try {
            TileNode first = plan.grid().get(0);
            StageAxisFlips flips = StageAxisFlips.forMethod(config.stitchingType);
            TileRegistrationSolution.from(
                            result,
                            plan.label(),
                            config.pixelSizeInMicrons,
                            config.baseDownsample,
                            flips.x(),
                            flips.y(),
                            first.widthPx(),
                            first.heightPx())
                    .write(out, settings, plan.notes());
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
            record(config, "solution missing or unreadable; tiles at nominal stage positions", solutionIn, null, 0, 0);
            return mappings;
        }

        ImageRegion sample = mappings.get(0).region;
        StageAxisFlips flips = StageAxisFlips.forMethod(config.stitchingType);
        String why = solution.incompatibilityReason(
                config.pixelSizeInMicrons,
                config.baseDownsample,
                flips.x(),
                flips.y(),
                sample.getWidth(),
                sample.getHeight());
        if (why != null) {
            // Applying a solution solved for different geometry would displace every tile by a
            // wrong-but-plausible amount -- a silent corruption. Nominal is strictly better.
            logger.warn("Ignoring registration solution {}: {}", solutionIn, why);
            record(config, "solution refused (" + why + "); tiles at nominal stage positions", solutionIn, null, 0, 0);
            return mappings;
        }

        logger.info(
                "Applying registration solution from '{}' ({} tiles, {} of {} edges accepted)",
                solution.header().reference(),
                solution.deltaPxByFilename().size(),
                solution.header().edgesAccepted(),
                solution.header().edgesTotal());
        List<TileMapping> out = applyDeltas(mappings, solution.deltaPxByFilename());
        record(
                config,
                "reused a solve from a sibling angle/channel",
                solutionIn,
                solution.header().reference(),
                countMoved(mappings, out),
                mappings.size());
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Shift every mapping by its tile's correction.
     *
     * <p>Keyed by filename, so a tile that appears once per Z-slice or timepoint receives the same
     * correction on every plane -- which is right, since they were all captured at one stage
     * position. A tile whose file has no correction of its own takes the one solved for its nominal
     * stage position: a Micro-Manager single-plane series writes each channel, slice and time point
     * to its own file, and those were captured at one position too. Positions round to whole pixels
     * because {@link ImageRegion} is integer-only; at sub-micron pixel sizes that is well under the
     * stage error being corrected.
     */
    private static List<TileMapping> applyDeltas(List<TileMapping> mappings, Map<String, double[]> deltas) {
        Map<String, double[]> byPosition = new java.util.HashMap<>();
        for (TileMapping m : mappings) {
            double[] d = deltas.get(m.file.getName());
            if (d != null) {
                byPosition.putIfAbsent(positionKey(m.region), d);
            }
        }
        List<TileMapping> out = new ArrayList<>(mappings.size());
        int shifted = 0;
        for (TileMapping m : mappings) {
            double[] d = deltas.get(m.file.getName());
            if (d == null) {
                d = byPosition.get(positionKey(m.region));
            }
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
            out.add(new TileMapping(m.file, moved, m.subdirName, m.ifdIndex));
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
     * The plane registration measures at every position: the middle z-slice of the first time
     * point the stitch holds, the same for every channel and position. The middle slice of a stack
     * set around focus is the sharpest; the first time point is the one every position has, even
     * in an acquisition stopped early. A position without that plane uses its nearest slice of its
     * earliest time point.
     *
     * @param z the slice measured
     * @param t the time point measured
     * @param slices distinct slices in the stitch
     * @param frames distinct time points in the stitch
     */
    record MeasuredPlane(int z, int t, int slices, int frames) {

        static MeasuredPlane of(List<TileMapping> mappings) {
            java.util.TreeSet<Integer> zs = new java.util.TreeSet<>();
            java.util.TreeSet<Integer> ts = new java.util.TreeSet<>();
            for (TileMapping m : mappings) {
                zs.add(m.region.getZ());
                ts.add(m.region.getT());
            }
            List<Integer> sorted = new ArrayList<>(zs);
            return new MeasuredPlane(sorted.get((sorted.size() - 1) / 2), ts.first(), zs.size(), ts.size());
        }

        /** Lower is a better stand-in for the measured plane: earliest time point, then nearest slice. */
        long distance(ImageRegion r) {
            return ((long) Math.abs(r.getT() - t) << 32) | Math.abs(r.getZ() - z);
        }

        /** @return the plane in words for the log and the solution header; null for a 2D stitch */
        String describe() {
            if (slices <= 1 && frames <= 1) {
                return null;
            }
            return "z-slice " + z + " (middle of " + slices + ") of time point " + t + " (first of " + frames + ")";
        }
    }

    /**
     * One node per stage position, read on the measured plane. A tile seen once per z-slice or time
     * point is still one tile to register, whether its planes are pages of one file (Micro-Manager
     * OME-TIFF per position), files of one name in {@code t*}/{@code z*} folders (QPSC), or files of
     * their own (Micro-Manager single-plane series).
     */
    private static List<TileNode> toNodes(List<TileMapping> tiles, MeasuredPlane plane) {
        Map<String, TileMapping> byPosition = new LinkedHashMap<>();
        for (TileMapping m : tiles) {
            String at = positionKey(m.region);
            TileMapping have = byPosition.get(at);
            if (have == null || plane.distance(m.region) < plane.distance(have.region)) {
                byPosition.put(at, m);
            }
        }
        List<TileNode> out = new ArrayList<>(byPosition.size());
        for (TileMapping m : byPosition.values()) {
            ImageRegion r = m.region;
            out.add(new TileNode(
                    m.file.getName(), m.file, r.getX(), r.getY(), r.getWidth(), r.getHeight(), m.ifdIndex));
        }
        return out;
    }

    private static String positionKey(TileNode t) {
        return (int) Math.round(t.xPx()) + "," + (int) Math.round(t.yPx());
    }

    private static String positionKey(ImageRegion r) {
        return r.getX() + "," + r.getY();
    }
}
