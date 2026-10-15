# 가게와 메뉴 조회 공개 계약

이슈 #297의 쿠폰용 조회 계약이다. 공개 타입은 store.contract에 두며 구현은 store.service에 둔다.
HTTP API와 온보딩 저장 기능은 추가하지 않는다.

## 반환 정책

| 메서드 | 동작 |
|---|---|
| findAllById | 가게 ID 목록으로 현재 가게명, 대표 이미지 URL, 좌표를 조회한다 |
| findMenusByIdIn | 메뉴 ID 목록으로 현재 메뉴의 소속 가게 ID, 이미지 URL, 이름, 원 단위 할인 전 가격을 조회한다 |
| canManageStore | store.owner_id와 요청 ownerId가 일치하면 true를 반환한다 |

목록에서 존재하지 않는 ID는 제외하고 조회 결과가 없으면 빈 목록을 반환한다.
빈 입력 목록도 빈 목록을 반환한다. 중복 ID는 결과를 늘리지 않으며 반환 순서는 보장하지 않는다.
입력 목록과 각 ID는 null을 허용하지 않고 ID는 양수여야 한다.
목록 자체가 null이면 NullPointerException, 목록 원소 또는 단건 ID가 유효하지 않으면 IllegalArgumentException을 던진다.
DB 장애는 빈 목록이나 false로 바꾸지 않고 예외로 전파한다.

가게 조회는 finalized 여부로 필터링하지 않는다.
LOCATION 저장 전 가게도 반환하므로 latitude와 longitude 각각은 null일 수 있다.
호출자는 두 좌표가 모두 있는지 확인해야 한다.
쿠폰의 QR 위치 검사는 좌표가 하나라도 없으면 위치 검사를 통과시키지 않아야 한다.
광고 후보처럼 좌표가 필수인 소비자는 좌표가 없는 가게를 후보에서 제외해야 한다.
좌표 누락을 0으로 치환하거나 가게 미존재로 취급하지 않는다.

canManageStore는 현재 소유 관계만 판단한다.
가게 미존재와 다른 점주 소유는 모두 false이며 온보딩 진행 상태는 조회하지 않는다.
최종 등록 여부나 점주 계정 상태를 판단하는 기능이 아니므로 쿠폰 사용 가능 여부 전체를 대신하지 않는다.
쿠폰은 인증된 점주 ID를 전달하고 자신의 업무에 필요한 상태 조건을 별도로 확인해야 한다.
발급된 쿠폰의 표시는 과거 스냅샷을 사용하고 소유 관계와 QR 검사 좌표는 현재 값을 사용한다.

## 이미지 URL

가게 이미지는 LOGO 중 VERIFIED만 반환하고 메뉴 이미지도 VERIFIED만 반환한다.
이미지가 없거나 아직 검증되지 않았으면 URL은 null이다. 이미지 누락으로 가게나 메뉴를 제외하지 않는다.
기존 cdn.base-url과 object_key를 슬래시로 연결하며 객체 키는 UTF-8 URL 경로로 인코딩한다.
cdn.base-url에는 HTTP(S) 주소를 사용하며 쿼리, 프래그먼트, 사용자 인증 정보는 허용하지 않는다.
이미지가 실제로 존재하는데 cdn.base-url이 비어 있으면 IllegalStateException을 전파한다.
설정 누락을 이미지 누락으로 바꾸거나 object_key를 URL로 반환하지 않는다.
공개 CDN 주소를 반환하며 사전 서명 URL을 발급하거나 객체 스토리지에 조회 요청을 보내지 않는다.

## 조회와 테스트 구성

각 목록 조회는 이미지 테이블을 LEFT JOIN하는 일괄 SQL 한 번으로 처리한다.
소유 관계는 store의 PK와 owner_id로 확인하며 다른 도메인 테이블을 읽지 않는다.
저장용 JPA 엔티티를 추가하지 않고 store 내부의 조회 repository가 JDBC로 기존 스키마를 읽는다.
단위 테스트는 이미지 변환과 장애 전파를 검증하고 통합 테스트는 실제 MySQL의 누락 ID, 소유 관계, 이미지 결합을 검증한다.
통합 테스트는 AbstractStoreIntegrationTest의 단일 @SpringBootTest 선언과 IntegrationTestSupport를 공유한다.
새 테스트 프로퍼티, 프로파일, 컨테이너 선언을 추가하지 않는다.

#161의 광고 서빙용 업종 코드 확장은 이 계약에 포함하지 않는다.
같은 인터페이스를 확장할 때 기존 소비자와 record 필드 변경을 함께 검토해야 한다.