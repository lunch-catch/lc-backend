# 관리자 로그아웃 이관 (#118)

Fresh Market의 관리자 로그아웃을 런치캐치의 admin 서비스와 auth 공통 토큰 저장소에 맞게 이관한다.

- API: DELETE /v1/admin/auth/tokens, ADMIN·SUPER_ADMIN, 요청 본문 없음, 성공 204 및 기존 Path의 인증 쿠키 삭제.
- admin의 짧은 새 트랜잭션에서 로그인·재발급과 같은 행을 잠그고 DB 해시·만료 시각을 폐기한다.
- 확정된 관리자 재발급 브랜치의 revokeBeforeVersion 계약을 재사용해 폐기 순번을 게시하고 이전 캐시 게시를 차단한다.
- Redis I/O는 DB 트랜잭션 밖에서 수행하며 더 최신 발급 포인터를 보존한다.
- 필수 저장소 오류는 AUTH-002로 매핑하고 자격증명을 포함할 수 있는 예외 메시지·cause를 로그로 전달하지 않는다.
- 감사 기록은 ops.contract.AuditLogWriter로 별도 트랜잭션에서 수행하고 실패해도 완료된 로그아웃을 유지한다.
- Access Token 폐기 기준은 원자적으로 전진시키며 기존 필터의 조회 장애 정책을 유지한다.
- 재발급 기능 자체는 이관하지 않는다. 공통 폐기 계약·엔티티 메서드는 재발급 브랜치와 동일한 시그니처를 사용한다.

참고: Fresh Market AdminAuthService.logout, AdminLogoutTransactionService, AdminRefreshTokenCleanupService 및 관련 테스트.
