package com.ivorovi.exams.internal;

/** Test access to the session-code generator (package-private in production code). */
public final class TestCodes {

    private TestCodes() {}

    public static String generate() {
        return SessionCodes.generate();
    }
}
