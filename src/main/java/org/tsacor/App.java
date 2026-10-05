/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 * The application window.
 */
package org.tsacor;

import java.awt.*;
import javax.swing.*;

public class App extends JFrame {
    private static final long serialVersionUID = 1L;

    public App() {
        super("tsacor-java " + TsaCor.VERSION + " -- Trial Sequential Analysis for correlations");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1380, scr.width - 40), Math.min(940, scr.height - 60));
        setLocationRelativeTo(null);
        MainPanel p = new MainPanel();
        setJMenuBar(p.menu());
        setContentPane(p);
    }
}
