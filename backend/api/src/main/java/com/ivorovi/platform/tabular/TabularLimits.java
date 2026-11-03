package com.ivorovi.platform.tabular;

/**
 * Limits applied while reading an uploaded spreadsheet. {@code maxUncompressedBytes} and {@code maxCompressionRatio}
 * protect against ZIP bombs in XLSX files; {@code maxSharedStrings} bounds the only structure kept in memory.
 */
public record TabularLimits(int maxRows, int maxColumns, int maxCellChars, long maxUncompressedBytes, int maxCompressionRatio, int maxSharedStrings) {

    public static TabularLimits of(int maxRows) {
        return new TabularLimits(maxRows, 100, 1000, 200L * 1024 * 1024, 200, 2_000_000);
    }
}
