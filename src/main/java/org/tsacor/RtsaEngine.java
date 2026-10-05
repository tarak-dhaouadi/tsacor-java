/*
 * Copyright (C) the RTSA authors (Anne Lyngholm Soerensen, Markus Harboe Olsen,
 * Theis Lange, Christian Gluud) for the algorithms this file is derived from
 * (RTSA 0.2.2, GPL (>= 2)); copyright (C) Tarak Dhaouadi for the adaptation.
 * GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 *
 * Java port of R/rtsa_engine.R of the R package 'tsacor': RTSA's orchestration
 * of the boundary engine (design route with the information-scale root search,
 * analysis route, retrospective chaining) and the associated diagnostics.
 */
package org.tsacor;

import java.util.List;

public final class RtsaEngine {
    private RtsaEngine() {}

    /** Result of a design or analysis pass (the fields tsa_cor() needs). */
    public static final class Bounds {
        public double[] timing, alphaUbound, za, betaUbound, betaSpent, betaSpentDelta;
        public double root;
        public int rmBs;
        public double delta;
        public boolean[] sentinel;
        public boolean finalBeyondWall;
    }

    private static void warnDiagnostics(RtsaCore.Diagnostics d, String where, List<String> warnings) {
        if (d.gridReversed > 0) {
            warnings.add(String.format("*** RTSA-ported integration interval was REVERSED (lower wall above the upper "
                + "wall) %d time(s) while computing %s. The interval was widened to a minimal non-zero window so the "
                + "calculation could continue, but a reversed configuration is NOT a valid RTSA computation (RTSA's "
                + "own R code would have errored): the boundaries from the look where this happened onward must not "
                + "be trusted or reported as RTSA-equivalent. Check the design (information fractions, alpha, "
                + "power). ***", d.gridReversed, where));
        }
        if (d.gridCollapses > 0) {
            warnings.add(String.format("RTSA-ported integration grid collapsed to a degenerate (fewer-than-two-node or "
                + "zero-width) interval %d time(s) while computing %s; the calculation continued (an interval with no "
                + "positive width is widened to a minimal non-zero window). RTSA's own R code would have errored at the "
                + "first one instead -- treat this boundary sequence with extra caution at the look(s) where it "
                + "happened.", d.gridCollapses, where));
        }
        if (d.slowSearches > 0) {
            warnings.add(String.format("RTSA-ported boundary search needed the slow (iteration-capped) path %d time(s) "
                + "while computing %s; the result still converged within a loose tolerance and was accepted, but this "
                + "is slower and less certain than RTSA's own uncapped search.", d.slowSearches, where));
        }
    }

    static RtsaCore.AlphaOut alphaCpp(double[] infFrac, int side, double alpha, double designR, String where,
                                      List<String> warnings) {
        if (infFrac.length == 0) throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (double v : infFrac)
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0)
                throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        RtsaCore.Diagnostics d = new RtsaCore.Diagnostics();
        RtsaCore.AlphaOut res = RtsaCore.alphaBoundary(infFrac, side, alpha, designR, 1e-9, 18, d);
        warnDiagnostics(d, where, warnings);
        return res;
    }

    static RtsaCore.BetaOut betaCpp(double[] infFrac, double[] alphaBound, double beta, double delta, int rmBs,
                                    double designR, double warpRoot, String where, boolean warn, List<String> warnings) {
        if (infFrac.length == 0) throw new IllegalArgumentException("information fractions must be finite");
        for (double v : infFrac)
            if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("information fractions must be finite");
        RtsaCore.Diagnostics d = new RtsaCore.Diagnostics();
        RtsaCore.BetaOut res = RtsaCore.betaBoundary(infFrac, alphaBound, beta, 1, delta, rmBs, designR, warpRoot,
            -20.0, 1e-15, 18, d);
        if (warn) warnDiagnostics(d, where, warnings);
        return res;
    }

    /** RTSA's "slide a narrow bracket upward until uniroot() succeeds" loop. */
    private static double slideRoot(Stats.Fn f, double start, double step, int maxIter, double tol) {
        double upper = start;
        String lastErr = null;
        for (int n = 0; n < maxIter; n++) {
            try {
                return Stats.uniroot(f, upper - step, upper, tol);
            } catch (Stats.RootException e) {
                if (!e.getMessage().contains("opposite sign")) lastErr = e.getMessage();
            } catch (RuntimeException e) {
                lastErr = e.getMessage();
            }
            upper += step;
        }
        throw new IllegalStateException("RTSA-style information-scale root search did not converge (no sign change "
            + "found in [" + (start - step) + ", " + (start + step * (maxIter - 1)) + "])"
            + (lastErr != null ? "; last engine error: " + lastErr : "") + ".");
    }

    static void checkLookSpacing(double[] t, String where, List<String> warnings) {
        final double threshold = 0.0025;
        if (t.length < 2) return;
        int count = 0, argmin = -1;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 1; i < t.length; i++) {
            double dt = t[i] - t[i - 1];
            if (dt < threshold) count++;
            if (dt < min) { min = dt; argmin = i + 1; }
        }
        if (count > 0) {
            warnings.add(String.format("%s has %d look(s) that add less than %.2f%% of the required information "
                + "(smallest increment %.4g of the required information, at look %d of %d). Below this spacing the "
                + "recursive integration at the default grid resolution can become numerically unreliable (in the "
                + "cases measured, sometimes grossly so), so the boundaries should not be trusted. (The %.2f%% level "
                + "is an empirical warning threshold for this implementation's default integration grid, not an RTSA "
                + "rule; RTSA itself refuses such schedules (design) or drops looks adding < 1%%.) Consider merging "
                + "near-simultaneous studies.", where, count, threshold * 100, min, argmin, t.length, threshold * 100));
        }
    }

    private static void checkConvergedPass(RtsaCore.BetaOut lb, double finalWall, String where) {
        if (lb.unreachableLook > 0)
            throw new IllegalStateException(String.format("%s reached an unreachable futility target at look %d: the "
                + "information-scale root search converged onto the infeasible region, not onto a root.",
                where, lb.unreachableLook));
        double resGap = finalWall - lb.za[lb.za.length - 1];
        if (Double.isNaN(resGap) || Double.isInfinite(resGap) || Math.abs(resGap) > 1e-6)
            throw new IllegalStateException(String.format("%s: the final futility bound does not meet the final "
                + "efficacy bound at the accepted root (residual gap %.3g).", where, resGap));
    }

    /**
     * RTSA::boundaries(timing = t, alpha, beta, side = 2, futility = "non-binding",
     * es_alpha = "esOF", es_beta = "esOF", type = "design"). A final look at t = 1
     * is appended when max(t) &lt; 1, as RTSA does.
     */
    public static Bounds designBounds(double[] tIn, double alpha, double beta, List<String> warnings) {
        double[] t = tIn.clone();
        if (t.length == 0) throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (double v : t) if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0)
            throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (int i = 1; i < t.length; i++)
            if (!(t[i] > t[i - 1])) throw new IllegalArgumentException("information fractions must be strictly increasing");
        double mx = t[t.length - 1];
        if (mx < 1) { t = java.util.Arrays.copyOf(t, t.length + 1); t[t.length - 1] = 1.0; }
        final double[] tt = t;
        final int nt = tt.length;
        checkLookSpacing(tt, "the design-route look schedule", warnings);

        RtsaCore.AlphaOut ab = alphaCpp(tt, 2, alpha, Double.NaN, "the design-route alpha (efficacy) boundary", warnings);
        final double[] ub = ab.zb;
        final double delta = Math.abs(Stats.qnorm(alpha / 2) + Stats.qnorm(beta));

        Bounds out = new Bounds();
        out.timing = tt; out.alphaUbound = ub; out.delta = delta;
        if (nt == 1) {
            out.za = ub.clone(); out.betaUbound = ub.clone(); out.root = 1; out.rmBs = 0;
            out.betaSpent = new double[]{beta}; out.betaSpentDelta = new double[]{beta};
            out.sentinel = new boolean[]{false};
            return out;
        }
        List<String> sink = new java.util.ArrayList<>();   // candidate evaluations are never warned about
        class Gap {
            double eval(double x, int rm) {
                RtsaCore.BetaOut lb = betaCpp(tt, ub, beta, delta, rm, Double.NaN, x,
                    "the design-route beta (futility) boundary (root search)", false, sink);
                return ub[nt - 1] - lb.za[nt - 1];
            }
        }
        final Gap gap = new Gap();
        // PASS 1: RTSA, nn_max = 50 windows of width 0.02 starting at [.93, .95]
        double root1 = slideRoot(x -> gap.eval(x, 0), 0.95, 0.02, 50, 1e-9);
        RtsaCore.BetaOut lb1 = betaCpp(tt, ub, beta, delta, 0, Double.NaN, root1,
            "the design-route beta (futility) boundary (pass 1)", true, warnings);
        checkConvergedPass(lb1, ub[nt - 1], "the design-route calibration (pass 1)");
        int rmBs = 0;
        for (double v : lb1.za) if (v < 0) rmBs++;
        if (rmBs >= nt)
            throw new IllegalStateException("every look has a negative futility bound on the first pass; the "
                + "non-binding design cannot be computed.");
        // PASS 2: negative early looks get zero beta spend; windows of width 0.05
        final int rmFinal = rmBs;
        double root2 = slideRoot(x -> gap.eval(x, rmFinal), 0.95, 0.05, 50, 1e-9);
        RtsaCore.BetaOut lb = betaCpp(tt, ub, beta, delta, rmBs, Double.NaN, root2,
            "the design-route beta (futility) boundary (pass 2)", true, warnings);
        checkConvergedPass(lb, ub[nt - 1], "the design-route calibration (pass 2)");

        double[] za = lb.za;
        boolean[] sentinel = new boolean[za.length];
        double[] betaUbound = za.clone();
        for (int i = 0; i < za.length; i++) {
            sentinel[i] = Math.abs(za[i]) == 20;
            if (sentinel[i]) betaUbound[i] = Double.NaN;
        }
        out.za = za; out.betaUbound = betaUbound; out.root = root2; out.rmBs = rmBs;
        out.betaSpent = lb.asCum; out.betaSpentDelta = lb.asIncr; out.sentinel = sentinel;
        return out;
    }

    /**
     * RTSA::boundaries(timing = t_ext, ..., type = "analysis", design_R = design_R).
     * tExt normally already ends at designR (RTSA() appends it before calling).
     */
    public static Bounds analysisBounds(double[] tExt, double designR, double alpha, double beta, List<String> warnings) {
        if (tExt.length == 0) throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        double mx = Double.NEGATIVE_INFINITY;
        for (double v : tExt) {
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0)
                throw new IllegalArgumentException("information fractions must be finite and strictly positive");
            mx = Math.max(mx, v);
        }
        if (Double.isNaN(designR) || Double.isInfinite(designR) || designR <= 0)
            throw new IllegalArgumentException("design_R must be a finite, strictly positive scalar");
        if (tExt.length < 2 && !(mx < designR))
            throw new IllegalArgumentException("the analysis route needs at least one observed look plus design_R");
        checkLookSpacing(tExt, "the analysis-route look schedule", warnings);
        RtsaCore.AlphaOut ab = alphaCpp(tExt, 2, alpha, designR, "the analysis-route alpha (efficacy) boundary", warnings);
        double[] ub = ab.zb;
        double delta = Math.abs(Stats.qnorm(alpha / 2) + Stats.qnorm(beta));

        int rmBs = 0;
        RtsaCore.BetaOut lb = null;
        int unr = 0;
        for (int pass = 1; pass <= 3; pass++) {
            lb = betaCpp(tExt, ub, beta, delta, rmBs, designR, Double.NaN,
                "the analysis-route beta (futility) boundary (pass " + pass + ")", true, warnings);
            unr = lb.unreachableLook;
            int nLooks = lb.za.length;
            if (unr > 0 && unr < nLooks)
                throw new IllegalStateException(String.format("the futility bound reaches the efficacy wall at look %d "
                    + "of %d in the analysis-route beta calculation (pass %d); the analysis route cannot be computed "
                    + "for this schedule.", unr, nLooks, pass));
            rmBs = 0;
            for (double v : lb.za) if (v < 0) rmBs++;
        }
        boolean finalBeyondWall = unr > 0;
        double[] za = lb.za;
        boolean[] sentinel = new boolean[za.length];
        double[] betaUbound = za.clone();
        for (int i = 0; i < za.length; i++) {
            sentinel[i] = Math.abs(za[i]) == 20;
            if (sentinel[i]) betaUbound[i] = Double.NaN;
        }
        int nl = ub.length;
        if (!Double.isNaN(betaUbound[nl - 1]) && betaUbound[nl - 1] > ub[nl - 1]) betaUbound[nl - 1] = ub[nl - 1];
        Bounds out = new Bounds();
        out.timing = tExt; out.alphaUbound = ub; out.za = za; out.betaUbound = betaUbound;
        out.root = designR; out.rmBs = rmBs; out.delta = delta; out.betaSpent = lb.asCum;
        out.betaSpentDelta = lb.asIncr; out.sentinel = sentinel; out.finalBeyondWall = finalBeyondWall;
        return out;
    }

    /** Estimated cumulative participants at which info_fraction first reaches target (linear interpolation). */
    public static double participantsAtFraction(double[] infoFraction, double[] cumN, double target) {
        int reach = -1;
        for (int i = 0; i < infoFraction.length; i++) if (infoFraction[i] >= target) { reach = i; break; }
        if (reach < 0) return Double.NaN;
        if (reach == 0) return cumN[0];
        double f0 = infoFraction[reach - 1], f1 = infoFraction[reach];
        double n0 = cumN[reach - 1], n1 = cumN[reach];
        double w = f1 > f0 ? (target - f0) / (f1 - f0) : 0.0;
        return n0 + w * (n1 - n0);
    }
}
