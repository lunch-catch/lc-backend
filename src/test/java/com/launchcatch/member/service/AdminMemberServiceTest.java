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
import com.launchcatch.member.dto.AdminMemberListQuery;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.dto.AdminMemberSearchType;
import com.launchcatch.member.repository.MemberAdminRow;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberSearchCondition;
import com.launchcatch.member.repository.MemberSortKey;
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
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class AdminMemberServiceTest {

    @Mock MemberRepository memberRepository;
    @InjectMocks AdminMemberService service;

    private static AdminMemberListQuery query(
            MemberStatus status, LocalDate from, LocalDate to, AdminMemberSearchType type, String keyword) {
        return new AdminMemberListQuery(status, from, to, type, keyword, 0, 20, null, null);
    }

    private static AdminMemberListQuery paged(int page, int size, String sortBy, String sortDir) {
        return new AdminMemberListQuery(null, null, null, null, null, page, size, sortBy, sortDir);
    }

    private MemberSearchCondition searchAndCapture(AdminMemberListQuery query) {
        when(memberRepository.searchForAdmin(any(), any(), any(), any())).thenReturn(Page.empty());
        service.search(query);
        ArgumentCaptor<MemberSearchCondition> captor = ArgumentCaptor.forClass(MemberSearchCondition.class);
        verify(memberRepository).searchForAdmin(captor.capture(), any(), any(), any(Pageable.class));
        return captor.getValue();
    }

    private record Requested(MemberSortKey sortKey, Sort.Direction direction, Pageable pageable) {
    }

    private Requested sortAndCapture(AdminMemberListQuery query) {
        when(memberRepository.searchForAdmin(any(), any(), any(), any())).thenReturn(Page.empty());
        service.search(query);
        ArgumentCaptor<MemberSortKey> key = ArgumentCaptor.forClass(MemberSortKey.class);
        ArgumentCaptor<Sort.Direction> direction = ArgumentCaptor.forClass(Sort.Direction.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(memberRepository).searchForAdmin(any(), key.capture(), direction.capture(), pageable.capture());
        return new Requested(key.getValue(), direction.getValue(), pageable.getValue());
    }

    @Test
    void 가입일_범위는_KST_날짜_양끝_포함으로_반열린_구간이_된다() {
        MemberSearchCondition condition = searchAndCapture(query(
                null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), null, null));

        assertThat(condition.createdFrom()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(condition.createdBefore()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 같은_날을_시작과_끝으로_주면_그날_하루가_된다() {
        MemberSearchCondition condition = searchAndCapture(query(
                null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), null, null));

        assertThat(condition.createdFrom()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(condition.createdBefore()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
    }

    @Test
    void 조건을_주지_않으면_범위와_키워드_조건이_비어_있다() {
        MemberSearchCondition condition = searchAndCapture(query(null, null, null, null, null));

        assertThat(condition).isEqualTo(new MemberSearchCondition(null, null, null, null, null));
    }

    @Test
    void 상태_조건은_그대로_넘긴다() {
        MemberSearchCondition condition = searchAndCapture(query(MemberStatus.SUSPENDED, null, null, null, null));

        assertThat(condition.status()).isEqualTo(MemberStatus.SUSPENDED);
    }

    @Test
    void 회원_번호_검색은_앞뒤_공백을_걷고_번호_일치로_본다() {
        MemberSearchCondition condition = searchAndCapture(
                query(null, null, null, AdminMemberSearchType.MEMBER_ID, " 123 "));

        assertThat(condition.memberId()).isEqualTo(123L);
        assertThat(condition.nicknamePrefix()).isNull();
    }

    @Test
    void 숫자만_있는_키워드도_닉네임_검색이면_닉네임_접두사로_본다() {
        MemberSearchCondition condition = searchAndCapture(
                query(null, null, null, AdminMemberSearchType.NICKNAME, "123"));

        assertThat(condition.nicknamePrefix()).isEqualTo("123");
        assertThat(condition.memberId()).isNull();
    }

    @Test
    void 닉네임_검색어는_글자_그대로_접두사가_된다() {
        MemberSearchCondition condition = searchAndCapture(
                query(null, null, null, AdminMemberSearchType.NICKNAME, "점심100%"));

        assertThat(condition.nicknamePrefix()).isEqualTo("점심100%");
        assertThat(condition.memberId()).isNull();
    }

    @Test
    void 키워드가_공백뿐이면_검색_조건이_아니다() {
        MemberSearchCondition withType = searchAndCapture(
                query(null, null, null, AdminMemberSearchType.NICKNAME, "   "));

        assertThat(withType.memberId()).isNull();
        assertThat(withType.nicknamePrefix()).isNull();
    }

    @Test
    void 키워드가_없으면_검색_종류만_있어도_조건이_아니다() {
        MemberSearchCondition condition = searchAndCapture(
                query(null, null, null, AdminMemberSearchType.MEMBER_ID, null));

        assertThat(condition).isEqualTo(new MemberSearchCondition(null, null, null, null, null));
    }

    @Test
    void 키워드를_주고_검색_종류가_없으면_거절한다() {
        assertThatThrownBy(() -> service.search(query(null, null, null, null, "abc")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 회원_번호_검색에_숫자가_아닌_키워드를_주면_거절한다() {
        assertThatThrownBy(() -> service.search(
                query(null, null, null, AdminMemberSearchType.MEMBER_ID, "12a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(
                query(null, null, null, AdminMemberSearchType.MEMBER_ID, "-1")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 회원_번호_범위를_넘는_숫자는_조회하지_않고_빈_결과다() {
        PageResponse<AdminMemberResponse> response = service.search(new AdminMemberListQuery(
                null, null, null, AdminMemberSearchType.MEMBER_ID, "99999999999999999999", 3, 50, null, null));

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(3);
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isZero();
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 정렬을_주지_않으면_가입일_내림차순이다() {
        Requested requested = sortAndCapture(paged(2, 20, null, null));

        assertThat(requested.sortKey()).isEqualTo(MemberSortKey.JOINED_AT);
        assertThat(requested.direction()).isEqualTo(Sort.Direction.DESC);
        assertThat(requested.pageable()).isEqualTo(PageRequest.of(2, 20));
    }

    @Test
    void 페이지_크기는_요청한_값으로_조회한다() {
        Requested requested = sortAndCapture(paged(1, 100, null, null));

        assertThat(requested.pageable()).isEqualTo(PageRequest.of(1, 100));
    }

    @Test
    void 요청_값마다_정렬_기준이_대응된다() {
        when(memberRepository.searchForAdmin(any(), any(), any(), any())).thenReturn(Page.empty());

        service.search(paged(0, 20, "joinedAt", "asc"));
        service.search(paged(0, 20, "lastLoginAt", "asc"));
        service.search(paged(0, 20, "nickname", "desc"));
        service.search(paged(0, 20, "memberId", "asc"));

        verify(memberRepository).searchForAdmin(any(), eq(MemberSortKey.JOINED_AT),
                eq(Sort.Direction.ASC), any());
        verify(memberRepository).searchForAdmin(any(), eq(MemberSortKey.LAST_LOGIN_AT),
                eq(Sort.Direction.ASC), any());
        verify(memberRepository).searchForAdmin(any(), eq(MemberSortKey.NICKNAME),
                eq(Sort.Direction.DESC), any());
        verify(memberRepository).searchForAdmin(any(), eq(MemberSortKey.MEMBER_ID),
                eq(Sort.Direction.ASC), any());
    }

    @Test
    void 허용되지_않는_정렬_값은_거절한다() {
        assertThatThrownBy(() -> service.search(paged(0, 20, "createdAt", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(paged(0, 20, "", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(paged(0, 20, null, "DESC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(paged(0, 20, null, "down")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 음수_페이지는_거절한다() {
        assertThatThrownBy(() -> service.search(paged(-1, 20, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 페이지_크기가_1에서_100_사이가_아니면_거절한다() {
        assertThatThrownBy(() -> service.search(paged(0, 0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(paged(0, 101, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 시작일이_끝일보다_늦으면_거절한다() {
        assertThatThrownBy(() -> service.search(query(
                null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).searchForAdmin(any(), any(), any(), any());
    }

    @Test
    void 저장된_KST_시각은_변환_없이_9시간_오프셋만_붙여_내린다() {
        LocalDateTime joined = LocalDateTime.of(2026, 10, 1, 9, 30);
        LocalDateTime lastLogin = LocalDateTime.of(2026, 10, 5, 12, 0);
        MemberAdminRow active = new MemberAdminRow(2L, "점심헌터", MemberStatus.ACTIVE, joined, lastLogin);
        MemberAdminRow withdrawn = new MemberAdminRow(1L, "탈퇴한 회원", MemberStatus.WITHDRAWN, joined, null);
        when(memberRepository.searchForAdmin(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(active, withdrawn), PageRequest.of(0, 20), 2));

        PageResponse<AdminMemberResponse> response = service.search(query(null, null, null, null, null));

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
