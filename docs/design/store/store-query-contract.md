# 가게와 메뉴 조회 공개 계약

쿠폰(#297), 광고 서빙(#161), 분석(#363), 관리자 점주 목록(#365)이 사용하는 조회 계약이다.
공개 타입은 store.contract에 두며 구현은 store.service에 둔다.
HTTP API와 온보딩 저장 기능은 추가하지 않는다.

## 반환 정책

| 메서드 | 동작 |
|---|---|
| findAllById | 가게 ID 목록으로 최종 등록된 가게의 현재 가게명, 대표 이미지 URL, 좌표를 조회한다 |
| findMenusByIdIn | 메뉴 ID 목록으로 최종 등록된 가게에 속한 메뉴의 소속 가게 ID, 이미지 URL, 이름, 원 단위 할인 전 가격을 조회한다 |
| canManageStore | store.owner_id와 요청 ownerId가 일치하고 finalized=true이면 true를 반환한다 |

목록에서 존재하지 않는 ID와 최종 등록 전 가게 및 그 메뉴의 ID는 제외한다. 조회 결과가 없으면 빈 목록을 반환한다.
빈 입력 목록도 빈 목록을 반환한다. 중복 ID는 결과를 늘리지 않으며 반환 순서는 보장하지 않는다.
입력 목록과 각 ID는 null을 허용하지 않고 ID는 양수여야 한다.
목록 자체가 null이면 NullPointerException, 목록 원소 또는 단건 ID가 유효하지 않으면 IllegalArgumentException을 던진다.
DB 장애는 빈 목록이나 false로 바꾸지 않고 예외로 전파한다.

가게·메뉴 목록 조회는 store.finalized=true인 가게를 대상으로 한다.
점주의 ACTIVE 상태 자체를 조회하거나 인증을 대신하지는 않는다.
정상적으로 최종 등록된 가게는 등록 조건과 DB 제약에 따라 좌표가 있어야 한다.
공개 계약의 좌표 타입은 Double이며 DB의 DECIMAL 값을 변환해 반환한다.
변환 시 null을 0으로 바꾸지 않으므로 호출자는 두 좌표가 모두 있는지 확인해야 한다.
쿠폰의 QR 위치 검사는 좌표가 하나라도 없으면 위치 검사를 통과시키지 않아야 한다.
광고 후보처럼 좌표가 필수인 소비자는 좌표가 없는 가게를 후보에서 제외해야 한다.
좌표 누락을 0으로 치환하거나 가게 미존재로 취급하지 않는다.

canManageStore는 현재 본인 소유이면서 최종 등록된 가게인지 판단한다.
가게 미존재, 다른 점주 소유와 등록 중인 가게는 모두 false이며 온보딩 진행 상태는 조회하지 않는다.
점주 계정 상태는 확인하지 않으므로 쿠폰 사용 가능 여부 전체를 대신하지 않는다.
쿠폰은 인증된 점주 ID를 전달하고 자신의 업무에 필요한 상태 조건을 별도로 확인해야 한다.
발급된 쿠폰의 표시는 과거 스냅샷을 사용하고 소유 관계와 QR 검사 좌표는 현재 값을 사용한다.

## 이미지 URL

가게 이미지는 LOGO 중 VERIFIED만 반환하고 메뉴 이미지도 VERIFIED만 반환한다.
최종 등록된 가게는 대표 이미지가 필수다. 메뉴 등록은 선택이며 등록된 메뉴에 대해서만 메뉴 이미지가 필수다.
메뉴가 없는 가게도 가게 조회 결과에 포함한다. 조회할 메뉴가 없으면 메뉴 목록은 빈 목록이다.
쿠폰 응답에서 메뉴 미등록으로 메뉴 이미지가 null인 경우와 등록된 메뉴의 필수 이미지 누락 오류를 구분한다.
메뉴 등록·수정 정책은 [가게 등록 진행 상태 설계](store-registration-progress.md#최종-등록-이후-설정)를 따른다.
조회 대상의 이미지가 없거나 아직 검증되지 않았으면 데이터 정합성 오류로 IllegalStateException을 전파한다.
이미지 누락을 null URL이나 빈 목록으로 반환하거나 해당 가게·메뉴를 결과에서 숨기지 않는다.
이 조회 검증은 온보딩 최종 등록·수정 시 필수 이미지를 검사하는 쓰기 검증을 대신하지 않는다.
기존 cdn.base-url과 object_key를 슬래시로 연결하며 객체 키는 UTF-8 URL 경로로 인코딩한다.
cdn.base-url에는 HTTP(S) 주소를 사용하며 쿼리, 프래그먼트, 사용자 인증 정보는 허용하지 않는다.
이미지가 실제로 존재하는데 cdn.base-url이 비어 있으면 IllegalStateException을 전파한다.
설정 누락을 이미지 누락으로 바꾸거나 object_key를 URL로 반환하지 않는다.
공개 CDN 주소를 반환하며 사전 서명 URL을 발급하거나 객체 스토리지에 조회 요청을 보내지 않는다.

## 조회와 테스트 구성

각 목록 조회는 이미지 테이블을 LEFT JOIN하는 일괄 SQL 한 번으로 처리해 이미지 누락도 서비스가 검증한다.
최종 등록된 본인 가게 여부는 store의 PK, owner_id와 finalized로 확인하며 다른 도메인 테이블을 읽지 않는다.
저장용 JPA 엔티티를 추가하지 않고 store 내부의 조회 repository가 JDBC로 기존 스키마를 읽는다.
단위 테스트는 이미지 변환과 장애 전파를 검증하고 통합 테스트는 실제 MySQL의 최종 등록 여부 필터, 누락 ID, 소유 관계, 이미지 누락 오류와 이미지 결합을 검증한다.
통합 테스트는 AbstractStoreIntegrationTest의 단일 @SpringBootTest 선언과 IntegrationTestSupport를 공유한다.
테스트 클래스별 프로퍼티, 프로파일, 컨테이너 선언은 추가하지 않는다.
테스트용 CDN 주소는 기존 공통 src/test/resources/application.properties 한 곳에서 설정한다.

## 광고 후보용 가게 일괄 조회 (#161)

`StoreQueryService.findAdCandidatesByStoreIds(List<Long> storeIds)`는 광고 후보 스냅샷용 가게를 조회한다.
`StoreAdCandidateInfo`는 storeId, storeName, categoryCode, storeImageUrl, latitude, longitude를 갖는다.
기존 `StoreInfo`와 `findAllById`의 계약은 유지한다.
업종 코드는 store.category_code의 문자열을 그대로 반환하며 스키마의 30자 제한을 따른다.
좌표는 DB의 DECIMAL(10,7)을 BigDecimal로 반환해 스냅샷에 복사할 때 정밀도를 유지한다.

최종 등록된 가게 중 두 좌표가 모두 있는 가게만 반환한다.
미존재, 등록 전, 위도나 경도가 하나라도 null인 가게는 제외하며 좌표를 0으로 대체하지 않는다.
현재 chk_store_finalized 제약은 최종 등록된 가게의 좌표 누락을 막으며 조회 SQL도 두 좌표의 존재를 명시한다.
0인 좌표는 누락으로 취급하지 않는다.
빈 입력, 중복, 누락 ID, 입력 검증과 DB 장애 전파는 기존 목록 조회 정책과 같다.
VERIFIED 상태의 LOGO를 LEFT JOIN하는 SQL 한 번으로 읽으며 이미지 URL과 이미지 누락 오류는 위 이미지 정책을 따른다.

할인 대상 메뉴명은 기존 `findMenusByIdIn`으로 메뉴 ID 목록을 일괄 조회한다.
광고 서빙은 캠페인의 target_menu_id에 해당하는 menuId로 menuName을 연결하고 storeId가 캠페인의 가게와 일치하는지 확인한다.
가게 조회에 메뉴를 조인하지 않으므로 메뉴가 여럿이어도 가게 결과가 늘어나지 않는다.
메뉴가 없는 가게도 가게 조회에 포함하며 메뉴 ID의 누락을 다른 메뉴명으로 대체하지 않는다.
메뉴 이미지 검증과 등록 전 메뉴 제외는 기존 메뉴 조회 계약을 따른다.
광고 서빙은 store와 store_image를 직접 읽지 않고 이 공개 계약을 사용한다.

## 분석용 신규·누적 입점 수 집계

이슈 #363의 공개 계약은 `StoreStatsQueryService.finalizedStoreCounts(LocalDate businessDate)`다.
분석의 일별 집계 배치가 사용하며 `FinalizedStoreCounts(newCount, cumulativeCount)`를 반환한다.

- `newCount`는 KST 영업일 00:00 이상, 다음 날 00:00 미만에 최종 등록한 가게 수다.
- `cumulativeCount`는 다음 날 00:00 미만까지 최종 등록한 가게 수다. 과거 날짜도 그날 말 기준으로 센다.
- `store.finalized=true`인 가게만 `finalized_at`으로 센다. 등록 중인 가게는 제외한다.
- 점주 상태와 관계없이 탈퇴한 점주의 가게도 포함한다. 점주 테이블을 조회하거나 조인하지 않는다.
- 대상이 없으면 두 수치는 0이다. 영업일이 null이면 NullPointerException을 던지고 DB 장애는 전파한다.

앱이 `ClockConfig.ZONE`으로 날짜 경계를 계산한다.
`finalized_at`은 KST 벽시계를 저장하는 DATETIME(6)이므로 경계도 LocalDateTime으로 전달한다.
서버 기본 시간대나 DB 날짜 함수에 의존하지 않고 SQL 한 번으로 두 수치를 함께 읽는다.
호출 날짜는 분석 배치가 확정된 영업일로 정하며 이 조회는 현재나 미래 날짜를 별도로 거부하지 않는다.

현재 store 구현에는 최종 등록을 취소하는 경로가 없다.
최종 등록 취소나 가게 삭제를 도입하면 과거 누적 수를 재현할 이력 정책도 함께 정해야 한다.
현재 집계는 등록 이력 원장을 대신하지 않는다.

## 관리자 점주 목록용 조회 (#365)

`findSummariesByOwnerIds(Collection<Long>)`는 점주 ID로 가게를 일괄 조회한다.
최종 등록 전 가게도 포함하며 가게가 없는 점주는 제외한다.
빈 입력은 DB 조회 없이 빈 목록을 반환하고 중복 ID는 결과를 늘리지 않는다.
반환 순서는 보장하지 않으며 ID 검증과 DB 장애 전파는 기존 계약과 같다.
입력 점주 ID는 중복을 포함해 최대 100개이며 초과하면 DB 조회 전에 IllegalArgumentException을 던진다.
한 페이지 최대 100명의 가게는 기존 owner_id 유니크 인덱스를 사용하는 SQL 한 번으로 읽는다.
이미지와 좌표는 조회하지 않으며 대표 이미지가 없는 등록 중 가게도 반환한다.

`StoreSummary`는 ownerId, storeId, name, finalized, businessRegistrationNumber를 갖는다.
사업자등록번호는 가운데 두 자리를 가려 `123**67890` 형식으로 반환한다.
아직 등록하지 않은 번호는 null로 유지한다.
원문은 store 내부 조회 결과에만 두고 공개 계약에는 전달하지 않는다.

`findOwnerIdsByNameContaining(String, int)`는 상호 부분 일치로 점주 ID를 반환한다.
최종 등록 여부로 제한하지 않으며 `%`, `_`, `!`는 와일드카드가 아닌 문자 그대로 검색한다.
검색어는 그대로 사용하며 null은 NullPointerException, 빈 문자열과 공백만 있는 검색어 및 1~10000 범위를 벗어난 limit는 DB 조회 전에 IllegalArgumentException으로 거절한다.
검색 결과가 없으면 빈 목록을 반환하고 순서는 보장하지 않는다.
SQL은 limit보다 한 건 더 읽고 초과하면 IllegalArgumentException을 던진다.
부분 결과를 반환하지 않으며 호출자는 검색어를 좁히도록 처리해야 한다.
사업자등록번호 마스킹과 검색 결과 초과 정책은 이슈 착수 시 결정한 사항이다.
기존 세 메서드와 소비자의 계약은 유지한다.

StoreQueryIntegrationTest에서 실제 MySQL의 등록 전 가게, 제한 초과와 누락 ID를 확인한다.
1만 건을 준비한 뒤 100명 요약 조회의 SELECT 한 번과 요약 및 상호 검색 각각 1초 이내를 검증한다.
시간 측정은 워밍업 이후 해당 테스트 환경에서의 결과다.
