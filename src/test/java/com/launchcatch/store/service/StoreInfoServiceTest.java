package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.store.BusinessVerificationStatus;
import com.launchcatch.store.StoreBasicInfo;
import com.launchcatch.store.contract.StoreInfoSaveCommand;
import com.launchcatch.store.entity.Store;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import com.launchcatch.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class StoreInfoServiceTest {
    private final StoreRegistrationProgressRepository progresses = mock(StoreRegistrationProgressRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final StoreRegistrationProgressService registration = new StoreRegistrationProgressService(progresses);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final StoreInfoService service = new StoreInfoService(progresses, stores, registration, clock);
    private StoreRegistrationProgress progress;

    @BeforeEach
    void setUp() {
        progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
        when(stores.findByOwnerId(7L)).thenReturn(Optional.empty());
        when(stores.saveAndFlush(any())).thenAnswer(call -> {
            Store store = call.getArgument(0);
            ReflectionTestUtils.setField(store, "id", 9L);
            return store;
        });
        when(progresses.countDraftStoreOwnedBy(7L, 9L)).thenReturn(1L);
    }

    private StoreInfoSaveCommand request(long version, String number) {
        return new StoreInfoSaveCommand(version, "가게", "KOREAN", "대표자", number, "02-123-4567");
    }

    @Test
    void 최초_성공은_검증된_초안을_만들고_LOCATION으로_진행한다() {
        var result = service.save(7L, request(1, "1234567890"));
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.LOCATION.name());
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.verificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED.name());
        var captured = org.mockito.ArgumentCaptor.forClass(Store.class);
        verify(stores).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getBusinessVerifiedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(captured.getValue().isFinalized()).isFalse();
    }

    @Test
    void Mock_실패는_가게와_진행_상태를_저장하지_않는다() {
        var result = service.save(7L, request(1, "0000000000"));
        assertThat(result.verificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED.name());
        assertThat(result.storeId()).isNull();
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.STORE_INFO.name());
        assertThat(result.version()).isEqualTo(1);
        verify(stores, never()).saveAndFlush(any());
        verify(progresses, never()).saveAndFlush(any());
    }

    private Store existingStore() {
        var store = Store.createDraft(7L, new StoreBasicInfo("기존", "CHINESE", "대표", "1234567890", "021234567"),
                LocalDateTime.now(clock).minusDays(1));
        ReflectionTestUtils.setField(store, "id", 9L);
        progress.completeStep(1, StoreRegistrationStep.STORE_INFO, 9L);
        progress.completeStep(2, StoreRegistrationStep.LOCATION, null);
        when(stores.findByOwnerId(7L)).thenReturn(Optional.of(store));
        return store;
    }

    @Test
    void 완료한_단계의_수정은_기존_가게와_재개_단계를_유지한다() {
        var store = existingStore();
        var result = service.save(7L, request(3, "9876543210"));
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.HOURS.name());
        assertThat(result.version()).isEqualTo(4);
        assertThat(store.getName()).isEqualTo("가게");
        assertThat(store.getBusinessRegistrationNumber()).isEqualTo("9876543210");
    }

    @Test
    void 재검증_실패는_기존_정보와_검증_시각을_유지한다() {
        var store = existingStore();
        var before = store.getBusinessVerifiedAt();
        var result = service.save(7L, request(3, "0000000000"));
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.HOURS.name());
        assertThat(store.getName()).isEqualTo("기존");
        assertThat(store.getBusinessVerifiedAt()).isEqualTo(before);
    }

    @Test
    void 버전_충돌과_약관_미완료를_거부한다() {
        assertCode(() -> service.save(7L, request(0, "1234567890")), StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(StoreRegistrationProgress.start(7L)));
        assertCode(() -> service.save(7L, request(0, "1234567890")), StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        verifyNoInteractions(stores);
    }

    @Test
    void 초기화_누락은_진행_상태를_만들지_않고_거부한다() {
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.empty());
        assertCode(() -> service.save(7L, request(1, "1234567890")), StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND);
        verifyNoInteractions(stores);
    }

    @Test
    void 소유_가게와_진행_상태가_불일치하거나_최종_등록된_가게는_거부한다() {
        var store = existingStore();
        ReflectionTestUtils.setField(store, "id", 10L);
        assertCode(() -> service.save(7L, request(3, "1234567890")), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        ReflectionTestUtils.setField(store, "id", 9L);
        ReflectionTestUtils.setField(store, "finalized", true);
        assertCode(() -> service.save(7L, request(3, "1234567890")), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
    }

    @Test
    void 초안이_이미_있거나_연결된_가게가_없으면_거부한다() {
        existingStore();
        var initial = StoreRegistrationProgress.start(7L);
        initial.completeStep(0, StoreRegistrationStep.TERMS, null);
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(initial));
        assertCode(() -> service.save(7L, request(1, "1234567890")), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
        when(stores.findByOwnerId(7L)).thenReturn(Optional.empty());
        assertCode(() -> service.save(7L, request(3, "1234567890")), StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
    }

    @Test
    void DB_저장_장애를_전파한다() {
        doThrow(new DataAccessResourceFailureException("offline")).when(stores).saveAndFlush(any());
        assertThatThrownBy(() -> service.save(7L, request(1, "1234567890"))).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.STORE_INFO);
    }

    @Test
    void 필수_정보의_누락과_형식_오류는_저장하지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L,
                new StoreInfoSaveCommand(1L, " ", "KOREAN", "대표", "1234567890", "02-123-4567")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L,
                new StoreInfoSaveCommand(1L, "가게", "ALL", "대표", "1234567890", "02-123-4567")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L,
                new StoreInfoSaveCommand(1L, "가게", "KOREAN", null, "1234567890", "02-123-4567")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L,
                new StoreInfoSaveCommand(1L, "가게", "KOREAN", "대표", "123-45-67890", "02-123-4567")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(7L,
                new StoreInfoSaveCommand(1L, "가게", "KOREAN", "대표", "1234567890", "")));
        verify(stores, never()).saveAndFlush(any());
        verify(progresses, never()).saveAndFlush(any());
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, StoreErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(StoreException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
