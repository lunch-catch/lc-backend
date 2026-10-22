package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreMenuImagePrepareResult;
import com.launchcatch.store.dto.StoreMenuRegisterResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.entity.StoreMenu;
import com.launchcatch.store.entity.StoreImageUploadStatus;
import com.launchcatch.store.entity.StoreMenuImage;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreMenuImageRepository;
import com.launchcatch.store.repository.StoreMenuRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
 * 점주 대표 메뉴와 메뉴 이미지 업로드(#389). store_menu_image.store_menu_id가 NOT NULL FK라
 * 이미지보다 메뉴(이름·가격·설명)를 먼저 저장해야 한다. 그래서 가게 인테리어 이미지(#375)와
 * 달리 등록과 이미지 업로드 사이에 메뉴 저장 단계가 하나 더 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreMenuUploadService {

    private static final int MAX_MENUS = 3;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final StoreMenuRepository storeMenuRepository;
    private final StoreMenuImageRepository storeMenuImageRepository;
    private final StoreRegistrationProgressService progressService;
    private final StoreUploadSupport storeUploadSupport;
    private final S3Client s3Client;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Value("${s3.media-bucket}")
    private String mediaBucket;

    @Transactional
    public StoreMenuRegisterResponse registerMenu(Long ownerId, String name, long price, String description) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

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
        storeUploadSupport.validateContentType(contentType);
        storeUploadSupport.validateFileSize(fileSize);

        StoreMenuImage image = transactionTemplate.execute(
                status -> reserveMenuImage(ownerId, menuId, originalFilename, contentType, fileSize));

        String uploadUrl;
        try {
            uploadUrl = storeUploadSupport.presign(image.getObjectKey(), contentType, fileSize);
        } catch (RuntimeException e) {
            log.error("event=STORE_UPLOAD_PREPARE_FAILED ownerId={} resourceType=MENU_IMAGE exceptionType={}",
                    ownerId, e.getClass().getSimpleName());
            // presign은 자격 증명을 원격으로 조회할 수 있어 잠금을 쥔 트랜잭션 밖에서 부른다(#375와 같은 근거).
            try {
                transactionTemplate.executeWithoutResult(status -> storeMenuImageRepository.delete(image));
            } catch (RuntimeException compensationFailure) {
                log.error("event=STORE_UPLOAD_COMPENSATION_FAILED ownerId={} resourceType=MENU_IMAGE exceptionType={}",
                        ownerId, compensationFailure.getClass().getSimpleName());
                throw compensationFailure;
            }
            throw e;
        }

        return new StoreMenuImagePrepareResult(image.getUploadId(), uploadUrl);
    }

    /*
     * 소유권 확인부터 기존 이미지 처리, 저장까지 같은 점주의 동시 요청을 직렬화한다(#389 셀프 리뷰로
     * 찾음). store_menu_image.store_menu_id는 메뉴당 1개만 허용하는 유니크 제약이라, 업로드를
     * 끝내지 못하고 포기한 PENDING 행이 남아 있으면 그 메뉴는 영원히 재시도할 수 없게 막힌다.
     * 그래서 PENDING 행은 지우고 다시 받고, VERIFIED는 거부한다.
     */
    private StoreMenuImage reserveMenuImage(Long ownerId, Long menuId, String originalFilename, String contentType,
            Long fileSize) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);
        if (!storeMenuRepository.existsByIdAndStoreId(menuId, storeId)) {
            throw new StoreException(StoreErrorCode.MENU_NOT_FOUND);
        }

        storeMenuImageRepository.findByStoreMenuId(menuId).ifPresent(existing -> {
            if (existing.getUploadStatus() == StoreImageUploadStatus.VERIFIED) {
                throw new StoreException(StoreErrorCode.MENU_IMAGE_ALREADY_EXISTS);
            }
            // 뒤이은 저장과 유니크 제약이 부딪히지 않도록 삭제를 바로 실행한다.
            storeMenuImageRepository.delete(existing);
            storeMenuImageRepository.flush();
        });

        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(storeId, menuId, uploadId, contentType);
        StoreMenuImage image = StoreMenuImage.preparePending(
                menuId, uploadId, objectKey, originalFilename, contentType, fileSize);
        storeMenuImageRepository.save(image);
        return image;
    }

    public void confirmMenuImageUpload(Long ownerId, String uploadId) {
        StoreMenuImage image = transactionTemplate.execute(status -> findOwnedImage(ownerId, uploadId));

        // 응답을 못 받은 클라이언트의 재시도는 같은 결과를 성공으로 돌려준다(#375와 같은 근거).
        if (image.getUploadStatus() == StoreImageUploadStatus.VERIFIED) {
            return;
        }

        HeadObjectResponse headObject;
        try {
            // headObject는 응답이 지연될 수 있어 DB 잠금을 쥐지 않은 채로 부른다(코드래빗 리뷰로 찾음).
            headObject = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(mediaBucket)
                    .key(image.getObjectKey())
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=MENU_IMAGE httpStatus={} exceptionType={}",
                        ownerId, e.statusCode(), e.getClass().getSimpleName());
                throw e;
            }
            log.info("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=MENU_IMAGE reason=NOT_UPLOADED",
                    ownerId);
            throw new StoreException(StoreErrorCode.IMAGE_NOT_UPLOADED);
        } catch (RuntimeException e) {
            log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=MENU_IMAGE exceptionType={}",
                    ownerId, e.getClass().getSimpleName());
            throw e;
        }
        if (headObject.contentLength() == null
                || headObject.contentLength() > MAX_FILE_SIZE
                || image.getFileSize() == null
                || headObject.contentLength() > image.getFileSize()) {
            log.info("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=MENU_IMAGE reason=INVALID_SIZE",
                    ownerId);
            throw new StoreException(StoreErrorCode.IMAGE_FILE_TOO_LARGE);
        }

        // 호출 사이 다른 요청이 이 PENDING 행을 지우고 다시 만들었을 수 있어, 잠금을 다시 잡고 재조회한다.
        transactionTemplate.executeWithoutResult(status -> {
            StoreMenuImage current = findOwnedImage(ownerId, uploadId);
            if (current.getUploadStatus() == StoreImageUploadStatus.PENDING) {
                current.verify(LocalDateTime.now(clock));
            }
        });
    }

    private StoreMenuImage findOwnedImage(Long ownerId, String uploadId) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);
        return storeMenuImageRepository.findByUploadId(uploadId)
                .filter(found -> storeMenuRepository.existsByIdAndStoreId(found.getStoreMenuId(), storeId))
                .orElseThrow(() -> new StoreException(StoreErrorCode.MENU_NOT_FOUND));
    }

    /*
     * 미완성 메뉴 확인과 단계 완료를 같은 잠금 안에서 한다(#389 셀프 리뷰로 찾음). 잠금 없이
     * 확인만 하면 그 사이에 registerMenu가 끼어들어 이미지 없는 메뉴를 추가해도 못 보고
     * 그대로 완료시킬 수 있다. registerMenu도 같은 진행 상태 행을 잠그므로 서로 끼어들 틈이
     * 없어진다. progressService.completeStep도 같은 행을 다시 잠그지만 같은 트랜잭션 안의
     * 재진입이라 추가 쿼리 한 번만 더 돈다.
     */
    @Transactional
    public StoreRegistrationProgressResponse completeMenuStep(Long ownerId, long expectedVersion) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

        if (storeMenuRepository.countWithoutVerifiedImage(storeId) > 0) {
            throw new StoreException(StoreErrorCode.MENU_IMAGE_REQUIRED);
        }
        var snapshot = progressService.completeStep(ownerId, expectedVersion, StoreRegistrationStep.MENU, null);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }

    private String buildObjectKey(Long storeId, Long menuId, String uploadId, String contentType) {
        String extension = contentType.equals("image/png") ? "png" : "jpg";
        return "stores/" + storeId + "/menu/" + menuId + "/" + uploadId + "." + extension;
    }
}
