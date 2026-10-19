package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.StoreHoursRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreHoursSaver;
import com.launchcatch.store.contract.StoreHoursSaveResult;
import java.time.DayOfWeek;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OwnerStoreHoursServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreHoursSaver hours = mock(StoreHoursSaver.class);
    private final OwnerStoreHoursService service = new OwnerStoreHoursService(owners, hours);
    private final List<StoreHoursRequest.Hour> week = Arrays.stream(DayOfWeek.values()).map(day ->
            new StoreHoursRequest.Hour(day, true, null, null, null, null, null)).toList();

    private void owner(OwnerStatus status) {
        var owner = mock(Owner.class);
        when(owner.getStatus()).thenReturn(status);
        when(owners.findByIdForLogin(1L)).thenReturn(Optional.of(owner));
    }

    @Test
    void ONBOARDING은_요청_버전과_함께_온보딩_저장으로_전달한다() {
        owner(OwnerStatus.ONBOARDING);
        var request = new StoreHoursRequest(3L, week);
        when(hours.saveOnboarding(1L, request.toCommand())).thenReturn(new StoreHoursSaveResult(10L, "IMAGES", 4L));
        var result = service.save(1L, request);
        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.resumeStep()).isEqualTo("IMAGES");
        assertThat(result.version()).isEqualTo(4L);
    }

    @Test
    void ACTIVE는_온보딩과_분리한_저장으로_전달한다() {
        owner(OwnerStatus.ACTIVE);
        var request = new StoreHoursRequest(null, week);
        when(hours.saveActive(1L, request.toCommand())).thenReturn(new StoreHoursSaveResult(10L, null, null));
        var result = service.save(1L, request);
        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.version()).isNull();
        verify(hours, never()).saveOnboarding(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 허용되지_않은_상태는_저장하지_않는다(OwnerStatus status) {
        owner(status);
        assertThatThrownBy(() -> service.save(1L, new StoreHoursRequest(3L, week))).isInstanceOf(OwnerException.class);
        verifyNoInteractions(hours);
    }

    @Test
    void 계정이_없거나_점주_ID가_잘못되면_저장하지_않는다() {
        assertThatThrownBy(() -> service.save(1L, new StoreHoursRequest(3L, week))).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.save(0L, new StoreHoursRequest(3L, week))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(hours);
    }
}
