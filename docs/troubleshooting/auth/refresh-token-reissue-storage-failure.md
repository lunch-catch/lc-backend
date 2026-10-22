# Refresh Token 재발급 저장소 장애 처리

## 도메인

auth

## 이슈

Redis 회전 성공 후 DB Refresh Token 백업 회전이 실패하면, 재발급 흐름이 DB 폴백으로 잘못 진입하고 DB 장애 예외가 비정형 500으로 새어 나갈 수 있다.

## 발생 조건

1. Refresh Token 재발급 요청이 Redis `compareAndRotate()`를 통과한다.
2. Redis에는 새 토큰 레코드와 활성 포인터가 반영되고, 이전 토큰은 tombstone 처리된다.
3. 이어지는 DB `rotateIfMatches()` 호출에서 `DataAccessException`이 발생한다.
4. 넓은 `catch (DataAccessException)`이 DB 예외까지 Redis 장애처럼 처리하여 DB 폴백 경로로 진입한다.
5. DB가 계속 장애 상태이면 DB 폴백의 `findValidByHash()` 또는 `rotateIfMatches()`에서도 예외가 발생하고, `AuthException`으로 변환되지 않은 원시 예외가 밖으로 전달된다.

## 영향

- 클라이언트가 표준 인증 오류 `AUTH-002` 대신 비정형 500을 받을 수 있다.
- Redis는 새 토큰을 가리키지만 DB 백업은 이전 상태에 남아 저장소 정합성이 깨질 수 있다.
- Redis 회전이 이미 성공한 경우에도 새 Redis 토큰을 보상 폐기하지 못할 수 있다.

## 해결

- `compareAndRotate()` 호출만 별도 Redis 예외 처리로 분리한다. Redis 장애일 때만 DB 폴백을 수행한다.
- Redis 회전 성공 후 DB `rotateIfMatches()`가 실패하면 새 Redis 토큰을 보상 폐기하고 `REFRESH_TOKEN_STORE_UNAVAILABLE` (`AUTH-002`)로 변환한다.
- DB 폴백 내부의 DB 조회·회전에서 발생하는 `DataAccessException`도 `AUTH-002`로 변환한다.

## 검증

- Redis 회전 뒤 DB 회전 실패 시 새 Redis 토큰을 보상 폐기한다.
- Redis 장애 후 DB 폴백 조회가 실패하면 `AUTH-002`를 반환한다.
- `OpaqueRefreshTokenLifecycleTest` 통과.

## 관련 커밋

- `f911065` `[Fix] Refresh Token 재발급 저장소 장애를 일관되게 처리한다`
