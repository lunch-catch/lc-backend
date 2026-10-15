# 캠페인 (campaign) API 설계

점주의 캠페인 등록 API를 서버가 어떻게 처리하는지와 그렇게 정한 이유를 적는다. 요청,
응답, 오류는 명세서 `campaign-api-spec.md`에 있고 여기서는 반복하지 않는다.

| 근거 | 기준 |
|---|---|
| 요구사항(캠페인 등록 1단계, 쿠폰 조건) | 명세서 기능 FR-040 |
| 저장 구조 | `db/migration/campaign/V1__campaign_init.sql`의 `campaign` 테이블, `V3__campaign_draft_nullable.sql` |
| 결정 | `#244`(캠페인 생성, 쿠폰 조건 입력) 이슈에서 정한 것 |
| 설계 | `campaign_구조와_동작_설계.md`("맡지 않는 것: 등록 화면 API 1단계") |

## 목록과 요구사항 ID

| 메서드 | 경로 | ID |
|---|---|---|
| `POST` | `/v1/owner/campaigns` | FR-040 |
| `PATCH` | `/v1/owner/campaigns/{campaignId}` | FR-041, FR-042 |

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
  `uk_campaign_active_store` UNIQUE로 "가게당 ACTIVE 1건"을 보장한다(FR-044). 같은 방식으로
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
- **새로 만들면 `201`, 덮어쓰면 `200`을 응답한다(코드래빗 리뷰로 찾음).** 공통 규약(`api-spec.md`)이
  `200`은 수정 성공, `201`은 생성 성공으로 구분해 둔 것을 그대로 따른다. `CampaignRepository.findDraftByStoreId(storeId)`의
  존재 여부로 컨트롤러가 상태 코드를 가른다
- **usableTime 검증(11:30~15:00, 30분 단위, 최소 1시간)은 서비스 레이어에 둔다.**
  `Campaign.create()`/`updateCouponCondition()`은 지금 `usableEndTime.isAfter(usableStartTime)`만
  본다(엔티티 불변식). 플랫폼 허용 범위와 30분 단위는 운영이 가진 값이 아니라 FR-040에
  고정값으로 적힌 범위라, 이 API를 구현하는 서비스가 직접 검사한다

## 캠페인 등록(노출 대상, 하루 예산과 집행 기간)

- **한 `PATCH` 엔드포인트로 두 단계를 같이 받는다.** `api-spec.md`의 공통 규약이 "부분
  수정"의 대표 예시로 접미사 없는 `PATCH /v1/owner/campaigns/{campaignId}`를 그대로
  쓰고 있어서, 단계마다 경로를 쪼개지 않고 이 예시를 그대로 따른다(`#312` 결정)
- **`Campaign`에 노출 대상만 바꾸는 메서드와 예산·기간만 바꾸는 메서드를 각각
  추가한다(`#244`의 `updateCouponCondition(...)`과 같은 패턴).** 쿠폰 조건 필드는
  손대지 않는다
- **점주 상태(`ACTIVE`)는 `#244`와 같은 방식(`OwnerStatusQuery`)으로 확인한다.** 이
  엔드포인트에도 같은 이유(도메인 경계)가 적용된다
- **본인 소유 확인은 `Campaign.ownerId`와 로그인한 점주 id를 비교한다.** store 공개
  contract가 없어도 가능하다(`#244`의 TODO와는 별개 확인). 없거나 다르면 둘 다 같은
  403으로 묶는다(`api-spec.md` "권한이 없으면 존재 여부와 상관없이 403")
- **노출 대상은 `DRAFT`에서만, 하루 예산은 `DRAFT`·`ACTIVE` 둘 다, 집행 기간은 `DRAFT`에서만
  바꿀 수 있다.** `ACTIVE`에서 집행 기간 필드가 오면 값이 같아도 거부한다(조용히 무시하지
  않는다, `#312` 결정). V51 45행이 "집행 기간은 활성화 이후 바꿀 수 없다"로 명확히
  규정하고 있어, 의도치 않은 변경 시도를 프론트가 바로 알 수 있게 한다
- **`ACTIVE`에서 바꾼 하루 예산은 다음 영업일 00:00부터 적용된다(V51 45행, 102행
  스케줄러).** 그 값을 어느 컬럼에 어떻게 들고 있다가 스케줄러가 읽는지는 스키마 변경이라
  이 명세 단계에서 정하지 않는다("아직 정해지지 않은 것" 참고)
- **"추천 하루 예산"과 "예상 노출 범위"(V51 45행 H45)는 이 API의 범위 밖이다.** 광고
  서빙이 매일 00:00 배치로 집계해 넘기는 값이 있어야 계산할 수 있어, 저장·검증만 하는 이
  API와는 다른 기능이다
- **`#253`은 노출 대상(3단계)만 구현한다.** 하루 예산과 집행 기간(4단계)은 아직 구현
  이슈가 없어서, 요청에 `dailyBudget`/`startDate`/`endDate` 중 하나라도 오면
  `IllegalArgumentException`(`COMMON-002`)으로 거부한다(`CampaignTargetAudienceService.validateNoBudgetPeriodFields`,
  임시 결정). 4단계 구현 이슈가 생기면 이 검증을 그 기능으로 바꾼다
- **본인 소유 확인은 `campaignRepository.findByIdForUpdate(campaignId)`(비관 잠금)로
  조회한 뒤 `Campaign.ownerId`를 비교한다.** 처음에는 단순 필드 갱신이라 잠금 없는
  `findById`로 충분하다고 봤는데, 같은 캠페인에 거의 동시에 들어온 두 PATCH가 서로의
  변경을 조용히 지우는 lost update와, 00:00 스케줄러의 상태 전이와 경쟁하며 낡은
  스냅샷으로 "DRAFT가 아닌데 DRAFT로 착각"하는 경우가 둘 다 가능해 `CampaignStatusService.findOrThrow`와
  같은 이유로 비관 잠금으로 바꿨다(`#253` 자체 리뷰로 찾음)
- **`#330`이 하루 예산과 집행 기간(4단계)을 구현한다.** 노출 대상(3단계)과 같은 트랜잭션,
  같은 비관 잠금을 그대로 쓴다. 노출 대상과 예산·기간 두 묶음을 한 요청에 같이 보내도
  각자의 상태 조건을 독립적으로 검사한 뒤 둘 다 적용한다
- **노출 대상은 `DRAFT`에서만, 하루 예산은 `DRAFT`·`ACTIVE` 둘 다, 집행 기간은 `DRAFT`에서만
  바꿀 수 있다는 규칙을 "두 필드 묶음이 왔는지"와 "지금 상태가 그 묶음을 허용하는지"로
  나눠 검사한다.** 묶음 안의 필드가 일부만 오면 `COMMON-002`(형식 문제), 묶음은 다 왔지만
  지금 상태가 그 묶음을 허용하지 않으면 `CAMPAIGN-006`(업무 규칙)이다. `ACTIVE`에서
  `startDate`/`endDate` 중 하나라도 오면 값이 같아도 `CAMPAIGN-006`이고, `DRAFT`도
  `ACTIVE`도 아닌 상태(`SCHEDULED`/`PAUSED`/`ENDED`)에서 예산·기간 필드가 오면 역시
  `CAMPAIGN-006`이다
- **`ACTIVE`에서 바꾼 하루 예산은 `Campaign.pendingDailyBudget`(V13 마이그레이션)에만 쓰고
  `dailyBudget`은 그대로 둔다.** 응답은 항상 `dailyBudget`(바뀌기 전 값)을 돌려주고
  `pendingDailyBudget`은 노출하지 않는다("아직 정해지지 않은 것"에서 정함). 00:00 배치가
  이 값을 `dailyBudget`에 반영하는 쪽은 "스케줄러 캠페인 담당 스텝" 이슈 몫이다
- **최소 하루 예산은 요구사항명세서 [H27]의 1,000포인트를 그대로 쓴다.**
  `ops.contract.PlatformSettingQueryService.getMinDailyBudget()`을 새로 추가해
  `getImpressionUnitPrice()`와 같은 패턴(파싱 실패·0 이하 거부)으로 구현한다
- **집행 기간은 최소 1일 차이를 요구한다.** `endDate`가 `startDate`보다 이르거나 같으면
  `CAMPAIGN-008`이다. 시작일은 "오늘 다음 날 이후"만 허용해 `LocalDate.now(clock).plusDays(1)`보다
  이르면 `CAMPAIGN-009`다(요구사항명세서 [I45] 등록 마감 D-1 23:59와 같은 경계)

## 오류 코드를 나눈 기준

요청 형식(필수값, 타입)처럼 판정에 업무 지식이 필요 없는 실패는 `COMMON-002`를 쓴다.
점주 상태처럼 호출 주체의 상태를 봐야 하는 실패와, 할인율·수량·시간처럼 캠페인 업무
규칙을 봐야 하는 실패에만 `CAMPAIGN-` 코드를 쓴다.

## 아직 정해지지 않은 것

| 항목 | 요구사항 | 현재 스키마 또는 명세 |
|---|---|---|
| 가게당 DRAFT 1개를 막는 UNIQUE 제약의 정확한 마이그레이션 문구 | `#244` 결정 | `#244`가 `active_store_marker`와 같은 방식으로 추가한다(이 문서 "캠페인 등록" 절 참고) |
| `Campaign.updateCouponCondition(...)` 메서드 시그니처 | `#244` 결정 | 아직 엔티티에 없다. `#244`가 추가한다 |
| 00:00 배치가 `pendingDailyBudget`을 `dailyBudget`에 반영하는 호출 지점 | V51 45행, 102행 스케줄러 | `#330`은 컬럼에 쓰기만 한다. "스케줄러 캠페인 담당 스텝" 이슈가 그 반영 호출을 정한다 |
| 예약 값(`pendingDailyBudget`) 자체를 PATCH 응답에 노출할지 | V51 45행 | `#330`은 노출하지 않는다(응답은 늘 `dailyBudget`). 노출이 필요해지면 그때 명세를 바꾼다 |

해소된 항목(`#330`): 하루 예산의 "다음 영업일 적용 예약 값" 컬럼은 `Campaign.pendingDailyBudget`(`V13__campaign_pending_daily_budget.sql`)이고,
최소 하루 예산은 `ops.contract.PlatformSettingQueryService.getMinDailyBudget()`(요구사항명세서 [H27] 1,000포인트, `V4__ops_min_daily_budget.sql` 시드)이다.
