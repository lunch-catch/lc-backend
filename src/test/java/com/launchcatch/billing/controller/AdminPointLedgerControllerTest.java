package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.AdminLedgerItem;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.service.AdminPointLedgerService;
import com.launchcatch.global.response.PageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPointLedgerControllerTest {

    private final AdminPointLedgerService service = mock(AdminPointLedgerService.class);
    private final AdminPointLedgerController controller = new AdminPointLedgerController(service);

    @Test
    void 요청_파라미터를_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        PageResponse<AdminLedgerItem> page = PageResponse.of(List.of(), 1, 50, 0);
        when(service.search(7L, EntryType.CHARGE, from, to, 12L, 1, 50, "asc")).thenReturn(page);

        ResponseEnvelope<PageResponse<AdminLedgerItem>> response =
                controller.search(7L, EntryType.CHARGE, from, to, 12L, 1, 50, "asc");

        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void 조회_메서드에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        Method search = AdminPointLedgerController.class.getMethod("search",
                Long.class, EntryType.class, LocalDate.class, LocalDate.class, Long.class,
                int.class, int.class, String.class);

        PreAuthorize preAuthorize = search.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
