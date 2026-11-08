package com.ivorovi.questions.internal;

import com.ivorovi.platform.web.ApiException;
import java.util.regex.Pattern;

/**
 * Server-side rules for TeX formulas, independent of the client renderer (ADR-0010): bounded length, balanced braces,
 * no control characters and none of the commands that could load content, define macros or reach outside the formula.
 * The renderer additionally runs with {@code trust: false}, MathML output only and bounded expansion.
 */
public final class TexGuard {

    public static final int MAX_LENGTH = 1000;
    private static final Pattern DENIED = Pattern.compile(
            "\\\\(href|url|includegraphics|html[A-Za-z]*|def|gdef|edef|xdef|let|futurelet|newcommand|renewcommand|providecommand|"
                    + "DeclareMathOperator|newenvironment|renewenvironment|input|include|write|immediate|openin|openout|read|catcode|"
                    + "csname|endcsname|expandafter|char|mathchar|special|verb)(?![A-Za-z])");

    private TexGuard() {}

    public static void check(String tex, String path) {
        if (tex == null || tex.isBlank()) throw invalid(path, "empty formula");
        if (tex.length() > MAX_LENGTH) throw invalid(path, "formula longer than " + MAX_LENGTH + " characters");
        for (int i = 0; i < tex.length(); i++) {
            char ch = tex.charAt(i);
            if (ch < 0x20 && ch != '\n' && ch != '\t') throw invalid(path, "control character in formula");
        }
        if (DENIED.matcher(tex).find()) throw invalid(path, "formula uses a command that is not allowed");
        int depth = 0;
        for (int i = 0; i < tex.length(); i++) {
            char ch = tex.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '{') depth++;
            if (ch == '}' && --depth < 0) throw invalid(path, "unbalanced braces in formula");
        }
        if (depth != 0) throw invalid(path, "unbalanced braces in formula");
    }

    private static ApiException invalid(String path, String what) {
        return ApiException.badRequest("invalid_math", path + ": " + what + ".");
    }
}
