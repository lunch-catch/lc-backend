package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;
import com.launchcatch.store.config.OwnerTermsProperties;
import com.launchcatch.store.contract.TermsAgreementCommand;
import com.launchcatch.store.entity.OwnerTermsAgreement;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OwnerTermsAgreementServiceTest {
    private final OwnerTermsAgreementRepository repository = mock(OwnerTermsAgreementRepository.class);
    private final List<OwnerTermsAgreement> stored = new ArrayList<>();
    private final com.launchcatch.store.repository.StoreRegistrationProgressRepository progressRepository =
            mock(com.launchcatch.store.repository.StoreRegistrationProgressRepository.class);
    private com.launchcatch.store.entity.StoreRegistrationProgress progress;
    private OwnerTermsAgreementService service;

    @BeforeEach
    void setUp() {
        progress = com.launchcatch.store.entity.StoreRegistrationProgress.start(7L);
        when(progressRepository.findByOwnerIdForUpdate(7L)).thenAnswer(call -> java.util.Optional.of(progress));
        service = new OwnerTermsAgreementService(repository, new OwnerTermsProperties(List.of(
                new OwnerTermsProperties.Term("SERVICE", "1.0", true),
                new OwnerTermsProperties.Term("PRIVACY", "2.0", true))), new StoreRegistrationProgressService(progressRepository));
        when(repository.findAllByOwnerId(7L)).thenAnswer(call -> List.copyOf(stored));
        when(repository.saveAllAndFlush(anyList())).thenAnswer(call -> {
            List<OwnerTermsAgreement> rows = call.getArgument(0);
            for (var row : rows) {
                if (!stored.contains(row)) stored.add(row);
            }
            return rows;
        });
    }

    private List<TermsAgreementCommand> required() {
        return List.of(new TermsAgreementCommand("SERVICE", "1.0", true),
                new TermsAgreementCommand("PRIVACY", "2.0", true));
    }

    @Test
    void 선택_정책에서는_미동의도_저장하고_필수_완료를_막지_않는다() {
        var mixed = new OwnerTermsAgreementService(repository, new OwnerTermsProperties(List.of(
                new OwnerTermsProperties.Term("SERVICE", "1.0", true),
                new OwnerTermsProperties.Term("PRIVACY", "2.0", true),
                new OwnerTermsProperties.Term("MARKETING", "1.0", false))),
                new StoreRegistrationProgressService(progressRepository));
        var commands = new ArrayList<>(required());
        commands.add(new TermsAgreementCommand("MARKETING", "1.0", false));
        assertThat(mixed.recordAgreements(7L, 0L, commands).requiredTermsCompleted()).isTrue();
        assertThat(stored.get(2).isRequired()).isFalse();
        assertThat(stored.get(2).isAgreed()).isFalse();
    }

    @Test
    void 선택_약관의_동의_변경과_생략_시_미동의_처리를_지원한다() {
        var mixed = new OwnerTermsAgreementService(repository, new OwnerTermsProperties(List.of(
                new OwnerTermsProperties.Term("SERVICE", "1.0", true),
                new OwnerTermsProperties.Term("PRIVACY", "2.0", true),
                new OwnerTermsProperties.Term("MARKETING", "1.0", false))),
                new StoreRegistrationProgressService(progressRepository));
        mixed.recordAgreements(7L, 0L, required());
        assertThat(stored.get(2).isAgreed()).isFalse();
        mixed.recordAgreements(7L, 1L, List.of(new TermsAgreementCommand("MARKETING", "1.0", true)));
        assertThat(stored.get(2).isAgreed()).isTrue();
        mixed.recordAgreements(7L, 2L, required());
        assertThat(stored).hasSize(3);
        assertThat(stored.get(2).isAgreed()).isFalse();
        assertThat(progress.getVersion()).isEqualTo(3L);
    }

    @Test
    void 최초_완료와_이전_단계_재저장은_진행_위치를_유지하고_버전을_증가시킨다() {
        var first = service.recordAgreements(7L, 0L, required());
        assertThat(first.requiredTermsCompleted()).isTrue();
        assertThat(stored).hasSize(2).allSatisfy(row -> {
            assertThat(row.isRequired()).isTrue();
            assertThat(row.isAgreed()).isTrue();
        });
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.resumeStep()).isEqualTo("STORE_INFO");
        var revised = service.recordAgreements(7L, 1L, required());
        assertThat(revised.version()).isEqualTo(2);
        assertThat(revised.resumeStep()).isEqualTo("STORE_INFO");
        assertThat(stored).hasSize(2).allMatch(OwnerTermsAgreement::isAgreed);
    }

    @Test
    void 생략한_필수_약관의_유효한_기존_동의를_유지한다() {
        service.recordAgreements(7L, 0L, required());
        var result = service.recordAgreements(7L, 1L, List.of(required().getFirst()));
        assertThat(result.requiredTermsCompleted()).isTrue();
        assertThat(result.version()).isEqualTo(2L);
        assertThat(stored).hasSize(2).allMatch(OwnerTermsAgreement::isAgreed);
    }

    @Test
    void 진행_상태_최초_저장에서도_기존_필수_동의의_재제출을_요구하지_않는다() {
        stored.add(OwnerTermsAgreement.record(7L, "SERVICE", "1.0", true, true));
        stored.add(OwnerTermsAgreement.record(7L, "PRIVACY", "2.0", true, true));
        when(progressRepository.findByOwnerIdForUpdate(7L))
                .thenReturn(java.util.Optional.empty(), java.util.Optional.of(progress));
        when(progressRepository.saveAndFlush(any())).thenReturn(progress);
        var result = service.recordAgreements(7L, 0L,
                List.of(required().getFirst()));
        assertThat(result.requiredTermsCompleted()).isTrue();
        assertThat(result.resumeStep()).isEqualTo("STORE_INFO");
        assertThat(result.version()).isEqualTo(1L);
        assertThat(stored).hasSize(2);
    }

    @Test
    void 철회한_필수_동의는_현재_필수_동의를_대신하지_않는다() {
        var agreement = OwnerTermsAgreement.record(7L, "PRIVACY", "2.0", true, true);
        org.springframework.test.util.ReflectionTestUtils.setField(agreement, "withdrawnAt", java.time.LocalDateTime.now());
        stored.add(agreement);
        assertThatThrownBy(() -> service.recordAgreements(7L, 0L, List.of(required().getFirst())))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED));
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void 기존_필수_동의가_있어도_명시적인_필수_미동의는_거부한다() {
        service.recordAgreements(7L, 0L, required());
        assertThatThrownBy(() -> service.recordAgreements(7L, 1L,
                List.of(new TermsAgreementCommand("SERVICE", "1.0", false))))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED));
        assertThat(progress.getVersion()).isEqualTo(1L);
        assertThat(stored.getFirst().isAgreed()).isTrue();
    }

    @Test
    void 요청에_빠진_필수_약관을_찾아_전체_저장을_거부한다() {
        assertThatThrownBy(() -> service.recordAgreements(7L, 0L, List.of(required().getFirst())))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED));
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void 오래된_버전의_재요청은_기존_내역을_덮어쓰지_않는다() {
        service.recordAgreements(7L, 0L, required());
        assertThatThrownBy(() -> service.recordAgreements(7L, 0L, required()))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_VERSION_CONFLICT));
        assertThat(stored).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAgreements")
    void 요청_중복과_알_수_없는_종류_버전을_거부한다(String scenario, List<TermsAgreementCommand> commands) {
        assertThatThrownBy(() -> service.recordAgreements(7L, 0L, commands))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    private static Stream<Arguments> invalidAgreements() {
        var service = new TermsAgreementCommand("SERVICE", "1.0", true);
        return Stream.of(
                Arguments.of("요청 안의 약관 중복", List.of(service, service)),
                Arguments.of("알 수 없는 약관 종류", List.of(new TermsAgreementCommand("UNKNOWN", "1.0", true))),
                Arguments.of("현재 정책과 다른 약관 버전", List.of(new TermsAgreementCommand("SERVICE", "old", true))));
    }

    @Test
    void 과거_버전의_동의는_현재_필수_동의를_대신하지_않는다() {
        stored.add(OwnerTermsAgreement.record(7L, "PRIVACY", "1.0", true, true));
        assertThatThrownBy(() -> service.recordAgreements(7L, 0L, List.of(required().getFirst())))
                .isInstanceOf(StoreException.class);
    }

    @Test
    void 정책_미설정은_503이며_저장하지_않는다() {
        var unconfigured = new OwnerTermsAgreementService(repository, new OwnerTermsProperties(null), new StoreRegistrationProgressService(progressRepository));
        assertThatThrownBy(() -> unconfigured.recordAgreements(7L, 0L, required()))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.TERMS_POLICY_UNAVAILABLE));
        verifyNoInteractions(repository);
    }

    @Test
    void 다른_DB_무결성_실패를_중복으로_오인하지_않는다() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("not null failed");
        when(repository.saveAllAndFlush(anyList())).thenThrow(failure);
        assertThatThrownBy(() -> service.recordAgreements(7L, 0L, required())).isSameAs(failure);
    }


}
