# 가게 목록 조회와 캠페인 요약 저장

요청과 응답은 [store-api-spec.md](../../api-spec/store-api-spec.md)가 소유한다.
이 문서는 이슈 #331의 조회 구현과 다른 도메인에 공개하는 요약 저장 계약을 정의한다.

## 조회 구조

`StoreController`는 요청을 검증하고 `StoreListService`를 호출한다.
`StoreRepository`는 JpaRepository와 StoreRepositoryCustom을 확장한다.
`StoreRepositoryImpl`은 쿠폰 조회와 같은 Querydsl 방식으로 조건과 조인, 정렬을 구성한다.
조회 대상은 기존 가게·메뉴 테이블과 가게 도메인이 소유한 캠페인 요약이다.
가게·메뉴·캠페인 요약 엔티티에는 목록 조회에 필요한 컬럼만 매핑하고 @Immutable을 적용한다.
이 엔티티는 조회에만 사용하며 생성 팩터리와 상태 변경 메서드를 두지 않는다.

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


## 점주 약관 동의

계약은 [store-api-spec.md](../../api-spec/store-api-spec.md#점주-약관-동의)를 따른다.
OwnerTermsSubmissionService가 쓰기 트랜잭션에서 점주 행을 잠그고 ONBOARDING 상태를 확인한다.
owner는 store.contract.OwnerTermsAgreementRecorder를 호출하며 store 내부 타입을 참조하지 않는다.
OwnerTermsAgreementService는 진행 상태를 초기화·잠금하고 요청 버전을 비교한 뒤 약관을 검증한다.
잠금 순서는 점주 행, 진행 상태 행, 약관 데이터 순이다.
현재 종류·버전의 기존 행은 수정하고 새 버전은 새 행으로 저장한다. 과거 버전은 유지한다.
created_at은 최초 제출 시점이며 updated_at은 최근 수정 시점이다.
필수 약관은 기존 저장 내역과 이번 입력을 합쳐 현재 정책으로 검증한다. 기존의 현재 버전·미철회 동의는 인정하되 필수 약관의 명시적 미동의는 거부한다. 생략한 현재 버전의 선택 약관은 미동의로 저장한다.
저장 후 현재 정책의 필수 동의를 다시 확인하고 TERMS에서는 completeStep, 이후 단계에서는 reviseStep을 호출한다.
약관과 진행 상태의 실패는 같은 트랜잭션에서 전부 롤백한다.
진행 상태가 없는 기존 동의자에게 현재 약관 전체의 재제출을 강제하지 않는다. 약관 저장 처리에서는 유효한 기존 동의를 인정한다. 기존 동의자의 진행 상태 자동 생성·이관 정책은 별도로 확정한다.
이력만으로 GET에서 진행 상태를 생성하거나 완료 처리하지 않는다.
공개 계약은 점주 ID, 요청 버전, TermsAgreementCommand 목록을 받고 TermsAgreementResult로 완료 여부·새 버전·재개 단계를 반환한다.
호출자는 점주 행 잠금과 현재 상태 확인을 보장하며 구현은 Propagation.MANDATORY로 참여한다.

### 서버 약관 정책


`store.owner-terms.policies`에 종류별 현재 버전과 필수 여부를 환경 설정으로 주입한다. 기본값은 빈 목록이며, 정책을 확정하기 전에는 완료 처리하지 않는다.
아래는 설정 형식 예시이며 저장소의 기본 정책으로 적용하지 않는다.

```yaml
store:
  owner-terms:
    policies:
      - terms-type: SERVICE
        terms-version: '1.0'
        required: true
      - terms-type: MARKETING
        terms-version: '1.0'
        required: false
```

Spring Boot의 환경별 YAML 또는 `SPRING_APPLICATION_JSON` 등 표준 설정 주입 방식을 사용한다.
종류별 현재 정책은 하나이고 전체 최대 100개다. 종류·버전은 앞뒤 공백을 허용하지 않고, 필수 여부 누락 및 중복 종류 설정은 기동 시 거부한다.
약관 내용이나 필수 여부가 바뀌면 버전을 올린다. 기존 종류·버전의 의미를 바꾸거나 DB collation상 동일한 식별자를 다른 약관으로 재사용하지 않는다.
모든 인스턴스에 동일한 정책을 배포해야 한다. 약관 본문 제공·정책 관리 API는 이번 범위에 포함하지 않는다.
