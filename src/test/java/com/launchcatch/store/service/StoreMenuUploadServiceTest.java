package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreMenu;
import com.launchcatch.store.entity.StoreMenuImage;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreMenuImageRepository;
import com.launchcatch.store.repository.StoreMenuRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.lang.reflect.Field;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class StoreMenuUploadServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long STORE_ID = 20L;
    private static final Long MENU_ID = 100L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-17T05:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock private StoreMenuRepository storeMenuRepository;
    @Mock private StoreMenuImageRepository storeMenuImageRepository;
    @Mock private StoreRegistrationProgressRepository progressRepository;
    @Mock private StoreRegistrationProgressService progressService;
    @Mock private S3Presigner s3Presigner;
    @Mock private S3Client s3Client;
    @Mock private PresignedPutObjectRequest presignedPutObjectRequest;
    @Mock private TransactionTemplate transactionTemplate;

    private StoreMenuUploadService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        StoreUploadSupport storeUploadSupport = new StoreUploadSupport(progressRepository, s3Presigner);
        Field supportBucketField = StoreUploadSupport.class.getDeclaredField("mediaBucket");
        supportBucketField.setAccessible(true);
        supportBucketField.set(storeUploadSupport, "test-media-bucket");

        service = new StoreMenuUploadService(
                storeMenuRepository, storeMenuImageRepository, progressService,
                storeUploadSupport, s3Client, FIXED_CLOCK, transactionTemplate);
        Field bucketField = StoreMenuUploadService.class.getDeclaredField("mediaBucket");
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
    @DisplayName("메뉴를 등록하면 저장하고 다음 순번을 매긴다")
    void 메뉴를_등록한다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.countByStoreId(STORE_ID)).thenReturn(0L);
        when(storeMenuRepository.save(any(StoreMenu.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.registerMenu(OWNER_ID, "제육볶음", 9000L, "매콤합니다");

        assertThat(response.sortOrder()).isEqualTo(1);

        ArgumentCaptor<StoreMenu> captor = ArgumentCaptor.forClass(StoreMenu.class);
        verify(storeMenuRepository).save(captor.capture());
        assertThat(captor.getValue().getStoreId()).isEqualTo(STORE_ID);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("메뉴가 이미 3개면 거부한다")
    void 메뉴_3개_제한을_넘으면_거부한다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.countByStoreId(STORE_ID)).thenReturn(3L);

        assertThatThrownBy(() -> service.registerMenu(OWNER_ID, "제육볶음", 9000L, null))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.MENU_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("등록 진행 상태가 없으면 메뉴 등록을 거부한다")
    void 등록_진행_상태가_없으면_메뉴_등록을_거부한다() {
        when(progressRepository.findByOwnerIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerMenu(OWNER_ID, "제육볶음", 9000L, null))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
    }

    @Test
    @DisplayName("메뉴 이미지 업로드를 준비하면 PENDING 행을 만들고 presigned URL을 돌려준다")
    void 메뉴_이미지_업로드를_준비한다() throws Exception {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        var result = service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.jpg", "image/jpeg", 1000L);

        assertThat(result.uploadUrl()).isEqualTo("https://example.com/presigned");
        assertThat(result.uploadId()).isNotBlank();

        ArgumentCaptor<StoreMenuImage> captor = ArgumentCaptor.forClass(StoreMenuImage.class);
        verify(storeMenuImageRepository).save(captor.capture());
        assertThat(captor.getValue().getStoreMenuId()).isEqualTo(MENU_ID);
    }

    @Test
    @DisplayName("presign이 실패하면 예약해 둔 PENDING 행을 지우고 그대로 던진다")
    void presign이_실패하면_예약을_지운다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);
        RuntimeException presignFailure = new RuntimeException("자격 증명을 가져오지 못했다");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(presignFailure);

        assertThatThrownBy(() -> service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.jpg", "image/jpeg", 1000L))
                .isSameAs(presignFailure);

        verify(storeMenuImageRepository).save(any(StoreMenuImage.class));
        verify(storeMenuImageRepository).delete(any(StoreMenuImage.class));
    }

    @Test
    @DisplayName("그 메뉴가 없거나 본인 가게 소유가 아니면 거부한다")
    void 다른_가게의_메뉴면_거부한다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.jpg", "image/jpeg", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.MENU_NOT_FOUND));
    }

    @Test
    @DisplayName("이미 이미지가 등록된 메뉴면 거부한다")
    void 이미_이미지가_있는_메뉴면_거부한다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);
        StoreMenuImage existing = StoreMenuImage.preparePending(
                MENU_ID, "upload-0", "stores/20/menu/100/upload-0.jpg", "menu.jpg", "image/jpeg", 1000L);
        existing.verify(java.time.LocalDateTime.now(FIXED_CLOCK));
        when(storeMenuImageRepository.findByStoreMenuId(MENU_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.jpg", "image/jpeg", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.MENU_IMAGE_ALREADY_EXISTS));
        verify(storeMenuImageRepository, never()).delete(any(StoreMenuImage.class));
    }

    @Test
    @DisplayName("포기한 PENDING 이미지가 남아 있으면 지우고 새로 받는다")
    void 포기한_PENDING_이미지는_지우고_새로_받는다() throws Exception {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);
        StoreMenuImage abandoned = StoreMenuImage.preparePending(
                MENU_ID, "upload-0", "stores/20/menu/100/upload-0.jpg", "menu.jpg", "image/jpeg", 1000L);
        when(storeMenuImageRepository.findByStoreMenuId(MENU_ID)).thenReturn(Optional.of(abandoned));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        var result = service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.jpg", "image/jpeg", 1000L);

        assertThat(result.uploadId()).isNotEqualTo("upload-0");
        verify(storeMenuImageRepository).delete(abandoned);
        verify(storeMenuImageRepository).save(any(StoreMenuImage.class));
    }

    @Test
    @DisplayName("허용하지 않는 파일 형식이면 거부한다")
    void 허용하지_않는_형식이면_거부한다() {
        assertThatThrownBy(() -> service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.gif", "image/gif", 1000L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED));
        verify(progressRepository, never()).findByOwnerIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("10MB를 넘는 파일 크기면 거부한다")
    void 파일_크기_상한을_넘으면_거부한다() {
        assertThatThrownBy(() -> service.prepareMenuImageUpload(OWNER_ID, MENU_ID, "menu.jpg", "image/jpeg", 10_485_761L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("등록 진행 상태가 없으면 업로드 확인을 거부한다")
    void 등록_진행_상태가_없으면_확인을_거부한다() {
        when(progressRepository.findByOwnerIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmMenuImageUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
    }

    @Test
    @DisplayName("업로드를 확인하면 headObject로 실제 업로드를 확인하고 VERIFIED로 바꾼다")
    void 업로드를_확인한다() {
        givenOwnerWithStoreForUpdate();
        StoreMenuImage image = StoreMenuImage.preparePending(
                MENU_ID, "upload-1", "stores/20/menu/100/upload-1.jpg", "menu.jpg", "image/jpeg", 1000L);
        when(storeMenuImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1000L).build());

        service.confirmMenuImageUpload(OWNER_ID, "upload-1");

        assertThat(image.getUploadStatus().name()).isEqualTo("VERIFIED");
        verify(s3Client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("이미 VERIFIED인 업로드를 다시 확인해도 headObject를 부르지 않고 그대로 성공한다(응답을 못 받은 재시도)")
    void 이미_확인된_업로드는_재시도해도_성공한다() {
        givenOwnerWithStoreForUpdate();
        StoreMenuImage image = StoreMenuImage.preparePending(
                MENU_ID, "upload-1", "stores/20/menu/100/upload-1.jpg", "menu.jpg", "image/jpeg", 1000L);
        image.verify(java.time.LocalDateTime.now(FIXED_CLOCK));
        when(storeMenuImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);

        service.confirmMenuImageUpload(OWNER_ID, "upload-1");

        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("실제로 업로드되지 않았으면 거부한다")
    void 업로드되지_않았으면_거부한다() {
        givenOwnerWithStoreForUpdate();
        StoreMenuImage image = StoreMenuImage.preparePending(
                MENU_ID, "upload-1", "stores/20/menu/100/upload-1.jpg", "menu.jpg", "image/jpeg", 1000L);
        when(storeMenuImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(image));
        when(storeMenuRepository.existsByIdAndStoreId(MENU_ID, STORE_ID)).thenReturn(true);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().message("not found").statusCode(404).build());

        assertThatThrownBy(() -> service.confirmMenuImageUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.IMAGE_NOT_UPLOADED));
    }

    @Test
    @DisplayName("다른 가게의 메뉴 이미지 업로드 확인이면 거부한다")
    void 다른_가게의_메뉴_이미지_확인이면_거부한다() {
        givenOwnerWithStoreForUpdate();
        StoreMenuImage otherStoreImage = StoreMenuImage.preparePending(
                999L, "upload-1", "stores/999/menu/999/upload-1.jpg", "menu.jpg", "image/jpeg", 1000L);
        when(storeMenuImageRepository.findByUploadId("upload-1")).thenReturn(Optional.of(otherStoreImage));
        when(storeMenuRepository.existsByIdAndStoreId(999L, STORE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.confirmMenuImageUpload(OWNER_ID, "upload-1"))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.MENU_NOT_FOUND));
    }

    @Test
    @DisplayName("VERIFIED가 아닌 이미지를 가진 메뉴가 있으면 메뉴 단계를 완료할 수 없다")
    void 미완성_메뉴가_있으면_완료할_수_없다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.countWithoutVerifiedImage(STORE_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.completeMenuStep(OWNER_ID, 5L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.MENU_IMAGE_REQUIRED));
        verify(progressService, never()).completeStep(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("모든 메뉴에 VERIFIED 이미지가 있으면(0개 포함) MENU 단계를 완료한다")
    void 메뉴_단계를_완료한다() {
        givenOwnerWithStoreForUpdate();
        when(storeMenuRepository.countWithoutVerifiedImage(STORE_ID)).thenReturn(0L);
        var snapshot = new StoreRegistrationProgressSnapshot(STORE_ID, StoreRegistrationStep.MENU, 6L);
        when(progressService.completeStep(OWNER_ID, 5L, StoreRegistrationStep.MENU, null)).thenReturn(snapshot);

        StoreRegistrationProgressResponse response = service.completeMenuStep(OWNER_ID, 5L);

        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(response.version()).isEqualTo(6L);
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
