package com.ivorovi.platform.storage;

import com.ivorovi.platform.tenancy.TenantContext;
import java.util.regex.Pattern;

/** Builds tenant-prefixed object keys and rejects traversal or ambiguous paths. */
public final class TenantObjectKeys {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,511}$");

    private TenantObjectKeys() {}

    public static String keyFor(String path) {
        if (path == null || !SAFE.matcher(path).matches() || path.contains("..") || path.contains("//")) {
            throw new IllegalArgumentException("Invalid object path");
        }
        return "t/" + TenantContext.requireTenantId() + "/" + path;
    }
}
