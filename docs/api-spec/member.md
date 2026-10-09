# 사용자와 회원 관리 API

V51 기능 10~12, 62~65, 77~78, 87~91, 94행을 기준으로 한다. 공통 인증과 토큰 계약은 `auth.md`, 저장 위치는 `member(위치 설정 및 관리).md`, 공통 규약은 `README.md`를 따른다.

구현 범위는 아래 "API 목록"의 구현 대상 표이며, 그 밖의 API는 "구현 제외" 절에 이유와 함께 모아 둔다. 구현 제외로 둔 것은 이번 계약에 포함하지 않으며 다시 채택하려면 해당 절의 확정 항목을 먼저 정한다.

## API 목록

### 구현 대상

| API | 권한 | V51 | 설명 |
| --- | --- | --- | --- |
| `PUT /v1/members/me/onboarding` | MEMBER | 63 | 성별, 연령대, 동의 입력 |
| `GET /v1/members/me` | MEMBER | 88 | 내 프로필 조회 |
| `PATCH /v1/members/me` | MEMBER | 87, 90 | 닉네임과 동의 수정 |
| `POST /v1/members/me:withdraw` | MEMBER | 89 | 카카오 재인증 후 탈퇴 |
| `GET`·`POST /webhook/kakao/unlink` | 공개 (카카오 호출) | 89 연계 | 카카오가 통보하는 연결 해제 |
| `GET /v1/admin/members` | ADMIN, SUPER_ADMIN | 11 | 사용자 목록과 검색 |

**2026-10-07 변경:** `GET /v1/admin/owners`를 member 구현 대상에서 제외했다. 점주 목록은 owner 도메인이 소유하고 store 조회 계약을 사용하며, 계약 문서는 `admin.md`가 소유한다.

### 구현 제외

| API | V51 | 비고 |
| --- | --- | --- |
| `PATCH /v1/admin/members/{memberId}/status` | 12 | 권장. 아래 "구현 제외" 절 참고 |
| `PATCH /v1/admin/owners/{ownerId}/status` | 12 | 권장. 아래 "구현 제외" 절 참고 |
| `GET /v1/admin/members/{memberId}` | 11 연계 | 제안 API. 구현하지 않음 |
| `GET /v1/admin/owners` | 10 | 필수 기능이지만 member 구현 범위가 아님. owner 도메인에서 구현하고 `admin.md`가 계약을 소유함 |
| 관심 가게 등록, 해제, 목록 | 77, 78 | 권장. 현재 스키마에 `member_favorite_store`가 없음 |

---

## 온보딩과 프로필

`PUT /v1/members/me/onboarding`은 `gender`(`MALE`, `FEMALE`, `OTHER`), `ageGroup`(`AGE_20S`, `AGE_30S`, `AGE_40S`, `AGE_50_PLUS`), `locationOptIn`, `notificationOptIn`을 모두 받는다. 성별과 연령대가 없으면 완료할 수 없다. 위치 동의를 거부한 회원은 가입할 수 있지만 피드와 지도 이용 제한을 안내받는다. 성별과 연령대는 온보딩 이후 수정하는 API가 없다. `SUSPENDED` 회원도 온보딩할 수 있다.

가입 진행 상태는 별도 회원 상태값이 아니라 `member_profile.onboarding_completed_at`으로 판단하며, 응답은 `onboardingCompleted` 불리언을 제공한다. 미완료 회원은 다음 로그인 뒤 온보딩 화면으로 이동한다.

응답의 `feedAvailable`은 `onboardingCompleted`와 `locationOptIn`이 모두 `true`일 때만 `true`다. 온보딩을 마치지 않았거나 위치정보 수집에 동의하지 않으면 `false`다(V51 63행: 온보딩을 마치지 않으면 피드를 이용할 수 없다).

**2026-10-07 변경:** 온보딩과 내 정보 응답에 `feedAvailable`을 추가하고, `SUSPENDED` 회원의 온보딩을 허용한다고 명시했다.

**2026-10-09 변경:** `feedAvailable`의 정의를 `locationOptIn`과 같은 값에서 `onboardingCompleted && locationOptIn`으로 바로잡았다. V51 63행이 온보딩을 마치지 않으면 피드를 이용할 수 없다고 정하고, 온보딩 전에도 내 정보 수정으로 `locationOptIn`을 바꿀 수 있어서 두 정의가 달라지는 경우가 있었다.

`GET /v1/members/me`과 `PATCH /v1/members/me`은 닉네임, 성별, 연령대, 두 동의 상태와 `onboardingCompleted`를 다룬다. 조회 응답에는 읽기 전용 nullable `profileImageUrl`을 포함한다. 닉네임은 1~20자만 검증하고 허용 패턴은 없다. 닉네임과 프로필 이미지는 카카오에서 최초 가입과 탈퇴 뒤 재가입 때 가져오고, 이후 사용자가 수정할 수 있는 것은 닉네임뿐이다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "memberId": 1,
    "nickname": "점심헌터",
    "profileImageUrl": null,
    "status": "ACTIVE",
    "onboardingCompleted": true,
    "feedAvailable": true,
    "profile": {
      "gender": "FEMALE",
      "ageGroup": "AGE_20S"
    },
    "consents": {
      "notificationOptIn": false,
      "notificationOptedInAt": null,
      "locationOptIn": true,
      "locationOptedInAt": "2026-10-07T09:00:00+09:00"
    },
    "createdAt": "2026-10-01T09:00:00+09:00"
  }
}
```

온보딩 전에는 `profile`이 `null`이다. `profileImageUrl`과 동의 시각은 값이 없으면 `null`이다.

**2026-10-07 변경:** 내 정보 응답의 실제 구조를 `memberId`, `status`, `profile`, `consents`, `createdAt`, `feedAvailable`까지 명시했다.

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `200` | | 성공 |
| `400` | 공통 검증 오류 | 필수 값 누락 또는 허용 값 밖 |
| `400` | `MEMBER-001` | 이미 탈퇴한 회원 |

## 탈퇴

`POST /v1/members/me:withdraw`는 카카오 재인증(`GET /v1/auth/kakao/authorize?reauth=true`, `prompt=login`)에서 받은 `authorizationCode`와 `state`, 선택 `reason`(255자 이하)을 받는다. callback의 state는 재인증을 시작한 탭의 `sessionStorage` 값과 먼저 대조한다. 서버는 카카오 로그인과 같은 `state -> nonce` 저장·검증 로직을 사용하고, 재인증한 카카오 회원번호가 현재 인증된 회원의 `provider_user_id`와 일치하는지 확인한다.

`reason`은 탈퇴 처리의 참고 입력일 뿐 DB나 운영 감사 로그에 저장하지 않으며, 처리 뒤 폐기한다.

```json
{ "authorizationCode": "...", "state": "...", "reason": "선택 입력" }
```

서버는 다음 순서로 처리한다.

1. 재인증 코드를 검증한다.
2. `member.status`를 `WITHDRAWN`으로 바꾸고 `withdrawn_at`을 기록한다. 개인정보는 즉시 비식별 처리하되 행은 남긴다(소프트 삭제).
3. Refresh Token을 폐기하고 Access Token valid-after를 기록하며, `accessToken`과 `refreshToken` 쿠키를 `Max-Age=0`으로 만료한다.
4. **2~3번과 같은 트랜잭션에서 `kakao_unlink_failure`에 해제 대상을 먼저 적재한다.** 이 행의 존재는 "실패"가 아니라 "아직 해제되지 않음"을 뜻한다.
5. 커밋 후 트랜잭션 밖에서 카카오 연결 해제를 호출한다. 성공하면 큐 행을 지우고, 실패해도 탈퇴는 완료로 응답한다. 남은 행은 매일 03시 배치가 재시도한다.

**적재를 호출보다 먼저 하는 이유.** "실패하면 그때 적재"로 두면 탈퇴 커밋과 적재 사이의 모든 중단이 유실이 된다 — 프로세스가 죽거나 적재 자체가 실패하면 회원은 `WITHDRAWN`인데 재시도 큐는 비어 있고, 카카오 연결은 영원히 남는다. 응답이 `204`라 클라이언트도 운영도 알 수 없다. 먼저 적재하면 그 사이에 무엇이 멈춰도 다음 배치가 집어 올린다.

탈퇴 이벤트(`MemberWithdrawnEvent`)는 발행하지 않는다. 발급받은 쿠폰의 즉시 만료(V51 12행)는 쿠폰 도메인이 회원 `status`를 조회해 처리해야 한다.

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `204` | | 탈퇴 완료 |
| `401` | `AUTH-007` | 카카오 재인증 정보가 유효하지 않음(`state` 불일치, 인가 코드 검증 실패 등) |
| `400` | `MEMBER-001` | 이미 탈퇴한 회원 |

### 카카오 연결 해제 재시도

| 항목 | 규약 |
| --- | --- |
| 주기 | 매일 03시(Asia/Seoul). 배치 서버가 두 대라 `batch_execution_log`의 작업 점유(`MEMBER_KAKAO_UNLINK_RETRY`)로 한 서버만 돈다 |
| 대상 | `resolved = FALSE`인 행을 오래된 것부터 최대 100건 |
| 성공 | 큐 행 삭제 |
| 일시적 실패 | `attempt_count` 증가. 한도 5회에 닿으면 `resolved = TRUE`, `stop_reason = EXHAUSTED` |
| 영구 거부 | 카카오가 `400`·`404`로 답한 경우만 `resolved = TRUE`, `stop_reason = REJECTED`. `attempt_count`는 올리지 않는다 |
| 재시도하는 실패 | `401`·`403`(Admin Key 설정 문제 — 고치면 성공할 요청이다), `429`(호출 한도), 5xx, 응답 없음 |
| 서킷 열림 | 카카오에 묻지 못했으므로 `attempt_count`를 올리지 않는다 |
| 재가입 | 대기 행을 삭제한다. 배치는 호출 직전에 행 존재와 `WITHDRAWN`을 다시 확인한다 |

호출 직전 재확인과 실제 호출 사이에 재가입이 끼어드는 창은 외부 호출을 트랜잭션 밖에 두는 한 없앨 수 없다. 닫지 않고 지나간 뒤 탐지해 경보로 남기며, 그 회원은 카카오 재연동 안내 대상이다.

### `GET`·`POST /webhook/kakao/unlink`

카카오가 연결 해제를 통보한다. 서버 대 서버 호출이라 `/v1/` 접두사가 없고 우리 JWT를 쓰지 않는다. 컨트롤러가 `Authorization: KakaoAK {Admin Key}`와 `app_id`를 직접 검증한다.

- 회원이 아직 `WITHDRAWN`이 아니면 탈퇴 처리를 수행한다. 카카오 쪽은 이미 끊겼으므로 **unlink를 다시 호출하지 않는다.**
- 대기 중인 해제 요청 행이 있으면 함께 지운다. 남겨 두면 배치가 끊을 것이 없는 대상을 계속 부른다.
- 검증 실패와 내부 처리 실패 모두 **항상 `200`** 을 돌려주고 로그만 남긴다. 실패를 응답 코드로 드러내면 카카오 재시도를 유발한다.
| `409` | `MEMBER-002` | 이미 온보딩을 완료함 (온보딩 API) |

이메일과 휴대폰은 수신, 수정, 응답하지 않는다. 프로필 사진은 카카오 선택 동의로만 수집하며 직접 업로드나 수정 API는 제공하지 않는다. 선택 동의가 없거나 카카오 응답에 사진이 없으면 `profileImageUrl`은 `null`이다.

---

### 탈퇴 시 데이터 처리

| 데이터 | 처리 | 상태 |
| --- | --- | --- |
| `nickname` | 고정 문구 `탈퇴한 회원`으로 교체 | 확정 |
| 성별, 연령대 | `member_profile` 행 삭제 | 확정. `ck_member_profile_onboarding` 때문에 컬럼만 비울 수 없음 |
| 저장 위치 4컬럼 | `member_profile` 행과 함께 삭제 | 확정(위 처리의 결과) |
| 알림, 위치 동의 | `opt_in=false`, 시각 컬럼 `NULL` | 확정 |
| 토큰 | 모두 폐기 | 확정 |
| `provider_user_id` | DB에 유지, 어떤 응답에도 내리지 않음 | 확정. 재가입 식별, 하루 3개 발급 제한, 정지 이력 판정 기준 |
| `suspended_at` | 유지 | 확정. 정지 이력 재가입 차단 기준 |
| 카카오 회원번호 사본 | `kakao_unlink_failure`에 두었다가 unlink 성공 시 삭제 | 확정 |
| `profile_image_url` | `NULL`로 삭제 | 확정. 탈퇴 뒤 재가입 시 카카오 선택 동의와 응답이 있으면 다시 저장하고, 없으면 `NULL`로 둔다 |
| `last_login_at` | `NULL`로 삭제 | 확정. 탈퇴 회원의 마지막 로그인 시각은 관리자 목록에도 내리지 않는다 |
| `reason` | 저장하지 않고 처리 뒤 폐기 | 확정 |

**2026-10-07 변경:** 탈퇴 회원의 닉네임 문구를 `탈퇴한 회원`으로 확정했다.

### 재가입

같은 `provider_user_id`로 다시 가입하면 새 행을 만들지 않고 기존 행을 재활성화한다. 재가입 시 카카오에서 받은 닉네임과 프로필 이미지를 다시 반영한다. `suspended_at`이 있으면(정지 중 탈퇴든 정지 해제 뒤 탈퇴든) `403 MEMBER-003`으로 거부한다. 재가입 회원은 온보딩을 다시 받는다.

**2026-10-07 변경:** 재가입 시 닉네임과 프로필 이미지를 카카오 응답으로 다시 반영하는 것으로 확정했다.

---

## 관리자 사용자 목록

```
GET /v1/admin/members?status=ACTIVE&joinedFrom=2026-09-01&joinedTo=2026-09-30&searchType=NICKNAME&keyword=홍&page=0&size=20&sortBy=joinedAt&sortDir=desc
```

| 파라미터 | 필수 | 설명 |
| --- | --- | --- |
| `status` | 아니오 | `ACTIVE`, `SUSPENDED`, `WITHDRAWN` |
| `joinedFrom`, `joinedTo` | 아니오 | 가입일(`member.created_at`) 범위. 양끝 포함, `yyyy-MM-dd`, KST 날짜 기준 |
| `searchType` | 아니오 | `MEMBER_ID`(일치) / `NICKNAME`(접두사 일치). `keyword`를 주면 필수이고 없으면 `400` |
| `keyword` | 아니오 | `searchType`에 맞는 검색어. `NICKNAME`은 `%`·`_`를 이스케이프하는 공통 LIKE 모듈을 쓴다. `MEMBER_ID`에 숫자가 아닌 값이면 `400` |
| `page`, `size` | 아니오 | `page`는 0부터(기본 0). `size`는 기본 20, 최대 100 |
| `sortBy`, `sortDir` | 아니오 | `joinedAt`(기본)·`lastLoginAt`·`nickname`·`memberId` / `desc`(기본)·`asc`. 값이 같으면 `memberId`가 `sortDir`과 같은 방향. `lastLoginAt`이 `null`인 행은 방향과 관계없이 맨 뒤 |

**2026-10-07 변경:** 닉네임 검색을 인덱스를 사용할 수 없는 완전 부분 일치에서 접두사 일치로 변경했다.

**2026-10-08 변경:** 검색 대상을 `keyword` 모양으로 추측하지 않고 `searchType`으로 명시한다(숫자 닉네임이 `memberId`로 잘못 검색되는 문제 제거). 페이지 크기 20 고정을 풀고 `size`·정렬 옵션을 추가했다. 이전 `keyword` 단독 호출은 `400`이다. 사용자 계정 정지 API가 구현 제외라 `SUSPENDED`는 현재 해당 행이 없다. 필터는 정지 기능 도입을 대비해 남긴다.

```json
{
  "code": "SUCCESS",
  "message": "...",
  "data": {
    "items": [
      { "memberId": 1, "nickname": "홍길동", "status": "ACTIVE",
        "joinedAt": "2026-09-30T09:00:00+09:00", "lastLoginAt": "2026-10-01T09:55:00+09:00" }
    ],
    "page": 0, "size": 20, "totalElements": 1
  }
}
```

- 필수 데이터는 회원 ID, 닉네임, 상태, 가입일이고 `lastLoginAt`은 선택 데이터다. 로그인 성공 때마다 갱신하며, 기록이 없으면 `null`이다.
- 성별, 연령대, 동의 상태, `provider_user_id`는 목록 응답에 내리지 않는다.
- 탈퇴 회원은 서버가 탈퇴 시점에 비식별 처리한 값(닉네임 `탈퇴한 회원`)을 그대로 표시하고 `lastLoginAt`은 `null`이다. 조회 시점에 별도로 마스킹하지 않는다.
- 조건에 맞는 회원이 없으면 `items`는 빈 배열이다.

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `200` | | 조회 성공 |
| `400` | 공통 검증 오류 **[2026-10-08 조건 추가]** | 날짜 형식 오류, `joinedFrom`이 `joinedTo`보다 늦음, `page`가 음수, `size`가 1~100 밖, 허용되지 않는 `status`·`searchType`·`sortBy`·`sortDir`, `searchType` 없는 `keyword` |
| `401` | | 인증되지 않음 |
| `403` | | 관리자 역할이 아님 |

---

## 관리자 점주 목록 (member 구현 제외)

**2026-10-07 변경:** 아래 내용은 참고용 기존 계약으로 남기되 member 도메인에서 구현하지 않는다. owner 도메인이 store 조회 계약으로 가게 정보를 조합하고, 최종 계약은 `admin.md`로 옮긴다.

```
GET /v1/admin/owners?status=ACTIVE&joinedFrom=2026-09-01&joinedTo=2026-09-30&keyword=런치&page=0
```

사용자 목록과 같은 규약이며 응답 항목과 검색 대상만 다르다. 점주 1계정은 가게 1개이므로 한 줄이 점주와 가게를 함께 나타낸다.

| 파라미터 | 필수 | 설명 |
| --- | --- | --- |
| `status` | 아니오 | `ONBOARDING`, `ACTIVE`, `SUSPENDED`, `WITHDRAWN`. V51은 활성, 정지, 탈퇴만 적었으나 점주 상태 정의에 `ONBOARDING`이 있어 함께 받는다 |
| `joinedFrom`, `joinedTo` | 아니오 | 가입일(`owner.created_at`) 범위. 사용자 목록과 같은 규칙 |
| `keyword` | 아니오 | 숫자만이면 `ownerId` 일치, 아니면 상호 부분 일치 (제안) |
| `page` | 아니오 | 기본 0. 페이지당 20건 고정. 정렬은 가입일 최신순이고 같으면 `ownerId` 내림차순 |

```json
{
  "code": "SUCCESS",
  "message": "...",
  "data": {
    "items": [
      { "ownerId": 7, "storeName": "런치식당", "status": "ACTIVE", "storeRegistered": true,
        "businessNo": "123-45-*****", "joinedAt": "2026-09-20T10:00:00+09:00", "lastLoginAt": "2026-10-01T09:00:00+09:00" }
    ],
    "page": 0, "size": 20, "totalElements": 1
  }
}
```

| 필드 | 필수 데이터 | 출처 |
| --- | --- | --- |
| `ownerId` | 예 | `owner.owner_id` |
| `storeName` | 예 | 가게 도메인의 `store.name`. 가게를 만들기 전 점주는 `null` |
| `status` | 예 | `owner.status` |
| `joinedAt` | 예 | `owner.created_at` |
| `storeRegistered` | 예 (가게 최종 등록 여부) | `store.finalized`. 가게가 없으면 `false` |
| `businessNo` | 선택 | `store.business_registration_number`를 부분 마스킹. 없으면 `null` |
| `lastLoginAt` | 선택 | `owner.last_login_at` |

- 목록은 점주 계정이 기준이다. 상호와 최종 등록 여부, 사업자등록번호는 가게 도메인 데이터라 점주 도메인이 가게 도메인의 조회 기능을 호출해 채운다. 목록 API는 점주 도메인 패키지에 둔다.
- 이메일, 대표자 성명, 비밀번호 관련 값은 응답에 내리지 않는다.
- 상호로 검색하려면 가게 도메인이 상호 검색 결과의 점주 ID 목록을 돌려주는 조회가 필요하다. 1만 건 기준 1초 이내를 지키려면 목록 한 페이지의 점주 ID를 묶어 한 번에 조회한다.
- 조건에 맞는 점주가 없으면 `items`는 빈 배열이다.

### 탈퇴 점주 표시 (제안)

V51 10행은 "탈퇴한 점주는 개인정보를 마스킹한 뒤 목록에 표시"라고만 하고 대상 필드를 정하지 않았다. 사용자와 달리 점주 탈퇴 시점에 비식별 처리하는 절차가 아직 없으므로, 목록 응답을 만들 때 `status`가 `WITHDRAWN`이면 서버가 다음처럼 가린다.

| 필드 | 일반 점주 | 탈퇴 점주 |
| --- | --- | --- |
| `storeName` | 그대로 | 그대로 (사업체 정보이자 V51 필수 표시 항목) |
| `businessNo` | 부분 마스킹 (`123-45-*****`) | 전체 마스킹 (`***-**-*****`) |
| `lastLoginAt` | 그대로 | `null` |

| 응답 | 코드 | 언제 |
| --- | --- | --- |
| `200` | | 조회 성공 |
| `400` | 공통 검증 오류 | 날짜 형식 오류, 기간 역전, `page`가 음수, 허용되지 않는 `status` |
| `401` | | 인증되지 않음 |
| `403` | | 관리자 역할이 아님 |

---

## 구현 제외

### 회원과 점주 상태 변경 (V51 12행, 권장)

권장 우선순위여서 이번 구현 범위에 넣지 않는다. 정지는 `Member.suspend()`, `releaseSuspension()` 엔티티 메서드와 `member` 컬럼까지만 두고 API, 정지 감사 로그, `MemberWithdrawnEvent`는 만들지 않는다. 정지 기간 만료는 스케줄러 없이 로그인 시점에 `ACTIVE`로 복귀시키며 영구 정지는 `2099-12-31`로 둔다. 정지 사유와 기간은 사용자에게 보이지 않는다.

점주 정지 시 캠페인을 PAUSED로 바꾸는 처리는 12행에 있으나 도메인 정의는 점주 쪽이 다른 도메인을 호출하지 않는다고 하여 서로 맞지 않는다. 이 항목은 상태 변경 API를 다시 채택할 때 함께 정한다.

### 사용자 상세 조회

정지와 강제 탈퇴 판단용으로 제안했던 `GET /v1/admin/members/{memberId}`는 구현하지 않는다.

### 관심 가게 (77, 78행)

현재 스키마에 `member_favorite_store`가 없어 이번 계약에 포함하지 않는다. 다시 채택할 경우 테이블, 멱등 등록과 해제, 이벤트 유형을 함께 확정한다.

---

## 데이터 보존과 삭제

| 구분 | 데이터 | 기준 |
| --- | --- | --- |
| 영구 보관 | 운영 감사 로그(`audit_log`) | 삭제하지 않음 |
| 탈퇴 후에도 유지 | `member` 행, `provider_user_id`, `suspended_at`, `withdrawn_at` | 소프트 삭제. 파기·정리 배치는 구현하지 않고 행을 계속 유지한다(2026-10-06 결정) |
| 기간 유지 후 삭제 | `event_log`, `serve_log`, `impression_log` | 13개월, 야간 배치 삭제 |
| 기간 유지 후 삭제 | `swipe_log` | 31일 |
| 기간 유지 후 삭제 | `feed_visit_daily` | 8일, 좌표는 소수점 3자리 반올림 |
| 기간 유지 후 삭제 | 피드 서빙 캐시 | 30분 |
| 사용자가 삭제할 때까지 | 저장 위치 | 탈퇴하면 `member_profile`과 함께 삭제 |
| 탈퇴 시 삭제 | `member_profile`, 토큰, 카카오 회원번호 사본(unlink 성공 후) | 위 "탈퇴 시 데이터 처리" 참고 |
| 규칙 없음 | 정산 원장, 결제, 환불 | 삭제 규칙과 법정 보존 기간이 정해지지 않음 |

---

# 오류 코드

`MEMBER-` 코드를 이 문서가 소유한다. 카카오 인증 실패는 `AUTH-007`(`auth.md`)을 쓰며 회원 코드를 따로 만들지 않는다.

| 코드 | 상태 | 언제 | 메시지 |
| --- | --- | --- | --- |
| `MEMBER-001` | `400` | 이미 탈퇴한 회원 | 이미 탈퇴한 회원입니다. |
| `MEMBER-002` | `409` | 이미 온보딩을 완료함 | 이미 온보딩을 완료한 회원입니다. |
| `MEMBER-003` | `403` | 정지 이력이 있는 회원번호의 재가입 | 정지 이력이 있는 회원은 재가입할 수 없습니다. |

---

# V51과 달라진 점

V51 > 레포 마이그레이션 > 문서 순서(`README.md`)를 따르되, 아래는 V51 행과 다르게 정한 것이다. 2026-10-06에 기록한다.

| V51 행 | V51 내용 | 이 문서의 결정 | 근거 |
| --- | --- | --- | --- |
| 62, 87 | 닉네임과 프로필 이미지는 최초 가입 시에만 동기화 | 탈퇴 뒤 재가입 때도 카카오에서 다시 가져온다 | 탈퇴 때 닉네임을 `탈퇴한 회원`으로, 프로필 이미지를 `NULL`로 비우기 때문이다 |
| 87 | 프로필 사진을 수정한다 | 프로필 사진 수정 API를 두지 않는다. 수정은 닉네임만 | 사진은 카카오 선택 동의로만 수집한다 |
| 87 | 닉네임이 허용 패턴을 통과하지 못하면 수정 실패 | 1~20자만 검증하고 허용 패턴은 없다 | 패턴 정의가 V51에 없다 |
| 88 | 닉네임, 이메일, 휴대폰, 프로필 이미지를 표시 | 이메일과 휴대폰은 수신, 저장, 응답하지 않는다 | 카카오에서 받지 않고 스키마에 컬럼이 없다 |
| 89 | 탈퇴 사유를 선택 데이터로 받음 | 받되 저장하지 않고 처리 뒤 폐기한다 | 탈퇴 처리의 참고 입력일 뿐이다 |
| 89 | 법정 기간 데이터 보존 | 보존 기간과 대상은 미정이며 파기 배치를 구현하지 않는다 | V51에 수치가 없다. 위 "아직 정해지지 않은 것" 참고 |
| 12 | 회원과 점주 상태 변경(권장) | 구현 제외. 정지 만료는 로그인 시점에 처리하고 영구 정지는 `2099-12-31` | 위 "구현 제외" 참고 |
| 77, 78 | 관심 가게(권장) | 구현 제외 | `member_favorite_store` 스키마가 없다 |
| 11 | 사용자 목록: 20건 단위 페이지, 키워드 검색 | `size`·정렬 옵션 지원, `searchType`으로 검색 대상 명시, 닉네임은 접두사 일치 (2026-10-08) | 프론트가 크기를 정하고, 인덱스를 쓸 수 있어야 한다 |
| 93 | 저장 위치 조회, 수정 | 대표 위치 1개의 설정, 조회, 삭제로 제공한다. 위치 목록은 없다 | `member(위치 설정 및 관리).md` 참고. V51 66행의 "사용자가 삭제할 때까지 보관"에 맞춰 삭제를 더했다 |

---

# 아직 정해지지 않은 것

| 항목 | 현재 기준 |
| --- | --- |
| 탈퇴 회원 행의 최종 파기 시점과 기간 | **정리 배치는 일단 구현하지 않는다(2026-10-06 결정).** 운영 배치에 "탈퇴자 보존 기간 경과 시 파기"가 있으나 기간 수치가 없고, 확정될 때 다시 정한다 |
| 파기 시 유지할 식별자 | 파기 배치를 구현하지 않으므로 지금은 해당 없음. 파기를 도입할 때 행을 지우면 재가입 차단과 하루 3개 발급 제한이 풀리므로 `provider_user_id`(또는 해시)와 `suspended_at` 유지 여부를 함께 정한다 |
| 법정 보존 기간과 대상 | V51 89행이 "법정 기간 데이터 보존"만 적었고 수치와 대상 데이터가 없음 |
| 점주 증빙서류 파기 기한 | V51 법적 요건에 "탈퇴 시 파기 기한 명시"가 있으나 값이 없음 |
| 탈퇴 점주 마스킹 대상 | 위 표는 제안이며 `storeName`을 그대로 둘지 확정 필요 |
| 점주가 `WITHDRAWN`이 되는 경로 | 점주 본인 탈퇴 API와 상태 변경 API가 모두 없음. 탈퇴 시 점주 이메일, 대표자 성명, 사업자등록번호 처리도 미정 |
| 관리자 목록의 탈퇴 시각 표시 | 목록 응답에 `withdrawnAt`이 없음. 필요하면 추가 |
| 탈퇴 회원 쿠폰 즉시 만료 | 이벤트를 만들지 않으므로 쿠폰 도메인과 처리 방식 합의 필요 |
| 점주 목록 `keyword`의 상호 검색 | 가게 도메인에 상호 검색 조회가 필요하며 제공 방식 미정 |
| 로그 보존 기간 | 레포에 설정이 없고 인프라 수집 시스템에서 정해야 함 |
