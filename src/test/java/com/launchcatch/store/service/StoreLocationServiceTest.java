package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.store.StoreBasicInfo;
import com.launchcatch.store.contract.StoreLocationSaveCommand;
import com.launchcatch.store.entity.OwnerTermsAgreement;
import com.launchcatch.store.entity.Store;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import com.launchcatch.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class StoreLocationServiceTest {
    private final StoreRegistrationProgressRepository progresses = mock(StoreRegistrationProgressRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final OwnerTermsAgreementRepository agreements = mock(OwnerTermsAgreementRepository.class);
    private final StoreLocationService service = new StoreLocationService(progresses, stores, agreements,
            new StoreRegistrationProgressService(progresses));
    private StoreRegistrationProgress progress;
    private Store store;

    @BeforeEach
    void prepare() {
        progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        progress.completeStep(1, StoreRegistrationStep.STORE_INFO, 9L);
        store = Store.createDraft(7L, new StoreBasicInfo("가게", "KOREAN", "대표", "1234567890", "021234567"), LocalDateTime.now());
        ReflectionTestUtils.setField(store, "id", 9L);
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
        when(stores.findByOwnerId(7L)).thenReturn(Optional.of(store));
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.of(OwnerTermsAgreement.record(7L, LocalDateTime.now())));
    }

    private StoreLocationSaveCommand command(long version) {
        return new StoreLocationSaveCommand(version, "123", "서울 강남구 테헤란로 123", new BigDecimal("37.5"), new BigDecimal("127.0"));
    }

    @Test
    void 최초_저장은_기존_초안에_위치를_반영하고_HOURS로_진행한다() {
        var result = service.save(7L, command(2));
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.resumeStep()).isEqualTo("HOURS");
        assertThat(result.version()).isEqualTo(3);
        assertThat(store.getKakaoPlaceId()).isEqualTo("123");
        assertThat(store.getRoadAddress()).isEqualTo(command(2).roadAddress());
        assertThat(store.getLatitude()).isEqualByComparingTo("37.5");
        assertThat(store.getLongitude()).isEqualByComparingTo("127.0");
        assertThat(store.getName()).isEqualTo("가게");
        assertThat(store.isFinalized()).isFalse();
    }

    @Test
    void 완료한_위치를_덮어써도_재개_단계를_되돌리지_않는다() {
        service.save(7L, command(2));
        progress.completeStep(3, StoreRegistrationStep.HOURS, null);
        var result = service.save(7L, new StoreLocationSaveCommand(4L, "456", "부산 중구 중앙대로 1", BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(result.resumeStep()).isEqualTo("IMAGES");
        assertThat(result.version()).isEqualTo(5);
        assertThat(store.getKakaoPlaceId()).isEqualTo("456");
        assertThat(store.getLatitude()).isEqualByComparingTo("0");
    }

    @Test
    void 같은_버전의_재요청은_기존_위치를_보존한다() {
        service.save(7L, command(2));
        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        assertThat(progress.getVersion()).isEqualTo(3);
        assertThat(store.getKakaoPlaceId()).isEqualTo("123");
    }

    @Test
    void 진행_상태가_없으면_거부한다() {
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.empty());

        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @Test
    void LOCATION_이전_단계면_거부한다() {
        progress = StoreRegistrationProgress.start(7L);
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));

        assertCode(() -> service.save(7L, command(0)), StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isNull();
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.TERMS);
        assertThat(progress.getVersion()).isZero();
    }

    @Test
    void 본인_초안이_없으면_거부한다() {
        when(stores.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @Test
    void 초안과_진행_상태의_가게_ID가_다르면_거부한다() {
        ReflectionTestUtils.setField(store, "id", 10L);

        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @Test
    void 최종_등록된_가게는_거부한다() {
        ReflectionTestUtils.setField(store, "finalized", true);

        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @Test
    void 약관_동의가_없으면_저장하지_않는다() {
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @Test
    void 사업자_검증_성공_시각이_없으면_저장하지_않는다() {
        ReflectionTestUtils.setField(store, "businessVerifiedAt", null);

        assertCode(() -> service.save(7L, command(2)), StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @Test
    void 필수_기본_정보가_없으면_위치를_저장하지_않는다() {
        ReflectionTestUtils.setField(store, "phone", null);
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L, command(2)));
        assertThat(store.getRoadAddress()).isNull();
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("validBoundaryLocations")
    void 좌표_경계와_문자열_최대_길이의_위치를_저장한다(String place, String address, BigDecimal latitude, BigDecimal longitude) {
        var result = service.save(7L, new StoreLocationSaveCommand(2L, place, address, latitude, longitude));
        assertThat(store.getKakaoPlaceId()).isEqualTo(place);
        assertThat(store.getRoadAddress()).isEqualTo(address);
        assertThat(store.getLatitude()).isEqualByComparingTo(latitude);
        assertThat(store.getLongitude()).isEqualByComparingTo(longitude);
        assertThat(result.resumeStep()).isEqualTo("HOURS");
        assertThat(result.version()).isEqualTo(3);
    }

    static Stream<Arguments> validBoundaryLocations() {
        return Stream.of(
                Arguments.of("1".repeat(50), "가".repeat(255), new BigDecimal("-90"), new BigDecimal("180")),
                Arguments.of("1", "주소", new BigDecimal("90"), new BigDecimal("-180")));
    }

    @Test
    void 카카오_원본_좌표를_DB_정밀도에_맞게_저장한다() {
        service.save(7L, new StoreLocationSaveCommand(2L, "1", "주소",
                new BigDecimal("37.123456789"), new BigDecimal("127.123456749")));
        assertThat(store.getLatitude()).isEqualByComparingTo("37.1234568");
        assertThat(store.getLongitude()).isEqualByComparingTo("127.1234567");
    }

    @ParameterizedTest
    @MethodSource("invalidLocations")
    void 잘못된_위치_입력은_가게와_진행_상태를_변경하지_않는다(String place, String address, BigDecimal latitude, BigDecimal longitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L,
                new StoreLocationSaveCommand(2L, place, address, latitude, longitude)));
        assertThat(store.getKakaoPlaceId()).isNull();
        assertThat(store.getRoadAddress()).isNull();
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(progress.getVersion()).isEqualTo(2);
    }

    static Stream<Arguments> invalidLocations() {
        var zero = BigDecimal.ZERO;
        return Stream.of(
                Arguments.of(null, "주소", zero, zero),
                Arguments.of(" ", "주소", zero, zero),
                Arguments.of("1".repeat(51), "주소", zero, zero),
                Arguments.of("1", null, zero, zero),
                Arguments.of("1", " ", zero, zero),
                Arguments.of("1", "가".repeat(256), zero, zero),
                Arguments.of("1", "주소", null, zero),
                Arguments.of("1", "주소", zero, null),
                Arguments.of("1", "주소", new BigDecimal("-90.0000001"), zero),
                Arguments.of("1", "주소", new BigDecimal("90.0000001"), zero),
                Arguments.of("1", "주소", zero, new BigDecimal("-180.0000001")),
                Arguments.of("1", "주소", zero, new BigDecimal("180.0000001")));
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, StoreErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(StoreException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
