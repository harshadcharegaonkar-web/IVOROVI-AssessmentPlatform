package com.ivorovi.platform.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Tenant-scoped object storage port. Paths are relative to the current tenant's namespace; implementations prefix
 * them with {@code t/{tenantId}/}, so one tenant can never address another tenant's objects.
 */
public interface ObjectStorage {

    void put(String path, InputStream content, long contentLength, String contentType);

    Optional<byte[]> get(String path);

    URI presignedDownload(String path, Duration ttl);

    void delete(String path);
}
