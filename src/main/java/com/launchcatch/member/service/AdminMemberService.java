package com.launchcatch.member.service;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.response.PageResponse;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.repository.MemberAdminRow;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberSearchCondition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberService {

    public static final int PAGE_SIZE = 20;

    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d+");

    private final MemberRepository memberRepository;

    public PageResponse<AdminMemberResponse> search(
            MemberStatus status, LocalDate joinedFrom, LocalDate joinedTo, String keyword, int page) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw new IllegalArgumentException("joinedFrom must not be after joinedTo");
        }

        String trimmed = keyword == null ? "" : keyword.trim();
        Long memberId = null;
        String nicknamePrefix = null;
        if (DIGITS_ONLY.matcher(trimmed).matches()) {
            memberId = parseMemberId(trimmed);
            if (memberId == null) {
                // Long 범위를 넘는 번호는 어떤 회원도 아니다
                return PageResponse.of(List.of(), page, PAGE_SIZE, 0);
            }
        } else if (!trimmed.isEmpty()) {
            nicknamePrefix = trimmed;
        }

        // 저장 시각이 이미 KST 라 날짜의 시작 시각을 변환 없이 그대로 비교한다. 끝 날짜는 포함이라 다음 날 0시 미만이다.
        MemberSearchCondition condition = new MemberSearchCondition(
                status,
                joinedFrom == null ? null : joinedFrom.atStartOfDay(),
                joinedTo == null ? null : joinedTo.plusDays(1).atStartOfDay(),
                memberId,
                nicknamePrefix);

        Page<MemberAdminRow> rows = memberRepository.searchForAdmin(condition, PageRequest.of(page, PAGE_SIZE));
        return PageResponse.from(rows.map(this::toResponse));
    }

    private Long parseMemberId(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AdminMemberResponse toResponse(MemberAdminRow row) {
        return new AdminMemberResponse(row.memberId(), row.nickname(), row.status(),
                kst(row.createdAt()), kst(row.lastLoginAt()));
    }

    private OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
