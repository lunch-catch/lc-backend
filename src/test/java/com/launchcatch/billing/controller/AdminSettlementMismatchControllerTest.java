package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.SettlementMismatchItem;
import com.launchcatch.billing.entity.MismatchCheckType;
import com.launchcatch.billing.service.SettlementMismatchQueryService;
import com.launchcatch.billing.service.SettlementMismatchResolutionService;
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminSettlementMismatchControllerTest {

    private final SettlementMismatchQueryService service = mock(SettlementMismatchQueryService.class);
    private final SettlementMismatchResolutionService resolutionService =
            mock(SettlementMismatchResolutionService.class);
    private final AdminSettlementMismatchController controller =
            new AdminSettlementMismatchController(service, resolutionService);

    @Test
    void 요청_파라미터를_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 13);
        PageResponse<SettlementMismatchItem> page = PageResponse.of(List.of(), 1, 20, 0);
        when(service.search(MismatchCheckType.PG_VS_CHARGE, false, from, to, 1, 20, "businessDate", "asc"))
                .thenReturn(page);

        assertThat(controller.search(MismatchCheckType.PG_VS_CHARGE, false, from, to, 1, 20, "businessDate", "asc")
                .data()).isSameAs(page);
    }

    @Test
    void 조회에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        PreAuthorize preAuthorize = AdminSettlementMismatchController.class
                .getMethod("search", MismatchCheckType.class, Boolean.class, LocalDate.class, LocalDate.class,
                        int.class, int.class, String.class, String.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }

    @Test
    void 조정_없는_종결은_토큰의_관리자로_닫고_닫힌_건을_돌려준다() {
        SettlementMismatchItem closed = mock(SettlementMismatchItem.class);
        when(resolutionService.closeWithoutAdjustment(2L, 55L)).thenReturn(closed);

        assertThat(controller.closeWithoutAdjustment(new CustomUserDetails(2L, Role.ADMIN), 55L).data())
                .isSameAs(closed);
    }

    @Test
    void 조정_없는_종결에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        PreAuthorize preAuthorize = AdminSettlementMismatchController.class
                .getMethod("closeWithoutAdjustment", CustomUserDetails.class, Long.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
