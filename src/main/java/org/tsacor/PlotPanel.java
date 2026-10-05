/* tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md. */
package org.tsacor;

import java.awt.*;
import javax.swing.JPanel;

/** Shows the TSA chart scaled to the available space (vector rendering, so it stays sharp). */
public class PlotPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private TsaCor.Result result;
    private TsaPlot.Options options = new TsaPlot.Options();

    public PlotPanel() { setBackground(Color.WHITE); }

    public void setResult(TsaCor.Result r) { this.result = r; repaint(); }
    public void setOptions(TsaPlot.Options o) { this.options = o; repaint(); }
    public TsaPlot.Options getOptions() { return options; }
    public TsaCor.Result getResult() { return result; }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        if (result == null) {
            g.setColor(Color.GRAY);
            g.setFont(getFont().deriveFont(15f));
            String s = "Load a data file (or the example data) and press \"Run TSA\".";
            g.drawString(s, (getWidth() - g.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
        } else {
            double s = Math.min(getWidth() / (double) TsaPlot.LOGICAL_W, getHeight() / (double) TsaPlot.LOGICAL_H);
            g.translate((getWidth() - TsaPlot.LOGICAL_W * s) / 2, (getHeight() - TsaPlot.LOGICAL_H * s) / 2);
            g.scale(s, s);
            TsaPlot.draw(g, TsaPlot.LOGICAL_W, TsaPlot.LOGICAL_H, result, options);
        }
        g.dispose();
    }
}
