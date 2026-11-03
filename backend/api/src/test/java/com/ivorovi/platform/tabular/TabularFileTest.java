package com.ivorovi.platform.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Streaming CSV/XLSX readers and writers (increment 1.6): encodings, quoting, dates, formulas, macros, ZIP bombs, limits. */
class TabularFileTest {

    @TempDir Path dir;

    private Path file(String name, byte[] content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content);
        return p;
    }

    private static List<TabularFile.Row> rows(TabularFile.Reader r) throws IOException {
        List<TabularFile.Row> out = new ArrayList<>();
        r.rows(row -> { out.add(row); return true; });
        return out;
    }

    private static List<String> texts(TabularFile.Row r) {
        return r.cells().stream().map(TabularFile.Cell::text).toList();
    }

    // ------------------------------------------------------------------------------------------------ CSV

    @Test
    void csvWithBomQuotesLineBreaksAndBlankLinesKeepsSpreadsheetRowNumbers() throws IOException {
        String csv = "﻿First name,Last name,Address\r\n\"Asha\",Rao,\"12, MG Road\nBengaluru\"\r\n\r\nRavi,\"O\"\"Neil\",\r\n";
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.CSV, file("a.csv", csv.getBytes(StandardCharsets.UTF_8)), TabularLimits.of(100));
        TabularFile.Description d = r.describe();
        assertThat(d.headers()).containsExactly("First name", "Last name", "Address");
        assertThat(d.encoding()).contains("UTF-8").contains("byte-order mark");
        assertThat(d.delimiter()).isEqualTo("comma");
        List<TabularFile.Row> rows = rows(r);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(2);
        assertThat(texts(rows.get(0))).containsExactly("Asha", "Rao", "12, MG Road\nBengaluru");
        assertThat(rows.get(1).number()).isEqualTo(4);
        assertThat(rows.get(1).cell(1).text()).isEqualTo("O\"Neil");
        assertThat(rows.get(1).cell(2).blank()).isTrue();
    }

    @Test
    void csvEncodingFallsBackToWindows1252AndDetectsSemicolons() throws IOException {
        byte[] content = "Nom;Ville\nJosé;Pune\n".getBytes(Charset.forName("windows-1252"));
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.CSV, file("b.csv", content), TabularLimits.of(100));
        TabularFile.Description d = r.describe();
        assertThat(d.encoding()).startsWith("Windows-1252");
        assertThat(d.delimiter()).isEqualTo("semicolon");
        assertThat(texts(rows(r).getFirst())).containsExactly("José", "Pune");
    }

    @Test
    void csvUtf16WithBomAndTabs() throws IOException {
        byte[] body = "Name\tCity\nअनिता\tNagpur\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] content = new byte[body.length + 2];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xFE;
        System.arraycopy(body, 0, content, 2, body.length);
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.CSV, file("c.csv", content), TabularLimits.of(100));
        assertThat(r.describe().delimiter()).isEqualTo("tab");
        assertThat(rows(r).getFirst().cell(0).text()).isEqualTo("अनिता");
    }

    @Test
    void csvLimitsAndMalformedInputAreReportedWithRowNumbers() throws IOException {
        StringBuilder big = new StringBuilder("A\n");
        for (int i = 0; i < 6; i++) big.append("x").append(i).append('\n');
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.CSV, file("d.csv", big.toString().getBytes(StandardCharsets.UTF_8)), TabularLimits.of(5));
        assertThatThrownBy(() -> rows(r)).isInstanceOf(TabularFormatException.class).hasMessageContaining("more than 5 data rows");

        TabularFile.Reader q = TabularFile.open(TabularFile.Format.CSV, file("e.csv", "A,B\n1,\"open\n".getBytes(StandardCharsets.UTF_8)), TabularLimits.of(5));
        assertThatThrownBy(() -> rows(q)).isInstanceOf(TabularFormatException.class).hasMessageContaining("Row 2").hasMessageContaining("quote");

        TabularFile.Reader empty = TabularFile.open(TabularFile.Format.CSV, file("f.csv", "\n\n".getBytes(StandardCharsets.UTF_8)), TabularLimits.of(5));
        assertThatThrownBy(empty::describe).isInstanceOf(TabularFormatException.class).hasMessageContaining("empty");
    }

    // ------------------------------------------------------------------------------------------------ detection

    @Test
    void detectionChecksContentAndExtensionTogether() {
        byte[] zip = {'P', 'K', 3, 4, 0, 0};
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        byte[] text = "a,b\n".getBytes(StandardCharsets.UTF_8);
        assertThat(TabularFile.detect("s.xlsx", zip)).isEqualTo(TabularFile.Format.XLSX);
        assertThat(TabularFile.detect("s.CSV", text)).isEqualTo(TabularFile.Format.CSV);
        assertThatThrownBy(() -> TabularFile.detect("s.xlsm", zip)).hasMessageContaining("Macro");
        assertThatThrownBy(() -> TabularFile.detect("s.xls", ole)).hasMessageContaining("Legacy");
        assertThatThrownBy(() -> TabularFile.detect("s.xlsx", text)).hasMessageContaining("not a valid .xlsx");
        assertThatThrownBy(() -> TabularFile.detect("s.csv", zip)).hasMessageContaining("workbook");
        assertThatThrownBy(() -> TabularFile.detect("s.pdf", text)).hasMessageContaining(".xlsx").hasMessageContaining(".csv");
        assertThatThrownBy(() -> TabularFile.detect("s.csv", new byte[] {1, 2, 0, 4})).hasMessageContaining("not a text CSV");
    }

    // ------------------------------------------------------------------------------------------------ XLSX

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final String TYPES = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>";
    private static final String WORKBOOK = "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
            + "<sheet name=\"Hidden\" sheetId=\"1\" state=\"hidden\" r:id=\"rId2\"/><sheet name=\"Students\" sheetId=\"2\" r:id=\"rId1\"/></sheets></workbook>";
    private static final String RELS = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"worksheet\" Target=\"/xl/worksheets/sheet2.xml\"/></Relationships>";
    private static final String STYLES = "<?xml version=\"1.0\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\"/></numFmts>"
            + "<cellXfs count=\"3\"><xf numFmtId=\"0\"/><xf numFmtId=\"164\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>";
    private static final String SHARED = "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<si><t>First name</t></si><si><t>Date of birth</t></si><si><t>Mobile</t></si><si><t>Total</t></si>"
            + "<si><r><t>As</t></r><r><t>ha</t></r><rPh><t>ignored</t></rPh></si></sst>";
    private static final String SHEET = "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>"
            + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>4</v></c><c r=\"B3\" s=\"1\"><v>39308</v></c><c r=\"C3\"><v>9845012345</v></c>"
            + "<c r=\"D3\"><f>SUM(1,2)</f><v>3</v></c></row>"
            + "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>Ravi</t></is></c><c r=\"C4\"><v>1.5E3</v></c></row></sheetData></worksheet>";

    @Test
    void xlsxSharedAndInlineStringsDatesNumbersAndFormulas() throws IOException {
        byte[] x = zip(new java.util.LinkedHashMap<>(Map.of("[Content_Types].xml", TYPES, "xl/workbook.xml", WORKBOOK, "xl/_rels/workbook.xml.rels", RELS,
                "xl/styles.xml", STYLES, "xl/sharedStrings.xml", SHARED, "xl/worksheets/sheet1.xml", SHEET, "xl/worksheets/sheet2.xml", "<worksheet/>")));
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.XLSX, file("s.xlsx", x), TabularLimits.of(100));
        TabularFile.Description d = r.describe();
        assertThat(d.sheetName()).isEqualTo("Students");
        assertThat(d.headers()).containsExactly("First name", "Date of birth", "Mobile", "Total");
        List<TabularFile.Row> rows = rows(r);
        assertThat(rows).hasSize(2);
        TabularFile.Row first = rows.getFirst();
        assertThat(first.number()).isEqualTo(3);
        assertThat(first.cell(0).text()).isEqualTo("Asha");
        assertThat(first.cell(1).text()).isEqualTo("2007-08-14");
        assertThat(first.cell(2).text()).isEqualTo("9845012345");
        assertThat(first.cell(3).formula()).isTrue();
        assertThat(first.cell(3).text()).isNull();
        assertThat(rows.get(1).cell(0).text()).isEqualTo("Ravi");
        assertThat(rows.get(1).cell(1).blank()).isTrue();
        assertThat(rows.get(1).cell(2).text()).isEqualTo("1500");
    }

    @Test
    void xlsxWithMacrosOrMacroContentTypeIsRejected() throws IOException {
        Map<String, String> base = new java.util.LinkedHashMap<>(Map.of("[Content_Types].xml", TYPES, "xl/workbook.xml", WORKBOOK, "xl/_rels/workbook.xml.rels", RELS,
                "xl/worksheets/sheet1.xml", SHEET.replace("t=\"s\"", "t=\"inlineStr\"")));
        Map<String, String> vba = new java.util.LinkedHashMap<>(base);
        vba.put("xl/vbaProject.bin", "binary");
        assertThatThrownBy(() -> TabularFile.open(TabularFile.Format.XLSX, file("m.xlsx", zip(vba)), TabularLimits.of(10)).describe())
                .isInstanceOf(TabularFormatException.class).hasMessageContaining("macros");
        Map<String, String> enabled = new java.util.LinkedHashMap<>(base);
        enabled.put("[Content_Types].xml", TYPES.replace("sheet.main+xml", "sheet.macroEnabled.main+xml"));
        assertThatThrownBy(() -> TabularFile.open(TabularFile.Format.XLSX, file("n.xlsx", zip(enabled)), TabularLimits.of(10)).describe())
                .isInstanceOf(TabularFormatException.class).hasMessageContaining("Macro-enabled");
    }

    @Test
    void xlsxZipBombIsRejectedWhileReading() throws IOException {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        sheet.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>A</t></is></c></row>");
        sheet.append("<!--").append("x".repeat(30 * 1024 * 1024)).append("-->");
        sheet.append("</sheetData></worksheet>");
        byte[] x = zip(new java.util.LinkedHashMap<>(Map.of("[Content_Types].xml", TYPES, "xl/workbook.xml", WORKBOOK, "xl/_rels/workbook.xml.rels", RELS,
                "xl/worksheets/sheet1.xml", sheet.toString())));
        assertThat(x.length).isLessThan(200_000);
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.XLSX, file("bomb.xlsx", x), TabularLimits.of(10));
        assertThat(r.describe().headers()).containsExactly("A");      // streaming: the header is read without inflating the rest
        assertThatThrownBy(() -> rows(r)).isInstanceOf(TabularFormatException.class).hasMessageContaining("compressed suspiciously");
    }

    @Test
    void xlsxExternalEntitiesAreNotResolved() throws IOException {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&e;</t></is></c></row></sheetData></worksheet>";
        byte[] x = zip(new java.util.LinkedHashMap<>(Map.of("[Content_Types].xml", TYPES, "xl/workbook.xml", WORKBOOK, "xl/_rels/workbook.xml.rels", RELS,
                "xl/worksheets/sheet1.xml", evil)));
        assertThatThrownBy(() -> TabularFile.open(TabularFile.Format.XLSX, file("xxe.xlsx", x), TabularLimits.of(10)).describe())
                .isInstanceOf(TabularFormatException.class);
    }

    // ------------------------------------------------------------------------------------------------ writers

    @Test
    void xlsxWriterOutputReadsBackAndKeepsLeadingZeros() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TabularWriter w = TabularWriter.of(TabularFile.Format.XLSX, out)) {
            w.sheet("Students", List.of(20, 20));
            w.header(List.of("Student ID", "Name & <notes>"));
            w.row(List.of("0012", "=cmd|' /C calc'!A0"));
            w.sheet("Instructions", null);
            w.header(List.of("x"));
        }
        TabularFile.Reader r = TabularFile.open(TabularFile.Format.XLSX, file("w.xlsx", out.toByteArray()), TabularLimits.of(10));
        assertThat(r.describe().headers()).containsExactly("Student ID", "Name & <notes>");
        TabularFile.Row row = rows(r).getFirst();
        assertThat(row.cell(0).text()).isEqualTo("0012");
        assertThat(row.cell(1).formula()).isFalse();
        assertThat(row.cell(1).text()).isEqualTo("=cmd|' /C calc'!A0");
    }

    @Test
    void csvWriterNeutralisesFormulaInjection() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TabularWriter w = TabularWriter.of(TabularFile.Format.CSV, out)) {
            w.header(List.of("a", "b", "c"));
            w.row(List.of("=1+1", "plain, text", "@SUM(A1)"));
        }
        String s = out.toString(StandardCharsets.UTF_8);
        assertThat(s).startsWith("﻿a,b,c\r\n");
        assertThat(s).contains("\"'=1+1\",\"plain, text\",\"'@SUM(A1)\"");
    }
}
