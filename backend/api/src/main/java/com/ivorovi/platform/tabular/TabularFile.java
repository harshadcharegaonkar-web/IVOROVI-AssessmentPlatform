package com.ivorovi.platform.tabular;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Streaming access to an uploaded spreadsheet (CSV or XLSX) without loading it into memory. Implemented with the JDK
 * only (no spreadsheet library): {@link CsvTabularReader} and {@link XlsxTabularReader}. Formulas are never evaluated
 * and macros are never accepted. Reusable by any bulk import (students now; candidates and question banks later).
 */
public final class TabularFile {

    public enum Format { CSV, XLSX }

    /** One cell as written in the file. {@code formula} is true for XLSX cells that contain a formula (value not used). */
    public record Cell(String text, boolean formula) {
        public static final Cell EMPTY = new Cell(null, false);

        public boolean blank() { return !formula && (text == null || text.isBlank()); }
    }

    /** {@code number} is the 1-based row number as shown by spreadsheet programs (the header is row 1). */
    public record Row(int number, List<Cell> cells) {
        public Cell cell(int index) { return index < cells.size() ? cells.get(index) : Cell.EMPTY; }

        public boolean blank() { return cells.stream().allMatch(Cell::blank); }
    }

    /** What was detected while opening the file (shown to the administrator). */
    public record Description(Format format, String encoding, String delimiter, String sheetName, List<String> headers) {}

    public interface RowHandler {
        /** Return false to stop reading. */
        boolean row(Row row) throws IOException;
    }

    public interface Reader {
        /** Reads the header row (first non-empty row). */
        Description describe() throws IOException;

        /** Streams every data row after the header; blank rows are skipped. Enforces the limits. */
        void rows(RowHandler handler) throws IOException;
    }

    private TabularFile() {}

    public static Reader open(Format format, Path file, TabularLimits limits) throws IOException {
        return switch (format) {
            case CSV -> new CsvTabularReader(file, limits);
            case XLSX -> new XlsxTabularReader(file, limits);
        };
    }

    /**
     * Detects the format from the file content and checks it against the name's extension (both must agree).
     * Rejects macro-enabled workbooks, legacy/encrypted (OLE) workbooks and binary data posing as CSV.
     */
    public static Format detect(String fileName, byte[] head) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        boolean zip = head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        boolean ole = head.length >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0;
        if (name.endsWith(".xlsm") || name.endsWith(".xltm") || name.endsWith(".xlsb")) {
            throw new TabularFormatException("macros_not_allowed", "Macro-enabled or binary workbooks are not accepted. Save the file as .xlsx (Excel Workbook) or .csv.");
        }
        if (ole) {
            throw new TabularFormatException("unsupported_workbook", "Legacy .xls and password-protected workbooks are not supported. Save the file as .xlsx without a password, or as .csv.");
        }
        if (name.endsWith(".xlsx")) {
            if (!zip) throw new TabularFormatException("malformed_file", "The file is not a valid .xlsx workbook.");
            return Format.XLSX;
        }
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            if (zip) throw new TabularFormatException("file_type_mismatch", "The file content is a workbook but the name ends in .csv. Rename it to .xlsx or export it as CSV.");
            for (int i = 0; i < Math.min(head.length, 4096); i++) {
                int b = head[i] & 0xFF;
                if (b == 0 && !(i < 4 && (head[0] == (byte) 0xFF || head[0] == (byte) 0xFE))) {
                    // NUL bytes appear only in UTF-16 text (with a BOM); anything else is binary
                    if (!utf16Bom(head)) throw new TabularFormatException("malformed_file", "The file is not a text CSV file.");
                    break;
                }
            }
            return Format.CSV;
        }
        throw new TabularFormatException("unsupported_file_type", "Upload an Excel workbook (.xlsx) or a CSV file (.csv).");
    }

    private static boolean utf16Bom(byte[] h) {
        return h.length >= 2 && ((h[0] == (byte) 0xFF && h[1] == (byte) 0xFE) || (h[0] == (byte) 0xFE && h[1] == (byte) 0xFF));
    }
}
