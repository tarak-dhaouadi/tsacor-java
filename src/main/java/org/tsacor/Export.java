/* tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md. */
package org.tsacor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Export {
    private Export() {}

    public static void summaryCsv(TsaCor.Result r, File f) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(DataLoader.csvLine(Arrays.asList("Parameter", "Value")));
            for (String[] row : r.summary) w.write(DataLoader.csvLine(Arrays.asList(row)));
        }
    }

    static String d(double v) { return Double.isNaN(v) ? "NA" : Double.toString(v); }

    public static final String[] CUM_HEADER = {"Study", "cum_n", "estimate", "se", "Z", "pval", "ci.lb", "ci.ub", "tau2", "r_estimate",
        "r_ci_lb", "r_ci_ub", "info_accrued", "info_fraction", "info_fraction_participants", "TSA_boundary_upper",
        "TSA_boundary_lower", "TSA_futility_upper", "TSA_futility_lower"};

    public static List<String[]> cumulativeRows(TsaCor.Result r) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < r.nStudies; i++) {
            rows.add(new String[]{r.study[i], d(r.cumN[i]), d(r.cumEstimate[i]), d(r.cumSe[i]), d(r.cumZ[i]), d(r.cumPval[i]),
                d(r.cumCiLb[i]), d(r.cumCiUb[i]), d(r.cumTau2[i]), d(r.cumR[i]), d(r.cumRLb[i]), d(r.cumRUb[i]),
                d(r.infoAccrued[i]), d(r.infoFraction[i]), d(r.infoFractionParticipants[i]), d(r.boundaryUpper[i]),
                d(r.boundaryLower[i]), d(r.futilityUpper[i]), d(r.futilityLower[i])});
        }
        return rows;
    }

    public static void cumulativeCsv(TsaCor.Result r, File f) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(DataLoader.csvLine(Arrays.asList(CUM_HEADER)));
            for (String[] row : cumulativeRows(r)) w.write(DataLoader.csvLine(Arrays.asList(row)));
        }
    }

    public static void boundaryCsv(TsaCor.Result r, File f) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(DataLoader.csvLine(Arrays.asList("info_fraction", "cum_n", "TSA_boundary_upper", "TSA_boundary_lower",
                "TSA_futility_upper", "TSA_futility_lower", "synthetic")));
            for (int i = 0; i < r.btInfoFraction.length; i++)
                w.write(DataLoader.csvLine(Arrays.asList(d(r.btInfoFraction[i]), d(r.btCumN[i]), d(r.btUpper[i]), d(r.btLower[i]),
                    d(r.btFutUpper[i]), d(r.btFutLower[i]), Boolean.toString(r.btSynthetic[i]))));
        }
    }
}
