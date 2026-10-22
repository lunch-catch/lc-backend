package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreImagePrepareResult;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.entity.StoreImage;
import com.launchcatch.store.entity.StoreImageType;
import com.launchcatch.store.entity.StoreImageUploadStatus;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreImageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
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
 * 가게 로고와 인테리어 이미지 업로드(인테리어 #375, 로고 #588). 로고는 가게당 1개이고, 이미
 * 등록된 로고가 있으면 새 행을 만들지 않고 그 행을 덮어써 교체한다.
 *
 * 업로드 예약과 단계 완료는 진행 상태 행 잠금 아래 본인 미확정 가게에만 허용한다.
 * 최종 등록은 같은 잠금을 사용하므로 확정 이후 온보딩 업로드가 추가되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreImageUploadService {

    private static final int MAX_INTERIOR_IMAGES = 3;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final StoreImageRepository storeImageRepository;
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
            log.error("event=STORE_UPLOAD_PREPARE_FAILED ownerId={} resourceType=IMAGE exceptionType={}",
                    ownerId, e.getClass().getSimpleName());
            // presign은 자격 증명을 원격으로 조회할 수 있어 잠금을 쥔 트랜잭션 밖에서 부른다(코드래빗 리뷰로 찾음).
            // 서명이 실패하면 예약해 둔 PENDING 행을 지워 순번과 3개 제한 자리를 돌려준다.
            try {
                transactionTemplate.executeWithoutResult(status -> storeImageRepository.delete(image));
            } catch (RuntimeException compensationFailure) {
                log.error("event=STORE_UPLOAD_COMPENSATION_FAILED ownerId={} resourceType=IMAGE exceptionType={}",
                        ownerId, compensationFailure.getClass().getSimpleName());
                throw compensationFailure;
            }
            throw e;
        }

        return new StoreImagePrepareResult(image.getUploadId(), uploadUrl, image.getSortOrder());
    }

    /*
     * 로고 업로드를 준비한다. 이미 로고 행이 있으면 그 행을 PENDING 으로 되돌려 새 업로드로 덮어쓰고,
     * 없으면 새로 만든다. 덮어쓰면 새 업로드를 confirm 하기 전까지 로고가 VERIFIED 가 아니다.
     */
    public StoreImagePrepareResult prepareLogoUpload(Long ownerId, String originalFilename,
            String contentType, Long fileSize) {
        storeUploadSupport.validateContentType(contentType);
        storeUploadSupport.validateFileSize(fileSize);

        LogoReservation reservation = transactionTemplate.execute(
                status -> reserveLogoImage(ownerId, originalFilename, contentType, fileSize));
        StoreImage image = reservation.image();

        String uploadUrl;
        try {
            uploadUrl = storeUploadSupport.presign(image.getObjectKey(), contentType, fileSize);
        } catch (RuntimeException e) {
            log.error("event=STORE_UPLOAD_PREPARE_FAILED ownerId={} resourceType=IMAGE exceptionType={}",
                    ownerId, e.getClass().getSimpleName());
            // 새로 만든 행만 지운다. 덮어쓴 행은 지우면 기존 로고까지 사라져서 그대로 두고, 점주가 다시 요청한다.
            if (reservation.created()) {
                try {
                    transactionTemplate.executeWithoutResult(status -> storeImageRepository.delete(image));
                } catch (RuntimeException compensationFailure) {
                    log.error("event=STORE_UPLOAD_COMPENSATION_FAILED ownerId={} resourceType=IMAGE exceptionType={}",
                            ownerId, compensationFailure.getClass().getSimpleName());
                    throw compensationFailure;
                }
            }
            throw e;
        }

        return new StoreImagePrepareResult(image.getUploadId(), uploadUrl, null);
    }

    private LogoReservation reserveLogoImage(Long ownerId, String originalFilename,
            String contentType, Long fileSize) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(storeId, uploadId, contentType, "logo");
        Optional<StoreImage> existing = storeImageRepository.findByStoreIdAndImageType(storeId, StoreImageType.LOGO);
        if (existing.isPresent()) {
            StoreImage logo = existing.get();
            logo.restartUpload(uploadId, objectKey, originalFilename, contentType, fileSize);
            return new LogoReservation(logo, false);
        }
        StoreImage logo = StoreImage.preparePending(
                storeId, uploadId, StoreImageType.LOGO, objectKey, originalFilename, contentType, fileSize, null);
        storeImageRepository.save(logo);
        return new LogoReservation(logo, true);
    }

    private record LogoReservation(StoreImage image, boolean created) { }

    private StoreImage reserveInteriorImage(Long ownerId, String originalFilename,
            String contentType, Long fileSize) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

        long existing = storeImageRepository.countByStoreIdAndImageType(storeId, StoreImageType.INTERIOR);
        if (existing >= MAX_INTERIOR_IMAGES) {
            throw new StoreException(StoreErrorCode.INTERIOR_IMAGE_LIMIT_EXCEEDED);
        }
        int sortOrder = (int) existing + 1;

        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(storeId, uploadId, contentType, "interior");
        StoreImage image = StoreImage.preparePending(
                storeId, uploadId, StoreImageType.INTERIOR, objectKey, originalFilename, contentType, fileSize,
                sortOrder);
        storeImageRepository.save(image);
        return image;
    }

    @Transactional
    public void confirmUpload(Long ownerId, String uploadId) {
        // 같은 로고 행을 덮어쓰는 prepareUpload 와 겹쳐 옛 값으로 행을 다시 쓰지 않도록 같은 잠금을 쓴다.
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

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
                log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=IMAGE httpStatus={} exceptionType={}",
                        ownerId, e.statusCode(), e.getClass().getSimpleName());
                throw e;
            }
            log.info("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=IMAGE reason=NOT_UPLOADED",
                    ownerId);
            throw new StoreException(StoreErrorCode.IMAGE_NOT_UPLOADED);
        } catch (RuntimeException e) {
            log.error("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=IMAGE exceptionType={}",
                    ownerId, e.getClass().getSimpleName());
            throw e;
        }
        if (headObject.contentLength() == null
                || headObject.contentLength() > MAX_FILE_SIZE
                || image.getFileSize() == null
                || headObject.contentLength() > image.getFileSize()) {
            log.info("event=STORE_UPLOAD_CONFIRM_FAILED ownerId={} resourceType=IMAGE reason=INVALID_SIZE",
                    ownerId);
            throw new StoreException(StoreErrorCode.IMAGE_FILE_TOO_LARGE);
        }

        image.verify(LocalDateTime.now(clock));
    }

    @Transactional
    public StoreRegistrationProgressResponse completeImagesStep(Long ownerId, long expectedVersion) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);

        if (!storeImageRepository.existsByStoreIdAndImageTypeAndUploadStatus(
                storeId, StoreImageType.LOGO, StoreImageUploadStatus.VERIFIED)) {
            throw new StoreException(StoreErrorCode.LOGO_IMAGE_REQUIRED);
        }
        var snapshot = progressService.completeStep(ownerId, expectedVersion, StoreRegistrationStep.IMAGES, null);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }

    private String buildObjectKey(Long storeId, String uploadId, String contentType, String folder) {
        String extension = contentType.equals("image/png") ? "png" : "jpg";
        return "stores/" + storeId + "/" + folder + "/" + uploadId + "." + extension;
    }
}
