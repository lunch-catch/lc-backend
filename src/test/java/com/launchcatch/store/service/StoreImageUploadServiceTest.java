package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreImage;
import com.launchcatch.store.entity.StoreImageType;
import com.launchcatch.store.entity.StoreImageUploadStatus;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
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
    @Mock private S3Presigner s3Presigner;
    @Mock private S3Client s3Client;
    @Mock private PresignedPutObjectRequest presignedPutObjectRequest;
    @Mock private TransactionTemplate transactionTemplate;

    private StoreImageUploadService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        lenient().when(progressRepository.countDraftStoreOwnedBy(OWNER_ID, STORE_ID)).thenReturn(1L);
        StoreUploadSupport storeUploadSupport = new StoreUploadSupport(progressRepository, s3Presigner);
        Field supportBucketField = StoreUploadSupport.class.getDeclaredField("mediaBucket");
        supportBucketField.setAccessible(true);
        supportBucketField.set(storeUploadSupport, "test-media-bucket");

        service = new StoreImageUploadService(
                storeImageRepository, progressService,
                storeUploadSupport, s3Client, FIXED_CLOCK, transactionTemplate);
        Field bucketField = StoreImageUploadService.class.getDeclaredField("mediaBucket");
        bucketField.setAccessible(true);
        bucketField.set(service, "test-media-bucket");

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("인테리어 업로드를 준비하면 PENDING 행을 만들고 presigned URL을 돌려준다")
    void 인테리어_업로드를_준비한다() throws Exception {
        givenOwnerWithStoreForUpdate();
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

        ArgumentCaptor<PutObjectPresignRequest> presignCaptor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(presignCaptor.capture());
        assertThat(presignCaptor.getValue().putObjectRequest().contentLength()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("로고 업로드를 처음 준비하면 sortOrder 없는 LOGO PENDING 행을 만든다")
    void 로고_업로드를_처음_준비한다() throws Exception {
        givenOwnerWithStoreForUpdate();
        when(storeImageRepository.findByStoreIdAndImageType(STORE_ID, StoreImageType.LOGO))
                .thenReturn(Optional.empty());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        var result = service.prepareLogoUpload(OWNER_ID, "logo.png", "image/png", 1000L);

        assertThat(result.sortOrder()).isNull();
        assertThat(result.uploadUrl()).isEqualTo("https://example.com/presigned");
        ArgumentCaptor<StoreImage> captor = ArgumentCaptor.forClass(StoreImage.class);
        verify(storeImageRepository).save(captor.capture());
        assertThat(captor.getValue().getImageType()).isEqualTo(StoreImageType.LOGO);
        assertThat(captor.getValue().getSortOrder()).isNull();
        assertThat(captor.getValue().getObjectKey()).startsWith("stores/" + STORE_ID + "/logo/").endsWith(".png");
        assertThat(captor.getValue().getUploadStatus()).isEqualTo(StoreImageUploadStatus.PENDING);
    }

    @Test
    @DisplayName("이미 로고가 있으면 새 행을 만들지 않고 그 행을 새 업로드로 덮어쓴다")
    void 이미_로고가_있으면_덮어쓴다() throws Exception {
        givenOwnerWithStoreForUpdate();
        StoreImage existing = StoreImage.preparePending(
                STORE_ID, "old-upload", StoreImageType.LOGO, "stores/20/logo/old.jpg", "old.jpg", "image/jpeg",
                500L, null);
        existing.verify(LocalDateTime.now(FIXED_CLOCK));
        when(storeImageRepository.findByStoreIdAndImageType(STORE_ID, StoreImageType.LOGO))
                .thenReturn(Optional.of(existing));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        var result = service.prepareLogoUpload(OWNER_ID, "logo.jpg", "image/jpeg", 1000L);

        verify(storeImageRepository, never()).save(any(StoreImage.class));
        assertThat(result.uploadId()).isNotEqualTo("old-upload");
        assertThat(existing.getUploadId()).isEqualTo(result.uploadId());
        assertThat(existing.getUploadStatus()).isEqualTo(StoreImageUploadStatus.PENDING);
        assertThat(existing.getVerifiedAt()).isNull();
        assertThat(existing.getFileSize()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("로고 presign이 실패하면 새로 만든 행은 지운다")
    void 로고_presign이_실패하면_새_행을_지운다() {
        givenOwnerWithStoreForUpdate();
        when(storeImageRepository.findByStoreIdAndImageType(STORE_ID, StoreImageType.LOGO))
                .thenReturn(Optional.empty());
        RuntimeException presignFailure = new RuntimeException("자격 증명을 가져오지 못했다");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(presignFailure);

        assertThatThrownBy(() -> service.prepareLogoUpload(OWNER_ID, "logo.jpg", "image/jpeg", 1000L))
                .isSameAs(presignFailure);

        verify(storeImageRepository).delete(any(StoreImage.class));
    }

    @Test
    @DisplayName("로고 presign이 실패해도 덮어쓴 행은 지우지 않는다")
    void 로고_presign이_실패해도_덮어쓴_행은_남긴다() {
        givenOwnerWithStoreForUpdate();
        StoreImage existing = StoreImage.preparePending(
                STORE_ID, "old-upload", StoreImageType.LOGO, "stores/20/logo/old.jpg", "old.jpg", "image/jpeg",
                500L, null);
        when(storeImageRepository.findByStoreIdAndImageType(STORE_ID, StoreImageType.LOGO))
                .thenReturn(Optional.of(existing));
        RuntimeException presignFailure = new RuntimeException("자격 증명을 가져오지 못했다");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(presignFailure);

        assertThatThrownBy(() -> service.prepareLogoUpload(OWNER_ID, "logo.jpg", "image/jpeg", 1000L))
                .isSameAs(presignFailure);

        verify(storeImageRepository, never()).delete(any(StoreImage.class));
    }

    @Test
    @DisplayName("로고의 형식과 크기도 인테리어와 같은 기준으로 검증한다")
    void 로고의_형식과_크기를_검증한다() {
        assertThatThrownBy(() -> service.prepareLogoUpload(OWNER_ID, "logo.gif", "image/gif", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.prepareLogoUpload(OWNER_ID, "logo.jpg", "image/jpeg", 10L * 1024 * 1024 + 1))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("presign이 실패하면 예약해 둔 PENDING 행을 지우고 그대로 던진다")
    void presign이_실패하면_예약을_지운다() {
        givenOwnerWithStoreForUpdate();
        when(storeImageRepository.countByStoreIdAndImageType(STORE_ID, StoreImageType.INTERIOR)).thenReturn(0L);
        RuntimeException presignFailure = new RuntimeException("자격 증명을 가져오지 못했다");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(presignFailure);

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 1000L))
                .isSameAs(presignFailure);

        verify(storeImageRepository).save(any(StoreImage.class));
        verify(storeImageRepository).delete(any(StoreImage.class));
    }

    @Test
    @DisplayName("인테리어 이미지가 이미 3개면 거부한다")
    void 인테리어_이미지_3개_제한을_넘으면_거부한다() {
        givenOwnerWithStoreForUpdate();
        when(storeImageRepository.countByStoreIdAndImageType(STORE_ID, StoreImageType.INTERIOR)).thenReturn(3L);

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.INTERIOR_IMAGE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("등록 진행 상태가 없으면 업로드 준비를 거부한다")
    void 등록_진행_상태가_없으면_준비를_거부한다() {
        when(progressRepository.findByOwnerIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
    }

    @Test
    @DisplayName("허용하지 않는 파일 형식이면 거부한다")
    void 허용하지_않는_형식이면_거부한다() {
        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.gif", "image/gif", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED));
        verify(progressRepository, never()).findByOwnerIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("10MB를 넘는 파일 크기면 거부한다")
    void 파일_크기_상한을_넘으면_거부한다() {
        assertThatThrownBy(() -> service.prepareInteriorUpload(OWNER_ID, "photo.jpg", "image/jpeg", 10_485_761L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("등록 진행 상태가 없으면 업로드 확인을 거부한다")
    void 등록_진행_상태가_없으면_확인을_거부한다() {
        when(progressRepository.findByOwnerIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
    }

    @Test
    @DisplayName("업로드를 확인하면 headObject로 실제 업로드를 확인하고 VERIFIED로 바꾼다")
    void 업로드를_확인한다() {
        givenOwnerWithStoreForUpdate();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1000L).build());

        service.confirmUpload(OWNER_ID, "upload-1");

        assertThat(image.getUploadStatus().name()).isEqualTo("VERIFIED");
        verify(s3Client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("이미 VERIFIED인 업로드를 다시 확인해도 headObject를 부르지 않고 그대로 성공한다(응답을 못 받은 재시도)")
    void 이미_확인된_업로드는_재시도해도_성공한다() {
        givenOwnerWithStoreForUpdate();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        image.verify(java.time.LocalDateTime.now(FIXED_CLOCK));
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));

        service.confirmUpload(OWNER_ID, "upload-1");

        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("실제로 업로드되지 않았으면 거부한다")
    void 업로드되지_않았으면_거부한다() {
        givenOwnerWithStoreForUpdate();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().message("not found").statusCode(404).build());

        assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_NOT_UPLOADED));
    }

    @Test
    @DisplayName("headObject가 404 아닌 오류를 돌려주면 그대로 전파한다")
    void headObject가_404가_아니면_전파한다() {
        givenOwnerWithStoreForUpdate();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        S3Exception serverError = (S3Exception) S3Exception.builder().message("internal error").statusCode(500).build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(serverError);

        assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-1"))
                .isSameAs(serverError);
    }

    @Test
    @DisplayName("실제 업로드 크기가 선언한 크기를 넘으면 거부한다")
    void 실제_크기가_선언한_크기를_넘으면_거부한다() {
        givenOwnerWithStoreForUpdate();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-1", StoreImageType.INTERIOR, "stores/20/interior/upload-1.jpg",
                "photo.jpg", "image/jpeg", 1000L, 1);
        when(storeImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1001L).build());

        assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("다른 가게의 업로드 요청이면 거부한다")
    void 다른_가게의_업로드면_거부한다() {
        givenOwnerWithStoreForUpdate();
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
        givenOwnerWithStoreForUpdate();
        when(storeImageRepository.existsByStoreIdAndImageTypeAndUploadStatus(
                STORE_ID, StoreImageType.LOGO, StoreImageUploadStatus.VERIFIED)).thenReturn(false);

        assertThatThrownBy(() -> service.completeImagesStep(OWNER_ID, 3L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.LOGO_IMAGE_REQUIRED));
        verify(progressService, never()).completeStep(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("로고가 있으면 IMAGES 단계를 완료하고 다음 상태를 돌려준다")
    void 로고가_있으면_완료한다() {
        givenOwnerWithStoreForUpdate();
        when(storeImageRepository.existsByStoreIdAndImageTypeAndUploadStatus(
                STORE_ID, StoreImageType.LOGO, StoreImageUploadStatus.VERIFIED)).thenReturn(true);
        var snapshot = new StoreRegistrationProgressSnapshot(STORE_ID, StoreRegistrationStep.MENU, 4L);
        when(progressService.completeStep(OWNER_ID, 3L, StoreRegistrationStep.IMAGES, null)).thenReturn(snapshot);

        StoreRegistrationProgressResponse response = service.completeImagesStep(OWNER_ID, 3L);

        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(response.version()).isEqualTo(4L);
    }

    @ParameterizedTest
    @MethodSource("rejectedUploadSizes")
    void 크기_검증_실패의_원인과_레벨을_구분하고_민감정보를_숨긴다(
            Long actualSize, Long declaredSize, Level expectedLevel, String reason) {
        var image = givenPendingImage(declaredSize);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(actualSize).build());

        try (var logs = StoreUploadLogCapture.attach(StoreUploadSupport.class)) {
            assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-log"))
                    .isInstanceOfSatisfying(StoreException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_FILE_TOO_LARGE));

            assertThat(image.getUploadStatus().name()).isEqualTo("PENDING");
            logs.assertEvent("STORE_UPLOAD_CONFIRM_FAILED", expectedLevel,
                    "ownerId=7", "uploadId=upload-log", "resourceType=IMAGE", "reason=" + reason);
            logs.assertNoSensitiveData("private/key", "private-photo.jpg");
        }
    }

    static Stream<Arguments> rejectedUploadSizes() {
        return Stream.of(
                Arguments.of(null, 1000L, Level.ERROR, "SIZE_METADATA_MISSING"),
                Arguments.of(1000L, null, Level.ERROR, "SIZE_METADATA_MISSING"),
                Arguments.of(1001L, 1000L, Level.INFO, "INVALID_SIZE"),
                Arguments.of(10_485_761L, 20_000_000L, Level.INFO, "INVALID_SIZE"));
    }

    @Test
    void S3_클라이언트_실패는_메시지_없이_ERROR로_기록하고_그대로_전파한다() {
        var image = givenPendingImage(1000L);
        var failure = SdkClientException.create("private-cause-message private/key private-photo.jpg");
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);

        try (var logs = StoreUploadLogCapture.attach(StoreUploadSupport.class)) {
            assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-log")).isSameAs(failure);

            assertThat(image.getUploadStatus().name()).isEqualTo("PENDING");
            logs.assertEvent("STORE_UPLOAD_CONFIRM_FAILED", Level.ERROR,
                    "ownerId=7", "uploadId=upload-log", "resourceType=IMAGE", "exceptionType=SdkClientException");
            logs.assertNoSensitiveData("private-cause-message", "private/key", "private-photo.jpg");
        }
    }

    @ParameterizedTest
    @MethodSource("s3Failures")
    void S3_미업로드와_시스템_실패의_레벨을_구분한다(
            int statusCode, Level expectedLevel, String expectedField, Class<? extends RuntimeException> expectedException) {
        var image = givenPendingImage(1000L);
        var failure = (S3Exception) S3Exception.builder().statusCode(statusCode)
                .message("private-cause-message private/key private-photo.jpg").build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);

        try (var logs = StoreUploadLogCapture.attach(StoreUploadSupport.class)) {
            assertThatThrownBy(() -> service.confirmUpload(OWNER_ID, "upload-log"))
                    .isInstanceOf(expectedException);

            assertThat(image.getUploadStatus().name()).isEqualTo("PENDING");
            logs.assertEvent("STORE_UPLOAD_CONFIRM_FAILED", expectedLevel,
                    "ownerId=7", "uploadId=upload-log", "resourceType=IMAGE", expectedField);
            logs.assertNoSensitiveData("private-cause-message", "private/key", "private-photo.jpg");
        }
    }

    static Stream<Arguments> s3Failures() {
        return Stream.of(
                Arguments.of(404, Level.INFO, "reason=NOT_UPLOADED", StoreException.class),
                Arguments.of(500, Level.ERROR, "httpStatus=500", S3Exception.class));
    }

    @Test
    void 인테리어_예약_보상_실패는_예약_식별자를_남기고_메시지를_숨긴다() {
        givenOwnerWithStoreForUpdate();
        assertCompensationFailure(() -> service.prepareInteriorUpload(
                OWNER_ID, "private-photo.jpg", "image/jpeg", 1000L));
    }

    @Test
    void 신규_로고_예약_보상_실패는_예약_식별자를_남기고_메시지를_숨긴다() {
        givenOwnerWithStoreForUpdate();
        assertCompensationFailure(() -> service.prepareLogoUpload(
                OWNER_ID, "private-photo.jpg", "image/jpeg", 1000L));
    }

    private void assertCompensationFailure(Runnable prepare) {
        var presignFailure = new IllegalStateException("private-presign-message");
        var cleanupFailure = new IllegalStateException("private-cleanup-message");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(presignFailure);
        doThrow(cleanupFailure).when(storeImageRepository).delete(any(StoreImage.class));

        try (var logs = StoreUploadLogCapture.attach(StoreImageUploadService.class)) {
            assertThatThrownBy(prepare::run).isSameAs(cleanupFailure);

            var reservation = ArgumentCaptor.forClass(StoreImage.class);
            verify(storeImageRepository).delete(reservation.capture());
            assertThat(reservation.getValue().getUploadId()).isNotBlank();
            logs.assertEvent("STORE_UPLOAD_PREPARE_FAILED", Level.ERROR,
                    "ownerId=7", "resourceType=IMAGE", "exceptionType=IllegalStateException");
            logs.assertEvent("STORE_UPLOAD_COMPENSATION_FAILED", Level.ERROR,
                    "ownerId=7", "uploadId=" + reservation.getValue().getUploadId(), "resourceType=IMAGE");
            logs.assertNoSensitiveData("private-presign-message", "private-cleanup-message", "private-photo.jpg",
                    reservation.getValue().getObjectKey());
        }
    }

    private StoreImage givenPendingImage(Long declaredSize) {
        givenOwnerWithStoreForUpdate();
        StoreImage image = StoreImage.preparePending(
                STORE_ID, "upload-log", StoreImageType.INTERIOR, "private/key",
                "private-photo.jpg", "image/jpeg", declaredSize, 1);
        when(storeImageRepository.findByUploadId("upload-log")).thenReturn(Optional.of(image));
        return image;
    }

    private void givenOwnerWithStoreForUpdate() {
        when(progressRepository.findByOwnerIdForUpdate(OWNER_ID)).thenReturn(Optional.of(progress()));
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
