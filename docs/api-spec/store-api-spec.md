# 가게 목록 조회 및 검색

공통 인증·응답·오류 규약은 [api-spec.md](api-spec.md)를 따른다.

## `GET /v1/stores`

사용자가 현재 위치를 기준으로 입점 가게를 가까운 순서로 조회한다.
`query`를 전달하면 가게명 또는 대표 메뉴명에 검색어가 포함된 가게를 조회한다.
리스트 뷰와 지도 뷰는 같은 API를 사용한다.

### 인증

`MEMBER`가 사용하는 사용자용 API다.

### 요청

검색어 없이 전체 카테고리에서 조회하는 예시:

```http
GET /v1/stores?longitude=127.1111967&latitude=37.3947766&filter=ALL&pageSize=20
```

검색어 없이 한식과 일식을 함께 선택하여 조회하는 예시:

```http
GET /v1/stores?longitude=127.1111967&latitude=37.3947766&filter=KOREAN&filter=JAPANESE&pageSize=20
```

검색어로 전체 카테고리에서 검색하는 예시:

```http
GET /v1/stores?query=국수&longitude=127.1111967&latitude=37.3947766&filter=ALL&pageSize=20
```

검색어와 함께 한식과 일식을 선택하여 검색하는 예시:

```http
GET /v1/stores?query=국수&longitude=127.1111967&latitude=37.3947766&filter=KOREAN&filter=JAPANESE&pageSize=20
```

카테고리를 여러 개 선택하면 `filter`를 같은 이름으로 반복하여 전달한다.
마지막 예시는 한식 또는 일식 가게 중 가게명이나 대표 메뉴명에 `국수`가 포함된 가게를 검색한다.

| 파라미터 | 필수 | 뜻 |
| --- | --- | --- |
| `query` | 아니오 | 생략하면 검색어 조건 없이 조회한다. 전달하면 가게명 또는 대표 메뉴명에 검색어가 포함된 가게를 조회한다. 빈 문자열과 공백만 있는 값은 허용하지 않는다 |
| `longitude` | 예 | 현재 위치의 경도. `-180~180` |
| `latitude` | 예 | 현재 위치의 위도. `-90~90` |
| `filter` | 예 | 카테고리 코드를 하나 이상 전달한다. 여러 개면 같은 파라미터를 반복하고 선택한 카테고리가 없으면 `ALL`을 전달한다. [카테고리 필터](#카테고리-필터)를 따른다 |
| `pageSize` | 아니오 | 한 번에 받을 개수. 기본 `20`, `1~100` |
| `pageToken` | 아니오 | 서버가 반환한 다음 페이지 커서. 없으면 첫 페이지부터 조회 |

### 조회 기준

- 최종 등록을 완료한 가게를 대상으로 조회한다.
- `query`를 전달하면 가게명 또는 대표 메뉴명에 검색어가 포함된 가게를 조회한다.
- `filter=ALL`이면 모든 카테고리에서 조회한다. 카테고리 코드를 선택하면 그중 하나에 속하는 가게를 조회한다.
- 조회 반경을 제한하지 않는다. 검색어 유무와 관계없이 조건에 맞는 전체 가게를 거리순으로 조회한다.
- [정렬과 커서 페이지네이션](#정렬과-커서-페이지네이션)을 적용한다.
- 캠페인 표시에는 [캠페인 표시 규칙](#캠페인-표시-규칙)을 적용한다.
- 다음 페이지 요청에서도 검색어, 필터, 좌표를 동일하게 유지한다.

### 오류

| 상태 | 언제 |
| --- | --- |
| `400` | 필수 좌표 또는 필터 누락, 빈 검색어, 빈 필터, 허용하지 않는 카테고리, `ALL`과 개별 카테고리 혼용 또는 좌표, `pageSize`, `pageToken`이 올바르지 않음 |
| `401` | 유효한 사용자 인증이 없음 |
| `403` | 인증된 사용자가 `MEMBER` 권한이 아님 |

## 카테고리 필터

`filter`는 필수 쿼리 파라미터이며 카테고리 코드의 배열로 받는다.
여러 카테고리는 같은 파라미터 이름을 반복하여 전달한다.

```text
filter=KOREAN&filter=CHINESE
```

여러 카테고리를 선택하면 그중 하나에 속하는 가게를 포함한다(OR 조건).
선택한 카테고리가 없으면 클라이언트는 `filter=ALL`을 전달한다.
`filter` 누락이나 빈 값은 `ALL`로 대체하지 않고 `400`으로 거부한다.
`ALL`은 다른 카테고리와 함께 전달하지 않는다.
`ALL`은 전체 카테고리를 뜻한다.

요청에서 사용할 수 있는 값은 다음과 같다.

```text
ALL
KOREAN
CHINESE
JAPANESE
WESTERN
BUNSIK
ASIAN
FAST_FOOD
CAFE_DESSERT
OTHER
```

## 정렬과 커서 페이지네이션

요청 좌표에서 가게까지의 반올림 전 직선거리 오름차순으로 정렬한다.
같은 거리에서는 `storeId` 오름차순으로 정렬한다.
페이지를 넘기면 같은 조건을 만족하는 더 먼 가게가 이어서 반환된다.
활성 캠페인 여부는 정렬 순서에 영향을 주지 않는다.
정렬은 고정이므로 `sort` 요청 파라미터를 제공하지 않는다.

목록은 커서 방식으로 페이지를 나눈다.
`pageToken`이 없으면 첫 페이지를 반환한다.
다음 페이지는 서버가 반환한 `nextPageToken`을 `pageToken`으로 전달한다.
마지막 페이지의 `nextPageToken`은 `null`이다.

클라이언트는 서버가 반환한 커서를 해석하거나 수정하지 않고 그대로 전달한다.
검색어나 필터 또는 현재 위치가 바뀌면 `pageToken`을 비우고 첫 페이지부터 요청한다.
필터 값의 전달 순서는 선택한 카테고리 집합의 의미를 바꾸지 않는다.

## 캠페인 표시 규칙

- 활성 캠페인 여부와 할인 정보는 당일 활성 캠페인을 기준으로 반환한다.
- 활성 캠페인 표시는 요청 위치와 관계없이 당일 캠페인 상태를 기준으로 판단한다.
- 활성 캠페인이 없는 가게는 `hasActiveCampaign=false`, `activeCampaign=null`로 반환한다.
- `discountTargetType=MENU`이면 `targetMenu`에 할인 대상 메뉴 정보를 반환한다.
- `discountTargetType=ALL`이면 `targetMenu`는 `null`이다.

## 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "items": [
      {
        "storeId": 10,
        "name": "런치식당",
        "businessCategory": "KOREAN",
        "longitude": 127.1135000,
        "latitude": 37.3960000,
        "distanceMeters": 245,
        "hasActiveCampaign": true,
        "activeCampaign": {
          "discountTargetType": "MENU",
          "discountType": "PERCENT",
          "discountValue": 20,
          "targetMenu": {
            "menuId": 701,
            "name": "숯불갈비 정식",
            "price": 12000
          }
        }
      }
    ],
    "nextPageToken": null
  }
}
```

아래 필드는 성공 응답의 `data` 안에 있다.
상위 객체가 존재하면 표의 모든 필드를 반환하며 `null`인 필드도 생략하지 않는다.
표의 null 허용 여부는 상위 객체가 존재할 때를 기준으로 한다.

| 필드 | 타입 | null 허용 | 뜻 |
| --- | --- | --- | --- |
| `items` | array<object> | 아니오 | 조회된 가게 목록을 반환한다. 결과가 없으면 빈 배열이다 |
| `nextPageToken` | string | 예 | 다음 페이지 커서를 반환한다. 마지막 페이지면 `null`이다 |
| `storeId` | integer | 아니오 | 가게 ID를 반환한다 |
| `name` | string | 아니오 | 가게명을 반환한다 |
| `businessCategory` | string(enum) | 아니오 | 가게의 업종 코드를 반환한다. [카테고리 필터](#카테고리-필터)에 나열한 코드 중 `ALL`을 제외한 값을 사용한다 |
| `longitude` | number | 아니오 | 가게 경도를 반환한다 |
| `latitude` | number | 아니오 | 가게 위도를 반환한다 |
| `distanceMeters` | integer | 아니오 | 요청 좌표에서 가게까지의 직선거리를 미터 단위로 반올림한 정수로 반환한다. 소수 부분이 0.5 이상이면 올림한다 |
| `hasActiveCampaign` | boolean | 아니오 | 당일 활성 캠페인이 있는지 반환한다 |
| `activeCampaign` | object | 예 | 당일 활성 캠페인의 요약을 반환한다. 없으면 `null`이다 |
| `discountTargetType` | string(enum) | 아니오 | 할인 대상 유형으로 `ALL` 또는 `MENU`를 반환한다 |
| `discountType` | string(enum) | 아니오 | 할인 유형으로 `PERCENT` 또는 `AMOUNT`를 반환한다 |
| `discountValue` | integer | 아니오 | `PERCENT`이면 할인율(%), `AMOUNT`이면 할인액(원)을 반환한다 |
| `targetMenu` | object | 예 | `MENU`이면 할인 대상 메뉴를 반환한다. `ALL`이면 `null`이다 |
| `menuId` | integer | 아니오 | 할인 대상 메뉴 ID를 반환한다 |
| `name` | string | 아니오 | 할인 대상 메뉴명을 반환한다 |
| `price` | integer | 아니오 | 할인 전 메뉴 가격을 원 단위로 반환한다 |

전체 건수는 반환하지 않는다.

`distanceMeters`는 `310.4m`이면 `310`, `310.5m`이면 `311`로 반환한다.
정렬에는 반올림 전 거리를 사용한다.


## 점주 약관 동의

`PUT /v1/owner/terms-agreements`

인증된 OWNER의 DB 현재 상태가 ONBOARDING이고 진행 단계가 TERMS일 때 전체 필수 약관에 대한 최초 동의만 저장한다.
이 API 호출 자체가 전체 필수 약관 동의를 의미한다. 미동의 상태에서는 호출하지 않는다.
가게 생성 전에도 점주 ID만으로 저장할 수 있으며 점주 ID는 인증 주체에서 가져온다.

### 요청

요청 본문 없이 호출한다. 빈 JSON 객체 `{}`도 허용하지만 version을 포함한 입력 필드는 받지 않는다.
약관별 종류·버전·필수 여부·동의 여부와 클라이언트 동의 시각은 받지 않는다.
서버가 required=true와 agreed=true를 설정하고 저장 처리 시각을 agreed_at에 기록한다. 점주당 한 행을 유지한다.
기존 동의 기록이 있으면 409 TERMS-001로 거부하며 agreed_at·withdrawn_at·진행 상태를 변경하지 않는다. ACTIVE 상태에서도 저장할 수 없다. 수정·재동의·철회 API는 제공하지 않는다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {"requiredTermsCompleted": true, "version": 1, "resumeStep": "STORE_INFO"}
}
```

최초 성공은 TERMS에서 STORE_INFO로 진행한다. 완료한 약관 단계로 돌아가 수정할 수 없다.
최초 저장 성공 시 서버가 잠근 진행 상태의 현재 버전을 사용해 단계를 완료하고 버전을 1 증가시킨다. 응답 version은 다음 STORE_INFO 저장 요청에서 사용할 값이다.
약관 내역과 진행 상태는 함께 커밋하거나 롤백한다. 점주 상태·튜토리얼·토큰·인증 쿠키는 변경하지 않는다.
약관 정책 설정은 필요하지 않으며 정책 미설정 503과 필수 약관별 검증 422는 사용하지 않는다.

### 오류

| HTTP 상태 | 코드 | 조건 |
|---|---|---|
| 400 | COMMON-003 | 잘못된 JSON 또는 제거된 약관별 입력 등 지원하지 않는 필드 |
| 401 | AUTH-005 | 인증 실패 |
| 403 | AUTH-006 | OWNER 역할이 아님 |
| 403 | OWNER-002 | 점주의 DB 현재 상태가 ONBOARDING이 아님 |
| 409 | STORE-002 | 약관 기록이 없더라도 현재 진행 단계가 TERMS가 아님 |
| 409 | TERMS-001 | 기존 약관 동의가 있거나 약관 동의 행의 DB UNIQUE 제약 충돌 |
| 500 | COMMON-001 | 서버 처리 실패 |

오류 응답은 공통 응답 봉투를 따른다.
