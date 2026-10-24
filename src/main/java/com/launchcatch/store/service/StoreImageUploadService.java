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
                log.error("event=STORE_UPLOAD_COMPENSATION_FAILED ownerId={} uploadId={} resourceType=IMAGE exceptionType={}",
                        ownerId, image.getUploadId(), compensationFailure.getClass().getSimpleName());
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
                    log.error("event=STORE_UPLOAD_COMPENSATION_FAILED ownerId={} uploadId={} resourceType=IMAGE exceptionType={}",
                            ownerId, image.getUploadId(), compensationFailure.getClass().getSimpleName());
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

    /*
     * 읽기, S3 확인, 다시 잠그고 재확인한 뒤 verify 하는 세 구간으로 나눈다(#876). headObject 는 외부 호출이라 응답이 느리면
     * 그만큼 진행 상태 행 잠금과 DB 커넥션을 쥐게 된다. 포스터(PosterUploadService)와 메뉴 이미지
     * (StoreMenuUploadService.confirmMenuImageUpload)가 이미 트랜잭션 밖에서 부르는 것과 같은 구조다.
     *
     * 로고를 덮어쓰는 prepareLogoUpload 는 같은 행을 새 uploadId 와 objectKey 로 PENDING 으로 되돌린다. S3 확인 사이에
     * 그런 덮어쓰기가 끼면 다시 잠근 뒤 처음 uploadId 로 찾지 못해 IMAGE_UPLOAD_NOT_FOUND 가 되므로, 옛 업로드를 확인한
     * 결과로 새 업로드를 VERIFIED 로 만들지 않는다. 다시 잠그는 구간도 prepareUpload 와 같은 진행 상태 행 잠금
     * (findStoreIdForUpdate)이라 서로 직렬화된다.
     */
    public void confirmUpload(Long ownerId, String uploadId) {
        StoreImage image = transactionTemplate.execute(status -> findOwnedImage(ownerId, uploadId));

        // 응답을 못 받은 클라이언트의 재시도는 같은 결과를 성공으로 돌려준다(api-spec.md 멱등성, 코드래빗 리뷰 전 자체 검토로 찾음).
        if (image.getUploadStatus() == StoreImageUploadStatus.VERIFIED) {
            return;
        }

        // S3 확인은 DB 잠금과 커넥션을 쥐지 않은 상태에서 한다.
        storeUploadSupport.verifyUpload(s3Client, mediaBucket, image.getObjectKey(), image.getFileSize(),
                ownerId, image.getUploadId(), "IMAGE");

        // 그 사이 같은 요청이 먼저 VERIFIED 로 만들었으면 다시 verify 하지 않고 성공으로 끝낸다.
        transactionTemplate.executeWithoutResult(status -> {
            StoreImage current = findOwnedImage(ownerId, uploadId);
            if (current.getUploadStatus() == StoreImageUploadStatus.PENDING) {
                current.verify(LocalDateTime.now(clock));
            }
        });
    }

    // 로고 덮어쓰기와 겹치지 않도록 prepareUpload 와 같은 진행 상태 행 잠금을 잡고 소유자를 확인한다.
    private StoreImage findOwnedImage(Long ownerId, String uploadId) {
        Long storeId = storeUploadSupport.findStoreIdForUpdate(ownerId);
        return storeImageRepository.findByUploadId(uploadId)
                .filter(found -> found.getStoreId().equals(storeId))
                .orElseThrow(() -> new StoreException(StoreErrorCode.IMAGE_UPLOAD_NOT_FOUND));
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
