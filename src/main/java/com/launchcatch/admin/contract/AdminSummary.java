package com.launchcatch.admin.contract;

/*
 * 다른 도메인이 화면에 "누가 했는지"를 보여 주려고 읽는 관리자 요약이다. 이름은 유일하지 않아 같은 이름의 관리자를
 * 구분할 수 있게 유일한 로그인 ID 를 함께 준다. 권한, 상태 등 다른 계정 정보는 내주지 않는다.
 */
public record AdminSummary(String name, String loginId) { }
