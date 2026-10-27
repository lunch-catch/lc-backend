package com.lunchcatch.store.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
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
@Table(name = "store_menu_image")
@AttributeOverride(name = "id", column = @Column(name = "store_menu_image_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreMenuImage extends BaseTimeEntity {

    @Column(name = "store_menu_id", nullable = false)
    private Long storeMenuId;

    @Column(name = "upload_id", length = 36)
    private String uploadId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private StoreImageUploadStatus uploadStatus;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    private StoreMenuImage(Long storeMenuId, String uploadId, String objectKey, String originalFilename,
            String contentType, Long fileSize) {
        this.storeMenuId = Objects.requireNonNull(storeMenuId, "storeMenuId");
        this.uploadId = Objects.requireNonNull(uploadId, "uploadId");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.uploadStatus = StoreImageUploadStatus.PENDING;
    }

    public static StoreMenuImage preparePending(Long storeMenuId, String uploadId, String objectKey,
            String originalFilename, String contentType, Long fileSize) {
        return new StoreMenuImage(storeMenuId, uploadId, objectKey, originalFilename, contentType, fileSize);
    }

    // 호출자가 S3Client.headObject로 실제 업로드를 확인한 뒤에만 불러야 한다.
    public void verify(LocalDateTime now) {
        if (uploadStatus != StoreImageUploadStatus.PENDING) {
            throw new IllegalStateException("only PENDING image can be verified: " + uploadStatus);
        }
        this.uploadStatus = StoreImageUploadStatus.VERIFIED;
        this.verifiedAt = Objects.requireNonNull(now, "now");
    }
}
