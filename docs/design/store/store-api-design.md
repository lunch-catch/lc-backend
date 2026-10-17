# 가게 목록 조회와 캠페인 요약 저장

요청과 응답은 [store-api-spec.md](../../api-spec/store-api-spec.md)가 소유한다.
이 문서는 이슈 #331의 조회 구현과 다른 도메인에 공개하는 요약 저장 계약을 정의한다.

## 조회 구조

`StoreController`는 요청을 검증하고 `StoreListService`를 호출한다.
`StoreRepository`는 JpaRepository와 StoreRepositoryCustom을 확장한다.
`StoreRepositoryImpl`은 쿠폰 조회와 같은 Querydsl 방식으로 조건과 조인, 정렬을 구성한다.
조회 대상은 기존 가게·메뉴 테이블과 가게 도메인이 소유한 캠페인 요약이다.
메뉴·캠페인 요약 엔티티에는 목록 조회에 필요한 컬럼만 매핑하고 @Immutable을 적용한다.
Store는 STORE_INFO 저장에도 사용하므로 변경 가능한 엔티티이며 초안 생성 팩터리와 기본 정보 교체 메서드를 가진다.

검색어는 그대로 부분 일치 검색에 사용하며 SQL의 `%`, `_`, `!`는 리터럴로 취급한다.
메뉴 검색은 EXISTS로 처리하므로 여러 메뉴가 일치해도 가게가 중복되지 않는다.
카테고리는 검증한 enum을 바인딩하며 전체 선택은 카테고리 조건을 생략한다.
Querydsl의 contains가 검색어를 이스케이프하며 사용자 입력은 모두 파라미터로 바인딩한다.

거리는 MySQL의 ST_Distance_Sphere로 계산한다.
POINT에는 경도와 위도를 순서대로 전달한다.
경도 -180도는 MySQL 함수의 범위에 맞게 같은 위치인 180도로 정규화하며 원본 응답 좌표는 유지한다.
거리와 가게 ID로 커서 조건을 적용하고 페이지 크기보다 한 개 더 조회하여 다음 페이지를 판단한다.
Repository는 StoreListRow로 필요한 값과 반올림 전 거리를 반환한다.
Service는 다음 페이지 여부와 커서를 결정하고 응답 DTO의 from 메서드로 표시 값을 변환한다.
전체 가게를 애플리케이션 메모리에 올리거나 전체 건수를 세지 않는다.

커서는 기존 PageTokens에 가게 ID와 반올림 전 거리의 Double 문자열을 담는다.
거리의 문자열은 Double.toString으로 만들어 다음 요청에서 같은 값으로 복원한다.
잘못된 토큰, 양수가 아닌 ID, 음수·NaN·무한대 거리는 IllegalArgumentException으로 거부한다.
검색 조건이나 좌표가 바뀌면 클라이언트가 첫 페이지부터 요청한다.
커서는 조회 결과의 스냅샷을 저장하지 않는다.

당일 ACTIVE 요약을 가게와 일괄 결합하고 할인 대상 메뉴의 현재 이름과 가격을 읽는다.
영업일은 공통 Clock 빈의 서울 기준 날짜로 판단한다.
현재 활성 캠페인을 가게당 하나로 유지하는 책임은 캠페인 상태 변경을 처리하는 호출자에게 있다.
요약을 전달할 때 이전 캠페인의 종료와 다음 캠페인의 활성 상태를 같은 트랜잭션에서 반영해야 한다.

최종 등록 가게의 좌표 또는 활성 MENU 캠페인의 메뉴가 누락되면 데이터 정합성 오류를 전파한다.
누락된 데이터를 0이나 정상적인 캠페인 미보유 상태로 바꾸지 않는다.
가게 대표 이미지와 메뉴 이미지는 이 목록 응답에 필요하지 않으므로 기존 이미지 검증용 공개 조회 계약을 호출하지 않는다.

## 요약을 저장하는 공개 계약

`store.contract.StoreCampaignSummaryUpdater.update(storeId, campaignId, command)`를 공개한다.
`StoreCampaignSummaryCommand`와 그 안의 상태·할인 enum도 store.contract가 소유한다.
전달 필드와 타입의 단일 기준은 해당 record이며 캠페인 도메인의 내부 타입을 받지 않는다.

배치는 같은 contract의 updateAll(List<StoreCampaignSummaryUpdate>)을 호출한다.
StoreCampaignSummaryUpdate는 가게 ID, 캠페인 ID와 기존 command를 묶은 일괄 입력 항목이다.
빈 목록은 DB에 접근하지 않으며 null 목록과 null 항목은 거부한다.
모든 항목의 필수값을 검증한 뒤 500개씩 나누어 메뉴 소속을 일괄 조회하고 여러 행을 한 SQL로 upsert한다.
한 묶음은 메뉴 할인이 있으면 조회 1회와 저장 1회, 전체 할인만 있으면 저장 1회를 실행한다.
배치 전체는 같은 저장 시각과 호출자 트랜잭션을 사용하며 어느 묶음이든 실패하면 앞서 저장한 묶음도 롤백한다.

구현은 `StoreCampaignSummaryUpdaterImpl`이며 호출자의 쓰기 트랜잭션에 MANDATORY로 참여한다.
가게·캠페인 ID, 필수값, 할인 값, 사용 가능 시간과 할인 대상 메뉴의 소속을 검증한다.
같은 영업일과 캠페인에 대해 요약이 없으면 생성하고 있으면 갱신한다.
생성 시각은 기존 값을 보존하고 갱신 시각은 공통 Clock에서 계산한다.

캠페인 담당은 상태 변경과 일일 요약 준비 시 이 계약을 호출한다.
당일 행을 매일 준비해야 상태가 바뀌지 않은 캠페인도 날짜가 바뀐 뒤 조회할 수 있다.
이 이슈는 저장 계약과 구현만 제공하며 캠페인 쪽 호출 지점이나 배치 작업을 수정하지 않는다.

## 저장 구조와 배포

V3 마이그레이션은 store_campaign_summary에 nullable인 target_menu_id와 메뉴 FK를 추가한다.
가게·영업일·상태 결합 조건에는 복합 인덱스를 추가한다.
메뉴 이름과 가격은 복사하지 않고 현재 store_menu에서 조회한다.

기존 MENU 요약의 메뉴 ID를 가게 도메인에서 복원할 수 없으므로 마이그레이션이 임의로 채우지 않는다.
기존 MENU 요약이 있는 환경은 캠페인 담당이 updater로 요약을 다시 전달해야 한다.
호출 연동 전에는 실제 캠페인 상태가 목록에 반영되지 않으므로 캠페인 기능까지 제공하는 배포에는 호출 연동이 필요하다.

## 검증

서비스 단위 테스트는 기본 조회 조건, 커서 검증과 요약 저장 검증을 확인한다.
StoreListIntegrationTest 하나에서 실제 MySQL의 거리 정렬과 반올림, 동점 커서, 다중 필터와 검색을 확인한다.
같은 테스트에서 캠페인·메뉴 응답, 공통 보안 체인, MEMBER 권한과 updater의 저장·롤백도 검증한다.
일괄 저장은 500개 메뉴 요약의 실제 SELECT·INSERT 실행 횟수와 기존 행 갱신, 메뉴 검증 실패와 롤백을 확인한다.

## STORE_INFO 저장 (#415)

HTTP 계약은 [store-api-spec.md](../../api-spec/store-api-spec.md#가게-등록-온보딩-2단계-기본-정보와-사업자-mock-검증)가 소유한다.
owner의 OwnerStoreInfoController는 인증 주체의 ID와 입력값을 OwnerStoreInfoService에 전달한다.
OwnerStoreInfoService가 점주를 확인한 뒤 store.contract.StoreInfoSaver를 호출한다.
StoreInfoService는 기존 StoreRegistrationProgressService의 완료·수정 메서드를 호출한다.

### 접근 확인과 트랜잭션

owner는 이미 내 정보 조회에서 store의 진행 상태 계약을 사용한다.
점주 상태 확인과 호출 순서는 owner의 서비스가 담당하며 가게 입력 검증과 저장은 store가 담당한다.
OwnerStoreInfoService는 쓰기 트랜잭션을 시작하고 점주 행을 잠근 뒤 DB의 ONBOARDING 상태를 확인한다.
상태 캐시와 토큰의 상태값은 사용하지 않는다.
상태 확인 후 store가 공개한 StoreInfoSaver.save를 호출하며 StoreInfoService가 그 계약을 구현한다.
StoreInfoService는 MANDATORY로 같은 트랜잭션에 참여하며 점주 데이터를 읽거나 owner의 구현을 호출하지 않는다.
잠금 순서는 점주 행 → 진행 상태 행 → 가게 데이터이며 의존 방향은 기존 owner → store를 유지한다.
가게의 유일한 owner_id와 진행 상태의 버전 검사로 동시 최초 요청의 중복 생성을 막는다.
단계·버전 검사 후 입력값과 Mock 결과를 확인하며 가게 저장과 진행 상태 갱신은 한 트랜잭션이다.

### 저장과 수정

최초 성공은 Store.createDraft로 finalized=false인 가게를 생성하고 saveAndFlush로 ID를 확보한다.
이어서 completeStep으로 가게 소유권을 확인하고 LOCATION으로 진행한다.
완료 단계 재저장은 연결된 본인 가게만 수정하고 reviseStep으로 버전만 증가시킨다.
StoreBasicInfo 값 객체가 필수 업무 입력의 불변식을 검증한다.
기본 정보 교체는 검증 성공 시각을 Clock으로 기록하며 다른 단계 데이터는 건드리지 않는다.

Mock 실패는 상태 변경 없이 결과만 반환한다.
따라서 최초 실패 초안을 저장하기 위한 DB 제약 변경이 필요하지 않다.
DB 장애·진행 상태 갱신 실패는 성공으로 바꾸지 않으며 가게 정보와 함께 롤백한다.
현재 Mock은 순수 계산으로 외부 의존이 없다. 실제 기관 연동 시 외부 호출은 DB 잠금 밖으로 분리해야 한다.

### 검증

StoreInfoServiceTest와 StoreBasicInfoTest가 입력 검증·최초 생성·수정·Mock 실패·진행 상태 오류를 확인한다.
OwnerStoreInfoServiceTest가 점주 상태·계정 누락·DB 장애와 저장 계약 호출을 확인한다.
StoreInfoIntegrationTest는 HTTP 보안·실제 저장·다른 단계 데이터 보존·동시 요청·후속 갱신 실패의 롤백을 확인한다.

### 가게 기본 정보 저장 공개 계약

StoreInfoSaver와 입출력 record인 StoreInfoSaveCommand·StoreInfoSaveResult는 store.contract가 소유한다.
StoreInfoService가 자기 도메인의 공개 계약을 구현하며 owner는 이 계약만 참조한다.
호출자는 같은 쓰기 트랜잭션에서 본인 점주 행을 먼저 잠그고 ONBOARDING 상태를 확인해야 한다.
트랜잭션이 없으면 MANDATORY가 호출을 거부한다.
공개 입출력 타입은 HTTP DTO나 내부 엔티티를 참조하지 않는다.
진행 단계와 검증 결과는 문자열로 전달하고 owner의 HTTP 응답 DTO가 결과를 변환한다.
가게 기본 정보와 Mock 판정 규칙은 store 내부에 유지하며 공개 명령을 받은 뒤 검증한다.
