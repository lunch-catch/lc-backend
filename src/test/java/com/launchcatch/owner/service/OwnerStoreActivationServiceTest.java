package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnerStoreActivationServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerStatusCacheInvalidationService status = mock(OwnerStatusCacheInvalidationService.class);
    private final OwnerStoreActivationService service = new OwnerStoreActivationService(owners, status);

    @Test
    void 가게_확정_후_ACTIVE로_전환하고_커밋_후_캐시_삭제를_예약한다() {
        var owner = Owner.create("test@example.com", "hash");
        when(owners.findByIdForLogin(1L)).thenReturn(Optional.of(owner));
        service.activate(1L);
        assertThat(owner.getStatus()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(owner.isTutorialViewed()).isFalse();
        verify(status).invalidateAfterCommit(1L);
    }

    @Test
    void 없는_점주의_활성화는_실패한다() {
        assertThatThrownBy(() -> service.activate(1L)).isInstanceOf(AuthException.class);
        verifyNoInteractions(status);
    }
}
