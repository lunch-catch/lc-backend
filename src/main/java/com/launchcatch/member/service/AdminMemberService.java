package com.launchcatch.member.service;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.response.PageResponse;
import com.launchcatch.member.dto.AdminMemberListQuery;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.dto.AdminMemberSortBy;
import com.launchcatch.member.repository.MemberAdminRow;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberSearchCondition;
import com.launchcatch.member.repository.MemberSortKey;
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
            throw new IllegalArgumentException("page must not be negative");
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (query.joinedFrom() != null && query.joinedTo() != null && query.joinedFrom().isAfter(query.joinedTo())) {
            throw new IllegalArgumentException("joinedFrom must not be after joinedTo");
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
        throw new IllegalArgumentException("unsupported sortDir: " + sortDir);
    }

    private AdminMemberResponse toResponse(MemberAdminRow row) {
        return new AdminMemberResponse(row.memberId(), row.nickname(), row.status(),
                kst(row.createdAt()), kst(row.lastLoginAt()));
    }

    private OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
