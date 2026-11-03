package com.ivorovi.platform.tabular;

/** An uploaded file that cannot be read (unsupported, malformed or over a limit). The message is safe to show. */
public class TabularFormatException extends RuntimeException {

    private final String code;

    public TabularFormatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
