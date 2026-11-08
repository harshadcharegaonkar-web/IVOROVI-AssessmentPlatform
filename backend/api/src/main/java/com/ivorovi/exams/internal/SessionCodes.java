package com.ivorovi.exams.internal;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * Session codes: 10 characters from a 31-character alphabet without look-alikes (no 0/O, 1/I/L), about 49 bits. Shown
 * once when generated; stored only as a keyed hash. Accepted with or without the separator, in any case.
 */
final class SessionCodes {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionCodes() {}

    static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    static Optional<String> normalize(String input) {
        if (input == null) return Optional.empty();
        String c = input.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (c.length() != LENGTH) return Optional.empty();
        for (int i = 0; i < c.length(); i++) if (ALPHABET.indexOf(c.charAt(i)) < 0) return Optional.empty();
        return Optional.of(c);
    }

    static String format(String code) {
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    /** Last two characters, for staff lists ("•••••-•••XY"). Never enough to use the code. */
    static String hint(String code) {
        return code.substring(LENGTH - 2);
    }
}
