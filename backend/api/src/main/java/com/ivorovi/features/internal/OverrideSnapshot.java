package com.ivorovi.features.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All overrides of one tenant; the unit cached in Valkey/Redis (JSON, never Java serialisation). */
public record OverrideSnapshot(List<Entry> entries) {

    public record Entry(String flagKey, UUID institutionId, boolean enabled) {}

    public OverrideSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public Optional<Boolean> tenant(String key) {
        return entries.stream().filter(e -> e.flagKey().equals(key) && e.institutionId() == null)
                .map(Entry::enabled).findFirst();
    }

    public Optional<Boolean> institution(String key, UUID institutionId) {
        if (institutionId == null) return Optional.empty();
        return entries.stream().filter(e -> e.flagKey().equals(key) && institutionId.equals(e.institutionId()))
                .map(Entry::enabled).findFirst();
    }
}
