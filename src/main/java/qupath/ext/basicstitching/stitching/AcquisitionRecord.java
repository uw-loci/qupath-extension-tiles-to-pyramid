package qupath.ext.basicstitching.stitching;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code acquisition_metadata.json} a QPSC acquisition writes beside its tile folders
 * ({@code write_acquisition_metadata} in microscope_command_server's {@code acquisition/workflow.py}):
 * what the acquisition did, for values a TileConfiguration.txt has no field for.
 */
public final class AcquisitionRecord {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionRecord.class);

    /** The file's name. */
    public static final String FILE_NAME = "acquisition_metadata.json";

    private AcquisitionRecord() {}

    /**
     * The z-step the acquisition recorded: {@code acquisition.z_stack.z_step_um}.
     *
     * @param folder the selected folder, or one tile folder: the record is looked for in it and then
     *     in its parent
     * @return the step in microns, or null when there is no record, it holds no z-stack, or the step
     *     is not positive
     */
    public static Double zStepUm(Path folder) {
        Path file = find(folder);
        if (file == null) {
            return null;
        }
        try {
            JsonObject root = MicroManagerPlanes.readJson(file);
            JsonObject acq = object(root, "acquisition");
            JsonObject z = object(acq, "z_stack");
            if (z == null) {
                return null;
            }
            JsonElement enabled = z.get("enabled");
            if (enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean()) {
                return null;
            }
            JsonElement step = z.get("z_step_um");
            if (step == null
                    || !step.isJsonPrimitive()
                    || !step.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            double v = step.getAsDouble();
            return v > 0 && Double.isFinite(v) ? v : null;
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the z-step from {}: {}", file, e.toString());
            return null;
        }
    }

    /** @return the record in {@code folder} or its parent, or null */
    static Path find(Path folder) {
        if (folder == null) {
            return null;
        }
        Path here = folder.resolve(FILE_NAME);
        if (Files.isRegularFile(here)) {
            return here;
        }
        Path parent = folder.toAbsolutePath().normalize().getParent();
        Path up = parent == null ? null : parent.resolve(FILE_NAME);
        return up != null && Files.isRegularFile(up) ? up : null;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
