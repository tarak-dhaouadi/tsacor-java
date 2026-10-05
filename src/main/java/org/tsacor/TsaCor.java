/*
 * tsacor-java -- Trial Sequential Analysis for meta-analyses of correlations.
 *
 * Java port of tsa_cor() of the R package 'tsacor' (Tarak Dhaouadi), which in
 * turn adapts tsa_hr() of 'tsahr' and the boundary engine of the R package
 * 'RTSA' (Soerensen, Olsen, Lange, Gluud). GPL (>= 2); see COPYRIGHTS.md, LICENSE.md.
 */
package org.tsacor;

import java.util.*;

public final class TsaCor {
    private TsaCor() {}

    public static final String VERSION = "0.1.1";

    public static class TsaException extends RuntimeException {
        public TsaException(String m) { super(m); }
    }

    static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    static double atanh(double x) { return 0.5 * Math.log1p(2.0 * x / (1.0 - x)); }

    static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    static String bigMark(double v) {
        long n = (long) Math.ceil(v);
        String s = Long.toString(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(',');
            sb.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb;
    }

    // ===================================================================== params
    public static final class Params {
        public double alphaTwoSided = 0.05;
        public double power = 0.80;
        public double targetR = Double.NaN;
        public String corType = "pearson";            // pearson | spearman (aliases r, rho)
        public String seSource = "ci";                // ci | n
        public String spearmanVariance = "fieller";   // fieller | bonett_wright
        public double ciLevel = 0.95;
        public String method = "DL";
        public String orderBy = null;
        public String boundaryRoute = "design";       // design | analysis
        /** If the analysis route fails: fall back to the design-route result (R: legacy_fallback = TRUE). */
        public boolean fallbackToDesign = true;
        public String projectionStat = "median";      // median | mean
        public String infoPerParticipantBasis = "per_study";   // per_study | pooled
        public String reInference = "standard";       // standard | hksj | hksj_adhoc (aliases knha, knha_adhoc)
        public boolean verbose = true;

        public Params copy() {
            Params p = new Params();
            p.alphaTwoSided = alphaTwoSided; p.power = power; p.targetR = targetR; p.corType = corType;
            p.seSource = seSource; p.spearmanVariance = spearmanVariance; p.ciLevel = ciLevel; p.method = method;
            p.orderBy = orderBy; p.boundaryRoute = boundaryRoute; p.fallbackToDesign = fallbackToDesign;
            p.projectionStat = projectionStat; p.infoPerParticipantBasis = infoPerParticipantBasis;
            p.reInference = reInference; p.verbose = verbose;
            return p;
        }
    }

    // ===================================================================== result
    public static final class Result {
        public Params params;
        public String corType, seSource, spearmanVariance, method, reInference;
        public double alphaTwoSided, power, targetR, rAnticipated, zAnticipated, varFactor;
        public List<String> warnings = new ArrayList<>();
        public String log = "";

        // data (after ordering)
        public DataSet data;
        public String[] study;
        public double[] r, nSubjects, lbound, ubound, zFisher, seZn, seZci, seZ;

        // pooled / heterogeneity
        public double pooledZ, pooledR, pooledLb, pooledUb, pooledPval, pooledTau2Display;
        public double Q, QEp, I2, tau2, D2, D2raw, AF;
        public int dfQ;
        public boolean D2capped;
        public Rma resRe, resStd, resFe;

        // information size
        public double zAlpha, zBeta, infoRequired, risParticipants, darisInfo, darisParticipants,
            darisInfoThresholdN, routeEndpointInfo, routeEndpointN, routeEndpointParticipantsTheoretical;
        public boolean circularityWarning, circularitySevere;

        // cumulative
        public double[] cumEstimate, cumSe, cumZval, cumZ, cumPval, cumCiLb, cumCiUb, cumTau2,
            cumR, cumRLb, cumRUb, cumN, infoAccrued, infoFraction, infoFractionParticipants,
            boundaryUpper, boundaryLower, futilityUpper, futilityLower;

        // boundary timeline
        public double[] btInfoFraction, btCumN, btUpper, btLower, btFutUpper, btFutLower;
        public boolean[] btSynthetic;
        public double designRoot = Double.NaN;
        public int rmBs;
        public String engine;

        // results
        public boolean crossedConventional, crossedTsa, enteredFutilityRegion, finalReached, darisReached;
        public Boolean finalCrossedEfficacy, finalNonEfficacy, finalEnteredFutilityRegion;
        public int finalTsaLook;
        public double participantsAccrued, infoAccruedFinal;

        // settings
        public String boundaryRoute, routeUsed, fallbackRoute = "none", fallbackReason;
        public boolean fallbackUsed;
        public double routeEndpoint;

        // projection
        public String projectionStat, infoPerParticipantBasis, projectionNote;
        public double iRequired, additionalInfoRequired = Double.NaN, centralInfoIncrement, centralParticipantIncrement,
            centralInfoPerParticipant, studyLevelInfoPerParticipant, pooledInfoPerParticipant,
            additionalParticipantsEstimated = Double.NaN, additionalParticipantsStudyLevel = Double.NaN,
            additionalParticipantsPooled = Double.NaN, participantsFromWholeStudies = Double.NaN,
            targetParticipantsHistoricalRate = Double.NaN, additionalParticipantsTheoretical = Double.NaN;
        public int nAdditionalStudies = -1;   // -1 = NA
        public int nStudies;

        // summary table
        public List<String[]> summary = new ArrayList<>();
        public LinkedHashMap<String, String> abbreviations = new LinkedHashMap<>();

        public boolean analysisRoute() { return "analysis".equals(routeUsed); }

        public String corLabel() { return "spearman".equals(corType) ? "Spearman rho" : "Pearson r"; }
        public String corSym() { return "spearman".equals(corType) ? "\u03c1" : "r"; }

        /** Last non-NaN value of the cumulative Z-curve. */
        public double lastZ() {
            for (int i = cumZ.length - 1; i >= 0; i--) if (!Double.isNaN(cumZ[i])) return cumZ[i];
            return Double.NaN;
        }
    }

    // ===================================================================== helpers
    static String methodLabel(String m) {
        switch (m) {
            case "DL": return "DerSimonian-Laird";
            case "HE": return "Hedges";
            case "HS": return "Hunter-Schmidt";
            case "HSk": return "Hunter-Schmidt (with k correction)";
            case "SJ": return "Sidik-Jonkman";
            case "ML": return "maximum likelihood";
            case "REML": return "restricted maximum likelihood";
            case "EB": return "empirical Bayes";
            case "PM": return "Paule-Mandel";
            case "PMM": return "Paule-Mandel (median-unbiased)";
            default: return "'" + m + "'";
        }
    }

    static String reInferenceLabel(String r) {
        switch (r) {
            case "standard": return "standard (Wald-type z test)";
            case "hksj": return "Hartung-Knapp-Sidik-Jonkman (HKSJ)";
            case "hksj_adhoc": return "HKSJ with ad hoc variance correction";
            default: return "'" + r + "'";
        }
    }

    static String normaliseReInference(String s) {
        String msg = "re_inference must be one of: \"standard\", \"hksj\" (alias \"knha\"), \"hksj_adhoc\" "
            + "(alias \"knha_adhoc\"); matching is case-insensitive.";
        if (s == null) throw new TsaException(msg);
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "standard": return "standard";
            case "hksj": case "knha": return "hksj";
            case "hksj_adhoc": case "knha_adhoc": return "hksj_adhoc";
            default: throw new TsaException(msg);
        }
    }

    static double varianceFactor(String corType, String spearmanVariance, double rRef) {
        if (corType.equals("pearson")) return 1.0;
        if (spearmanVariance.equals("fieller")) return 1.06;
        return 1.0 + rRef * rRef / 2.0;
    }

    static double seFromN(double r, double n, String corType, String sv) {
        if (corType.equals("pearson")) return 1.0 / Math.sqrt(n - 3);
        if (sv.equals("fieller")) return Math.sqrt(1.06 / (n - 3));
        return Math.sqrt((1 + r * r / 2) / (n - 3));
    }

    static double seFromCi(double lb, double ub, double ciLevel) {
        return (atanh(ub) - atanh(lb)) / (2 * Stats.qnorm(1 - (1 - ciLevel) / 2));
    }

    static int matchIndex(double[] arr, double v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    // ===================================================================== main
    public static Result run(DataSet input, Params pIn) {
        Params p = pIn.copy();
        Result R = new Result();
        StringBuilder log = new StringBuilder();
        boolean verbose = p.verbose;
        List<String> warn = R.warnings;

        // ---- argument normalisation / validation ---------------------------------
        String cor = p.corType == null ? "" : p.corType.trim().toLowerCase(Locale.ROOT);
        if (cor.equals("rho")) cor = "spearman";
        if (cor.equals("r")) cor = "pearson";
        if (!cor.equals("pearson") && !cor.equals("spearman"))
            throw new TsaException("'arg' should be one of \"pearson\", \"spearman\"");
        String seSource = p.seSource.toLowerCase(Locale.ROOT);
        if (!seSource.equals("ci") && !seSource.equals("n")) throw new TsaException("'se_source' should be one of \"ci\", \"n\"");
        String sv = p.spearmanVariance.toLowerCase(Locale.ROOT);
        if (!sv.equals("fieller") && !sv.equals("bonett_wright"))
            throw new TsaException("'spearman_variance' should be one of \"fieller\", \"bonett_wright\"");
        String route = p.boundaryRoute.toLowerCase(Locale.ROOT);
        if (!route.equals("design") && !route.equals("analysis"))
            throw new TsaException("'boundary_route' should be one of \"design\", \"analysis\"");
        String projStat = p.projectionStat.toLowerCase(Locale.ROOT);
        if (!projStat.equals("median") && !projStat.equals("mean"))
            throw new TsaException("'projection_stat' should be one of \"median\", \"mean\"");
        String ippBasis = p.infoPerParticipantBasis.toLowerCase(Locale.ROOT);
        if (!ippBasis.equals("per_study") && !ippBasis.equals("pooled"))
            throw new TsaException("'info_per_participant_basis' should be one of \"per_study\", \"pooled\"");
        String corLabel = cor.equals("spearman") ? "Spearman rho" : "Pearson r";

        String method = p.method == null ? "" : p.method.trim();
        if (method.equals("CO") || method.equals("VC")) method = "HE";
        if (method.equals("GENQ") || method.equals("GENQM"))
            throw new TsaException("method = \"" + method + "\" is not currently supported by tsa_cor(): metafor's "
                + "generalized-Q-statistic estimators require a user-supplied `weights` argument. Supported methods "
                + "are: " + String.join(", ", Rma.METHODS) + ".");
        if (!Arrays.asList(Rma.METHODS).contains(method))
            throw new TsaException("method must be one of: " + String.join(", ", Rma.METHODS)
                + " (the random-effects heterogeneity-variance estimators supported).");
        String reInferenceRequested = p.reInference;
        String reInference = normaliseReInference(p.reInference);

        double alpha = p.alphaTwoSided, power = p.power, ciLevel = p.ciLevel;
        if (!finite(alpha) || alpha <= 0 || alpha >= 1)
            throw new TsaException("alpha_two_sided must be a single finite value strictly between 0 and 1.");
        if (!finite(power) || power <= 0 || power >= 1)
            throw new TsaException("power must be a single finite value strictly between 0 and 1.");
        if (!finite(ciLevel) || ciLevel <= 0 || ciLevel >= 1)
            throw new TsaException("ci_level must be a single finite value strictly between 0 and 1.");

        // ---- 1. load / validate data --------------------------------------------
        DataSet ds = input.copy();
        List<String> namesBefore = new ArrayList<>(ds.names);
        for (int i = 0; i < ds.names.size(); i++) ds.names.set(i, ds.names.get(i).replace(" ", "_"));
        {
            Set<String> seen = new HashSet<>(), dup = new LinkedHashSet<>();
            for (String n : ds.names) if (!seen.add(n)) dup.add(n);
            if (!dup.isEmpty()) {
                List<String> orig = new ArrayList<>();
                for (int i = 0; i < ds.names.size(); i++) if (dup.contains(ds.names.get(i))) orig.add("'" + namesBefore.get(i) + "'");
                throw new TsaException("Column names are not unique after spaces are replaced with underscores: "
                    + String.join(", ", dup) + ". Original column name(s) involved: " + String.join(", ", orig)
                    + ". Rename the columns in the source data so they remain distinct once spaces become underscores.");
            }
        }
        String orderBy = p.orderBy;
        if (orderBy != null) { orderBy = orderBy.trim().replace(" ", "_"); if (orderBy.isEmpty()) orderBy = null; }

        List<String> required = new ArrayList<>(Arrays.asList("Study", "r", "n_subjects"));
        if (seSource.equals("ci")) { required.add("lbound"); required.add("ubound"); }
        List<String> missing = new ArrayList<>();
        for (String c : required) if (ds.col(c) < 0) missing.add(c);
        if (!missing.isEmpty()) {
            String extra = "";
            if (seSource.equals("ci") && (missing.contains("lbound") || missing.contains("ubound")))
                extra = " (the confidence-interval columns are needed because se_source = \"ci\"; use se_source = "
                    + "\"n\" to derive the standard errors from n_subjects instead)";
            throw new TsaException("Missing required column(s): " + String.join(", ", missing) + extra);
        }
        int nrow = ds.nrow();
        String[] studyRaw = ds.column(ds.col("Study"));
        for (String s : studyRaw)
            if (DataSet.isBlank(s) || s.trim().isEmpty()) throw new TsaException("Study must contain non-missing, non-empty identifiers.");
        if (new HashSet<>(Arrays.asList(studyRaw)).size() != studyRaw.length)
            throw new TsaException("Study names must be unique (duplicate found in 'Study' column).");
        if (nrow < 2) throw new TsaException("tsa_cor() requires at least two studies.");
        if (nrow < 10)
            warn.add("Only " + nrow + " studies were supplied. Heterogeneity/D2 estimates (and therefore DARIS and the "
                + "monitoring boundaries) can be highly unstable with few studies; the Copenhagen TSA manual cautions "
                + "about this below roughly 10 studies.");

        List<String> numericCols = new ArrayList<>(Arrays.asList("r", "n_subjects"));
        if (seSource.equals("ci")) { numericCols.add("lbound"); numericCols.add("ubound"); }
        List<String> notNumeric = new ArrayList<>();
        for (String c : numericCols) if (!ds.isNumericColumn(ds.col(c))) notNumeric.add(c + " (text)");
        if (!notNumeric.isEmpty())
            throw new TsaException("Column(s) must be numeric, but are not: " + String.join(", ", notNumeric)
                + ". Check for text, footnote markers, or blank-but-not-empty cells in the source data.");

        double[] r = ds.numericColumn(ds.col("r"));
        double[] n = ds.numericColumn(ds.col("n_subjects"));
        for (double v : r) if (!finite(v)) throw new TsaException("r must be finite for every study (found NA/NaN/Inf).");
        for (double v : r) if (Math.abs(v) >= 1)
            throw new TsaException("r must lie strictly between -1 and 1 for every study (Fisher's z is infinite at |r| = 1).");
        for (double v : n) if (!finite(v)) throw new TsaException("n_subjects must be finite for every study (found NA/NaN/Inf).");
        for (double v : n) if (Math.abs(v - Math.rint(v)) > Math.sqrt(Math.ulp(1.0)))
            throw new TsaException("n_subjects must be whole numbers.");
        for (double v : n) if (v <= 3)
            throw new TsaException("n_subjects must be greater than 3 for every study (the variance of Fisher's z is 1/(n - 3)).");

        double[] z = new double[nrow], seN = new double[nrow], seCi = new double[nrow], se = new double[nrow];
        Arrays.fill(seCi, Double.NaN);
        for (int i = 0; i < nrow; i++) { z[i] = atanh(r[i]); seN[i] = seFromN(r[i], n[i], cor, sv); }
        double[] lb = null, ub = null;
        boolean ciColsOk = false;
        if (ds.col("lbound") >= 0 && ds.col("ubound") >= 0 && ds.isNumericColumn(ds.col("lbound")) && ds.isNumericColumn(ds.col("ubound"))) {
            lb = ds.numericColumn(ds.col("lbound")); ub = ds.numericColumn(ds.col("ubound"));
            ciColsOk = true;
            for (int i = 0; i < nrow; i++)
                if (!finite(lb[i]) || !finite(ub[i]) || Math.abs(lb[i]) >= 1 || Math.abs(ub[i]) >= 1 || !(lb[i] < ub[i])) ciColsOk = false;
        }
        if (seSource.equals("ci")) {
            for (int i = 0; i < nrow; i++) if (!finite(lb[i]) || !finite(ub[i]))
                throw new TsaException("lbound and ubound must be finite for every study (found NA/NaN/Inf).");
            for (int i = 0; i < nrow; i++) if (Math.abs(lb[i]) >= 1 || Math.abs(ub[i]) >= 1)
                throw new TsaException("lbound and ubound must lie strictly between -1 and 1 for every study.");
            for (int i = 0; i < nrow; i++) if (lb[i] >= ub[i])
                throw new TsaException("lbound must be smaller than ubound for every study.");
        }
        if (ciColsOk) for (int i = 0; i < nrow; i++) seCi[i] = seFromCi(lb[i], ub[i], ciLevel);
        if (seSource.equals("ci")) {
            System.arraycopy(seCi, 0, se, 0, nrow);
            List<String> outside = new ArrayList<>(), offc = new ArrayList<>();
            for (int i = 0; i < nrow; i++) {
                if (r[i] < lb[i] || r[i] > ub[i]) outside.add(studyRaw[i]);
                double dev = Math.abs((atanh(lb[i]) + atanh(ub[i])) / 2 - z[i]) / seCi[i];
                if (dev > 0.25) offc.add(studyRaw[i]);
            }
            if (!outside.isEmpty())
                warn.add("r lies outside its own confidence interval [lbound, ubound] for study/studies: "
                    + String.join(", ", outside) + ". Check the columns; the standard errors are still derived from the interval width.");
            if (!offc.isEmpty())
                warn.add("The confidence interval of study/studies " + String.join(", ", offc) + " is not centred on "
                    + "Fisher's z of r (the midpoint differs by more than 0.25 standard errors), so it does not look "
                    + "like a Fisher-z interval; the standard error taken from its width is only approximate. "
                    + "Consider se_source = \"n\".");
        } else {
            System.arraycopy(seN, 0, se, 0, nrow);
        }
        for (double v : se) if (!finite(v) || v <= 0)
            throw new TsaException("The standard error of Fisher's z must be finite and strictly positive for every study.");

        // ---- target_r --------------------------------------------------------------
        double targetR = p.targetR;
        if (!Double.isNaN(targetR) && !finite(targetR)) throw new TsaException("target_r must be a single finite numeric value, or NA.");
        if (!Double.isNaN(targetR) && Math.abs(targetR) >= 1) throw new TsaException("target_r must lie strictly between -1 and 1.");
        if (!Double.isNaN(targetR) && targetR == 0)
            throw new TsaException("target_r cannot equal 0: atanh(0)=0 makes the required information infinite.");
        if (!Double.isNaN(targetR) && Math.abs(targetR) < 0.10)
            warn.add("target_r (" + targetR + ") is very close to the null value of 0; the required information size "
                + "increases rapidly as atanh(target_r) approaches zero. Confirm that this represents a meaningful target correlation.");

        // ---- ordering ---------------------------------------------------------------
        Integer[] perm = new Integer[nrow];
        for (int i = 0; i < nrow; i++) perm[i] = i;
        if (orderBy != null) {
            int oc = ds.col(orderBy);
            if (oc < 0) throw new TsaException("order_by = '" + orderBy + "' is not a column in data.");
            String[] raw = ds.column(oc);
            boolean numericCol = ds.isNumericColumn(oc);
            int nNa = 0;
            for (String s : raw) if (DataSet.isBlank(s)) nNa++;
            if (nNa > 0) warn.add("order_by column '" + orderBy + "' contains NA values; those rows will sort to the end.");
            if (!numericCol)
                warn.add("order_by column '" + orderBy + "' is not numeric; sorting will use lexical ordering of its text, "
                    + "which may not reflect chronological order unless e.g. formatted as 'YYYY-MM-DD'.");
            Set<String> seen = new HashSet<>();
            boolean ties = false;
            for (String s : raw) if (!DataSet.isBlank(s) && !seen.add(s.trim())) ties = true;
            if (ties)
                warn.add("order_by column '" + orderBy + "' contains tied values; TSA is order-dependent, so the relative "
                    + "order of tied studies (broken by a stable sort, i.e. their original row order among ties) may "
                    + "affect the cumulative TSA. Consider a finer-grained order_by column (e.g. publication date "
                    + "instead of year alone) if the exact ordering of tied studies matters.");
            final double[] num = numericCol ? ds.numericColumn(oc) : null;
            final String[] fraw = raw;
            Arrays.sort(perm, (a, b) -> {
                boolean na = DataSet.isBlank(fraw[a]), nb = DataSet.isBlank(fraw[b]);
                if (na || nb) return na == nb ? 0 : (na ? 1 : -1);
                return num != null ? Double.compare(num[a], num[b]) : fraw[a].trim().compareTo(fraw[b].trim());
            });
            if (verbose) log.append("Studies sorted by '").append(orderBy).append("' (ascending) for the cumulative analysis.\n");
        } else if (verbose) {
            log.append("Study order used for sequential analysis (assumed chronological -- choose an 'order by' column to sort explicitly):\n");
        }
        R.study = new String[nrow];
        R.r = new double[nrow]; R.nSubjects = new double[nrow]; R.zFisher = new double[nrow];
        R.seZn = new double[nrow]; R.seZci = new double[nrow]; R.seZ = new double[nrow];
        R.lbound = new double[nrow]; R.ubound = new double[nrow];
        List<String[]> newRows = new ArrayList<>();
        for (int i = 0; i < nrow; i++) {
            int s = perm[i];
            R.study[i] = studyRaw[s].trim();
            R.r[i] = r[s]; R.nSubjects[i] = n[s]; R.zFisher[i] = z[s]; R.seZn[i] = seN[s]; R.seZci[i] = seCi[s]; R.seZ[i] = se[s];
            R.lbound[i] = lb != null ? lb[s] : Double.NaN; R.ubound[i] = ub != null ? ub[s] : Double.NaN;
            newRows.add(ds.rows.get(s));
        }
        R.data = new DataSet(ds.names, newRows);
        if (verbose) {
            log.append(Arrays.toString(R.study)).append("\n\n");
            log.append(f("Loaded %d studies (%s; Fisher z scale). Total participants: %.0f\n", nrow, corLabel, Stats.sum(R.nSubjects)));
            log.append("Standard errors of Fisher's z taken from: ")
               .append(seSource.equals("ci") ? f("the reported %.0f%% confidence intervals", ciLevel * 100) : "the sample sizes (n_subjects)")
               .append("\n");
            if (ciColsOk) {
                double[] ratio = new double[nrow];
                double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < nrow; i++) { ratio[i] = R.seZci[i] / R.seZn[i]; mn = Math.min(mn, ratio[i]); mx = Math.max(mx, ratio[i]); }
                log.append(f("Median ratio of CI-based to n-based SE(z): %.3f (range %.3f-%.3f)\n", Stats.median(ratio), mn, mx));
            }
            log.append("\n");
        }

        // ---- 2. conventional meta-analysis --------------------------------------------
        double[] yi = R.zFisher, sei = R.seZ;
        Rma resStd = Rma.fit(yi, sei, method);
        Rma resFe = Rma.fit(yi, sei, "FE");
        Rma resRe;
        if (reInference.equals("standard")) {
            resRe = resStd;
        } else {
            double qPooled = Rma.hksjQ(yi, sei, resStd.tau2);
            if (!finite(qPooled) || qPooled <= 0) {
                warn.add("re_inference = \"" + reInferenceRequested + "\" needs a positive, finite Hartung-Knapp scale "
                    + "factor, but it is " + qPooled + " for these data (e.g. identical study estimates); falling back "
                    + "to re_inference = \"standard\".");
                reInference = "standard";
                resRe = resStd;
            } else {
                resRe = Rma.fit(yi, sei, method);
                resRe.applyHksj(yi, sei, reInference.equals("hksj_adhoc"));
            }
        }
        if (!reInference.equals("standard"))
            warn.add("HKSJ inference (re_inference = \"" + reInferenceRequested + "\") can be unstable at early cumulative "
                + "looks because the degrees of freedom are k-1: the HKSJ statistic is undefined at k=1 (\"NA\" at the first "
                + "look) and is based on only one degree of freedom at k=2. Early cumulative HKSJ values should not be "
                + "interpreted as directly comparable in magnitude with conventional normal Z-statistics.");
        double pooledZ = resRe.b, pooledR = Math.tanh(pooledZ), pooledLb = Math.tanh(resRe.ciLb), pooledUb = Math.tanh(resRe.ciUb);
        if (verbose) {
            if (reInference.equals("standard"))
                log.append(f("=== Random-effects (%s) meta-analysis of Fisher's z ===\n", methodLabel(method)));
            else
                log.append(f("=== Random-effects (%s) meta-analysis of Fisher's z; inference: %s ===\n", methodLabel(method), reInferenceLabel(reInference)));
            log.append(f("Random-Effects Model (k = %d; tau^2 estimator: %s)\n", nrow, method));
            log.append(f("tau^2 (estimated amount of total heterogeneity): %.4f\n", resStd.tau2));
            log.append(f("I^2 (total heterogeneity / total variability): %.2f%%\n", resStd.I2));
            log.append(f("Test for heterogeneity: Q(df = %d) = %.4f, p-val = %s\n", nrow - 1, resStd.QE, fmtP(resStd.QEp)));
            log.append(f("Model results (Fisher z): estimate = %.4f, se = %.4f, %s = %.4f, p-val = %s, ci.lb = %.4f, ci.ub = %.4f\n",
                resRe.b, resRe.se, reInference.equals("standard") ? "zval" : "tval", resRe.zval, fmtP(resRe.pval), resRe.ciLb, resRe.ciUb));
            log.append(f("\nPooled %s (random effects, back-transformed): %.3f  95%% CI: %.3f-%.3f\n\n", corLabel, pooledR, pooledLb, pooledUb));
        }

        // ---- 3. heterogeneity / diversity ---------------------------------------------
        double Q = resStd.QE; int dfQ = resStd.k - 1; double I2 = resStd.I2, tau2 = resStd.tau2;
        double varRandom = resStd.vb, varFixed = resFe.vb;
        double D2raw = Math.max(0.0, (varRandom - varFixed) / varRandom);
        boolean capped = D2raw >= 0.999;
        double D2 = capped ? 0.999 : D2raw;
        if (capped)
            warn.add("Diversity D2 is at or very near its theoretical upper bound (100%), indicating extreme heterogeneity "
                + "relative to the number of studies. D2 has been capped at 99.9% to avoid a numerically unstable/explosive "
                + "heterogeneity adjustment factor; interpret the required information size and DARIS with caution in this scenario.");
        double AF = 1.0 / (1.0 - D2);
        if (verbose) {
            log.append("=== Heterogeneity ===\n");
            log.append(f("Q = %.2f (df = %d), p = %.4f\n", Q, dfQ, resStd.QEp));
            log.append(f("I^2 = %.1f%%   tau^2 = %.4f (Fisher z scale)\n", I2, tau2));
            log.append(f("Diversity D^2 = %.1f%%   Adjustment factor (1/(1-D2)) = %.3f\n\n", D2 * 100, AF));
        }

        // ---- 4. required information size ------------------------------------------------
        double zAlpha = Stats.qnorm(1 - alpha / 2), zBeta = Stats.qnorm(power);
        double rAnticipated, zAnticipated;
        String effectSource;
        if (Double.isNaN(targetR)) {
            rAnticipated = pooledR; zAnticipated = pooledZ;
            effectSource = "OBSERVED pooled correlation (random-effects model) -- see circularity caution";
            warn.add("target_r was not specified: the OBSERVED pooled correlation from this meta-analysis is being used to "
                + "calculate the required information size. This is circular and is appropriate for exploratory use only -- "
                + "for a publication-quality TSA, set target_r to a pre-specified, meaningful correlation, e.g. target_r = 0.20.");
        } else {
            rAnticipated = targetR; zAnticipated = atanh(targetR);
            effectSource = "user-specified target_r (pre-specified)";
        }
        if (!finite(zAnticipated) || zAnticipated == 0)
            throw new TsaException("The anticipated correlation is exactly 0 (the observed pooled correlation is 0, or "
                + "target_r = 0), which makes the required information infinite. Specify a non-zero target_r.");
        double varFactor = varianceFactor(cor, sv, rAnticipated);
        double infoRequired = (zAlpha + zBeta) * (zAlpha + zBeta) / (zAnticipated * zAnticipated);
        double risParticipants = varFactor * infoRequired + 3;
        double darisInfo = infoRequired * AF;
        double darisParticipants = varFactor * darisInfo + 3;
        double participantsAccrued = Stats.sum(R.nSubjects);
        if (verbose) {
            log.append("=== Required Information Size (Fisher z scale) ===\n");
            log.append("Effect size source: ").append(effectSource).append("\n");
            log.append(f("Anticipated %s (used for RIS calculation): %.3f (Fisher z = %.4f)\n", corLabel, rAnticipated, zAnticipated));
            log.append(f("alpha (2-sided) = %.3f, power = %.0f%%, variance factor c = %.3f\n", alpha, power * 100, varFactor));
            log.append(f("Required statistical information (inverse-variance units): %.4f\n", infoRequired));
            log.append(f("Required sample size (RIS, no heterogeneity adj.; single-study equivalent, c*I + 3): %.0f\n", Math.ceil(risParticipants)));
            log.append(f("Diversity-Adjusted Required Information (DARIS, information units): %.4f\n", darisInfo));
            log.append(f("DARIS translated to an equivalent number of participants (c*DARIS + 3): %.0f\n\n", Math.ceil(darisParticipants)));
            log.append(f("Total participants accrued across included studies: %.0f (%.1f%% of DARIS participant-equivalent)\n\n",
                participantsAccrued, 100 * participantsAccrued / darisParticipants));
        }
        boolean circWarn = Double.isNaN(targetR);
        boolean circSevere = circWarn && participantsAccrued / darisParticipants > 3;
        if (verbose && circSevere) {
            log.append("*** NOTE: accrued participants greatly exceed the DARIS because the RIS was calculated from the\n"
                + "    observed (very large, very precise) pooled effect. This is circular and will make the TSA boundary\n"
                + "    collapse almost immediately to the conventional boundary. Consider re-running with a pre-specified\n"
                + "    'target_r' for a more standard, protocol-driven TSA. ***\n\n");
        } else if (verbose && circWarn) {
            log.append("*** NOTE: 'target_r' was not specified, so the required information size was calculated from the\n"
                + "    OBSERVED pooled effect. This is circular: set a pre-specified 'target_r' for a standard,\n"
                + "    protocol-driven TSA. ***\n\n");
        }

        // ---- 5. cumulative meta-analysis -----------------------------------------------------
        double[] cEst = new double[nrow], cSe = new double[nrow], cZv = new double[nrow], cP = new double[nrow],
            cLb = new double[nrow], cUb = new double[nrow], cTau = new double[nrow], cZ = new double[nrow];
        for (int i = 0; i < nrow; i++) {
            Rma ri = Rma.fit(Arrays.copyOf(yi, i + 1), Arrays.copyOf(sei, i + 1), method);
            cEst[i] = ri.b; cSe[i] = ri.se; cZv[i] = ri.zval; cP[i] = ri.pval; cLb[i] = ri.ciLb; cUb[i] = ri.ciUb; cTau[i] = ri.tau2;
            cZ[i] = ri.b / ri.se;
        }
        if (!reInference.equals("standard")) {
            boolean adhoc = reInference.equals("hksj_adhoc");
            for (int i = 1; i < nrow; i++) {
                int kk = i + 1;
                double[] y = Arrays.copyOf(yi, kk), s = Arrays.copyOf(sei, kk);
                double q = Rma.hksjQ(y, s, cTau[i]);
                if (!finite(q) || q <= 0) continue;
                double scale = adhoc ? Math.max(1.0, q) : q;
                double sw = 0;
                for (int j = 0; j < kk; j++) sw += 1.0 / (s[j] * s[j] + cTau[i]);
                double est = cEst[i], seI = Math.sqrt(scale / sw);
                int dfI = kk - 1;
                double tI = est / seI;
                double lp1 = Stats.logUpperT(Math.abs(tI), dfI);
                double crit = Stats.qtUpper(0.025, dfI);
                cSe[i] = seI; cZv[i] = tI; cP[i] = Math.min(1.0, 2 * Math.exp(lp1));
                cLb[i] = est - crit * seI; cUb[i] = est + crit * seI;
                cZ[i] = Math.signum(est) * Stats.qnormUpperLog(lp1);
            }
            cZ[0] = Double.NaN;
        }
        R.cumEstimate = cEst; R.cumSe = cSe; R.cumZval = cZv; R.cumPval = cP; R.cumCiLb = cLb; R.cumCiUb = cUb;
        R.cumTau2 = cTau; R.cumZ = cZ;
        R.cumR = new double[nrow]; R.cumRLb = new double[nrow]; R.cumRUb = new double[nrow];
        R.cumN = new double[nrow]; R.infoAccrued = new double[nrow]; R.infoFraction = new double[nrow];
        R.infoFractionParticipants = new double[nrow];
        double cn = 0, ci = 0;
        for (int i = 0; i < nrow; i++) {
            R.cumR[i] = Math.tanh(cEst[i]); R.cumRLb[i] = Math.tanh(cLb[i]); R.cumRUb[i] = Math.tanh(cUb[i]);
            cn += R.nSubjects[i]; ci += 1.0 / (sei[i] * sei[i]);
            R.cumN[i] = cn; R.infoAccrued[i] = ci;
            R.infoFraction[i] = ci / darisInfo;
            R.infoFractionParticipants[i] = cn / darisParticipants;
        }
        if (verbose) {
            log.append(f("%-14s %9s %8s %8s %8s %8s %9s %9s\n", "Study", "estimate", "se", "Z", "r", "cum_n", "info_frac", "part_frac"));
            for (int i = 0; i < nrow; i++)
                log.append(f("%-14s %9.4f %8.4f %8s %8.4f %8.0f %9.4f %9.4f\n", R.study[i], cEst[i], cSe[i],
                    Double.isNaN(cZ[i]) ? "NA" : f("%.4f", cZ[i]), R.cumR[i], R.cumN[i], R.infoFraction[i], R.infoFractionParticipants[i]));
            log.append("\n*** IMPORTANT CAVEAT: the cumulative Z-curve above is from a RANDOM-EFFECTS model, whose between-study\n"
                + "    variance (tau^2) is RE-ESTIMATED at every step. The Lan-DeMets/O'Brien-Fleming monitoring boundaries\n"
                + "    strictly assume a fixed, CANONICAL information process with independent Brownian-motion increments.\n"
                + "    Applying them here is a widely-used APPROXIMATION (as in the official Copenhagen Trial Unit TSA\n"
                + "    software), not an exact result. ***\n\n");
        }

        // ---- 6. boundaries -------------------------------------------------------------------
        double[] infoFracs = R.infoFraction;
        TreeSet<Double> ts = new TreeSet<>();
        for (double v : infoFracs) if (v < 1) ts.add(v);
        ts.add(1.0);
        double[] timingDesign = new double[ts.size()];
        { int j = 0; for (double v : ts) timingDesign[j++] = v; }

        boolean fallbackUsed = false;
        String fallbackRoute = "none", fallbackReason = null, routeUsed = route;
        RtsaEngine.Bounds des;
        try {
            des = RtsaEngine.designBounds(timingDesign, alpha, 1 - power, warn);
        } catch (RuntimeException e) {
            throw new TsaException("The RTSA-derived boundary engine failed (" + e.getMessage() + "). The Java application "
                + "does not include the legacy approximate fallback engine of the R package, so it stops instead of "
                + "substituting a non-RTSA-comparable result. Check the design (information fractions, alpha, power); "
                + "merging near-simultaneous studies or changing alpha/power/target_r may help.");
        }
        double[] alphaBoundsDesign, betaBoundsDesign, boundaryTiming;
        double routeEndpoint;
        double designRoot = des.root;
        int rmBsUsed = des.rmBs;
        String engine;
        if (route.equals("design")) {
            alphaBoundsDesign = des.alphaUbound;
            double[] bpre = new double[0];
            List<Double> bl = new ArrayList<>();
            for (int i = 0; i < timingDesign.length; i++) if (timingDesign[i] < 1) bl.add(des.betaUbound[i]);
            double[] beta = new double[bl.size() + 1];
            for (int i = 0; i < bl.size(); i++) beta[i] = bl.get(i);
            beta[beta.length - 1] = alphaBoundsDesign[alphaBoundsDesign.length - 1];
            betaBoundsDesign = beta;
            routeEndpoint = 1.0;
            boundaryTiming = timingDesign;
            engine = "rtsa_design";
        } else {
            double mx = Double.NEGATIVE_INFINITY;
            for (double v : infoFracs) mx = Math.max(mx, v);
            List<Double> tl = new ArrayList<>();
            if (mx < designRoot) { for (double v : infoFracs) tl.add(v); tl.add(designRoot); }
            else if (mx > designRoot) { for (double v : infoFracs) if (v < designRoot) tl.add(v); tl.add(designRoot); }
            else for (double v : infoFracs) tl.add(v);
            double[] tExt = new double[tl.size()];
            for (int i = 0; i < tExt.length; i++) tExt[i] = tl.get(i);
            RtsaEngine.Bounds ana = null;
            String anaErr = null;
            try {
                ana = RtsaEngine.analysisBounds(tExt, designRoot, alpha, 1 - power, warn);
            } catch (RuntimeException e) {
                anaErr = e.getMessage();
            }
            if (ana == null) {
                String msg = "*** WARNING: THE RTSA ANALYSIS-ROUTE ENGINE FAILED (" + anaErr + "). boundary_route = \"analysis\" "
                    + "was requested, but the result shown is the \"design\"-route result instead (still the RTSA-derived "
                    + "engine, just the other route). ***";
                if (!p.fallbackToDesign)
                    throw new TsaException("The RTSA analysis-route boundary engine failed (" + anaErr + ") and the fallback to the "
                        + "design route is disabled. Enable the fallback, choose the design route, or address the underlying issue.");
                warn.add(msg);
                if (verbose) log.append("\n").append(msg).append("\n\n");
                alphaBoundsDesign = des.alphaUbound;
                List<Double> bl = new ArrayList<>();
                for (int i = 0; i < timingDesign.length; i++) if (timingDesign[i] < 1) bl.add(des.betaUbound[i]);
                double[] beta = new double[bl.size() + 1];
                for (int i = 0; i < bl.size(); i++) beta[i] = bl.get(i);
                beta[beta.length - 1] = alphaBoundsDesign[alphaBoundsDesign.length - 1];
                betaBoundsDesign = beta;
                routeEndpoint = 1.0; boundaryTiming = timingDesign;
                fallbackUsed = true; fallbackRoute = "design"; fallbackReason = anaErr; routeUsed = "design";
                engine = "rtsa_design";
            } else {
                routeEndpoint = designRoot;
                boundaryTiming = ana.timing;
                alphaBoundsDesign = ana.alphaUbound;
                betaBoundsDesign = ana.betaUbound;
                rmBsUsed = ana.rmBs;
                engine = "rtsa_analysis";
            }
        }

        // ---- 6b. reconcile participants-scale DARIS with the observed-information verdict -------
        double maxFrac = Double.NEGATIVE_INFINITY;
        for (double v : infoFracs) maxFrac = Math.max(maxFrac, v);
        boolean finalReached = maxFrac >= routeEndpoint;
        boolean darisReached = maxFrac >= 1;
        boolean analysisEndpoint = routeUsed.equals("analysis");
        double routeEndpointInfo = routeEndpoint * darisInfo;
        double routeEndpointPartsTheo = varFactor * routeEndpointInfo + 3;
        double darisInfoThresholdN = RtsaEngine.participantsAtFraction(infoFracs, R.cumN, 1.0);
        double routeEndpointNEst = routeEndpoint == 1.0 ? darisInfoThresholdN
            : RtsaEngine.participantsAtFraction(infoFracs, R.cumN, routeEndpoint);
        String endpointName = analysisEndpoint ? f("analysis-route endpoint (%.3f x DARIS)", routeEndpoint) : "DARIS";

        R.boundaryUpper = new double[nrow]; R.boundaryLower = new double[nrow];
        R.futilityUpper = new double[nrow]; R.futilityLower = new double[nrow];
        Arrays.fill(R.boundaryUpper, Double.NaN); Arrays.fill(R.boundaryLower, Double.NaN);
        Arrays.fill(R.futilityUpper, Double.NaN); Arrays.fill(R.futilityLower, Double.NaN);
        for (int i = 0; i < nrow; i++) {
            if (infoFracs[i] < routeEndpoint) {
                int m = matchIndex(boundaryTiming, infoFracs[i]);
                if (m >= 0) {
                    R.boundaryUpper[i] = alphaBoundsDesign[m]; R.boundaryLower[i] = -alphaBoundsDesign[m];
                    R.futilityUpper[i] = betaBoundsDesign[m]; R.futilityLower[i] = -betaBoundsDesign[m];
                }
            }
        }
        double boundaryEndpointN = (finalReached && finite(routeEndpointNEst)) ? routeEndpointNEst : routeEndpointPartsTheo;
        int nb = boundaryTiming.length;
        R.btInfoFraction = boundaryTiming.clone();
        R.btCumN = new double[nb];
        R.btUpper = new double[nb]; R.btLower = new double[nb]; R.btFutUpper = new double[nb]; R.btFutLower = new double[nb];
        R.btSynthetic = new boolean[nb];
        {
            int j = 0;
            for (int i = 0; i < nb; i++) if (boundaryTiming[i] < routeEndpoint) {
                int idx = matchIndex(infoFracs, boundaryTiming[i]);
                R.btCumN[j++] = idx >= 0 ? R.cumN[idx] : Double.NaN;
            }
            if (j < nb) R.btCumN[j] = boundaryEndpointN;
        }
        for (int i = 0; i < nb; i++) {
            R.btUpper[i] = alphaBoundsDesign[i]; R.btLower[i] = -alphaBoundsDesign[i];
            R.btFutUpper[i] = betaBoundsDesign[i]; R.btFutLower[i] = -betaBoundsDesign[i];
            R.btSynthetic[i] = boundaryTiming[i] == routeEndpoint;
        }
        if (verbose) {
            log.append("=== Trial sequential monitoring boundaries (alpha/beta spending) ===\n");
            if (analysisEndpoint)
                log.append(f("Formal final boundary endpoint (%s = %.4f information units): %.1f cumulative participants\n", endpointName, routeEndpointInfo, boundaryEndpointN));
            else
                log.append(f("Formal final boundary endpoint (DARIS information reached): %.1f cumulative participants\n", boundaryEndpointN));
            log.append(f("%-14s %9s %9s %9s %9s\n", "Study", "info_frac", "Z", "alpha_ub", "futility_ub"));
            for (int i = 0; i < nrow; i++)
                log.append(f("%-14s %9.4f %9s %9s %9s\n", R.study[i], infoFracs[i], Double.isNaN(cZ[i]) ? "NA" : f("%.4f", cZ[i]),
                    Double.isNaN(R.boundaryUpper[i]) ? "NA" : f("%.4f", R.boundaryUpper[i]),
                    Double.isNaN(R.futilityUpper[i]) ? "NA" : f("%.4f", R.futilityUpper[i])));
            log.append("\n");
        }

        // ---- 7c. decision logic ---------------------------------------------------------------
        int finalTsaLook = nrow;   // 1-based
        if (finalReached) for (int i = 0; i < nrow; i++) if (infoFracs[i] >= routeEndpoint) { finalTsaLook = i + 1; break; }
        double[] decUpper = new double[finalTsaLook], decFut = new double[finalTsaLook];
        for (int i = 0; i < finalTsaLook; i++) {
            int m = matchIndex(R.btInfoFraction, Math.min(infoFracs[i], routeEndpoint));
            decUpper[i] = m >= 0 ? R.btUpper[m] : Double.NaN;
            decFut[i] = m >= 0 ? R.btFutUpper[m] : Double.NaN;
        }
        boolean crossedTsa = false, enteredFut = false, crossedConv = false;
        for (int i = 0; i < finalTsaLook; i++) {
            if (Math.abs(cZ[i]) >= decUpper[i]) crossedTsa = true;
            if (Math.abs(cZ[i]) <= decFut[i]) enteredFut = true;
        }
        for (int i = 0; i < nrow; i++) if (Math.abs(cZ[i]) >= zAlpha) crossedConv = true;
        Boolean finCross = null, finNon = null, finEnt = null;
        if (finalReached) {
            double zf = Math.abs(cZ[finalTsaLook - 1]);
            double bf = decUpper[finalTsaLook - 1], ff = decFut[finalTsaLook - 1];
            boolean crossed = !(Double.isNaN(bf) || Double.isNaN(zf)) && zf >= bf;
            finCross = Double.isNaN(bf) || Double.isNaN(zf) ? null : (Boolean) (zf >= bf);
            finEnt = Double.isNaN(ff) || Double.isNaN(zf) ? null : (Boolean) (zf <= ff);
            finNon = finCross == null ? null : (Boolean) (!crossed);
        }

        // ---- 6d. projection ------------------------------------------------------------------
        double infoAccruedFinal = R.infoAccrued[nrow - 1];
        double[] perInfo = new double[nrow], perIpp = new double[nrow];
        for (int i = 0; i < nrow; i++) { perInfo[i] = 1.0 / (sei[i] * sei[i]); perIpp[i] = perInfo[i] / R.nSubjects[i]; }
        boolean useMean = projStat.equals("mean");
        double centralInfoInc = useMean ? Stats.mean(perInfo) : Stats.median(perInfo);
        double centralPartInc = useMean ? Stats.mean(R.nSubjects) : Stats.median(R.nSubjects);
        double studyIpp = useMean ? Stats.mean(perIpp) : Stats.median(perIpp);
        double pooledIpp = Stats.sum(perInfo) / Stats.sum(R.nSubjects);
        double centralIpp = ippBasis.equals("pooled") ? pooledIpp : studyIpp;
        String ippLabel = ippBasis.equals("pooled") ? "pooled ratio: total information / total participants"
            : projStat + " of the study-level information per participant";
        String ippShort = ippBasis.equals("pooled") ? "pooled" : projStat;
        double iRequired = routeEndpointInfo;
        double addInfoRaw = iRequired - infoAccruedFinal;
        int nAddStudies = -1;
        double addPartsEst = Double.NaN, addPartsStudy = Double.NaN, addPartsPooled = Double.NaN, partsWhole = Double.NaN,
            targetPartsHist = Double.NaN;
        if (!finalReached && finite(addInfoRaw) && addInfoRaw > 0) {
            if (finite(studyIpp) && studyIpp > 0) addPartsStudy = addInfoRaw / studyIpp;
            if (finite(pooledIpp) && pooledIpp > 0) addPartsPooled = addInfoRaw / pooledIpp;
            addPartsEst = ippBasis.equals("pooled") ? addPartsPooled : addPartsStudy;
            if (finite(addPartsEst)) targetPartsHist = participantsAccrued + addPartsEst;
            if (finite(centralInfoInc) && centralInfoInc > 0) {
                nAddStudies = (int) Math.max(1, Math.ceil(addInfoRaw / centralInfoInc));
                if (finite(centralPartInc)) partsWhole = nAddStudies * centralPartInc;
            }
        }
        double addPartsTheo = !finalReached ? Math.max(Math.ceil(routeEndpointPartsTheo - participantsAccrued), 0) : Double.NaN;
        String projectionNote = "Projection assumes future studies contribute information at approximately the observed "
            + "historical rate (" + ippLabel + "); it is not a formal guarantee of the number of future studies or "
            + "participants required. It is a linear extrapolation on the fixed-effect, study-level inverse-variance "
            + "information scale that is compared with DARIS, and it does not model how random-effects weights or the "
            + "between-study variance (\u03c4\u00b2) would change as further studies are added; treat it as indicative only.";

        // ---- fill the result ------------------------------------------------------------------------
        R.params = p; R.corType = cor; R.seSource = seSource; R.spearmanVariance = sv; R.method = method;
        R.reInference = reInference; R.alphaTwoSided = alpha; R.power = power; R.targetR = targetR;
        R.rAnticipated = rAnticipated; R.zAnticipated = zAnticipated; R.varFactor = varFactor;
        R.resRe = resRe; R.resStd = resStd; R.resFe = resFe;
        R.pooledZ = pooledZ; R.pooledR = pooledR; R.pooledLb = pooledLb; R.pooledUb = pooledUb; R.pooledPval = resRe.pval;
        R.Q = Q; R.QEp = resStd.QEp; R.dfQ = dfQ; R.I2 = I2; R.tau2 = tau2; R.D2 = D2; R.D2raw = D2raw; R.D2capped = capped; R.AF = AF;
        R.zAlpha = zAlpha; R.zBeta = zBeta; R.infoRequired = infoRequired; R.risParticipants = risParticipants;
        R.darisInfo = darisInfo; R.darisParticipants = darisParticipants; R.darisInfoThresholdN = darisInfoThresholdN;
        R.routeEndpointInfo = routeEndpointInfo; R.routeEndpointN = routeEndpointNEst;
        R.routeEndpointParticipantsTheoretical = routeEndpointPartsTheo;
        R.circularityWarning = circWarn; R.circularitySevere = circSevere;
        R.designRoot = designRoot; R.rmBs = rmBsUsed; R.engine = engine;
        R.crossedConventional = crossedConv; R.crossedTsa = crossedTsa; R.enteredFutilityRegion = enteredFut;
        R.finalReached = finalReached; R.darisReached = darisReached; R.finalTsaLook = finalTsaLook;
        R.finalCrossedEfficacy = finCross; R.finalNonEfficacy = finNon; R.finalEnteredFutilityRegion = finEnt;
        R.participantsAccrued = participantsAccrued; R.infoAccruedFinal = infoAccruedFinal;
        R.boundaryRoute = route; R.routeUsed = routeUsed; R.routeEndpoint = routeEndpoint;
        R.fallbackUsed = fallbackUsed; R.fallbackRoute = fallbackRoute; R.fallbackReason = fallbackReason;
        R.projectionStat = projStat; R.infoPerParticipantBasis = ippBasis; R.projectionNote = projectionNote;
        R.iRequired = iRequired; R.additionalInfoRequired = !finalReached ? Math.max(addInfoRaw, 0) : Double.NaN;
        R.centralInfoIncrement = centralInfoInc; R.centralParticipantIncrement = centralPartInc;
        R.centralInfoPerParticipant = centralIpp; R.studyLevelInfoPerParticipant = studyIpp; R.pooledInfoPerParticipant = pooledIpp;
        R.additionalParticipantsEstimated = addPartsEst; R.additionalParticipantsStudyLevel = addPartsStudy;
        R.additionalParticipantsPooled = addPartsPooled; R.participantsFromWholeStudies = partsWhole;
        R.targetParticipantsHistoricalRate = targetPartsHist; R.additionalParticipantsTheoretical = addPartsTheo;
        R.nAdditionalStudies = nAddStudies; R.nStudies = nrow;

        if (verbose) appendDecisionLog(log, R, endpointName, analysisEndpoint, finalReached, finalTsaLook, nrow, ippShort, boundaryEndpointN);
        buildSummary(R, ippShort);
        R.log = log.toString();
        return R;
    }

    static String fmtP(double p) {
        if (Double.isNaN(p)) return "NA";
        if (p < 0.0001) return "<.0001";
        return f("%.4f", p);
    }

    // ===================================================================== verbose log
    private static void appendDecisionLog(StringBuilder log, Result R, String endpointName, boolean analysisEndpoint,
                                          boolean finalReached, int finalTsaLook, int nrow, String ippShort, double boundaryEndpointN) {
        String yn = "YES", no = "NO";
        if (finalReached && finalTsaLook < nrow) {
            log.append(f("Note: %s was reached at study #%d of %d ('%s'). Formal TSA\n  boundary-crossing/futility decisions below are "
                + "evaluated only\n  through that look (studies added afterward are still shown in\n  the returned data and plot, but "
                + "are not treated as additional\n  formal '%s' analyses).\n", endpointName, finalTsaLook, nrow,
                R.study[finalTsaLook - 1], analysisEndpoint ? f("t=%.3f", R.routeEndpoint) : "t=1"));
        }
        log.append("Cumulative Z-curve crossed the conventional (P<0.05) boundary: ").append(R.crossedConventional ? yn : no).append("\n");
        log.append("Cumulative Z-curve crossed the TSA monitoring boundary       : ").append(R.crossedTsa ? yn : no).append("\n");
        log.append("Cumulative Z-curve entered the non-binding futility region  : ").append(R.enteredFutilityRegion ? yn : no).append("\n");
        if (finalReached)
            log.append(f("Definitive look (%s) crossed the efficacy boundary: %s\n", analysisEndpoint ? "route endpoint" : "DARIS",
                Boolean.TRUE.equals(R.finalCrossedEfficacy) ? yn : no));
        log.append("Required information size (DARIS) reached                    : ").append(R.darisReached ? yn : no).append("\n");
        if (analysisEndpoint)
            log.append(f("Analysis-route endpoint (%.3f x DARIS) reached               : %s\n", R.routeEndpoint, finalReached ? yn : no));
        log.append(f("  Theoretical DARIS participant-equivalent (c*DARIS + 3)       : %.0f\n", Math.ceil(R.darisParticipants)));
        if (analysisEndpoint)
            log.append(f("  Theoretical participant-equivalent of the analysis-route endpoint (%.3f x DARIS): %.0f\n",
                R.routeEndpoint, Math.ceil(R.routeEndpointParticipantsTheoretical)));
        if (R.darisReached) {
            log.append(f("  Estimated cumulative participants at which DARIS information was reached\n  (interpolated, not an observed look): %.0f\n",
                Math.ceil(R.darisInfoThresholdN)));
            if (Math.abs(R.darisInfoThresholdN - R.darisParticipants) > 0.01 * R.darisParticipants)
                log.append("  (These may differ because the observed study-level information per participant differs from the\n"
                    + "   1/c assumed by the theoretical participant-equivalent, and because that equivalent counts the 3 lost\n"
                    + "   units of information only once -- in either direction, not necessarily because information accrued faster.)\n");
        }
        log.append("\n");
        if (!finalReached) {
            String target = analysisEndpoint ? "analysis-route endpoint" : "DARIS";
            log.append(f("Participants accrued = %.0f\n\n", R.participantsAccrued));
            if (analysisEndpoint) {
                log.append(f("Analysis-route endpoint (%.3f x DARIS = %s information units): %s cumulative participants (theoretical)\n",
                    R.routeEndpoint, f("%,.4f", R.routeEndpointInfo), bigMark(boundaryEndpointN)));
                log.append(f("Additional information required: %s\n", f("%,.3f", R.additionalInfoRequired)));
            }
            log.append(f("Theoretical additional participants to %s (c*I + 3 equivalent): %.0f\n", target, R.additionalParticipantsTheoretical));
            if (finite(R.targetParticipantsHistoricalRate))
                log.append(f("%s: ~%s cumulative participants\n  (%s information units per participant)\n",
                    analysisEndpoint ? "Historical information/participant-rate projection" : "DARIS (historical rate)",
                    bigMark(R.targetParticipantsHistoricalRate), f("%.4f", R.centralInfoPerParticipant)));
            if (Double.isNaN(R.additionalParticipantsEstimated))
                log.append(f("Estimated additional participants to %s (historical rate): cannot be estimated\n", target));
            else
                log.append(f("Estimated additional participants to %s (historical rate): ~%s\n", target, bigMark(R.additionalParticipantsEstimated)));
            if (R.nAdditionalStudies < 0)
                log.append("Estimated additional studies required: cannot be estimated\n  (no usable historical per-study information increment to project from)\n");
            else
                log.append(f("Estimated additional studies required: %d\n", R.nAdditionalStudies));
            log.append("Note: ").append(R.projectionNote).append("\n\n");
        }
        log.append("Note: The \u03c4\u00b2 estimator may have limited influence on the pooled average effect-size when the evidence base "
            + "is substantial, but it can materially influence heterogeneity-dependent quantities, prediction intervals, DARIS, and "
            + "the timing of TSA conclusions\u2014particularly when cumulative information is near the DARIS threshold.\n");
    }

    // ===================================================================== summary table
    static String num(double v, int digits) {
        if (Double.isNaN(v)) return "NA";
        java.math.BigDecimal bd = new java.math.BigDecimal(v).setScale(digits, java.math.RoundingMode.HALF_EVEN);
        String s = bd.stripTrailingZeros().toPlainString();
        return s;
    }

    static String numCeil(double v) { return Double.isNaN(v) ? "NA" : Long.toString((long) Math.ceil(v)); }

    static String lgl(Boolean b) { return b == null ? "NA" : (b ? "TRUE" : "FALSE"); }

    private static void buildSummary(Result R, String ippShort) {
        List<String[]> t = new ArrayList<>();
        String cl = R.corLabel();
        boolean analysisEndpoint = R.analysisRoute();
        t.add(new String[]{"Pooled " + cl + " (RE, observed)", num(R.pooledR, 3)});
        t.add(new String[]{"95% CI lower", num(R.pooledLb, 3)});
        t.add(new String[]{"95% CI upper", num(R.pooledUb, 3)});
        if (!R.reInference.equals("standard"))
            t.add(new String[]{"Random-effects inference", reInferenceLabel(R.reInference)});
        t.add(new String[]{"Anticipated " + cl + " (for RIS)", num(R.rAnticipated, 3)});
        t.add(new String[]{"Pooled Fisher z (RE)", num(R.pooledZ, 4)});
        t.add(new String[]{"I2 (%)", num(R.I2, 1)});
        t.add(new String[]{"tau2 (z scale)", num(R.tau2, 4)});
        t.add(new String[]{"Diversity D2 (%)", num(R.D2 * 100, 1)});
        t.add(new String[]{"Adjustment factor", num(R.AF, 3)});
        t.add(new String[]{"Variance factor c", num(R.varFactor, 3)});
        t.add(new String[]{"Required info (inv-var units)", num(R.infoRequired, 4)});
        t.add(new String[]{"RIS, participants (c*I + 3)", numCeil(R.risParticipants)});
        t.add(new String[]{"DARIS (info units)", num(R.darisInfo, 4)});
        t.add(new String[]{"DARIS participant-equiv. (c*DARIS + 3)", numCeil(R.darisParticipants)});
        t.add(new String[]{"Participants accrued", num(R.participantsAccrued, 6)});
        t.add(new String[]{"Info accrued (observed inv-var)", num(R.infoAccruedFinal, 4)});
        t.add(new String[]{"% of DARIS reached", num(100 * R.infoAccruedFinal / R.darisInfo, 1)});
        t.add(new String[]{"Participants at DARIS reached (est.)", numCeil(R.darisInfoThresholdN)});
        if (analysisEndpoint)
            t.add(new String[]{f("Participants at AR endpoint (%.3fxDARIS) reached (est.)", R.routeEndpoint), numCeil(R.routeEndpointN)});
        if (!analysisEndpoint && !R.darisReached) {
            t.add(new String[]{"Add'l participants to DARIS (theoretical)", num(R.additionalParticipantsTheoretical, 6)});
            t.add(new String[]{"DARIS reached, hist. rate (est. participants)", numCeil(R.targetParticipantsHistoricalRate)});
            t.add(new String[]{"Add'l participants to DARIS (hist. rate; " + ippShort + " IPP)", numCeil(R.additionalParticipantsEstimated)});
            t.add(new String[]{"Add'l studies to DARIS (" + R.projectionStat + "-based proj.)", R.nAdditionalStudies < 0 ? "NA" : Integer.toString(R.nAdditionalStudies)});
        } else if (analysisEndpoint && !R.finalReached) {
            t.add(new String[]{f("Add'l info to AR endpoint (%.3fxDARIS)", R.routeEndpoint), num(R.additionalInfoRequired, 4)});
            t.add(new String[]{"Add'l participants to AR endpoint (theoretical)", num(R.additionalParticipantsTheoretical, 6)});
            t.add(new String[]{"AR endpoint reached, hist. rate (est. participants)", numCeil(R.targetParticipantsHistoricalRate)});
            t.add(new String[]{"Add'l participants to AR endpoint (hist. rate; " + ippShort + " IPP)", numCeil(R.additionalParticipantsEstimated)});
            t.add(new String[]{"Add'l studies to AR endpoint (" + R.projectionStat + "-based proj.)", R.nAdditionalStudies < 0 ? "NA" : Integer.toString(R.nAdditionalStudies)});
        }
        t.add(new String[]{"Crossed conventional boundary", lgl(R.crossedConventional)});
        t.add(new String[]{"Crossed TSA boundary (any formal look)", lgl(R.crossedTsa)});
        t.add(new String[]{"Entered futility region (any look; not a stop decision)", lgl(R.enteredFutilityRegion)});
        t.add(new String[]{"Definitive look crossed efficacy (NA if not reached)", lgl(R.finalCrossedEfficacy)});
        t.add(new String[]{"Definitive look: non-efficacy (NA if not reached)", lgl(R.finalNonEfficacy)});
        R.summary = t;
        R.abbreviations.put("RE", "random effects");
        R.abbreviations.put("RIS", "Required Information Size");
        R.abbreviations.put("DARIS", "Diversity-Adjusted RIS");
        R.abbreviations.put("AR endpoint", "analysis-route endpoint");
        R.abbreviations.put("c", "variance factor of Fisher's z (Var(z) = c/(n-3); 1 for Pearson r)");
        R.abbreviations.put("hist. rate", "historical participant/information rate");
        R.abbreviations.put("IPP", "information per participant (pooled: total information / total participants; otherwise the per-study statistic named, e.g. median)");
        R.abbreviations.put("proj.", "projection");
        R.abbreviations.put("inv-var", "inverse-variance");
        R.abbreviations.put("est.", "estimated");
        R.abbreviations.put("Add'l", "Additional");
    }

    // ===================================================================== print / summary text
    public static String printText(Result x) {
        StringBuilder sb = new StringBuilder();
        String cl = x.corLabel();
        sb.append(f("Trial Sequential Analysis (%s correlations, Fisher z)\n", cl));
        sb.append("----------------------------------------------------------\n");
        sb.append(f("Studies: %d | Participants accrued: %.0f\n", x.nStudies, x.participantsAccrued));
        sb.append(f("Pooled %s (random effects): %.3f [95%% CI: %.3f, %.3f]\n", cl, x.pooledR, x.pooledLb, x.pooledUb));
        if (!x.reInference.equals("standard")) sb.append("Random-effects inference: ").append(reInferenceLabel(x.reInference)).append("\n");
        sb.append(f("Anticipated %s (RIS calc): %.3f\n", cl, x.rAnticipated));
        sb.append(f("Theoretical DARIS participant-equivalent: %.0f\n", Math.ceil(x.darisParticipants)));
        sb.append(f("Crossed TSA boundary: %s | Entered futility region: %s | DARIS information reached: %s\n",
            x.crossedTsa ? "YES" : "NO", x.enteredFutilityRegion ? "YES" : "NO", x.darisReached ? "YES" : "NO"));
        if (x.analysisRoute())
            sb.append(f("Boundary route: RTSA analysis | Analysis-route endpoint (%.3f x DARIS) reached: %s\n", x.routeEndpoint, x.finalReached ? "YES" : "NO"));
        if (x.finalReached)
            sb.append(f("Definitive look crossed the efficacy boundary: %s\n", Boolean.TRUE.equals(x.finalCrossedEfficacy) ? "YES" : "NO"));
        if (x.fallbackRoute.equals("design"))
            sb.append("\n*** NOTE: boundary_route = \"analysis\" FAILED; the results shown are the\n    DESIGN-route (RTSA-derived) result -- reason: ")
              .append(x.fallbackReason).append(" ***\n");
        return sb.toString();
    }

    public static String summaryText(Result x) {
        int w = 0;
        for (String[] row : x.summary) w = Math.max(w, row[0].length());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-" + w + "s  %s\n", "Parameter", "Value"));
        for (String[] row : x.summary) sb.append(String.format(Locale.ROOT, "%-" + w + "s  %s\n", row[0], row[1]));
        sb.append("\nAbbreviations: ");
        boolean first = true;
        for (Map.Entry<String, String> e : x.abbreviations.entrySet()) {
            if (!first) sb.append("; ");
            sb.append(e.getKey()).append(" = ").append(e.getValue());
            first = false;
        }
        sb.append(".\n");
        if (x.fallbackRoute.equals("design"))
            sb.append("\n*** NOTE: boundary_route = \"analysis\" FAILED; the results shown are the\n    DESIGN-route (RTSA-derived) result -- reason: ")
              .append(x.fallbackReason).append(" ***\n");
        if (x.circularityWarning) {
            sb.append("\nNOTE: target_r was not specified, so the observed pooled correlation was used\nfor the required information size. This is circular.\n");
            if (x.circularitySevere)
                sb.append("Accrued participants also greatly exceed the resulting DARIS, so the TSA\nboundary will collapse to the conventional boundary almost immediately.\n");
        }
        return sb.toString();
    }
}
