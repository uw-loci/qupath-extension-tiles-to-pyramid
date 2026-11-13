package qupath.ext.basicstitching.functions;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.ext.basicstitching.progress.StitchProgress;
import qupath.lib.gui.QuPathGUI;

/**
 * A non-modal window showing what a stitch started from the Tiles to Pyramid dialog is doing:
 * the step (preprocessing, stitching, merging channels), a progress bar, the current output and
 * the time used and left. Closing it does not stop the stitch; choosing Tiles to Pyramid again
 * while the stitch runs brings it back.
 *
 * <p>{@link #update} may be called from any thread and as often as wanted: the window shows the
 * latest state, at most once per FX pulse.
 */
final class StitchProgressWindow {

    static final String TITLE = "Tiles to Pyramid progress";

    private final Stage stage = new Stage();
    private final Label folderLabel = new Label();
    private final Label stepLabel = new Label("Starting...");
    private final Label detailLabel = new Label();
    private final Label timeLabel = new Label();
    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);

    private final Object lock = new Object();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private State latest;

    /** One displayed state. {@code fraction} is 0 to 1, or negative when unknown. */
    private record State(String step, double fraction, String detail, String time) {}

    /** Create the window; call on the FX thread. */
    StitchProgressWindow(String folder) {
        stage.setTitle(TITLE);
        stage.initModality(Modality.NONE);
        var qupath = QuPathGUI.getInstance();
        if (qupath != null && qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
        }
        folderLabel.setText("Stitching " + folder);
        folderLabel.setStyle("-fx-font-weight: bold;");
        for (Label l : new Label[] {folderLabel, stepLabel, detailLabel, timeLabel}) {
            wrap(l);
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinHeight(Region.USE_PREF_SIZE);
        Label note = new Label("Closing this window does not stop the stitch. Choose Tiles to Pyramid again"
                + " to bring it back. You will be told when the stitch finishes.");
        wrap(note);
        note.setStyle("-fx-opacity: 0.75;");
        Button hide = new Button("Hide");
        hide.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(spacer, hide);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(8, folderLabel, stepLabel, bar, detailLabel, timeLabel, note, buttons);
        root.setPadding(new Insets(12));
        root.setPrefWidth(460);
        stage.setScene(new Scene(root));
        stage.setResizable(true);
    }

    /** Grow the window to the height its content needs at its current width. */
    private void fitHeight() {
        Scene scene = stage.getScene();
        if (!stage.isShowing()) {
            return;
        }
        double need = scene.getRoot().prefHeight(scene.getWidth());
        if (need > scene.getHeight() + 0.5) {
            stage.setHeight(stage.getHeight() + (need - scene.getHeight()));
        }
    }

    /** Wrap at the window's width and take the height the wrapped text needs, never "...". */
    private static void wrap(Label l) {
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(Region.USE_PREF_SIZE);
    }

    /** Show the window, or bring it to the front; call on the FX thread. */
    void show() {
        if (!stage.isShowing()) {
            stage.show();
            Platform.runLater(this::fitHeight);
        }
        stage.toFront();
    }

    /** Close the window for good; any thread. */
    void close() {
        Platform.runLater(stage::close);
    }

    /**
     * Show a step; any thread.
     *
     * @param step what is running, e.g. "Merging channels"
     * @param fraction how much of it is done, 0 to 1; negative when unknown
     * @param detail the current output or item; may be empty
     * @param time time used and left; may be empty
     */
    void update(String step, double fraction, String detail, String time) {
        synchronized (lock) {
            latest = new State(step, fraction, detail == null ? "" : detail, time == null ? "" : time);
        }
        if (scheduled.compareAndSet(false, true)) {
            Platform.runLater(this::apply);
        }
    }

    /** Show a stitch's own progress report under {@code step}; any thread. */
    void update(String step, StitchProgress p) {
        String phase = p.phase().label();
        String time = StitchProgress.formatDuration(p.elapsedSeconds()) + " so far, " + p.remainingText()
                + (p.measured() || Double.isNaN(p.remainingSeconds()) ? "" : " (first estimate)");
        update(
                step + ": " + phase,
                p.phaseFraction(),
                p.detail() == null || p.detail().isBlank() ? p.target() : p.detail(),
                time);
    }

    /**
     * "2 min so far, about 5 min left" for a step that measures its own progress, extrapolated from
     * the rate so far once a little of it is done.
     */
    static String timeText(double elapsedSeconds, double fraction) {
        String used = StitchProgress.formatDuration(elapsedSeconds) + " so far";
        if (fraction < 0.02 || fraction >= 1 || elapsedSeconds < 5) {
            return used;
        }
        return used + ", " + StitchProgress.formatRemaining(elapsedSeconds * (1 - fraction) / fraction);
    }

    private void apply() {
        scheduled.set(false);
        State s;
        synchronized (lock) {
            s = latest;
        }
        if (s == null) {
            return;
        }
        stepLabel.setText(s.step());
        bar.setProgress(s.fraction() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : Math.min(1, s.fraction()));
        detailLabel.setText(s.detail());
        detailLabel.setVisible(!s.detail().isEmpty());
        detailLabel.setManaged(!s.detail().isEmpty());
        timeLabel.setText(s.time());
        // A longer step line wraps onto another row; grow the window rather than cut it off. The
        // new text is measured only after the next layout, so check then.
        Platform.runLater(this::fitHeight);
    }
}
