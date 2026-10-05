/*
 * tsacor-java -- Trial Sequential Analysis for correlations (Java application)
 * Java port of the R package 'tsacor', itself derived from the R package 'RTSA'
 * (Soerensen, Olsen, Lange, Gluud). GPL (>= 2). See COPYRIGHTS.md.
 */
package org.tsacor;

/**
 * Self-contained numerical library: normal, chi-square and Student t
 * distribution functions, and R's zeroin (Brent) root finder. No external
 * dependencies, so the application runs on a plain JRE.
 */
public final class Stats {
    private Stats() {}

    static final double SQRT2 = Math.sqrt(2.0);
    static final double SQRT_PI = Math.sqrt(Math.PI);
    static final double SQRT_2PI = 2.5066282746310002;
    public static final double NaN = Double.NaN;

    // ------------------------------------------------------------------ normal
    public static double dnorm(double x, double mu, double sd) {
        double z = (x - mu) / sd;
        return Math.exp(-0.5 * z * z) / (sd * SQRT_2PI);
    }

    /** erf by the (non-alternating) series; accurate for |x| below about 3. */
    private static double erfSeries(double x) {
        double x2 = x * x;
        double term = x, sum = x;
        for (int n = 0; n < 500; n++) {
            term *= 2.0 * x2 / (2.0 * n + 3.0);
            sum += term;
            if (term < 1e-17 * sum) break;
        }
        return 2.0 / SQRT_PI * Math.exp(-x2) * sum;
    }

    /** exp(x^2) * erfc(x) for x >= 2, by a fixed-depth backward continued fraction. */
    private static double erfcxCF(double x) {
        double t = x;
        for (int k = 200; k >= 1; k--) t = x + 0.5 * k / t;
        return 1.0 / (SQRT_PI * t);
    }

    public static double erfc(double x) {
        if (Double.isNaN(x)) return NaN;
        if (x < 0) return 2.0 - erfc(-x);
        if (x < 2.0) return 1.0 - erfSeries(x);
        if (x > 27.0) return 0.0;
        return Math.exp(-x * x) * erfcxCF(x);
    }

    public static double pnorm(double x, double mu, double sd) {
        double z = (x - mu) / sd;
        return 0.5 * erfc(-z / SQRT2);
    }

    public static double pnorm(double z) { return pnorm(z, 0.0, 1.0); }

    /** log P(Z > z) for the standard normal, accurate far into the tail. */
    public static double logUpperTail(double z) {
        double x = z / SQRT2;
        if (x < 2.0) return Math.log(0.5 * erfc(x));
        return Math.log(0.5 * erfcxCF(x)) - x * x;
    }

    /** z with P(Z > z) = p. */
    public static double qnormUpper(double p) {
        if (Double.isNaN(p) || p < 0.0 || p > 1.0) return NaN;
        if (p == 0.0) return Double.POSITIVE_INFINITY;
        if (p == 1.0) return Double.NEGATIVE_INFINITY;
        if (p == 0.5) return 0.0;
        if (p > 0.5) return -qnormUpper(1.0 - p);
        return qnormUpperLog(Math.log(p));
    }

    /** z with log P(Z > z) = lp, for lp <= log(0.5). */
    public static double qnormUpperLog(double lp) {
        if (lp >= Math.log(0.5)) return qnormUpper(Math.exp(lp));
        double lo = 0.0, hi = 40.0;
        while (logUpperTail(hi) > lp && hi < 1e5) hi *= 2.0;
        for (int it = 0; it < 200; it++) {
            double mid = 0.5 * (lo + hi);
            if (logUpperTail(mid) > lp) lo = mid; else hi = mid;
            if (hi - lo <= 1e-17 * Math.max(1.0, hi)) break;
        }
        double z = 0.5 * (lo + hi);
        for (int it = 0; it < 3; it++) {            // Newton polish on the log scale
            double lq = logUpperTail(z);
            double haz = Math.exp(-0.5 * z * z - Math.log(SQRT_2PI) - lq);
            if (!(haz > 0) || Double.isInfinite(haz)) break;
            double step = (lq - lp) / haz;           // d/dz logQ = -haz
            if (Double.isNaN(step) || Math.abs(step) > 1.0) break;
            z += step;
        }
        return z;
    }

    /** Lower-tail quantile, as R's qnorm(p). */
    public static double qnorm(double p) { return -qnormUpper(p); }

    // ------------------------------------------------------------------- gamma
    private static final double[] LG = {
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    public static double logGamma(double x) {
        if (x < 0.5) return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1.0 - x);
        x -= 1.0;
        double a = LG[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) a += LG[i] / (x + i);
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Regularised upper incomplete gamma Q(a, x). */
    public static double gammaQ(double a, double x) {
        if (x <= 0) return 1.0;
        double lg = logGamma(a);
        if (x < a + 1.0) {
            double ap = a, del = 1.0 / a, sum = del;
            for (int n = 0; n < 5000; n++) {
                ap += 1.0; del *= x / ap; sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-17) break;
            }
            return 1.0 - sum * Math.exp(-x + a * Math.log(x) - lg);
        }
        final double FPMIN = 1e-300;
        double b = x + 1.0 - a, c = 1.0 / FPMIN, d = 1.0 / b, h = d;
        for (int i = 1; i < 5000; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b; if (Math.abs(d) < FPMIN) d = FPMIN;
            c = b + an / c; if (Math.abs(c) < FPMIN) c = FPMIN;
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-16) break;
        }
        return Math.exp(-x + a * Math.log(x) - lg) * h;
    }

    /** P(chi-square_df > q). */
    public static double pchisqUpper(double q, double df) { return gammaQ(df / 2.0, q / 2.0); }

    /** Lower-tail quantile of chi-square. */
    public static double qchisq(double p, double df) {
        double lo = 0.0, hi = Math.max(1.0, df);
        while (1.0 - pchisqUpper(hi, df) < p && hi < 1e12) hi *= 2.0;
        for (int it = 0; it < 300; it++) {
            double mid = 0.5 * (lo + hi);
            if (1.0 - pchisqUpper(mid, df) < p) lo = mid; else hi = mid;
            if (hi - lo <= 1e-16 * hi) break;
        }
        return 0.5 * (lo + hi);
    }

    // -------------------------------------------------------------------- beta
    private static double betacf(double a, double b, double x) {
        final double FPMIN = 1e-300;
        double qab = a + b, qap = a + 1.0, qam = a - 1.0;
        double c = 1.0, d = 1.0 - qab * x / qap;
        if (Math.abs(d) < FPMIN) d = FPMIN;
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= 5000; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d; if (Math.abs(d) < FPMIN) d = FPMIN;
            c = 1.0 + aa / c; if (Math.abs(c) < FPMIN) c = FPMIN;
            d = 1.0 / d; h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d; if (Math.abs(d) < FPMIN) d = FPMIN;
            c = 1.0 + aa / c; if (Math.abs(c) < FPMIN) c = FPMIN;
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-16) break;
        }
        return h;
    }

    /** log of the regularised incomplete beta function I_x(a, b). */
    public static double logIncBeta(double x, double a, double b) {
        if (x <= 0.0) return Double.NEGATIVE_INFINITY;
        if (x >= 1.0) return 0.0;
        double lbt = logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log1p(-x);
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return lbt + Math.log(betacf(a, b, x)) - Math.log(a);
        }
        double lcomp = lbt + Math.log(betacf(b, a, 1.0 - x)) - Math.log(b);   // log I_{1-x}(b, a)
        return Math.log1p(-Math.exp(lcomp));
    }

    /** log P(T > t) for Student's t with df degrees of freedom, t >= 0. */
    public static double logUpperT(double t, double df) {
        if (t == 0.0) return Math.log(0.5);
        double x = df / (df + t * t);
        return Math.log(0.5) + logIncBeta(x, df / 2.0, 0.5);
    }

    /** t with P(T > t) = p (p in (0, 0.5]). */
    public static double qtUpper(double p, double df) {
        double lp = Math.log(p);
        double lo = 0.0, hi = 2.0;
        while (logUpperT(hi, df) > lp && hi < 1e15) hi *= 2.0;
        for (int it = 0; it < 300; it++) {
            double mid = 0.5 * (lo + hi);
            if (logUpperT(mid, df) > lp) lo = mid; else hi = mid;
            if (hi - lo <= 1e-16 * hi) break;
        }
        return 0.5 * (lo + hi);
    }

    // -------------------------------------------------------------- root finder
    public interface Fn { double eval(double x); }

    public static class RootException extends RuntimeException {
        public RootException(String m) { super(m); }
    }

    /** R's stats::uniroot(f, lower, upper, tol = tol)$root (Brent's zeroin, R_zeroin2). */
    public static double uniroot(Fn f, double lower, double upper, double tol) {
        double fa = f.eval(lower), fb = f.eval(upper);
        if (Double.isNaN(fa)) throw new RootException("f.lower = f(lower) is NA");
        if (Double.isNaN(fb)) throw new RootException("f.upper = f(upper) is NA");
        if (fa * fb > 0) throw new RootException("f() values at end points not of opposite sign");
        return zeroin(f, lower, upper, fa, fb, tol, 1000);
    }

    private static double zeroin(Fn f, double ax, double bx, double fa, double fb, double tol, int maxitIn) {
        final double EPS = Math.ulp(1.0);
        double a = ax, b = bx, c = a, fc = fa;
        int maxit = maxitIn + 1;
        if (fa == 0.0) return a;
        if (fb == 0.0) return b;
        while (maxit-- > 0) {
            double prevStep = b - a;
            if (Math.abs(fc) < Math.abs(fb)) {
                a = b; b = c; c = a;
                fa = fb; fb = fc; fc = fa;
            }
            double tolAct = 2 * EPS * Math.abs(b) + tol / 2;
            double newStep = (c - b) / 2;
            if (Math.abs(newStep) <= tolAct || fb == 0.0) return b;
            if (Math.abs(prevStep) >= tolAct && Math.abs(fa) > Math.abs(fb)) {
                double t1, cb = c - b, t2, p, q;
                if (a == c) {
                    t1 = fb / fa; p = cb * t1; q = 1.0 - t1;
                } else {
                    q = fa / fc; t1 = fb / fc; t2 = fb / fa;
                    p = t2 * (cb * q * (q - t1) - (b - a) * (t1 - 1.0));
                    q = (q - 1.0) * (t1 - 1.0) * (t2 - 1.0);
                }
                if (p > 0) q = -q; else p = -p;
                if (p < (0.75 * cb * q - Math.abs(tolAct * q) / 2) && p < Math.abs(prevStep * q / 2)) newStep = p / q;
            }
            if (Math.abs(newStep) < tolAct) newStep = newStep > 0 ? tolAct : -tolAct;
            a = b; fa = fb;
            b += newStep; fb = f.eval(b);
            if ((fb > 0 && fc > 0) || (fb < 0 && fc < 0)) { c = a; fc = fa; }
        }
        return b;   // R warns (maxiter reached) but still returns the last iterate
    }

    // ----------------------------------------------------------- small helpers
    public static double median(double[] x) {
        double[] y = x.clone();
        java.util.Arrays.sort(y);
        int n = y.length;
        if (n == 0) return NaN;
        return n % 2 == 1 ? y[n / 2] : 0.5 * (y[n / 2 - 1] + y[n / 2]);
    }

    public static double mean(double[] x) {
        double s = 0; for (double v : x) s += v; return s / x.length;
    }

    public static double sum(double[] x) { double s = 0; for (double v : x) s += v; return s; }
}
