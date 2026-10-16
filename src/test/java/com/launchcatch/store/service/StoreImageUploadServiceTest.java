package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreImage;
import com.launchcatch.store.entity.StoreImageType;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreImageRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.lang.reflect.Field;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class StoreImageUploadServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long STORE_ID = 20L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock private StoreImageRepository storeImageRepository;
    @Mock private StoreRegistrationProgressRepository progressRepository;
    @Mock private StoreRegistrationProgressService progressService;
    @Mock private OwnerStatusQuery ownerStatusQuery;
    @Mock private S3Presigner s3Presigner;
    @Mock private S3Client s3Client;
    @Mock private PresignedPutObjectRequest presignedPutObjectRequest;

    private StoreImageUploadService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new StoreImageUploadService(
                storeImageRepository, progressRepository, progressService, ownerStatusQuery,
                s3Presigner, s3Client, FIXED_CLOCK);
        Field bucketField = StoreImageUploadService.class.getDeclaredField("mediaBucket");
        bucketField.setAccessible(true);
        bucketField.set(service, "test-media-bucket");
    }

    @Test
    @DisplayName("인테리어 업로드를 준비하면 PENDING 행을 만들고 presigned URL을 돌려준다")
    void 인테리어_업로드를_준비한다() throws Exception {
        givenOnboardingOwnerWithStore();
        when(storeImageRepository.countByStoreIdAndImageType(STORE_ID, StoreImageType.INTERIOR)).thenReturn(0L);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        var result = service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 1000L);

        assertThat(result.sortOrder()).isEqualTo(1);
        assertThat(result.uploadUrl()).isEqualTo("https://example.com/presigned");
        assertThat(result.uploadId()).isNotBlank();

        ArgumentCaptor<StoreImage> captor = ArgumentCaptor.forClass(StoreImage.class);
        verify(storeImageRepository).save(captor.capture());
        assertThat(captor.getValue().getSortOrder()).isEqualTo(1);
        assertThat(captor.getValue().getImageType()).isEqualTo(StoreImageType.INTERIOR);
    }

    @Test
    @DisplayName("인테리어 이미지가 이미 3개면 거부한다")
    void 인테리어_이미지_3개_제한을_넘으면_거부한다() {
        givenOnboardingOwnerWithStore();
        when(storeImageRepository.countByStoreIdAndImageType(STORE_ID, StoreImageType.INTERIOR)).thenReturn(3L);

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.INTERIOR_IMAGE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("허용하지 않는 파일 형식이면 거부한다")
    void 허용하지_않는_형식이면_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.gif", "image/gif", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED));
        verify(progressRepository, never()).findByOwnerId(anyLong());
    }

    @Test
    @DisplayName("10MB를 넘는 파일 크기면 거부한다")
    void 파일_크기_상한을_넘으면_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 10_485_761L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("ONBOARDING이 아니면 거부한다")
    void ONBOARDING이_아니면_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STEP_CONFLICT));
    }

    @Test
    @DisplayName("업로드를 확인하면 headObject로 실제 업로드를 확인하고 VERIFIED로 바꾼다")
    void 업로드를_확인한다() {
        givenOnboardingOwnerWithStore();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));

        service.confirmUpload(OWNER_ID, "upload-1");

        assertThat(image.getUploadStatus().name()).isEqualTo("VERIFIED");
        verify(s3Client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("실제로 업로드되지 않았으면 거부한다")
    void 업로드되지_않았으면_거부한다() {
        givenOnboardingOwnerWithStore();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_NOT_UPLOADED));
    }

    @Test
    @DisplayName("다른 가게의 업로드 요청이면 거부한다")
    void 다른_가게의_업로드면_거부한다() {
        givenOnboardingOwnerWithStore();
        StoreImage otherStoreImage = StoreImage.preparePending(
                999L, "upload-1", StoreImageType.INTERIOR, "stores/999/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(otherStoreImage));

        assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_UPLOAD_NOT_FOUND));
    }

    @Test
    @DisplayName("로고가 없으면 이미지 단계를 완료할 수 없다")
    void 로고가_없으면_완료할_수_없다() {
        givenOnboardingOwnerWithStore();
        when(storeImageRepository.existsByStoreIdAndImageType(STORE_ID, StoreImageType.LOGO)).thenReturn(false);

        assertThatThrownBy(() -> service.completeImagesStep(OWNER_ID, 3L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.LOGO_IMAGE_REQUIRED));
        verify(progressService, never()).completeStep(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("로고가 있으면 IMAGES 단계를 완료하고 다음 상태를 돌려준다")
    void 로고가_있으면_완료한다() {
        givenOnboardingOwnerWithStore();
        when(storeImageRepository.existsByStoreIdAndImageType(STORE_ID, StoreImageType.LOGO)).thenReturn(true);
        var snapshot = new StoreRegistrationProgressSnapshot(STORE_ID, StoreRegistrationStep.MENU, 4L);
        when(progressService.completeStep(OWNER_ID, 3L, StoreRegistrationStep.IMAGES, null)).thenReturn(snapshot);

        StoreRegistrationProgressResponse response = service.completeImagesStep(OWNER_ID, 3L);

        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(response.version()).isEqualTo(4L);
    }

    private void givenOnboardingOwnerWithStore() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));
        when(progressRepository.findByOwnerId(OWNER_ID)).thenReturn(Optional.of(progress()));
    }

    private StoreRegistrationProgress progress() {
        try {
            StoreRegistrationProgress progress = StoreRegistrationProgress.start(OWNER_ID);
            Field storeIdField = StoreRegistrationProgress.class.getDeclaredField("storeId");
            storeIdField.setAccessible(true);
            storeIdField.set(progress, STORE_ID);
            return progress;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
