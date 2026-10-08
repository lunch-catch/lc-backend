package com.launchcatch.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.global.response.PageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.service.AdminMemberService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class AdminMemberControllerTest {

    @Mock AdminMemberService adminMemberService;
    @InjectMocks AdminMemberController controller;

    @Test
    void 요청_파라미터를_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 7);
        PageResponse<AdminMemberResponse> page = PageResponse.of(List.of(), 1, 20, 0);
        when(adminMemberService.search(MemberStatus.ACTIVE, from, to, "abc", 1)).thenReturn(page);

        ResponseEnvelope<PageResponse<AdminMemberResponse>> response =
                controller.list(MemberStatus.ACTIVE, from, to, "abc", 1);

        verify(adminMemberService).search(MemberStatus.ACTIVE, from, to, "abc", 1);
        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void 목록_메서드에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        Method list = AdminMemberController.class.getMethod("list",
                MemberStatus.class, LocalDate.class, LocalDate.class, String.class, int.class);

        PreAuthorize preAuthorize = list.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
