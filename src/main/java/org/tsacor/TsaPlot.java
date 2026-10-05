/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 *
 * Java2D rendering of the TSA chart, following plot.tsa_cor() of the R package
 * 'tsacor' (cumulative Z-curve, alpha/beta spending boundaries, conventional
 * boundary, theoretical / observed / historical-rate DARIS reference lines).
 *
 * Label placement (differs from tsacor 0.1.0, which always put the DARIS labels
 * in the upper part of the plot):
 *   - Z-curve positive (final cumulative Z > 0): the four DARIS labels go to
 *     the LOWER part of the plot and the "Participants accrued" label sits just
 *     above the Z-curve;
 *   - Z-curve negative: the four DARIS labels stay in the UPPER part and the
 *     "Participants accrued" label stays at the bottom.
 */
package org.tsacor;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TsaPlot {
    private TsaPlot() {}

    public enum Placement { AUTO, UPPER, LOWER }

    public static final Color FIREBRICK = new Color(178, 34, 34), BLUE = new Color(0, 0, 255),
        DARKGREEN = new Color(0, 100, 0), BLACK = Color.BLACK, STEELBLUE4 = new Color(54, 100, 139),
        DARKORANGE3 = new Color(205, 102, 0), GREY35 = new Color(89, 89, 89), PURPLE4 = new Color(85, 26, 139),
        GREY60 = new Color(153, 153, 153);

    public static final class Options {
        public boolean legend = true, caption = true, captionItalic = true;
        public double captionSize = 8;
        public boolean showTheoreticalDaris = true, showHistoricalDaris = true;
        public double darisLabelSize = 3.2, infoLabelSize = 3.2, participantsLabelSize = 3.2;
        public double endpointLabelSize = Double.NaN, historicalLabelSize = Double.NaN;   // NaN: R defaults
        public double darisLabelX = Double.NaN, darisLabelY = Double.NaN;
        public double infoLabelX = Double.NaN, infoLabelY = Double.NaN;
        public double participantsLabelX = Double.NaN, participantsLabelY = Double.NaN;
        public double endpointLabelX = Double.NaN, endpointLabelY = Double.NaN;
        public double historicalLabelX = Double.NaN, historicalLabelY = Double.NaN;
        public double xmaxMult = 1.15;
        public double yLimit = Double.NaN;   // NaN: automatic, as in R
        public Color alphaCol = FIREBRICK, betaCol = BLUE, naiveCol = DARKGREEN, zCol = BLACK;
        public Placement placement = Placement.AUTO;
    }

    /** Design resolution (logical pixels): 11 x 7.5 inches at 100 px/inch, like the R example in the README. */
    public static final int LOGICAL_W = 1100, LOGICAL_H = 750;
    static final double PT = 100.0 / 72.0;          // logical pixels per typographic point
    static final double MM = 2.845276;               // ggplot2 text size (mm) -> points

    /** True when the label block goes to the lower part of the plot for this result/options. */
    public static boolean lowerLabels(TsaCor.Result x, Options o) {
        if (o.placement == Placement.UPPER) return false;
        if (o.placement == Placement.LOWER) return true;
        double z = x.lastZ();
        return z > 0;
    }

    public static BufferedImage render(TsaCor.Result x, Options o, double scale) {
        int w = (int) Math.round(LOGICAL_W * scale), h = (int) Math.round(LOGICAL_H * scale);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.scale(scale, scale);
        draw(g, LOGICAL_W, LOGICAL_H, x, o);
        g.dispose();
        return img;
    }

    private static final int BAND_ROWS = 128;

    /**
     * Writes the chart as an RGB PNG at the given scale (dpi / 100) WITHOUT ever holding the full bitmap in
     * memory: the chart is drawn band by band and each band is deflated straight into the file. This is
     * what makes 600 and 1200 dpi exports (13,200 x 9,000 pixels) possible on a default Java heap. The
     * resolution is recorded in the file (pHYs chunk).
     */
    public static void writePng(TsaCor.Result x, Options o, double scale, File file) throws IOException {
        final int w = (int) Math.round(LOGICAL_W * scale), h = (int) Math.round(LOGICAL_H * scale);
        try (java.io.OutputStream raw = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file), 1 << 16)) {
            raw.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            java.io.ByteArrayOutputStream hdr = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(hdr);
            d.writeInt(w); d.writeInt(h); d.writeByte(8); d.writeByte(2); d.writeByte(0); d.writeByte(0); d.writeByte(0);   // 8-bit RGB
            chunk(raw, "IHDR", hdr.toByteArray(), hdr.size());
            int ppm = (int) Math.round(scale * 100.0 / 0.0254);
            hdr.reset();
            d.writeInt(ppm); d.writeInt(ppm); d.writeByte(1);
            chunk(raw, "pHYs", hdr.toByteArray(), hdr.size());

            java.util.zip.Deflater def = new java.util.zip.Deflater(6);
            byte[] out = new byte[1 << 16];
            byte[] line = new byte[1 + 3 * w], prev = new byte[3 * w], cur = new byte[3 * w];
            BufferedImage band = new BufferedImage(w, BAND_ROWS, BufferedImage.TYPE_3BYTE_BGR);
            byte[] px = ((java.awt.image.DataBufferByte) band.getRaster().getDataBuffer()).getData();
            for (int y0 = 0; y0 < h; y0 += BAND_ROWS) {
                int rows = Math.min(BAND_ROWS, h - y0);
                Graphics2D g = band.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, BAND_ROWS);
                g.translate(0, -y0);
                g.scale(scale, scale);
                draw(g, LOGICAL_W, LOGICAL_H, x, o);
                g.dispose();
                for (int r = 0; r < rows; r++) {
                    int base = r * 3 * w;
                    for (int i = 0; i < w; i++) {            // BGR -> RGB
                        cur[3 * i] = px[base + 3 * i + 2];
                        cur[3 * i + 1] = px[base + 3 * i + 1];
                        cur[3 * i + 2] = px[base + 3 * i];
                    }
                    line[0] = 2;                              // PNG filter "Up"
                    for (int i = 0; i < cur.length; i++) line[1 + i] = (byte) (cur[i] - prev[i]);
                    byte[] t = prev; prev = cur; cur = t;
                    def.setInput(line, 0, line.length);
                    while (!def.needsInput()) {
                        int n = def.deflate(out);
                        if (n > 0) chunk(raw, "IDAT", out, n);
                    }
                }
            }
            def.finish();
            while (!def.finished()) {
                int n = def.deflate(out);
                if (n > 0) chunk(raw, "IDAT", out, n);
            }
            def.end();
            chunk(raw, "IEND", new byte[0], 0);
        }
    }

    private static void chunk(java.io.OutputStream os, String type, byte[] data, int len) throws IOException {
        java.io.DataOutputStream d = new java.io.DataOutputStream(os);
        d.writeInt(len);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        d.write(t);
        d.write(data, 0, len);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data, 0, len);
        d.writeInt((int) crc.getValue());
    }

    // ------------------------------------------------------------------ helpers
    private static Font font(int style, double pt) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, (float) (pt * PT));
    }

    /** Draw text; hjust 0 = left, 1 = right; vjust 0 = baseline at y (text above), 1 = top at y (text below). */
    private static Rectangle2D text(Graphics2D g, String s, double x, double y, double hjust, double vjust) {
        FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(s);
        double bx = x - hjust * w;
        double by = y + vjust * fm.getAscent();
        g.drawString(s, (float) bx, (float) by);
        return new Rectangle2D.Double(bx, by - fm.getAscent(), w, fm.getAscent() + fm.getDescent());
    }

    private static double[] niceTicks(double lo, double hi, int target) {
        double range = hi - lo;
        double raw = range / Math.max(1, target);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double res = raw / mag;
        double step = (res < 1.5 ? 1 : res < 3.5 ? 2 : res < 7.5 ? 5 : 10) * mag;
        List<Double> t = new ArrayList<>();
        for (double v = Math.ceil(lo / step - 1e-9) * step; v <= hi + 1e-9 * step; v += step) t.add(Math.abs(v) < 1e-12 * step ? 0.0 : v);
        double[] a = new double[t.size() + 1];
        for (int i = 0; i < t.size(); i++) a[i] = t.get(i);
        a[a.length - 1] = step;   // last element carries the step
        return a;
    }

    private static String tickLabel(double v, double step) {
        if (step >= 1 && Math.abs(v - Math.rint(v)) < 1e-9) return String.format(Locale.ROOT, "%,d", (long) Math.rint(v)).replace(",", "");
        int dec = (int) Math.max(0, Math.ceil(-Math.log10(step) - 1e-9));
        return String.format(Locale.ROOT, "%." + dec + "f", v);
    }

    private static Stroke stroke(double widthPx, String type) {
        float w = (float) widthPx;
        switch (type) {
            case "dashed": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4 * w, 4 * w}, 0f);
            case "dotted": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{w, 3 * w}, 0f);
            case "dotdash": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{w, 3 * w, 4 * w, 3 * w}, 0f);
            case "longdash": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7 * w, 3 * w}, 0f);
            default: return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
        }
    }

    // --------------------------------------------------------- subtitle / caption
    static String pooledSubtitle(TsaCor.Result x) {
        double pv = x.resRe.pval;
        String pTxt = Double.isNaN(pv) ? "p = NA" : pv < 0.001 ? "p < 0.001" : String.format(Locale.ROOT, "p = %.3f", pv);
        return String.format(Locale.ROOT, "Pooled %s = %.2f [95%% CI: %.2f, %.2f] | %s | Tau\u00b2 = %.4f | I\u00b2 = %.1f%%",
            x.corSym(), Math.tanh(x.resRe.b), Math.tanh(x.resRe.ciLb), Math.tanh(x.resRe.ciUb), pTxt, x.tau2, x.I2);
    }

    static String caption(TsaCor.Result x) {
        String se;
        if (x.seSource.equals("n")) {
            if (x.corType.equals("spearman")) se = "sample sizes (" + (x.spearmanVariance.equals("bonett_wright") ? "Bonett-Wright" : "Fieller") + " variance)";
            else se = "sample sizes (1/(n-3))";
        } else se = "reported confidence intervals";
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Methods: Random-effects (%s) model of Fisher's z (%s); SE(z) from %s",
            TsaCor.methodLabel(x.method), x.corLabel(), se));
        if (!x.reInference.equals("standard")) {
            lines.add(x.reInference.equals("hksj_adhoc")
                ? "Random-effects inference: HKSJ with ad hoc correction (variance scale max(1, q); t distribution, k-1 df); Z = normal-equivalent of the t-statistic"
                : "Random-effects inference: HKSJ (Hartung-Knapp-Sidik-Jonkman; t distribution, k-1 df); Z = normal-equivalent of the t-statistic");
        }
        lines.add("Alpha spending: O'Brien-Fleming-type (asOF); Non-binding futility: RTSA-derived recursive-integration engine, O'Brien-Fleming-type beta-spending (bsOF)");
        lines.add(String.format(Locale.ROOT, "alpha = %.0f%% (two-sided), power = %.0f%% | Diversity D\u00b2 = %.0f%%, Adjustment factor = %.2f",
            x.alphaTwoSided * 100, x.power * 100, x.D2 * 100, x.AF));
        if (x.analysisRoute())
            lines.add(String.format(Locale.ROOT, "Boundary route: RTSA analysis (formal endpoint = %.3f x DARIS information)", x.routeEndpoint));
        boolean showProj = x.analysisRoute() ? !x.finalReached : !x.darisReached;
        if (showProj) {
            String target = x.analysisRoute() ? "analysis-route endpoint" : "DARIS";
            List<String> parts = new ArrayList<>();
            if (!Double.isNaN(x.additionalParticipantsTheoretical))
                parts.add("Theoretical additional participants to " + target + ": " + TsaCor.bigMark(x.additionalParticipantsTheoretical));
            if (!Double.isNaN(x.additionalParticipantsEstimated))
                parts.add("Estimated additional participants to " + target + " (historical rate): ~" + TsaCor.bigMark(x.additionalParticipantsEstimated));
            if (!parts.isEmpty()) lines.add(String.join(", ", parts));
            if (x.nAdditionalStudies >= 0) lines.add("Estimated additional studies required: " + x.nAdditionalStudies);
        }
        return String.join("\n", lines);
    }

    // ================================================================== main draw
    public static void draw(Graphics2D g, double W, double H, TsaCor.Result x, Options o) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        final int nrow = x.nStudies;
        final boolean analysisRoute = x.analysisRoute();
        final boolean showEndpointMarker = analysisRoute && x.finalReached && !Double.isNaN(x.routeEndpointN);
        final double endpointTheo = x.routeEndpointParticipantsTheoretical;
        final boolean showEndpointTheoretical = analysisRoute && !showEndpointMarker && TsaCor.finite(endpointTheo)
            && !(Math.abs(x.routeEndpoint - 1.0) < 1e-8 && o.showTheoreticalDaris);
        final boolean showInfoMarker = x.darisReached && !Double.isNaN(x.darisInfoThresholdN);
        double histTarget = x.targetParticipantsHistoricalRate;
        boolean histReached = analysisRoute ? x.finalReached : x.darisReached;
        final boolean showHist = o.showHistoricalDaris && !histReached && TsaCor.finite(histTarget);

        // y range (as in plot.tsa_cor)
        double yAbsMax = 0;
        for (double v : x.cumZ) if (!Double.isNaN(v)) yAbsMax = Math.max(yAbsMax, Math.abs(v));
        for (int i = 0; i < x.btUpper.length; i++) {
            if (TsaCor.finite(x.btUpper[i])) yAbsMax = Math.max(yAbsMax, x.btUpper[i]);
            if (TsaCor.finite(x.btLower[i])) yAbsMax = Math.max(yAbsMax, Math.abs(x.btLower[i]));
        }
        double yLim = TsaCor.finite(o.yLimit) && o.yLimit > 0 ? o.yLimit : yAbsMax * 1.15;

        double xmax = Stats.sum(new double[0]);
        double m = 0;
        for (double v : x.cumN) m = Math.max(m, v);
        if (o.showTheoreticalDaris) m = Math.max(m, x.darisParticipants);
        if (showInfoMarker) m = Math.max(m, x.darisInfoThresholdN);
        if (showEndpointMarker) m = Math.max(m, x.routeEndpointN);
        if (showEndpointTheoretical) m = Math.max(m, endpointTheo);
        if (showHist) m = Math.max(m, histTarget);
        for (double v : x.btCumN) if (TsaCor.finite(v)) m = Math.max(m, v);
        xmax = m * o.xmaxMult;

        // ---- layout --------------------------------------------------------------------------------
        String[] capLines = o.caption ? caption(x).split("\n") : new String[0];
        Font titleF = font(Font.PLAIN, 14.4), subF = font(Font.PLAIN, 11), axisF = font(Font.PLAIN, 9.6),
            axisTitleF = font(Font.PLAIN, 12), legendF = font(Font.PLAIN, 9.6),
            capF = font(o.captionItalic ? Font.ITALIC : Font.PLAIN, o.captionSize);
        double left = 72, right = 36, top = 14;
        FontMetrics fmT = g.getFontMetrics(titleF), fmS = g.getFontMetrics(subF), fmA = g.getFontMetrics(axisF),
            fmAT = g.getFontMetrics(axisTitleF), fmL = g.getFontMetrics(legendF), fmC = g.getFontMetrics(capF);
        double yTitle = top + fmT.getAscent();
        double yS1 = yTitle + fmT.getDescent() + 7 + fmS.getAscent();
        double yS2 = yS1 + fmS.getHeight() * 1.05;
        double panelTop = yS2 + fmS.getDescent() + 10;
        double capH = capLines.length == 0 ? 0 : capLines.length * fmC.getHeight() * 1.05 + 10;
        double legendH = o.legend ? 26 : 0;
        double bottomBlock = fmA.getHeight() + 6 + fmAT.getHeight() + 6 + legendH + capH + 8;
        double panelBottom = H - bottomBlock;
        double pw = W - left - right, ph = panelBottom - panelTop;
        final double X0 = left, Y0 = panelTop;
        final double xm = xmax, yl = yLim;
        java.util.function.DoubleUnaryOperator px = v -> X0 + v / xm * pw;
        java.util.function.DoubleUnaryOperator py = v -> Y0 + (yl - v) / (2 * yl) * ph;

        // ---- panel: grid ---------------------------------------------------------------------------------
        double[] xt = niceTicks(0, xmax, 6), yt = niceTicks(-yLim, yLim, 6);
        double xStep = xt[xt.length - 1], yStep = yt[yt.length - 1];
        Shape oldClip = g.getClip();
        g.setClip(new Rectangle2D.Double(X0, Y0, pw, ph));
        g.setColor(new Color(235, 235, 235));
        g.setStroke(new BasicStroke((float) (0.25 * PT * 0.75)));
        for (double v = Math.floor(0 / (xStep / 2)) * (xStep / 2); v <= xmax; v += xStep / 2)
            g.draw(new Line2D.Double(px.applyAsDouble(v), Y0, px.applyAsDouble(v), Y0 + ph));
        for (double v = Math.ceil(-yLim / (yStep / 2)) * (yStep / 2); v <= yLim; v += yStep / 2)
            g.draw(new Line2D.Double(X0, py.applyAsDouble(v), X0 + pw, py.applyAsDouble(v)));
        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke((float) (0.5 * PT * 0.75)));
        for (int i = 0; i < xt.length - 1; i++) g.draw(new Line2D.Double(px.applyAsDouble(xt[i]), Y0, px.applyAsDouble(xt[i]), Y0 + ph));
        for (int i = 0; i < yt.length - 1; i++) g.draw(new Line2D.Double(X0, py.applyAsDouble(yt[i]), X0 + pw, py.applyAsDouble(yt[i])));

        // zero line
        g.setColor(GREY60);
        g.setStroke(new BasicStroke((float) (0.3 * PT * 0.75)));
        g.draw(new Line2D.Double(X0, py.applyAsDouble(0), X0 + pw, py.applyAsDouble(0)));

        double lw = 0.8 * PT * 0.75 * 2.13;   // ggplot linewidth 0.8 mm
        double lwThin = 0.5 * PT * 0.75 * 2.13;
        // naive boundaries
        g.setColor(o.naiveCol); g.setStroke(stroke(lwThin, "dashed"));
        g.draw(new Line2D.Double(px.applyAsDouble(0), py.applyAsDouble(x.zAlpha), px.applyAsDouble(xmax), py.applyAsDouble(x.zAlpha)));
        g.draw(new Line2D.Double(px.applyAsDouble(0), py.applyAsDouble(-x.zAlpha), px.applyAsDouble(xmax), py.applyAsDouble(-x.zAlpha)));
        // alpha / beta boundaries (alpha clipped to +-yLim as in R)
        drawSeries(g, x.btCumN, clip(x.btUpper, yLim), px, py, o.alphaCol, stroke(lw, "solid"));
        drawSeries(g, x.btCumN, clipLow(x.btLower, yLim), px, py, o.alphaCol, stroke(lw, "solid"));
        drawSeries(g, x.btCumN, x.btFutUpper, px, py, o.betaCol, stroke(lw, "dashed"));
        drawSeries(g, x.btCumN, x.btFutLower, px, py, o.betaCol, stroke(lw, "dashed"));
        // Z-curve
        drawSeries(g, x.cumN, x.cumZ, px, py, o.zCol, stroke(lw, "solid"));
        g.setColor(o.zCol);
        for (int i = 0; i < nrow; i++) if (!Double.isNaN(x.cumZ[i])) {
            double r = 2.0 * PT * 0.75 * 1.9 / 2 + 1.2;
            g.fill(new Ellipse2D.Double(px.applyAsDouble(x.cumN[i]) - r, py.applyAsDouble(x.cumZ[i]) - r, 2 * r, 2 * r));
        }

        // ---- reference lines ---------------------------------------------------------------------------
        g.setStroke(stroke(0.6 * PT * 0.75 * 2.13, "dotted"));
        if (o.showTheoreticalDaris) { g.setColor(BLACK); vline(g, px.applyAsDouble(x.darisParticipants), Y0, ph); }
        if (showHist) { g.setColor(DARKORANGE3); g.setStroke(stroke(0.6 * PT * 0.75 * 2.13, "dotdash")); vline(g, px.applyAsDouble(histTarget), Y0, ph); }
        if (showInfoMarker) { g.setColor(GREY35); g.setStroke(stroke(0.6 * PT * 0.75 * 2.13, "dashed")); vline(g, px.applyAsDouble(x.darisInfoThresholdN), Y0, ph); }
        if (showEndpointMarker) { g.setColor(PURPLE4); g.setStroke(stroke(0.6 * PT * 0.75 * 2.13, "longdash")); vline(g, px.applyAsDouble(x.routeEndpointN), Y0, ph); }
        if (showEndpointTheoretical) { g.setColor(PURPLE4); g.setStroke(stroke(0.6 * PT * 0.75 * 2.13, "longdash")); vline(g, px.applyAsDouble(endpointTheo), Y0, ph); }
        g.setClip(oldClip);

        // ---- labels (DARIS block placement depends on the sign of the Z-curve) ----------------------------
        boolean lower = lowerLabels(x, o);
        double sgn = lower ? -1.0 : 1.0;
        double vj = lower ? 1.0 : 0.0;     // top-aligned when the block sits in the lower part
        double canvasRight = W - 4;
        double endpointSize = Double.isNaN(o.endpointLabelSize) ? o.infoLabelSize : o.endpointLabelSize;
        double histSize = Double.isNaN(o.historicalLabelSize) ? o.darisLabelSize : o.historicalLabelSize;

        if (o.showTheoreticalDaris) {
            g.setFont(font(Font.PLAIN, o.darisLabelSize * MM)); g.setColor(BLACK);
            lineLabel(g, "Theoretical DARIS participant-equivalent ~ " + (long) Math.ceil(x.darisParticipants),
                px.applyAsDouble(Double.isNaN(o.darisLabelX) ? x.darisParticipants : o.darisLabelX),
                py.applyAsDouble(Double.isNaN(o.darisLabelY) ? sgn * yLim * 0.92 : o.darisLabelY), vj, canvasRight);
        }
        if (showHist) {
            String t = analysisRoute
                ? "Historical information/participant-rate projection ~ " + (long) Math.ceil(histTarget) + " participants"
                : "DARIS (historical rate) ~ " + (long) Math.ceil(histTarget) + " participants (projected)";
            g.setFont(font(Font.PLAIN, histSize * MM)); g.setColor(DARKORANGE3);
            lineLabel(g, t, px.applyAsDouble(Double.isNaN(o.historicalLabelX) ? histTarget : o.historicalLabelX),
                py.applyAsDouble(Double.isNaN(o.historicalLabelY) ? sgn * yLim * (analysisRoute ? 0.41 : 0.75) : o.historicalLabelY), vj, canvasRight);
        }
        if (showInfoMarker) {
            g.setFont(font(Font.PLAIN, o.infoLabelSize * MM)); g.setColor(GREY35);
            lineLabel(g, "DARIS information reached ~ " + (long) Math.ceil(x.darisInfoThresholdN) + " participants (est.)",
                px.applyAsDouble(Double.isNaN(o.infoLabelX) ? x.darisInfoThresholdN : o.infoLabelX),
                py.applyAsDouble(Double.isNaN(o.infoLabelY) ? sgn * yLim * 0.75 : o.infoLabelY), vj, canvasRight);
        }
        if (showEndpointMarker || showEndpointTheoretical) {
            double xe = showEndpointMarker ? x.routeEndpointN : endpointTheo;
            String t = showEndpointMarker
                ? String.format(Locale.ROOT, "Analysis-route endpoint (%.3f x DARIS) reached ~ %d participants (est.)", x.routeEndpoint, (long) Math.ceil(x.routeEndpointN))
                : String.format(Locale.ROOT, "Analysis-route endpoint (%.3f x DARIS) not yet reached; theoretical ~ %d participants", x.routeEndpoint, (long) Math.ceil(endpointTheo));
            g.setFont(font(Font.PLAIN, endpointSize * MM)); g.setColor(PURPLE4);
            lineLabel(g, t, px.applyAsDouble(Double.isNaN(o.endpointLabelX) ? xe : o.endpointLabelX),
                py.applyAsDouble(Double.isNaN(o.endpointLabelY) ? sgn * yLim * 0.58 : o.endpointLabelY), vj, canvasRight);
        }

        // participants accrued
        {
            g.setFont(font(Font.PLAIN, o.participantsLabelSize * MM)); g.setColor(STEELBLUE4);
            String t = "Participants accrued = " + (long) Math.rint(x.participantsAccrued);
            double xr = Double.isNaN(o.participantsLabelX) ? maxOf(x.cumN) : o.participantsLabelX;
            double yBase;   // data coordinate of the text baseline
            if (!Double.isNaN(o.participantsLabelY)) {
                yBase = o.participantsLabelY;
            } else if (lower) {
                // just above the Z-curve around the label's horizontal extent
                FontMetrics fm = g.getFontMetrics();
                double wData = fm.stringWidth(t) / pw * xmax;
                double zTop = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < nrow; i++) {
                    if (!Double.isNaN(x.cumZ[i]) && x.cumN[i] >= xr - wData - 0.01 * xmax && x.cumN[i] <= xr + 0.01 * xmax) zTop = Math.max(zTop, x.cumZ[i]);
                }
                if (Double.isInfinite(zTop)) zTop = x.lastZ();
                yBase = Math.min(zTop + 0.035 * yLim, 0.95 * yLim);
            } else {
                yBase = -yLim * 0.92;
            }
            text(g, t, px.applyAsDouble(xr), py.applyAsDouble(yBase), 1, 0);
        }

        // ---- title, subtitle, axes, legend, caption --------------------------------------------------------
        g.setColor(Color.BLACK);
        g.setFont(titleF);
        text(g, String.format(Locale.ROOT, "Trial Sequential Analysis of Correlations (%s, Fisher z)", x.corLabel()), left - 62, yTitle, 0, 0);
        g.setFont(subF);
        g.setColor(new Color(40, 40, 40));
        text(g, String.format(Locale.ROOT, "Random-effects model | Diversity D\u00b2 = %.0f%% | Anticipated %s = %.2f | alpha=%.0f%%, power=%.0f%%",
            x.D2 * 100, x.corSym(), x.rAnticipated, x.alphaTwoSided * 100, x.power * 100), left - 62, yS1, 0, 0);
        text(g, pooledSubtitle(x), left - 62, yS2, 0, 0);

        g.setFont(axisF); g.setColor(new Color(77, 77, 77));
        for (int i = 0; i < xt.length - 1; i++) {
            String s = tickLabel(xt[i], xStep);
            text(g, s, px.applyAsDouble(xt[i]), panelBottom + 4 + fmA.getAscent(), 0.5, 0);
        }
        for (int i = 0; i < yt.length - 1; i++) {
            String s = tickLabel(yt[i], yStep);
            text(g, s, left - 6, py.applyAsDouble(yt[i]) + fmA.getAscent() * 0.36, 1, 0);
        }
        g.setFont(axisTitleF); g.setColor(Color.BLACK);
        double yAxisTitle = panelBottom + 4 + fmA.getHeight() + 6 + fmAT.getAscent();
        text(g, "Cumulative number of participants", left + pw / 2, yAxisTitle, 0.5, 0);
        Graphics2D gr = (Graphics2D) g.create();
        gr.setFont(axisTitleF);
        gr.rotate(-Math.PI / 2, 16, panelTop + ph / 2);
        text(gr, "Cumulative Z-score", 16, panelTop + ph / 2 + fmAT.getAscent() / 2.0, 0.5, 0);
        gr.dispose();

        double yNext = yAxisTitle + fmAT.getDescent() + 8;
        if (o.legend) {
            String[] names = {"Alpha boundaries", "Non-binding futility boundaries", "Naive boundaries", "Z scores"};
            Color[] cols = {o.alphaCol, o.betaCol, o.naiveCol, o.zCol};
            String[] types = {"solid", "dashed", "dashed", "solid"};
            g.setFont(legendF);
            double total = 0;
            for (String n : names) total += 30 + 6 + fmL.stringWidth(n) + 18;
            double lx = left + pw / 2 - total / 2 + 9, ly = yNext + 10;
            for (int i = 0; i < names.length; i++) {
                g.setColor(cols[i]); g.setStroke(stroke(lw, types[i]));
                g.draw(new Line2D.Double(lx, ly, lx + 30, ly));
                if (i == 3) { double r = 3.2; g.fill(new Ellipse2D.Double(lx + 15 - r, ly - r, 2 * r, 2 * r)); }
                g.setColor(new Color(20, 20, 20));
                text(g, names[i], lx + 36, ly + fmL.getAscent() * 0.36, 0, 0);
                lx += 30 + 6 + fmL.stringWidth(names[i]) + 18;
            }
            yNext += legendH;
        }
        if (capLines.length > 0) {
            g.setFont(capF); g.setColor(new Color(40, 40, 40));
            double cy = yNext + 6 + fmC.getAscent();
            for (String line : capLines) { text(g, line, left - 62, cy, 0, 0); cy += fmC.getHeight() * 1.05; }
        }
    }

    private static double maxOf(double[] a) { double m = Double.NEGATIVE_INFINITY; for (double v : a) m = Math.max(m, v); return m; }

    private static double[] clip(double[] v, double lim) {
        double[] o = v.clone(); for (int i = 0; i < o.length; i++) if (!Double.isNaN(o[i])) o[i] = Math.min(o[i], lim); return o;
    }

    private static double[] clipLow(double[] v, double lim) {
        double[] o = v.clone(); for (int i = 0; i < o.length; i++) if (!Double.isNaN(o[i])) o[i] = Math.max(o[i], -lim); return o;
    }

    private static void vline(Graphics2D g, double xPx, double y0, double h) {
        g.draw(new Line2D.Double(xPx, y0, xPx, y0 + h));
    }

    /** Label to the right of its line (hjust = -0.05 in R); flipped to the left when it would leave the canvas. */
    private static void lineLabel(Graphics2D g, String s, double xPx, double yPx, double vjust, double canvasRight) {
        FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(s);
        double gap = 0.05 * w;
        if (xPx + gap + w > canvasRight) text(g, s, xPx - gap, yPx, 1, vjust);
        else text(g, s, xPx + gap, yPx, 0, vjust);
    }

    private static void drawSeries(Graphics2D g, double[] xs, double[] ys, java.util.function.DoubleUnaryOperator px,
                                   java.util.function.DoubleUnaryOperator py, Color c, Stroke st) {
        Path2D.Double path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            if (Double.isNaN(xs[i]) || Double.isNaN(ys[i])) continue;
            double X = px.applyAsDouble(xs[i]), Y = py.applyAsDouble(ys[i]);
            if (!started) { path.moveTo(X, Y); started = true; } else path.lineTo(X, Y);
        }
        if (!started) return;
        g.setColor(c); g.setStroke(st);
        g.draw(path);
    }
}
