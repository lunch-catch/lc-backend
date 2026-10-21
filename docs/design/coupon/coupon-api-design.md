# 쿠폰 API 설계

클라이언트 계약은 [쿠폰 API 명세](../../api-spec/coupon-api-spec.md)를 따른다.
첨부 명세와 합의한 이벤트 스냅샷 방향을 정리한 설계다.
여러 API가 공유하는 저장·상태·잠금 기준은 [구조와 동작 설계](./coupon_구조와_동작_설계.md)가 갖는다.
의존 대상 장애의 기본 처리와 미점검 항목은 [장애 처리 설계](./coupon_장애_처리_설계.md)가 갖는다.
쿠폰 발급과 당일 발급 한도, 쿠폰 통계 조회, 목록·단건 조회, 미사용 쿠폰 삭제, QR 생성·갱신, 매장 사용 이력 조회와 이벤트 상태 조회의 처리 흐름은 아래에 적는다.

발급 구조와 시퀀스, 동시성·실패 처리에서 고려한 내용은 다음 세 설계도에 기록한다.
HTTP 경로의 버전과 발급 구조의 버전은 별개다.

| 버전 | 설계도 |
|---|---|
| v1 | [MySQL에서 재고를 선택하고 요청마다 커밋했다.](./coupon-issuance-v1-design.md) |
| v2 | [Redis 순번 발급기로 재고 선택을 분리했다.](./coupon-issuance-v2-design.md) |
| v3 | [여러 발급 요청을 묶어 한 번에 커밋했다.](./coupon-issuance-v3-design.md) |

## 묶음으로 처리하는 쿠폰 발급

`POST /v1/coupons`는 `CouponCommandService.issue(memberId, eventId)`로 처리한다.
조정 메서드는 `Propagation.NEVER`로 호출자의 트랜잭션 안에서 실행하는 것을 막는다.
요청 스레드는 Redis의 확정 소진을 먼저 검사한다. 사전 검사의 응답 우선순위는 [발급 명세](../../api-spec/coupon-api-spec.md#쿠폰-발급)를 따른다.
`CouponSequenceService.isConfirmedSoldOut`이 true면 발급 대기열이나 발급 DB 조회를 사용하지 않는다.
소진이 확정되지 않은 요청만 DB 커넥션 없이 `CouponIssueBatcher.submitAndAwait`로 대기열에 넣고 자기 결과를 기다린다.
작업자 스레드가 대기열에서 요청을 꺼내 `CouponBatchIssuanceService.issueBatch`로 묶음 하나를 READ_COMMITTED 트랜잭션 하나에서 처리한다.
요청마다 트랜잭션을 열던 단건 경로는 두지 않는다.

### 대기열과 작업자

작업자는 한 건을 꺼낸 뒤 그동안 쌓인 요청을 최대 묶음 크기까지 함께 꺼낸다. 묶음이 차기를 기다리지 않는다.
작업자마다 DB 커넥션을 하나씩 쓰므로 작업자 수는 커넥션 풀보다 작게 둔다.
설정은 `coupon.issue.batch` 아래에 두며 기본값은 아래와 같다.

| 설정 | 기본값 | 넘었을 때 동작 |
|---|---:|---|
| `workers` | 4 | 작업자 수만큼만 묶음을 동시에 처리한다 |
| `max-size` | 500 | 한 트랜잭션에 담는 요청 수의 상한이다 |
| `queue-capacity` | 2,000 | 대기열이 가득 차면 기다리지 않고 `503 COUPON-008`로 거절한다 |
| `wait-timeout` | 10초 | 복구와 발급 결과 대기가 공유하는 처리 기한이며 대기 시간 초과는 `500 COMMON-001`로 응답한다 |

요청 시각은 Redis 사전 검사 전에 한 번 기록한다. 사전 검사와 대기열에서 기다린 시간은 발급 시간 판정에 들어가지 않는다.

### 트랜잭션과 잠금

1. 묶음에 든 이벤트를 ID 순으로 공유 잠금하고 이벤트의 영업일과 ACTIVE 상태, 요청 시각의 시스템 발급 시간을 검사한다.
2. Redis에서 이벤트의 소진 여부를 읽는다. 소진이면 회원·찜·중복·한도보다 먼저 `COUPON-006`으로 거절한다.
3. `MemberQueryService.findCurrentStatuses`로 묶음의 회원 상태를 한 번 조회한다. ACTIVE 회원을 캠페인별로 모아 `WishlistQueryService.findWishedMemberIds`로 캠페인당 한 번 찜을 조회한다. 반환된 상태가 없으면 회원 자격을 거절하고 반환된 찜 집합에 없으면 찜 자격을 거절한다. 조회 장애는 그대로 전파한다.
4. 남은 회원의 일일 카운터를 다건 UPSERT로 만들고 회원 ID 순으로 `FOR UPDATE` 잠근다. 잠금 쿼리는 `uk_coupon_daily_limit_member_date`를 고정해 잠금 순서를 맞춘다.
5. 잠근 뒤 기존 발급 기록을 읽고 요청 순서대로 중복과 일일 한도를 판정한다. 같은 묶음 안의 앞 요청도 함께 센다. REMOVED도 중복 기록에 포함한다.
6. 판정을 통과한 요청 수만큼만 이벤트마다 Redis 호출 한 번으로 순번을 예약한다. 거절할 요청은 순번을 잡지 않는다.
7. 예약한 순번의 AVAILABLE 재고를 이벤트마다 한 문장으로 ISSUED로 바꾸고 쿠폰 코드를 읽는다. 바꾼 행 수가 예약 수와 다르면 이벤트를 차단하고 묶음 전체를 실패시킨다.
8. 카운터를 늘리고 회원 쿠폰을 다건 INSERT 한 문장으로 저장한다. 생성된 ID는 JDBC 생성 키로 받는다. 카운터·재고·회원 쿠폰을 함께 커밋한다.
9. 커밋 후 작업자 스레드가 Redis 예약을 완료하고 요청 스레드에 발급 응답과 소진 여부를 전달한다. 요청 스레드가 타임아웃돼도 예약 완료는 수행한다. 롤백되면 받은 순번을 모두 반납한다.

업무 거절은 쓰기 전에 걸러 같은 묶음의 다른 요청에 영향을 주지 않는다.
판정을 통과했지만 순번을 받지 못한 요청이 있어도 같은 회원의 뒤 요청은 한도 판정을 다시 하지 않는다.
예외로 전파되는 묶음 실패는 모든 요청에 오류를 전달한다. 작업자는 묶음을 나누거나 자동으로 재시도하지 않는다.
트랜잭션 종료 상태와 재시도 보완 조건은 [장애 처리 설계](./coupon_장애_처리_설계.md#발급-묶음의-자동-재시도)를 따른다.
DB에 새 테이블을 만들지 않으며 기존 UNIQUE·CHECK는 유지한다.
묶음 크기는 두 일괄 조회 계약의 입력 상한을 넘기지 않는다. 현재 더 작은 상한은 `WishlistQueryService.MAX_WISHED_MEMBER_IDS`의 500명이다.
공유 잠금은 복구가 진행 중인 발급의 종료를 기다리는 데 사용한다. 캠페인의 상태 변경 UPDATE도 진행 중 발급이 공유 잠금을 해제할 때까지 기다린다.
Redis 키와 예약 ID는 [구조와 동작 설계](./coupon_구조와_동작_설계.md#redis-순번과-예약)가 소유한다.
차단과 재구축, 복구 후 발급을 이어가는 조건은 [장애 처리 설계](./coupon_장애_처리_설계.md#redis-장애와-큐-복구)가 가리키는 공통 복구 설계를 따른다.
Redis 키의 절대 만료 시각과 보관 여유시간은 [v2의 TTL 정책](./coupon-issuance-v2-design.md#26-ttl은-발급-종료-시각을-기준으로-설정한다)이 소유한다.

### 찜과 소진 contract 연결

찜 조회와 소진 반영의 반환·실패·복구 정책은 [광고 서빙 공개 계약](../adserving/feed-api-design.md#wishlistqueryservice)이 소유한다.
찜 조회 장애는 묶음을 롤백하며 찜 없음으로 바꾸지 않는다.
`CouponIssueBatcher`는 발급 커밋 후 Redis의 completed를 한 번만 증가시킨다.
completed가 total에 도달하면 HTTP 대기 종료 여부와 관계없이 `CouponSoldOutNotificationService`에 이벤트를 접수한다.
소진 거절도 같은 서비스에 접수하지만 요청 스레드는 재고를 DB에서 다시 확인하거나 광고 통보를 기다리지 않는다.
사전 검사에서 소진되지 않은 요청은 기존 묶음 트랜잭션에서 이벤트의 운영 상태와 발급 시간을 검사한다.
묶음 트랜잭션의 재고 검사는 사전 검사 뒤에 소진되거나 다른 요청이 재고를 예약한 경우를 처리한다.

통보 서비스는 이 서버에서 대기하거나 성공한 이벤트의 중복 접수를 합친다.
별도 작업자 하나가 `CouponLookUpService.findSoldOut`으로 소진 통보에 필요한 값을 읽는다.
삭제된 쿠폰도 포함한 마지막 createdAt과 이벤트의 캠페인·영업일을 그대로 전달한다.
이벤트나 발급 기록이 없으면 소진 시각을 임의로 만들지 않고 실패한 작업으로 남긴다.
서비스는 조회 트랜잭션이 끝난 뒤 `CandidateStateUpdater.markSoldOut`을 부른다.
성공한 이벤트는 최대 10,000개를 하루 동안 기억하며 서버가 여러 대면 각 서버가 한 번씩 통보할 수 있다.
반복 통보는 [소진 반영 계약](../adserving/feed-api-design.md#candidatestateupdater)의 멱등성으로 처리한다.

대기 작업은 최대 2,000개이며 작업자는 1초마다 처리할 작업을 확인한다.
조회나 통보가 실패하면 새 HTTP 요청 없이 5초 간격으로 재시도한다.
접수한 뒤 한 시간이 지나면 작업을 만료시키고 ERROR 로그를 남긴다.
접수 거절, 재시도 실패와 종료 때 남은 작업도 ERROR 로그로 남기며 원래 HTTP 결과는 유지한다.
대기 작업과 성공 표시는 서버 메모리에 있으므로 재시작 뒤에는 남지 않는다.
서버 재시작이나 대기열 포화로 접수를 잃으면 후속 소진 거절이 다시 접수한다.
후속 요청 없이 서버가 종료된 경우의 통보 복구는 보장하지 않는다.

### 검증

`CouponIntegrationTest`의 `SequenceRecovery`는 Redis 반납, 오래된 예약, 키 유실, 커밋 후 Redis 실패와 복구의 DB 잠금 대기를 검증한다.
같은 클래스의 `BatchIssuance`는 동시 요청의 재고 초과와 같은 회원의 중복, 일일 한도, 한 묶음 안의 거절을 검증한다.
`Issue.Http`는 JWT 쿠키와 실제 HTTP 연결로 발급·복구·거절 응답을 검사하고 응답 ID·시각을 DB 저장값과 대조한다.
재고보다 많은 동시 HTTP 요청도 검증하며 요청·응답 기록은 `build/reports/coupon-issuance-http`에 남긴다.

단위 테스트는 업무 조건과 커밋 실패를 검증한다. MySQL 통합 테스트는 중복과 재고 초과, 일일 한도, 삭제 후 재발급, 시간 경계, 롤백과 DB 오류의 공통 응답을 검증한다.
기존 MySQL 동시 요청 테스트는 다섯 건으로 재고 초과와 중복 발급, 일일 한도 초과를 검증한다. 별도 부하 시험용 Gradle 작업은 두지 않는다.
[발급 성능 목표](#발급-성능-목표)를 기준으로 수행한 로컬 부하 측정과 개선 이력은 트러블슈팅의 [v1](../../troubleshooting/coupon/coupon-issuance-v1-improvement-comparison.md), [v2](../../troubleshooting/coupon/coupon-issuance-v2-improvement-comparison.md), [v3](../../troubleshooting/coupon/coupon-issuance-v3-improvement-comparison.md)에 기록한다.

## 쿠폰 QR 생성·갱신

`POST /v1/coupons/{memberCouponId}:generateQr`는 `CouponCommandService.generateQr(memberId, memberCouponId)`로 처리한다.
응답과 QR 유효기간, 사용 시간 및 오류는 [명세](../../api-spec/coupon-api-spec.md#qr-생성)를 따른다.
기존 `member_coupon`의 `qr_token`, `qr_version`, `qr_expires_at`을 변경하며 새 마이그레이션은 필요하지 않다.

### 서버가 하는 일

1. `CouponSecurityConfig`는 QR POST 경로에 `MEMBER` 역할을 요구한다. 컨트롤러는 인증된 회원 ID와 양수인 쿠폰 ID를 전달한다.
2. 서비스는 쓰기 트랜잭션에서 `MemberCouponRepository.findByIdAndMemberId`로 본인 쿠폰을 조회하고 행 잠금을 확보한다. 본인 범위에 없는 ID는 존재 여부와 관계없이 접근을 거절한다.
3. 서비스는 `member.contract.MemberQueryService.findById`로 쿠폰 소유 회원의 현재 상태를 확인한다. 회원이 없거나 ACTIVE가 아니면 거절한다. 인증 토큰이나 조회 응답의 과거 상태로 대신하지 않는다.
4. 잠금을 얻은 뒤 주입된 `Clock`으로 한국 시각을 한 번 읽는다. `MemberCoupon.renewQr`는 최신 쿠폰 상태와 사용 시간 경계를 검증한다.
5. 엔티티는 새 UUID 토큰과 증가한 QR 버전, 명세의 토큰 만료 시각을 함께 반영한다. 캠페인 상태는 재조회하지 않으며 발급된 쿠폰의 사용 시간 스냅샷을 따른다.
6. `CouponQrImageEncoder`는 저장할 토큰을 ZXing으로 PNG 이미지로 만들고 Base64로 인코딩한다. 기존 `coupon-inte`의 300px 크기를 유지한다.
7. JPA 변경 감지로 QR을 저장한다. 트랜잭션이 커밋되면 컨트롤러는 공통 성공 응답과 캐시 금지 헤더를 반환한다.

### 동시 요청과 실패 처리

행 잠금은 소유권 확인부터 커밋까지 유지한다. 삭제·단건 조회·목록 조회의 만료 저장과 같은 잠금 기준을 사용한다.
대기하던 QR 요청은 잠금 획득 뒤의 상태와 시각으로 판단한다. 삭제나 만료가 먼저 반영되면 QR을 다시 생성하지 않는다.
갱신이 먼저 커밋되고 삭제가 이어지면 삭제가 새 QR을 무효화한다.
동시 갱신이 성공하면 마지막으로 커밋된 토큰 하나만 저장된다.

QR 변경은 쿠폰 상태나 발급 횟수, 재고와 사용 이력을 변경하지 않는다.
회원 상태 조회 실패를 ACTIVE나 회원 없음으로 바꾸지 않고 공통 예외 처리로 전달한다.
이미지 생성이나 DB 저장이 실패하면 트랜잭션을 롤백하고 기존 QR 정보를 유지한다. 실패한 요청에 새 QR 이미지를 성공 응답으로 보내지 않는다.
QR 업무 거절은 `COUPON_QR_GENERATION_FAILED` 이벤트로 WARN 로그를 남긴다. 회원 ID와 쿠폰 ID, 오류 코드만 기록하며 토큰과 이미지는 넣지 않는다.
성공은 공통 HTTP 접근 로그로 추적한다.

## 당일 쿠폰 발급 한도 조회

`GET /v1/coupons/issuance-limit`은 `CouponLookUpService.getIssuanceLimit(memberId)`로 처리한다.
클라이언트가 받는 필드와 오류는 [명세](../../api-spec/coupon-api-spec.md#당일-쿠폰-발급-한도-조회)를 따른다.
요구사항은 FR-080이며 저장 구조는 [쿠폰 V1 마이그레이션](../../../src/main/resources/db/migration/coupon/V1__coupon_init.sql)의 `coupon_daily_limit`이다.

### 서버가 하는 일

1. `CouponSecurityConfig`에서 공용 인증 설정을 적용하고 이 GET 경로에 `MEMBER` 역할을 요구한다.
2. `CouponController`는 인증 정보의 회원 ID를 서비스로 전달한다.
3. 서비스는 주입된 `Clock`으로 한국 날짜를 한 번 계산한다.
4. `CouponDailyLimitRepository`는 `(member_id, business_date)` 조건으로 `issued_count`만 조회한다.
5. 정상 조회 결과가 비어 있으면 0건으로 처리하고 설정된 일일 한도와 비교한다.
6. 컨트롤러는 서비스 결과에 기존 `ResponseEnvelope`를 씌운다.

### 정한 이유

- 일일 한도에 반영되는 발급 횟수는 `coupon_daily_limit.issued_count`에서 읽는다. 카운터의 차감은 [쿠폰 삭제의 변경된 정책](../../api-spec/coupon-api-spec.md#쿠폰-삭제)을 따른다.
- 읽기 전용 트랜잭션으로 처리하며 조회만으로 일일 한도 행을 생성하거나 수정하지 않는다. 발급 경쟁을 제어하는 쓰기 잠금도 잡지 않는다.
- 한국 날짜를 애플리케이션에서 계산한다. 서버 기본 시간대나 DB 날짜 함수에 따라 영업일이 달라지지 않게 한다.
- 현재 회원 상태와 찜 정보를 조회하는 다른 도메인 contract는 호출하지 않는다. 발급 자격과 한도는 실제 발급 요청에서 다시 판단한다.

### 설정과 저장 제약

일일 한도는 `coupon.daily-limit`에서 읽는다. 환경 변수는 `COUPON_DAILY_LIMIT`이고 기본값은 3이다.
V1 마이그레이션은 `issued_count`를 0부터 3까지로 제한한다. 한도를 3보다 크게 바꾸려면 DB 제약과 실제 발급 처리도 함께 변경해야 한다.
회원과 영업일의 유일성은 기존 `uk_coupon_daily_limit_member_date`가 보장하며 이 API를 위한 새 마이그레이션은 추가하지 않는다.

조회 실패를 발급 기록 없음으로 바꾸지 않는다. DB 조회 예외는 기존 공통 예외 처리로 전달한다.

## 마이페이지 쿠폰 통계 조회

`GET /v1/coupons/summary`는 `CouponLookUpService.getSummary(memberId)`로 처리한다.
클라이언트가 받는 필드와 오류는 [명세](../../api-spec/coupon-api-spec.md#마이페이지-쿠폰-요약-확인)를 따른다.
요구사항은 FR-088이며 저장 구조는 [쿠폰 V1 마이그레이션](../../../src/main/resources/db/migration/coupon/V1__coupon_init.sql)의 `member_coupon`과 `coupon_usage_history`다.

### 서버가 하는 일

1. `CouponSecurityConfig`에서 이 GET 경로에 `MEMBER` 역할을 요구한다.
2. `CouponController`는 인증 정보의 회원 ID를 서비스로 전달한다.
3. `MemberCouponRepository.findSummaryByMemberId`는 해당 회원의 쿠폰과 사용 이력을 한 집계 쿼리로 조회한다.
4. 서비스는 `CouponSummaryResponse`를 반환하고 컨트롤러는 기존 `ResponseEnvelope`를 씌운다.

### 집계 방식과 정한 이유

- `member_coupon.member_id`로 본인의 쿠폰만 집계한다. 상태로 제외하지 않아 삭제·만료된 쿠폰도 누적 발급 수에 포함한다.
- 사용 이력은 `member_coupon_id`로 `LEFT JOIN`한다. 사용 이력이 없는 발급 쿠폰도 집계에서 빠지지 않게 한다.
- 발급 수와 사용 수는 각각 쿠폰 ID와 사용 이력의 회원 쿠폰 ID를 `COUNT(DISTINCT ...)`로 센다. 조인 행의 수로 쿠폰 수를 계산하지 않는다.
- 할인액은 저장된 `discount_amount`를 `SUM`한다. 같은 할인액을 중복으로 제거하지 않으며 현재 메뉴 가격이나 할인 조건을 다시 조회하지 않는다.
- 사용 이력의 할인액이 0이어도 해당 쿠폰을 사용 수에 포함한다. 할인액의 크기로 사용 여부를 판단하지 않는다.
- 발급 이력이 없으면 `COUNT`가 0을 반환하고 할인액은 `COALESCE(SUM(...), 0L)`로 0을 반환한다. 사용 이력만 없는 경우도 할인액에 null을 반환하지 않는다.
- 세 집계 값은 `long`으로 받는다. 각 이력의 할인액은 DB의 `INT`여도 합계가 32비트 범위를 넘을 수 있다.

읽기 전용 트랜잭션으로 조회하며 쿠폰 상태와 사용 이력을 변경하지 않는다.
다른 도메인의 contract, 캐시와 새 마이그레이션은 필요하지 않다.
조회 실패를 0건으로 바꾸지 않고 기존 공통 예외 처리로 전달한다.

## 점주 캠페인 쿠폰 수량 조회

클라이언트 계약과 수량의 의미는 [쿠폰 명세](../../api-spec/coupon-api-spec.md#캠페인-쿠폰-수량-조회)가 소유한다.
`OwnerCouponController`는 점주 전용 경로를 제공하며 `OwnerCouponService.summary`가 처리를 맡는다.

### 서버가 하는 일

1. `CouponSecurityConfig`는 정확한 조회 경로에 OWNER 역할을 요구한다. 다른 HTTP 메서드는 허용하지 않는다.
2. 컨트롤러는 인증 주체의 점주 ID와 캠페인 ID, 필수 날짜를 서비스에 전달한다.
3. 서비스는 양수 캠페인 ID와 양끝을 포함한 조회 기간을 검증한다. 종료일 다음 날 자정을 계산할 수 없는 입력도 거절한다.
4. `OwnerStatusQuery.findCurrentStatus`로 현재 ACTIVE 상태를 확인한다. 계정 상태 캐시로 대신 판정하지 않는다.
5. `CampaignQueryService.findAllById`로 해당 캠페인의 현재 점주 ID를 확인한다. 캠페인 부재와 다른 점주의 소유를 같은 접근 거절로 처리한다.
6. `CouponEventRepository.findOwnerSummary`는 쿠폰 도메인의 원천 테이블에서 네 수량을 한 SQL로 조회한다.
7. 서비스는 `OwnerCouponSummaryResponse`를 반환하고 컨트롤러는 공통 성공 봉투를 씌운다.

### 쿼리를 구성한 이유

전체 수량, 발급 수, 사용 수, 미발급 재고는 각각 스칼라 하위 쿼리로 묶는다.
회원 쿠폰과 재고, 사용 이력을 한 번에 조인하지 않아 조인 행 수만큼 수량이 늘지 않는다.
한 SQL 안에서 집계해 발급·사용 트랜잭션이 동시에 커밋돼도 네 수량이 같은 읽기 스냅샷을 따른다.

회차는 `(campaign_id, business_date)` 인덱스로 좁히고 쿠폰과 재고는 회차 ID로 연결한다.
사용 이력은 쿠폰 ID로 연결한 뒤 한국 날짜의 시작 이상과 다음 날 시작 미만으로 조회한다.
사용 시각 컬럼을 날짜 함수로 감싸지 않는다. 엔티티 목록을 메모리에 올리거나 회차마다 쿼리를 반복하지 않는다.
기간 상한은 원천 데이터 집계의 범위를 제한하며 구체적인 계약은 위 명세를 따른다.

조회는 읽기 전용 트랜잭션이며 상태 변경이나 쓰기 잠금을 사용하지 않는다.
정상 빈 결과의 수량은 SQL의 `COUNT`와 `COALESCE(SUM(...), 0)`가 반환한다.
권한·DB 조회 오류는 [장애 처리의 기본 규칙](./coupon_장애_처리_설계.md)을 따라 그대로 전파한다.
캐시와 새 마이그레이션은 추가하지 않으며 분석 배치용 `CouponStatsQueryService`를 호출하지 않는다.

## 매장별 쿠폰 이벤트 상태 조회

`POST /v1/coupon-events:getStatus`는 `CouponLookUpService.getEventStatus(memberId, storeIds)`로 처리한다.
요청과 응답, 발급 시간과 상태의 판정 순서는 [쿠폰 명세](../../api-spec/coupon-api-spec.md#매장별-쿠폰-이벤트-상태-조회)를 따른다.
이벤트 생성과 재고 적재는 이번 조회 API의 범위에 포함하지 않는다.

### 서버가 하는 일

1. `CouponSecurityConfig`는 이 POST 경로에 MEMBER 역할을 요구한다. 컨트롤러는 `CouponEventStatusRequest`로 ID 목록의 필수값, 개수, 양수와 중복 여부를 검증한다.
2. 서비스는 주입된 `Clock`으로 한국 시각을 한 번 읽는다. 같은 시각에서 영업일과 발급 상태를 계산하고 `serverTime`에도 반영한다.
3. `StoreQueryService.findAllById`로 최종 등록된 매장만 남긴다. 전부 제외되면 빈 items를 반환한다. `CouponEventRepository`는 남은 매장 ID 목록과 영업일, ACTIVE·SCHEDULED 상태를 조건으로 이벤트를 일괄 조회한다. 같은 매장의 조회 대상 이벤트가 중복되면 서버 오류로 거절하고 매장 ID와 영업일을 ERROR 로그에 남긴다.
4. 서비스는 조회한 이벤트 ID를 모아 `CouponEventRepository`로 이벤트가 소유한 AVAILABLE 재고를 집계한다. `MemberCouponRepository`는 인증된 회원의 발급 이벤트 ID를 한 번에 조회하며 삭제·사용·만료 상태로 제외하지 않는다.
5. 서비스는 `CampaignQueryService.findByStoreIdIn`의 공개 정보에서 ACTIVE·SCHEDULED 캠페인을 찾는다. `MemberQueryService.findById`로 현재 회원이 ACTIVE인지 확인하고 기존 일일 한도 조회 쿼리로 한도 도달 여부를 판단한다.
6. `WishlistQueryService.findWishedCampaignIds(memberId)`로 현재 찜한 캠페인 ID를 한 번 조회한다. 서비스는 이벤트의 표시 상태와 회원의 발급 불가 사유를 계산한다. 이벤트 스냅샷의 사용 시간과 판정 결과를 입력 순서대로 응답에 담는다.

### 조회와 실패 처리

조회는 읽기 전용 트랜잭션에서 수행한다. 재고와 쿠폰, 발급 횟수와 사용 이력은 변경하지 않으며 쓰기 잠금을 잡지 않는다.
이벤트가 없으면 재고와 회원 발급 이력의 일괄 조회를 생략한다. 재고 집계 결과가 없는 이벤트는 수량이 0이다.
이벤트별 집계나 다른 도메인의 조회를 매장마다 반복하지 않는다.
현재 회원이 없거나 ACTIVE가 아니면 이벤트가 있는 항목에 계정 자격 제한을 표시한다. DB나 공개 조회 contract의 실패는 빈 결과나 계정 제한으로 바꾸지 않고 공통 서버 오류로 전파한다.
기존 매장·영업일 인덱스와 이벤트·회원 유일 키를 사용하며 새 마이그레이션은 추가하지 않는다.
기존 DB는 캠페인과 영업일의 유일성만 보장한다. 매장당 하루 이벤트 한 건이라는 정책은 후속 이벤트 생성 구현에서도 지켜야 한다.

<a id="미제공-contract-연결"></a>

### 연결한 contract

매장 조회 결과의 순서는 보장되지 않으므로 ID 집합으로 존재 여부를 확인하고 요청 순서를 유지한다.
최종 등록 전 매장과 누락 ID, 필수 이미지 오류의 정책은 [store 공개 계약](../store/store-query-contract.md)을 따른다.
현재 찜 캠페인 목록으로 각 이벤트의 wished와 canIssue를 계산한다. 이벤트가 없으면 wished는 false다.
찜과 매장 조회 실패는 빈 목록으로 처리하지 않는다. 다른 도메인의 내부 테이블은 조회하지 않는다.

## 내 쿠폰 목록 조회

`GET /v1/coupons`는 `CouponLookUpService.getCoupons(memberId, filter, pageSize, pageToken)`로 처리한다.
응답 필드와 필터, 페이지 크기는 [명세](../../api-spec/coupon-api-spec.md#내-쿠폰-목록-조회)를 따른다.

### 서버가 하는 일

1. 보안 설정은 `MEMBER` 역할을 요구하고 컨트롤러는 인증된 회원 ID와 조회 조건을 전달한다.
2. 서비스는 허용된 필터를 해석하고 커서의 양수 ID, 발급 시각과 필터 일치를 검증한다. 잘못된 커서를 첫 페이지로 바꾸지 않는다.
3. 서비스는 주입된 `Clock`으로 한국 시각을 한 번 읽는다. 쓰기 트랜잭션에서 저장소는 본인의 삭제되지 않은 쿠폰에 필터를 적용한다.
4. 저장소는 발급 시각과 ID의 내림차순으로 요청 크기보다 한 건 더 조회하고 행 잠금을 확보한다. 다음 페이지 확인용 쿠폰은 반환하거나 만료로 변경하지 않는다.
5. 서비스는 반환할 쿠폰의 이벤트 스냅샷과 사용 완료 이력을 각각 일괄 조회한다. 사용 완료 쿠폰이 없으면 이력 조회를 생략한다.
6. 서비스는 반환할 미사용 쿠폰의 만료를 저장하고 `CouponDetailResponse.from(...)`으로 응답을 조립한다. 다음 페이지가 있으면 마지막 반환 항목의 발급 시각과 ID, 필터로 커서를 만든다.

### 조회와 저장의 기준

필터는 페이지 크기를 적용하기 전에 DB에서 판단한다. 저장 상태가 아직 `ISSUED`여도 종료 시각에 도달한 쿠폰은 만료 필터에 포함한다.
시작 시각은 포함하고 종료 시각은 제외한다. `ISSUED` 필터에는 사용 시작 전 쿠폰도 포함한다.

행 잠금은 [단건 조회](#내-쿠폰-단건-조회)의 삭제·사용 처리와 같은 기준을 따른다.
잠금을 기다린 뒤에는 삭제·사용이 반영된 상태로 조회하며 먼저 커밋된 상태를 만료 처리로 덮어쓰지 않는다.
목록 조립이나 저장이 실패하면 이번 요청의 만료 변경을 함께 롤백한다.
QR과 발급 횟수, 재고와 사용 이력은 변경하지 않는다.

이벤트와 이력은 ID로 모아 일괄 조회하므로 쿠폰마다 조회를 반복하지 않는다.
이미지 URL과 응답 변환, 누락된 데이터의 오류와 진단 로그는 단건 조회의 기준을 재사용한다.
다른 도메인의 내부 데이터나 contract는 호출하지 않으며 새 마이그레이션은 필요하지 않다.

## 내 쿠폰 단건 조회

`GET /v1/coupons/{memberCouponId}`는 `CouponLookUpService.getDetail(memberId, memberCouponId)`로 처리한다.
클라이언트가 받는 필드와 오류는 [명세](../../api-spec/coupon-api-spec.md#내-쿠폰-단건-조회)를 따른다.
요구사항은 FR-081이며 저장 구조는 [쿠폰 V1 마이그레이션](../../../src/main/resources/db/migration/coupon/V1__coupon_init.sql)의 `member_coupon`, `coupon_event`, `coupon_usage_history`다.

### 서버가 하는 일

1. `CouponSecurityConfig`에서 단건 GET 경로에 `MEMBER` 역할을 요구한다. 컨트롤러는 인증된 회원 ID와 양수인 쿠폰 ID를 서비스에 전달한다.
2. 서비스는 쓰기 트랜잭션에서 `MemberCouponRepository.findByIdAndMemberId`로 본인 쿠폰의 행 잠금을 확보한다. 본인 쿠폰을 찾지 못하면 소유권 오류를 반환하고 삭제된 본인 쿠폰은 조회를 거절한다.
3. 서비스는 쿠폰에 연결된 이벤트 스냅샷을 읽고 주입된 `Clock`으로 한국 시각을 한 번 계산한다.
4. `MemberCoupon.changeToExpired(now)`는 미사용 쿠폰의 사용 종료 시각에 도달했으면 상태를 `EXPIRED`로 바꾼다. 사용 완료와 삭제 상태는 바꾸지 않는다.
5. 서비스는 변경된 상태와 사용 가능 시간으로 `usable`을 계산한다. 사용 완료 쿠폰은 확정 사용 이력을 추가로 읽는다.
6. 서비스는 `CouponDetailResponse.from(...)`에 조회 결과와 계산한 상태를 전달한다. DTO는 스냅샷과 사용 결과를 응답 필드로 옮기고 저장 시각에 한국 시간 오프셋을 붙인다.
7. JPA 변경 감지로 만료 상태를 저장하고 컨트롤러는 커밋 후 공통 응답 봉투를 반환한다.

### 정한 이유와 실패 처리

- 조회 시 만료를 저장해 DB와 응답의 쿠폰 상태를 맞춘다. 별도 만료 배치의 실행 시점에 의존하지 않는다.
- 행 잠금은 소유권 조회부터 커밋까지 유지한다. 삭제도 같은 메서드로 쿠폰을 잠가 만료 처리와 삭제가 서로의 상태를 덮어쓰지 않게 한다. 후속 QR 갱신과 사용 확정도 같은 잠금 규칙을 따른다.
- 만료 처리는 쿠폰 상태와 감사 수정 시각만 바꾼다. QR 토큰·버전·만료 시각, 발급 횟수, 재고와 사용 이력은 유지한다.
- 가게·메뉴·할인 정보는 이벤트 생성 시 고정한 스냅샷에서 읽는다. 원본 캠페인이 중단·종료돼도 발급된 쿠폰의 조회는 허용한다.
- `usable`은 쿠폰 상태와 사용 가능 시간으로만 계산한다. 회원 상태와 QR 유효성은 후속 QR 처리에서 검증하며 단건 조회는 다른 도메인의 contract를 호출하지 않는다.
- 이벤트 스냅샷이나 사용 완료 이력이 누락되면 `500 COMMON-001`로 거절한다. 세부 원인은 서버 로그에 남긴다.
- 정합성 오류는 서버 ERROR 로그에 오류 코드와 회원 쿠폰 ID, 이벤트 ID를 남긴다. 식별자를 응답 메시지에 넣지 않는다.
- DB 조회·저장 실패는 공통 예외 처리로 전달하며 만료 상태 변경을 롤백한다.

이미지 스냅샷의 컬럼 변경은 아래 [이벤트 스냅샷 저장](#이벤트-스냅샷-저장)을 따른다. 캐시는 필요하지 않다.

## 미사용 쿠폰 삭제

`DELETE /v1/coupons/{memberCouponId}`는 `CouponCommandService.deleteCoupon(memberId, memberCouponId)`로 처리한다.
클라이언트가 받는 응답과 오류는 [명세](../../api-spec/coupon-api-spec.md#쿠폰-삭제)를 따른다.
저장 구조는 [쿠폰 V1 마이그레이션](../../../src/main/resources/db/migration/coupon/V1__coupon_init.sql)의 `member_coupon`과 `coupon_daily_limit`이다.

### 서버가 하는 일

1. `CouponSecurityConfig`에서 삭제 경로에 `MEMBER` 역할을 요구한다.
2. `CouponController`는 인증 정보의 회원 ID와 양수인 쿠폰 ID를 서비스로 전달한다.
3. 서비스는 쓰기 트랜잭션 안에서 `MemberCouponRepository.findByIdAndMemberId`로 본인 쿠폰을 조회하고 `PESSIMISTIC_WRITE` 행 잠금을 확보한다.
4. 서비스는 본인 쿠폰을 찾지 못하면 소유권 오류를 던진다. 본인 소유권 확인 전에 전체 쿠폰 범위에서 존재 여부나 상태를 조회하지 않는다.
5. `MemberCoupon.remove()`는 사용 완료 상태를 먼저 거절하고 이미 삭제된 쿠폰에는 `false`를 반환한다. 서비스는 `false`이면 일일 발급 횟수를 조회하거나 차감하지 않고 반환한다.
6. 엔티티는 나머지 상태를 `REMOVED`로 바꾸고 `qr_version`을 1 올린다. `qr_token`과 `qr_expires_at`은 비운다.
7. 처음 삭제한 쿠폰의 발급 시각인 `member_coupon.created_at`에서 한국 날짜를 가져온다. 서버의 현재 날짜나 쿠폰 사용 가능 시작 시각으로 대신하지 않는다.
8. `CouponDailyLimitRepository.findByMemberIdAndBusinessDate`로 해당 회원과 발급 날짜의 카운터를 조회하고 쓰기 잠금을 확보한다.
9. 카운터가 있으면 `CouponDailyLimit.decreaseIssuedCount()`로 횟수를 1 줄인다. 횟수가 이미 0이면 그대로 두며 카운터가 없으면 새 행을 만들지 않는다.
10. JPA 변경 감지로 쿠폰 상태와 QR, 발급 횟수를 함께 저장한다. 트랜잭션이 커밋되면 컨트롤러가 명세의 성공 응답을 반환한다.

회원 쿠폰 행과 이벤트 연결, 쿠폰 코드와 발급 시각은 유지한다.
재고와 사용 이력은 수정하지 않으며 다른 도메인의 contract도 호출하지 않는다.
누적 발급 수를 집계하는 통계 조회는 삭제한 쿠폰 행을 계속 포함한다.
DB 조회나 저장 실패는 공통 예외 처리로 전달하며 상태와 QR, 발급 횟수 변경을 함께 롤백한다.
기존 컬럼만 변경하므로 새 마이그레이션은 필요하지 않다.
오류 enum은 [쿠폰 명세의 오류 표](../../api-spec/coupon-api-spec.md#오류-코드)에 있는 활성 코드만 선언한다.

### 동시 요청과 후속 QR 처리

쿠폰 행 잠금과 발급 카운터의 잠금 순서, QR 세대 재검증은 [구조와 동작 설계 7장](./coupon_구조와_동작_설계.md#7-트랜잭션과-동시성)을 따른다.
삭제와 QR 갱신의 기존 검증, 후속 사용 확정에서 필요한 검증은 [12장](./coupon_구조와_동작_설계.md#12-테스트와-미정-사항)이 갖는다.

## 매장의 날짜별 쿠폰 사용 건수 조회

클라이언트 계약은 [쿠폰 명세의 날짜별 사용 건수](../../api-spec/coupon-api-spec.md#매장의-날짜별-쿠폰-사용-건수-조회)가 갖는다.
`OwnerCouponController.getDailyUsages`는 조회 조건과 인증된 점주 ID를 `OwnerCouponService.getDailyUsages`에 전달한다.

### 서버가 하는 일

1. `CouponSecurityConfig`는 GET 경로에 OWNER 역할을 요구한다.
2. 서비스는 매장 ID와 날짜 범위를 검증하고 `OwnerStatusQuery`로 현재 ACTIVE 상태를 확인한다.
3. 서비스는 store의 [공개 조회 계약](../store/store-query-contract.md)에 있는 `canManageStore`로 현재 매장 관리 권한을 확인한다. 거절되면 사용 이력을 조회하지 않는다.
4. `CouponUsageHistoryRepository.findDailyUsageCounts`는 매장과 실제 사용 시각의 범위를 적용한 뒤 날짜별 건수를 한 번에 집계한다. 캠페인이나 이벤트의 영업일로 범위를 좁히지 않는다.
5. 서비스는 사용이 없는 날짜를 채우고 날짜별 건수의 합계를 응답에 담는다.

### 집계와 장애 처리

SQL은 `coupon_usage_history`만 읽으며 다른 엔티티를 조인하거나 사용 이력 전체를 메모리로 가져오지 않는다.
WHERE 절은 `store_id`와 원래의 `created_at` 범위를 사용한다.
`DATE(created_at)`는 조회 컬럼과 그룹, 정렬에만 적용한다.
기존 `(store_id, created_at, coupon_usage_history_id)` 인덱스로 매장과 사용 시각의 범위를 좁힌다.
날짜마다 SQL을 반복하지 않으며 서비스는 최대 31개의 응답 항목만 만든다.
읽기 전용 트랜잭션에서 원천 이력을 집계하며 별도 캐시나 배치 집계 테이블을 추가하지 않는다.
점주 상태 조회나 매장 권한 조회, DB 집계의 실패는 기존 공통 예외 처리로 전달한다.
조회 실패를 0건으로 바꾸지 않으며 기존 컬럼과 인덱스를 사용하므로 마이그레이션은 추가하지 않는다.

## 매장 쿠폰 사용 이력 조회

`GET /v1/owner/stores/{storeId}/coupon-usages`의 조회 코드는 `OwnerCouponController.getUsages`와 `OwnerCouponService.getUsages`가 갖는다.
클라이언트 계약은 [쿠폰 명세의 매장 쿠폰 사용 이력 확인](../../api-spec/coupon-api-spec.md#매장-쿠폰-사용-이력-확인)을 따른다.
`coupon-inte`에서 보관한 구현 중 이력 조회만 분리했다.

### 서버가 하는 일

1. 컨트롤러는 `@AuthenticationPrincipal`의 점주 ID와 매장 ID, 날짜 범위 및 페이지 조건을 서비스로 전달한다.
2. 서비스는 조회 조건을 검증하고 한국 날짜를 시작일 00:00 이상부터 종료일 다음 날 00:00 미만의 범위로 변환한다. 종료일의 다음 날을 계산할 수 없는 입력은 거절한다.
3. 서비스는 공통 `PageTokens`로 커서를 해석한다. 커서의 사용 시각과 양수 ID, 매장 ID와 조회 기간을 검증한다.
4. `CouponUsageHistoryRepositoryImpl`은 매장과 시각 범위를 적용한 뒤 사용 시각과 ID의 내림차순으로 조회한다. 같은 사용 시각에서는 직전 페이지의 마지막 ID보다 작은 이력부터 반환한다.
5. 저장소는 요청 크기보다 한 건 더 조회한다. 서비스는 반환할 항목만 DTO로 변환하고 다음 페이지가 있을 때 마지막 반환 항목과 조회 조건으로 커서를 만든다.
6. DTO는 저장한 할인액과 사용 시각을 그대로 반환한다. 서비스는 사용 시각에 한국 시간 오프셋을 붙이며 현재 메뉴 가격이나 할인 조건을 조회하지 않는다.

### HTTP 연결과 인가

`CouponSecurityConfig`는 회원 쿠폰 경로와 점주 사용 이력 경로를 같은 필터 체인에서 처리한다.
공용 인증 설정을 적용한 뒤 이력 조회 GET에 `hasRole("OWNER")`를 요구한다.
미인증 요청과 역할이 다른 요청의 오류는 공용 인증 규약을 따른다.
`CouponLookUpService`는 `owner.contract.OwnerStatusQuery.findCurrentStatus(ownerId)`로 DB의 현재 상태를 조회한다.
점주가 없거나 ACTIVE가 아니면 이력을 조회하지 않고 `403 AUTH-006`으로 거절한다.
상태 조회 장애는 공통 서버 오류로 전파하며 캐시나 JWT의 과거 상태로 대신하지 않는다.

현재 ACTIVE 점주에게 `StoreQueryService.canManageStore(ownerId, storeId)`로 관리 권한을 확인한다.
이 계약은 매장 미존재와 등록 중인 매장, 다른 점주의 매장을 모두 false로 반환하므로 존재 검증도 함께 수행한다.
false이면 사용 이력을 읽기 전에 공용 인가 오류인 `403 AUTH-006`으로 거절하고 매장 존재 여부는 구분해 노출하지 않는다.
점주나 매장의 공개 조회 장애는 서버 오류로 전파한다. 이벤트 스냅샷의 과거 점주 정보로 현재 권한을 대신하지 않는다.

### 조회와 저장의 기준

조회는 읽기 전용 트랜잭션에서 수행한다. 쿠폰과 사용 이력, 재고와 발급 횟수를 변경하지 않으며 쓰기 잠금을 확보하지 않는다.
공통 토큰 해석이 실패하면 빈 첫 페이지로 바꾸지 않는다. DB 예외도 빈 이력으로 바꾸지 않고 기존 공통 예외 처리로 전달한다.
쿠폰이나 이벤트를 조인하지 않으므로 사용 이력마다 추가 조회가 발생하지 않는다.
`V4__coupon_usage_history_cursor_index.sql`은 기존 매장 단일 인덱스를 `(store_id, created_at, coupon_usage_history_id)`로 교체한다.
이 복합 인덱스는 매장과 날짜 범위, 커서 정렬에 사용한다. 기존 컬럼과 데이터는 변경하지 않는다.

## 구현된 API의 문서와 로그

`CouponController`와 쿠폰 요청·응답 DTO의 Swagger 애너테이션은 현재 구현된 API의 계약을 설명한다.
요청과 응답의 기준은 [쿠폰 API 명세](../../api-spec/coupon-api-spec.md)이며 실제 인증과 역할 검사는 `CouponSecurityConfig`가 처리한다.
`CouponOpenApiConfig`는 공통 응답 팩터리와 쿠폰 DTO로 성공 예시를 구성한다. Swagger는 성공 응답만 표시하며 오류 계약은 [쿠폰 API 명세](../../api-spec/coupon-api-spec.md#오류-코드)를 따른다.
사용 이력도 같은 컨트롤러에서 계약과 성공 예시를 설명한다. 매장 검증은 [매장 쿠폰 사용 이력 조회](#매장-쿠폰-사용-이력-조회)를 따른다.

HTTP 접근 로그와 요청의 MDC는 공통 필터가 기록한다. 조회 성공과 입력 형식 오류는 공통 로그로 추적한다.
서비스는 삭제와 단건 조회의 업무 거절을 WARN으로 기록하며 오류 코드를 `reason`에 넣는다.
점주 수량·사용 이력 조회의 점주 상태 거절은 `OWNER_COUPON_QUERY_FAILED` 이벤트와 점주 ID, 고정된 사유 및 오류 코드로 기록한다.
데이터 정합성 오류는 ERROR로 기록하고 원인을 나타내는 `reason`과 오류 코드, 회원 쿠폰 ID 및 이벤트 ID를 남긴다.
삭제 성공은 공통 HTTP 접근 로그의 204 응답으로 추적한다.
비즈니스 로그는 `event=대문자_스네이크케이스` 형식을 사용하며 식별자와 고정된 사유만 전달한다. 엔티티·DTO 전체와 QR·인증 토큰은 전달하지 않는다.
DB 조회·저장 예외는 기존 공통 예외 처리와 HTTP 로그로 전달한다.

## 이벤트 스냅샷 저장

스냅샷 컬럼과 사본의 수명, 이미지 URL과 과거 데이터 보완 기준은 [구조와 동작 설계 3장](./coupon_구조와_동작_설계.md#3-저장)이 갖는다.

## QR 사용 구현 전 확정 사항

OwnerCouponService.redeem(ownerId, request)는 [HTTP 명세의 QR 사용](../../api-spec/coupon-api-spec.md#qr-사용)을 구현한다.
요청은 위경도와 QR 토큰만 받으며 할인액은 이벤트 스냅샷으로 계산한다.

| 항목 | 정책 |
|---|---|
| 요청과 결과 | 입력 필드·필수값과 201 응답은 [QR 사용 명세](../../api-spec/coupon-api-spec.md#qr-사용)를 따른다 |
| 위치 검사 | 허용 거리와 오류는 같은 명세를 따른다. 거리 계산 방법과 좌표 조회는 아래에서 정한다 |
| 할인 계산 | 지원하는 세 할인 조합과 메뉴 단건 적용, 반올림은 같은 명세의 할인 계산 규칙을 따른다 |
| 점주 상태 | owner의 공개 상태 조회를 사용하며 상태 거절은 명세의 COUPON-013을 반환한다. owner 내부 exception을 참조하지 않는다 |
| 사용한 QR | 사용 완료에도 member_coupon.qr_token의 마지막 토큰을 지우거나 교체하지 않는다. 현재 점주 인증·매장 관리 권한과 마지막 토큰의 일치를 확인한 뒤 USED를 QR 만료보다 먼저 검사한다 |

메뉴의 할인 후 가격은 이벤트 생성 때 확정해 저장한다. QR 사용 때는 스냅샷의 할인 전·후 가격 차액을 사용하며 다시 반올림하지 않는다.
전체 메뉴 정액 할인은 스냅샷 할인값으로 계산한다. 확정한 할인액을 사용 이력에 기록한다.

거리 계산은 기존 `global.geo.GeoDistance.meters`의 하버사인 계산을 사용한다.
매장 좌표는 이벤트 스냅샷의 storeId로 store의 공개 조회 계약에서 읽으며 스캔 위치는 요청 값을 사용한다.
회원의 위치와 매장 사이 거리를 검사하지 않는다. 매장 관리 권한 확인을 거리 검사로 대신하지 않는다.
store 좌표가 누락되면 검사를 통과시키지 않고 내부 데이터 누락인 COMMON-001로 처리한다. 공개 조회 장애도 공통 서버 오류로 전파한다.

사용 확정도 [구조와 동작 설계 7장](./coupon_구조와_동작_설계.md#7-트랜잭션과-동시성)의 쿠폰 잠금에 참여해야 한다.
잠금을 얻은 뒤 한 번 읽은 시각으로 QR·쿠폰의 유효 시간을 판정한다. useTime은 저장된 사용 이력의 createdAt을 반환한다.
CouponUsageHistory.record는 확정 할인액으로 이력을 만들며 Spring Data JPA의 saveAndFlush로 저장한다.
CouponUsageHistory도 BaseTimeEntity를 상속하며 ID·생성·수정 시각은 공통 매핑과 auditing을 따른다. 공통 베이스는 수정하지 않고 별도 사용 시각 컬럼도 추가하지 않는다.
응답의 useTime은 이력의 createdAt을 MySQL DATETIME(6)과 같은 마이크로초 정밀도로 반올림하고 한국 시간 오프셋을 붙인다.

### 서버가 하는 일

1. CouponSecurityConfig는 POST /v1/owner/coupon-usages에 OWNER 역할을 요구한다. OwnerCouponController는 QR 토큰과 위치의 필수값·좌표 범위를 검증한다.
2. OwnerCouponService는 READ_COMMITTED 트랜잭션에서 OwnerStatusQuery.findCurrentStatus로 점주 상태를 확인한다. 점주가 없거나 비활성이면 COUPON-013으로 거절한다.
3. MemberCouponRepository.findQrReference는 토큰의 쿠폰 ID와 QR 버전만 읽는다. 엔티티를 미리 적재하지 않아 잠금 뒤 최신 상태 조회를 가리지 않는다.
4. findByIdForUpdate로 회원 쿠폰을 잠그고 이벤트 스냅샷을 읽는다. StoreQueryService.canManageStore로 현재 관리 권한을 확인한다. USED 상태라면 마지막 토큰·버전을 확인하여 교체된 QR과 실제 사용한 QR의 재요청을 구분한다.
5. MemberQueryService로 쿠폰 소유 회원의 현재 상태를 확인하고 StoreQueryService.findAllById로 현재 매장 좌표를 읽는다. 정상적인 부재와 조회 장애를 구분한다.
6. 잠금을 얻은 뒤 Clock으로 판정 시각을 한 번 읽고 거리와 스냅샷 할인액을 계산한다. MemberCoupon.redeem은 최신 상태·토큰·버전·유효 시간을 검증한 뒤 USED로 바꾸며 마지막 QR 토큰을 유지한다.
7. CouponUsageHistoryRepository.saveAndFlush는 확정 할인액을 가진 이력을 JPA로 저장하고 생성된 ID와 auditing 시각을 반환한다. 쿠폰 상태는 JPA 변경 감지로 저장한다.
8. 두 저장이 커밋된 뒤 기존 201 응답을 반환한다. 하나라도 실패하면 상태·사용 이력을 함께 되돌린다.

CouponEvent.calculateDiscount는 인자 없이 자기 스냅샷의 메뉴 가격 차액 또는 정액 할인값으로 할인액을 계산한다.
수량·반올림의 공개 규칙은 HTTP 명세가 소유하며 사용 시 메뉴 가격을 재조회하지 않는다.
지원하지 않는 전체 메뉴 정률 스냅샷은 내부 데이터 오류인 COMMON-001로 거절하며 쿠폰 상태와 사용 이력을 변경하지 않는다.

V6__coupon_qr_redemption.sql은 QR 토큰 조회 인덱스를 추가했다.
V7__coupon_supported_discount_combinations.sql은 할인 조합 CHECK에서 ALL + PERCENT를 제외한다.
기존에 ALL + PERCENT 이벤트가 있으면 V7 적용이 실패한다. 마이그레이션은 과거 스냅샷을 임의로 변환하거나 삭제하지 않는다.

인덱스와 구현은 사용자 요청으로 #368에서 함께 진행하며 #367은 이 이슈에 합쳤다.

### 검증과 남은 연동

OwnerCouponServiceTest의 Redeem이 서비스와 할인 계산·상태 전이를 검증한다.
CouponIntegrationTest의 Redeem 중첩 테스트는 실제 MySQL과 보안 체인을 사용해 HTTP 요청부터 커밋 결과까지 확인한다.
MockMvc 검증과 함께 임의 포트로 실행한 서버에 JWT 쿠키를 담은 실제 HTTP 요청을 보낸다.
CouponIntegrationTest 하나가 서버 설정과 빈 대역을 소유하고 QR 사용도 중첩 테스트로 묶는다.
점주 상태와 매장 공개 조회, 점주 AT의 도메인별 폐기 검증은 대역을 사용한다. JWT 서명·쿠키 처리와 member 조회, 쿠폰 DB 처리는 실제 구현이다.
이 검증을 owner·store 제공자의 실제 DB 조회 검증으로 표시하지 않는다.
발급·사용 이벤트의 공개 계약과 분석 수신 시점은 [구조와 동작 설계 5·7장](./coupon_구조와_동작_설계.md#5-모듈-연결)이 갖는다.

## 확정 업무 정책

| 항목 | 정책 |
| --- | --- |
| 할인 조합과 계산 | [QR 사용의 확정 사항](#qr-사용-구현-전-확정-사항)을 따른다 |
| QR 사용 요청과 거리 | [확정 사항](#qr-사용-구현-전-확정-사항)을 따른다 |
| 발급 시간 | 시스템에서 정한 발급 시간을 사용하며 쿠폰 사용 가능 시간으로 대체하지 않음 |
| 사용 이력 조회 | 조회 기간의 길이에 상한을 두지 않고 커서 페이지네이션 적용 |
| 점주 인증과 매장 검증 | 사용 이력 조회의 상태 판정과 매장 검증 범위는 [HTTP 연결과 인가](#http-연결과-인가)를 따른다. QR 사용의 점주 상태 정책은 해당 API에 유지한다 |
| 오늘 이벤트 조회 대상 | `ACTIVE`, `SCHEDULED` 이벤트만 조회. `PAUSED`, `ENDED` 제외 |
| 과거 가격 | [구조와 동작 설계의 스냅샷 기준](./coupon_구조와_동작_설계.md#3-저장)을 따른다 |
| 기존 발급 쿠폰 | 캠페인 중단·종료 후에도 원래 사용 가능 시간 안에서 QR 생성·사용 허용. 쿠폰·QR 상태와 현재 계정·매장 권한 검사는 유지 |
| 사용 완료된 QR 재요청 | `409 COUPON-012`, "이미 사용된 쿠폰입니다."로 응답. 기존 성공 결과를 다시 반환하지 않으며 쿠폰 사용과 이력을 추가하지 않음 |
| 회원 상태 검증 | 발급·QR 생성·갱신·새 사용 확정 시 쿠폰 소유 회원의 현재 `ACTIVE` 상태 요구. `SUSPENDED`, `WITHDRAWN`은 거절. 발급·QR 생성·갱신·사용 확정은 모두 `403 COUPON-003` |
| 조회의 usable | `ISSUED`이고 사용 가능 시간 안이면 `true`. 회원 계정 상태는 계산과 목록 필터에 포함하지 않으며 최종 사용 가능 여부는 처리 시 별도 검증 |
| 쿠폰 조회 식별자 | 목록·단건 응답에서 `eventId` 제외. 발급된 쿠폰의 조회·QR 생성·삭제는 `memberCouponId` 사용. 이벤트 스냅샷 연결은 서버 내부에서 유지하며 발급 요청과 이벤트 상태 조회의 `eventId`는 유지 |

## 발급 성능 목표

| 구분 | 기준 |
| --- | --- |
| 총 요청 수 | 50,000건 |
| 유입 구간 | 발급 시작 후 30초 |
| 순간 피크 | 첫 10초 25,000건 = 2,500 RPS |
| 나머지 유입 | 이후 20초 25,000건 = 1,250 RPS |
| HTTP 5xx, 네트워크 드롭 | 0.1% 이하 |
| API P95 | 700ms 이하 |
| API P99 | 1000ms 이하 |
| 중복 발급 | 0건 |
| 1인 일 최대 3건 초과 | 0건 |
| 재고 초과 발급 | 0건 |
| 성공 응답과 DB 발급 건수 불일치 | 0건 |

## 미확정 업무 정책

API 규약에 맞추는 과정에서 정할 수 없는 업무 정책은 구현 전에 별도로 확정한다.

| 항목 | 확인할 내용 |
| --- | --- |
| 보관 기간 | 점주 사용 이력과 누적 쿠폰 요약의 보존 방식. API 조회 기간의 길이는 제한하지 않음 |
| 기존 쿠폰 보완 | 기존 이벤트의 과거 정보 복원을 위한 데이터 출처와 보완 방법. 기준은 과거 가격이며 현재 값을 대신 채우지 않음 |

## 구현 정합성 확인

- 공통 응답은 `ResponseEnvelope`, 커서 목록은 `CursorPageResponse`를 재사용한다.
- 쿠폰 업무 오류는 `CouponErrorCode`와 `CouponException`으로 정의하고 기존 `GlobalExceptionHandler`를 사용한다.
- COUPON 코드, 상태, 메시지는 명세의 오류 표와 일치시킨다. STORE 오류는 store 도메인 소유다.
- 스냅샷 생성 시 campaign·store의 공개 contract를 사용한다. 찜과 현재 계정·매장 관리 권한의 검증도 소유 도메인의 contract를 사용하며 다른 도메인의 엔티티나 repository를 직접 참조하지 않는다.
- 회원 상태는 member의 공개 contract로 DB 현재 상태를 확인한다. 인증 토큰의 과거 상태나 조회 시점의 `usable`로 대체하지 않는다. 점주가 호출하는 QR 사용에서도 쿠폰 소유 회원을 기준으로 확인한다. 회원 상태에 `ONBOARDING`은 없으며 온보딩 완료 여부는 별도 정보다.
- 스냅샷이 준비되지 않은 이벤트는 발급을 열지 않는다. 이후 발급·조회·사용에서 현재 원본 값으로 대체하지 않는다.
- 재고, 일일 한도, 중복 발급, QR 단일 사용은 동시 요청에서도 불변식을 지켜야 한다.
- QR 사용, 삭제, 갱신은 [구조와 동작 설계 7장](./coupon_구조와_동작_설계.md#7-트랜잭션과-동시성)의 잠금 기준을 따른다.
- 후속 QR 사용의 시각 판정과 기록은 [구현 전 확정 사항](#qr-사용-구현-전-확정-사항)을 따른다.
- 사용 완료 QR의 재요청은 [구현 전 확정 사항](#qr-사용-구현-전-확정-사항)의 마지막 토큰 유지 기준을 따른다. 원문 QR 토큰은 로그에 남기지 않는다.
- 미사용 쿠폰 삭제의 저장 처리와 보존 대상은 [미사용 쿠폰 삭제](#미사용-쿠폰-삭제)를 따른다.
- 경로, 필드, 상태 코드, 시간대, 삭제 멱등성, 커서 동점 정렬과 필터 변경 시 초기화 규칙, 오류 카탈로그와 스냅샷 표시·사용 기준의 일치를 계약 검증에 포함한다.
- 쿠폰의 오류 통합은 [명세의 오류 코드](../../api-spec/coupon-api-spec.md#오류-코드)를 따른다. 다른 도메인의 공통 오류 처리에는 적용하지 않는다.
- 쿠폰 발급과 당일 발급 한도, 쿠폰 통계 조회, 목록·단건 조회, 미사용 쿠폰 삭제, QR 생성·갱신·사용, 매장 사용 이력 조회와 이벤트 상태 조회를 구현했다. 사용 이력의 HTTP 인가는 [매장 쿠폰 사용 이력 조회](#매장-쿠폰-사용-이력-조회)의 연결 조건을 따른다.
