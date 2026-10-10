package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OwnerAccessTokenPolicyTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerAccessTokenVersionRepository versions = mock(OwnerAccessTokenVersionRepository.class);
    private final OwnerAccessTokenPolicy policy = new OwnerAccessTokenPolicy(owners, versions);

    @AfterEach
    void clear() { TransactionSynchronizationManager.clear(); }

    @Test
    void 큰_순번도_트랜잭션_안에서_문자열로_제공한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(owners.findIssuanceVersionById(7L)).thenReturn(Optional.of(Long.MAX_VALUE));
        assertThat(policy.role()).isEqualTo(Role.OWNER);
        assertThat(policy.additionalClaims(7L)).containsEntry("issuanceVersion", Long.toString(Long.MAX_VALUE));
        when(versions.isValid(7L, Long.MAX_VALUE)).thenReturn(true);
        assertThat(policy.isValid(7L, policy.additionalClaims(7L))).isTrue();
    }

    @Test
    void 트랜잭션_밖에서는_발급하지_않는다() {
        assertThatThrownBy(() -> policy.additionalClaims(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 없는_점주는_발급하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(owners.findIssuanceVersionById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.additionalClaims(7L)).isInstanceOf(AuthException.class);
    }

    @Test
    void 음수_순번은_발급하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(owners.findIssuanceVersionById(7L)).thenReturn(Optional.of(-1L));
        assertThatThrownBy(() -> policy.additionalClaims(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 기존_JWT는_0으로_검증하고_차단_결과를_따른다() {
        when(versions.isValid(7L, 0L)).thenReturn(true);
        assertThat(policy.isValid(7L, Map.of())).isTrue();
        when(versions.isValid(7L, 0L)).thenReturn(false);
        assertThat(policy.isValid(7L, Map.of())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "bad", "9223372036854775808", "12345678901234567890"})
    void 잘못된_순번은_거부한다(String version) {
        assertThat(policy.isValid(7L, Map.of("issuanceVersion", version))).isFalse();
        verifyNoInteractions(versions);
    }

    @Test
    void 문자열이_아닌_순번은_거부한다() {
        assertThat(policy.isValid(7L, Map.of("issuanceVersion", 1))).isFalse();
    }

    @Test
    void Redis_조회_장애는_기존_LENIENT_정책을_유지한다() {
        when(versions.isValid(7L, 1L)).thenThrow(new DataAccessResourceFailureException("Redis"));
        assertThat(policy.isValid(7L, Map.of("issuanceVersion", "1"))).isTrue();
    }
}
