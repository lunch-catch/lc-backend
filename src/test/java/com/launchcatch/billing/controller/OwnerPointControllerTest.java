package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.OwnerLedgerItem;
import com.launchcatch.billing.dto.PointBalanceResponse;
import com.launchcatch.billing.dto.SpendGroupBy;
import com.launchcatch.billing.dto.SpendSummaryResponse;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.service.OwnerPointBalanceService;
import com.launchcatch.billing.service.OwnerPointLedgerService;
import com.launchcatch.billing.service.OwnerSpendSummaryService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerPointControllerTest {

    private final OwnerPointBalanceService balanceService = mock(OwnerPointBalanceService.class);
    private final OwnerPointLedgerService ledgerService = mock(OwnerPointLedgerService.class);
    private final OwnerSpendSummaryService spendSummaryService = mock(OwnerSpendSummaryService.class);
    private final OwnerPointController controller =
            new OwnerPointController(balanceService, ledgerService, spendSummaryService);
    private final CustomUserDetails owner = new CustomUserDetails(7L, Role.OWNER);

    @Test
    void 로그인한_점주의_ID로_잔액을_조회한다() {
        PointBalanceResponse balance = new PointBalanceResponse(12000, 3500, 15500, LocalDate.of(2026, 10, 1));
        when(balanceService.getBalance(7L)).thenReturn(balance);

        ResponseEnvelope<PointBalanceResponse> response = controller.balance(owner);

        verify(balanceService).getBalance(7L);
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data()).isSameAs(balance);
    }

    @Test
    void 변동_내역은_요청_조건을_로그인한_점주의_ID와_함께_서비스에_넘긴다() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        CursorPageResponse<OwnerLedgerItem> page = CursorPageResponse.of(List.of(), null);
        when(ledgerService.list(7L, List.of(EntryType.CHARGE), from, to, 12L, "token", 50)).thenReturn(page);

        ResponseEnvelope<CursorPageResponse<OwnerLedgerItem>> response =
                controller.ledger(owner, List.of(EntryType.CHARGE), from, to, 12L, "token", 50);

        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void 소진_집계는_묶는_기준과_기간을_로그인한_점주의_ID와_함께_서비스에_넘긴다() {
        LocalDate from = LocalDate.of(2026, 9, 24);
        LocalDate to = LocalDate.of(2026, 9, 30);
        SpendSummaryResponse summary = new SpendSummaryResponse(List.of());
        when(spendSummaryService.summarize(7L, SpendGroupBy.DAY_CAMPAIGN, from, to, 12L)).thenReturn(summary);

        ResponseEnvelope<SpendSummaryResponse> response =
                controller.spendSummary(owner, SpendGroupBy.DAY_CAMPAIGN, from, to, 12L);

        assertThat(response.data()).isSameAs(summary);
    }
}
