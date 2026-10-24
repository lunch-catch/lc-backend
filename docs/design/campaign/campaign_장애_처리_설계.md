# campaign 장애 처리 설계

공통 기준은 [장애_처리_설계_가이드.md](../장애_처리_설계_가이드.md)다. 여기서는 그 기준을
그대로 따르는 대상은 적지 않고, campaign 도메인이 기대는 대상과 벗어난 것만 적는다.

## 이 도메인이 기대는 대상

| 유형 | 대상 |
|---|---|
| DB | MySQL(JPA), `campaign`/`template`/`store_audience`/`poster` 등. `ops.contract.PlatformSettingQueryService.getMinDailyBudget()`(같은 트랜잭션 안의 도메인 간 직접 호출, 외부 API 아님)도 DB 읽기와 같은 처리를 받는다(`#330`). `store.contract.StoreQueryService`(가게명·메뉴명 조회, 포스터 생성, `#376`)도 같은 처리다 |
| 외부 API | Bedrock LLM(템플릿 생성·수정, `template/client/BedrockTemplateHtmlGenerator`) |
| 외부 API | S3(`S3Presigner`, `S3Client`. presigned URL 발급과 `headObject` 확인, 포스터 이미지 업로드, `#376`. 포스터 자동 검수의 `GetObject`, `#535`) |
| 외부 API | Rekognition(`DetectText`, 포스터 자동 검수, `poster/client/PosterImageInspector`, `#535`) |
| 캐시 | 해당 없음(이 도메인은 캐시에 기대지 않는다. `owner.contract.OwnerStatusQuery`가 캐시를 쓰지만 그건 owner 도메인이 소유한다) |
| 애플리케이션 | 대용량 조회만 훑음(`#341`). 나머지(요청 폭주, graceful shutdown, 다중 서버)는 아직 훑지 않음 |
| 롤백 안 되는 효과 | 해당 없음(아직 훑지 않음) |

## 벗어난 것

### 00:00 묶음의 가게 요약: 캠페인 한 건의 데이터 오류

기본 처리는 "실패하면 단계를 실패시킨다"지만 가게 요약 단계(30)는 캠페인 한 건의 데이터 오류로는 실패하지
않는다(`#903`). 종료(10)와 시작(20)이 데이터 오류 캠페인만 건너뛰는 것과 같다. 요약 단계가 멈추면 그 뒤의
정산 예약(50), 충전 판정(60), 후보 적재(75)가 돌지 않아 그날 모든 점주의 광고가 나가지 않는다.

- 청크(500건)를 `updateAll` 로 한 번에 저장하다 `IllegalArgumentException` 이 나면, 그 청크만 캠페인 한 건씩
  각자 트랜잭션에서 다시 저장한다. 오류 난 캠페인만 ERROR 로그(`CAMPAIGN_DAILY_SUMMARY_SKIPPED`)를 남기고
  건너뛴다. 그 캠페인은 그날 요약이 없어 가게 목록과 상세에서 빠지고 광고와 정산에는 영향이 없다.
- 데이터 오류가 아닌 실패(DB 오류 등)는 지금처럼 단계를 실패시킨다.
- 운영이 데이터를 고칠 때까지 같은 ERROR 로그가 매일 남는다.

### DB: 제약 위반, 데이터 불일치, 교착 상태

기본 처리는 "재시도하지 않고 그대로 올린다"다. 캠페인 등록 1단계(쿠폰 조건, `#244`,
`CampaignCouponConditionService.handle`)가 `uk_campaign_draft_store` 위반에서만 벗어난다.

이 제약은 데이터가 어긋나서 나는 게 아니라 "가게당 DRAFT 1개"를 두 요청이 동시에 쫓다가
늦게 온 쪽이 걸리는 경쟁의 흔적이다. 늦은 쪽이 받아야 할 결과(그 가게의 DRAFT에 자기 쿠폰
조건을 덮어쓰는 것)는 먼저 조회했을 때 DRAFT가 있었다면 탔을 경로와 같다. 그래서 재조회
후 덮어쓰기는 재시도가 아니라 원래 의도한 "이어 쓰기" 경로로 들어가는 것일 뿐이다.
다른 제약 위반은 기본 처리대로 그대로 올린다.

같은 자리에서 제약 위반 대신 교착 상태로 나타나는 경우도 있다. `findByStoreIdAndStatus`의
`PESSIMISTIC_WRITE`는 그 가게에 DRAFT가 하나도 없으면 갭 락이 되는데, 같은 가게로 동시에
들어온 두 생성 요청이 이 갭을 같이 잠근 뒤 각자 INSERT하면 상대의 갭 락에 막혀 MySQL이
교착 상태로 판정해 한쪽을 롤백시킨다. 롤백된 쪽이 받아야 할 결과는 위 제약 위반과 똑같이
"재조회해 덮어쓰기"라 같은 경로로 묶는다.

### 외부 API: Bedrock LLM

기본 처리는 "멱등한 연산의 재시도 가능한 오류만 재시도하고 한도에서 포기한다"다. 템플릿
생성·수정(`#182`)이 다음 지점에서 벗어난다. 이유는 각 코드의 블록 주석에 있고 여기서는
반복하지 않는다.

- `ThrottlingException`/`ServiceUnavailableException`/`ModelTimeoutException` 등 일시적
  실패는 재시도하지 않고 즉시 503(`POSTER-012`)으로 끝낸다.
- 가드레일(콘텐츠 검증) 위반은 교정 지시를 덧붙여 **1회만** 재시도한다(`POSTER-013`).
- 호출 빈도 제한(관리자 1명당 분당 3회, `#227`)은 멱등성·재시도와는 다른 축이라 이 절이
  다루는 범위가 아니다(`TemplateGenerationRateLimiter`).

### 외부 API: 포스터 자동 검수의 S3, Rekognition

기본 처리는 "멱등한 연산의 재시도 가능한 오류만 재시도하고 한도에서 포기한다"다. 포스터 자동
검수(`#535`)는 재시도하지 않고, 실패와 타임아웃을 예외로 올리지도 않는다. 대신 `FAIL`(사유
`MODERATION_TOOL_FAILURE`)로 끝내고 결과를 저장한다.

명세서 57행이 "외부 호출 실패나 타임아웃도 FAIL 로 처리하며, 점주는 수정 후 다시 활성화를
요청한다"로 정했다. 점주가 다시 요청하는 것이 재시도 역할을 해서 서버가 따로 재시도할 이유가 없다.
그래서 `RekognitionConfig` 는 SDK 자체 재시도도 끈다(`maxAttempts(1)`).

이미지를 디코딩하지 못하는 것은 도구 장애가 아니라 점주가 고칠 수 있는 원인이라 `IMAGE_UNREADABLE`
로 따로 나눈다. Rekognition 의 `InvalidImageFormatException` 도 같은 사유로 옮긴다.

### 애플리케이션: 템플릿 버전 삭제 중 배치 서버가 멈춤

04시 템플릿 버전 삭제(`TemplateVersionPurgeScheduler`, `CAMPAIGN_TEMPLATE_VERSION_PURGE`)는
`StandaloneJob`을 구현해 등록돼 있다(`#622`). 실행 중에 서버가 멈추면 다른 서버가 이어받아 같은
영업일로 다시 지우고, `FAILED`로 닫힌 행은 관리자가 다시 실행할 수 있다. 청크마다 따로 커밋되므로
멈춘 실행이 지운 만큼은 사라져 있고, 다시 돈 실행은 남은 소프트삭제 버전만 지운다.

## 아직 정하지 못한 것

| 항목 | 비고 |
|---|---|
| 캐시, 롤백 안 되는 효과 유형의 점검 | 이번 PR(`#244`)이 건드린 범위 밖이라 훑지 않았다 |
| 애플리케이션: 요청 폭주, graceful shutdown, 다중 서버 | 이번 PR(`#341`)이 건드린 범위 밖이라 훑지 않았다. 대용량 조회는 훑었다(위 표) |
| S3 호출의 재시도 | 기본 처리는 "멱등한 연산의 재시도 가능한 오류만 재시도"다. `headObject`(읽기, 멱등)와 presign 발급(로컬 서명, 네트워크 호출 없음) 모두 지금은 재시도를 두지 않는다. 이 저장소에 재시도 인프라가 아직 없어 store의 `#375`/`#389`와 같은 이유로 `#376`도 그대로 전파하고 넘어갔다(`store_장애_처리_설계.md`와 같은 결론) |
