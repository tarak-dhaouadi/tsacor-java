/*
 * Copyright (C) the RTSA authors (Anne Lyngholm Soerensen, Markus Harboe Olsen,
 * Theis Lange, Christian Gluud) for the algorithms this file is derived from
 * (RTSA 0.2.2, GPL (>= 2)); copyright (C) Tarak Dhaouadi for the adaptation.
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version. See COPYRIGHTS.md and LICENSE.md.
 *
 * Java port of src/rtsa_core.h of the R package 'tsacor' (itself a C++ port of
 * RTSA's recursive-integration boundary engine: init_int(), recur_int(), prob(),
 * z_n_w(), searchfunc(), alpha_boundary(), beta_boundary(), esOF(), sd_inf()).
 * The arithmetic, operand order and the disclosed departures from RTSA (iteration
 * cap, grid-collapse accounting, unreachable-beta handling, no 'spend == beta'
 * shortcut) are kept exactly as in the C++ source. The engine works on
 * information fractions only and is therefore effect-measure agnostic.
 */
package org.tsacor;

import java.util.Arrays;

public final class RtsaCore {
    private RtsaCore() {}

    static final double NaN = Double.NaN;
    static final double kLooseSearchTol = 1e-6;

    /** Thrown by searchfunc() (beta searches only) when the target spend is unreachable. */
    public static class SearchUnreachable extends RuntimeException {
        public SearchUnreachable(String m) { super(m); }
    }

    public static class Diagnostics {
        public int gridCollapses = 0, gridReversed = 0, slowSearches = 0;
    }

    public static final class AlphaOut {
        public double[] zb, asIncr, asCum;
        public int gridCollapses, gridReversed, slowSearches;
    }

    public static final class BetaOut {
        public double[] za, zb, asIncr, asCum, betaTiming, infoFracUsed, sdIncr, sdProc;
        public int gridCollapses, gridReversed, slowSearches;
        /** 1-based look where the beta target was unreachable (0 = none). */
        public int unreachableLook = 0;
    }

    static final class Grid { double[] zj, wj; }

    // ------------------------------------------------------- spending / info
    static void esOF(double a, double[] timing, double[][] out /* [0]=cum, [1]=incr */) {
        int n = timing.length;
        double[] cum = new double[n], incr = new double[n];
        double q = Stats.qnorm(1.0 - a / 2.0);
        for (int i = 0; i < n; i++) {
            cum[i] = 2.0 * (1.0 - Stats.pnorm(q / Math.sqrt(timing[i]), 0.0, 1.0));
            incr[i] = (i == 0) ? cum[i] : cum[i] - cum[i - 1];
        }
        out[0] = cum; out[1] = incr;
    }

    static void sdInf(double[] timing, double[][] out /* [0]=sd_incr, [1]=sd_proc */) {
        int n = timing.length;
        double[] si = new double[n], sp = new double[n];
        for (int i = 0; i < n; i++) {
            double prev = (i == 0) ? 0.0 : timing[i - 1];
            si[i] = Math.sqrt(timing[i] - prev);
            sp[i] = Math.sqrt(timing[i]);
        }
        out[0] = si; out[1] = sp;
    }

    // --------------------------------------------------------------- z_n_w()
    /** `i` is the 1-based look index used throughout RTSA's R code. */
    static Grid zNW(int r, double[] sdIncr, double[] za, double[] zb, int i, double delta, Diagnostics diag) {
        final int n0 = 6 * r - 1;
        double[] xi = new double[n0];
        for (int j = 1; j <= n0; j++) {
            double v = delta * sdIncr[i - 1];
            if (j < r) v += (-3.0 - 4.0 * Math.log((double) r / (double) j));
            if (r <= j && j <= 5 * r) v += (-3.0 + 3.0 * (double) (j - r) / (2.0 * (double) r));
            if (5 * r < j) v += (3.0 + 4.0 * Math.log((double) r / (double) (6 * r - j)));
            xi[j - 1] = v;
        }
        // lower trim
        int lastBelow = -1;
        for (int k = 0; k < n0; k++) if (xi[k] < za[i - 1]) lastBelow = k;
        if (lastBelow >= 0) {
            xi = Arrays.copyOfRange(xi, lastBelow, xi.length);
            xi[0] = za[i - 1];
        }
        // upper trim
        int firstAbove = -1;
        for (int k = 0; k < xi.length; k++) if (xi[k] > zb[i - 1]) { firstAbove = k; break; }
        if (firstAbove >= 0) {
            xi = Arrays.copyOf(xi, firstAbove + 1);
            xi[firstAbove] = zb[i - 1];
        }
        // grid-collapse safety net (RTSA would throw here)
        if (xi.length < 2) {
            double lo = za[i - 1], hi = zb[i - 1];
            if (hi < lo) {
                if (diag != null) diag.gridReversed += 1;
            } else {
                if (diag != null) diag.gridCollapses += 1;
            }
            if (!(hi > lo)) hi = lo + 1e-8;
            xi = new double[]{lo, hi};
        } else if (!(xi[xi.length - 1] > xi[0])) {
            if (diag != null) diag.gridCollapses += 1;
        }
        final int n = xi.length;
        final int m = 2 * n - 1;
        Grid g = new Grid();
        g.zj = new double[m];
        g.wj = new double[m];
        for (int k = 0; k < n; k++) g.zj[2 * k] = xi[k];
        for (int k = 0; k < n - 1; k++) g.zj[2 * k + 1] = (xi[k] + xi[k + 1]) / 2.0;
        double[] zj = g.zj;
        for (int k = 1; k <= m; k++) {            // 1-based k as in R
            if (k == 1) {
                g.wj[k - 1] = (1.0 / 6.0) * (zj[2] - zj[0]);
            } else if ((k % 2 == 1) && k >= 3 && k <= m - 2) {
                g.wj[k - 1] = (1.0 / 6.0) * (zj[k + 1] - zj[k - 3]);
            } else if ((k % 2 == 0) && k >= 2 && k <= m - 1) {
                g.wj[k - 1] = (4.0 / 6.0) * (zj[k] - zj[k - 2]);
            } else {
                g.wj[k - 1] = (1.0 / 6.0) * (zj[m - 1] - zj[m - 3]);
            }
        }
        return g;
    }

    // ------------------------------------------- first.cpp: init/recur/prob
    static double[] initInt(double[] wj, double[] zj, double delta, double sd1) {
        int n = zj.length;
        double[] last = new double[n];
        for (int i = 0; i < n; i++) last[i] = wj[i] * Stats.dnorm(zj[i], delta * sd1, 1.0);
        return last;
    }

    /** k is the 1-based index of the NEW look. */
    static double[] recurInt(int k, double[] sdIncr, double[] sdProc, double[] zj, double[] last,
                             double[] zjUp, double[] wjUp, double delta, boolean bs) {
        int nu = zjUp.length, nl = last.length;
        double[] lastUp = new double[nu];
        double sdk = sdIncr[k - 1];
        double spk = sdProc[k - 1];
        double spp = sdProc[k - 2];
        for (int i = 0; i < nu; i++) {
            double acc = 0.0;
            for (int j = 0; j < nl; j++) {
                if (bs) {
                    acc += last[j] * spk / sdk * Stats.dnorm((zj[j] * spp - zjUp[i] * spk) / sdk, delta * sdk, 1.0);
                } else {
                    acc += last[j] * spk / sdk * Stats.dnorm((zjUp[i] * spk - zj[j] * spp) / sdk, delta * sdk, 1.0);
                }
            }
            lastUp[i] = acc * wjUp[i];
        }
        return lastUp;
    }

    static double prob(double xq, double[] last, double[] zj, int k, double[] sdIncr, double[] sdProc,
                       boolean bs, double delta) {
        double pOut = 0.0;
        double sdk = sdIncr[k - 1];
        double spp = sdProc[k - 2];
        for (int i = 0; i < zj.length; i++) {
            if (!bs && delta != 0.0) {
                pOut += last[i] * Stats.pnorm((zj[i] * spp - xq) / sdk, -delta * sdk, 1.0);
            } else if (bs) {
                pOut += last[i] * Stats.pnorm((xq - zj[i] * spp) / sdk, delta * sdk, 1.0);
            } else {
                pOut += last[i] * Stats.pnorm((zj[i] * spp - xq) / sdk, delta * sdk, 1.0);
            }
        }
        return pOut;
    }

    // ------------------------------------------------------------ searchfunc()
    static double searchfunc(double[] last, double[] zj, int i, double as, double[] sdIncr, double[] sdProc,
                             double[] za, double[] zb, double tol, boolean bs, double delta,
                             Diagnostics diag, int maxOuter) {
        final int maxnn = 50;
        if (bs) {
            double qmax = 0.0;
            for (double v : last) qmax += v;
            if (qmax < as - tol) {
                throw new SearchUnreachable("RTSA-ported beta search: the target spend (" + as
                    + ") exceeds the probability mass still alive (" + qmax + ") at look " + i
                    + " (the futility bound would lie beyond the efficacy wall)");
            }
        }
        double upper = zb[i - 2] * sdProc[i - 1];
        if (bs) upper = za[i - 2] * sdProc[i - 1];
        double del = 10.0;
        double qout = prob(upper, last, zj, i, sdIncr, sdProc, bs, delta);
        int outer = 0;
        boolean converged = false;
        for (;;) {
            ++outer;
            if (Math.abs(qout - as) <= tol) { converged = true; break; }
            if (qout > as + tol) {
                del = del / 10.0;
                for (int k = 0; k < maxnn; k++) {
                    if (bs) upper = upper - 2.0 * del;
                    upper = upper + del;
                    qout = prob(upper, last, zj, i, sdIncr, sdProc, bs, delta);
                    if (qout <= as + tol) break;
                }
            }
            if (qout < as - tol) {
                del = del / 10.0;
                for (int k = 0; k < maxnn; k++) {
                    if (bs) upper = upper + 2.0 * del;
                    upper = upper - del;
                    qout = prob(upper, last, zj, i, sdIncr, sdProc, bs, delta);
                    if (qout >= as - tol) break;
                }
            }
            if (outer > maxOuter) break;
        }
        if (!converged) {
            double resid = Math.abs(qout - as);
            if (resid > kLooseSearchTol) {
                throw new IllegalStateException("RTSA-ported boundary search did not converge at look " + i
                    + " after " + maxOuter + " rounds (residual " + resid + " exceeds the loose tolerance "
                    + kLooseSearchTol + "). This boundary sequence cannot be trusted as computed; consider a "
                    + "different design (information fractions, alpha, power) or report the problem.");
            }
            if (diag != null) diag.slowSearches += 1;
        }
        return upper / sdProc[i - 1];
    }

    // ------------------------------------------------------- alpha_boundary()
    /** designR = NaN -> type "design"; finite -> type "analysis". */
    public static AlphaOut alphaBoundary(double[] infFrac, int side, double alpha, double designR,
                                         double tol, int r, Diagnostics diag) {
        final double zninf = -20.0;
        final int nn = infFrac.length;
        if (nn < 1) throw new IllegalArgumentException("inf_frac must not be empty");
        double[] alphaTiming = infFrac.clone();
        double mx = Double.NEGATIVE_INFINITY;
        for (double v : infFrac) mx = Math.max(mx, v);
        if (mx > 1.0) for (int i = 0; i < nn; i++) alphaTiming[i] = infFrac[i] / mx;

        AlphaOut out = new AlphaOut();
        double[][] sp = new double[2][];
        esOF(alpha / side, alphaTiming, sp);
        out.asCum = sp[0]; out.asIncr = sp[1];

        double[] scale = infFrac.clone();
        if (!Double.isNaN(designR)) for (int i = 0; i < nn; i++) scale[i] = infFrac[i] * designR;
        double[][] sd = new double[2][];
        sdInf(scale, sd);
        double[] sdIncr = sd[0], sdProc = sd[1];

        out.asIncr[0] = Math.min(alpha, out.asIncr[0]);
        out.asIncr[0] = Math.max(0.0, out.asIncr[0]);

        double[] za = new double[nn], zb = new double[nn];
        zb[0] = (out.asIncr[0] < tol) ? -zninf : Stats.qnormUpper(out.asIncr[0]);
        if (side == 1) za[0] = zninf; else za[0] = -zb[0];

        Grid g = zNW(r, sdIncr, za, zb, 1, 0.0, diag);
        double[] last = null;
        for (int ii = 2; ii <= nn; ii++) {
            final int i = ii;
            if (i == 2) last = initInt(g.wj, g.zj, 0.0, sdIncr[0]);
            double a = out.asIncr[i - 1];
            if (a <= 0.0 || a >= 1.0) {
                a = Math.max(0.0, Math.min(1.0, a));
                out.asIncr[i - 1] = a;
            }
            if (a < tol) {
                zb[i - 1] = -zninf;
            } else if (a == 1.0) {
                zb[i - 1] = 0.0;
            } else {
                zb[i - 1] = searchfunc(last, g.zj, i, a, sdIncr, sdProc, za, zb, tol, false, 0.0, diag, 400);
            }
            za[i - 1] = (side == 1) ? zninf : -zb[i - 1];
            if (i != nn) {
                Grid up = zNW(r, sdIncr, za, zb, i, 0.0, diag);
                last = recurInt(i, sdIncr, sdProc, g.zj, last, up.zj, up.wj, 0.0, false);
                g = up;
            }
        }
        out.zb = zb;
        if (diag != null) { out.gridCollapses = diag.gridCollapses; out.gridReversed = diag.gridReversed; out.slowSearches = diag.slowSearches; }
        return out;
    }

    // -------------------------------------------------------- beta_boundary()
    /**
     * warpRoot / designR: NaN when RTSA passes NULL. NOTE: za is NOT a valid boundary from
     * unreachableLook onward (sentinel zb + 1, "beyond the efficacy wall").
     */
    public static BetaOut betaBoundary(double[] infFrac, double[] alphaBound, double beta, int side,
                                       double delta, int rmBs, double designR, double warpRoot,
                                       double zninf, double tol, int r, Diagnostics diag) {
        int nn = infFrac.length;
        if (nn < 1) throw new IllegalArgumentException("inf_frac must not be empty");

        double[] org = infFrac.clone();
        if (!Double.isNaN(warpRoot)) for (int i = 0; i < nn; i++) org[i] = infFrac[i] * warpRoot;

        double[] betaTiming = infFrac.clone();
        if (!Double.isNaN(designR)) {
            betaTiming = new double[nn];
            for (int i = 0; i < nn; i++) betaTiming[i] = infFrac[i] / designR;
            org = infFrac.clone();
            double mo = Double.NEGATIVE_INFINITY;
            for (double v : org) mo = Math.max(mo, v);
            if (mo < designR) { org = Arrays.copyOf(org, org.length + 1); org[org.length - 1] = designR; }
            java.util.ArrayList<Double> bt = new java.util.ArrayList<>();
            for (double v : betaTiming) if (v < 1.0) bt.add(v);
            bt.add(1.0);
            betaTiming = toArray(bt);
            nn = betaTiming.length;
        }
        boolean anyGt1 = false;
        for (double v : betaTiming) if (v > 1.0) anyGt1 = true;
        if (anyGt1) {
            java.util.ArrayList<Double> bt = new java.util.ArrayList<>();
            for (double v : betaTiming) if (v < 1.0) bt.add(v);
            bt.add(1.0);
            betaTiming = toArray(bt);
            nn = betaTiming.length;
        }
        if (rmBs != 0) {
            if (rmBs > betaTiming.length) throw new IllegalArgumentException("rm_bs exceeds number of looks");
            for (int i = 0; i < rmBs; i++) betaTiming[i] = 0.0;
        }
        boolean zbShort = !Double.isNaN(designR) && alphaBound.length + 1 == nn;
        if (org.length != nn || (alphaBound.length != nn && !zbShort))
            throw new IllegalArgumentException("inf_frac, alpha_bound and the beta timeline must have equal length");

        BetaOut out = new BetaOut();
        double[][] sp = new double[2][];
        esOF(beta / side, betaTiming, sp);
        out.asCum = sp[0]; out.asIncr = sp[1];
        double[][] sd = new double[2][];
        sdInf(org, sd);
        double[] sdIncr = sd[0], sdProc = sd[1];

        if (out.asIncr[0] <= 0.0 || out.asIncr[0] >= beta) {
            out.asIncr[0] = Math.min(beta, out.asIncr[0]);
            out.asIncr[0] = Math.max(0.0, out.asIncr[0]);
        }

        double[] za = new double[nn];
        double[] zb = alphaBound.clone();
        if (zbShort) { zb = Arrays.copyOf(zb, zb.length + 1); zb[zb.length - 1] = NaN; }
        if (out.asIncr[0] == 0.0) {
            za[0] = zninf;
        } else {
            za[0] = sdProc[0] * delta + Stats.qnorm(out.asIncr[0]) ;   // qnorm(p, mean = sd_proc[0]*delta, sd = 1)
        }

        Grid g = zNW(r, sdIncr, za, zb, 1, delta, diag);
        double[] last = null;
        for (int ii = 2; ii <= nn; ii++) {
            final int i = ii;
            if (i == 2) last = initInt(g.wj, g.zj, delta, sdIncr[0]);
            double a = out.asIncr[i - 1];
            if (a <= 0.0 || a >= 1.0) {
                a = Math.max(0.0, Math.min(1.0, a));
                out.asIncr[i - 1] = a;
            }
            if (a < tol) {
                za[i - 1] = zninf;
            } else {
                try {
                    za[i - 1] = searchfunc(last, g.zj, i, a, sdIncr, sdProc, za, zb, tol, true, delta, diag, 400);
                } catch (SearchUnreachable e) {
                    out.unreachableLook = i;
                    for (int j = i - 1; j < nn; j++) {
                        double wall = Double.isNaN(zb[j]) ? zb[nn - 2] : zb[j];
                        za[j] = wall + 1.0;
                    }
                    break;
                }
            }
            if (i != nn) {
                Grid up = zNW(r, sdIncr, za, zb, i, delta, diag);
                last = recurInt(i, sdIncr, sdProc, g.zj, last, up.zj, up.wj, delta, false);
                g = up;
            }
        }
        out.za = za; out.zb = zb;
        out.betaTiming = betaTiming; out.infoFracUsed = org; out.sdIncr = sdIncr; out.sdProc = sdProc;
        if (diag != null) { out.gridCollapses = diag.gridCollapses; out.gridReversed = diag.gridReversed; out.slowSearches = diag.slowSearches; }
        return out;
    }

    private static double[] toArray(java.util.List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
