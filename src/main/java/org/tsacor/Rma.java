/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 *
 * Minimal intercept-only random-effects meta-analysis, replacing the parts of
 * metafor::rma() that tsa_cor() uses (yi, sei, one of the tau^2 estimators
 * DL, HE, HS, HSk, SJ, ML, REML, EB, PM, PMM; standard Wald-type inference).
 */
package org.tsacor;

public final class Rma {
    public static final String[] METHODS = {"DL", "HE", "HS", "HSk", "SJ", "ML", "REML", "EB", "PM", "PMM"};

    public int k;
    public String method;
    public double tau2, b, vb, se, zval, pval, ciLb, ciUb, QE, QEp, I2, H2;

    private Rma() {}

    private static double[] weights(double[] v, double tau2) {
        double[] w = new double[v.length];
        for (int i = 0; i < v.length; i++) w[i] = 1.0 / (v[i] + tau2);
        return w;
    }

    private static double wmean(double[] y, double[] w) {
        double sw = 0, swy = 0;
        for (int i = 0; i < y.length; i++) { sw += w[i]; swy += w[i] * y[i]; }
        return swy / sw;
    }

    private static double qStat(double[] y, double[] w) {
        double mu = wmean(y, w), q = 0;
        for (int i = 0; i < y.length; i++) q += w[i] * (y[i] - mu) * (y[i] - mu);
        return q;
    }

    /** Fit with the requested tau^2 estimator ("FE" gives tau^2 = 0). */
    public static Rma fit(double[] yi, double[] sei, String method) {
        int k = yi.length;
        double[] v = new double[k];
        for (int i = 0; i < k; i++) v[i] = sei[i] * sei[i];
        Rma r = new Rma();
        r.k = k;
        r.method = method;
        double[] w0 = weights(v, 0.0);
        double sw0 = Stats.sum(w0);
        double sw02 = 0;
        for (double w : w0) sw02 += w * w;
        double Q0 = qStat(yi, w0);
        r.QE = Q0;
        r.QEp = k > 1 ? Stats.pchisqUpper(Q0, k - 1) : Double.NaN;
        double tau2;
        if (k == 1 || "FE".equals(method)) tau2 = 0.0;
        else tau2 = estimateTau2(yi, v, method, Q0, sw0, sw02);
        r.tau2 = tau2;
        double[] w = weights(v, tau2);
        double sw = Stats.sum(w);
        r.b = wmean(yi, w);
        r.vb = 1.0 / sw;
        r.se = Math.sqrt(r.vb);
        r.zval = r.b / r.se;
        r.pval = 2.0 * Stats.pnorm(-Math.abs(r.zval));
        double crit = Stats.qnorm(1.0 - 0.05 / 2.0);
        r.ciLb = r.b - crit * r.se;
        r.ciUb = r.b + crit * r.se;
        if (k > 1) {
            double vt = (k - 1) / (sw0 - sw02 / sw0);
            r.H2 = (tau2 + vt) / vt;
            r.I2 = 100.0 * tau2 / (vt + tau2);
        } else { r.H2 = Double.NaN; r.I2 = Double.NaN; }
        return r;
    }

    private static double estimateTau2(double[] y, double[] v, String method, double Q0, double sw0, double sw02) {
        int k = y.length;
        switch (method) {
            case "DL": return Math.max(0.0, (Q0 - (k - 1)) / (sw0 - sw02 / sw0));
            case "HE": {
                double ybar = Stats.mean(y), rss = 0, sv = 0;
                for (int i = 0; i < k; i++) { rss += (y[i] - ybar) * (y[i] - ybar); sv += v[i]; }
                return Math.max(0.0, (rss - (k - 1.0) / k * sv) / (k - 1.0));
            }
            case "HS": return Math.max(0.0, (Q0 - k) / sw0);
            case "HSk": return Math.max(0.0, (Q0 - k) / sw0 * k / (k - 1.0));
            case "SJ": {
                double ybar = Stats.mean(y), rss = 0;
                for (double yy : y) rss += (yy - ybar) * (yy - ybar);
                double tau0 = rss / k;
                if (!(tau0 > 0)) return 0.0;
                double[] w = weights(v, tau0);
                return qStat(y, w) * tau0 / (k - 1.0);
            }
            case "PM": return pm(y, v, k - 1.0);
            case "PMM": return pm(y, v, Stats.qchisq(0.5, k - 1.0));
            case "ML": case "REML": case "EB": return scoreRoot(y, v, method);
            default: throw new IllegalArgumentException("unsupported method " + method);
        }
    }

    private static double pm(double[] y, double[] v, double target) {
        Stats.Fn f = t -> qStat(y, weights(v, t)) - target;
        if (f.eval(0.0) <= 0) return 0.0;
        double hi = 1.0;
        while (f.eval(hi) > 0 && hi < 1e12) hi *= 2.0;
        return Stats.uniroot(f, 0.0, hi, 1e-13 * Math.max(1.0, hi));
    }

    private static double scoreRoot(double[] y, double[] v, String method) {
        final int k = y.length;
        Stats.Fn s = t -> {
            double[] w = weights(v, t);
            double mu = wmean(y, w), sw = 0, sw2 = 0, sw2r = 0;
            for (int i = 0; i < k; i++) {
                sw += w[i]; sw2 += w[i] * w[i];
                sw2r += w[i] * w[i] * (y[i] - mu) * (y[i] - mu);
            }
            switch (method) {
                case "ML": return sw2r - sw;
                case "REML": return sw2r - sw + sw2 / sw;
                default: return (double) k / (k - 1.0) * sw2r - sw;   // EB
            }
        };
        if (s.eval(0.0) <= 0) return 0.0;
        double hi = 1.0;
        while (s.eval(hi) > 0 && hi < 1e12) hi *= 2.0;
        return Stats.uniroot(s, 0.0, hi, 1e-13 * Math.max(1.0, hi));
    }

    /** Hartung-Knapp-Sidik-Jonkman scale factor q for a fit with given tau^2. NaN for k &lt; 2. */
    public static double hksjQ(double[] yi, double[] sei, double tau2) {
        int k = yi.length;
        if (k < 2 || Double.isNaN(tau2) || Double.isInfinite(tau2)) return Double.NaN;
        double[] w = new double[k];
        for (int i = 0; i < k; i++) w[i] = 1.0 / (sei[i] * sei[i] + tau2);
        double mu = wmean(yi, w), s = 0;
        for (int i = 0; i < k; i++) s += w[i] * (yi[i] - mu) * (yi[i] - mu);
        return s / (k - 1);
    }

    /** Re-do the inference of this fit under HKSJ (metafor test = "knha", or "t" for the ad hoc case with q &lt; 1). */
    public void applyHksj(double[] yi, double[] sei, boolean adhoc) {
        double q = hksjQ(yi, sei, tau2);
        double scale = (adhoc && q < 1) ? 1.0 : q;
        double[] w = new double[k];
        for (int i = 0; i < k; i++) w[i] = 1.0 / (sei[i] * sei[i] + tau2);
        double sw = Stats.sum(w);
        vb = scale / sw;
        se = Math.sqrt(vb);
        double df = k - 1;
        zval = b / se;
        double lp1 = Stats.logUpperT(Math.abs(zval), df);
        pval = Math.min(1.0, 2.0 * Math.exp(lp1));
        double crit = Stats.qtUpper(0.025, df);
        ciLb = b - crit * se;
        ciUb = b + crit * se;
    }
}
