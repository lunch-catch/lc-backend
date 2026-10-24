package com.launchcatch.admin.dto;

// 관리자 이름 조회(AdminNameQuery)가 필요한 두 컬럼만 읽는 값이다. 엔티티와 비밀번호 해시 등은 읽지 않는다.
public record AdminIdName(Long id, String name) { }
