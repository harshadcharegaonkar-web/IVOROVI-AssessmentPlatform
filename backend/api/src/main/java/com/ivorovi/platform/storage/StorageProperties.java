package com.ivorovi.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible storage. {@code endpoint} empty = AWS default endpoint resolution; set it for SeaweedFS, MinIO,
 * Ceph RGW, GCS interoperability or other S3-compatible stores.
 */
@ConfigurationProperties("ivorovi.storage")
public record StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey,
                                boolean pathStyle, boolean createBucket) {}
