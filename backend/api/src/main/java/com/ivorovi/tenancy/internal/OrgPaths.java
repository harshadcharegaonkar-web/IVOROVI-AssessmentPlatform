package com.ivorovi.tenancy.internal;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Materialised-path helpers: each label is the unit's UUID without dashes. */
final class OrgPaths {

    private OrgPaths() {}

    static String label(UUID id) {
        return id.toString().replace("-", "");
    }

    static String childPath(String parentPath, UUID id) {
        return parentPath == null ? label(id) : parentPath + "." + label(id);
    }

    static List<UUID> ids(String path) {
        return Arrays.stream(path.split("\\.")).map(OrgPaths::fromLabel).toList();
    }

    static UUID fromLabel(String l) {
        return UUID.fromString(l.substring(0, 8) + "-" + l.substring(8, 12) + "-" + l.substring(12, 16) + "-"
                + l.substring(16, 20) + "-" + l.substring(20));
    }
}
