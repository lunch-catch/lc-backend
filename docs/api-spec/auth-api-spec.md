# 인증 (auth) API 명세

관리자·점주·사용자의 **공통 토큰 정책, 토큰 재발급, 로그아웃**과 **사용자의 카카오 로그인/가입** API의 요청, 응답, 오류를 적는다.

관리자 계정 발급과 로그인은 [admin-api-spec.md](./admin-api-spec.md), 점주 회원가입·로그인·내 정보 조회·최초 튜토리얼 완료 처리는 [owner-api-spec.md](owner-api-spec.md)에서 다룬다. 관리자·점주 로그인 API는 이 문서에 중복 정의하지 않는다. 사용자 온보딩과 탈퇴는 `member.md`, 점주 입점 API는 `store-api-spec.md`에서 다룬다.

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

관리자·점주 로그인은 전체 인증 API를 안내하기 위해 목록에 포함한다. 점주 로그인·토큰 재발급·로그아웃은 모두 구현되어 있다.

로그인의 상세 명세는 각각 [admin-api-spec.md](./admin-api-spec.md), `owner-api-spec.md`에서 정의한다. 재발급과 로그아웃은 아래 역할별 절을 따른다.

"공개"는 기존 Access Token 없이 부를 수 있다는 뜻이다.

토큰 재발급 API도 기존 Access Token은 필요하지 않지만, 유효한 Refresh Token은 필요하다.

`api-spec.md`의 "로그인 없이 부를 수 있는 경로"는 이 표의 공개 API 7개와 점주 회원가입이다. 점주 회원가입은 아래 이유로 점주 도메인 문서에 있다.

**점주 회원가입은 `owner-api-spec.md`에서 다룬다.** 로그인 전에 열린 경로이지만 계정을 만드는 일이고
토큰 정책과 무관하다. 쿠키도 내려주지 않는다. 점주 도메인의 문서가 소유한다.

## 공통 토큰 정책

| 토큰 | 형식 | 유효 기간 | 쿠키 |
| --- | --- | --- | --- |
| Access Token | JWT | 30분 | 역할별 이름, `HttpOnly`, `SameSite=Strict`, `Path=/`, `Max-Age=1800` |
| Refresh Token | Opaque(무작위 문자열) | 관리자 1일, 점주 1일, 사용자 14일 | `refreshToken`, `HttpOnly`, `SameSite=Strict`, 역할별 `Path` 와 `Max-Age` |

**Access Token 은 인증이 필요한 모든 요청에 실려야 하므로 `Path=/` 다. Refresh Token 은 재발급과 로그아웃에서만 쓰므로 역할별로 좁힌다.**

**Access Token 쿠키는 역할마다 이름이 다르다(#841).** 같은 브라우저에서 관리자와 점주로 함께 로그인해도 서로의 토큰을 덮어쓰지 않는다. 서버는 요청 경로로 읽을 쿠키를 고른다. `/v1/admin` 아래는 관리자 쿠키를, `/v1/owner` 아래는 점주 쿠키를, 나머지는 사용자 쿠키를 읽고 다른 역할의 쿠키는 함께 실려도 쓰지 않는다.

| 역할 | Access Token 쿠키 | 읽는 경로 | Refresh Token `Path` | Refresh Token `Max-Age` |
| --- | --- | --- | --- | --- |
| 관리자 | `adminAccessToken` | `/v1/admin/**` | `/v1/admin/auth/` | `86400` (1일) |
| 점주 | `ownerAccessToken` | `/v1/owner/**` | `/v1/owner/auth/` | `86400` (1일) |
| 사용자 | `accessToken` | 그 밖의 경로 | `/v1/auth/` | `1209600` (14일) |

```
Set-Cookie: accessToken=<JWT>; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800
Set-Cookie: refreshToken=<opaque>; HttpOnly; SameSite=Strict; Path=/v1/auth/; Max-Age=1209600
```

- **토큰은 쿠키로만 오간다.** 응답 본문에 토큰을 싣지 않고, 클라이언트는 토큰 값을 읽거나 `Authorization` 헤더에 싣지 않는다
- **사용자 Refresh Token은 항상 14일 persistent cookie다.** 회원 로그인 화면에 자동 로그인 선택지는 두지 않는다
- 사용자 자동 로그인 선택 기능은 이번 구현에서 제외한다. FR-061, FR-091의 체크 여부 분기는 적용하지 않으며, 카카오 로그인과 토큰 재발급 모두 같은 14일 쿠키 정책을 사용한다
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

현재 인증된 관리자의 토큰을 폐기하고 브라우저 인증 쿠키를 삭제한다.

**요청**

- 유효한 `adminAccessToken` 쿠키와 `ADMIN` 또는 `SUPER_ADMIN` 권한이 필요하다.
- 요청 본문은 없다. 관리자 ID는 인증 정보에서 확인하므로 경로 변수·쿼리로 전달하지 않는다.
- Refresh Token 쿠키는 필수 입력이 아니다. Access Token만으로 계정의 토큰을 폐기한다.

```http
DELETE /v1/admin/auth/tokens
Cookie: adminAccessToken=<JWT>
```

**응답** `204 No Content`. 두 인증 쿠키를 `Max-Age=0`으로 삭제한다.

삭제 쿠키는 발급 때와 같은 `Path` 로 보낸다. `Path` 가 다르면 브라우저가 Refresh Token 쿠키를 지우지 않는다. 점주와 사용자 로그아웃도 같다.

```http
HTTP/1.1 204 No Content
Set-Cookie: adminAccessToken=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict
Set-Cookie: refreshToken=; Path=/v1/admin/auth/; Max-Age=0; HttpOnly; SameSite=Strict
```

응답 본문과 성공 응답 봉투는 없다. 쿠키 삭제 시에도 `JWT_COOKIE_SECURE=true` 환경이면 `Secure` 속성이 포함된다.

**오류**

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token 없음·무효·만료·폐기 또는 DB 계정 없음·비활성·현재 역할 불일치 |
| `403` | `AUTH-006` | 인증된 주체가 관리자 역할이 아님 |
| `503` | `AUTH-002` | 필수 DB Refresh Token 폐기, Redis Refresh Token 폐기 또는 Access Token 폐기 기준 저장 실패 |

로그아웃의 필수 폐기 처리가 실패하면 삭제 쿠키 없이 `503 AUTH-002`를 반환하며, 일부 폐기는 이미 적용되었을 수 있다. 로그아웃 뒤 같은 초에 다시 로그인해 받은 새 토큰은 유지된다. 폐기를 검증하는 순서와 저장소 장애 시 정책은 [관리자 설계서](../design/admin/admin-api-design.md#로그아웃)에 있다.

## 점주

### `POST /v1/owner/auth/tokens`

요청·응답·오류는 [점주 명세의 로그인 절](owner-api-spec.md#로그인)을 따른다. 로그인 API는 인증 경로 전체를 안내하기 위해 목록에 포함한다.

### `POST /v1/owner/auth/tokens:refresh`

유효한 `refreshToken` 쿠키로 Access Token과 Refresh Token을 교체한다. 기존 Access Token과 요청 본문은 필요하지 않다. DB 현재 상태가 ONBOARDING 또는 ACTIVE인 점주만 재발급할 수 있다.

성공 시 `200`과 새 AT·RT의 `Set-Cookie`를 반환한다. 응답 본문의 필드와 상태별 `tutorialViewed` 포함 조건은 [점주 로그인 응답](owner-api-spec.md#로그인)을 따른다. 토큰은 응답 본문에 포함하지 않는다. 쿠키 속성과 수명은 공통 토큰 정책을 따른다.

처리 중인 동일 RT의 중복 요청은 `401 AUTH-003`으로 거부하지만 계정 토큰을 폐기하지 않는다. 교체가 확정된 RT를 재사용하면 현재 RT와 그때까지 발급된 AT를 모두 막는다. 새 RT를 받지 못한 경우에는 다시 로그인한다.

| 상태 | 코드 | 조건 |
|---|---|---|
| `401` | `AUTH-003` | RT 없음·만료·폐기·유효하지 않음, 처리 중인 중복 요청, 재발급 불가 계정 상태 |
| `401` | `AUTH-004` | 교체가 확정된 RT의 재사용 |
| `503` | `AUTH-002` | DB 회전·확인·폐기 또는 필수 Redis 폐기 처리 실패 |

행 잠금·Redis 소비 표시·게시 순서·실패 복구는 [인증 설계의 점주 재발급 절](../design/auth/auth-api-design.md#점주)을 따른다.

### `DELETE /v1/owner/auth/tokens`

현재 인증된 점주의 토큰을 폐기한다. 유효한 OWNER Access Token이 필요하며 요청 본문과 Refresh Token 쿠키는 필요하지 않다. ONBOARDING 점주도 호출할 수 있다.

성공 시 `204`와 두 인증 쿠키를 삭제하는 `Set-Cookie`를 반환하며 본문은 없다. 삭제 쿠키는 `ownerAccessToken`의 `Path=/`, `refreshToken`의 `Path=/v1/owner/auth/`와 공통 보안 속성을 유지하고 `Max-Age=0`으로 보낸다.

로그아웃 전에 받은 RT·AT는 사용할 수 없다. 같은 초에 재로그인해도 새로 받은 토큰은 유지된다.

`503`을 받았어도 폐기가 이미 저장됐을 수 있다. 그 경우 기존 AT로 다시 부르면 `401 / AUTH-005`가 반환된다. 따라서 `503`을 받은 뒤 같은 AT로 반복 호출해 복구할 수 있다고 가정하지 않는다.

| 상태 | 코드 | 조건 |
|---|---|---|
| `401` | `AUTH-005` | Access Token 없음·만료·폐기·유효하지 않음, 점주 계정 없음 |
| `403` | `AUTH-006` | 인증된 역할이 OWNER가 아님 |
| `503` | `AUTH-002` | DB 폐기 또는 Redis RT 폐기·AT 차단 처리 실패 |

DB·Redis 처리 순서와 자동 복구 한계는 [인증 설계의 점주 로그아웃 절](../design/auth/auth-api-design.md#점주)을 따른다.

## 사용자

### `GET /v1/auth/kakao/authorize`

카카오 인가 화면으로 이동할 URL을 발급한다. 서버는 환경 설정의 고정 `KAKAO_REDIRECT_URI`를 사용하고, Redis에 일회용 `state`와 OIDC `nonce`를 저장한 뒤 이를 인가 URL에 넣는다. 로그인과 탈퇴 재인증은 같은 인가 흐름을 쓰며, 재인증 옵션도 서버가 관리한다.

| 쿼리 | 필수 | 뜻 |
| --- | --- | --- |
| `reauth` | 아니오 | `true`이면 카카오 `prompt=login`을 붙여 탈퇴 재인증을 요청한다. 회원의 카카오 로그인에는 생략하며 기본값은 `false`다. |

**응답**은 아래처럼 카카오 인가 URL 하나다. `state`, `nonce`, 고정 redirect URI는 각각 별도 응답 필드가 아니라 이 URL의 쿼리 파라미터다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=...&redirect_uri=...&scope=openid%20profile_nickname%20profile_image&state=...&nonce=..."
  }
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
3. 재가입은 새 회원 ID를 발급한다. 날짜와 정지 이력, 이전 카카오 연결 해제에 따른 제한은 [member 명세의 재가입 절](./member.md#재가입)을 따른다
4. 두 토큰을 내려준다

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `400` | `COMMON-002` | 필수 필드 누락 |
| `401` | `AUTH-007` | state 불일치·만료·재사용, 카카오 인가 코드나 ID 토큰(nonce 포함) 검증 실패 |
| `403` | `MEMBER-003` | 정지 이력이 있는 탈퇴 회원의 재가입 또는 현재 ACTIVE가 아닌 기존 회원의 로그인. 코드는 `member.md`가 정의한다 |
| `403` | `MEMBER-004` | 탈퇴한 당일의 재가입. 기준은 `member.md`가 정의한다 |
| `409` | `MEMBER-005` | 이전 카카오 연결 해제가 끝나지 않아 재가입을 보류함. 기준은 `member.md`가 정의한다 |
| `429` | 없음 | IP별 인증 요청 제한 초과. 본문 없이 `Retry-After` 제공 |
| `503` | `AUTH-002` | Refresh Token 저장 실패. 서버 캐시 저장 실패만으로는 오류가 나지 않는다 |

### `POST /v1/auth/tokens:refresh`

유효한 `refreshToken` 쿠키로 재발급한다. 기존 Access Token과 요청 본문, 카카오 재인증은 필요 없다.

- 새 Refresh Token도 14일 persistent cookie로 발급한다. 서버 캐시 장애 중에 재발급해도 같다
- 성공하면 `200`과 두 새 인증 쿠키를 반환한다. `data`는 사용자 로그인과 같은 `memberId`, `nickname`, `newMember`, `onboardingCompleted`이며 `newMember`는 `false`다
- 재발급은 직렬화한다. 이미 교체된 Refresh Token의 재사용을 감지하면 사용자의 Refresh Token을 폐기하고 이전 Access Token도 차단한다
- 결과가 불명인 `503 AUTH-002`를 받았을 때 기존 토큰으로 자동 반복 재시도하지 않는다. 다시 로그인한다

**오류**: 관리자 재발급과 같다(`AUTH-002`, `AUTH-003`, `AUTH-004`, `429`).

### `DELETE /v1/auth/tokens`

서비스에서 로그아웃시킨다. 응답은 `204` 이고 두 쿠키를 지운다.

- Refresh Token을 폐기한다. 로그아웃 전에 받은 Access Token은 거부된다
- 카카오 쪽 로그아웃도 요청한다. 카카오 계정 자체를 로그아웃시키지는 않는다

**오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-005` | 로그인 상태가 아님 |
| `403` | `AUTH-006` | MEMBER 역할이 아님 |
| `503` | `AUTH-002` | 필수 토큰 폐기 처리 실패. 삭제 쿠키는 발급하지 않으며 일부 폐기는 이미 반영됐을 수 있음 |

## 접근 제어

**권한은 역할로 판정한다.** 경로 접두어와 역할이 맞지 않으면 `403` 이다.

| 경로 | 허용 역할 |
| --- | --- |
| `/v1/admin/**` | `ADMIN`, `SUPER_ADMIN` (계정 발급은 `SUPER_ADMIN` 만) |
| `/v1/owner/**` | `OWNER` |
| `/v1/**` (위 둘 제외) | `MEMBER` |
| 목록의 공개 7개 | 누구나 |
| 점주 회원가입 | 누구나. 경로와 코드는 점주 도메인의 문서가 소유한다 |

**점주는 업무별로 현재 상태를 확인한다.** 입점 등록 API(약관 동의, 가게 기본 정보, 사업자 검증, 영업시간, 이미지, 메뉴, 최종 등록)는 `ONBOARDING` 점주를 위한 계약이다. 내 정보 조회도 `ONBOARDING` 점주에게 허용한다. 최초 튜토리얼 완료는 DB 현재 상태가 `ACTIVE`인 점주만 허용하며, 그 외 상태는 `403 OWNER-002`다. 나머지 점주 업무 API의 상태 조건은 각 도메인 명세를 따른다.

현재 구현된 입점 API의 경로와 계약은 `store-api-spec.md`에 있다. 업무 API마다 상태 검사를 어디까지 구현했는지는 설계서의 접근 제어 절에 있다.

**이 판정의 오류 코드는 이 문서가 소유하지 않는다.** 점주 상태(`ONBOARDING`, `ACTIVE`,
`SUSPENDED`)는 점주 도메인의 데이터이고, 역할만 아는 인증 모듈은 그 값을 판정할 수 없다.
코드는 점주 도메인의 문서가 정한다.

**접근 제어 오류**

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| `401` | `AUTH-005` | Access Token이 없거나, 만료됐거나, 로그아웃 전에 발급됨 |
| `403` | `AUTH-006` | 역할이 맞지 않음 |

권한이 없으면 리소스 존재 여부와 상관없이 `403`이다. 상태 코드 공통 규약은 [api-spec.md](api-spec.md)를 따른다.

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
