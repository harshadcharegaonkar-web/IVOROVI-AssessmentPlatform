package com.ivorovi.platform.storage;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties p) {
        var b = S3Client.builder().region(Region.of(p.region())).credentialsProvider(credentials(p))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.pathStyle()).build());
        if (p.endpoint() != null && !p.endpoint().isBlank()) b.endpointOverride(URI.create(p.endpoint()));
        return b.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties p) {
        var b = S3Presigner.builder().region(Region.of(p.region())).credentialsProvider(credentials(p))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.pathStyle()).build());
        if (p.endpoint() != null && !p.endpoint().isBlank()) b.endpointOverride(URI.create(p.endpoint()));
        return b.build();
    }

    @Bean
    ObjectStorage objectStorage(S3Client s3, S3Presigner presigner, StorageProperties p) {
        S3ObjectStorage storage = new S3ObjectStorage(s3, presigner, p.bucket());
        if (p.createBucket()) storage.ensureBucket();
        return storage;
    }

    /** Static keys when configured; otherwise the standard chain (IAM role, IRSA/workload identity, env). */
    private static AwsCredentialsProvider credentials(StorageProperties p) {
        if (p.accessKey() != null && !p.accessKey().isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
