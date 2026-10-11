package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.PointBalanceResponse;
import com.launchcatch.billing.service.OwnerPointBalanceService;
import com.launchcatch.global.response.ResponseEnvelope;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OwnerPointControllerTest {

    private final OwnerPointBalanceService balanceService = mock(OwnerPointBalanceService.class);
    private final OwnerPointController controller = new OwnerPointController(balanceService);

    @Test
    void 로그인한_점주의_ID로_잔액을_조회한다() {
        PointBalanceResponse balance = new PointBalanceResponse(12000, 3500, 15500, LocalDate.of(2026, 10, 1));
        when(balanceService.getBalance(7L)).thenReturn(balance);

        ResponseEnvelope<PointBalanceResponse> response =
                controller.balance(new CustomUserDetails(7L, Role.OWNER));

        verify(balanceService).getBalance(7L);
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data()).isSameAs(balance);
    }
}
