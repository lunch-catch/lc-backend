# 점주 계정 API

공통 규약은 [api-spec.md](api-spec.md)를 따른다.

## 내 정보 조회

`GET /v1/owner/me`

새로고침이나 재접속 시 로그인한 점주의 이메일, 상태와 튜토리얼 확인 여부를 조회한다.
요청 본문은 없으며 `accessToken` 쿠키로 인증한다. OWNER 역할만 접근할 수 있고 ONBOARDING 점주도 조회할 수 있다.

성공 시 `200`과 공통 응답 봉투를 반환한다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "email": "owner@example.com", "status": "ONBOARDING", "tutorialViewed": false }
}
```

| 필드 | 타입 | 필수 | null 가능 | 설명 |
|---|---|---|---|---|
| email | string | 예 | 아니오 | 로그인한 점주의 이메일 |
| status | string | 예 | 아니오 | DB의 현재 점주 상태: ONBOARDING, ACTIVE, SUSPENDED, WITHDRAWN |
| tutorialViewed | boolean | 예 | 아니오 | DB에 저장된 튜토리얼 확인 여부 |

| HTTP | 코드 | 조건 |
|---|---|---|
| 401 | AUTH-005 | 인증 쿠키 누락, 유효하지 않거나 만료·무효화된 Access Token, 점주 계정 없음 |
| 403 | AUTH-006 | 점주가 아닌 역할 |

조회는 점주 상태나 튜토리얼 확인 여부를 변경하지 않는다. 토큰 재발급과 쿠키 갱신도 수행하지 않는다.
Access Token 만료 시 프론트는 기존 재발급 API를 사용한 뒤 조회를 다시 요청한다.

## 입점용 가게 검색

`GET /v1/owner/kakao-places`의 요청, 응답과 오류는 [가게 API 명세](store-api-spec.md)의 카카오맵 가게 검색 절을 따른다.
