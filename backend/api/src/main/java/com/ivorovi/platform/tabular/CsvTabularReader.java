package com.ivorovi.platform.tabular;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV reader (quoted fields, doubled quotes, line breaks inside quotes; CRLF, LF or CR line endings).
 * <ul>
 *   <li>Encoding: a UTF-8 or UTF-16 byte-order mark wins; otherwise the file is checked as strict UTF-8 and, if that
 *       fails, read as Windows-1252 (what spreadsheet programs on Windows write by default).</li>
 *   <li>Delimiter: comma, semicolon or tab, whichever occurs most often (outside quotes) in the header line.</li>
 *   <li>Row numbers count every record from the header (row 1), so they match what a spreadsheet program shows.</li>
 * </ul>
 */
final class CsvTabularReader implements TabularFile.Reader {

    private final Path file;
    private final TabularLimits limits;
    private Charset charset;
    private String encodingLabel;
    private int skipBytes;
    private char delimiter;

    CsvTabularReader(Path file, TabularLimits limits) {
        this.file = file;
        this.limits = limits;
    }

    @Override
    public TabularFile.Description describe() throws IOException {
        detectEncoding();
        List<String> header = new ArrayList<>();
        try (Reader r = open()) {
            delimiter = detectDelimiter(r);
        }
        try (Reader r = open()) {
            Parser p = new Parser(r);
            List<String> rec;
            while ((rec = p.next()) != null) {
                if (rec.stream().anyMatch(s -> !s.isBlank())) {
                    for (String s : rec) header.add(s.trim());
                    break;
                }
            }
        }
        if (header.isEmpty()) throw new TabularFormatException("empty_file", "The file is empty. The first row must contain the column headings.");
        if (header.size() > limits.maxColumns()) {
            throw new TabularFormatException("too_many_columns", "The file has " + header.size() + " columns; at most " + limits.maxColumns() + " are supported.");
        }
        return new TabularFile.Description(TabularFile.Format.CSV, encodingLabel, delimiterName(delimiter), null, header);
    }

    @Override
    public void rows(TabularFile.RowHandler handler) throws IOException {
        if (charset == null) describe();
        try (Reader r = open()) {
            Parser p = new Parser(r);
            List<String> rec;
            boolean headerSeen = false;
            int dataRows = 0;
            while ((rec = p.next()) != null) {
                int number = p.recordNumber;
                boolean blank = rec.stream().allMatch(String::isBlank);
                if (!headerSeen) {
                    if (!blank) headerSeen = true;
                    continue;
                }
                if (blank) continue;
                if (rec.size() > limits.maxColumns()) {
                    throw new TabularFormatException("too_many_columns", "Row " + number + " has " + rec.size() + " columns; at most " + limits.maxColumns() + " are supported.");
                }
                if (++dataRows > limits.maxRows()) {
                    throw new TabularFormatException("too_many_rows", "The file has more than " + limits.maxRows() + " data rows. Split it into smaller files.");
                }
                List<TabularFile.Cell> cells = new ArrayList<>(rec.size());
                for (int i = 0; i < rec.size(); i++) {
                    String v = rec.get(i);
                    if (v.length() > limits.maxCellChars()) {
                        throw new TabularFormatException("cell_too_long", "Row " + number + ", column " + (i + 1) + " has more than " + limits.maxCellChars() + " characters.");
                    }
                    cells.add(v.isEmpty() ? TabularFile.Cell.EMPTY : new TabularFile.Cell(v, false));
                }
                if (!handler.row(new TabularFile.Row(number, cells))) return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ encoding

    private void detectEncoding() throws IOException {
        byte[] bom = new byte[3];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(bom, 0, 3);
        }
        if (n >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
            charset = StandardCharsets.UTF_8; encodingLabel = "UTF-8 (with byte-order mark)"; skipBytes = 3; return;
        }
        if (n >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE; encodingLabel = "UTF-16 LE"; skipBytes = 2; return;
        }
        if (n >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE; encodingLabel = "UTF-16 BE"; skipBytes = 2; return;
        }
        skipBytes = 0;
        if (validUtf8()) {
            charset = StandardCharsets.UTF_8; encodingLabel = "UTF-8";
        } else {
            charset = Charset.forName("windows-1252"); encodingLabel = "Windows-1252 (detected: not valid UTF-8)";
        }
    }

    private boolean validUtf8() throws IOException {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.allocate(64 * 1024);
        CharBuffer out = CharBuffer.allocate(64 * 1024);
        try (InputStream s = Files.newInputStream(file)) {
            byte[] chunk = new byte[32 * 1024];
            int n;
            while ((n = s.read(chunk)) > 0) {
                in.put(chunk, 0, n);
                in.flip();
                if (d.decode(in, out, false).isError()) return false;
                in.compact();
                out.clear();
            }
            in.flip();
            if (d.decode(in, out, true).isError()) return false;
            return !d.flush(out).isError();
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private Reader open() throws IOException {
        InputStream in = Files.newInputStream(file);
        in.skipNBytes(skipBytes);
        return new BufferedReader(new InputStreamReader(in, charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)), 64 * 1024);
    }

    private static char detectDelimiter(Reader r) throws IOException {
        int comma = 0, semi = 0, tab = 0, c;
        boolean quoted = false, content = false;
        while ((c = r.read()) != -1) {
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '\n' || c == '\r')) { if (content) break; else continue; }
            else if (!quoted && c == ',') comma++;
            else if (!quoted && c == ';') semi++;
            else if (!quoted && c == '\t') tab++;
            if (!Character.isWhitespace(c)) content = true;
        }
        if (semi > comma && semi >= tab) return ';';
        if (tab > comma && tab > semi) return '\t';
        return ',';
    }

    private static String delimiterName(char d) {
        return switch (d) { case ';' -> "semicolon"; case '\t' -> "tab"; default -> "comma"; };
    }

    /** Record parser; recordNumber counts records (including blank ones) from 1. */
    private final class Parser {
        private final Reader in;
        private int pending = -2;
        int recordNumber;

        Parser(Reader in) { this.in = in; }

        private int read() throws IOException {
            if (pending != -2) { int p = pending; pending = -2; return p; }
            return in.read();
        }

        List<String> next() throws IOException {
            int c = read();
            if (c == -1) return null;
            recordNumber++;
            List<String> fields = new ArrayList<>();
            StringBuilder f = new StringBuilder();
            boolean quoted = false, wasQuoted = false;
            int startRecord = recordNumber;
            while (true) {
                if (c == -1) {
                    if (quoted) throw new TabularFormatException("malformed_file", "Row " + startRecord + " has an opening quote (\") without a closing quote.");
                    fields.add(f.toString());
                    return fields;
                }
                if (quoted) {
                    if (c == '"') {
                        int d = read();
                        if (d == '"') f.append('"');
                        else { quoted = false; pending = d; }
                    } else {
                        if (f.length() > limits.maxCellChars() * 4) {
                            throw new TabularFormatException("cell_too_long", "Row " + startRecord + " has a value with more than " + limits.maxCellChars() + " characters.");
                        }
                        f.append((char) c);
                    }
                } else if (c == '"' && f.isEmpty() && !wasQuoted) {
                    quoted = true;
                    wasQuoted = true;
                } else if (c == delimiter) {
                    fields.add(f.toString());
                    f.setLength(0);
                    wasQuoted = false;
                    if (fields.size() > limits.maxColumns() + 50) {
                        throw new TabularFormatException("too_many_columns", "Row " + startRecord + " has more than " + limits.maxColumns() + " columns.");
                    }
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        int d = read();
                        if (d != '\n') pending = d;
                    }
                    fields.add(f.toString());
                    return fields;
                } else {
                    if (f.length() > limits.maxCellChars() * 4) {
                        throw new TabularFormatException("cell_too_long", "Row " + startRecord + " has a value with more than " + limits.maxCellChars() + " characters.");
                    }
                    f.append((char) c);
                }
                c = read();
            }
        }
    }
}
