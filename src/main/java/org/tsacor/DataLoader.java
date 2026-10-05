/*
 * tsacor-java -- GPL (>= 2); see COPYRIGHTS.md and LICENSE.md.
 *
 * Reads .xlsx (first sheet; implemented on the JDK's zip + XML parsers, so no
 * Apache POI is needed) and delimited text (.csv / .tsv / .txt) files.
 */
package org.tsacor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public final class DataLoader {
    private DataLoader() {}

    public static DataSet load(File f) throws IOException {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm")) return loadXlsx(f);
        return loadDelimited(f);
    }

    // ------------------------------------------------------------ delimited text
    public static DataSet loadDelimited(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return parseDelimited(text);
    }

    public static DataSet parseDelimited(String text) {
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        char delim = ',';
        int bestCount = count(firstLine, ',');
        for (char c : new char[]{';', '\t'}) {
            int k = count(firstLine, c);
            if (k > bestCount) { bestCount = k; delim = c; }
        }
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else inQuotes = false;
                } else cell.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delim) {
                row.add(cell.toString()); cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                table.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); table.add(row); }
        // drop empty trailing rows
        while (!table.isEmpty() && allBlank(table.get(table.size() - 1))) table.remove(table.size() - 1);
        if (table.isEmpty()) throw new IllegalArgumentException("The file is empty.");
        DataSet ds = new DataSet();
        List<String> header = table.get(0);
        for (String h : header) ds.names.add(h.trim());
        boolean commaDecimal = delim == ';';
        for (int r = 1; r < table.size(); r++) {
            List<String> src = table.get(r);
            if (allBlank(src)) continue;
            String[] out = new String[ds.names.size()];
            for (int c = 0; c < out.length; c++) {
                String v = c < src.size() ? src.get(c).trim() : "";
                if (commaDecimal && v.matches("-?\\d+,\\d+")) v = v.replace(',', '.');
                out[c] = v;
            }
            ds.rows.add(out);
        }
        return ds;
    }

    private static int count(String s, char c) {
        int k = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) k++; return k;
    }

    private static boolean allBlank(List<String> r) {
        for (String s : r) if (s != null && !s.trim().isEmpty()) return false;
        return true;
    }

    public static void saveCsv(DataSet ds, File f) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(csvLine(ds.names));
            for (String[] r : ds.rows) w.write(csvLine(Arrays.asList(r)));
        }
    }

    public static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            String s = cells.get(i) == null ? "" : cells.get(i);
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
            sb.append(s);
        }
        return sb.append('\n').toString();
    }

    // -------------------------------------------------------------------- xlsx
    private static Document parse(ZipFile z, String entry) throws Exception {
        ZipEntry e = z.getEntry(entry);
        if (e == null) return null;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        try (InputStream in = z.getInputStream(e)) { return db.parse(in); }
    }

    public static DataSet loadXlsx(File f) throws IOException {
        try (ZipFile z = new ZipFile(f)) {
            // shared strings
            List<String> shared = new ArrayList<>();
            Document ss = parse(z, "xl/sharedStrings.xml");
            if (ss != null) {
                NodeList sis = ss.getElementsByTagName("si");
                for (int i = 0; i < sis.getLength(); i++) {
                    Element si = (Element) sis.item(i);
                    StringBuilder sb = new StringBuilder();
                    NodeList ts = si.getElementsByTagName("t");
                    for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
                    shared.add(sb.toString());
                }
            }
            // locate the first sheet
            String sheetPath = "xl/worksheets/sheet1.xml";
            Document wb = parse(z, "xl/workbook.xml");
            Document rels = parse(z, "xl/_rels/workbook.xml.rels");
            if (wb != null && rels != null) {
                NodeList sheets = wb.getElementsByTagName("sheet");
                if (sheets.getLength() > 0) {
                    Element s0 = (Element) sheets.item(0);
                    String rid = s0.getAttribute("r:id");
                    NodeList rl = rels.getElementsByTagName("Relationship");
                    for (int i = 0; i < rl.getLength(); i++) {
                        Element rel = (Element) rl.item(i);
                        if (rel.getAttribute("Id").equals(rid)) {
                            String t = rel.getAttribute("Target");
                            sheetPath = t.startsWith("/") ? t.substring(1) : "xl/" + t;
                        }
                    }
                }
            }
            Document sh = parse(z, sheetPath);
            if (sh == null) throw new IOException("Cannot find the first worksheet in " + f.getName());
            NodeList rowEls = sh.getElementsByTagName("row");
            TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
            int maxCol = -1;
            for (int i = 0; i < rowEls.getLength(); i++) {
                Element re = (Element) rowEls.item(i);
                int rIdx = re.hasAttribute("r") ? Integer.parseInt(re.getAttribute("r")) - 1 : i;
                NodeList cells = re.getElementsByTagName("c");
                for (int j = 0; j < cells.getLength(); j++) {
                    Element ce = (Element) cells.item(j);
                    int cIdx = colIndex(ce.getAttribute("r"), j);
                    String type = ce.getAttribute("t");
                    String val = "";
                    NodeList vs = ce.getElementsByTagName("v");
                    if ("inlineStr".equals(type)) {
                        NodeList ts = ce.getElementsByTagName("t");
                        StringBuilder sb = new StringBuilder();
                        for (int q = 0; q < ts.getLength(); q++) sb.append(ts.item(q).getTextContent());
                        val = sb.toString();
                    } else if (vs.getLength() > 0) {
                        String raw = vs.item(0).getTextContent();
                        if ("s".equals(type)) val = shared.get(Integer.parseInt(raw.trim()));
                        else if ("b".equals(type)) val = raw.trim().equals("1") ? "TRUE" : "FALSE";
                        else if ("e".equals(type)) val = "";
                        else if ("str".equals(type)) val = raw;
                        else val = formatNumber(raw);
                    }
                    grid.computeIfAbsent(rIdx, k -> new TreeMap<>()).put(cIdx, val);
                    maxCol = Math.max(maxCol, cIdx);
                }
            }
            if (grid.isEmpty()) throw new IOException("The first worksheet of " + f.getName() + " is empty.");
            DataSet ds = new DataSet();
            int firstRow = grid.firstKey();
            TreeMap<Integer, String> hdr = grid.get(firstRow);
            for (int c = 0; c <= maxCol; c++) {
                String h = hdr.getOrDefault(c, "").trim();
                ds.names.add(h.isEmpty() ? "..." + (c + 1) : h);
            }
            for (Map.Entry<Integer, TreeMap<Integer, String>> e : grid.entrySet()) {
                if (e.getKey() == firstRow) continue;
                String[] row = new String[maxCol + 1];
                boolean any = false;
                for (int c = 0; c <= maxCol; c++) {
                    row[c] = e.getValue().getOrDefault(c, "").trim();
                    if (!row[c].isEmpty()) any = true;
                }
                if (any) ds.rows.add(row);
            }
            return ds;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot read " + f.getName() + " as an .xlsx workbook: " + e.getMessage(), e);
        }
    }

    private static int colIndex(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) return fallback;
        int c = 0, i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : c - 1;
    }

    private static String formatNumber(String raw) {
        String s = raw.trim();
        try {
            double d = Double.parseDouble(s);
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            if (Double.isNaN(d) || Double.isInfinite(d)) return s;
            // Excel keeps 15 significant digits; rounding to them removes binary noise such as 0.5340000000000001
            return new java.math.BigDecimal(d).round(new java.math.MathContext(15)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
