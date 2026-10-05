/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 * Swing application: no R installation needed.
 */
package org.tsacor;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** The whole user interface as a panel (so it can be laid out and tested without a window). */
class MainPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private DataSet data;
    private String dataName = "(no data)";
    TsaCor.Result result;
    private File lastDir = new File(System.getProperty("user.home"));

    private final JLabel dataLabel = new JLabel("No data loaded");
    private final JTable dataTable = new JTable();
    final JTable studiesTable = new JTable();
    private boolean loading = false;
    private final JTextField alpha = new JTextField("0.05", 6), power = new JTextField("0.80", 6),
        targetR = new JTextField("", 6), ciLevel = new JTextField("0.95", 6);
    private final JComboBox<String> corType = new JComboBox<>(new String[]{"pearson", "spearman"}),
        seSource = new JComboBox<>(new String[]{"ci", "n"}),
        spearmanVar = new JComboBox<>(new String[]{"fieller", "bonett_wright"}),
        method = new JComboBox<>(Rma.METHODS),
        orderBy = new JComboBox<>(),
        route = new JComboBox<>(new String[]{"design", "analysis"}),
        reInf = new JComboBox<>(new String[]{"standard", "hksj", "hksj_adhoc"}),
        projStat = new JComboBox<>(new String[]{"median", "mean"}),
        ippBasis = new JComboBox<>(new String[]{"per_study", "pooled"});
    private final JCheckBox fallback = new JCheckBox("If the analysis route fails, fall back to the design route", true);
    final JButton runBtn = new JButton("Run TSA");
    final PlotPanel plot = new PlotPanel();
    final JTextArea summaryArea = mono(), logArea = mono(), warnArea = mono();
    private final JTable cumTable = new JTable();
    final JTabbedPane tabs = new JTabbedPane();
    final JLabel status = new JLabel(" ");

    private static JTextArea mono() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setLineWrap(true);          // everything stays visible: no scrolling to the right
        a.setWrapStyleWord(true);
        return a;
    }

    /** Scroll pane for a table, with its column header always attached. */
    private static JScrollPane tableScroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setColumnHeaderView(t.getTableHeader());
        return sp;
    }

    /** Scroll pane that only scrolls vertically (the text wraps to the window width). */
    private static JScrollPane wrapped(JTextArea a) {
        return new JScrollPane(a, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    }

    MainPanel() {
        super(new BorderLayout());

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        JScrollPane settingsScroll = new JScrollPane(settingsPanel(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.setBorder(null);
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, dataPanel(), settingsScroll);
        leftSplit.setResizeWeight(0.0);
        leftSplit.setDividerLocation(265);
        leftSplit.setBorder(null);
        left.add(leftSplit, BorderLayout.CENTER);
        runBtn.setFont(runBtn.getFont().deriveFont(Font.BOLD, 14f));
        runBtn.addActionListener(e -> runAnalysis());
        left.add(runBtn, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(440, 100));

        tabs.addTab("TSA chart", plot);
        tabs.addTab("Summary", wrapped(summaryArea));
        tabs.addTab("Cumulative results", tableScroll(cumTable));
        tabs.addTab("Full log", wrapped(logArea));
        tabs.addTab("Warnings", wrapped(warnArea));
        tabs.addTab("Data", tableScroll(dataTable));
        JPanel plotBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton po = new JButton("Chart options...");
        po.addActionListener(e -> chartOptions());
        plotBar.add(po);
        JButton png = new JButton("Save chart (PNG)...");
        png.addActionListener(e -> savePng());
        plotBar.add(png);
        JPanel right = new JPanel(new BorderLayout());
        right.add(plotBar, BorderLayout.NORTH);
        right.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        add(status, BorderLayout.SOUTH);
        orderBy.addItem("(keep file order)");
        orderBy.addActionListener(e -> { if (!loading) refreshPreview(); });
        corType.addActionListener(e -> updateEnabled());
        seSource.addActionListener(e -> updateEnabled());
        updateEnabled();
    }

    JMenuBar menu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open data file (.xlsx / .csv)...");
        open.addActionListener(e -> openFile());
        JMenuItem ex = new JMenuItem("Load example data (20 studies)");
        ex.addActionListener(e -> loadExample());
        JMenuItem savePng = new JMenuItem("Save chart as PNG...");
        savePng.addActionListener(e -> savePng());
        JMenuItem saveSum = new JMenuItem("Save summary table (CSV)...");
        saveSum.addActionListener(e -> saveCsv("summary"));
        JMenuItem saveCum = new JMenuItem("Save cumulative results (CSV)...");
        saveCum.addActionListener(e -> saveCsv("cumulative"));
        JMenuItem saveBnd = new JMenuItem("Save boundaries (CSV)...");
        saveBnd.addActionListener(e -> saveCsv("boundaries"));
        JMenuItem saveRep = new JMenuItem("Save full report (text)...");
        saveRep.addActionListener(e -> saveReport());
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> System.exit(0));
        for (JMenuItem m : new JMenuItem[]{open, ex}) file.add(m);
        file.addSeparator();
        for (JMenuItem m : new JMenuItem[]{savePng, saveSum, saveCum, saveBnd, saveRep}) file.add(m);
        file.addSeparator();
        file.add(quit);
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About / licence");
        about.addActionListener(e -> about());
        JMenuItem fmt = new JMenuItem("Data format");
        fmt.addActionListener(e -> JOptionPane.showMessageDialog(this, FORMAT_HELP, "Data format", JOptionPane.INFORMATION_MESSAGE));
        help.add(fmt); help.add(about);
        mb.add(file); mb.add(help);
        return mb;
    }

    static final String FORMAT_HELP = "One row per study, in a .xlsx (first sheet) or .csv file, with a header row and these columns:\n\n"
        + "  Study       unique study label\n"
        + "  r           Pearson correlation or Spearman rho (strictly between -1 and 1)\n"
        + "  n_subjects  number of subjects (whole number, greater than 3)\n"
        + "  lbound      lower limit of the study's confidence interval (correlation scale)\n"
        + "  ubound      upper limit of the study's confidence interval\n\n"
        + "lbound / ubound are only needed when the standard errors are taken from the confidence intervals\n"
        + "(default); with \"SE source = n\" they can be omitted. Other columns (e.g. Year) are kept and can be\n"
        + "used to sort the studies. TSA is order-dependent: sort chronologically.";

    private JPanel dataPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Data"));
        JPanel head = new JPanel(new GridLayout(0, 1, 4, 4));
        JPanel b = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton open = new JButton("Open file...");
        open.addActionListener(e -> openFile());
        JButton ex = new JButton("Example data");
        ex.addActionListener(e -> loadExample());
        b.add(open); b.add(ex);
        head.add(b);
        head.add(dataLabel);
        p.add(head, BorderLayout.NORTH);
        studiesTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        studiesTable.setFillsViewportHeight(true);
        studiesTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane sp = tableScroll(studiesTable);
        sp.setBorder(BorderFactory.createTitledBorder("Studies (in analysis order)"));
        sp.setPreferredSize(new Dimension(300, 190));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** Shows the studies in the order the analysis will use (sorted by the chosen "Order studies by" column). */
    private void refreshPreview() {
        if (data == null) return;
        String ob = (String) orderBy.getSelectedItem();
        DataSet shown = (ob == null || ob.startsWith("(")) ? data : data.sortedBy(ob);
        DefaultTableModel m = new DefaultTableModel(shown.names.toArray(), 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] r : shown.rows) m.addRow(r);
        studiesTable.setModel(m);
        for (int c = 0; c < studiesTable.getColumnCount(); c++) {
            int w = studiesTable.getTableHeader().getDefaultRenderer()
                .getTableCellRendererComponent(studiesTable, studiesTable.getColumnName(c), false, false, -1, c).getPreferredSize().width;
            for (int r = 0; r < Math.min(studiesTable.getRowCount(), 300); r++)
                w = Math.max(w, studiesTable.getCellRenderer(r, c)
                    .getTableCellRendererComponent(studiesTable, studiesTable.getValueAt(r, c), false, false, r, c).getPreferredSize().width);
            studiesTable.getColumnModel().getColumn(c).setPreferredWidth(Math.min(w + 16, 230));
        }
    }

    private JPanel settingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        row = add(p, c, row, "alpha (two-sided)", alpha, "Two-sided type I error, e.g. 0.05");
        row = add(p, c, row, "Power", power, "e.g. 0.80");
        row = add(p, c, row, "Anticipated r (target_r)", targetR, "Pre-specified anticipated correlation. Leave empty to use the observed pooled r (circular; exploratory only).");
        row = add(p, c, row, "Correlation type", corType, "Which coefficient the r column holds");
        row = add(p, c, row, "SE(z) source", seSource, "ci: from the reported CIs (default); n: from sample sizes");
        row = add(p, c, row, "Spearman variance", spearmanVar, "Variance model of Fisher's z for Spearman's rho");
        row = add(p, c, row, "CI level of the file", ciLevel, "Level of the reported confidence intervals");
        row = add(p, c, row, "tau\u00b2 estimator", method, "Between-study variance estimator");
        row = add(p, c, row, "Order studies by", orderBy, "Sort ascending by this column (TSA is order-dependent)");
        row = add(p, c, row, "Boundary route", route, "RTSA design route (DARIS = t 1) or analysis route (retrospective endpoint)");
        row = add(p, c, row, "RE inference", reInf, "standard Wald z, or Hartung-Knapp-Sidik-Jonkman");
        row = add(p, c, row, "Projection statistic", projStat, "Typical study used for the 'additional studies' projection");
        row = add(p, c, row, "Info/participant basis", ippBasis, "Historical information-per-participant rate");
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        p.add(fallback, c);
        c.gridy = row + 1; c.weighty = 1;
        p.add(Box.createVerticalGlue(), c);
        return p;
    }

    private int add(JPanel p, GridBagConstraints c, int row, String label, JComponent comp, String tip) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setToolTipText(tip);
        p.add(l, c);
        c.gridx = 1; c.weightx = 1;
        comp.setToolTipText(tip);
        p.add(comp, c);
        return row + 1;
    }

    private void updateEnabled() {
        boolean sp = "spearman".equals(corType.getSelectedItem());
        spearmanVar.setEnabled(sp);
    }

    // ---------------------------------------------------------------- data
    private void openFile() {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Data files (xlsx, csv, tsv, txt)", "xlsx", "xlsm", "csv", "tsv", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        lastDir = f.getParentFile();
        try {
            setData(DataLoader.load(f), f.getName());
        } catch (Exception e) {
            error("Cannot read the file", e);
        }
    }

    void loadExample() {
        try {
            setData(Examples.load(), "example data (r_meta.xlsx)");
            targetR.setText("0.10");
            selectOrderBy("Year");
        } catch (Exception e) {
            error("Cannot load the example data", e);
        }
    }

    private void selectOrderBy(String col) {
        for (int i = 0; i < orderBy.getItemCount(); i++) if (orderBy.getItemAt(i).equals(col)) orderBy.setSelectedIndex(i);
    }

    private void setData(DataSet ds, String name) {
        loading = true;
        data = ds;
        dataName = name;
        dataLabel.setText(name + " (" + ds.nrow() + " rows)");
        DefaultTableModel m = new DefaultTableModel(ds.names.toArray(), 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] r : ds.rows) m.addRow(r);
        dataTable.setModel(m);
        orderBy.removeAllItems();
        orderBy.addItem("(keep file order)");
        for (String n : ds.names) orderBy.addItem(n);
        if (ds.names.contains("Year")) selectOrderBy("Year");
        loading = false;
        refreshPreview();
        status.setText("Loaded " + name);
    }

    // ---------------------------------------------------------------- run
    private TsaCor.Params readParams() {
        TsaCor.Params p = new TsaCor.Params();
        p.alphaTwoSided = num(alpha, "alpha");
        p.power = num(power, "power");
        p.ciLevel = num(ciLevel, "CI level");
        String t = targetR.getText().trim();
        p.targetR = (t.isEmpty() || t.equalsIgnoreCase("NA")) ? Double.NaN : num(targetR, "target r");
        p.corType = (String) corType.getSelectedItem();
        p.seSource = (String) seSource.getSelectedItem();
        p.spearmanVariance = (String) spearmanVar.getSelectedItem();
        p.method = (String) method.getSelectedItem();
        String ob = (String) orderBy.getSelectedItem();
        p.orderBy = (ob == null || ob.startsWith("(")) ? null : ob;
        p.boundaryRoute = (String) route.getSelectedItem();
        p.reInference = (String) reInf.getSelectedItem();
        p.projectionStat = (String) projStat.getSelectedItem();
        p.infoPerParticipantBasis = (String) ippBasis.getSelectedItem();
        p.fallbackToDesign = fallback.isSelected();
        p.verbose = true;
        return p;
    }

    private double num(JTextField f, String what) {
        try { return Double.parseDouble(f.getText().trim().replace(',', '.')); }
        catch (NumberFormatException e) { throw new TsaCor.TsaException("Please enter a number for " + what + "."); }
    }

    void runAnalysis() {
        if (data == null) { JOptionPane.showMessageDialog(this, "Load a data file first (File > Open, or the example data).", "No data", JOptionPane.WARNING_MESSAGE); return; }
        final TsaCor.Params p;
        try { p = readParams(); } catch (TsaCor.TsaException e) { JOptionPane.showMessageDialog(this, e.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE); return; }
        runBtn.setEnabled(false);
        status.setText("Computing the trial sequential monitoring boundaries (this can take a few seconds)...");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<TsaCor.Result, Void>() {
            @Override protected TsaCor.Result doInBackground() { return TsaCor.run(data, p); }
            @Override protected void done() {
                runBtn.setEnabled(true);
                setCursor(Cursor.getDefaultCursor());
                try {
                    show(get());
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    status.setText("Analysis failed.");
                    JOptionPane.showMessageDialog(MainPanel.this, t.getMessage(), "Analysis failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void show(TsaCor.Result r) {
        result = r;
        plot.setResult(r);
        summaryArea.setText(TsaCor.printText(r) + "\n" + TsaCor.summaryText(r));
        summaryArea.setCaretPosition(0);
        logArea.setText(r.log);
        logArea.setCaretPosition(0);
        StringBuilder w = new StringBuilder();
        for (String s : r.warnings) w.append("- ").append(s).append("\n\n");
        warnArea.setText(r.warnings.isEmpty() ? "No warnings." : w.toString());
        DefaultTableModel m = new DefaultTableModel(Export.CUM_HEADER, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int a, int b) { return false; }
        };
        for (String[] row : Export.cumulativeRows(r)) {
            String[] shown = row.clone();
            for (int i = 1; i < shown.length; i++) {
                try { shown[i] = String.format(Locale.ROOT, "%.4f", Double.parseDouble(shown[i])); } catch (NumberFormatException ignore) { /* NA */ }
            }
            m.addRow(shown);
        }
        cumTable.setModel(m);
        tabs.setTitleAt(4, r.warnings.isEmpty() ? "Warnings" : "Warnings (" + r.warnings.size() + ")");
        tabs.setSelectedIndex(0);
        status.setText(String.format(Locale.ROOT, "Done: %d studies, %.0f participants; pooled %s = %.3f; DARIS participant-equivalent = %.0f; chart labels: %s.",
            r.nStudies, r.participantsAccrued, r.corSym(), r.pooledR, Math.ceil(r.darisParticipants),
            TsaPlot.lowerLabels(r, plot.getOptions()) ? "lower part (Z-curve positive)" : "upper part"));
    }

    // ---------------------------------------------------------------- chart options / export
    private void chartOptions() {
        TsaPlot.Options o = plot.getOptions();
        JComboBox<String> pl = new JComboBox<>(new String[]{"Automatic (lower if the Z-curve is positive)", "Always upper", "Always lower"});
        pl.setSelectedIndex(o.placement.ordinal());
        JCheckBox leg = new JCheckBox("Legend", o.legend), cap = new JCheckBox("Methods caption", o.caption),
            th = new JCheckBox("Theoretical DARIS line", o.showTheoreticalDaris), hi = new JCheckBox("Historical-rate line (when target not reached)", o.showHistoricalDaris);
        JTextField xm = new JTextField(String.valueOf(o.xmaxMult), 6), ls = new JTextField(String.valueOf(o.darisLabelSize), 6),
            cs = new JTextField(String.valueOf(o.captionSize), 6);
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("DARIS label placement")); p.add(pl);
        p.add(leg); p.add(cap); p.add(th); p.add(hi);
        p.add(new JLabel("x-axis margin multiplier")); p.add(xm);
        p.add(new JLabel("Label font size")); p.add(ls);
        p.add(new JLabel("Caption font size")); p.add(cs);
        if (JOptionPane.showConfirmDialog(this, p, "Chart options", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            TsaPlot.Options n = new TsaPlot.Options();
            n.placement = TsaPlot.Placement.values()[pl.getSelectedIndex()];
            n.legend = leg.isSelected(); n.caption = cap.isSelected();
            n.showTheoreticalDaris = th.isSelected(); n.showHistoricalDaris = hi.isSelected();
            n.xmaxMult = Double.parseDouble(xm.getText().trim());
            double s = Double.parseDouble(ls.getText().trim());
            n.darisLabelSize = s; n.infoLabelSize = s; n.participantsLabelSize = s;
            n.captionSize = Double.parseDouble(cs.getText().trim());
            if (n.xmaxMult <= 0) throw new NumberFormatException();
            plot.setOptions(n);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter valid positive numbers.", "Chart options", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean needResult() {
        if (result == null) { JOptionPane.showMessageDialog(this, "Run the analysis first.", "Nothing to save", JOptionPane.INFORMATION_MESSAGE); return false; }
        return true;
    }

    private File choose(String name, String ext) {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setSelectedFile(new File(lastDir, name));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(ext)) f = new File(f.getParentFile(), f.getName() + ext);
        lastDir = f.getParentFile();
        return f;
    }

    private void savePng() {
        if (!needResult()) return;
        String[] dpis = {"150", "300", "600", "1200"};
        Object sel = JOptionPane.showInputDialog(this,
            "Resolution (dpi) of the 11 x 7.5 inch chart:\n"
            + "300 dpi = 3,300 x 2,250 px;  600 dpi = 6,600 x 4,500 px;\n"
            + "1200 dpi = 13,200 x 9,000 px (large file, takes several seconds).",
            "Save chart", JOptionPane.QUESTION_MESSAGE, null, dpis, "300");
        if (sel == null) return;
        final File f = choose("tsa_plot.png", ".png");
        if (f == null) return;
        final int dpi = Integer.parseInt((String) sel);
        final TsaCor.Result res = result;
        final TsaPlot.Options opt = plot.getOptions();
        status.setText("Saving the chart at " + dpi + " dpi...");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                TsaPlot.writePng(res, opt, dpi / 100.0, f);
                return null;
            }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                    status.setText("Saved " + f + " (" + dpi + " dpi)");
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    status.setText("Saving the chart failed.");
                    JOptionPane.showMessageDialog(MainPanel.this, String.valueOf(t.getMessage()), "Cannot save the chart", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void saveCsv(String what) {
        if (!needResult()) return;
        File f = choose("tsa_" + what + ".csv", ".csv");
        if (f == null) return;
        try {
            switch (what) {
                case "summary": Export.summaryCsv(result, f); break;
                case "cumulative": Export.cumulativeCsv(result, f); break;
                default: Export.boundaryCsv(result, f);
            }
            status.setText("Saved " + f);
        } catch (IOException e) { error("Cannot save the file", e); }
    }

    private void saveReport() {
        if (!needResult()) return;
        File f = choose("tsa_report.txt", ".txt");
        if (f == null) return;
        try {
            StringBuilder sb = new StringBuilder(result.log).append("\n").append(TsaCor.printText(result)).append("\n").append(TsaCor.summaryText(result));
            if (!result.warnings.isEmpty()) { sb.append("\nWarnings:\n"); for (String s : result.warnings) sb.append(" - ").append(s).append("\n"); }
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            status.setText("Saved " + f);
        } catch (IOException e) { error("Cannot save the report", e); }
    }

    private void error(String title, Exception e) {
        JOptionPane.showMessageDialog(this, e.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private void about() {
        JOptionPane.showMessageDialog(this,
            "tsacor-java " + TsaCor.VERSION + "\nTrial Sequential Analysis for meta-analyses of Pearson / Spearman correlations (Fisher z).\n\n"
            + "Java port of the R package 'tsacor' (Tarak Dhaouadi), sister of 'tsahr'. The boundary engine is a port of the\n"
            + "R package 'RTSA' (Anne Lyngholm Soerensen, Markus Harboe Olsen, Theis Lange, Christian Gluud), itself the R\n"
            + "version of the Copenhagen Trial Unit's TSA software (https://ctu.dk/tools). Methodology: Wetterslev et al.\n"
            + "(2009), BMC Med Res Methodol 9:86.\n\n"
            + "Licence: GNU General Public License, version 2 or later (see LICENSE.md and COPYRIGHTS.md).\n"
            + "Dedicated to research use; no warranty. If you use it for boundary computations, please also cite RTSA and TSA.",
            "About", JOptionPane.INFORMATION_MESSAGE);
    }
}
