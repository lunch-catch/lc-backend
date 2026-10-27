package com.lunchcatch.member.service;

import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.member.dto.AdminMemberListQuery;
import com.lunchcatch.member.dto.AdminMemberResponse;
import com.lunchcatch.member.dto.AdminMemberSortBy;
import com.lunchcatch.member.repository.MemberAdminRow;
import com.lunchcatch.member.repository.MemberRepository;
import com.lunchcatch.member.repository.MemberSearchCondition;
import com.lunchcatch.member.repository.MemberSortKey;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository memberRepository;

    public PageResponse<AdminMemberResponse> search(AdminMemberListQuery query) {
        if (query.page() < 0) {
            throw new IllegalArgumentException("페이지 번호는 0 이상이어야 합니다.");
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("페이지 크기는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        if (query.joinedFrom() != null && query.joinedTo() != null && query.joinedFrom().isAfter(query.joinedTo())) {
            throw new IllegalArgumentException("가입일 시작 날짜는 종료 날짜보다 늦을 수 없습니다.");
        }
        MemberSortKey sortKey = toSortKey(query.sortBy());
        Sort.Direction direction = toDirection(query.sortDir());

        String trimmed = query.keyword() == null ? "" : query.keyword().trim();
        String nicknamePrefix = trimmed.isEmpty() ? null : trimmed;

        // 저장 시각이 이미 KST 라 날짜의 시작 시각을 변환 없이 그대로 비교한다. 끝 날짜는 포함이라 다음 날 0시 미만이다.
        MemberSearchCondition condition = new MemberSearchCondition(
                query.status(),
                query.joinedFrom() == null ? null : query.joinedFrom().atStartOfDay(),
                query.joinedTo() == null ? null : query.joinedTo().plusDays(1).atStartOfDay(),
                nicknamePrefix);

        Page<MemberAdminRow> rows = memberRepository.searchForAdmin(
                condition, sortKey, direction, PageRequest.of(query.page(), query.size()));
        return PageResponse.from(rows.map(this::toResponse));
    }

    private MemberSortKey toSortKey(String sortBy) {
        if (sortBy == null) {
            return MemberSortKey.JOINED_AT;
        }
        return switch (AdminMemberSortBy.from(sortBy)) {
            case JOINED_AT -> MemberSortKey.JOINED_AT;
            case LAST_LOGIN_AT -> MemberSortKey.LAST_LOGIN_AT;
            case NICKNAME -> MemberSortKey.NICKNAME;
            case MEMBER_ID -> MemberSortKey.MEMBER_ID;
        };
    }

    private Sort.Direction toDirection(String sortDir) {
        if (sortDir == null || "desc".equals(sortDir)) {
            return Sort.Direction.DESC;
        }
        if ("asc".equals(sortDir)) {
            return Sort.Direction.ASC;
        }
        throw new IllegalArgumentException("지원하지 않는 정렬 방향입니다. 오름차순 또는 내림차순을 선택해 주세요.");
    }

    private AdminMemberResponse toResponse(MemberAdminRow row) {
        return new AdminMemberResponse(row.memberId(), row.nickname(), row.status(),
                kst(row.createdAt()), kst(row.lastLoginAt()));
    }

    private OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
