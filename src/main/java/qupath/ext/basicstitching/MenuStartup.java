// =======================================================================================
// 1. MenuStartup.java
// =======================================================================================
package qupath.ext.basicstitching;

import java.util.ResourceBundle;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.functions.StitchingGUI;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * TODO: create public functions so that stitching can be run from command line or a script
 * CHECK: Always build AND publish to maven local for use with qp-scope
 * ./gradlew publishToMavenLocal
 */
public class MenuStartup implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(MenuStartup.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.basicstitching.ui.strings");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

    @Override
    public void installExtension(QuPathGUI qupath) {
        addMenuItem(qupath);
    }

    /**
     * Get the description of the extension.
     *
     * @return The description of the extension.
     */
    @Override
    public String getDescription() {
        return res.getString("name");
    }

    /**
     * Get the name of the extension.
     *
     * @return The name of the extension.
     */
    @Override
    public String getName() {
        return "name";
    }

    private void addMenuItem(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + res.getString("name"), true);
        var fileNameStitching = new MenuItem(res.getString("title"));
        fileNameStitching.setOnAction(e -> {
            logger.info("GUI menu click detected");
            StitchingGUI.createGUI();
        });
        menu.getItems().add(fileNameStitching);
    }
}
