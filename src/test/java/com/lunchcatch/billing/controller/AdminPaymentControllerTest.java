package com.lunchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.billing.dto.AdminPaymentItem;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.service.AdminPaymentQueryService;
import com.lunchcatch.billing.service.AdminPaymentRecheckService;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminPaymentControllerTest {

    private final AdminPaymentQueryService queryService = mock(AdminPaymentQueryService.class);
    private final AdminPaymentRecheckService recheckService = mock(AdminPaymentRecheckService.class);
    private final AdminPaymentController controller = new AdminPaymentController(queryService, recheckService);

    @Test
    void 요청_조건을_그대로_서비스에_넘기고_결과를_감싸서_돌려준다() {
        PageResponse<AdminPaymentItem> page = PageResponse.of(List.of(), 0, 20, 0);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);
        when(queryService.search(from, to, PaymentStatus.UNKNOWN, "OWNER_ID", "7", 0, 20, "amount", "asc"))
                .thenReturn(page);

        ResponseEnvelope<PageResponse<AdminPaymentItem>> response = controller.search(
                from, to, PaymentStatus.UNKNOWN, "OWNER_ID", "7", 0, 20, "amount", "asc");

        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void 재조회는_로그인한_관리자의_ID로_하고_갱신된_결제를_돌려준다() {
        AdminPaymentItem item = mock(AdminPaymentItem.class);
        when(recheckService.recheck(3L, 101L)).thenReturn(item);

        ResponseEnvelope<AdminPaymentItem> response =
                controller.recheck(new CustomUserDetails(3L, Role.ADMIN), 101L);

        assertThat(response.data()).isSameAs(item);
    }
}
