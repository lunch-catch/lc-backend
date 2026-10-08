package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.global.response.PageResponse;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.repository.MemberAdminRow;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberSearchCondition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminMemberServiceTest {

    @Mock MemberRepository memberRepository;
    @InjectMocks AdminMemberService service;

    private MemberSearchCondition searchAndCapture(
            MemberStatus status, LocalDate from, LocalDate to, String keyword, int page) {
        when(memberRepository.searchForAdmin(any(), any())).thenReturn(Page.empty());
        service.search(status, from, to, keyword, page);
        ArgumentCaptor<MemberSearchCondition> captor = ArgumentCaptor.forClass(MemberSearchCondition.class);
        verify(memberRepository).searchForAdmin(captor.capture(), any(Pageable.class));
        return captor.getValue();
    }

    @Test
    void 가입일_범위는_KST_날짜_양끝_포함으로_반열린_구간이_된다() {
        MemberSearchCondition condition = searchAndCapture(
                null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), null, 0);

        assertThat(condition.createdFrom()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(condition.createdBefore()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 같은_날을_시작과_끝으로_주면_그날_하루가_된다() {
        MemberSearchCondition condition = searchAndCapture(
                null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), null, 0);

        assertThat(condition.createdFrom()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(condition.createdBefore()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
    }

    @Test
    void 조건을_주지_않으면_범위와_키워드_조건이_비어_있다() {
        MemberSearchCondition condition = searchAndCapture(null, null, null, null, 0);

        assertThat(condition).isEqualTo(new MemberSearchCondition(null, null, null, null, null));
    }

    @Test
    void 상태_조건은_그대로_넘긴다() {
        MemberSearchCondition condition = searchAndCapture(MemberStatus.SUSPENDED, null, null, null, 0);

        assertThat(condition.status()).isEqualTo(MemberStatus.SUSPENDED);
    }

    @Test
    void 숫자만_있는_키워드는_회원_번호_일치로_본다() {
        MemberSearchCondition condition = searchAndCapture(null, null, null, " 123 ", 0);

        assertThat(condition.memberId()).isEqualTo(123L);
        assertThat(condition.nicknamePrefix()).isNull();
    }

    @Test
    void 숫자가_아닌_키워드는_닉네임_접두사로_본다() {
        MemberSearchCondition condition = searchAndCapture(null, null, null, "점심100%", 0);

        assertThat(condition.nicknamePrefix()).isEqualTo("점심100%");
        assertThat(condition.memberId()).isNull();
    }

    @Test
    void 공백뿐인_키워드는_조건이_아니다() {
        MemberSearchCondition condition = searchAndCapture(null, null, null, "   ", 0);

        assertThat(condition.memberId()).isNull();
        assertThat(condition.nicknamePrefix()).isNull();
    }

    @Test
    void 회원_번호_범위를_넘는_숫자는_조회하지_않고_빈_결과다() {
        PageResponse<AdminMemberResponse> response =
                service.search(null, null, null, "99999999999999999999", 3);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(3);
        assertThat(response.size()).isEqualTo(AdminMemberService.PAGE_SIZE);
        assertThat(response.totalElements()).isZero();
        verify(memberRepository, never()).searchForAdmin(any(), any());
    }

    @Test
    void 페이지는_20건_고정으로_요청한다() {
        when(memberRepository.searchForAdmin(any(), eq(PageRequest.of(2, 20)))).thenReturn(Page.empty());

        service.search(null, null, null, null, 2);

        verify(memberRepository).searchForAdmin(any(), eq(PageRequest.of(2, 20)));
    }

    @Test
    void 음수_페이지는_거절한다() {
        assertThatThrownBy(() -> service.search(null, null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any());
    }

    @Test
    void 시작일이_끝일보다_늦으면_거절한다() {
        assertThatThrownBy(() -> service.search(
                null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any());
    }

    @Test
    void 저장된_KST_시각은_변환_없이_9시간_오프셋만_붙여_내린다() {
        LocalDateTime joined = LocalDateTime.of(2026, 10, 1, 9, 30);
        LocalDateTime lastLogin = LocalDateTime.of(2026, 10, 5, 12, 0);
        MemberAdminRow active = new MemberAdminRow(2L, "점심헌터", MemberStatus.ACTIVE, joined, lastLogin);
        MemberAdminRow withdrawn = new MemberAdminRow(1L, "탈퇴한 회원", MemberStatus.WITHDRAWN, joined, null);
        when(memberRepository.searchForAdmin(any(), any()))
                .thenReturn(new PageImpl<>(List.of(active, withdrawn), PageRequest.of(0, 20), 2));

        PageResponse<AdminMemberResponse> response = service.search(null, null, null, null, 0);

        assertThat(response.totalElements()).isEqualTo(2);
        AdminMemberResponse first = response.items().get(0);
        assertThat(first.memberId()).isEqualTo(2L);
        assertThat(first.joinedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(first.joinedAt().toLocalDateTime()).isEqualTo(joined);
        assertThat(first.lastLoginAt().toLocalDateTime()).isEqualTo(lastLogin);
        AdminMemberResponse second = response.items().get(1);
        assertThat(second.nickname()).isEqualTo("탈퇴한 회원");
        assertThat(second.lastLoginAt()).isNull();
    }
}
