package com.ivorovi.platform.tabular;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming reader for the first visible worksheet of an Office Open XML workbook (.xlsx), JDK only (ZIP + StAX).
 * <ul>
 *   <li><b>No macros:</b> workbooks with a VBA project or a macro-enabled content type are rejected.</li>
 *   <li><b>No formulas:</b> formula cells are reported as such ({@link TabularFile.Cell#formula()}); their cached values
 *       are never used and formulas are never evaluated.</li>
 *   <li><b>ZIP bomb protection:</b> entry count, total uncompressed bytes and per-entry compression ratio are limited
 *       while reading (declared sizes are not trusted).</li>
 *   <li><b>XML hardening:</b> DTDs and external entities are disabled.</li>
 *   <li>Date-formatted numeric cells are converted to ISO dates (1900 and 1904 date systems); other numbers are written
 *       in plain notation (no exponent), so long phone numbers survive.</li>
 * </ul>
 */
final class XlsxTabularReader implements TabularFile.Reader {

    private static final int MAX_ENTRIES = 10_000;
    private static final Set<Integer> BUILTIN_DATE_FORMATS = Set.of(14, 15, 16, 17, 22, 27, 30, 36, 45, 46, 47, 50, 57);

    private final Path file;
    private final TabularLimits limits;
    private ZipFile zip;
    private String sheetPath;
    private String sheetName;
    private boolean date1904;
    private List<String> sharedStrings;
    private List<Boolean> dateStyles;
    private long uncompressedRead;

    XlsxTabularReader(Path file, TabularLimits limits) {
        this.file = file;
        this.limits = limits;
    }

    @Override
    public TabularFile.Description describe() throws IOException {
        prepare();
        List<String> header = new ArrayList<>();
        try {
            readSheet(row -> {
                if (row.blank()) return true;
                for (TabularFile.Cell c : row.cells()) header.add(c.formula() ? "" : c.text() == null ? "" : c.text().trim());
                return false;
            });
        } finally {
            close();
        }
        while (!header.isEmpty() && header.getLast().isEmpty()) header.removeLast();
        if (header.isEmpty()) throw new TabularFormatException("empty_file", "The first worksheet is empty. Its first row must contain the column headings.");
        return new TabularFile.Description(TabularFile.Format.XLSX, "UTF-8 (XLSX)", null, sheetName, header);
    }

    @Override
    public void rows(TabularFile.RowHandler handler) throws IOException {
        prepare();
        boolean[] headerSeen = {false};
        int[] count = {0};
        try {
            readSheet(row -> {
                if (row.blank()) return true;
                if (!headerSeen[0]) { headerSeen[0] = true; return true; }
                if (++count[0] > limits.maxRows()) {
                    throw new TabularFormatException("too_many_rows", "The file has more than " + limits.maxRows() + " data rows. Split it into smaller files.");
                }
                return handler.row(row);
            });
        } finally {
            close();
        }
    }

    private void close() throws IOException {
        if (zip != null) { zip.close(); zip = null; }
    }

    // ------------------------------------------------------------------------------------------------ structure

    private void prepare() throws IOException {
        if (zip != null) return;
        uncompressedRead = 0;
        try {
            zip = new ZipFile(file.toFile());
        } catch (ZipException e) {
            throw new TabularFormatException("malformed_file", "The file is not a valid .xlsx workbook.");
        }
        int entries = 0;
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (++entries > MAX_ENTRIES) throw new TabularFormatException("malformed_file", "The workbook has too many internal parts.");
            String name = e.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith("vbaproject.bin") || name.startsWith("xl/activex/")) {
                throw new TabularFormatException("macros_not_allowed", "The workbook contains macros or ActiveX controls, which are not accepted. Save it as a plain .xlsx workbook.");
            }
        }
        String types = text("[Content_Types].xml");
        if (types == null) throw new TabularFormatException("malformed_file", "The file is not a valid .xlsx workbook.");
        if (types.contains("macroEnabled")) {
            throw new TabularFormatException("macros_not_allowed", "Macro-enabled workbooks are not accepted. Save the file as .xlsx (Excel Workbook).");
        }
        locateSheet();
        sharedStrings = readSharedStrings();
        dateStyles = readDateStyles();
    }

    private void locateSheet() throws IOException {
        Map<String, String> rels = new HashMap<>();
        parse("xl/_rels/workbook.xml.rels", r -> {
            if (r.getEventType() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                rels.put(r.getAttributeValue(null, "Id"), r.getAttributeValue(null, "Target"));
            }
            return true;
        });
        String[] chosen = new String[2];
        parse("xl/workbook.xml", r -> {
            if (r.getEventType() != XMLStreamConstants.START_ELEMENT) return true;
            if ("workbookPr".equals(r.getLocalName())) {
                String v = r.getAttributeValue(null, "date1904");
                date1904 = "1".equals(v) || "true".equalsIgnoreCase(v);
            }
            if ("sheet".equals(r.getLocalName()) && chosen[0] == null) {
                String state = r.getAttributeValue(null, "state");
                if (state == null || "visible".equals(state)) {
                    String rid = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if ("id".equals(r.getAttributeLocalName(i))) rid = r.getAttributeValue(i);
                    }
                    chosen[0] = rels.get(rid);
                    chosen[1] = r.getAttributeValue(null, "name");
                }
            }
            return true;
        });
        if (chosen[0] == null) throw new TabularFormatException("malformed_file", "The workbook has no visible worksheet.");
        String target = chosen[0];
        sheetPath = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        sheetName = chosen[1];
        if (zip.getEntry(sheetPath) == null) throw new TabularFormatException("malformed_file", "The workbook's first worksheet is missing.");
    }

    private List<String> readSharedStrings() throws IOException {
        List<String> out = new ArrayList<>();
        if (zip.getEntry("xl/sharedStrings.xml") == null) return out;
        StringBuilder current = new StringBuilder();
        boolean[] inSi = {false}, inT = {false}, inPhonetic = {false};
        parse("xl/sharedStrings.xml", r -> {
            switch (r.getEventType()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    switch (r.getLocalName()) {
                        case "si" -> { inSi[0] = true; current.setLength(0); }
                        case "t" -> inT[0] = true;
                        case "rPh" -> inPhonetic[0] = true;
                        default -> { }
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (inSi[0] && inT[0] && !inPhonetic[0]) {
                        current.append(r.getText());
                        if (current.length() > limits.maxCellChars() * 4) current.setLength(limits.maxCellChars() + 1);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    switch (r.getLocalName()) {
                        case "si" -> {
                            inSi[0] = false;
                            if (out.size() >= limits.maxSharedStrings()) {
                                throw new TabularFormatException("malformed_file", "The workbook has too many distinct text values.");
                            }
                            out.add(current.toString());
                        }
                        case "t" -> inT[0] = false;
                        case "rPh" -> inPhonetic[0] = false;
                        default -> { }
                    }
                }
                default -> { }
            }
            return true;
        });
        return out;
    }

    private List<Boolean> readDateStyles() throws IOException {
        List<Boolean> out = new ArrayList<>();
        if (zip.getEntry("xl/styles.xml") == null) return out;
        Map<Integer, Boolean> custom = new HashMap<>();
        boolean[] inXfs = {false};
        parse("xl/styles.xml", r -> {
            if (r.getEventType() == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "numFmt" -> {
                        try {
                            custom.put(Integer.parseInt(r.getAttributeValue(null, "numFmtId")), isDateFormat(r.getAttributeValue(null, "formatCode")));
                        } catch (NumberFormatException ignored) { }
                    }
                    case "cellXfs" -> inXfs[0] = true;
                    case "xf" -> {
                        if (inXfs[0]) {
                            int id;
                            try { id = Integer.parseInt(String.valueOf(r.getAttributeValue(null, "numFmtId"))); } catch (NumberFormatException e) { id = 0; }
                            out.add(BUILTIN_DATE_FORMATS.contains(id) || Boolean.TRUE.equals(custom.get(id)));
                        }
                    }
                    default -> { }
                }
            } else if (r.getEventType() == XMLStreamConstants.END_ELEMENT && "cellXfs".equals(r.getLocalName())) {
                inXfs[0] = false;
            }
            return true;
        });
        return out;
    }

    /** A custom number format is a date format if it contains d, m or y outside quotes/brackets and no digit placeholders only. */
    static boolean isDateFormat(String code) {
        if (code == null) return false;
        String s = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^]]*]", "").replaceAll("\\\\.", "");
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return (lower.contains("d") || lower.contains("y") || lower.contains("m")) && !lower.contains("0") && !lower.contains("#") && !lower.contains("@");
    }

    // ------------------------------------------------------------------------------------------------ sheet

    private void readSheet(TabularFile.RowHandler handler) throws IOException {
        try (InputStream in = guarded(zip.getEntry(sheetPath))) {
            XMLStreamReader r = factory().createXMLStreamReader(in);
            try {
                List<TabularFile.Cell> cells = new ArrayList<>();
                int rowNumber = 0, nextCol = 0, col = 0, style = -1;
                String type = null;
                boolean inValue = false, inInline = false, formula = false;
                StringBuilder value = new StringBuilder();
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "row" -> {
                                cells = new ArrayList<>();
                                nextCol = 0;
                                String rn = r.getAttributeValue(null, "r");
                                rowNumber = rn == null ? rowNumber + 1 : Integer.parseInt(rn);
                            }
                            case "c" -> {
                                String ref = r.getAttributeValue(null, "r");
                                col = ref == null ? nextCol : columnIndex(ref);
                                nextCol = col + 1;
                                if (col >= limits.maxColumns()) {
                                    throw new TabularFormatException("too_many_columns", "Row " + rowNumber + " uses more than " + limits.maxColumns() + " columns.");
                                }
                                type = r.getAttributeValue(null, "t");
                                String s = r.getAttributeValue(null, "s");
                                style = s == null ? -1 : Integer.parseInt(s);
                                formula = false;
                                value.setLength(0);
                            }
                            case "f" -> formula = true;
                            case "v" -> inValue = true;
                            case "is" -> inInline = true;
                            case "t" -> { if (inInline) inValue = true; }
                            default -> { }
                        }
                    } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                        if (inValue) {
                            value.append(r.getText());
                            if (value.length() > limits.maxCellChars() * 4) {
                                throw new TabularFormatException("cell_too_long", "Row " + rowNumber + " has a value with more than " + limits.maxCellChars() + " characters.");
                            }
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "v" -> inValue = false;
                            case "t" -> { if (inInline) inValue = false; }
                            case "is" -> inInline = false;
                            case "c" -> {
                                TabularFile.Cell cell = formula ? new TabularFile.Cell(null, true) : convert(type, style, value.toString(), rowNumber);
                                while (cells.size() < col) cells.add(TabularFile.Cell.EMPTY);
                                cells.add(cell);
                            }
                            case "row" -> {
                                while (!cells.isEmpty() && cells.getLast().blank()) cells.removeLast();
                                if (!handler.row(new TabularFile.Row(rowNumber, cells))) return;
                            }
                            default -> { }
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new TabularFormatException("malformed_file", "The worksheet could not be read (damaged or not a standard .xlsx file).");
        } catch (NumberFormatException e) {
            throw new TabularFormatException("malformed_file", "The worksheet contains an invalid cell reference.");
        }
    }

    private TabularFile.Cell convert(String type, int style, String raw, int rowNumber) {
        if (raw.isEmpty()) return TabularFile.Cell.EMPTY;
        String text;
        if ("s".equals(type)) {
            int idx;
            try { idx = Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { idx = -1; }
            if (idx < 0 || idx >= sharedStrings.size()) throw new TabularFormatException("malformed_file", "Row " + rowNumber + " refers to a missing text value.");
            text = sharedStrings.get(idx);
        } else if ("b".equals(type)) {
            text = "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        } else if ("inlineStr".equals(type) || "str".equals(type) || "e".equals(type)) {
            text = raw;
        } else {
            text = number(raw.trim(), style >= 0 && style < dateStyles.size() && dateStyles.get(style));
        }
        if (text.length() > limits.maxCellChars()) {
            throw new TabularFormatException("cell_too_long", "Row " + rowNumber + " has a value with more than " + limits.maxCellChars() + " characters.");
        }
        return text.isEmpty() ? TabularFile.Cell.EMPTY : new TabularFile.Cell(text, false);
    }

    private String number(String raw, boolean date) {
        BigDecimal n;
        try {
            n = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
        if (date && n.signum() >= 0 && n.compareTo(BigDecimal.valueOf(2_958_465)) <= 0) {
            long days = n.longValue();
            LocalDate base = date1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 30);
            return base.plusDays(days).toString();
        }
        String plain = n.stripTrailingZeros().toPlainString();
        return plain;
    }

    static int columnIndex(String ref) {
        int col = 0, i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            if (col > 16_384) throw new NumberFormatException("column");
            i++;
        }
        if (i == 0) throw new NumberFormatException("column");
        return col - 1;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private interface XmlHandler {
        boolean on(XMLStreamReader r) throws XMLStreamException;
    }

    private void parse(String entry, XmlHandler handler) throws IOException {
        ZipEntry e = zip.getEntry(entry);
        if (e == null) throw new TabularFormatException("malformed_file", "The file is not a valid .xlsx workbook (" + entry + " missing).");
        try (InputStream in = guarded(e)) {
            XMLStreamReader r = factory().createXMLStreamReader(in);
            try {
                while (r.hasNext()) {
                    r.next();
                    if (!handler.on(r)) break;
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException ex) {
            throw new TabularFormatException("malformed_file", "The workbook could not be read (damaged or not a standard .xlsx file).");
        }
    }

    private String text(String entry) throws IOException {
        ZipEntry e = zip.getEntry(entry);
        if (e == null) return null;
        try (InputStream in = guarded(e)) {
            byte[] b = in.readNBytes(1024 * 1024);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        return f;
    }

    /** Counts uncompressed bytes; fails on excessive totals or compression ratios (declared sizes are not trusted). */
    private InputStream guarded(ZipEntry entry) throws IOException {
        long compressed = Math.max(entry.getCompressedSize(), 1);
        return new FilterInputStream(zip.getInputStream(entry)) {
            long read;

            private void count(long n) {
                if (n <= 0) return;
                read += n;
                uncompressedRead += n;
                if (uncompressedRead > limits.maxUncompressedBytes()) {
                    throw new TabularFormatException("file_too_large", "The workbook expands to more data than allowed.");
                }
                if (read > 1024 * 1024 && read / compressed > limits.maxCompressionRatio()) {
                    throw new TabularFormatException("malformed_file", "The workbook is compressed suspiciously strongly and was rejected.");
                }
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) count(1);
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                count(n);
                return n;
            }
        };
    }
}
