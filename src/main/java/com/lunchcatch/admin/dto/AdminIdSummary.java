package com.lunchcatch.admin.dto;

// 관리자 요약 조회(AdminSummaryQuery)가 필요한 세 컬럼만 읽는 값이다. 엔티티와 비밀번호 해시 등은 읽지 않는다.
public record AdminIdSummary(Long id, String name, String loginId) { }
