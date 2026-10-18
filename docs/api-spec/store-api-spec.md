# 가게 (store) API 명세

공통 인증·응답·오류 규약은 [api-spec.md](api-spec.md)를 따른다.
서버 내부 처리와 근거는 [store-api-design.md](../design/store/store-api-design.md)를 참고한다.

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
버전을 조회한다. 진행 상태가 없으면 DB에 생성하지 않고 `storeId=null`, `resumeStep=TERMS`, `version=0`을 반환한다.
최초 진행 상태 생성은 점주 행을 잠근 약관 저장 트랜잭션에서 처리한다.

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
| storeId | integer | 예 | 기본 정보 단계(`STORE_INFO`)를 아직 안 마쳤으면 `null` |
| resumeStep | string(enum) | 아니오 | 이어서 진행할 단계. `TERMS`, `STORE_INFO`, `LOCATION`, `HOURS`, `IMAGES`, `MENU` 중 하나 |
| version | integer | 아니오 | 다음 완료·수정 요청에 그대로 실어 보내는 값 |

### 오류

| 상태 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-005` | 유효한 점주 인증이 없음 |
| `403` | `AUTH-006` | 인증된 역할이 `OWNER`가 아님 |
| `409` | `STORE-002` | 점주 상태가 `ONBOARDING`이 아님 |

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

## `GET /v1/owner/store`

인증된 점주의 가게 ID와 전체 저장 정보를 조회한다. ONBOARDING 초안과 ACTIVE 가게를 지원한다.
가게 생성 전에도 `200`과 응답 객체를 반환한다. `storeId`는 null이며 약관과 진행 정보는 조회된다. 조회로 가게나 진행 상태를 생성하지 않는다.
요청 본문과 경로·쿼리 식별자는 받지 않으며 인증된 점주를 기준으로 조회한다.

### 응답 `200`

공통 응답 봉투의 `data`는 항상 객체이며 아래 필드를 갖는다. 목록은 항상 배열이다.

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| storeId | integer(int64) | 예 | STORE_INFO 최초 저장 성공 시 생성되는 가게 ID. 생성 전에는 null |
| finalized | boolean | 아니오 | 최종 등록 여부 |
| name, businessCategory, representativeName | string | 예 | 상호명, 업종 코드, 대표자 성명. 가게 생성 전에는 null |
| businessRegistrationNumber, phone | string | 예 | 사업자등록번호, 전화번호. 기존 미입력 데이터는 null |
| businessVerificationStatus | string | 예 | VERIFIED 또는 FAILED. 미검증은 null |
| businessVerifiedAt | string(date-time) | 예 | 기존 저장 규약의 KST 검증 성공 시각 |
| kakaoPlaceId, roadAddress | string | 예 | 카카오 장소 ID, 도로명 주소 |
| latitude, longitude | number | 예 | 위도, 경도 |
| businessHours | array | 아니오 | 월요일부터 일요일 순. 미입력은 [] |
| logoImage | object | 예 | 검증된 대표 이미지. 없으면 null |
| interiorImages | array | 아니오 | 검증된 내부 이미지. sortOrder 오름차순, 없으면 [] |
| menus | array | 아니오 | sortOrder 오름차순. 메뉴 0개도 정상이며 [] |

`businessHours` 항목은 `dayOfWeek`(MONDAY~SUNDAY), `openTime`, `closeTime`, `isClosed`,
`breakStartTime`, `breakEndTime`, `lastOrderTime`을 갖는다. 시각은 `HH:mm:ss`이며 미입력은 null이다.
`dayOfWeek`와 `isClosed`는 null이 아니다.

이미지는 `objectKey`(string), `url`(string), `sortOrder`(integer 또는 null)를 갖는다.
대표·메뉴 이미지의 sortOrder는 null이다. PENDING·UPLOADED 이미지는 반환하지 않는다.
이미지가 있는데 CDN 설정이 없으면 500으로 처리하며 이미지가 없는 것처럼 숨기지 않는다.

메뉴는 `menuId`(integer), `name`(string), `price`(integer, 원), `description`(string 또는 null),
`sortOrder`(integer), `image`(이미지 또는 null)를 갖는다. 이미지 미검증 상태의 기존 메뉴도 반환한다.
이 조회의 null 허용은 메뉴 저장·최종 등록의 필수 이미지 검증을 완화하지 않는다.

### 오류

| 상태·코드 | 조건 |
|---|---|
| 401 AUTH-005 | 인증이 없거나 유효하지 않음 |
| 403 AUTH-006 | OWNER 외 역할 |
| 403 OWNER-002 | DB의 현재 점주 상태가 SUSPENDED 또는 WITHDRAWN |

내 가게 조회는 약관 동의 내역과 단계별 저장·완료 상태, 현재·재개 단계와 최종 등록 가능 여부를 함께 반환한다.
별도 진행 상태 조회는 화면 이동에 필요한 단계·버전 중심의 가벼운 응답을 담당한다.

### onboarding

| 필드 | 타입 | 의미 |
|---|---|---|
| currentStep | string 또는 null | 저장된 진행 상태의 단계. 진행 상태가 없으면 TERMS, 최종 등록 후에는 null |
| resumeStep | string 또는 null | 필수 저장값을 다시 확인한 첫 미완료 단계. 필수 단계가 충족되면 MENU, 최종 등록 후에는 null |
| version | integer(int64) | 저장된 요청 버전. 진행 상태가 없으면 0 |
| canComplete | boolean | 아직 최종 등록하지 않았고 필수 저장값과 선택 메뉴의 필수 항목이 충족되는지 여부 |
| termsAgreement | object 또는 null | 저장된 약관 동의. 기록이 없으면 null |
| steps | array | TERMS, STORE_INFO, LOCATION, HOURS, IMAGES, MENU 순서의 현재 저장 상태 |

steps의 항목은 `step`, `optional`, `hasSavedData`, `completed`를 갖는다.
MENU만 optional=true다. 메뉴가 없으면 hasSavedData=false, completed=false지만 최종 등록을 막지 않는다.
메뉴가 있으면 메뉴명·0원 이상의 가격·검증된 이미지가 모두 있어야 완료다.
현재 단계와 재개 단계는 데이터 누락이 있을 때 다를 수 있다. 이미 등록된 가게는 finalized를 먼저 확인한다.

termsAgreement는 `termsType`, `termsVersion`, `required`, `agreed`, `agreedAt`, `recordedAt`, `withdrawnAt`을 갖는다.
PR #148에 맞춰 종류·버전은 항상 null이며 required는 항상 true다. 동의 시각과 기록 생성 시각은 구분한다.
agreedAt은 develop에 병합된 PR #148의 agreed_at 저장값이다.
recordedAt은 저장 행의 created_at이다. agreed=true이고 철회 시각이 없을 때 TERMS 완료다.

canComplete는 현재 조회의 입력 충족 여부다. 최종 등록 POST에서도 필수 조건을 다시 검증해야 한다.
HOURS는 현재 저장 구조에서 영업시간 행이 존재하는지 확인한다. 향후 HOURS 저장 API의 추가 검증을 확정하면 같은 기준으로 맞춘다.
진행 내역은 현재 저장 상태의 요약이며 덮어쓰기 전 값이나 단계별 과거 변경 이력을 생성하지 않는다.


가게 생성 전에는 storeId와 가게 입력값이 null이며 목록은 []다. onboarding은 항상 반환한다.
약관 동의가 없으면 resumeStep=TERMS이며 약관만 동의한 상태면 resumeStep=STORE_INFO다.
어느 경우에도 GET에서 가게·진행 상태·약관을 생성하지 않는다.

## 가게 등록 온보딩 3단계: 위치 저장 (#482)

`PUT /v1/owner/store/location`은 프론트가 카카오맵에서 선택·확정한 위치를 저장한다.
백엔드는 카카오 API를 호출하거나 장소의 실재 여부를 재검증하지 않는다.
인증된 OWNER 중 DB 현재 상태가 ONBOARDING인 점주만 본인의 미확정 가게에 저장할 수 있다.

### 요청

```json
{
  "version": 2,
  "kakaoPlaceId": "12345678",
  "roadAddress": "서울 강남구 테헤란로 123",
  "latitude": 37.5000000,
  "longitude": 127.0000000
}
```

모든 필드가 필수다. version은 0 이상의 integer(int64)다.
장소 ID는 최대 50자, 도로명 주소는 최대 255자이며 빈 문자열·공백만 있는 값은 거절한다.
위도는 -90~90, 경도는 -180~180의 JSON 숫자다. 카카오 응답의 좌표를 그대로 전달할 수 있으며 저장 시 소수점 이하 7자리로 반올림한다(HALF_UP).
점주 ID나 가게 ID는 받지 않는다. 기본 정보와 사업자 검증 성공 및 약관 동의가 선행되어야 한다.

### 성공 응답 `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "storeId": 501, "resumeStep": "HOURS", "version": 3 }
}
```

최초 저장은 LOCATION을 완료해 HOURS로 진행한다.
완료한 위치의 재저장은 네 위치 필드를 모두 덮어쓰며 기존 재개 단계와 다른 단계의 저장값을 유지한다.
같은 값으로 저장해도 성공마다 version이 1 증가한다. 최신 버전으로 요청해야 한다.
저장한 위치와 LOCATION 완료 여부는 기존 내 가게 조회에서 확인한다. 별도 위치 GET은 없다.
가게 생성이나 최종 등록·ACTIVE 전환은 수행하지 않는다.

### 오류

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | 공통 입력·파싱 오류 코드 | 필수값 누락, 문자열·좌표·버전 오류, 잘못된 JSON, 저장된 기본 정보의 필수값 오류 |
| 401 | AUTH-005 | 미인증 또는 점주 계정 부재 |
| 403 | AUTH-006 | OWNER 외 역할 |
| 403 | OWNER-002 | 현재 점주 상태가 ONBOARDING이 아님 |
| 403 | STORE-004 | 본인 초안 부재, 진행 상태와 가게 연결 불일치 또는 최종 등록한 가게임 |
| 409 | STORE-001 | 요청 버전이 현재 버전과 다름 |
| 409 | STORE-002 | LOCATION 이전 단계이거나 유효한 약관 동의·사업자 검증 성공이 없음 |
| 409 | STORE-003 | 진행 상태가 없음 |

실패하면 위치·진행 상태·버전을 함께 보존한다. 버전 충돌 시 최신 조회 결과를 확인한 뒤 재저장한다.
