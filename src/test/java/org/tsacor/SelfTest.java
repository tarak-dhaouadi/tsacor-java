/*
 * tsacor-java -- GPL (>= 2). Dependency-free test runner (no JUnit needed):
 *   run with  java -cp build/classes:build/test-classes org.tsacor.SelfTest
 * Reference values come from the R package's own tests (tests/testthat),
 * from the frozen live-RTSA 0.2.2 output (inst/extdata/rtsa_0.2.2_reference.R)
 * and from independent SciPy/NumPy computations.
 */
package org.tsacor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

public class SelfTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("FAIL: " + name); }
    }

    static void near(String name, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (!ok) System.out.println("   " + name + ": got " + got + ", want " + want + ", tol " + tol);
        check(name, ok);
    }

    static void near(String name, double[] got, double[] want, double tol) {
        check(name + " (length)", got.length == want.length);
        for (int i = 0; i < Math.min(got.length, want.length); i++) {
            if (Double.isNaN(want[i])) check(name + "[" + i + "] NaN", Double.isNaN(got[i]));
            else near(name + "[" + i + "]", got[i], want[i], tol);
        }
    }

    static DataSet example() throws Exception { return Examples.load(); }

    static TsaCor.Result fit(TsaCor.Params p) throws Exception {
        p.verbose = false;
        return TsaCor.run(example(), p);
    }

    static TsaCor.Params base() { TsaCor.Params p = new TsaCor.Params(); p.targetR = 0.10; p.orderBy = "Year"; return p; }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // ---- distributions (SciPy references, 16 significant digits) ----
        near("pnorm(1.96)", Stats.pnorm(1.96), 0.9750021048517795, 1e-15);
        near("qnorm(.975)", Stats.qnorm(0.975), 1.959963984540054, 1e-13);
        near("qnorm(.8)", Stats.qnorm(0.8), 0.8416212335729143, 1e-13);
        near("qnormUpper tail", Stats.qnormUpper(7.366808435937955e-06), 4.332633646049566, 1e-12);
        near("log t tail", Stats.logUpperT(3.2, 7), -4.888474432287252, 1e-12);
        near("qt(.975,5)", Stats.qtUpper(0.025, 5), 2.57058183563632, 1e-12);
        near("chisq upper", Stats.pchisqUpper(72.177, 19), 3.9828781311146686e-08, 1e-19);
        near("qchisq(.5,19)", Stats.qchisq(0.5, 19), 18.33765289675647, 1e-11);

        // ---- boundary engine vs frozen LIVE RTSA 0.2.2 output ----
        double alpha = 0.05, beta = 0.20;
        double[] t4 = {0.25, 0.50, 0.75, 1.00};
        List<String> w = new ArrayList<>();
        RtsaEngine.Bounds des = RtsaEngine.designBounds(t4, alpha, beta, w);
        near("design root", des.root, 1.133241903483384, 1e-7);
        near("design alpha", des.alphaUbound, new double[]{4.332633646049564, 2.963130728293607, 2.359044407368292, 2.014090377368289}, 1e-10);
        near("design beta", des.betaUbound, new double[]{Double.NaN, 0.6325313124459123, 1.404161788140875, 2.0140903773682739}, 1e-7);
        near("design spend", des.betaSpent, new double[]{0, 0.06992632672050636, 0.13892441763937158, 0.19999999999999996}, 1e-9);
        check("design rm_bs == 1", des.rmBs == 1);
        RtsaEngine.Bounds an = RtsaEngine.analysisBounds(t4, 1.133241903483384, alpha, beta, w);
        near("analysis alpha", an.alphaUbound, new double[]{4.332633646049564, 2.963130674316038, 2.359044357300598, 2.014090359906189}, 1e-12);
        near("analysis beta", an.betaUbound, new double[]{Double.NaN, 0.3709071738839083, 1.1440542785920043, 1.7200465809397056, 2.1228442649443582}, 1e-9);
        near("analysis spend", an.betaSpent, new double[]{0, 0.05368666786513043, 0.11518429065555469, 0.17248550115092032, 0.19999999999999996}, 1e-9);
        check("no warnings on the reference schedule", w.isEmpty());

        // ---- statistics layer: reference values of tests/testthat/test-tsa_cor.R ----
        TsaCor.Result r = fit(base());
        near("pooled r", r.pooledR, 0.4997, 1e-3);
        near("pooled r lb", r.pooledLb, 0.4572, 1e-3);
        near("pooled r ub", r.pooledUb, 0.5398, 1e-3);
        near("Q", r.Q, 72.177, 1e-2);
        near("I2", r.I2, 73.68, 0.2);
        near("D2", r.D2, 0.7462, 1e-3);
        near("AF", r.AF, 3.9406, 1e-3);
        double zA = Stats.qnorm(0.975), zB = Stats.qnorm(0.80);
        double infoReq = (zA + zB) * (zA + zB) / (TsaCor.atanh(0.10) * TsaCor.atanh(0.10));
        near("info required", r.infoRequired, infoReq, 1e-8);
        near("RIS participants", r.risParticipants, infoReq + 3, 1e-8);
        near("DARIS info", r.darisInfo, infoReq * r.AF, 1e-6);
        near("DARIS participants", r.darisParticipants, infoReq * r.AF + 3, 1e-6);
        check("example has 20 studies / 5355 participants", r.nStudies == 20 && r.participantsAccrued == 5355);
        check("DARIS reached mid-series", r.darisReached && r.infoFraction[0] < 1 && r.infoFraction[19] > 1);
        check("design route endpoint is 1", r.routeUsed.equals("design") && r.routeEndpoint == 1.0 && r.btSynthetic[r.btSynthetic.length - 1]);
        near("tau2 estimators agree with SciPy: DL", Rma.fit(r.zFisher, r.seZ, "DL").tau2, 0.01131050, 1e-8);
        near("REML", Rma.fit(r.zFisher, r.seZ, "REML").tau2, 0.01069172, 1e-7);
        near("ML", Rma.fit(r.zFisher, r.seZ, "ML").tau2, 0.00995726, 1e-7);
        near("PM", Rma.fit(r.zFisher, r.seZ, "PM").tau2, 0.01045644, 1e-7);
        near("EB", Rma.fit(r.zFisher, r.seZ, "EB").tau2, 0.01068135, 1e-7);
        near("HE", Rma.fit(r.zFisher, r.seZ, "HE").tau2, 0.01021901, 1e-8);
        near("SJ", Rma.fit(r.zFisher, r.seZ, "SJ").tau2, 0.01131926, 1e-8);
        near("cumulative Z at look 5", r.cumZ[4], 6.6844337839921115, 1e-8);
        near("cumulative Z at look 12", r.cumZ[11], 13.62232120744217, 1e-8);

        // ---- analysis route ----
        TsaCor.Params pa = base(); pa.boundaryRoute = "analysis";
        TsaCor.Result ra = fit(pa);
        check("analysis route used, endpoint >= 1", ra.routeUsed.equals("analysis") && ra.routeEndpoint >= 1.0);
        near("route endpoint info", ra.routeEndpointInfo, ra.routeEndpoint * ra.darisInfo, 1e-6);

        // ---- small target -> projection ----
        TsaCor.Params ps = base(); ps.targetR = 0.05;
        TsaCor.Result rs = fit(ps);
        check("small target not reached", !rs.darisReached && !rs.finalReached && rs.finalCrossedEfficacy == null);
        check("projection finite", TsaCor.finite(rs.additionalParticipantsEstimated) && TsaCor.finite(rs.additionalParticipantsTheoretical) && rs.nAdditionalStudies >= 1);
        check("small-target warning raised", rs.warnings.stream().anyMatch(s -> s.contains("close to the null value")));

        // ---- HKSJ (SciPy references) ----
        TsaCor.Params ph = base(); ph.reInference = "knha";
        TsaCor.Result rh = fit(ph);
        check("knha alias", rh.reInference.equals("hksj"));
        near("HKSJ pooled z unchanged", rh.resRe.b, r.resRe.b, 1e-10);
        near("HKSJ se", rh.resRe.se, 0.02732248, 1e-7);
        near("HKSJ ci lb (z)", rh.resRe.ciLb, 0.49166826, 1e-6);
        near("HKSJ ci ub (z)", rh.resRe.ciUb, 0.60604149, 1e-6);
        check("HKSJ: Z undefined at look 1", Double.isNaN(rh.cumZ[0]) && !Double.isNaN(rh.cumZ[1]));
        near("HKSJ normal-equivalent Z at look 5", rh.cumZ[4], 3.01806378, 1e-6);
        near("HKSJ leaves DARIS unchanged", rh.darisInfo, r.darisInfo, 1e-9);

        // ---- Spearman variance factor ----
        TsaCor.Params pf = base(); pf.corType = "rho"; pf.seSource = "n";
        TsaCor.Result rf = fit(pf);
        check("spearman alias", rf.corType.equals("spearman"));
        near("Fieller c", rf.varFactor, 1.06, 0);
        near("same required info as Pearson", rf.infoRequired, r.infoRequired, 1e-8);
        check("c enters the participant equivalent", rf.risParticipants > r.risParticipants);
        TsaCor.Params pb = pf.copy(); pb.spearmanVariance = "bonett_wright";
        near("Bonett-Wright c", fit(pb).varFactor, 1 + 0.1 * 0.1 / 2, 1e-12);

        // ---- ordering ----
        DataSet ds = example();
        DataSet rev = new DataSet();
        rev.names.addAll(ds.names);
        for (int i = ds.nrow() - 1; i >= 0; i--) rev.rows.add(ds.rows.get(i));
        TsaCor.Params po = base(); po.verbose = false;
        TsaCor.Result r1 = TsaCor.run(rev, po);
        po.orderBy = null;
        TsaCor.Result r2 = TsaCor.run(rev, po);
        near("same final estimate regardless of order", r1.cumEstimate[19], r2.cumEstimate[19], 1e-8);
        check("order changes the path", Math.abs(r1.cumZ[3] - r2.cumZ[3]) > 1e-6);
        check("sorted ascending by Year", r1.study[0].equals("Study_01"));

        // ---- validation errors ----
        expectError("target_r = 0", () -> { TsaCor.Params p = base(); p.targetR = 0; fit(p); }, "cannot equal 0");
        expectError("target_r = 1", () -> { TsaCor.Params p = base(); p.targetR = 1; fit(p); }, "strictly between -1 and 1");
        expectError("bad method", () -> { TsaCor.Params p = base(); p.method = "bogus"; fit(p); }, "method must be one of");
        expectError("GENQ", () -> { TsaCor.Params p = base(); p.method = "GENQ"; fit(p); }, "not currently supported");
        expectError("bad re_inference", () -> { TsaCor.Params p = base(); p.reInference = "bogus"; fit(p); }, "re_inference must be one of");
        expectError("alpha", () -> { TsaCor.Params p = base(); p.alphaTwoSided = 1.5; fit(p); }, "alpha_two_sided");
        expectError("missing column", () -> {
            DataSet d = example(); int c = d.col("n_subjects"); d.names.set(c, "nn"); TsaCor.run(d, base()); }, "Missing required column");
        expectError("r out of range", () -> { DataSet d = example(); d.rows.get(0)[d.col("r")] = "1.4"; TsaCor.run(d, base()); }, "strictly between -1 and 1");
        expectError("n <= 3", () -> { DataSet d = example(); d.rows.get(0)[d.col("n_subjects")] = "3"; TsaCor.run(d, base()); }, "greater than 3");
        expectError("n not whole", () -> { DataSet d = example(); d.rows.get(0)[d.col("n_subjects")] = "10.5"; TsaCor.run(d, base()); }, "whole numbers");
        expectError("lbound >= ubound", () -> { DataSet d = example(); d.rows.get(0)[d.col("lbound")] = "0.99"; TsaCor.run(d, base()); }, "lbound must be smaller");
        expectError("duplicate Study", () -> { DataSet d = example(); d.rows.get(1)[d.col("Study")] = d.rows.get(0)[d.col("Study")]; TsaCor.run(d, base()); }, "unique");
        expectError("text in r", () -> { DataSet d = example(); d.rows.get(0)[d.col("r")] = "abc"; TsaCor.run(d, base()); }, "must be numeric");
        expectError("one study", () -> { DataSet d = example(); d.rows.subList(1, d.rows.size()).clear(); TsaCor.run(d, base()); }, "at least two studies");
        TsaCor.Params pn = base(); pn.targetR = Double.NaN;
        check("target_r = NA warns about circularity", fit(pn).warnings.stream().anyMatch(s -> s.contains("circular")));

        // ---- loaders ----
        DataSet csv = DataLoader.parseDelimited("Study,r,n_subjects,lbound,ubound\nA,0.5,100,0.35,0.62\n\"B, Jr\",0.4,80,0.2,0.57\n");
        check("csv parse", csv.nrow() == 2 && csv.rows.get(1)[0].equals("B, Jr") && csv.names.size() == 5);
        DataSet semi = DataLoader.parseDelimited("Study;r;n_subjects\nA;0,5;100\n");
        check("semicolon + decimal comma", semi.rows.get(0)[1].equals("0.5"));
        DataSet x = example();
        check("xlsx example columns", x.names.equals(Arrays.asList("Study", "Year", "Ethnicity", "Age", "r", "lbound", "ubound", "n_subjects")));
        check("xlsx first row", x.rows.get(0)[0].equals("Study_01") && x.rows.get(0)[7].equals("120"));

        // ---- plot: label placement follows the sign of the Z-curve ----
        TsaPlot.Options o = new TsaPlot.Options();
        check("positive Z-curve -> labels in the lower part", TsaPlot.lowerLabels(r, o));
        TsaCor.Result neg = fit(base());
        for (int i = 0; i < neg.cumZ.length; i++) neg.cumZ[i] = -neg.cumZ[i];
        check("negative Z-curve -> labels in the upper part", !TsaPlot.lowerLabels(neg, o));
        o.placement = TsaPlot.Placement.UPPER;
        check("manual override (upper)", !TsaPlot.lowerLabels(r, o));
        BufferedImage img = TsaPlot.render(r, new TsaPlot.Options(), 1.0);
        check("render size", img.getWidth() == TsaPlot.LOGICAL_W && img.getHeight() == TsaPlot.LOGICAL_H);
        PlotPanel pp = new PlotPanel(); pp.setSize(900, 600); pp.setResult(ra);
        BufferedImage img2 = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img2.createGraphics(); pp.paint(g); g.dispose();
        check("panel paints", img2.getRGB(450, 300) != 0);


        // ---- 0.1.1 additions ----
        check("version is 0.1.1", TsaCor.VERSION.equals("0.1.1"));
        check("xlsx numbers carry no binary noise (0.534 not 0.5340000000000001)", example().rows.get(0)[example().col("r")].equals("0.534"));
        // preview order == analysis order
        DataSet previewSorted = rev.sortedBy("Year");
        boolean sameOrder = previewSorted.nrow() == r1.study.length;
        for (int i = 0; sameOrder && i < r1.study.length; i++) sameOrder = previewSorted.rows.get(i)[0].equals(r1.study[i]);
        check("studies preview order equals the order used by the analysis", sameOrder);
        check("unknown order column leaves the data unchanged", rev.sortedBy(null).rows.get(0)[0].equals(rev.rows.get(0)[0]));
        // streamed PNG (used for 150-1200 dpi) is pixel-identical to the in-memory rendering
        {
            File tmp = File.createTempFile("tsacor_png", ".png");
            tmp.deleteOnExit();
            TsaPlot.writePng(r, new TsaPlot.Options(), 1.0, tmp);
            BufferedImage a = javax.imageio.ImageIO.read(tmp), b = TsaPlot.render(r, new TsaPlot.Options(), 1.0);
            long diff = 0;
            for (int yy = 0; yy < b.getHeight(); yy++) for (int xx = 0; xx < b.getWidth(); xx++)
                if ((a.getRGB(xx, yy) & 0xffffff) != (b.getRGB(xx, yy) & 0xffffff)) diff++;
            check("streamed PNG identical to the rendered chart (" + diff + " differing pixels)", diff == 0 && a.getWidth() == 1100 && a.getHeight() == 750);
            TsaPlot.writePng(r, new TsaPlot.Options(), 2.0, tmp);
            BufferedImage a2 = javax.imageio.ImageIO.read(tmp);
            check("200 dpi size 2200 x 1500", a2.getWidth() == 2200 && a2.getHeight() == 1500);
            byte[] bytes = java.nio.file.Files.readAllBytes(tmp.toPath());
            int at = -1;
            for (int i = 0; i + 4 < bytes.length && at < 0; i++) if (bytes[i] == 'p' && bytes[i + 1] == 'H' && bytes[i + 2] == 'Y' && bytes[i + 3] == 's') at = i + 4;
            int ppm = at < 0 ? -1 : ((bytes[at] & 255) << 24) | ((bytes[at + 1] & 255) << 16) | ((bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
            check("pHYs stores 200 dpi (7874 px/m)", ppm == 7874);
            // 1200 dpi: dimensions only (writing 13,200 x 9,000 px takes a few seconds)
            File big = File.createTempFile("tsacor_png1200", ".png");
            big.deleteOnExit();
            TsaPlot.writePng(r, new TsaPlot.Options(), 12.0, big);
            javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(big);
            javax.imageio.ImageReader rd = javax.imageio.ImageIO.getImageReadersByFormatName("png").next();
            rd.setInput(iis);
            check("1200 dpi PNG is 13200 x 9000", rd.getWidth(0) == 13200 && rd.getHeight(0) == 9000);
            rd.dispose(); iis.close();
        }
        // user interface laid out headless: studies preview and wrapped text
        {
            MainPanel mp = new MainPanel();
            mp.loadExample();
            check("preview lists the 20 studies in analysis order", mp.studiesTable.getRowCount() == 20 && mp.studiesTable.getValueAt(0, 0).equals("Study_01"));
            check("preview shows the data columns", mp.studiesTable.getColumnCount() == 8 && mp.studiesTable.getColumnName(0).equals("Study"));
            check("text tabs wrap (no horizontal scrolling)", mp.summaryArea.getLineWrap() && mp.logArea.getLineWrap() && mp.warnArea.getLineWrap());
        }

        System.out.println("\n" + pass + " checks passed, " + fail + " failed.");
        if (fail > 0) System.exit(1);
    }

    interface Thunk { void run() throws Exception; }

    static void expectError(String name, Thunk t, String fragment) {
        try { t.run(); fail++; System.out.println("FAIL: " + name + " (no error)"); }
        catch (TsaCor.TsaException e) { check(name + " message", e.getMessage().contains(fragment)); if (!e.getMessage().contains(fragment)) System.out.println("   got: " + e.getMessage()); }
        catch (Exception e) { fail++; System.out.println("FAIL: " + name + " (" + e + ")"); }
    }
}
