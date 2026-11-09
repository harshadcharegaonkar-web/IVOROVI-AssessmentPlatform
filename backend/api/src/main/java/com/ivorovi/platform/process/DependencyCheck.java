package com.ivorovi.platform.process;

/**
 * A dependency this process needs, checked with every heartbeat (follow-up B): {@code OK}, {@code SLOW} (answered, but
 * slower than its limit) or {@code FAILED}. Checks must be quick and bounded by their own timeout.
 */
public interface DependencyCheck {

    enum Status { OK, SLOW, FAILED }

    String name();

    /** Whether this process role needs the dependency at all. */
    default boolean appliesTo(ProcessRole role) {
        return true;
    }

    Status check();
}
