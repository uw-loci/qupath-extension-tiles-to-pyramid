package qupath.ext.basicstitching;

import qupath.ext.basicstitching.registration.RegistrationRequest;
import qupath.ext.basicstitching.registration.RegistrationResult;

/** Thin shim so the figure harness can reach the engine from this package. */
final class TileRegistrationEngineAccess {
    private TileRegistrationEngineAccess() {}

    static RegistrationResult register(RegistrationRequest req) {
        return qupath.ext.basicstitching.registration.TileRegistrationEngine.register(req);
    }
}
