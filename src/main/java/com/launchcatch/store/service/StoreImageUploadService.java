package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreImagePrepareResult;
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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
 * 가게 인테리어 이미지 업로드(#375). 로고는 가게 입점 과정에서 이미 등록돼 있다는 전제로,
 * 이 서비스는 로고를 새로 만들지 않고 존재 여부만 확인한다.
 *
 * ONBOARDING이 아닌 점주를 거부하는 검사는 두지 않는다. 그 전환(ACTIVE/SUSPENDED/WITHDRAWN)이
 * 아직 이 저장소에 구현돼 있지 않고, 나중에 필요해지면 owner가 이벤트로 알리는 쪽으로 풀어야
 * owner<->store 순환 의존이 생기지 않는다(런치캐치_도메인_구조와_의존성_설계.md 2.3).
 */
@Service
@RequiredArgsConstructor
public class StoreImageUploadService {

    private static final int MAX_INTERIOR_IMAGES = 3;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final StoreImageRepository storeImageRepository;
    private final StoreRegistrationProgressRepository progressRepository;
    private final StoreRegistrationProgressService progressService;
    private final StoreUploadSupport storeUploadSupport;
    private final S3Client s3Client;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Value("${s3.media-bucket}")
    private String mediaBucket;

    public StoreImagePrepareResult prepareInteriorUpload(Long ownerId, String originalFilename,
            String contentType, Long fileSize) {
        storeUploadSupport.validateContentType(contentType);
        storeUploadSupport.validateFileSize(fileSize);

        StoreImage image = transactionTemplate.execute(
                status -> reserveInteriorImage(ownerId, originalFilename, contentType, fileSize));

        String uploadUrl;
        try {
            uploadUrl = storeUploadSupport.presign(image.getObjectKey(), contentType, fileSize);
        } catch (RuntimeException e) {
            // presign은 자격 증명을 원격으로 조회할 수 있어 잠금을 쥔 트랜잭션 밖에서 부른다(코드래빗 리뷰로 찾음).
            // 서명이 실패하면 예약해 둔 PENDING 행을 지워 순번과 3개 제한 자리를 돌려준다.
            transactionTemplate.executeWithoutResult(status -> storeImageRepository.delete(image));
            throw e;
        }

        return new StoreImagePrepareResult(image.getUploadId(), uploadUrl, image.getSortOrder());
    }

    private StoreImage reserveInteriorImage(Long ownerId, String originalFilename,
            String contentType, Long fileSize) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

        long existing = storeImageRepository.countByStoreIdAndImageType(storeId, StoreImageType.INTERIOR);
        if (existing >= MAX_INTERIOR_IMAGES) {
            throw new StoreException(StoreErrorCode.INTERIOR_IMAGE_LIMIT_EXCEEDED);
        }
        int sortOrder = (int) existing + 1;

        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(storeId, uploadId, contentType);
        StoreImage image = StoreImage.preparePending(
                storeId, uploadId, StoreImageType.INTERIOR, objectKey, originalFilename, contentType, fileSize,
                sortOrder);
        storeImageRepository.save(image);
        return image;
    }

    @Transactional
    public void confirmUpload(Long ownerId, String uploadId) {
        Long storeId = findStoreId(ownerId);

        StoreImage image = storeImageRepository.findByUploadId(uploadId)
                .filter(found -> found.getStoreId().equals(storeId))
                .orElseThrow(() -> new StoreException(StoreErrorCode.IMAGE_UPLOAD_NOT_FOUND));

        // 응답을 못 받은 클라이언트의 재시도는 같은 결과를 성공으로 돌려준다(api-spec.md 멱등성, 코드래빗 리뷰 전 자체 검토로 찾음).
        if (image.getUploadStatus() == StoreImageUploadStatus.VERIFIED) {
            return;
        }

        HeadObjectResponse headObject;
        try {
            headObject = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(mediaBucket)
                    .key(image.getObjectKey())
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            throw new StoreException(StoreErrorCode.IMAGE_NOT_UPLOADED);
        }
        if (headObject.contentLength() == null
                || headObject.contentLength() > MAX_FILE_SIZE
                || image.getFileSize() == null
                || headObject.contentLength() > image.getFileSize()) {
            throw new StoreException(StoreErrorCode.IMAGE_FILE_TOO_LARGE);
        }

        image.verify(LocalDateTime.now(clock));
    }

    @Transactional
    public StoreRegistrationProgressResponse completeImagesStep(Long ownerId, long expectedVersion) {
        Long storeId = findStoreId(ownerId);

        if (!storeImageRepository.existsByStoreIdAndImageType(storeId, StoreImageType.LOGO)) {
            throw new StoreException(StoreErrorCode.LOGO_IMAGE_REQUIRED);
        }
        var snapshot = progressService.completeStep(ownerId, expectedVersion, StoreRegistrationStep.IMAGES, null);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }

    private Long findStoreId(Long ownerId) {
        StoreRegistrationProgress progress = progressRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        return storeUploadSupport.requireStoreId(progress);
    }

    private String buildObjectKey(Long storeId, String uploadId, String contentType) {
        String extension = contentType.equals("image/png") ? "png" : "jpg";
        return "stores/" + storeId + "/interior/" + uploadId + "." + extension;
    }
}
