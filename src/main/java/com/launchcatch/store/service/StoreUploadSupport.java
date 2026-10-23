package com.launchcatch.store.service;

import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * StoreImageUploadService(#375)와 StoreMenuUploadService(#389)가 복제해 쓰던 검증·presign·
 * 진행 상태 조회 로직이다. 복제가 SonarCloud 중복도 게이트를 넘겨 이 클래스로 뽑았다(#389 리뷰로 찾음).
 */
@Slf4j
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
        Long storeId = requireStoreId(progress);
        if (progressRepository.countDraftStoreOwnedBy(ownerId, storeId) != 1) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        }
        return storeId;
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

    // 호출자가 정한 트랜잭션 경계를 유지하면서 이미지와 메뉴의 S3 확인 및 실패 기록을 공유한다.
    public void verifyUpload(S3Client s3Client, String bucket, String objectKey, Long fileSize,
            Long ownerId, String uploadId, String resourceType) {
        HeadObjectResponse headObject;
        try {
            headObject = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} uploadId={} resourceType={} httpStatus={} exceptionType={}",
                        ownerId, uploadId, resourceType, e.statusCode(), e.getClass().getSimpleName());
                throw e;
            }
            log.info("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} uploadId={} resourceType={} reason=NOT_UPLOADED",
                    ownerId, uploadId, resourceType);
            throw new StoreException(StoreErrorCode.IMAGE_NOT_UPLOADED);
        } catch (RuntimeException e) {
            log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} uploadId={} resourceType={} exceptionType={}",
                    ownerId, uploadId, resourceType, e.getClass().getSimpleName());
            throw e;
        }
        if (headObject.contentLength() == null || fileSize == null) {
            log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} uploadId={} resourceType={} reason=SIZE_METADATA_MISSING",
                    ownerId, uploadId, resourceType);
            throw new StoreException(StoreErrorCode.IMAGE_FILE_TOO_LARGE);
        }
        if (headObject.contentLength() > MAX_FILE_SIZE || headObject.contentLength() > fileSize) {
            log.info("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} uploadId={} resourceType={} reason=INVALID_SIZE",
                    ownerId, uploadId, resourceType);
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
