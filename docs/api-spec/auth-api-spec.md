# 인증 (auth) API 명세

관리자·점주·사용자의 **공통 토큰 정책, 토큰 재발급, 로그아웃**과 **사용자의 카카오 로그인/가입** API의 요청, 응답, 오류를 적는다.

관리자 계정 발급과 로그인은 [admin-api-spec.md](./admin-api-spec.md), 점주 회원가입과 로그인 및 최초 튜토리얼 완료 처리는 `owner-api-spec.md`에서 다룬다. 관리자·점주 로그인 API는 이 문서에 중복 정의하지 않는다. 사용자 온보딩과 탈퇴 `member.md`, 점주 가게 등록은 `store-api-spec.md`에서 다룬다.

세 역할은 공통 토큰 정책을 따르므로, 토큰 정책과 재발급·로그아웃 명세를 이 문서에 모았다.

공통 규약(경로, 응답 봉투, 상태 코드, 식별자, 시각)은 `api-spec.md`를 따르고 여기서는 반복하지 않는다.
서버가 내부에서 어떻게 처리하는지와 그렇게 정한 이유는 설계서 [auth-api-design.md](../design/auth/auth-api-design.md) 에 있다.

## 목록

| 메서드 | 경로 | 하는 일 | 권한 |
| --- | --- | --- | --- |
| `POST` | `/v1/admin/auth/tokens` | 관리자 로그인 | 공개 |
| `POST` | `/v1/admin/auth/tokens:refresh` | 관리자 토큰 재발급 | 공개(Refresh Token) |
| `DELETE` | `/v1/admin/auth/tokens` | 관리자 로그아웃 | ADMIN, SUPER_ADMIN |
| `POST` | `/v1/owner/auth/tokens` | 점주 로그인 | 공개 |
| `POST` | `/v1/owner/auth/tokens:refresh` | 점주 토큰 재발급 | 공개(Refresh Token) |
| `DELETE` | `/v1/owner/auth/tokens` | 점주 로그아웃 | OWNER |
| `GET` | `/v1/auth/kakao/authorize` | 카카오 인가 URL 발급 | 공개 |
| `POST` | `/v1/auth/tokens` | 카카오 로그인(처음이면 가입) | 공개 |
| `POST` | `/v1/auth/tokens:refresh` | 사용자 토큰 재발급 | 공개(Refresh Token) |
| `DELETE` | `/v1/auth/tokens` | 사용자 로그아웃 | MEMBER |

관리자·점주 로그인은 전체 인증 API를 안내하기 위해 목록에 포함한다.

상세 명세는 각각 [admin-api-spec.md](./admin-api-spec.md), `owner-api-spec.md`에서 정의하며, 이 문서에서는 중복 정의하지 않는다.

"공개"는 기존 Access Token 없이 부를 수 있다는 뜻이다.

토큰 재발급 API도 기존 Access Token은 필요하지 않지만, 유효한 Refresh Token은 필요하다.

README의 "로그인 없이 부를 수 있는 경로"는 이 표의 공개 API 7개와 점주 회원가입이다. 점주 회원가입은 아래 이유로 점주 도메인 문서에 있다.

**점주 회원가입은 `owner-api-spec.md`에서 다룬다.** 로그인 전에 열린 경로이지만 계정을 만드는 일이고
토큰 정책과 무관하다. 쿠키도 내려주지 않는다. 점주 도메인의 문서가 소유한다.

## 공통 토큰 정책

| 토큰 | 형식 | 유효 기간 | 쿠키 |
| --- | --- | --- | --- |
| Access Token | JWT | 30분 | `accessToken`, `HttpOnly`, `SameSite=Strict`, `Path=/`, `Max-Age=1800` |
| Refresh Token | Opaque(무작위 문자열) | 관리자 1일, 점주 1일, 사용자 14일 | `refreshToken`, `HttpOnly`, `SameSite=Strict`, 역할별 `Path` 와 `Max-Age` |

**Access Token 은 인증이 필요한 모든 요청에 실려야 하므로 `Path=/` 다. Refresh Token 은 재발급과 로그아웃에서만 쓰므로 역할별로 좁힌다.**

| 역할 | `Path` | `Max-Age` |
| --- | --- | --- |
| 관리자 | `/v1/admin/auth/` | `86400` (1일) |
| 점주 | `/v1/owner/auth/` | `86400` (1일) |
| 사용자 | `/v1/auth/` | `1209600` (14일) |

```
Set-Cookie: accessToken=<JWT>; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800
Set-Cookie: refreshToken=<opaque>; HttpOnly; SameSite=Strict; Path=/v1/auth/; Max-Age=1209600
```

- **토큰은 쿠키로만 오간다.** 응답 본문에 토큰을 싣지 않고, 클라이언트는 토큰 값을 읽거나 `Authorization` 헤더에 싣지 않는다
- **사용자 Refresh Token은 항상 14일 persistent cookie다.** 회원 로그인 화면에 자동 로그인 선택지는 두지 않는다
- 사용자 자동 로그인 선택 기능은 이번 구현에서 제외한다. V51 64·94행의 체크 여부 분기는 적용하지 않으며, 카카오 로그인과 토큰 재발급 모두 같은 14일 쿠키 정책을 사용한다
- **Refresh Token은 회전한다.** 재발급할 때마다 새 토큰을 주고 이전 토큰은 폐기한다. 이미 폐기된 토큰이 다시 오면 탈취로 보고 그 계정의 Refresh Token을 모두 폐기한다
- 로그인과 재발급 때 DB 저장에 실패하면 `AUTH-002`로 실패하고, 캐시 저장 실패는 DB 저장이 성공했다면 허용한다
- **로그아웃하면 Access Token도 막는다.** 로그아웃 전에 발급된 Access Token은 만료 전이라도 거부한다

상태 변경을 반영하기 위한 토큰 재발급은 필수가 아니며, 토큰 만료에 따른 재발급 정책은 유지한다.

## 로그인·재발급 요청 제한

IP별 1분 고정 윈도우에서 인증 요청 10회를 허용한다. 대상 로그인·재발급 경로들이 같은 IP 카운터를 공유한다. 초과 시 `429`와 남은 대기 시간(초)의 `Retry-After`를 반환하며 JSON 본문이나 오류 코드는 없다. 캐시 장애로 횟수를 확인하지 못하면 요청을 통과시킨다.

기본 토큰 수명은 환경 설정으로 바꿀 수 있다. `JWT_COOKIE_SECURE=true`이면 두 쿠키에 `Secure`가 추가된다. 관리자는 Refresh Token을 항상 persistent cookie로 받는다. `503 AUTH-002`에는 `Retry-After: 1`이 포함된다.

## 관리자

### `POST /v1/admin/auth/tokens`

`POST /v1/admin/auth/tokens`의 요청, 응답, 처리 절차 및 오류는 [admin-api-spec.md](./admin-api-spec.md)의 「관리자 로그인」 절을 따른다.

### `POST /v1/admin/auth/tokens:refresh`

Refresh Token을 검증하고 Access Token과 Refresh Token을 모두 교체한다(Rotation).

### 인증 및 요청

기존 Access Token은 필요하지 않다. `refreshToken` 쿠키를 사용하며 요청 본문은 없다.

```http
POST /v1/admin/auth/tokens:refresh
Cookie: refreshToken=<opaque>
```

Refresh Token은 영문·숫자·`_`·`-`로 이루어진 43자 문자열이다. 쿠키 누락·형식 오류도 `400`이 아닌 `401 AUTH-003`으로 처리한다. 회원·점주 Refresh Token으로 관리자 토큰을 발급받을 수 없다.

재발급 요청은 동시에 여러 번 보내지 않도록 직렬화한다. 이미 교체된 토큰을 다시 보내면 해당 관리자의 Refresh Token이 모두 폐기될 수 있다. `401 AUTH-003`·`AUTH-004` 또는 회전 결과 미확정의 `503 AUTH-002`를 받으면 다시 로그인한다. 회전 결과를 모르는 상태에서 기존 토큰으로 자동 반복 재시도하지 않는다.

### 응답 `200 OK`

로그인과 동일한 두 `Set-Cookie`를 새 토큰 값으로 반환하며, 기본 쿠키 수명은 Access 30분, Refresh 1일로 다시 설정된다. JSON 응답도 로그인과 같은 `AdminLoginResponse`다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "adminId": 1,
    "name": "홍길동",
    "role": "ADMIN"
  }
}
```

### 오류

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `401` | `AUTH-003` | Refresh Token 누락·형식 오류·무효·만료·폐기, 다른 도메인 토큰, 계정 없음·비활성 또는 DB 현재 해시 불일치 |
| `401` | `AUTH-004` | 이미 교체된 Refresh Token의 재사용이 감지되어 Refresh Token 폐기를 완료함 |
| `429` | 없음 | IP별 인증 요청 제한 초과. 본문 없이 `Retry-After` 제공 |
| `503` | `AUTH-002` | 필수 저장소 처리 실패 또는 토큰 회전 결과 미확정 |

### `DELETE /v1/admin/auth/tokens`

**구현 예정:** 관리자 로그아웃은 후속 PR에서 구현한다. 아래는 목표 계약이며 현재 브랜치에서 제공하는 API가 아니다.

현재 로그인한 관리자를 로그아웃시킨다. 요청 본문은 없다.

**응답** `204`. 두 쿠키를 `Max-Age=0` 으로 내려 브라우저에서도 지운다. 삭제 쿠키는 발급 때와 같은 `Path` 로 보낸다. `Path` 가 다르면 브라우저가 Refresh Token 쿠키를 지우지 않는다. 점주와 사용자 로그아웃도 같다.

- Refresh Token을 폐기한다. 로그아웃 전에 받은 Access Token은 만료 전이라도 거부된다

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token이 없거나 유효하지 않음 |
| `503` | `AUTH-002` | 로그아웃 처리 실패. 잠시 후 다시 시도한다 |

## 점주

### `POST /v1/owner/auth/tokens`

이메일과 비밀번호로 로그인한다.

**요청**

```json
{
  "email": "owner@example.com",
  "password": "********"
}
```

**응답** `200` 과 두 쿠키

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "email": "owner@example.com",
    "role": "OWNER",
    "status": "ACTIVE",
    "tutorialViewed": false
  }
}
```

응답 본문의 계약은 `owner-api-spec.md`가 소유한다. `ONBOARDING` 점주 응답에는 `tutorialViewed`가 없다.

| 필드 | 뜻 |
| --- | --- |
| `email` | 점주 이메일 |
| `role` | `OWNER` |
| `status` | `ONBOARDING` 이면 프론트는 입점 등록 화면으로 보낸다. 이 상태에서는 입점 등록 API만 열린다 |
| `tutorialViewed` | `ACTIVE` 일 때만 싣는다. `false` 면 프론트가 최초 튜토리얼을 보인다. 완료 처리는 `owner-api-spec.md` |

**동작**

1. 계정이 없어도 비밀번호 비교를 한 번 수행한다(관리자와 같다)
2. 상태가 `ONBOARDING` 또는 `ACTIVE` 일 때만 로그인시킨다. `SUSPENDED`, `WITHDRAWN` 이면 거부한다
3. 최근 로그인 시각을 갱신하고 두 토큰을 내려준다

점주 로그인은 DB 행 잠금 아래 RT 해시와 발급 순번을 저장하고, 커밋 후 현재 상태·해시·순번을 재확인한 뒤 Redis에 `saveIfNewer`로 게시한다.
Redis 유실이나 게시 실패는 DB 커밋과 원자적으로 처리할 수 없으므로 DB 저장 성공 시 로그인을 유지하는 공통 정책을 따른다.
캐시 장애 로그에는 토큰을 남기지 않는다.
점주 토큰 재발급은 Redis 조회에 성공해도 DB에 저장된 현재 RT 해시, 만료 시각, 점주 상태를 다시 확인한다. Redis에 이전 RT가 남아 있어도 DB의 현재 해시와 다르면 거부한다. Redis 장애로 DB에서 처리할 때도 점주 행을 잠그고, 요청받은 RT의 해시가 DB 값과 일치하며 만료되지 않았고 점주가 재발급 가능한 상태인 경우에만 새 RT로 교체한다.

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `400` | `COMMON-002` | `email` 또는 `password` 누락 |
| `401` | `AUTH-001` | 계정 없음, 비밀번호 불일치, `SUSPENDED`, `WITHDRAWN`. 사유를 노출하지 않는다 |
| `503` | `AUTH-002` | Refresh Token 저장 실패. 관계형 DB 해시 백업에 실패한 경우이며, 인메모리 캐시 저장 실패는 오류가 아니다 |

### `POST /v1/owner/auth/tokens:refresh`

관리자 재발급과 같다. 추가로 Refresh Token에 연결된 점주의 **현재 상태**를 다시 확인한다.

- `SUSPENDED`, `WITHDRAWN` 이면 재발급하지 않는다
- 재발급 가능 여부는 `findCurrentStatus` 또는 동등한 DB 직접 조회로 확인하며, DB 해시의 조건부 회전에서도 현재 계정 상태와 만료 시각을 검증한다. 상태 캐시만으로 발급을 허용하지 않는다
- 점주 전용 Redis 처리는 `owner.repository.OwnerRefreshTokenRepository`가 담당한다. 기존 공용 `RefreshTokenRepository`의 회전 결과와 처리 방식은 유지하며, 새 RT 저장에는 공용 `saveIfNewer`를 사용한다.
- 정상 재발급: 먼저 Redis에서 기존 RT를 처리 중(`PENDING`)으로 표시한다. 처리 중 같은 RT로 들어온 중복 요청은 `401 / AUTH-003`으로 거부하되 계정 토큰을 폐기하지 않는다. 이어서 DB의 해당 점주 행을 잠그고 RT 해시·만료 시각·점주 상태를 확인한 뒤, 새 RT 해시와 만료 시각을 저장하고 발급 순번을 증가시킨다. 최근 로그인 시각은 변경하지 않는다
- DB 저장이 확정된 뒤에는 현재 RT 해시·점주 상태·발급 순번을 다시 확인하고 `saveIfNewer`로 Redis에 새 RT를 저장한다. 이때 순번을 비교하므로 늦게 도착한 이전 요청이 더 최신 RT를 덮어쓰지 못한다. Redis 저장만 실패했다면 DB 저장을 기준으로 재발급 성공을 유지한다
- RT 재사용 감지: 재발급의 DB 커밋과 Redis 게시 시도가 끝나면 기존 RT를 재사용 감지용(`REVOKED`)으로 확정한다. 이후 같은 RT가 들어오면 DB의 현재 RT 해시와 만료 시각을 지우고 순번을 증가시켜 폐기 기준으로 사용한다. Redis에서도 RT를 폐기하고, 폐기 기준 시각보다 먼저 발급된 AT도 사용할 수 없게 한다. 폐기 기준 순번 이하의 이전 요청이 뒤늦게 RT를 다시 저장하는 것도 막는다. 폐기 처리에 실패하면 `503 / AUTH-002`로 응답하며, DB와 Redis 작업이 항상 함께 성공하거나 함께 취소되는 것은 아니다
- AT 차단 기준: 점주 DB 행 잠금 안에서 RT 폐기를 반영한 시각까지 발급된 AT를 차단한다. 잠금을 기다리는 동안 현재 RT의 재발급으로 발급된 AT도 포함한다. 발급 시각과 폐기 시각이 같아도 차단하도록 비교 기준을 폐기 시각보다 1ns 뒤로 설정한다. JWT 발급 시각은 초 단위이므로 폐기 직후 같은 초에 발급된 AT도 차단될 수 있다. 늦게 도착한 이전 요청이 차단 시각을 과거로 되돌리거나 기존 차단 정보의 남은 보관 시간을 줄이지 못하게 한다
- 폐기 장애 한계: DB의 RT 폐기 후 Redis의 RT 폐기 또는 AT 차단이 실패하면 `503 / AUTH-002`로 응답한다. DB 폐기는 이미 커밋되어 있으며 기존 AT 차단이 누락될 수 있다. AT 검증의 기존 `LENIENT` 정책은 유지한다. 실패 작업의 영구 기록과 자동 재시도는 이번 PR에 포함하지 않으며 후속 리팩터링에서 설계한다
- 처리 중 표시의 확정이 실패하거나 서버가 중단되면 `PENDING`은 기존 RT의 만료 시각까지 남을 수 있다. 별도 스케줄러는 두지 않으므로 만료 전 기존 RT 요청은 `401 / AUTH-003`으로 거부하고, 새 RT를 받지 못한 점주는 다시 로그인해야 한다. DB의 현재 해시와 다른 RT가 다시 발급되는 것은 허용하지 않는다
- Redis 장애 시: DB에 저장된 RT 정보로 재발급을 처리한다. 다만 Redis에서 토큰 없음·만료·재사용을 정상적으로 확인한 경우에는 DB로 다시 시도하지 않는다. Redis 장애 중에는 이전 RT의 사용 불가 표시를 확인할 수 없어 재사용 여부를 구분하지 못하지만, DB의 현재 해시와 다른 RT는 `401 / AUTH-003`으로 거부한다
- 가게 최종 등록(42행)에서 DB 상태를 `ACTIVE`로 변경하고 커밋 성공 후 점주 상태 캐시를 무효화한다. 캐시가 비어 있는 다음 상태 조회는 DB의 새 상태를 읽으며, 삭제 실패와 경합에 따른 지연 가능성은 공통 토큰 정책의 상태 캐시 규칙을 따른다. 상태 반영만을 위한 재발급은 필요하지 않다. 프론트는 최종 등록 응답이나 점주 정보 조회로 화면을 갱신한다

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-003` | Refresh Token이 없음, 유효하지 않음, 만료, 폐기됨, 재발급할 수 없는 계정 상태 |
| `401` | `AUTH-004` | 이미 교체된 Refresh Token의 재사용 |

### `DELETE /v1/owner/auth/tokens`

관리자 로그아웃과 같다. 응답은 `204` 이고 두 쿠키를 지운다.

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token이 없거나 유효하지 않음 |
| `503` | `AUTH-002` | 로그아웃 처리 실패. 잠시 후 다시 시도한다 |

## 사용자

### `GET /v1/auth/kakao/authorize`

카카오 인가 화면으로 이동할 URL을 발급한다. 서버는 환경 설정의 고정 `KAKAO_REDIRECT_URI`를 사용하고, Redis에 일회용 `state`와 OIDC `nonce`를 저장한 뒤 이를 인가 URL에 넣는다. 로그인과 탈퇴 재인증은 같은 인가 흐름을 쓰며, 재인증 옵션도 서버가 관리한다.

| 쿼리 | 필수 | 뜻 |
| --- | --- | --- |
| `reauth` | 아니오 | `true`이면 카카오 `prompt=login`을 붙여 탈퇴 재인증을 요청한다. 회원의 카카오 로그인에는 생략하며 기본값은 `false`다. |

**응답**은 아래처럼 카카오 인가 URL 하나다. `state`, `nonce`, 고정 redirect URI는 각각 별도 응답 필드가 아니라 이 URL의 쿼리 파라미터다.

```json
{
  "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=...&redirect_uri=...&scope=openid%20profile_nickname%20profile_image&state=...&nonce=..."
}
```

#### 인가 요청과 콜백 흐름

1. 프론트가 회원 화면의 카카오 로그인 버튼을 누른 뒤 이 API를 호출한다. 회원 로그인 수단은 카카오 하나이며, 별도의 아이디·비밀번호 로그인 API는 없다. 카카오 로그인에는 쿼리를 붙이지 않고, 로그인한 회원의 탈퇴 재인증에만 `reauth=true`를 붙인다.
2. 프론트는 응답의 `authorizationUrl`에서 `state`를 추출해 현재 탭의 `sessionStorage`에 저장한 뒤, URL 전체로 브라우저를 이동시킨다. 프론트는 `nonce`를 저장하거나 요청 본문에 넣지 않는다.
3. 카카오는 로그인과 동의 뒤 고정 callback URL로 `code`, `state`를 붙여 브라우저를 되돌린다. `nonce`는 callback URL이 아니라 이후 카카오가 발급하는 서명된 `id_token`의 claim에 들어간다.
4. 프론트 callback은 받은 state가 `sessionStorage` 값과 같은지 확인하고 저장한 값을 즉시 지운다. 카카오 로그인으로 시작한 경우 `POST /v1/auth/tokens`에 `authorizationCode`, `state`를 보낸다. 탈퇴를 위한 카카오 재인증으로 시작한 경우 현재 회원의 인증 쿠키와 함께 `POST /v1/members/me:withdraw`에 같은 두 값과 선택 `reason`을 보낸다. 프론트는 인가 시작 시 callback 이후 진행할 동작도 현재 탭에 보관한다.

### `POST /v1/auth/tokens`

카카오 인가 코드를 받아 로그인한다. 처음 온 사용자면 가입까지 한다.

**로그인 수단은 카카오 하나다.** 요청에 어느 소셜인지 가리는 필드를 두지 않는다. 경로가
하나이고 들어오는 인가 코드는 항상 카카오 것이다.

**요청**

```json
{
  "authorizationCode": "kakao-auth-code",
  "state": "one-time-state"
}
```

| 필드 | 필수 | 뜻 |
| --- | --- | --- |
| `authorizationCode` | 예 | 카카오 로그인 화면이 돌려준 인가 코드 |
| `state` | 예 | 카카오 callback의 state. 프론트가 현재 탭에 보관한 값과 먼저 대조하고, 서버는 Redis의 일회용 값과 대조한 뒤 즉시 소비한다 |

**응답** `200` 과 두 쿠키

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "memberId": 1024,
    "nickname": "점심헌터",
    "newMember": true,
    "onboardingCompleted": false
  }
}
```

| 필드 | 뜻 |
| --- | --- |
| `newMember` | 이번 요청으로 가입했으면 `true` |
| `onboardingCompleted` | `false` 면 프론트는 온보딩 폼으로 보낸다. 다음 로그인 때도 끝나지 않았으면 다시 보낸다 |

**동작**

1. 회원번호로 사용자를 찾는다. 없으면 가입시킨다. 닉네임과 프로필 이미지는 **최초 가입과 탈퇴 뒤 재가입 때만** 카카오에서 가져온다. 프로필 사진 동의가 없거나 카카오가 값을 주지 않으면 `profileImageUrl`은 `null`이다
2. 성별과 연령은 카카오에서 받지 않는다(온보딩에서 받는다). 기기 식별자는 수집하지 않는다
3. **정지 이력이 있는 회원번호의 재가입은 거부한다.** 탈퇴 후 재가입해도 같은 회원번호라 하루 3개 발급 제한과 정지 이력이 이어진다
4. 두 토큰을 내려준다

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `400` | `COMMON-002` | 필수 필드 누락 |
| `401` | `AUTH-007` | state 불일치·만료·재사용, 카카오 인가 코드나 ID 토큰(nonce 포함) 검증 실패 |
| `403` | `MEMBER-003` | 정지 이력이 있는 회원번호의 재가입. 코드는 `member.md`가 정의한다 |
| `503` | `AUTH-002` | Refresh Token 저장 실패. 관계형 DB 해시 백업에 실패한 경우이며, 인메모리 캐시 저장 실패는 오류가 아니다 |

### `POST /v1/auth/tokens:refresh`

관리자 재발급과 같다. 카카오 재인증은 필요 없다.

- 새 Refresh Token도 14일 persistent cookie로 발급한다
- 캐시 장애 시 DB 해시 폴백으로 재발급한 경우에도 같은 14일 쿠키 정책을 사용한다. 자동 로그인 선택값을 저장하거나 복원하지 않는다
- 이미 쓴 Refresh Token이 다시 오면 그 사용자의 Refresh Token을 모두 폐기한다

**오류**: 관리자 재발급과 같다(`AUTH-002`, `AUTH-003`, `AUTH-004`).

### `DELETE /v1/auth/tokens`

서비스에서 로그아웃시킨다. 응답은 `204` 이고 두 쿠키를 지운다.

- Refresh Token을 폐기한다. 로그아웃 전에 받은 Access Token은 거부된다
- 카카오 쪽 로그아웃도 요청한다. 카카오 계정 자체를 로그아웃시키지는 않는다

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-005` | 로그인 상태가 아님 |

## 접근 제어

**권한은 역할로 판정한다.** 경로 접두어와 역할이 맞지 않으면 `403` 이다.

| 경로 | 허용 역할 |
| --- | --- |
| `/v1/admin/**` | `ADMIN`, `SUPER_ADMIN` (계정 발급은 `SUPER_ADMIN` 만) |
| `/v1/owner/**` | `OWNER` |
| `/v1/**` (위 둘 제외) | `MEMBER` |
| 목록의 공개 7개 | 누구나 |
| 점주 회원가입 | 누구나. 경로와 코드는 점주 도메인의 문서가 소유한다 |

**점주는 상태로 한 번 더 막는다.** 점주 상태가 `ONBOARDING` 이면 입점 등록 API(약관 동의, 가게 기본 정보, 사업자 검증, 영업시간, 이미지, 메뉴, 최종 등록)만 열고 나머지 점주 API는 `403` 이다.

입점 등록 API의 확정 경로는 `store-api-spec.md`에 있다.

이 절은 구현할 접근 제어 계약이다. 현재 점주 인증 구현에는 상태 조회 기반이 있으며, 상태 변경 서비스와 캐시 무효화 호출부 및 각 업무 API의 상태 제한은 아직 연결되어 있지 않다. 상태 변경을 추가할 때 DB 변경과 커밋 후 무효화 등록을 한 점주 서비스 작업으로 묶고, 각 업무 API는 역할과 필요한 현재 상태를 직접 검증해야 한다.

**이 판정의 오류 코드는 이 문서가 소유하지 않는다.** 점주 상태(`ONBOARDING`, `ACTIVE`,
`SUSPENDED`)는 점주 도메인의 데이터이고, 역할만 아는 인증 모듈은 그 값을 판정할 수 없다.
코드는 점주 도메인의 문서가 정한다.

**접근 제어 오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token이 없거나, 만료됐거나, 로그아웃 전에 발급됨 |
| `403` | `AUTH-006` | 역할이 맞지 않음 |

권한이 없으면 리소스 존재 여부와 상관없이 `403` 이다(README 상태 코드).

## 오류 코드

공통 규약은 같은 폴더의 `api-spec.md`를 따른다. 도메인별 오류 코드는 각 도메인 문서에서 정의한다.

**이 표는 `AUTH-` 만 소유한다.** 카카오 인증 실패(`AUTH-007`)는 토큰을 만드는 경로에서 생기는 인증 실패이므로 여기서 정의한다.

| 코드 | 상태 | 메시지 |
| --- | --- | --- |
| `AUTH-001` | `401` | 아이디 또는 비밀번호가 올바르지 않습니다. |
| `AUTH-002` | `503` | 일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요. |
| `AUTH-003` | `401` | 다시 로그인해 주세요. |
| `AUTH-004` | `401` | 보안을 위해 모든 기기에서 로그아웃되었습니다. 다시 로그인해 주세요. |
| `AUTH-005` | `401` | 로그인이 필요합니다. |
| `AUTH-006` | `403` | 접근 권한이 없습니다. |
| `AUTH-007` | `401` | 카카오 인증에 실패했습니다. 다시 시도해 주세요. |
