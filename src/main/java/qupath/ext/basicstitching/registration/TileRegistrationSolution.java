package qupath.ext.basicstitching.registration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A persisted registration solve: per-tile position corrections plus the acquisition parameters
 * they are only valid against.
 *
 * <p>This file is the mechanism that lets one solve serve a whole acquisition. Polarization angles
 * and fluorescence channels are captured at the <b>same</b> stage position for a given tile, so
 * solving each independently would misregister them against each other -- worse than sharing a
 * nominal grid. Instead the reference subdirectory is solved once and writes this file; every
 * sibling reads it and applies the identical correction.
 *
 * <p>Corrections are in <b>output-pixel space</b>, which means they are only meaningful for a run
 * with the same pixel size, downsample, flip flags and tile size. {@link #read} therefore records
 * those in the header and {@link #isCompatibleWith} checks them -- the file refuses to be applied
 * to a run it was not solved for, rather than silently shifting tiles by the wrong amount.
 *
 * <p>Format is ASCII, one tile per line, {@code name; deltaXPx; deltaYPx}, mirroring the
 * {@code name; ; (x, y)} shape of TileConfiguration.txt that it sits beside.
 */
public record TileRegistrationSolution(SolutionHeader header, Map<String, double[]> deltaPxByFilename) {

    private static final Logger logger = LoggerFactory.getLogger(TileRegistrationSolution.class);

    /** Conventional file name, written beside the angle subdirectories in the tile base dir. */
    public static final String DEFAULT_FILENAME = "TileRegistration.txt";

    private static final String MAGIC = "# QPSC tile registration solution v1";

    /** Relative tolerance when comparing the header's pixel size / downsample against a run. */
    private static final double PARAM_TOLERANCE = 1e-6;

    public TileRegistrationSolution {
        deltaPxByFilename = Map.copyOf(deltaPxByFilename);
    }

    /**
     * The parameters a solution is valid against, plus solve statistics for the audit trail.
     *
     * @param reference name of the subdirectory that was solved
     * @param pixelSizeUm pixel size the corrections were computed in
     * @param baseDownsample downsample the corrections were computed at
     * @param flipX whether the run negated stage X before converting to pixels
     * @param flipY whether the run negated stage Y before converting to pixels
     * @param tileWidthPx tile width the corrections were computed against
     * @param tileHeightPx tile height the corrections were computed against
     * @param overlapFracX X overlap fraction used for the search bound
     * @param overlapFracY Y overlap fraction used for the search bound
     * @param edgesAccepted edges that survived the gates
     * @param edgesTotal edges in the neighbour graph
     * @param tilesClamped tiles whose correction hit the overlap clamp
     */
    public record SolutionHeader(
            String reference,
            double pixelSizeUm,
            double baseDownsample,
            boolean flipX,
            boolean flipY,
            int tileWidthPx,
            int tileHeightPx,
            double overlapFracX,
            double overlapFracY,
            int edgesAccepted,
            int edgesTotal,
            int tilesClamped) {}

    /**
     * Build a solution from a solve result.
     *
     * @param result the engine's output
     * @param reference name of the subdirectory that was solved
     * @param pixelSizeUm pixel size the solve ran in
     * @param baseDownsample downsample the solve ran at
     * @param flipX whether the run negated stage X
     * @param flipY whether the run negated stage Y
     * @param tileWidthPx tile width
     * @param tileHeightPx tile height
     * @return a solution ready to {@link #write}
     */
    public static TileRegistrationSolution from(
            RegistrationResult result,
            String reference,
            double pixelSizeUm,
            double baseDownsample,
            boolean flipX,
            boolean flipY,
            int tileWidthPx,
            int tileHeightPx) {
        SolutionHeader header = new SolutionHeader(
                reference,
                pixelSizeUm,
                baseDownsample,
                flipX,
                flipY,
                tileWidthPx,
                tileHeightPx,
                result.overlapFracX(),
                result.overlapFracY(),
                result.edgesAccepted(),
                result.edgesTotal(),
                result.tilesClamped());
        return new TileRegistrationSolution(header, result.deltaPxByFilename());
    }

    /**
     * Write this solution to disk, overwriting any existing file.
     *
     * @param file destination
     * @throws IOException if the file cannot be written
     */
    public void write(Path file) throws IOException {
        write(file, null);
    }

    /**
     * Write this solution, additionally recording the tuning that produced it as a comment.
     *
     * <p>The settings line is informational only -- {@link #read} ignores unrecognised comment lines
     * -- but it makes a run self-documenting: anyone inspecting the file later can see exactly which
     * confidence threshold, shift bound, and solver knobs were in effect, without re-deriving them.
     *
     * @param file destination
     * @param settings the tuning used, or null to omit the settings line
     * @throws IOException if the file cannot be written
     */
    public void write(Path file, RegistrationSettings settings) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(MAGIC);
        lines.add(fmt("# reference: %s", header.reference()));
        lines.add(fmt(
                "# pixelSizeUm: %.6f   baseDownsample: %.4f   flipX: %s   flipY: %s",
                header.pixelSizeUm(), header.baseDownsample(), header.flipX(), header.flipY()));
        lines.add(fmt("# tileSizePx: %d x %d", header.tileWidthPx(), header.tileHeightPx()));
        lines.add(fmt("# overlapFracX: %.4f   overlapFracY: %.4f", header.overlapFracX(), header.overlapFracY()));
        lines.add(fmt(
                "# edgesAccepted: %d   edgesTotal: %d   tilesClamped: %d",
                header.edgesAccepted(), header.edgesTotal(), header.tilesClamped()));
        if (settings != null) {
            lines.add(fmt(
                    "# settings: minNcc=%.2f  maxShift=%.1f%% (min %dpx)  lambda=%.3f  outlierPasses=%d  "
                            + "minCoeffOfVar=%.3f  ambiguityRatio=%.2f  coarsestDownsample=%d  topKPeaks=%d  "
                            + "fillUnregistered=%s  threads=%d",
                    settings.minNcc(),
                    settings.maxStepErrorFrac() * 100,
                    settings.minStepErrorPx(),
                    settings.lambda(),
                    settings.maxOutlierIters(),
                    settings.minCoeffOfVar(),
                    settings.ambiguityRatio(),
                    settings.coarsestDownsample(),
                    settings.topKPeaks(),
                    settings.fillUnregistered(),
                    settings.threads()));
        }
        lines.add("# name; deltaXPx; deltaYPx");

        // Sorted so the file is diffable between runs.
        new java.util.TreeMap<>(deltaPxByFilename)
                .forEach((name, d) -> lines.add(fmt("%s; %.3f; %.3f", name, d[0], d[1])));

        Files.write(file, lines, StandardCharsets.US_ASCII);
        logger.info("Wrote registration solution for {} tiles to {}", deltaPxByFilename.size(), file.toAbsolutePath());
    }

    /**
     * Read a solution written by {@link #write}.
     *
     * @param file source
     * @return the parsed solution
     * @throws IOException if the file cannot be read, is not a solution file, or is malformed
     */
    public static TileRegistrationSolution read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        if (lines.isEmpty() || !lines.get(0).startsWith(MAGIC)) {
            throw new IOException("Not a tile registration solution file: " + file);
        }

        String reference = "";
        double pixelSizeUm = Double.NaN;
        double baseDownsample = Double.NaN;
        boolean flipX = false;
        boolean flipY = false;
        int tileW = 0;
        int tileH = 0;
        double overlapX = 0;
        double overlapY = 0;
        int accepted = 0;
        int total = 0;
        int clamped = 0;

        Map<String, double[]> deltas = new LinkedHashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String body = trimmed.substring(1).trim();
                if (body.startsWith("reference:")) {
                    reference = body.substring("reference:".length()).trim();
                } else if (body.startsWith("pixelSizeUm:")) {
                    pixelSizeUm = keyedDouble(body, "pixelSizeUm");
                    baseDownsample = keyedDouble(body, "baseDownsample");
                    flipX = keyedBoolean(body, "flipX");
                    flipY = keyedBoolean(body, "flipY");
                } else if (body.startsWith("tileSizePx:")) {
                    String[] wh = body.substring("tileSizePx:".length()).trim().split("x");
                    if (wh.length >= 2) {
                        tileW = Integer.parseInt(wh[0].trim());
                        tileH = Integer.parseInt(wh[1].trim());
                    }
                } else if (body.startsWith("overlapFracX:")) {
                    overlapX = keyedDouble(body, "overlapFracX");
                    overlapY = keyedDouble(body, "overlapFracY");
                } else if (body.startsWith("edgesAccepted:")) {
                    accepted = (int) keyedDouble(body, "edgesAccepted");
                    total = (int) keyedDouble(body, "edgesTotal");
                    clamped = (int) keyedDouble(body, "tilesClamped");
                }
                continue;
            }

            String[] parts = trimmed.split(";");
            if (parts.length < 3) {
                logger.warn("Skipping malformed solution line: {}", trimmed);
                continue;
            }
            deltas.put(
                    parts[0].trim(),
                    new double[] {Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())});
        }

        SolutionHeader header = new SolutionHeader(
                reference,
                pixelSizeUm,
                baseDownsample,
                flipX,
                flipY,
                tileW,
                tileH,
                overlapX,
                overlapY,
                accepted,
                total,
                clamped);
        return new TileRegistrationSolution(header, deltas);
    }

    /**
     * Whether this solution may be applied to a run with the given parameters.
     *
     * <p>Corrections are pixel-space, so a run at a different pixel size, downsample, flip
     * convention or tile size would be shifted by the wrong amount. This is a guard on recorded
     * data, not an attempt to re-derive the run's geometry.
     *
     * @param pixelSizeUm the run's pixel size
     * @param baseDownsample the run's downsample
     * @param flipX the run's X flip
     * @param flipY the run's Y flip
     * @param tileWidthPx the run's tile width
     * @param tileHeightPx the run's tile height
     * @return null when compatible, otherwise a human-readable reason it is not
     */
    public String incompatibilityReason(
            double pixelSizeUm,
            double baseDownsample,
            boolean flipX,
            boolean flipY,
            int tileWidthPx,
            int tileHeightPx) {
        if (!closeEnough(header.pixelSizeUm(), pixelSizeUm)) {
            return fmt("pixel size %.6f um does not match solution's %.6f um", pixelSizeUm, header.pixelSizeUm());
        }
        if (!closeEnough(header.baseDownsample(), baseDownsample)) {
            return fmt("downsample %.4f does not match solution's %.4f", baseDownsample, header.baseDownsample());
        }
        if (header.flipX() != flipX || header.flipY() != flipY) {
            return fmt(
                    "flip flags (X=%s, Y=%s) do not match solution's (X=%s, Y=%s)",
                    flipX, flipY, header.flipX(), header.flipY());
        }
        if (header.tileWidthPx() != tileWidthPx || header.tileHeightPx() != tileHeightPx) {
            return fmt(
                    "tile size %dx%d does not match solution's %dx%d",
                    tileWidthPx, tileHeightPx, header.tileWidthPx(), header.tileHeightPx());
        }
        return null;
    }

    /**
     * @param pixelSizeUm the run's pixel size
     * @param baseDownsample the run's downsample
     * @param flipX the run's X flip
     * @param flipY the run's Y flip
     * @param tileWidthPx the run's tile width
     * @param tileHeightPx the run's tile height
     * @return whether this solution may be applied to such a run
     */
    public boolean isCompatibleWith(
            double pixelSizeUm,
            double baseDownsample,
            boolean flipX,
            boolean flipY,
            int tileWidthPx,
            int tileHeightPx) {
        return incompatibilityReason(pixelSizeUm, baseDownsample, flipX, flipY, tileWidthPx, tileHeightPx) == null;
    }

    /**
     * @param filename tile file name
     * @return the correction for the given tile, or {@code {0, 0}} if this solution does not cover
     *     it
     */
    public double[] deltaFor(String filename) {
        double[] d = deltaPxByFilename.get(filename);
        return d == null ? new double[] {0, 0} : new double[] {d[0], d[1]};
    }

    private static boolean closeEnough(double a, double b) {
        double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
        return Math.abs(a - b) <= PARAM_TOLERANCE * scale;
    }

    private static double keyedDouble(String body, String key) {
        int at = body.indexOf(key + ":");
        if (at < 0) {
            return Double.NaN;
        }
        String rest = body.substring(at + key.length() + 1).trim();
        String token = rest.split("\\s+")[0].trim();
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean keyedBoolean(String body, String key) {
        int at = body.indexOf(key + ":");
        if (at < 0) {
            return false;
        }
        String rest = body.substring(at + key.length() + 1).trim();
        return Boolean.parseBoolean(rest.split("\\s+")[0].trim());
    }

    /** Locale-independent formatting: a comma decimal separator would corrupt the file. */
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
