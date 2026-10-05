/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 */
package org.tsacor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A rectangular table of strings (header + rows): what an .xlsx / .csv file holds. */
public final class DataSet {
    public final List<String> names = new ArrayList<>();
    public final List<String[]> rows = new ArrayList<>();

    public DataSet() {}

    public DataSet(List<String> names, List<String[]> rows) {
        this.names.addAll(names);
        for (String[] r : rows) this.rows.add(r.clone());
    }

    public DataSet copy() { return new DataSet(names, rows); }

    /**
     * The data in the order the analysis uses when the studies are sorted ascending by the named column
     * (numeric order for a numeric column, text order otherwise; blank cells last; ties keep their file order).
     * An unknown / null column name returns an unchanged copy.
     */
    public DataSet sortedBy(String colName) {
        int c = colName == null ? -1 : col(colName);
        if (c < 0) return copy();
        final String[] raw = column(c);
        final double[] num = isNumericColumn(c) ? numericColumn(c) : null;
        Integer[] perm = new Integer[nrow()];
        for (int i = 0; i < perm.length; i++) perm[i] = i;
        Arrays.sort(perm, (a, b) -> {
            boolean na = isBlank(raw[a]), nb = isBlank(raw[b]);
            if (na || nb) return na == nb ? 0 : (na ? 1 : -1);
            return num != null ? Double.compare(num[a], num[b]) : raw[a].trim().compareTo(raw[b].trim());
        });
        DataSet out = new DataSet();
        out.names.addAll(names);
        for (int i : perm) out.rows.add(rows.get(i).clone());
        return out;
    }

    public int nrow() { return rows.size(); }
    public int ncol() { return names.size(); }

    public int col(String name) {
        for (int i = 0; i < names.size(); i++) if (names.get(i).equals(name)) return i;
        return -1;
    }

    public String[] column(int c) {
        String[] out = new String[rows.size()];
        for (int i = 0; i < out.length; i++) {
            String[] r = rows.get(i);
            out[i] = (c < r.length && r[c] != null) ? r[c] : "";
        }
        return out;
    }

    static boolean isBlank(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("NA") || t.equalsIgnoreCase("NaN");
    }

    /** Parses a cell; returns NaN for blank/NA; throws NumberFormatException for text. */
    static double parseCell(String s) {
        if (isBlank(s)) return Double.NaN;
        return Double.parseDouble(s.trim());
    }

    /** True when every non-blank cell of the column parses as a number. */
    public boolean isNumericColumn(int c) {
        boolean any = false;
        for (String s : column(c)) {
            if (isBlank(s)) continue;
            try { Double.parseDouble(s.trim()); any = true; } catch (NumberFormatException e) { return false; }
        }
        return any;
    }

    public double[] numericColumn(int c) {
        String[] raw = column(c);
        double[] out = new double[raw.length];
        for (int i = 0; i < out.length; i++) out[i] = parseCell(raw[i]);
        return out;
    }
}
