/* tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md. */
package org.tsacor;

import java.awt.GraphicsEnvironment;

/** Entry point: no arguments opens the GUI, any argument runs the command-line interface. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length > 0) {
            System.setProperty("java.awt.headless", "true");
            System.exit(Cli.run(args));
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available: use the command-line interface.\n\n" + Cli.USAGE);
            System.exit(2);
        }
        javax.swing.SwingUtilities.invokeLater(() -> new App().setVisible(true));
    }
}
