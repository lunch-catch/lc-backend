# 캠페인 (campaign) API 설계

점주의 캠페인 등록 API를 서버가 어떻게 처리하는지와 그렇게 정한 이유를 적는다. 요청,
응답, 오류는 명세서 `campaign-api-spec.md`에 있고 여기서는 반복하지 않는다.

| 근거 | 기준 |
|---|---|
| 요구사항(캠페인 등록 1단계, 쿠폰 조건) | 명세서 V51 기능 43행 |
| 저장 구조 | `db/migration/campaign/V1__campaign_init.sql`의 `campaign` 테이블, `V3__campaign_draft_nullable.sql` |
| 결정 | `#244`(캠페인 생성, 쿠폰 조건 입력) 이슈에서 정한 것 |
| 설계 | `campaign_구조와_동작_설계.md`("맡지 않는 것: 등록 화면 API 1단계") |

## 목록과 요구사항 행

| 메서드 | 경로 | 행 |
|---|---|---|
| `POST` | `/v1/owner/campaigns` | 43 |

## 캠페인 등록(쿠폰 조건)

- **점주 상태를 `owner.contract.OwnerStatusQuery`로 확인한다.** `campaign`이 `owner`
  내부 엔티티나 예외 타입에 의존하지 않으려고 조회 결과(`OwnerAccountStatus`)만 받고,
  `ACTIVE`가 아니면 `CampaignErrorCode`의 자체 코드로 거부한다. `owner.exception.OwnerErrorCode.STATUS_NOT_ALLOWED`를
  그대로 쓰지 않는 이유가 이것이다(도메인 경계, 설계 문서 1.3)
- **가게당 `DRAFT` 상태 캠페인은 1개로 제한하고, 있으면 그 캠페인의 쿠폰 조건만
  덮어쓴다(`#244` 결정).** 새 요청마다 `CampaignRepository.findDraftByStoreId(storeId)`로
  먼저 찾는다. 있으면 쿠폰 조건 필드만 바꾸는 메서드(`Campaign.updateCouponCondition(...)`,
  `create()`와 별도)를 쓰고, 없으면 `Campaign.create()`로 새로 만든다. 노출 대상(3단계)과
  예산·기간(4단계) 필드는 덮어쓸 때 손대지 않는다
- **가게당 DRAFT 1개 제한을 DB 제약으로도 건다.** `campaign` 테이블은 이미 `status='ACTIVE'`인
  행에만 `store_id`를 채우는 생성 컬럼(`active_store_marker`)과 그 위의
  `uk_campaign_active_store` UNIQUE로 "가게당 ACTIVE 1건"을 보장한다(47행). 같은 방식으로
  `status='DRAFT'`일 때만 `store_id`를 채우는 생성 컬럼을 하나 더 두고 UNIQUE를 걸면,
  동시에 들어온 두 생성 요청 중 하나가 DB 제약으로 걸려 애플리케이션 레벨의 조회만으로는
  못 막는 경쟁을 막을 수 있다. 이 마이그레이션은 `#244`가 추가한다(명세 단계는 스키마를
  바꾸지 않는다)
- **노출 대상(3단계) 미입력분은 명세의 기본값을 그대로 쓴다.** `target_radius`(1000,
  1km)와 `target_gender`(`ALL`)는 `V1__campaign_init.sql`에 이미 DB `DEFAULT`로 있다.
  `target_age_groups`(전체 4개)도 `V3__campaign_draft_nullable.sql`에서 DB `DEFAULT`를
  추가했다. 다만 JPA는 매핑된 컬럼을 항상 명시해서 INSERT 하므로 DB `DEFAULT`는 실제로
  적용되지 않는다 — `Campaign.create()`를 호출하는 쪽(이 API)이 이 세 값을 직접 채워
  넘긴다
- **예산·기간(4단계)은 `null`로 둔다.** `daily_budget`/`start_date`/`end_date`는 이미
  nullable이고(`V3__campaign_draft_nullable.sql`), `Campaign.activate()`가 이 둘의
  null을 따로 확인한다(캠페인 엔티티와 상태 전이, `#176`). 활성화 이전에 반드시 채워야
  한다는 제약은 그 전이 쪽이 갖고, 이 API는 신경 쓰지 않는다
- **usableTime 검증(11:30~15:00, 30분 단위, 최소 1시간)은 서비스 레이어에 둔다.**
  `Campaign.create()`/`updateCouponCondition()`은 지금 `usableEndTime.isAfter(usableStartTime)`만
  본다(엔티티 불변식). 플랫폼 허용 범위와 30분 단위는 운영이 가진 값이 아니라 V51 43행에
  고정값으로 적힌 범위라, 이 API를 구현하는 서비스가 직접 검사한다

## 오류 코드를 나눈 기준

요청 형식(필수값, 타입)처럼 판정에 업무 지식이 필요 없는 실패는 `COMMON-002`를 쓴다.
점주 상태처럼 호출 주체의 상태를 봐야 하는 실패와, 할인율·수량·시간처럼 캠페인 업무
규칙을 봐야 하는 실패에만 `CAMPAIGN-` 코드를 쓴다.

## 아직 정해지지 않은 것

| 항목 | 요구사항 | 현재 스키마 또는 명세 |
|---|---|---|
| 가게당 DRAFT 1개를 막는 UNIQUE 제약의 정확한 마이그레이션 문구 | `#244` 결정 | `#244`가 `active_store_marker`와 같은 방식으로 추가한다(이 문서 "캠페인 등록" 절 참고) |
| `Campaign.updateCouponCondition(...)` 메서드 시그니처 | `#244` 결정 | 아직 엔티티에 없다. `#244`가 추가한다 |
| 노출 대상(44행)·예산과 집행 기간(45행) 단계의 API 계약 | V51 44, 45행 | 이 문서와 `campaign-api-spec.md`에 아직 없다. 그 단계를 구현하는 이슈가 절을 더한다 |
