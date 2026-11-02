package com.ivorovi.platform.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S3 API adapter. Works with any S3-compatible store; no vendor-specific features are used. */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public void put(String path, InputStream content, long contentLength, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(TenantObjectKeys.keyFor(path)).contentType(contentType)
                .contentLength(contentLength), RequestBody.fromInputStream(content, contentLength));
    }

    @Override
    public Optional<byte[]> get(String path) {
        try {
            ResponseBytes<GetObjectResponse> bytes =
                    s3.getObjectAsBytes(b -> b.bucket(bucket).key(TenantObjectKeys.keyFor(path)));
            return Optional.of(bytes.asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public URI presignedDownload(String path, Duration ttl) {
        String key = TenantObjectKeys.keyFor(path);
        try {
            return presigner.presignGetObject(p -> p.signatureDuration(ttl)
                    .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void delete(String path) {
        s3.deleteObject(b -> b.bucket(bucket).key(TenantObjectKeys.keyFor(path)));
    }

    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() != 404) throw e;
            s3.createBucket(b -> b.bucket(bucket));
        }
    }
}
