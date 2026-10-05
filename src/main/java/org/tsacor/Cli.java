/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 * Command-line interface: runs tsa_cor() on a file and writes the chart / tables.
 */
package org.tsacor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Cli {
    private Cli() {}

    static final String USAGE = String.join("\n",
        "tsacor-java " + TsaCor.VERSION + " -- Trial Sequential Analysis for meta-analyses of correlations",
        "",
        "Usage:  java -jar tsacor-java.jar                       (opens the graphical application)",
        "        java -jar tsacor-java.jar --cli <data.xlsx|csv> [options]",
        "",
        "Analysis options (names follow the R function tsa_cor()):",
        "  --alpha <x>               two-sided alpha (default 0.05)",
        "  --power <x>               power (default 0.80)",
        "  --target-r <x>            pre-specified anticipated correlation (recommended; default: observed, circular)",
        "  --cor-type <pearson|spearman>",
        "  --se-source <ci|n>",
        "  --spearman-variance <fieller|bonett_wright>",
        "  --ci-level <x>            level of the reported CIs (default 0.95)",
        "  --method <DL|HE|HS|HSk|SJ|ML|REML|EB|PM|PMM>   tau^2 estimator (default DL)",
        "  --order-by <column>       sort studies ascending by this column (e.g. Year)",
        "  --route <design|analysis> boundary route (default design)",
        "  --no-fallback             fail instead of falling back to the design route",
        "  --projection-stat <median|mean>",
        "  --ipp-basis <per_study|pooled>",
        "  --re-inference <standard|hksj|hksj_adhoc>",
        "",
        "Output options:",
        "  --png <file>              TSA chart as PNG        --dpi <n> (default 300; 150, 300, 600, 1200 ... any value)",
        "  --labels <auto|upper|lower>   DARIS label placement (default auto: lower when the Z-curve is positive)",
        "  --no-legend  --no-caption  --no-theoretical-daris  --no-historical-daris  --xmax-mult <x>",
        "  --summary-csv <file>      summary table           --cumulative-csv <file>   cumulative results",
        "  --report <file>           full text report (log + summary)",
        "  --quiet                   do not print the report to the console",
        "  --example                 use the bundled example data (20 studies) instead of a file",
        "  -h, --help, --version");

    public static int run(String[] args) {
        try {
            Deque<String> q = new ArrayDeque<>(Arrays.asList(args));
            TsaCor.Params p = new TsaCor.Params();
            TsaPlot.Options po = new TsaPlot.Options();
            String file = null, png = null, sumCsv = null, cumCsv = null, report = null;
            boolean quiet = false, example = false;
            int dpi = 300;
            while (!q.isEmpty()) {
                String a = q.poll();
                switch (a) {
                    case "--cli": break;
                    case "-h": case "--help": System.out.println(USAGE); return 0;
                    case "--version": System.out.println("tsacor-java " + TsaCor.VERSION); return 0;
                    case "--alpha": p.alphaTwoSided = Double.parseDouble(need(q, a)); break;
                    case "--power": p.power = Double.parseDouble(need(q, a)); break;
                    case "--target-r": p.targetR = Double.parseDouble(need(q, a)); break;
                    case "--cor-type": p.corType = need(q, a); break;
                    case "--se-source": p.seSource = need(q, a); break;
                    case "--spearman-variance": p.spearmanVariance = need(q, a); break;
                    case "--ci-level": p.ciLevel = Double.parseDouble(need(q, a)); break;
                    case "--method": p.method = need(q, a); break;
                    case "--order-by": p.orderBy = need(q, a); break;
                    case "--route": p.boundaryRoute = need(q, a); break;
                    case "--no-fallback": p.fallbackToDesign = false; break;
                    case "--projection-stat": p.projectionStat = need(q, a); break;
                    case "--ipp-basis": p.infoPerParticipantBasis = need(q, a); break;
                    case "--re-inference": p.reInference = need(q, a); break;
                    case "--png": png = need(q, a); break;
                    case "--dpi": dpi = Integer.parseInt(need(q, a)); break;
                    case "--labels": po.placement = TsaPlot.Placement.valueOf(need(q, a).toUpperCase(Locale.ROOT)); break;
                    case "--no-legend": po.legend = false; break;
                    case "--no-caption": po.caption = false; break;
                    case "--no-theoretical-daris": po.showTheoreticalDaris = false; break;
                    case "--no-historical-daris": po.showHistoricalDaris = false; break;
                    case "--xmax-mult": po.xmaxMult = Double.parseDouble(need(q, a)); break;
                    case "--summary-csv": sumCsv = need(q, a); break;
                    case "--cumulative-csv": cumCsv = need(q, a); break;
                    case "--report": report = need(q, a); break;
                    case "--quiet": quiet = true; break;
                    case "--example": example = true; break;
                    default:
                        if (a.startsWith("--")) { System.err.println("Unknown option " + a + "\n\n" + USAGE); return 2; }
                        file = a;
                }
            }
            DataSet ds;
            if (example) ds = Examples.load();
            else if (file == null) { System.err.println("No data file given.\n\n" + USAGE); return 2; }
            else ds = DataLoader.load(new File(file));
            p.verbose = true;
            TsaCor.Result r = TsaCor.run(ds, p);
            String text = r.log + "\n" + TsaCor.printText(r) + "\n" + TsaCor.summaryText(r);
            if (!r.warnings.isEmpty()) {
                StringBuilder w = new StringBuilder("\nWarnings:\n");
                for (String s : r.warnings) w.append(" - ").append(s).append("\n");
                text += w;
            }
            if (!quiet) System.out.println(text);
            if (report != null) write(report, text);
            if (png != null) TsaPlot.writePng(r, po, dpi / 100.0, new File(png));
            if (sumCsv != null) Export.summaryCsv(r, new File(sumCsv));
            if (cumCsv != null) Export.cumulativeCsv(r, new File(cumCsv));
            return 0;
        } catch (TsaCor.TsaException | IllegalArgumentException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static String need(Deque<String> q, String opt) {
        if (q.isEmpty()) throw new IllegalArgumentException("Option " + opt + " needs a value.");
        return q.poll();
    }

    private static void write(String path, String text) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) { w.write(text); }
    }
}
