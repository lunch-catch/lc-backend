# 광고 서빙 (adserving) API 명세

스와이프 피드, 찜과 패스, 노출 수집, 피드 필터, 찜 목록, 피드 배정 진단 API의 요청, 응답, 오류를 적는다.
공통 규약(경로, 인증, 응답 봉투, 상태 코드, 식별자, 시각)은 `README.md` 를 따르고 여기서는 반복하지 않는다.
서버가 내부에서 어떻게 처리하는지와 그렇게 정한 이유는 설계서 `feed-api-design.md` 에 있다.

**표 읽는 법.** 응답 필드 표의 "null" 칸이 "가능"이면 그 필드에 `null` 이 올 수 있고, 언제 오는지는 설명 칸에 적는다.
"아니오"면 항상 값이 있다. 배열은 비어 있을 수는 있어도 `null` 은 아니다.

## 목록

| 메서드 | 경로 | 하는 일 | 권한 |
|---|---|---|---|
| `GET` | `/v1/feed` | 스와이프 피드 10장 | MEMBER |
| `POST` | `/v1/swipes` | 찜 또는 패스 | MEMBER |
| `POST` | `/v1/impressions` | 카드 표시 이벤트 수집 | MEMBER |
| `GET` | `/v1/feed-filter` | 내 피드 필터 | MEMBER |
| `PUT` | `/v1/feed-filter` | 피드 필터 저장 | MEMBER |
| `GET` | `/v1/wishlist` | 찜 목록 | MEMBER |
| `POST` | `/v1/wishlist` | 가게 상세에서 찜 | MEMBER |
| `DELETE` | `/v1/wishlist/{campaignId}` | 찜 삭제 | MEMBER |
| `GET` | `/v1/admin/feed-diagnosis/feeds` | 사용자 피드 배정 진단 | ADMIN, SUPER_ADMIN |

## 피드

### `GET /v1/feed`

서빙 시간대(10:00~12:59)에 현재 위치 기준 카드 10장을 내려준다.

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `latitude` | 쿼리 | number | 아니오 | 현재 위도. -90 ~ 90 |
| `longitude` | 쿼리 | number | 아니오 | 현재 경도. -180 ~ 180 |

- 둘 다 보내지 않으면 사용자의 저장 위치를 쓴다
- 둘 중 하나만 보내면 `400` 이다

```
GET /v1/feed?latitude=37.4979&longitude=127.0276
```

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "status": "SERVING",
    "feedId": "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8d9e",
    "radiusM": 1000,
    "nextFeedAvailableAt": "2026-10-01T10:15:30+09:00",
    "cards": [
      {
        "serveId": "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0",
        "servedAt": "2026-10-01T10:15:30.123+09:00",
        "campaignId": 101,
        "storeId": 55,
        "storeName": "점심식당",
        "categoryCode": "KOREAN",
        "distanceM": 420,
        "discount": { "target": "ALL", "type": "PERCENT", "value": 20 },
        "issueOpenTime": "11:00",
        "usableStartTime": "12:00",
        "usableEndTime": "14:00",
        "issueQuantity": 30,
        "posterUrl": "/v1/campaigns/101/poster"
      }
    ]
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `status` | string | 아니오 | `SERVING`(카드 있음), `OUT_OF_SERVING_HOURS`(서빙 시간대 밖), `NO_CANDIDATE`(후보 없음) |
| `feedId` | string (UUID) | 가능 | 이 요청 한 번의 카드 묶음 ID. `SERVING` 이 아니면 `null` |
| `radiusM` | integer | 가능 | 후보를 찾는 데 실제로 쓴 반경(미터). 1000, 2000, 3000, 5000 중 하나. 후보가 모자라면 서버가 넓혀 가므로 요청마다 다를 수 있다. 점주가 캠페인에 지정한 노출 반경과는 다른 값이다. `OUT_OF_SERVING_HOURS` 면 `null` |
| `nextFeedAvailableAt` | string (시각) | 아니오 | 다음 피드를 요청해도 거부되지 않는 가장 이른 시각. 지금 바로 요청할 수 있으면 응답 시각과 같다 |
| `cards` | array | 아니오 | 카드 목록. 최대 10장. `SERVING` 이 아니면 빈 배열 |
| `cards[].serveId` | string (UUID) | 아니오 | 카드 1장의 식별자. 노출과 스와이프 요청에 그대로 돌려보낸다 |
| `cards[].servedAt` | string (시각) | 아니오 | 서버가 카드를 내려준 시각 |
| `cards[].campaignId` | integer | 아니오 | 캠페인 ID |
| `cards[].storeId` | integer | 아니오 | 가게 ID. 카드를 누르면 가게 상세로 이동할 때 쓴다 |
| `cards[].storeName` | string | 아니오 | 상호 |
| `cards[].categoryCode` | string | 아니오 | 업종 코드 |
| `cards[].distanceM` | integer | 아니오 | 요청 위치에서 가게까지 거리(미터) |
| `cards[].discount.target` | string | 아니오 | `ALL`(전체 메뉴), `MENU`(특정 메뉴) |
| `cards[].discount.type` | string | 아니오 | `PERCENT`(할인율), `AMOUNT`(할인 금액) |
| `cards[].discount.value` | integer | 아니오 | `PERCENT` 면 1 ~ 100(%), `AMOUNT` 면 원 |
| `cards[].issueOpenTime` | string (HH:mm) | 아니오 | 선착순 오픈 시각. 지금은 항상 `11:00` |
| `cards[].usableStartTime` | string (HH:mm) | 아니오 | 쿠폰 사용 시작 시각 |
| `cards[].usableEndTime` | string (HH:mm) | 아니오 | 쿠폰 사용 종료 시각 |
| `cards[].issueQuantity` | integer | 아니오 | 선착순 발급 수량(표시용). 잔여 수량은 싣지 않는다 |
| `cards[].posterUrl` | string | 아니오 | 포스터 주소. 사용자 포스터 조회 API(`poster.md`)의 경로다 |

**클라이언트 규칙**

- **받은 순서를 바꾸지 않는다**
- **모든 카드에 "광고" 라벨을 항상 표시한다.** 서버는 라벨 여부를 내려주지 않는다
- 피드에서는 찜과 패스만 할 수 있다. 발급 버튼을 두지 않는다
- `OUT_OF_SERVING_HOURS` 면 "내일 10시에 만나요"를, `NO_CANDIDATE` 면 빈 피드 안내를 보인다
- 마지막 카드에 닿았을 때 지금 시각이 `nextFeedAvailableAt` 이후면 바로 다음 피드를 요청한다. 아직 전이면 "N초 후 새 카드"를 보여 주고, 그 시각에 한 번 요청한다
- 포스터는 `posterUrl` 을 불러 받은 `data.html` 을 `<iframe sandbox srcdoc=...>` 에 그린다. 점주가 만든 HTML이라 앱 화면과 격리한다. 같은 주소는 항상 같은 포스터라 브라우저 캐시를 그대로 쓴다

**요청 제한**

- 사용자마다 **최근 60초 동안 6회**까지 받는다. 분 단위로 끊어 세지 않는다
- `200` 으로 끝난 요청은 카드가 없어도 횟수에 들어간다. 거부된 요청(`429`)은 들어가지 않는다
- 넘으면 `429` 와 함께 `Retry-After` 헤더(다시 요청할 수 있을 때까지 남은 초, 올림한 정수)를 준다

```
HTTP/1.1 429 Too Many Requests
Retry-After: 37
```

- 클라이언트는 `Retry-After` 초가 지난 뒤 **한 번만** 다시 요청한다. 앱이 화면에 보일 때만 다시 요청하고, 실패하면 반복하지 않는다

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 위도와 경도 중 하나만 보냄, 범위 밖 좌표 |
| `403` | `ADSERVING-001` | 온보딩(성별, 연령대)을 마치지 않음 |
| `422` | `ADSERVING-002` | 위치를 보내지 않았고 저장 위치도 없음 |
| `429` | `ADSERVING-003` | 최근 60초 6회 초과 |

## 찜과 패스

### `POST /v1/swipes`

카드를 오른쪽(찜) 또는 왼쪽(패스)으로 넘긴다. 어느 쪽으로 넘겨도 노출 수는 달라지지 않는다.

**요청**

```json
{
  "serveId": "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0",
  "campaignId": 101,
  "action": "WISH",
  "occurredAt": "2026-10-01T10:15:34.020+09:00"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `serveId` | string (UUID) | 예 | 피드 응답의 카드 식별자 |
| `campaignId` | integer | 예 | 카드의 캠페인 |
| `action` | string | 예 | `WISH`(찜) 또는 `PASS`(패스) |
| `occurredAt` | string (시각) | 예 | 클라이언트에서 넘긴 시각 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "applied": true }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `applied` | boolean | 아니오 | 이번 요청이 반영됐으면 `true`. 같은 카드를 이미 넘긴 적이 있으면 `false` |

**동작**

- 같은 카드(`serveId`)는 처음 넘긴 것 하나만 반영한다. 다시 보내면 `applied: false` 로 성공을 돌려준다
- `WISH` 면 찜 목록에 들어간다. 찜한 캠페인과 패스한 캠페인은 그날 피드에 다시 나오지 않는다. 다음 날에는 다시 후보가 된다

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 필수 필드 누락, `action` 값이 잘못됨 |
| `404` | `ADSERVING-004` | `campaignId` 가 오늘 노출 중인 캠페인이 아님 |

## 노출 수집

### `POST /v1/impressions`

카드가 스와이프 피드의 맨 위 카드로 보인 순간 1건씩 즉시 보낸다.

**요청**

```json
{
  "serveId": "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0",
  "campaignId": 101,
  "displayedAt": "2026-10-01T10:15:31.880+09:00",
  "latitude": 37.4979,
  "longitude": 127.0276,
  "viewedMs": 2140
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `serveId` | string (UUID) | 예 | 피드 응답의 카드 식별자 |
| `campaignId` | integer | 예 | 카드의 캠페인 |
| `displayedAt` | string (시각) | 예 | 카드가 보인 시각 |
| `latitude` | number | 예 | 카드가 보인 시점의 위도. 서버는 소수점 3자리로 반올림해 저장한다 |
| `longitude` | number | 예 | 카드가 보인 시점의 경도. 서버는 소수점 3자리로 반올림해 저장한다 |
| `viewedMs` | integer | 아니오 | 카드를 본 시간(밀리초). 참고 지표 |

요청한 사용자는 본문이 아니라 로그인 정보로 정한다.

**응답** `202`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "validity": "VALID", "invalidReason": null }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `validity` | string | 아니오 | `VALID`(유효 노출), `INVALID`(무효 노출) |
| `invalidReason` | string | 가능 | `INVALID` 일 때 사유. `EXPIRED_OR_UNKNOWN`(카드를 받은 지 30분이 지났거나 없는 카드), `NOT_OWNER`(다른 사용자가 받은 카드). `VALID` 면 `null` |

**클라이언트 규칙**

- 그 카드까지 넘기지 않았으면 보내지 않는다. 앱이 백그라운드에 있는 동안의 카드도 보내지 않는다
- 화면을 벗어나는 순간에도 전송이 끝나도록 `keepalive` 옵션을 쓴다(README "클라이언트가 응답을 기다리지 않는 요청")
- **응답에 의존하지 않는다.** 실패하면 1회 재시도하고 포기한다

**동작**

- 같은 사용자가 같은 카드(`serveId`)를 다시 보내면 새로 기록하지 않고, `202` 와 처음 판정을 돌려준다
- 무효 노출은 오류가 아니다. `202` 와 `INVALID` 로 돌려준다

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 필수 필드 누락, `serveId` 형식 오류 |

## 피드 필터

### `GET /v1/feed-filter`

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "categories": ["KOREAN", "JAPANESE"], "sort": "DISTANCE" }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `categories` | array of string | 아니오 | 고른 업종 코드. 빈 배열이면 전체 업종 |
| `sort` | string | 아니오 | `DISTANCE`(거리순), `DISCOUNT_RATE`(할인율순) |

저장한 적이 없으면 `categories: []`, `sort: "DISTANCE"` 를 돌려준다.

### `PUT /v1/feed-filter`

필터를 통째로 바꾼다. 계정에 저장되어 다시 접속해도 유지되고, 다음 피드 요청부터 적용된다.

**요청**

```json
{ "categories": ["KOREAN", "JAPANESE"], "sort": "DISCOUNT_RATE" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `categories` | array of string | 예 | 업종 코드 목록. **빈 배열은 전체 업종이다** |
| `sort` | string | 예 | `DISTANCE`, `DISCOUNT_RATE` |

**응답** `200`. `data` 는 `GET /v1/feed-filter` 와 같은 모양이다.

- 업종 필터는 카드 10장 전부에, 정렬은 추천 카드 7장에만 적용된다. 위치 1, 4, 7번 카드는 정렬과 상관없이 정해진다

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | 없는 업종 코드, 허용되지 않은 정렬 값 |

## 찜 목록

### `GET /v1/wishlist`

찜한 캠페인 목록이다. 잔여 수량, 발급 가능 여부, 하루 발급 잔여 횟수는 여기에 없다.
받은 `campaignId` 들로 쿠폰 API(`coupon.md`)를 따로 불러 화면에서 합친다.

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `campaignId` | 쿼리 | integer | 아니오 | 주면 그 캠페인만 돌려준다. 가게 상세에서 찜 여부를 볼 때 쓴다 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "wishlist": [
      {
        "campaignId": 101,
        "storeId": 55,
        "storeName": "점심식당",
        "storeImageUrl": "https://cdn.example.com/stores/55/logo.jpg",
        "discount": { "target": "MENU", "type": "AMOUNT", "value": 3000, "menuName": "갈비살 정식" },
        "wishedAt": "2026-10-01T10:15:34.020+09:00"
      }
    ]
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `wishlist` | array | 아니오 | 찜 목록. 찜한 시각 내림차순. 0건이면 빈 배열 |
| `wishlist[].campaignId` | integer | 아니오 | 캠페인 ID |
| `wishlist[].storeId` | integer | 아니오 | 가게 ID |
| `wishlist[].storeName` | string | 가능 | 상호. 오늘 노출 후보가 아닌 캠페인이면 `null` |
| `wishlist[].storeImageUrl` | string | 가능 | 가게 대표 이미지 주소. 오늘 노출 후보가 아니거나 가게가 대표 이미지를 올리지 않았으면 `null`. 가게가 이미지를 바꾸면 다음 날부터 반영된다 |
| `wishlist[].discount` | object | 가능 | 할인 내용. 오늘 노출 후보가 아닌 캠페인이면 `null` |
| `wishlist[].discount.target` | string | 아니오 | `ALL`, `MENU` |
| `wishlist[].discount.type` | string | 아니오 | `PERCENT`, `AMOUNT` |
| `wishlist[].discount.value` | integer | 아니오 | `PERCENT` 면 1 ~ 100(%), `AMOUNT` 면 원 |
| `wishlist[].discount.menuName` | string | 가능 | 할인 대상 메뉴명. `target` 이 `MENU` 일 때만 값이 있고 `ALL` 이면 `null` |
| `wishlist[].wishedAt` | string (시각) | 아니오 | 찜한 시각 |

- 페이지네이션이 없다. 캠페인이 끝나면 찜 목록에서 자동으로 빠진다
- 0건이면 빈 상태 화면과 피드 이동 버튼을 보인다
- 이 화면 조회는 노출이 아니다. 분석 수집 API로 `wishlist_view` 를 따로 보낸다(`analytics.md`)

### `POST /v1/wishlist`

가게 상세의 "찜해두기"다. 피드의 찜은 `POST /v1/swipes` 로 한다.

**요청**

```json
{ "campaignId": 101 }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `campaignId` | integer | 예 | 찜할 캠페인 |

**응답** `201`. 이미 찜한 캠페인이면 `200` 이다. 두 경우 모두 `data` 는 `GET /v1/wishlist` 의 항목 하나와 같은 모양이다.

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | `campaignId` 누락 |
| `404` | `ADSERVING-004` | 캠페인이 오늘 노출 중인 캠페인이 아님 |

### `DELETE /v1/wishlist/{campaignId}`

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `campaignId` | 경로 | integer | 예 | 찜을 지울 캠페인 |

**응답** `204`. 찜 목록에 없는 캠페인이어도 `204` 다.

- 지운 캠페인은 그날 피드에 다시 나오지 않는다
- 10:50 선착순 오픈 알림 대상에서도 빠진다

## 피드 배정 진단 (관리자)

### `GET /v1/admin/feed-diagnosis/feeds`

특정 사용자의 피드 한 묶음이 어떻게 구성됐는지 본다. 기록을 바꾸지 않는다.

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `feedId` | 쿼리 | string (UUID) | 둘 중 하나 | 피드 묶음 ID |
| `memberId` | 쿼리 | integer | 둘 중 하나 | 사용자 ID. `at` 과 함께 보낸다 |
| `at` | 쿼리 | string (시각) | 둘 중 하나 | 이 시각 직전의 피드 묶음을 찾는다. `memberId` 와 함께 보낸다 |

**응답** `200`

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "found": true,
    "feedId": "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8d9e",
    "memberId": 3001,
    "servedAt": "2026-10-01T10:15:30.123+09:00",
    "cards": [
      { "slotPosition": 1, "slotType": "ALLOCATION", "campaignId": 101, "storeId": 55, "serveId": "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0" }
    ],
    "settings": {
      "slotRatio": "3:7",
      "relevanceWeights": { "distance": 45, "category": 35, "discount": 20 },
      "dailyImpressionCap": 2
    }
  }
}
```

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `found` | boolean | 아니오 | 기록을 찾았으면 `true`. `false` 면 화면은 "기록 없음"을 보인다 |
| `feedId` | string (UUID) | 가능 | `found` 가 `false` 면 `null` |
| `memberId` | integer | 가능 | `found` 가 `false` 면 `null` |
| `servedAt` | string (시각) | 가능 | 피드를 내려준 시각. `found` 가 `false` 면 `null` |
| `cards` | array | 아니오 | 카드 구성. `found` 가 `false` 면 빈 배열 |
| `cards[].slotPosition` | integer | 아니오 | 카드 위치. 1 ~ 10 |
| `cards[].slotType` | string | 아니오 | `ALLOCATION`(배분 슬롯), `RELEVANCE`(관련성 슬롯) |
| `cards[].campaignId` | integer | 아니오 | 캠페인 ID |
| `cards[].storeId` | integer | 아니오 | 가게 ID |
| `cards[].serveId` | string (UUID) | 아니오 | 카드 식별자 |
| `settings` | object | 가능 | 그 시각에 적용된 설정값. `found` 가 `false` 면 `null` |
| `settings.slotRatio` | string | 아니오 | 배분 대 관련성 슬롯 비율. 예: `3:7` |
| `settings.relevanceWeights.distance` | integer | 아니오 | 거리 가중치(%) |
| `settings.relevanceWeights.category` | integer | 아니오 | 카테고리 선호도 가중치(%) |
| `settings.relevanceWeights.discount` | integer | 아니오 | 할인 가중치(%) |
| `settings.dailyImpressionCap` | integer | 아니오 | 같은 캠페인을 한 사용자에게 하루에 내려주는 최대 횟수 |

**오류**

| 상태 | 코드 | 언제 |
|---|---|---|
| `400` | `COMMON-002` | `feedId` 도 `memberId` 와 `at` 도 없음 |
| `422` | `ADSERVING-005` | 서빙 기록 보관 기간(13개월) 밖의 시각 |

## 오류 코드

요청 형식이 틀린 실패는 공통 코드(`COMMON-002`)로 온다. 공통 코드 목록은 `README.md` 에 있다.
여기에는 광고 서빙만 아는 실패를 둔다.

| 코드 | 상태 | 메시지 |
|---|---|---|
| `ADSERVING-001` | `403` | 온보딩을 완료한 뒤 피드를 이용할 수 있습니다. |
| `ADSERVING-002` | `422` | 위치를 설정한 뒤 피드를 이용할 수 있습니다. |
| `ADSERVING-003` | `429` | 잠시 후 다시 시도해 주세요. |
| `ADSERVING-004` | `404` | 오늘 노출 중인 캠페인이 아닙니다. |
| `ADSERVING-005` | `422` | 조회할 수 있는 기간이 지났습니다. |
