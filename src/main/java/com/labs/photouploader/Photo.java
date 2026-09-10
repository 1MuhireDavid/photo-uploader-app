package com.labs.photouploader;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One uploaded photo. The binary image itself lives in S3 under {@link
 * #s3Key}; this row is only the metadata the rubric asks for (description
 * + enough to build the CloudFront URL and order the gallery).
 */
@Entity
@Table(name = "photos")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "s3_key", nullable = false, unique = true, length = 512)
    private String s3Key;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected Photo() {
        // JPA
    }

    public Photo(String s3Key, String originalFilename, String description, String contentType) {
        this.s3Key = s3Key;
        this.originalFilename = originalFilename;
        this.description = description;
        this.contentType = contentType;
        this.uploadedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getS3Key() {
        return s3Key;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getDescription() {
        return description;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
