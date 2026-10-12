# 관리자 계정 발급의 Access Token 폐기 확인

## 처리 정책

- 공통 JWT 필터는 `CutoffPolicy.LENIENT`를 사용한다. Redis 조회 장애 시 기존 동작을 유지하되, 발급 시각(`iat`) 누락은 역할과 무관하게 WARN 로그 후 인증하지 않는다.
- 필터가 검증한 발급 시각을 `CustomUserDetails.issuedAt`로 서비스에 전달한다. 클라이언트 요청 본문에서 발급 시각을 받지 않는다.
- 관리자 계정 발급 서비스는 `CutoffPolicy.REQUIRED`로 다시 확인한다. 확인은 해싱과 DB 작업 전에 수행한다.
- 필수 확인에서 `DataAccessException`이 발생하면 `AuthException(REFRESH_TOKEN_STORE_UNAVAILABLE)`으로 변환한다. 기존 공통 예외 처리에서 `503 / AUTH-002`, `Retry-After: 1`, ERROR 로그를 제공한다.
- 정상 조회 결과 이미 폐기된 토큰이면 `401 / AUTH-005`다. 현재 DB의 `ACTIVE`·`SUPER_ADMIN` 검사와 계정·감사 로그의 단일 트랜잭션은 유지한다.

## 보안 체인

- 발급 전용 체인: `@Order(90)`.
- matcher: `/v1/admin/admins`, `/v1/admin/admins/**`.
- 허용 요청: `POST /v1/admin/admins`, `SUPER_ADMIN`만.
- 일반 도메인 체인은 `@Order(100)`이므로 넓은 `/v1/admin/**` 체인이 추가되어도 발급 전용 체인이 먼저 선택된다.
- 엄격한 조회 정책은 체인 전체가 아니라 발급 서비스 작업에 적용한다. `ApiSecurityDefaults`는 원본 그대로 사용하며 이전 패치의 `applyRequiringAccessTokenCutoff()`는 사용하지 않는다.

## 검증

- `AccessTokenCutoffVerifierTest`: 두 정책의 장애 처리, 발급 시각 누락, 정상·폐기 토큰.
- `JwtAuthenticationFilterTest`: 모든 역할의 발급 시각 누락 거부 및 기존 장애 허용 동작.
- `AdminRegistrationServiceTest`: 필수 조회 장애의 AUTH-002 변환과 DB·해싱 작업 미실행.
- `AdminRegistrationSecurityIntegrationTest`: 실제 Spring Security 체인과 MVC·서비스를 연결한다. 저장소만 테스트 더블로 대체하며, 넓은 관리자 체인과의 우선순위, 503 응답·Retry-After·ERROR 로그, 403·401·201 응답을 검증한다. 실제 Redis·MySQL과의 통합이나 DB 롤백 실험을 대신하지 않는다.

제공 환경에서 Gradle 배포 파일 다운로드가 네트워크 제한으로 실패하여 테스트는 실행하지 못했다. 적용 후 `./gradlew clean check`로 확인한다.
