package qupath.ext.basicstitching.registration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link TileRegistrationEngine} needs to solve one grid.
 *
 * <p>The request describes one grid of stage positions. What is correlated at each seam is
 * {@code primary}: one channel, or several combined into a normalized projection. Sharing the solve
 * across siblings is done by writing the result to a {@link TileRegistrationSolution} file and
 * applying it.
 *
 * @param referenceName what was solved on, for logging and the solution header
 * @param nominal the tiles at their nominal positions, in output-pixel space
 * @param settings tuning
 * @param primary the channel(s) every seam is measured on; several are averaged after scaling
 * @param alternates channels a seam is re-measured on when {@code primary} gives a weak or rejected
 *     match; empty to measure on {@code primary} only
 */
public record RegistrationRequest(
        String referenceName,
        List<TileNode> nominal,
        RegistrationSettings settings,
        List<RegistrationChannel> primary,
        List<RegistrationChannel> alternates) {

    public RegistrationRequest {
        nominal = List.copyOf(nominal);
        primary = List.copyOf(primary);
        alternates = List.copyOf(alternates);
        if (primary.isEmpty()) {
            throw new IllegalArgumentException("A registration request needs at least one primary channel");
        }
    }

    /**
     * A request that measures on the tiles' own files, unscaled, with no alternates.
     *
     * @param referenceName what is being solved, for logging and the solution header
     * @param nominal the tiles at their nominal positions
     * @param settings tuning
     */
    public RegistrationRequest(String referenceName, List<TileNode> nominal, RegistrationSettings settings) {
        this(referenceName, nominal, settings, List.of(channelOf(referenceName, nominal)), List.of());
    }

    /**
     * @param name channel name
     * @param tiles tiles whose own files make up the channel
     * @return an unscaled channel over those files
     */
    public static RegistrationChannel channelOf(String name, List<TileNode> tiles) {
        Map<String, File> files = new LinkedHashMap<>();
        for (TileNode t : tiles) {
            files.putIfAbsent(t.filename(), t.file());
        }
        return new RegistrationChannel(name, files, 1.0);
    }
}
