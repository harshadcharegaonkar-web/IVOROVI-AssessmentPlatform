package com.ivorovi.support;

import com.ivorovi.exams.CandidateMfa;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory second-factor state of candidate-realm subjects (the Keycloak adapter is exercised by Playwright). */
public class FakeCandidateMfa implements CandidateMfa {

    public final Set<String> enrolled = ConcurrentHashMap.newKeySet();
    public final Set<String> resets = ConcurrentHashMap.newKeySet();
    public final java.util.List<PasswordReset> passwordResets = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final java.util.List<String> signedOut = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public java.util.List<PasswordReset> passwordResetsSince(java.time.Instant since) {
        return passwordResets.stream().filter(r -> r.at().isAfter(since)).toList();
    }

    @Override
    public void signOutEverywhere(String subject) {
        signedOut.add(subject);
    }

    @Override
    public boolean enrolled(String subject) {
        return enrolled.contains(subject);
    }

    @Override
    public void reset(String subject) {
        enrolled.remove(subject);
        resets.add(subject);
    }
}
