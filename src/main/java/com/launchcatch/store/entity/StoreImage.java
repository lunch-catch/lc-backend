package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_image")
@AttributeOverride(name = "id", column = @Column(name = "store_image_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreImage extends BaseTimeEntity {

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "upload_id", length = 36)
    private String uploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false, length = 20)
    private StoreImageType imageType;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "sort_order", columnDefinition = "TINYINT")
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private StoreImageUploadStatus uploadStatus;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    private StoreImage(Long storeId, String uploadId, StoreImageType imageType, String objectKey,
            String originalFilename, String contentType, Long fileSize, Integer sortOrder) {
        requireSortOrder(imageType, sortOrder);
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.uploadId = Objects.requireNonNull(uploadId, "uploadId");
        this.imageType = Objects.requireNonNull(imageType, "imageType");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.sortOrder = sortOrder;
        this.uploadStatus = StoreImageUploadStatus.PENDING;
    }

    // 인테리어 이미지는 1~3번 순서를 받는다. 로고는 가게당 1개라 순서가 없다.
    public static StoreImage preparePending(Long storeId, String uploadId, StoreImageType imageType,
            String objectKey, String originalFilename, String contentType, Long fileSize, Integer sortOrder) {
        return new StoreImage(storeId, uploadId, imageType, objectKey, originalFilename, contentType, fileSize,
                sortOrder);
    }

    // 호출자가 S3Client.headObject로 실제 업로드를 확인한 뒤에만 불러야 한다.
    public void verify(LocalDateTime now) {
        if (uploadStatus != StoreImageUploadStatus.PENDING) {
            throw new IllegalStateException("only PENDING image can be verified: " + uploadStatus);
        }
        this.uploadStatus = StoreImageUploadStatus.VERIFIED;
        this.verifiedAt = Objects.requireNonNull(now, "now");
    }

    private static void requireSortOrder(StoreImageType imageType, Integer sortOrder) {
        boolean logo = imageType == StoreImageType.LOGO && sortOrder == null;
        boolean interior = imageType == StoreImageType.INTERIOR
                && sortOrder != null && sortOrder >= 1 && sortOrder <= 3;
        if (!logo && !interior) {
            throw new IllegalArgumentException(
                    "sortOrder must be null for LOGO and 1~3 for INTERIOR: " + imageType + ", " + sortOrder);
        }
    }
}
