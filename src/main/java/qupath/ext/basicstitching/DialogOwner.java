package qupath.ext.basicstitching;

import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

/**
 * Attaches dialogs to the QuPath main window so they cannot hide behind it.
 *
 * <p>A JavaFX {@link javafx.scene.control.Alert} is {@code APPLICATION_MODAL} by default, so it
 * blocks input to every window in the application. An unowned modal dialog is not tied to any
 * stage, so nothing keeps it in front of the QuPath window; when QuPath takes focus the dialog
 * can end up behind it, and the application then accepts no input with nothing visible to
 * explain why. Giving the dialog an owner fixes it: an owned window always stays above its
 * owner.
 *
 * <p>Same helper as {@code qupath.ext.qpsc.ui.DialogOwner}; keep the copies in step.
 */
public final class DialogOwner {

    private static final Logger logger = LoggerFactory.getLogger(DialogOwner.class);

    private DialogOwner() {}

    /**
     * Returns the QuPath main stage, or {@code null} when the GUI is not available.
     */
    public static Stage mainStage() {
        try {
            QuPathGUI gui = QuPathGUI.getInstance();
            return gui == null ? null : gui.getStage();
        } catch (Exception e) {
            logger.debug("Could not resolve the QuPath stage: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Owns {@code dialog} to the QuPath main window when one exists. Must be called before the
     * dialog is shown. A dialog that already has an owner, or for which no stage is available,
     * is returned unchanged.
     *
     * @param dialog the dialog to attach; {@code null} is ignored
     * @return the same dialog, for chaining
     */
    public static <T> Dialog<T> own(Dialog<T> dialog) {
        if (dialog == null) {
            return null;
        }
        Stage stage = mainStage();
        if (stage == null) {
            logger.debug("No QuPath stage; showing '{}' unowned", dialog.getTitle());
            return dialog;
        }
        Window existing = dialog.getOwner();
        if (existing != null) {
            return dialog;
        }
        try {
            dialog.initOwner(stage);
        } catch (IllegalStateException e) {
            logger.debug("Could not set owner on '{}': {}", dialog.getTitle(), e.getMessage());
        }
        return dialog;
    }
}
