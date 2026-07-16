package qupath.ext.basicstitching.registration;

import java.util.List;

/**
 * Everything {@link TileRegistrationEngine} needs to solve one grid.
 *
 * <p>The request describes a single subdirectory -- the reference angle or channel. Sharing the
 * solve across siblings is done by writing the result to a {@link TileRegistrationSolution} file
 * and applying it, not by handing the engine several grids at once.
 *
 * @param referenceName name of the subdirectory being solved, for logging and the solution header
 * @param nominal the tiles at their nominal positions, in output-pixel space
 * @param settings tuning
 */
public record RegistrationRequest(String referenceName, List<TileNode> nominal, RegistrationSettings settings) {

    public RegistrationRequest {
        nominal = List.copyOf(nominal);
    }
}
