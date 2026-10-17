# 가게 (store) API 명세

공통 인증·응답·오류 규약은 [api-spec.md](api-spec.md)를 따른다. 서버 내부 처리와 근거는
[store-api-design.md](../design/store/store-api-design.md)를 참고한다.

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

## `GET /v1/owner/store-registration-progress`

점주가 지금 자신의 가게 등록에서 이어서 진행할 단계와, 다음 완료 요청에 실어 보낼
버전을 조회한다. 아직 진행 상태가 없으면 만들어서 돌려준다(`TERMS`, 버전 0).

### 인증

`OWNER`가 사용하며 `ONBOARDING` 상태에서만 호출할 수 있다.

### 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "storeId": 10,
    "resumeStep": "IMAGES",
    "version": 5
  }
}
```

| 필드 | 타입 | null 허용 | 설명 |
|---|---|---|---|
| storeId | integer | 예 | 기본 정보 단계(`STORE`)를 아직 안 마쳤으면 `null` |
| resumeStep | string(enum) | 아니오 | 이어서 진행할 단계. `TERMS`, `STORE`, `BUSINESS_VERIFICATION`, `LOCATION`, `HOURS`, `IMAGES`, `MENU` 중 하나 |
| version | integer | 아니오 | 다음 완료·수정 요청에 그대로 실어 보내는 값 |

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님 |

## `POST /v1/owner/store-images:prepareUpload`

점주가 가게 인테리어 이미지를 올릴 presigned PUT URL을 발급받는다. 이미지 한 장당
호출한다. 로고는 가게 입점 과정에서 이미 등록돼 있다고 보고 이 API로 새로 올리지
않는다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "originalFilename": "interior.jpg",
  "contentType": "image/jpeg",
  "fileSize": 204800
}
```

| 필드 | 타입 | 필수 | 조건 |
|---|---|---|---|
| originalFilename | string | 예 | |
| contentType | string | 예 | `image/jpeg`, `image/png` 중 하나 |
| fileSize | integer | 예 | 1 이상, 10485760(10MB) 이하 |

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "uploadId": "a1b2c3d4-...",
    "uploadUrl": "https://...",
    "sortOrder": 1
  }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| uploadId | string | 확인 요청에 그대로 보내는 식별자 |
| uploadUrl | string | 이 주소로 파일을 직접 PUT 업로드한다. 10분 안에 사용해야 한다 |
| sortOrder | integer | 이 이미지가 인테리어 이미지 중 몇 번째로 표시되는지(1~3). 서버가 업로드 순서대로 자동으로 매긴다 |

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님, 또는 기본 정보 단계를 아직 안 마침 |
| `422` | `STORE-005` | `contentType`이 `image/jpeg`, `image/png`가 아님 |
| `422` | `STORE-006` | `fileSize`가 10MB를 넘음 |
| `422` | `STORE-007` | 이미 인테리어 이미지가 3개 등록돼 있음 |

## `POST /v1/owner/store-images:confirmUpload`

발급받은 URL로 실제 업로드했는지 확인한다. 이미지 한 장당 호출한다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "uploadId": "a1b2c3d4-..."
}
```

### 성공 응답

`200`. 본문 `data`는 `null`이다.

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `403` | `STORE-008` | 그 `uploadId`가 없거나 본인 가게의 업로드가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님 |
| `422` | `STORE-009` | 실제로 업로드되지 않음(S3에 객체가 없음) |

## `POST /v1/owner/store-images:complete`

인테리어 이미지(0~3개) 등록을 마치고 `IMAGES` 단계를 완료해 `MENU`로 진행한다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "version": 5
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| version | integer | 예 | 진행 상태 조회로 받은 현재 버전 |

### 성공 응답

`200`. `GET /v1/owner/store-registration-progress`와 같은 모양이며 `resumeStep`이
`MENU`, `version`이 1 증가한 값이다.

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `409` | `STORE-001` | `version`이 현재 버전과 다름 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님, 또는 현재 재개 단계가 `IMAGES`가 아님 |
| `409` | `STORE-010` | 로고 이미지가 없음 |

## `POST /v1/owner/store-menus`

점주가 대표 메뉴 하나를 등록한다. 메뉴명·가격·상세설명을 저장하며, 이미지는 이 API로
올리지 않는다. 이미지는 등록된 `menuId`로 `POST /v1/owner/store-menu-images:prepareUpload`를
따로 호출해 올린다. 메뉴 한 건당 호출한다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "name": "제육볶음",
  "price": 9000,
  "description": "매콤한 제육볶음입니다."
}
```

| 필드 | 타입 | 필수 | 조건 |
|---|---|---|---|
| name | string | 예 | 100자 이내 |
| price | integer | 예 | 0 이상. 원 단위 |
| description | string | 아니오 | 500자 이내 |

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "menuId": 10,
    "sortOrder": 1
  }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| menuId | integer | 등록된 메뉴의 식별자. 이미지 업로드 요청에 그대로 보낸다 |
| sortOrder | integer | 이 메뉴가 몇 번째로 표시되는지(1~3). 서버가 등록 순서대로 자동으로 매긴다 |

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님, 또는 기본 정보 단계를 아직 안 마침 |
| `422` | `STORE-011` | 이미 메뉴가 3개 등록돼 있음 |

## `POST /v1/owner/store-menu-images:prepareUpload`

점주가 등록한 메뉴의 이미지를 올릴 presigned PUT URL을 발급받는다. 메뉴 이미지 한 장당
호출한다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "menuId": 10,
  "originalFilename": "menu.jpg",
  "contentType": "image/jpeg",
  "fileSize": 204800
}
```

| 필드 | 타입 | 필수 | 조건 |
|---|---|---|---|
| menuId | integer | 예 | `POST /v1/owner/store-menus`로 받은 식별자 |
| originalFilename | string | 예 | |
| contentType | string | 예 | `image/jpeg`, `image/png` 중 하나 |
| fileSize | integer | 예 | 1 이상, 10485760(10MB) 이하 |

### 성공 응답

`200`.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "uploadId": "a1b2c3d4-...",
    "uploadUrl": "https://..."
  }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| uploadId | string | 확인 요청에 그대로 보내는 식별자 |
| uploadUrl | string | 이 주소로 파일을 직접 PUT 업로드한다. 10분 안에 사용해야 한다 |

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `403` | `STORE-012` | 그 `menuId`가 없거나 본인 가게의 메뉴가 아님(존재 여부를 알려주지 않으려고 같은 코드로 묶는다, `api-spec.md`) |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님 |
| `422` | `STORE-005` | `contentType`이 `image/jpeg`, `image/png`가 아님 |
| `422` | `STORE-006` | `fileSize`가 10MB를 넘음 |

## `POST /v1/owner/store-menu-images:confirmUpload`

발급받은 URL로 실제 업로드했는지 확인한다. 메뉴 이미지 한 장당 호출한다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "uploadId": "a1b2c3d4-..."
}
```

### 성공 응답

`200`. 본문 `data`는 `null`이다.

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `403` | `STORE-012` | 그 `uploadId`가 없거나 본인 가게의 메뉴 이미지가 아님 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님 |
| `422` | `STORE-006` | 실제 업로드된 크기가 선언한 크기나 10MB를 넘음 |
| `422` | `STORE-009` | 실제로 업로드되지 않음(S3에 객체가 없음) |

## `POST /v1/owner/store-menus:complete`

대표 메뉴(0~3개) 등록을 마치고 `MENU` 단계를 완료한다. 다음 단계는 없으며 재개 단계는
`MENU`를 유지한다.

### 인증

`GET /v1/owner/store-registration-progress`와 같다.

### 요청

```json
{
  "version": 6
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| version | integer | 예 | 진행 상태 조회로 받은 현재 버전 |

### 성공 응답

`200`. `GET /v1/owner/store-registration-progress`와 같은 모양이며 `resumeStep`이
`MENU`, `version`이 1 증가한 값이다.

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `409` | `STORE-001` | `version`이 현재 버전과 다름 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님, 또는 현재 재개 단계가 `MENU`가 아님 |
| `409` | `STORE-013` | 등록한 메뉴 중 이미지가 `VERIFIED`가 아닌 메뉴가 있음 |
