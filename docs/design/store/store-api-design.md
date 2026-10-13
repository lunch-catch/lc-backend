# 가게 API 설계

## 카카오맵 가게 검색

계약은 [store-api-spec.md](../../api-spec/store-api-spec.md)의 카카오맵 가게 검색 절을 따른다.
관련 이슈는 #190이다.

### 경계와 공개 계약

검색과 카카오 응답 변환은 `store`가 소유한다.
점주의 요청 접수와 현재 상태 검사는 [점주 API 설계](../owner/owner-api-design.md)의 카카오맵 가게 검색 절을 따른다.

`store.contract.StoreQueryService.searchKakaoPlaces(query)`를 공개한다.
`KakaoPlaceSearchService`가 검색 계약을 구현하며 검색어의 앞뒤 공백을 제거한다.
검색 결과는 `PlaceSearchResult`와 내부의 `Place` record로 반환하며 HTTP DTO를 공개하지 않는다.
계약은 점주 ID, 상태, 인증 객체를 받지 않으며 DB의 상태를 조회하지 않는다.
호출자는 인증, 인가, 검색어의 필수 조건을 검증한 뒤 계약을 호출한다.
검색 결과가 없을 때만 빈 목록을 반환하며 외부 장애는 예외로 전파한다.

### 외부 API 경계

`KakaoPlaceSearchService`는 `PlaceSearchClient` 인터페이스만 주입받는다.
`PlaceKeywordSearchResponse`는 카카오 JSON을 읽는 전용 타입이며 `client` 안에 둔다.
`KakaoLocalClient`가 카카오 응답을 `store.contract.PlaceSearchResult`로 변환한다.
카카오 필드명과 원문 응답은 `client` 패키지 안에서만 사용한다.
클라이언트와 서비스는 같은 계약 결과를 사용하며 중간 결과 타입을 따로 두지 않는다.
서비스는 검색어의 앞뒤 공백을 제거한 뒤 클라이언트가 반환한 결과를 그대로 전달한다.
DB 트랜잭션을 외부 HTTP 호출까지 유지하지 않으며 검색 결과를 저장하지 않는다.

카카오 키워드 검색 경로는 `/v2/local/search/keyword.json`이다.
서버는 `query`, `sort=accuracy`, `page=1`, `size=15`만 전송한다.
카카오 요청의 `x`, `y`, `radius`는 사용하지 않는다.
카카오의 `y`는 위도이고 `x`는 경도다. 좌표 문자열은 `BigDecimal`로 변환한다.
전화번호와 도로명 주소의 빈 문자열은 `null`로 바꾼다.

사용자가 요청한 검색어 하나로 가게 선택 후보를 제공하므로 공통 목록 페이지네이션의 예외로 첫 15개만 반환한다.
카카오의 전체 장소 목록을 순회하거나 우리 DB의 가게 목록을 조회하는 API가 아니다.
페이지가 필요해지면 요청과 응답의 계약을 먼저 확장한다.

### 설정과 장애 처리

`kakao.local.rest-api-key`는 `KAKAO_LOCAL_REST_API_KEY` 환경변수로 주입한다.
설정값은 `@Value`로 설정 메서드와 클라이언트 생성자에 직접 주입하며 별도의 Properties 클래스를 두지 않는다.
값이 없으면 앱의 다른 기능은 기동할 수 있지만 검색 요청은 `503`으로 실패한다.
키는 서버의 `Authorization: KakaoAK ...` 헤더에만 넣는다.
API의 호스트와 경로는 서버가 정하며 클라이언트가 바꿀 수 없다.

주입받은 `WebClient.Builder`로 전용 클라이언트를 만든다.
공통 `WebClientConfig`의 연결 3초, 응답 5초, 풀 대기 2초와 외부 호출 로그 필터를 사용한다.
`kakao.local.request-timeout`은 전체 요청의 상한이며 기본 8초다.
클라이언트는 `DurationStyle`로 `8s` 같은 설정값을 변환하며 0 이하이거나 해석할 수 없는 타임아웃이면 기동에 실패한다.
이 값은 초기 설정값이며 운영 지연을 측정한 성능 목표가 아니다.

가게 검색 전용 `kakaoPlaceSearch` 회로를 사용한다.
연결 오류, 타임아웃, 카카오 429와 5xx를 회로 실패로 센다.
그 밖의 카카오 4xx와 응답 변환 오류는 회로 실패로 세지 않는다.
일시적 장애와 열린 회로는 `503`, 잘못된 카카오 응답은 `502`로 변환한다.
정상적인 `documents: []`만 빈 목록으로 처리한다.

외부 API의 기본 재시도에서 벗어나 서버는 검색을 자동 재시도하지 않는다.
가게 선택 화면은 사용자가 다시 검색할 수 있고 동일 요청을 반복하면 카카오 호출량과 대기 시간이 늘어난다.
일시적 장애는 이슈 #190의 요구에 따라 즉시 알린다.
실제 카카오 호출에 대한 응답 시간과 동시 요청 성능은 아직 측정하지 않았다.
