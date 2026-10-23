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
| `POST` | `/v1/owner/campaigns/{campaignId}:activate` | FR-044 |
| `POST` | `/v1/owner/campaigns/{campaignId}:pause` | FR-092 |
| `POST` | `/v1/owner/campaigns/{campaignId}:resume` | FR-092 |
| `POST` | `/v1/owner/campaigns/{campaignId}:cancel` | FR-092(요구사항명세서 상태 시트의 SCHEDULED "취소(DRAFT로)", `#571`) |
| `GET` | `/v1/owner/campaigns` | 없음(요구사항명세서에 조회 전용 행이 없다. 점주가 등록한 값을 다시 확인하는 화면 전제, `#341`) |
| `GET` | `/v1/owner/campaigns/{campaignId}` | 없음(위와 같음) |

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
- **요청의 `storeId`와 `targetMenuId`가 로그인한 점주의 것인지 저장 전에 확인한다(`#874`).** `storeId`는 클라이언트가 보낸 값이라 `StoreQueryService.canManageStore(ownerId, storeId)`로 본인의 최종 등록된 가게인지 본다. 없는 가게, 남의 가게, 등록 중인 가게는 같은 `CAMPAIGN-013`(403)이라 존재 여부를 알려주지 않는다. `MENU` 할인이면 `findMenusByIdIn`의 정상 목록에 `targetMenuId`가 있고 그 메뉴의 `storeId`가 요청의 `storeId`와 같은지 본다. 없는 메뉴, 다른 가게의 메뉴, 이미지가 없거나 검증되지 않은 메뉴는 정상 목록에 들어오지 않으므로 한 번에 `CAMPAIGN-014`(422)로 거절한다. `MENU`인데 `targetMenuId`를 아예 보내지 않은 것은 필수값 누락이라 이 확인을 하지 않고, 엔티티 검증이 `COMMON-002`(400)로 거절한다(이 변경 전과 같다). 두 확인은 다른 도메인의 조회라 트랜잭션 밖에서 끝낸다. 같은 가게의 기존 `DRAFT`를 덮어쓸 때도 그 `DRAFT`의 `ownerId`가 로그인한 점주인지 확인한다. 다르면 소유 확인이 생기기 전에 만들어진 데이터라 덮어쓰지 않고 `CAMPAIGN-005`로 거절하며 WARN 로그를 남긴다
- **점주는 `ENDED`가 아닌 캠페인을 동시에 하나만 가질 수 있다(`#525`, 피드백 리뷰로
  나온 요구사항).** 새로 만드는 경로(`create()`)에서만
  `CampaignRepository.existsByOwnerIdAndStatusNotIn(ownerId, List.of(ENDED))`로 확인한다.
  같은 가게의 `DRAFT`를 다시 저장하는 덮어쓰기 경로는 새 캠페인을 만드는 게 아니라서 이
  확인을 타지 않는다
  - **`DRAFT`도 "진행 중"으로 본다.** 아직 제출 전인 초안이라도 점주가 동시에 두 캠페인을
    끌고 가는 걸 막는다는 의도에는 걸린다. billing의 `hasLiveCampaign`(환불 차단용,
    `DRAFT`·`ENDED` 제외)과 범위가 다른 건 이 때문이고, 그쪽 기준을 그대로 가져오지
    않았다
  - **등록 1단계(새 캠페인 생성 시점)에서 막는다.** 활성화 시점까지 기다리면 점주가
    이미 입력을 다 마친 뒤에야 거부당해 더 큰 손해다. 가장 일찍 걸 수 있는 자리에서 막는다
  - **점주당 가게가 1개뿐이라(`uk_store_owner`) 별도 잠금을 추가하지 않는다.** 두 생성
    요청이 동시에 들어와도 가게가 하나뿐이라 결국 같은 `uk_campaign_draft_store` 경쟁으로
    수렴하고, 기존 `overwriteAfterConflict` 경로가 그 경쟁을 이미 처리한다. 이 확인은
    그 경쟁 밖(둘 다 진행 중 캠페인이 전혀 없을 때만 통과)에서 한 번 더 거르는 것뿐이다
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

## 캠페인 활성화(점주)

- **`DRAFT` → `SCHEDULED` 전이는 기존 `CampaignStatusService.schedule(campaignId)`를 그대로
  부른다(FR-092).** 이미 구현되어 있고 호출하는 곳이 없었다. `Campaign.schedule()`이
  `DRAFT`가 아니면 `IllegalStateException`을 던지는 것도 그대로 쓴다
- **이 흐름 전체를 하나의 트랜잭션으로 묶지 않는다(자체 리뷰로 찾음, `#561`).**
  `PosterModerationService.moderate()`가 S3·Rekognition을 부르는데, 그 호출을
  트랜잭션 안에서 하면 그 시간만큼 캠페인 행 잠금과 DB 커넥션을 쥐고 있게 된다.
  `PosterModerationService`의 클래스 주석이 이미 트랜잭션을 걸지 않는 이유로 든 것과 같은
  문제라, 세 구간으로 나눈다.
  1. 잠금 없는 `findById`로 본인 소유(`ownerId` 비교)·점주 계정 상태(`OwnerStatusQuery`)·
     `DRAFT` 여부를 먼저 거른다. 없거나 다르면 `CAMPAIGN-005`, 점주가 `ACTIVE`가 아니면
     `CAMPAIGN-001`, `DRAFT`가 아니면 `CAMPAIGN-006`이다. 여기서 걸러지면 검수를 부르지
     않는다.
  2. `CampaignActivationRateLimiter`로 점주 1명당 요청 빈도를 센 뒤(아래 항목)
     `PosterModerationService.moderate()`를 트랜잭션 밖에서 부른다.
  3. 검수가 `PASS`면 `CampaignStatusService.schedule(campaignId)`를 부른다. 이 메서드는
     이미 자기 트랜잭션 안에서 `findByIdForUpdate`(비관 잠금)로 다시 잠그고 `DRAFT` 여부를
     다시 확인한다. 1번과 3번 사이에 다른 요청이 먼저 전이를 끝내면 여기서
     `IllegalStateException`이 나고, `CAMPAIGN-006`으로 변환해 응답한다
     (`CampaignPauseResumeService.pause()`/`resume()`과 같은 처리).
  1번의 확인은 최종 권위가 아니다. 1번을 통과한 두 요청이 거의 동시에 들어오면 둘 다 검수까지
  진행할 수 있지만, 3번에서 먼저 도착한 쪽만 실제로 `SCHEDULED`가 되고 나머지는 거기서
  거부된다. 같은 캠페인에 대한 중복 전이는 막되, 그 틈에 생기는 중복 검수 호출(낭비)은
  감수하는 선택이다. 그 경쟁이 실제로 자주 일어날 일은 아니라서(점주가 같은 캠페인을 거의
  동시에 두 번 누르는 경우) 더 무거운 해법(짧은 트랜잭션을 더 쪼개거나 `@Version` 낙관적
  잠금으로 바꾸는 것)까지는 가지 않았다
- **검수가 끝난 뒤 `schedule()`의 캠페인 잠금을 잡기 전까지 포스터가 바뀔 수 있어, 검수
  결과가 지금 포스터의 것인지 그 잠금 아래서 다시 확인한다(코드래빗 리뷰로 찾음,
  `#561`).** 검수(2번 구간)는 트랜잭션 밖에 있어서 포스터 잠금을 쥐지 않는다. 그 사이
  점주가 `PosterUploadService`로 같은 캠페인의 포스터를 덮어쓸 수 있는데(그 쪽도 캠페인
  잠금을 거친다), `Poster.overwrite()`는 새 행을 만들지 않고 같은 `posterId`의 내용만
  바꿔서 포스터 ID만으로는 그 변경을 알 수 없다. 그래서 `CampaignStatusService.schedule()`에
  `Runnable precondition`을 받는 오버로드를 추가했다. 그 메서드가 캠페인 잠금을 잡은
  뒤, 상태를 바꾸기 전에 precondition을 실행한다. `CampaignActivationService`는 검수를
  부르기 전에 포스터의 `updatedAt`을 읽어 두고, 이 precondition으로 캠페인 잠금 아래서
  (포스터 덮어쓰기도 같은 잠금을 거치므로 그 사이엔 못 바뀐다) 지금 값과 같은지 다시
  확인한다. 다르면 `CAMPAIGN-006`으로 거부하고 전이하지 않는다. `CampaignStatusService`는
  이 precondition이 무엇을 확인하는지 모른다. 포스터를 몰라도 되게 하려는 것이다
- **점주 1명당 캠페인 활성화 요청을 분당 3회로 제한한다(`CampaignActivationRateLimiter`,
  자체 리뷰로 찾음, `#561`).** 검수가 `FAIL`이어도 점주가 다시 요청하면 매번 새로
  Rekognition을 부르는데(아래 항목), 반복 요청을 막지 않으면 그 호출 비용과 쿼터를 그대로
  소모한다. `TemplateGenerationRateLimiter`와 같은 고정 창 카운터(`auth_rate_limit.lua`
  재사용)를 쓰고, 넘으면 `CAMPAIGN-011`(429)이다. 분당 3회는 근거 없는 예시값이고(`C` 등급),
  실제 값은 운영 쪽과 정한다("아직 정해지지 않은 것" 참고). Redis 장애 시에는 세지 못하는
  것뿐이지 막을 이유가 없어 통과시킨다(`장애_처리_설계_가이드.md`, 캐시 기본 처리)
- **포스터 존재 확인은 `PosterRepository.findByCampaignId`로 하고, 없으면
  `PosterErrorCode.POSTER_NOT_SAVED`(`POSTER-020`, 404)로 거부한다.**
  `PosterModerationService.moderate()`가 같은 조회에서 쓰는 코드를 그대로 재사용한다. 새
  코드를 추가하지 않는다
- **자동 검수는 `PosterModerationService.moderate(campaignId)`를 그대로 부른다(FR-054,
  `#535`).** 그 결과(`PosterModerationOutcome`)의 `result`가 `PASS`면 `schedule()`을
  부르고, `FAIL`이면 `schedule()`을 부르지 않고 그 `reasons`를 응답에 담는다
- **검수 `FAIL`도 `200 SUCCESS`로 응답한다(채팅으로 확인, 2026-10-19).** 여러 사유를
  동시에 모두 담아야 하는데(`PosterModerationService`의 "사유는 하나에서 멈추지 않고 모두
  모은다") 공통 응답 봉투의 오류 경로는 `data`가 항상 `null`이라 담을 수 없다. 쿠폰
  선착순 소진(`COUPON-003`)처럼 사유가 하나로 좁혀지는 경우와 달리, 이 기능은 사유가
  복수라 성공 봉투 안의 `reasons` 배열로 간다
- **`reasons` 배열 값은 `ModerationFailReason` enum 코드 원문이다(채팅으로 확인,
  2026-10-19).** 그 enum의 클래스 주석이 이미 "화면 문구는 이 코드를 받는 쪽(캠페인
  활성화)이 붙인다"고 적어 둬서, 서버가 번역하지 않고 그대로 내려주는 쪽이 원래 의도와
  맞다
- **엔드포인트 경로는 `api-spec.md`의 커스텀 메서드 컨벤션 예시(`:activate`)를 그대로
  쓴다.** "캠페인 중단과 재개"와 같은 선택이다
- **잔액은 확인하지 않는다(FR-044 예외사항).** 캠페인이 정산을 조회하면 정산이 이미
  캠페인을 조회하고 있어 도메인 간 순환이 생긴다(설계 문서 2.2). 잔액 부족 안내는 프론트가
  정산 조회를 따로 불러서 화면에 표시한다
- **`SCHEDULED` → `ACTIVE` 전환과 가게당 동시 1건 제한은 이 엔드포인트가 하지 않는다.**
  D일 00:00 스케줄러(`SYSTEM`)가 `CampaignStatusService.activate()`를 불러 처리한다
  (`campaign_구조와_동작_설계.md`). 이 엔드포인트는 `schedule()`만 부른다
- **`PAUSED`(`ADMIN`) 캠페인의 재개는 이 엔드포인트가 다루지 않는다.** `DRAFT`가 아닌
  상태에서의 요청은 전부 `CAMPAIGN-006`으로 막히므로 재개 기능과 분리된다
- **이 설계는 `#535`(PR #552)가 아직 `develop`에 없는 상태에서 썼다.**
  `PosterModerationService`, `PosterModerationOutcome`, `ModerationFailReason`은
  `feat/535-poster-moderation` 브랜치 기준이다. 구현(`#561`)은 `#535`가 먼저 병합된 뒤
  진행한다

## 캠페인 종료(점주)

- **종료도 `CampaignPauseResumeService`가 맡는다(`#600`).** 점주 상태 확인, 본인 소유 확인
  (`findByIdForUpdate`와 `ownerId` 비교, 실패하면 `CAMPAIGN-005`), 엔티티의
  `IllegalStateException`을 `CAMPAIGN-006`으로 바꾸는 방식이 중단과 재개와 같다. 실제
  전이는 `CampaignStatusService.end(campaignId, ActorType.OWNER, businessDate)`에 맡긴다.
  기존 `end(campaignId, businessDate)`는 정기 종료용으로 `ActorType.SYSTEM`을 넘겨 그대로 둔다
- **점주는 `PAUSED` 캠페인만 종료할 수 있다.** 진행 중(`ACTIVE`)인 캠페인은 먼저 중단하고 종료하는
  순서(`ACTIVE` -> `PAUSED` -> `ENDED`)를 지킨다. 실수로 진행 중인 캠페인을 바로 끝내는 일을
  막고, 중단으로 노출을 멈춘 뒤 종료를 한 번 더 확인하게 하려는 결정이다. 집행 기간이 끝난
  정기 종료(`#569`)는 `ACTIVE`에서도 종료하므로 엔티티의 `Campaign.end()`는 `ACTIVE`와 `PAUSED`를
  모두 받는다. 이 순서 제한은 점주용 서비스에만 있다
- **이미 `ENDED`인 캠페인의 재요청은 멱등하게 받지 않고 `CAMPAIGN-006`으로 거부한다.**
  종료는 되돌릴 수 없는 전이라, 중단과 재개처럼 "이미 그 상태면 성공"으로 받으면 점주가 실수로
  두 번 누른 요청과 정말 끝난 캠페인의 구분이 사라진다. `CampaignStatusService.end()` 자체는
  배치의 재실행을 위해 여전히 멱등하다
- **종료할 때 가게 요약을 `ENDED`로 갱신한다.** 중단과 재개처럼 `StoreCampaignSummaryUpdater`를
  같은 트랜잭션에서 부른다. 정기 종료(`#569`)도 같은 `end()`를 부르므로 이 갱신은 한 곳에 있다
- **종료하면 `CampaignStatusChangedEvent`와 `CampaignEndedEvent`가 발행되어 정산과 광고
  서빙이 받는다.** 점주 종료와 정기 종료가 같은 이벤트를 쓴다

## 캠페인 중단과 재개(점주)

- **점주용 전이는 기존 `CampaignStatusService.pause()`/`resume()`을 그대로 부른다(FR-092,
  `#198`).** 둘 다 이미 구현되어 있어 새 전이 메서드를 추가하지 않는다. `pause()`는
  `PauseReason.OWNER`, `resume()`은 `ActorType.OWNER`로 부른다
- **본인 소유 확인은 `CampaignTargetAudienceService`와 같은 패턴이다.**
  `campaignRepository.findByIdForUpdate(campaignId)`(비관 잠금)로 조회한 뒤
  `Campaign.ownerId`를 비교하고, 없거나 다르면 `CAMPAIGN-005`로 거부한다. 이 확인은 새
  서비스(`CampaignPauseResumeService`)가 하고, `CampaignStatusService.pause()`/`resume()`은
  여전히 `ownerId`를 모른다
- **점주 상태(`ACTIVE`)도 등록·수정 엔드포인트와 같은 방식(`OwnerStatusQuery`)으로
  확인한다.** 이슈 본문은 이 확인을 언급하지 않았지만, 다른 캠페인 쓰기 엔드포인트와
  보안 수준을 맞추기로 했다(채팅으로 확인, 2026-10-18)
- **엔티티가 던지는 `IllegalStateException`(상태가 안 맞음)을 `CAMPAIGN-006`으로
  변환한다.** `Campaign.pause()`/`resume()`은 각각 `ACTIVE`/`PAUSED`가 아니면 이 예외를
  던진다. 새 서비스가 `CampaignStatusService` 호출을 `try-catch`로 감싸 변환한다
- **`StoreCampaignSummaryUpdater.update()`가 던지는 `IllegalArgumentException`(가게-메뉴
  불일치)은 그대로 두지 않고 `RuntimeException`으로 다시 던진다(자체 리뷰로 찾음,
  2026-10-18).** 캠페인 등록 1단계(`CampaignCouponConditionService`)는 이제 `targetMenuId`의
  가게 소속을 저장 전에 검증하지만(`#874`), 그 전에 저장됐거나 직접 저장한 데이터에는
  MENU 할인 캠페인의 `targetMenuId`가 실제로는 다른 가게 메뉴인 것이 남아 있을 수 있어
  이 변환은 그대로 둔다. 이 불일치는 점주의 요청이 잘못된 게 아니라 서버 쪽
  데이터 문제라서, 그대로 두면 `GlobalExceptionHandler.handleIllegalArgument`가
  `COMMON-002`(400, "입력값이 올바르지 않습니다")로 잘못 답한다. `CampaignException`도
  `IllegalStateException`도 아닌 `RuntimeException`으로 다시 던져
  `handleUnexpected`의 `COMMON-001`(500)로 보낸다
- **`store.contract.StoreCampaignSummaryUpdater.update()` 호출을
  `CampaignStatusService.pause()`/`resume()` 안에 추가한다(설계 문서 2.4, `#468`).** 호출자가
  누구든(점주, 나중에 추가될 관리자) 같은 트랜잭션 안에서 가게 요약이 함께 갱신되게
  하려고 호출 지점을 전이 메서드 내부에 둔다. `Campaign`의 `discountTargetType`,
  `discountType`, `discountValue`, `targetMenuId`, `usableStartTime`, `usableEndTime`과
  바뀐 `CampaignStatus`를 `StoreCampaignStatus`로 매핑해 `StoreCampaignSummaryCommand`를
  구성한다. 두 enum의 값 이름이 같아 매핑은 단순 1:1 `switch`다
- **멱등하게 조용히 반환하는 자리(이미 목표 상태)에서는 가게 요약 갱신도 건너뛴다.**
  `CampaignStatusLog` 저장과 이벤트 발행이 이미 그 자리를 건너뛰고 있어 같은 기준을
  따른다. 실제로 상태가 바뀌지 않았으니 가게 요약도 바뀔 이유가 없다
- **`schedule`/`activate`/`end`/`pauseFromFunding`/`resumeFromFunding`은 이번 이슈에서
  연동하지 않는다.** 가게 호출 지점은 다섯 곳 모두 필요하다고 이미 확정했지만(설계 문서
  2.4, 가게 담당 회신 2026-10-13), 이번 이슈는 점주의 수동 중단·재개만 다룬다. 나머지는
  각자의 구현 이슈가 이어 붙인다
- **엔드포인트 경로는 `api-spec.md`의 커스텀 메서드 컨벤션 예시를 그대로 쓴다.**
  `POST /v1/owner/campaigns/{campaignId}:pause`, `:resume`이다. 대체할 다른 패턴이
  저장소에 없어 그 예시를 확정 계약으로 채택했다
- **응답은 등록 1단계(`POST`)와 같은 최소 모양이다.** `campaignId`와 `status`만 담는다.
  상세 조회처럼 `pausedReason`, `statusChangedAt`까지 담을 필요는 없다고 보았다(채팅으로
  확인, 2026-10-18)

## 캠페인 취소(점주)

허용할 상태와 취소 뒤 수정 범위는 `#571`이 정했다([구조와 동작 설계](./campaign_구조와_동작_설계.md#scheduled-캠페인의-취소571)).
이 절은 그 결정을 HTTP 로 여는 방법을 적는다(`#597`).

- **새 전이 `CampaignStatusService.cancel()`과 엔티티 `Campaign.cancel()`을 더한다.** `cancel()`은
  `SCHEDULED`만 `DRAFT`로 바꾸고 나머지 상태면 `IllegalStateException`을 던진다. 상태 변경 이력은
  `ActorType.OWNER`로 남긴다
- **이미 `DRAFT`면 이력 없이 조용히 성공한다.** 다른 전이(`pause`, `resume`)와 같은 멱등 기준이다.
  응답을 받지 못하고 다시 보낸 요청이 실제로는 성공한 취소를 오류로 보지 않게 한다. 한 번도 활성화하지
  않은 `DRAFT`에 보낸 취소도 `200`이 되지만 상태는 같아 해가 없다
- **이벤트를 발행하지 않고 가게 요약도 갱신하지 않는다.** `DRAFT -> SCHEDULED`(`schedule`)도 둘 다
  하지 않는다. 다른 도메인은 `SCHEDULED`를 그때그때 읽기만 한다. coupon 의 쿠폰 행사 조회
  (`CouponLookUpService`)와 billing 의 환불 차단(`hasLiveCampaign`)이 그렇다. 그래서 되돌릴 것이 없다.
  `CampaignStatusChangedEvent`를 받는 billing, adserving, coupon 의 리스너는 집행 중인 캠페인을 다룬다
- **본인 소유 확인과 점주 상태 확인은 중단·재개와 같다.** 새 서비스 `CampaignCancelService`가
  `findByIdForUpdate`로 잠그고 `ownerId`를 비교한다(`CAMPAIGN-005`). 점주가 `ACTIVE`가 아니면
  `CAMPAIGN-001`이다. 엔티티의 `IllegalStateException`은 `CAMPAIGN-006`으로 바꾼다
- **00:00 의 `SCHEDULED -> ACTIVE`와는 캠페인 행 잠금으로 줄을 선다.** 취소도 `activate()`도
  `findByIdForUpdate`로 같은 행을 잠근다. 배치가 먼저면 취소는 `ACTIVE`를 보고 `CAMPAIGN-006`이다.
  취소가 먼저면 배치는 `DRAFT`를 보고 `Campaign.activate()`가 `IllegalStateException`을 던진다.
  그 배치를 만드는 쪽(`#569`)이 이 예외를 "취소된 캠페인이라 건너뛴다"로 받아야 한다. 아래
  "아직 정해지지 않은 것"에 남긴다
- **응답은 중단·재개와 같은 `CampaignStatusResponse`다.** `campaignId`와 `status`만 담는다
- **취소에는 요청 빈도 제한을 두지 않는다.** 취소는 외부 호출이 없는 행 하나의 갱신이다. 취소와 재활성화를
  되풀이해 검수를 여러 번 부르는 것은 활성화 쪽 제한(`CAMPAIGN-011`)이 막는다

## 캠페인 조회(점주)

- **두 엔드포인트 모두 점주 상태(`OwnerStatusQuery`) 확인을 하지 않는다.** 등록·수정과
  달리 상태를 바꾸지 않는 단순 조회라, `owner-api-spec.md`의 `GET /v1/owner/me`(상태를
  확인하지 않고 `ONBOARDING` 점주도 조회할 수 있다)와 같은 선례를 따른다(`#341` 결정)
- **목록은 커서 페이지네이션을 쓴다.** `api-spec.md`의 공통 규약과
  [장애_처리_설계_가이드.md](../장애_처리_설계_가이드.md) "애플리케이션: 대용량 조회"(페이징
  없이 전체를 읽으면 OOM)를 따른다. 정렬·커서 키는 `campaignId desc` 하나로 충분하다.
  `campaignId`는 auto-increment라 생성 순서와 일치해서,
  `TemplateVersionRepository.findPage`(버전 번호 하나로 커서를 가르는 패턴)와 같은 단일
  커서를 쓴다. 목록 조회라도 실행 중 집행 데이터(예약액, 소진 카운터)는 담지 않는다. 그
  값은 billing이 소유하고, `campaign -> billing` 의존은 순환이 생겨 막혀 있다(설계 문서
  2.2, 281행)
- **본인 소유 확인 실패(없음과 타인 소유)는 기존 `CAMPAIGN-005`(`CAMPAIGN_NOT_OWNED`,
  403)를 그대로 재사용한다.** `#312`가 PATCH에서 이미 쓰고 있는 코드이고, 뜻도
  "그 캠페인에 접근할 권한이 없습니다"로 조회에도 맞아 새 코드를 추가하지 않는다
- **상세 조회는 등록 세 단계(쿠폰 조건, 노출 대상, 하루 예산과 집행 기간)의 전체 필드와
  상태·`pausedReason`·`statusChangedAt`·`createdAt`을 모두 담는다.** 목록은 캠페인을
  식별하고 구분하는 데 필요한 요약 필드만 담아 가볍게 둔다(캠페인 ID, 가게 ID, 상태, 할인
  조건 요약, 예산·기간, 생성 시각)
- **상세 조회는 잠금 없이 `findById`로 조회한다.** 등록 수정과 달리 값을 바꾸지 않아
  `CampaignTargetAudienceService`처럼 `findByIdForUpdate`(비관 잠금)를 쓸 이유가 없다

## 캠페인 조회(관리자)

- **점주용 조회와 소유자 확인만 다르다.** `AdminCampaignQueryService`는 소유자를 가리지 않고
  전체 캠페인을 조회한다. 목록의 정렬과 커서는 점주용과 같이 `campaignId desc` 하나를 쓴다
  (`#603`)
- **필터는 가게 ID와 상태 둘이고 둘 다 선택이다.** 상호 검색은 서버가 하지 않고 클라이언트가
  가게 ID로 바꿔 보낸다. 전체를 최신순으로 훑는 조회라 점주용처럼 복합 인덱스를 힌트로
  강제하지 않고 기본키 역순 스캔을 쓴다
- **가게 상호는 한 페이지의 가게 ID를 모아 `StoreQueryService.findAllById`로 한 번에
  조회한다.** 캠페인마다 가게를 조회하면 N+1이 된다. 최종 등록 전 가게는 그 조회가
  제외하므로 상호는 `null`이다
- **상세는 점주용 상세에 `ownerId`, `storeName`, 상태 이력, 포스터를 더한다.** 상태 이력은
  `campaign_status_log`를 `id` 오름차순으로 읽고, 포스터는 `campaign_id`로 한 건 조회한다.
  포스터가 없으면 `poster`는 `null`이다
- **없는 캠페인은 `CAMPAIGN-012`(404)다.** 점주용은 존재 여부를 숨기려 없음과 타인 소유를
  `CAMPAIGN-005`로 묶지만, 관리자는 모든 캠페인에 접근할 수 있어 숨길 이유가 없다
- **소진 포인트와 누적 유효 노출은 담지 않는다.** 점주용 조회와 같은 이유다(`campaign ->
  billing` 의존은 순환이 생긴다). 클라이언트가 분석의 캠페인 리포트를 따로 불러 합친다

## 오류 코드를 나눈 기준

요청 형식(필수값, 타입)처럼 판정에 업무 지식이 필요 없는 실패는 `COMMON-002`를 쓴다.
점주 상태처럼 호출 주체의 상태를 봐야 하는 실패와, 할인율·수량·시간처럼 캠페인 업무
규칙을 봐야 하는 실패에만 `CAMPAIGN-` 코드를 쓴다.

## 아직 정해지지 않은 것

| 항목 | 요구사항 | 현재 스키마 또는 명세 |
|---|---|---|
| 가게당 DRAFT 1개를 막는 UNIQUE 제약의 정확한 마이그레이션 문구 | `#244` 결정 | `#244`가 `active_store_marker`와 같은 방식으로 추가한다(이 문서 "캠페인 등록" 절 참고) |
| `Campaign.updateCouponCondition(...)` 메서드 시그니처 | `#244` 결정 | 아직 엔티티에 없다. `#244`가 추가한다 |
| 00:00 배치가 `pendingDailyBudget`을 `dailyBudget`에 반영하는 호출 지점 | V51 45행, 102행 스케줄러 | 해소됨(`#840`). `CampaignPendingBudgetJob`(`@Order(35)`)이 ACTIVE 이고 예약 값이 있는 캠페인마다 행 잠금 아래에서 상태를 다시 확인한 뒤 `dailyBudget`으로 옮기고 예약 값을 비운다. `pause()`는 `pendingDailyBudget`을 지우지 않으므로 `PAUSED`로 멈춰 있는 캠페인에는 적용하지 않고 예약 값을 남긴다. 정산의 일일 예약(50)보다 앞이라 바뀐 예산이 그날 예약에 반영된다 |
| 예약 값(`pendingDailyBudget`) 자체를 PATCH 응답에 노출할지 | V51 45행 | `#330`은 노출하지 않는다(응답은 늘 `dailyBudget`). 노출이 필요해지면 그때 명세를 바꾼다 |
| `targetMenuId`가 그 가게(`storeId`) 소유인지 등록 시점(`CampaignCouponConditionService`)에 검증하는 것 | `#468` 자체 리뷰로 찾음(2026-10-18) | 해소됨(`#874`). 등록 시점에 `findMenusByIdIn`으로 `targetMenuId`가 그 가게의 메뉴이고 검증된 이미지가 있는지 확인하고 아니면 `CAMPAIGN-014`로 거절한다. 같은 변경이 `storeId`가 로그인한 점주의 가게인지도 `canManageStore`로 확인한다(`CAMPAIGN-013`) |
| 00:00 의 `SCHEDULED -> ACTIVE` 배치가 취소된 캠페인을 만났을 때의 처리 | `#597`(2026-10-20) | 취소가 먼저 잠금을 잡으면 배치의 `CampaignStatusService.activate()`가 `DRAFT`를 보고 `IllegalStateException`을 던진다. 그 배치를 만드는 `#569`가 이 예외를 건너뛰기로 받아야 한다. 받지 않으면 단계가 실패해 00:00 묶음이 멈춘다 |
| 캠페인 활성화 요청 빈도 제한의 정확한 횟수·창 | `#561` 자체 리뷰로 찾음(2026-10-19) | `CampaignActivationRateLimiter`가 지금은 `TemplateGenerationRateLimiter`의 분당 3회를 그대로 가져와 썼다(근거 없는 예시값, `C` 등급). 실제 값은 운영 쪽과 정한다 |

해소된 항목(`#330`): 하루 예산의 "다음 영업일 적용 예약 값" 컬럼은 `Campaign.pendingDailyBudget`(`V13__campaign_pending_daily_budget.sql`, 양수만 허용하는
`chk_campaign_pending_daily_budget` CHECK 포함)이고, 최소 하루 예산은 `ops.contract.PlatformSettingQueryService.getMinDailyBudget()`(요구사항명세서
[H27] 1,000포인트, `V4__ops_min_daily_budget.sql` 시드)이다.

**`pendingDailyBudget`을 상태 전이에서 지우는 기준도 이 PR의 자체 리뷰로 정했다.** `pause()`는 지우지 않고 `end()`만 지운다. 중단은
"끝"이 아니라 "잠깐 멈춤"이라 그 사이 재개되면(NO_POINTS 충전 재개 등, V51 51·49행) 점주가 걸어 둔 예약값이 남아 있어야 하고, `end()`는
되돌릴 수 없는 종료라 더 쓸 일이 없는 값을 지운다(`Campaign.java`의 `pause`/`end` 블록 주석 참고).
