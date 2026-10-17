package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreMenuImagePrepareResult;
import com.launchcatch.store.dto.StoreMenuRegisterResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.entity.StoreMenu;
import com.launchcatch.store.entity.StoreImageUploadStatus;
import com.launchcatch.store.entity.StoreMenuImage;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreMenuImageRepository;
import com.launchcatch.store.repository.StoreMenuRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 점주 대표 메뉴와 메뉴 이미지 업로드(#389). store_menu_image.store_menu_id가 NOT NULL FK라
 * 이미지보다 메뉴(이름·가격·설명)를 먼저 저장해야 한다. 그래서 가게 인테리어 이미지(#375)와
 * 달리 등록과 이미지 업로드 사이에 메뉴 저장 단계가 하나 더 있다.
 */
@Service
@RequiredArgsConstructor
public class StoreMenuUploadService {

    private static final int MAX_MENUS = 3;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(10);

    private final StoreMenuRepository storeMenuRepository;
    private final StoreMenuImageRepository storeMenuImageRepository;
    private final StoreRegistrationProgressRepository progressRepository;
    private final StoreRegistrationProgressService progressService;
    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Value("${s3.media-bucket}")
    private String mediaBucket;

    @Transactional
    public StoreMenuRegisterResponse registerMenu(Long ownerId, String name, long price, String description) {
        Long storeId = findStoreIdForUpdate(ownerId);

        long existing = storeMenuRepository.countByStoreId(storeId);
        if (existing >= MAX_MENUS) {
            throw new StoreException(StoreErrorCode.MENU_LIMIT_EXCEEDED);
        }
        int sortOrder = (int) existing + 1;

        StoreMenu menu = StoreMenu.register(storeId, name, price, description, sortOrder);
        storeMenuRepository.save(menu);
        return new StoreMenuRegisterResponse(menu.getId(), sortOrder);
    }

    public StoreMenuImagePrepareResult prepareMenuImageUpload(Long ownerId, Long menuId, String originalFilename,
            String contentType, Long fileSize) {
        validateContentType(contentType);
        validateFileSize(fileSize);

        Long storeId = findStoreId(ownerId);
        if (!storeMenuRepository.existsByIdAndStoreId(menuId, storeId)) {
            throw new StoreException(StoreErrorCode.MENU_NOT_FOUND);
        }

        StoreMenuImage image = transactionTemplate.execute(
                status -> reserveMenuImage(storeId, menuId, originalFilename, contentType, fileSize));

        String uploadUrl;
        try {
            uploadUrl = presign(image.getObjectKey(), contentType, fileSize);
        } catch (RuntimeException e) {
            // presign은 자격 증명을 원격으로 조회할 수 있어 잠금을 쥔 트랜잭션 밖에서 부른다(#375와 같은 근거).
            transactionTemplate.executeWithoutResult(status -> storeMenuImageRepository.delete(image));
            throw e;
        }

        return new StoreMenuImagePrepareResult(image.getUploadId(), uploadUrl);
    }

    private StoreMenuImage reserveMenuImage(Long storeId, Long menuId, String originalFilename, String contentType,
            Long fileSize) {
        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(storeId, menuId, uploadId, contentType);
        StoreMenuImage image = StoreMenuImage.preparePending(
                menuId, uploadId, objectKey, originalFilename, contentType, fileSize);
        storeMenuImageRepository.save(image);
        return image;
    }

    @Transactional
    public void confirmMenuImageUpload(Long ownerId, String uploadId) {
        Long storeId = findStoreId(ownerId);

        StoreMenuImage image = storeMenuImageRepository.findByUploadId(uploadId)
                .filter(found -> storeMenuRepository.existsByIdAndStoreId(found.getStoreMenuId(), storeId))
                .orElseThrow(() -> new StoreException(StoreErrorCode.MENU_NOT_FOUND));

        // 응답을 못 받은 클라이언트의 재시도는 같은 결과를 성공으로 돌려준다(#375와 같은 근거).
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
    public StoreRegistrationProgressResponse completeMenuStep(Long ownerId, long expectedVersion) {
        Long storeId = findStoreId(ownerId);

        if (storeMenuRepository.countWithoutVerifiedImage(storeId) > 0) {
            throw new StoreException(StoreErrorCode.MENU_IMAGE_REQUIRED);
        }
        var snapshot = progressService.completeStep(ownerId, expectedVersion, StoreRegistrationStep.MENU, null);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }

    private Long findStoreId(Long ownerId) {
        StoreRegistrationProgress progress = progressRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        return requireStoreId(progress);
    }

    // 개수 확인부터 저장까지 같은 점주의 동시 요청을 직렬화해 sortOrder 중복 저장을 막는다(#375와 같은 근거).
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

    private String buildObjectKey(Long storeId, Long menuId, String uploadId, String contentType) {
        String extension = contentType.equals("image/png") ? "png" : "jpg";
        return "stores/" + storeId + "/menu/" + menuId + "/" + uploadId + "." + extension;
    }

    private String presign(String objectKey, String contentType, Long fileSize) {
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
