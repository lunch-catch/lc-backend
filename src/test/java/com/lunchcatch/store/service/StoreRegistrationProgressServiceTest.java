package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lunchcatch.store.entity.StoreRegistrationProgress;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.exception.StoreErrorCode;
import com.lunchcatch.store.exception.StoreException;
import com.lunchcatch.store.repository.StoreRegistrationProgressRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class StoreRegistrationProgressServiceTest {
    private final StoreRegistrationProgressRepository repository = mock(StoreRegistrationProgressRepository.class);
    private final StoreRegistrationProgressService service = new StoreRegistrationProgressService(repository);

    @Test
    void 완료한_단계_수정은_진행을_유지한_새_버전을_반환한다() {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        progress.completeStep(1, StoreRegistrationStep.STORE_INFO, 9L);
        progress.completeStep(2, StoreRegistrationStep.LOCATION, null);
        progress.completeStep(3, StoreRegistrationStep.HOURS, null);
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
        var result = service.reviseStep(7L, 4, StoreRegistrationStep.HOURS);
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.IMAGES);
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.version()).isEqualTo(5);
    }

    @Test
    void 수정_요청의_잘못된_인자와_초기화_누락을_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.reviseStep(null, 0, StoreRegistrationStep.HOURS));
        assertThatIllegalArgumentException().isThrownBy(() -> service.reviseStep(0L, 0, StoreRegistrationStep.HOURS));
        assertThatIllegalArgumentException().isThrownBy(() -> service.reviseStep(7L, -1, StoreRegistrationStep.HOURS));
        assertThatIllegalArgumentException().isThrownBy(() -> service.reviseStep(7L, 0, null));
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reviseStep(7L, 0, StoreRegistrationStep.HOURS))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
    }

    @Test
    void 최초_초기화는_0번_버전이며_반복_초기화는_기존_진행을_유지한다() {
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var initial = service.initialize(7L);
        assertThat(initial.version()).isZero();
        assertThat(initial.resumeStep()).isEqualTo(StoreRegistrationStep.TERMS);
        var stored = StoreRegistrationProgress.start(7L);
        stored.completeStep(0, StoreRegistrationStep.TERMS, null);
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(stored));
        assertThat(service.initialize(7L).version()).isEqualTo(1);
    }

    @Test
    void 유효하지_않은_인자를_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.initialize(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.initialize(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> service.completeStep(null, 0, StoreRegistrationStep.TERMS, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.completeStep(0L, 0, StoreRegistrationStep.TERMS, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.completeStep(7L, -1, StoreRegistrationStep.TERMS, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.completeStep(7L, 0, null, null));
    }

    @Test
    void 진행_상태가_없으면_완료_처리하지_않는다() {
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeStep(7L, 0, StoreRegistrationStep.TERMS, null))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
    }

    @Test
    void 약관_완료는_가게_없이_STORE_INFO와_새_버전을_반환한다() {
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(StoreRegistrationProgress.start(7L)));
        var result = service.completeStep(7L, 0, StoreRegistrationStep.TERMS, null);
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.STORE_INFO);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.storeId()).isNull();
    }

    @Test
    void 기본_정보_완료는_본인_미등록확정_가게만_연결한다() {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
        when(repository.countDraftStoreOwnedBy(7L, 9L)).thenReturn(1L);
        var result = service.completeStep(7L, 1, StoreRegistrationStep.STORE_INFO, 9L);
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(result.version()).isEqualTo(2);
    }

    @Test
    void 다른_점주의_가게나_등록확정_가게는_연결하지_못한다() {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
        assertThatThrownBy(() -> service.completeStep(7L, 1, StoreRegistrationStep.STORE_INFO, 9L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        assertThat(progress.getVersion()).isEqualTo(1);
    }

    @Test
    void 저장_실패를_성공으로_바꾸지_않는다() {
        when(repository.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(StoreRegistrationProgress.start(7L)));
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("injected"));
        assertThatThrownBy(() -> service.completeStep(7L, 0, StoreRegistrationStep.TERMS, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
