package com.launchcatch.store.service;

import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.entity.StoreImage;
import com.launchcatch.store.entity.StoreImageType;
import com.launchcatch.store.entity.StoreImageUploadStatus;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreImageRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 가게 인테리어 이미지 업로드(#375). 로고는 가게 입점 과정에서 이미 등록돼 있다는 전제로,
 * 이 서비스는 로고를 새로 만들지 않고 존재 여부만 확인한다.
 */
@Service
@RequiredArgsConstructor
public class StoreImageUploadService {

    private static final int MAX_INTERIOR_IMAGES = 3;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(10);

    private final StoreImageRepository storeImageRepository;
    private final StoreRegistrationProgressRepository progressRepository;
    private final StoreRegistrationProgressService progressService;
    private final OwnerStatusQuery ownerStatusQuery;
    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final Clock clock;

    @Value("${s3.media-bucket}")
    private String mediaBucket;

    @Transactional
    public StoreImagePrepareResult prepareInteriorUpload(Long ownerId, String originalFilename,
            String contentType, Long fileSize) {
        validateOnboardingOwner(ownerId);
        validateContentType(contentType);
        validateFileSize(fileSize);
        Long storeId = findStoreIdForUpdate(ownerId);

        long existing = storeImageRepository.countByStoreIdAndImageType(storeId, StoreImageType.INTERIOR);
        if (existing >= MAX_INTERIOR_IMAGES) {
            throw new StoreException(StoreErrorCode.INTERIOR_IMAGE_LIMIT_EXCEEDED);
        }
        int sortOrder = (int) existing + 1;

        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(storeId, uploadId, contentType);
        String uploadUrl = presign(objectKey, contentType);

        StoreImage image = StoreImage.preparePending(
                storeId, uploadId, StoreImageType.INTERIOR, objectKey, originalFilename, contentType, fileSize,
                sortOrder);
        storeImageRepository.save(image);

        return new StoreImagePrepareResult(uploadId, uploadUrl, sortOrder);
    }

    @Transactional
    public void confirmUpload(Long ownerId, String uploadId) {
        validateOnboardingOwner(ownerId);
        Long storeId = findStoreId(ownerId);

        StoreImage image = storeImageRepository.findByUploadId(uploadId)
                .filter(found -> found.getStoreId().equals(storeId))
                .orElseThrow(() -> new StoreException(StoreErrorCode.IMAGE_UPLOAD_NOT_FOUND));

        // 응답을 못 받은 클라이언트의 재시도는 같은 결과를 성공으로 돌려준다(api-spec.md 멱등성, 코드래빗 리뷰 전 자체 검토로 찾음).
        if (image.getUploadStatus() == StoreImageUploadStatus.VERIFIED) {
            return;
        }

        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(mediaBucket)
                    .key(image.getObjectKey())
                    .build());
        } catch (NoSuchKeyException e) {
            throw new StoreException(StoreErrorCode.IMAGE_NOT_UPLOADED);
        }

        image.verify(LocalDateTime.now(clock));
    }

    @Transactional
    public StoreRegistrationProgressResponse completeImagesStep(Long ownerId, long expectedVersion) {
        validateOnboardingOwner(ownerId);
        Long storeId = findStoreId(ownerId);

        if (!storeImageRepository.existsByStoreIdAndImageType(storeId, StoreImageType.LOGO)) {
            throw new StoreException(StoreErrorCode.LOGO_IMAGE_REQUIRED);
        }
        var snapshot = progressService.completeStep(ownerId, expectedVersion, StoreRegistrationStep.IMAGES, null);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }

    private void validateOnboardingOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ONBOARDING) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
    }

    private Long findStoreId(Long ownerId) {
        StoreRegistrationProgress progress = progressRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        return requireStoreId(progress);
    }

    // 개수 확인부터 저장까지 같은 점주의 동시 요청을 직렬화해 sortOrder 중복 저장을 막는다(코드래빗 리뷰로 찾음).
    private Long findStoreIdForUpdate(Long ownerId) {
        StoreRegistrationProgress progress = progressRepository.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        return requireStoreId(progress);
    }

    private Long requireStoreId(StoreRegistrationProgress progress) {
        Long storeId = progress.getStoreId();
        if (storeId == null) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        return storeId;
    }

    private void validateContentType(String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new StoreException(StoreErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED);
        }
    }

    private void validateFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new StoreException(StoreErrorCode.IMAGE_FILE_TOO_LARGE);
        }
    }

    private String buildObjectKey(Long storeId, String uploadId, String contentType) {
        String extension = contentType.equals("image/png") ? "png" : "jpg";
        return "stores/" + storeId + "/interior/" + uploadId + "." + extension;
    }

    private String presign(String objectKey, String contentType) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(mediaBucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_DURATION)
                .putObjectRequest(objectRequest)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    public record StoreImagePrepareResult(String uploadId, String uploadUrl, int sortOrder) {
    }
}
