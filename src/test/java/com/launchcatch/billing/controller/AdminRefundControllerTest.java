package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.AdminRefundRequestItem;
import com.launchcatch.billing.dto.RefundProcessResponse;
import com.launchcatch.billing.dto.RefundRejectRequest;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.service.AdminRefundProcessService;
import com.launchcatch.billing.service.AdminRefundService;
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminRefundControllerTest {

    private final AdminRefundService service = mock(AdminRefundService.class);
    private final AdminRefundProcessService processService = mock(AdminRefundProcessService.class);
    private final AdminRefundController controller = new AdminRefundController(service, processService);

    private static final CustomUserDetails ADMIN = new CustomUserDetails(2L, Role.ADMIN);

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

    @Test
    void 승인을_서비스에_넘긴다() {
        RefundProcessResponse processed = new RefundProcessResponse(31L, RefundStatus.COMPLETED, 50_000L, 0L,
                OffsetDateTime.now());
        when(processService.approve(2L, 31L)).thenReturn(processed);

        assertThat(controller.approve(ADMIN, 31L).data()).isSameAs(processed);
    }

    @Test
    void 반려_사유를_서비스에_넘긴다() {
        RefundProcessResponse processed = new RefundProcessResponse(31L, RefundStatus.REJECTED, 50_000L, 0L,
                OffsetDateTime.now());
        when(processService.reject(2L, 31L, "정산 확인 필요")).thenReturn(processed);

        assertThat(controller.reject(ADMIN, 31L, new RefundRejectRequest("정산 확인 필요")).data())
                .isSameAs(processed);
    }

    @Test
    void 승인과_반려에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        PreAuthorize approve = AdminRefundController.class
                .getMethod("approve", CustomUserDetails.class, Long.class)
                .getAnnotation(PreAuthorize.class);
        PreAuthorize reject = AdminRefundController.class
                .getMethod("reject", CustomUserDetails.class, Long.class, RefundRejectRequest.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(approve.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
        assertThat(reject.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
