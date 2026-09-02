package com.labs.photouploader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3Client bean. Credentials come from {@link DefaultCredentialsProvider},
 * which on ECS Fargate resolves to the task's IAM role automatically via
 * the container credentials endpoint -- no access key/secret is ever
 * configured in the app.
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${AWS_REGION:us-east-1}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
