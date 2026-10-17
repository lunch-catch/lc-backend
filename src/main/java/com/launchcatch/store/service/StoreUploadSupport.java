package com.launchcatch.store.service;

import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * StoreImageUploadService(#375)와 StoreMenuUploadService(#389)가 복제해 쓰던 검증·presign·
 * 진행 상태 조회 로직이다. 복제가 SonarCloud 중복도 게이트를 넘겨 이 클래스로 뽑았다(#389 리뷰로 찾음).
 */
@Component
@RequiredArgsConstructor
public class StoreUploadSupport {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(10);

    private final StoreRegistrationProgressRepository progressRepository;
    private final S3Presigner s3Presigner;

    @Value("${s3.media-bucket}")
    private String mediaBucket;

    // 개수 확인부터 저장까지 같은 점주의 동시 요청을 직렬화해 순번 중복 저장을 막는다(코드래빗 리뷰로 찾음).
    public Long findStoreIdForUpdate(Long ownerId) {
        StoreRegistrationProgress progress = progressRepository.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        return requireStoreId(progress);
    }

    public Long requireStoreId(StoreRegistrationProgress progress) {
        Long storeId = progress.getStoreId();
        if (storeId == null) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        return storeId;
    }

    public void validateContentType(String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new StoreException(StoreErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED);
        }
    }

    public void validateFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new StoreException(StoreErrorCode.IMAGE_FILE_TOO_LARGE);
        }
    }

    public String presign(String objectKey, String contentType, Long fileSize) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(mediaBucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(fileSize)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_DURATION)
                .putObjectRequest(objectRequest)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }
}
