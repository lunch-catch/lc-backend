# 점주 계정 API 설계

## 내 정보 조회

계약은 [owner-api-spec.md](../../api-spec/owner-api-spec.md)의 내 정보 조회 절을 따른다. 관련 이슈는 #151이다.

공용 JWT 필터가 쿠키를 검증하고 컨트롤러는 인증된 CustomUserDetails의 ID만 서비스에 전달한다.
서비스는 읽기 전용 트랜잭션에서 점주를 한 번 조회한다. 상태 캐시나 토큰 클레임 대신 같은 DB 행의 이메일, 상태와 튜토리얼 확인 여부를 반환한다.
계정이 없으면 LOGIN_REQUIRED로 처리한다. 저장소 장애는 공통 예외 처리로 전파하며 정상 응답으로 대체하지 않는다.

현재 상태를 확인하는 API이므로 ONBOARDING을 차단하지 않고 별도의 상태별 업무 인가를 추가하지 않는다.
토큰 무효화는 공용 인증 필터의 검증을 따르며 인증 로직을 복제하지 않는다.
튜토리얼 완료(#139), 재발급(#123), 로그아웃(#133) 병합 시 OwnerSecurityConfig의 기존 규칙과 조회 규칙을 함께 유지한다.

## 카카오맵 가게 검색

HTTP 계약은 [store-api-spec.md](../../api-spec/store-api-spec.md)의 카카오맵 가게 검색 절을 따른다.
점주 계정의 접근 정책과 가게 검색을 분리하며 도메인 의존 방향은 [도메인 구조와 의존성 설계](../../architecture/런치캐치_도메인_구조와_의존성_설계.md) 2.2절을 따른다.

`OwnerPlaceSearchController`와 요청 및 응답 DTO는 `owner`에 둔다.
`OwnerSecurityConfig`는 `/v1/owner/kakao-places`의 GET만 OWNER 역할에 허용한다.
기존 로그인, 가입, 내 정보 조회의 인증 규칙은 유지한다.

`OwnerPlaceSearchService`는 인증 객체의 점주 ID로 `OwnerRepository.findStatusById`를 호출한다.
상태 컬럼을 DB에서 직접 읽으며 JWT의 상태나 상태 캐시를 사용하지 않는다.
계정이 없으면 `AUTH-005`, 현재 상태가 허용되지 않으면 `OwnerException`으로 `OWNER-002`를 반환한다.
DB 조회 실패는 전파하며 접근이 허용되지 않으면 검색 계약을 호출하지 않는다.

서비스는 상태 확인을 마친 뒤 `store.contract.StoreQueryService`에 검색어만 전달한다.
검색 결과인 `PlaceSearchResult`를 프론트용 응답 DTO로 변환한다.
카카오 호출의 정책과 오류는 [가게 API 설계](../store/store-api-design.md)가 소유한다.
가게 검색에서 올라온 예외는 그대로 전파하며 빈 검색 결과로 바꾸지 않는다.

서비스 전체를 DB 트랜잭션으로 묶지 않으며 외부 API를 기다리는 동안 DB 트랜잭션을 유지하지 않는다.
상태 조회 이후의 동시 상태 변경까지 잠그지는 않는다. 검색은 가게 정보와 점주 상태를 변경하지 않는다.
