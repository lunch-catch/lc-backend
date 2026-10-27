package com.lunchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.AdminLedgerItem;
import com.lunchcatch.billing.dto.AdminPointSummaryResponse;
import com.lunchcatch.billing.dto.SummaryPeriod;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.service.AdminPointLedgerService;
import com.lunchcatch.billing.service.AdminPointSummaryService;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPointLedgerControllerTest {

    private final AdminPointLedgerService service = mock(AdminPointLedgerService.class);
    private final AdminPointSummaryService summaryService = mock(AdminPointSummaryService.class);
    private final AdminPointLedgerController controller =
            new AdminPointLedgerController(service, summaryService);

    @Test
    void 요청_파라미터를_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        CursorPageResponse<AdminLedgerItem> page = CursorPageResponse.of(List.of(), "next");
        when(service.search(7L, EntryType.CHARGE, from, to, 12L, "token", 20, "asc")).thenReturn(page);

        ResponseEnvelope<CursorPageResponse<AdminLedgerItem>> response =
                controller.search(7L, EntryType.CHARGE, from, to, 12L, "token", 20, "asc");

        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void 조회_메서드에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        Method search = AdminPointLedgerController.class.getMethod("search",
                Long.class, EntryType.class, LocalDate.class, LocalDate.class, Long.class,
                String.class, int.class, String.class);

        PreAuthorize preAuthorize = search.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }

    @Test
    void 합계_요청_파라미터를_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        AdminPointSummaryResponse summary = new AdminPointSummaryResponse(7L, SummaryPeriod.DAILY, from, to, List.of());
        when(summaryService.summarize(7L, SummaryPeriod.DAILY, from, to, "desc")).thenReturn(summary);

        ResponseEnvelope<AdminPointSummaryResponse> response =
                controller.summary(7L, SummaryPeriod.DAILY, from, to, "desc");

        assertThat(response.data()).isSameAs(summary);
    }

    @Test
    void 합계_메서드에도_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        Method summary = AdminPointLedgerController.class.getMethod("summary",
                Long.class, SummaryPeriod.class, LocalDate.class, LocalDate.class, String.class);

        PreAuthorize preAuthorize = summary.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
