package com.launchcatch.member.controller;

import com.launchcatch.global.response.PageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.AdminMemberListQuery;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.dto.AdminMemberSearchType;
import com.launchcatch.member.service.AdminMemberService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    // TODO: 관리자 인증·인가 구현 후 실제 권한 정책으로 교체한다.
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ResponseEnvelope<PageResponse<AdminMemberResponse>> list(
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @RequestParam(required = false) AdminMemberSearchType searchType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(adminMemberService.search(new AdminMemberListQuery(
                status, joinedFrom, joinedTo, searchType, keyword, page, size, sortBy, sortDir)));
    }
}
