package com.ivorovi.platform.tabular;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streaming spreadsheet writers for templates and reports (JDK only).
 * <ul>
 *   <li>CSV: UTF-8 with byte-order mark (opens correctly in spreadsheet programs), CRLF, RFC 4180 quoting. Values that a
 *       spreadsheet would interpret as a formula ({@code = + - @}, tab, carriage return) are prefixed with an apostrophe
 *       (CSV/formula injection).</li>
 *   <li>XLSX: every cell is an inline string (never a formula; leading zeros and long numbers are preserved), bold
 *       frozen header row, one or more sheets.</li>
 * </ul>
 */
public abstract class TabularWriter implements AutoCloseable {

    public static TabularWriter of(TabularFile.Format format, OutputStream out) throws IOException {
        return format == TabularFile.Format.CSV ? new Csv(out) : new Xlsx(out);
    }

    /** Starts a sheet (CSV: only the first sheet is written; later sheets are ignored). */
    public abstract void sheet(String name, List<Integer> columnWidths) throws IOException;

    public abstract void header(List<String> values) throws IOException;

    public abstract void row(List<String> values) throws IOException;

    @Override
    public abstract void close() throws IOException;

    static String neutralise(String v) {
        if (v == null || v.isEmpty()) return v;
        char c = v.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' ? "'" + v : v;
    }

    // ------------------------------------------------------------------------------------------------ CSV

    private static final class Csv extends TabularWriter {
        private final Writer w;
        private int sheets;

        Csv(OutputStream out) throws IOException {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            this.w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        }

        @Override
        public void sheet(String name, List<Integer> widths) { sheets++; }

        private boolean active() { return sheets <= 1; }

        @Override
        public void header(List<String> values) throws IOException { row(values); }

        @Override
        public void row(List<String> values) throws IOException {
            if (!active()) return;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) line.append(',');
                String v = neutralise(values.get(i));
                if (v == null) continue;
                if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.startsWith("'")) {
                    line.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    line.append(v);
                }
            }
            w.write(line.append("\r\n").toString());
        }

        @Override
        public void close() throws IOException { w.flush(); }
    }

    // ------------------------------------------------------------------------------------------------ XLSX

    private static final class Xlsx extends TabularWriter {
        private final ZipOutputStream zip;
        private final Writer w;
        private final List<String> names = new ArrayList<>();
        private int rowNo;
        private boolean open;

        Xlsx(OutputStream out) {
            this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
            this.w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        }

        @Override
        public void sheet(String name, List<Integer> widths) throws IOException {
            endSheet();
            String safe = name.replaceAll("[\\\\/?*\\[\\]:]", " ");
            names.add(safe.length() > 31 ? safe.substring(0, 31) : safe);
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + names.size() + ".xml"));
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
            if (widths != null && !widths.isEmpty()) {
                w.write("<cols>");
                for (int i = 0; i < widths.size(); i++) {
                    w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + widths.get(i) + "\" customWidth=\"1\"/>");
                }
                w.write("</cols>");
            }
            w.write("<sheetData>");
            rowNo = 0;
            open = true;
        }

        private void ensureSheet() throws IOException {
            if (!open) sheet("Sheet1", null);
        }

        @Override
        public void header(List<String> values) throws IOException { write(values, 1); }

        @Override
        public void row(List<String> values) throws IOException { write(values, 0); }

        private void write(List<String> values, int style) throws IOException {
            ensureSheet();
            rowNo++;
            StringBuilder sb = new StringBuilder("<row r=\"").append(rowNo).append("\">");
            for (int i = 0; i < values.size(); i++) {
                String v = values.get(i);
                if (v == null || v.isEmpty()) continue;
                sb.append("<c r=\"").append(column(i)).append(rowNo).append("\" t=\"inlineStr\"").append(style > 0 ? " s=\"" + style + "\"" : "")
                        .append("><is><t xml:space=\"preserve\">").append(escape(v)).append("</t></is></c>");
            }
            w.write(sb.append("</row>").toString());
        }

        private void endSheet() throws IOException {
            if (!open) return;
            w.write("</sheetData></worksheet>");
            w.flush();
            zip.closeEntry();
            open = false;
        }

        @Override
        public void close() throws IOException {
            ensureSheet();
            endSheet();
            entry("[Content_Types].xml", contentTypes());
            entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < names.size(); i++) {
                wb.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
            }
            rels.append("<Relationship Id=\"rId").append(names.size() + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry("xl/workbook.xml", wb.append("</sheets></workbook>").toString());
            entry("xl/_rels/workbook.xml.rels", rels.toString());
            entry("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8EEF7\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                    + "<xf numFmtId=\"49\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyNumberFormat=\"1\"/></cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            zip.finish();
            zip.flush();
        }

        private String contentTypes() {
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for (int i = 0; i < names.size(); i++) {
                sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            return sb.append("</Types>").toString();
        }

        private void entry(String name, String content) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            w.write(content);
            w.flush();
            zip.closeEntry();
        }

        static String column(int index) {
            StringBuilder s = new StringBuilder();
            int n = index + 1;
            while (n > 0) {
                int r = (n - 1) % 26;
                s.insert(0, (char) ('A' + r));
                n = (n - 1) / 26;
            }
            return s.toString();
        }

        static String escape(String v) {
            StringBuilder sb = new StringBuilder(v.length());
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '&' -> sb.append("&amp;");
                    case '<' -> sb.append("&lt;");
                    case '>' -> sb.append("&gt;");
                    case '"' -> sb.append("&quot;");
                    default -> { if (c >= 0x20 || c == '\t' || c == '\n') sb.append(c); }
                }
            }
            return sb.toString();
        }
    }
}
