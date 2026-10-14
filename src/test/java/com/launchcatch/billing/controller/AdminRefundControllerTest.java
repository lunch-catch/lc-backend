package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.AdminRefundRequestItem;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.service.AdminRefundService;
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminRefundControllerTest {

    private final AdminRefundService service = mock(AdminRefundService.class);
    private final AdminRefundController controller = new AdminRefundController(service);

    @Test
    void 목록_파라미터를_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 13);
        PageResponse<AdminRefundRequestItem> page = PageResponse.of(List.of(), 1, 20, 0);
        when(service.search(RefundStatus.REQUESTED, 7L, from, to, 1, 20, "amount", "asc")).thenReturn(page);

        assertThat(controller.search(RefundStatus.REQUESTED, 7L, from, to, 1, 20, "amount", "asc").data())
                .isSameAs(page);
    }

    @Test
    void 목록에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        PreAuthorize preAuthorize = AdminRefundController.class
                .getMethod("search", RefundStatus.class, Long.class, LocalDate.class, LocalDate.class,
                        int.class, int.class, String.class, String.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
