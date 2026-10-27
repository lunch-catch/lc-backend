package com.lunchcatch.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.member.dto.AdminMemberListQuery;
import com.lunchcatch.member.dto.AdminMemberResponse;
import com.lunchcatch.member.service.AdminMemberService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminMemberControllerTest {

    @Mock AdminMemberService adminMemberService;
    @InjectMocks AdminMemberController controller;

    @Test
    void 요청_파라미터를_질의_객체로_묶어_서비스에_그대로_넘긴다() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 7);
        AdminMemberListQuery query = new AdminMemberListQuery(
                MemberStatus.ACTIVE, from, to, "abc", 1, 50, "nickname", "asc");
        PageResponse<AdminMemberResponse> page = PageResponse.of(List.of(), 1, 50, 0);
        when(adminMemberService.search(query)).thenReturn(page);

        ResponseEnvelope<PageResponse<AdminMemberResponse>> response = controller.list(
                MemberStatus.ACTIVE, from, to, "abc", 1, 50, "nickname", "asc");

        verify(adminMemberService).search(query);
        assertThat(response.data()).isSameAs(page);
    }
}
