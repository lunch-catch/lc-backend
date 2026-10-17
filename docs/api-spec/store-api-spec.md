# 가게 API

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

## 가게 등록 온보딩 2단계: 기본 정보와 사업자 Mock 검증

### `PUT /v1/owner/store/basic`

인증된 OWNER 중 DB의 현재 상태가 ONBOARDING인 점주가 사용한다.
약관 동의 단계인 TERMS가 완료되어 STORE_INFO에 진입한 뒤 호출한다.
이 API는 가게 초안 생성과 사업자등록번호 Mock 검증을 한 요청으로 처리한다.
완료한 STORE_INFO를 다시 저장할 때도 같은 API에 해당 단계의 전체 입력값을 보낸다.

```json
{
  "version": 1,
  "name": "런치캐치 식당",
  "categoryCode": "KOREAN",
  "representativeName": "홍길동",
  "businessRegistrationNumber": "1234567890",
  "phone": "02-123-4567"
}
```

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| version | integer(int64) | 예 | 현재 진행 상태의 버전이며 0 이상이다 |
| name | string | 예 | 상호명이며 공백만 있는 값을 제외한 1~100자다 |
| categoryCode | string | 예 | 위 카테고리 코드 중 하나이며 ALL은 허용하지 않는다 |
| representativeName | string | 예 | 대표자 성명이며 공백만 있는 값을 제외한 1~100자다 |
| businessRegistrationNumber | string | 예 | 하이픈 없는 ASCII 숫자 10자리다 |
| phone | string | 예 | 숫자 9~12자리 또는 `02-123-4567`, `010-1234-5678`처럼 `2~4자리-3~4자리-4자리` 형식이다 |

모든 필드는 누락·null을 허용하지 않으며 문자열의 빈 값·공백만 있는 값도 거부한다.
전화번호·사업자등록번호의 공백이나 구분 기호를 서버가 자동으로 제거하지 않는다.
위치·영업시간·이미지·메뉴 정보는 이 요청에 포함하지 않는다.

### 응답 `200`

최초 저장 성공 예시다. 식별자는 공통 규약의 int64 숫자를 따른다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "storeId": 501,
    "resumeStep": "LOCATION",
    "version": 2,
    "verificationStatus": "VERIFIED"
  }
}
```

| 필드 | 타입 | null 허용 | 의미 |
|---|---|---|---|
| storeId | integer(int64) | 예 | 최초 성공 때 생성한 본인 소유 초안의 ID다. 최초 검증 실패 때만 null이다 |
| resumeStep | string | 아니오 | 저장 후 재개할 단계다. 최초 성공이면 LOCATION이고 완료 단계 수정이면 기존 단계다 |
| version | integer(int64) | 아니오 | 다음 요청에 사용할 버전이다. 저장 성공마다 1 증가하고 Mock 실패 때는 유지한다 |
| verificationStatus | string | 아니오 | 이번 요청의 Mock 결과인 VERIFIED 또는 FAILED다 |

Mock은 외부 기관을 호출하지 않는다. 형식이 유효한 번호 중 `0000000000`은 FAILED이며 나머지는 VERIFIED다.
실제 사업자의 존재·영업 상태나 사업자번호 체크섬을 검증하는 기능이 아니다.
검증 실패도 정상 처리된 업무 결과이므로 `200`, `code: SUCCESS`, `verificationStatus: FAILED`를 반환한다.
프론트는 HTTP 성공만으로 다음 단계로 이동하지 않고 verificationStatus도 확인해야 한다.

최초 실패 응답의 data는 다음과 같다.

```json
{
  "storeId": null,
  "resumeStep": "STORE_INFO",
  "version": 1,
  "verificationStatus": "FAILED"
}
```

실패한 입력값과 초안은 저장하지 않는다. 완료 단계 수정 중 재검증이 실패하면 기존 가게 ID·저장값·검증 성공 시각·재개 단계·버전이 유지된다.
응답의 FAILED는 이번 요청 결과이며 기존 가게의 검증 상태를 FAILED로 덮어쓰지 않는다.
최신 버전으로 재저장하면 같은 가게를 수정하며 이후 단계의 입력값은 보존한다.
가게 최종 등록과 ACTIVE 전환은 수행하지 않는다.

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | 공통 입력·파싱 오류 코드 | 필수값 누락, 형식·길이 오류, 잘못된 JSON |
| 401 | AUTH-005 | 인증이 없거나 점주 계정이 존재하지 않음 |
| 403 | AUTH-006 | OWNER 역할이 아님 |
| 403 | OWNER-002 | DB의 현재 점주 상태가 ONBOARDING이 아님 |
| 409 | STORE-001 | 요청 버전이 현재 진행 상태와 다름 |
| 409 | STORE-002 | 아직 TERMS 단계임 |
| 409 | STORE-003 | 진행 상태가 초기화되지 않음 |
| 403 | STORE-004 | 진행 상태와 본인 가게의 연결이 일치하지 않거나 최종 등록된 가게임 |

버전 충돌 시 입력값을 자동 재전송하지 않고 최신 진행 상태를 확인해야 한다.
약관 API와 진행 상태 최초 생성은 선행 작업이며 이 API가 약관 단계를 자동 완료하거나 진행 상태를 생성하지 않는다.
