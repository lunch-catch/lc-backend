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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerTermsAgreementServiceTest {
    private final OwnerTermsAgreementRepository repository = mock(OwnerTermsAgreementRepository.class);
    private final List<OwnerTermsAgreement> stored = new ArrayList<>();
    private OwnerTermsAgreementService service;

    @BeforeEach
    void setUp() {
        service = new OwnerTermsAgreementService(repository, new OwnerTermsProperties(List.of(
                new OwnerTermsProperties.Term("SERVICE", "1.0", true),
                new OwnerTermsProperties.Term("PRIVACY", "2.0", true),
                new OwnerTermsProperties.Term("MARKETING", "1.0", false))));
        when(repository.findAllByOwnerId(7L)).thenAnswer(call -> List.copyOf(stored));
        when(repository.saveAllAndFlush(anyList())).thenAnswer(call -> {
            List<OwnerTermsAgreement> rows = call.getArgument(0);
            stored.addAll(rows);
            return rows;
        });
    }

    private List<TermsAgreementCommand> required() {
        return List.of(new TermsAgreementCommand("SERVICE", "1.0", true),
                new TermsAgreementCommand("PRIVACY", "2.0", true));
    }

    @Test
    void 필수_동의와_선택_미동의를_서버_정책으로_저장한다() {
        var request = new ArrayList<>(required());
        request.add(new TermsAgreementCommand("MARKETING", "1.0", false));
        assertThat(service.recordAgreements(7L, request)).isTrue();
        assertThat(stored).hasSize(3).allSatisfy(row -> assertThat(row.getOwnerId()).isEqualTo(7L));
        assertThat(stored.get(0).isRequired()).isTrue();
        assertThat(stored.get(2).isRequired()).isFalse();
        assertThat(stored.get(2).isAgreed()).isFalse();
    }

    @Test
    void 필수_동의만으로도_완료하며_기존_필수_동의로_선택_항목을_추가할_수_있다() {
        assertThat(service.recordAgreements(7L, required())).isTrue();
        assertThat(service.recordAgreements(7L, List.of(new TermsAgreementCommand("MARKETING", "1.0", false)))).isTrue();
        assertThat(stored).hasSize(3);
    }

    @Test
    void 요청에_빠진_필수_약관을_찾아_전체_저장을_거부한다() {
        assertThatThrownBy(() -> service.recordAgreements(7L, List.of(required().getFirst())))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED));
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void 필수_미동의는_422이다() {
        assertThatThrownBy(() -> service.recordAgreements(7L,
                List.of(new TermsAgreementCommand("SERVICE", "1.0", false))))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED));
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void 동일_약관_재요청은_기존_내역을_덮어쓰지_않는다() {
        service.recordAgreements(7L, required());
        assertThatThrownBy(() -> service.recordAgreements(7L, required()))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.TERMS_ALREADY_RECORDED));
        assertThat(stored).hasSize(2);
    }

    @Test
    void 요청_중복과_알_수_없는_종류_버전을_거부한다() {
        for (var commands : List.of(List.of(required().getFirst(), required().getFirst()),
                List.of(new TermsAgreementCommand("UNKNOWN", "1.0", true)),
                List.of(new TermsAgreementCommand("SERVICE", "old", true)))) {
            assertThatThrownBy(() -> service.recordAgreements(7L, commands)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void 과거_버전의_동의는_현재_필수_동의를_대신하지_않는다() {
        stored.add(OwnerTermsAgreement.record(7L, "PRIVACY", "1.0", true, true));
        assertThatThrownBy(() -> service.recordAgreements(7L, List.of(required().getFirst())))
                .isInstanceOf(StoreException.class);
    }

    @Test
    void 정책_미설정은_503이며_저장하지_않는다() {
        var unconfigured = new OwnerTermsAgreementService(repository, new OwnerTermsProperties(null));
        assertThatThrownBy(() -> unconfigured.recordAgreements(7L, required()))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.TERMS_POLICY_UNAVAILABLE));
        verifyNoInteractions(repository);
    }

    @Test
    void 저장_실패를_성공으로_바꾸지_않는다() {
        when(repository.saveAllAndFlush(anyList())).thenThrow(new DataAccessResourceFailureException("DB down"));
        assertThatThrownBy(() -> service.recordAgreements(7L, required())).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 잘못된_내부_호출도_거부한다() {
        assertThatThrownBy(() -> service.recordAgreements(null, required())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAgreements(7L, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAgreements(7L, java.util.Arrays.asList((TermsAgreementCommand) null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void DB_UNIQUE_충돌만_409로_변환한다() {
        var cause = new org.hibernate.exception.ConstraintViolationException("duplicate",
                new java.sql.SQLException("duplicate", "23000", 1062), "owner_terms_agreement.uk_owner_terms_version");
        var failure = new org.springframework.dao.DataIntegrityViolationException("duplicate", cause);
        when(repository.saveAllAndFlush(anyList())).thenThrow(failure);
        assertThatThrownBy(() -> service.recordAgreements(7L, required()))
                .isInstanceOfSatisfying(StoreException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.TERMS_ALREADY_RECORDED);
                    assertThat(e.getCause()).isSameAs(failure);
                });
    }

    @Test
    void 다른_DB_무결성_실패를_중복으로_오인하지_않는다() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("not null failed");
        when(repository.saveAllAndFlush(anyList())).thenThrow(failure);
        assertThatThrownBy(() -> service.recordAgreements(7L, required())).isSameAs(failure);
    }

    @Test
    void 저장후_필수_완료_상태가_아니면_성공하지_않는다() {
        when(repository.findAllByOwnerId(7L)).thenReturn(List.of(), List.of());
        assertThatThrownBy(() -> service.recordAgreements(7L, required())).isInstanceOf(StoreException.class);
    }

}
