# 가게 (store) API 설계

요청과 응답은 [store-api-spec.md](../../api-spec/store-api-spec.md)가 소유한다.
이 문서는 이슈 #331의 조회 구현, 다른 도메인에 공개하는 요약 저장 계약, 이슈 #375의
점주 인테리어 이미지 업로드, 이슈 #389의 점주 대표 메뉴와 메뉴 이미지 업로드를 정의한다.

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
전체 약관이 필수이므로 요청 자체를 전체 동의로 처리한다. 약관별 정책 설정과 선택 약관 처리는 두지 않는다.
owner는 점주 행을 잠그고 DB 현재 상태가 ONBOARDING인지 확인한 뒤 store.contract.OwnerTermsAgreementRecorder를 호출한다.
store는 owner 내부에 의존하지 않는다.

잠금 순서는 점주 행 → 진행 상태 행 → 약관 데이터다.
OwnerTermsAgreementService는 호출자의 쓰기 트랜잭션에 참여하고 진행 상태를 초기화·잠근다. 약관 동의 요청에서는 버전을 받지 않는다.
진행 상태 GET은 읽기만 수행하며 행이 없으면 TERMS, version=0을 반환한다.
최초 생성은 점주 행 잠금 아래 약관 저장에서만 수행하므로 최초 조회와 저장이 경합해도 중복 생성하지 않는다.

약관은 owner_id UNIQUE로 점주당 한 행을 저장한다.
agreed_at은 공용 Clock을 사용한 최초 동의 저장 시각이며 이후 갱신하지 않는다.
withdrawn_at은 향후 확장을 위해 NULL을 허용한다. 최초 동의 시 null로 저장하며 현재 수정·재동의·철회 기능은 제공하지 않는다.
동의 완료 여부는 agreed=true이고 agreed_at이 존재하며 withdrawn_at이 null인지로 판단한다.
약관 종류·버전은 애플리케이션에서 사용하지 않으며 약관별 정책 설정은 제거한다. required와 agreed는 향후 확장을 위해 유지하며 현재 동의 저장에서는 서버가 둘 다 true로 설정한다. 약관 동의는 점주 행 잠금과 기존 동의 확인·UNIQUE 제약으로 중복 저장을 막는다. 진행 상태의 현재 버전은 서버가 잠금 아래 읽으며 완료 후 증가한 버전을 다음 단계용 응답에 포함한다.

기존 동의 행이 있으면 TERMS-001로 거부한다. 기존 행이 없더라도 현재 단계가 TERMS가 아니면 STORE-002로 거부한다.
최초 성공 시에만 TERMS를 완료해 STORE_INFO로 진행한다. reviseStep은 호출하지 않으며 동의 완료 후의 약관 수정은 허용하지 않는다.
약관과 진행 상태는 함께 커밋하거나 롤백한다. 점주 상태·튜토리얼·토큰·인증 쿠키는 변경하지 않는다.
기존 동의 재요청과 약관 UNIQUE 충돌은 TERMS-001로 처리하며 다른 DB 오류는 그대로 전파한다.
약관 오류는 전용 접두어 TERMS-001을 사용해 develop의 이미지 오류 STORE-005~010과 충돌하지 않는다. 약관 오류 번호와 STORE 오류 번호가 각각 연속이므로 공통 오류 코드 목록 검사도 유지한다. 정책 미설정과 약관별 미동의 오류는 제거한다.

### 스키마 이관

기존 V1은 체크섬을 유지하고 V5로 변경한다.
V5는 terms_type과 terms_version을 삭제하지 않고 NULL을 허용해 새 코드가 두 값을 생략하고 저장할 수 있게 한다.
두 컬럼은 새 코드 배포와 구버전 인스턴스 종료를 확인한 뒤 별도 배포의 후속 마이그레이션에서 제거한다.
같은 배포에 삭제 마이그레이션을 포함하지 않는다. 기존 행 통합과 점주당 한 행 제약은 유지하므로 약관별 여러 행을 쓰는 코드와의 호환성을 보장하는 것은 아니다.
기존 점주별 여러 행은 한 행으로 합친다. 모든 기존 기록이 동의 상태이고 철회되지 않은 경우에만 마지막 갱신 시각을 agreed_at으로 이관한다.
이관 시 required=true로 설정한다. 미동의나 철회가 섞인 기록은 agreed=false, agreed_at=null로 남겨 전체 필수 약관 동의로 승격하지 않는다.
기존 철회 시각은 보존한다. 실제 최초 동의 시각은 과거 스키마에 없으므로 이관 시각과 새 요청의 서버 동의 시각을 구분한다.

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

StoreInfoServiceTest가 입력 검증·최초 생성·수정·Mock 실패·진행 상태 오류를 확인한다.
OwnerStoreInfoServiceTest가 점주 상태·계정 누락·DB 장애와 저장 계약 호출을 확인한다.
StoreInfoIntegrationTest는 약관 동의 후 기본 정보 등록·HTTP 보안·실제 저장·다른 단계 데이터 보존·동시 요청·후속 갱신 실패의 롤백을 확인한다.

### 가게 기본 정보 저장 공개 계약

StoreInfoSaver와 입출력 record인 StoreInfoSaveCommand·StoreInfoSaveResult는 store.contract가 소유한다.
StoreInfoService가 자기 도메인의 공개 계약을 구현하며 owner는 이 계약만 참조한다.
호출자는 같은 쓰기 트랜잭션에서 본인 점주 행을 먼저 잠그고 ONBOARDING 상태를 확인해야 한다.
트랜잭션이 없으면 MANDATORY가 호출을 거부한다.
공개 입출력 타입은 HTTP DTO나 내부 엔티티를 참조하지 않는다.
진행 단계와 검증 결과는 문자열로 전달하고 owner의 HTTP 응답 DTO가 결과를 변환한다.
가게 기본 정보와 Mock 판정 규칙은 store 내부에 유지하며 공개 명령을 받은 뒤 검증한다.

## 내 가게 조회 (#427)

HTTP 계약은 [store-api-spec.md](../../api-spec/store-api-spec.md)의 `GET /v1/owner/store` 절이 소유한다.
OwnerStoreController → OwnerStoreService → store.contract.StoreQueryService.getOwnerStoreOverview 순서로 호출한다.
owner는 DB에서 현재 계정과 상태를 확인하고 store는 본인 가게의 업무 데이터를 조회한다.
store가 owner를 역방향 참조하지 않으며 StoreQueryService의 기존 최종 등록 전용 메서드의 조회 조건은 유지한다.
OwnerStoreSnapshot은 도메인 간 계약이며 OwnerStoreResponse가 HTTP 응답으로 변환한다.

하나의 읽기 전용 트랜잭션에서 가게, 사진, 메뉴, 영업시간을 각각 조회한다.
MySQL 기본 REPEATABLE READ의 스냅샷으로 읽으며 쓰기 잠금과 진행 상태 초기화는 수행하지 않는다.
가게가 없으면 후속 목록을 조회하지 않는다. 사진과 메뉴 이미지는 VERIFIED만 연결한다.
이미지 URL은 기존 StoreImageUrlResolver로 생성하며 S3에 요청하지 않는다.
TERMS는 develop에 병합된 PR #148의 필수 약관 단일 동의 계약을 따른다.
OwnerStoreOverview를 HTTP DTO로 변환해 가게 생성 전에도 약관·진행 정보를 반환한다.
완료 단계는 현재 업무 데이터를 확인한다. 약관은 유효한 단일 동의, STORE_INFO는 필수 5항목과 검증 성공,
LOCATION은 장소·주소·좌표, HOURS는 저장 행 존재, IMAGES는 검증된 대표 이미지로 판단한다.
메뉴는 선택이며 입력한 메뉴가 있으면 3개 이하·메뉴명·가격·검증된 이미지를 확인한다.
최종 등록 API가 추가 검증을 확정하면 조회의 canComplete와 같은 규칙을 사용해야 한다.

## 입점 등록 진행 상태 조회 (#453)

HTTP 계약과 단계별 완료 기준은 [가게 API 명세](../../api-spec/store-api-spec.md#get-v1owneronboarding)가 소유한다.
GET /v1/owner/onboarding의 owner 진입점에서 점주 상태를 확인한 뒤 StoreOnboardingQuery를 호출한다.
StoreRegistrationProgressQueryService가 공개 계약을 구현하며 StoreOnboardingStatus를 반환한다.
입출력 계약은 store.contract에 두고 HTTP 응답 변환은 owner에서 처리한다.

StoreOnboardingQueryRepository는 본인 owner_id를 바인딩한 SELECT 한 번으로 약관·가게·진행 상태와 업무 데이터 완료 조건을 읽는다.
가게·진행 상태가 없는 경우도 한 행을 반환하며 다른 점주의 가게를 연결된 store_id로 따라가지 않는다.
업로드 확인은 DB의 VERIFIED와 객체 키를 사용하며 GET에서 S3를 호출하지 않는다.
DB 오류를 최초 진입이나 미완료 상태로 숨기지 않는다.
StoreOnboardingReadiness가 첫 미완료 단계와 최종 등록 가능 여부를 계산한다.
후속 최종 등록 구현은 같은 판정 기준을 사용해 요청 시점에 다시 검증해야 한다.
저장된 resume_step과 다른 화면을 안내하더라도 진행 상태와 버전은 변경하지 않는다.
단계 저장 API가 쓰는 StoreRegistrationProgressResponse는 세 필드 계약을 유지한다.

## 점주 인테리어 이미지 업로드(`#375`)

**서버가 하는 일**

1. presigned URL 발급(`prepareUpload`): 점주 상태(`ONBOARDING`)와 파일 형식·크기를 검증하고,
   그 가게의 인테리어 이미지 개수를 세어 `sortOrder`를 매긴 뒤 `store_image` 행을
   `upload_status=PENDING`으로 먼저 만든다. 그다음 `S3Presigner`로 그 `objectKey`에 대한
   presigned PUT URL을 발급한다.
2. 업로드 확인(`confirmUpload`): `uploadId`로 `PENDING` 행을 찾아 본인 가게 소유인지 확인하고,
   `S3Client.headObject`로 실제 업로드됐는지 확인한 뒤 `VERIFIED`로 바꾼다.
3. 단계 완료(`complete`): 그 가게에 로고(`LOGO`) 이미지가 있는지 확인한 뒤
   `StoreRegistrationProgressService.completeStep(ownerId, version, IMAGES, null)`을 불러
   `MENU`로 진행한다.

**정한 이유**

- **`upload_status`는 `PENDING` → `VERIFIED`만 쓴다.** DB CHECK 제약은 `UPLOADED`도 허용하지만,
  점주가 "업로드 끝냈다"고 자체 보고하는 중간 상태를 둘 이유가 없어 단순하게 뺐다(#375 결정).
  `headObject` 확인 자체가 "실제로 업로드됨"의 증거라 중간 상태가 없어도 정합성이 깨지지 않는다.
- **인테리어 `sortOrder`는 점주가 지정하지 않고 서버가 업로드 순서대로 자동으로 매긴다(#375 결정).**
  재배치(순서 바꾸기) 기능이 없는 지금 범위에서는 점주 입력을 받을 이유가 없어 더 단순한
  쪽을 택했다. 재배치가 생기면 이 결정을 다시 봐야 한다.
- **로고는 이 기능으로 새로 올리지 않고 존재만 확인한다(#375 결정).** 로고는 가게 입점
  과정에서 이미 필수로 등록돼 있다는 전제다. 가게 담당이 "대표 메뉴가 선택으로 바뀌어
  온보딩이 끝나도 메뉴가 없을 수 있다"고 확인해 줬지만, 그건 메뉴 얘기고 로고(`image_type=LOGO`)는
  `store_image`의 `uk_store_image_single_logo` 제약과 별개로 여전히 필수 전제다.
- **`completeStep`에 필요한 `version`을 돌려주는 조회 API(`GET /v1/owner/onboarding`)를
  이 이슈에서 같이 만든다.** 등록 단계 API 전체가 아직 하나도 없어 클라이언트가 현재 버전을
  얻을 곳이 없었다. 이 조회는 저장된 진행 상태를 읽으며 없으면 TERMS와 버전 0을 반환한다.
  GET은 진행 상태를 생성하지 않는다. 최초 생성은 점주 행 잠금 아래 약관 저장에서 처리한다.
- **presigned 업로드 흐름을 이 이슈에서 처음 구현한다.** `S3Config`는 "가게 이미지, 포스터
  이미지, 증빙 서류가 같은 경로를 쓴다"고 전제해 뒀지만 실제로 쓰는 코드는 없었다. 이 구현은
  `store_image` 테이블(업로드 상태 추적 컬럼 포함)에 맞춘 것이라, 포스터 이미지처럼 추적
  컬럼이 없는 테이블은 그대로 재사용할 수 없고 패턴만 따라야 한다.
- **콜론 커스텀 메서드 경로(`:prepareUpload` 등)를 쓴다.** `api-spec.md` 공통 규약의 예시이고
  `AdminRefundController`(`/{id}:approve`)가 이미 같은 표기를 쓴다. 다만 이번엔 경로 변수 없이
  컬렉션 경로에 바로 붙는 형태라 실제로 라우팅되는지 통합 테스트로 확인했다(`StoreImageSecurityIntegrationTest`).

## 점주 대표 메뉴와 메뉴 이미지 업로드(`#389`)

**서버가 하는 일**

1. 메뉴 등록(`POST /v1/owner/store-menus`): 그 가게의 메뉴 개수를 세어 `sortOrder`를 매긴 뒤
   `store_menu` 행을 만든다. 이름·가격·설명(선택)만 저장하며 이미지는 아직 없다.
2. presigned URL 발급(`prepareUpload`): 요청한 `menuId`가 본인 가게의 메뉴인지 확인하고
   파일 형식·크기를 검증한 뒤 `store_menu_image` 행을 `upload_status=PENDING`으로 먼저
   만든다. 그다음 `S3Presigner`로 그 `objectKey`에 대한 presigned PUT URL을 발급한다.
3. 업로드 확인(`confirmUpload`): `uploadId`로 `PENDING` 행을 찾아 그 메뉴가 본인 가게 소유인지
   확인하고, `S3Client.headObject`로 실제 업로드됐는지 확인한 뒤 `VERIFIED`로 바꾼다.
4. 단계 완료(`complete`): 그 가게의 메뉴 중 이미지가 `VERIFIED`가 아닌 메뉴이 하나라도 있으면
   거부하고, 없으면(메뉴가 0개여도 통과한다) `StoreRegistrationProgressService.completeStep
   (ownerId, version, MENU, null)`을 불러 버전을 올린다. `MENU`는 마지막 입력 단계라
   다음 단계로 넘어가지 않는다.

**정한 이유**

- **메뉴 저장과 이미지 업로드를 분리한다.** `store_menu_image.store_menu_id`가 `NOT NULL` FK로
  `store_menu`를 가리켜서, 가게 인테리어 이미지(`#375`, `store_id`로 바로 참조하며 가게는
  이미 존재)와 달리 메뉴는 이 이슈에서 처음 만들어지는 대상이라 이미지보다 먼저 존재해야
  한다. 그래서 이름·가격·설명 저장(1번)과 이미지 업로드(2~3번)를 별개 API로 나눴다(이슈
  본문에서 사용자가 직접 정리, `#389` 결정).
- **`upload_status`는 `PENDING` → `VERIFIED`만 쓴다.** `store_image`와 같은 이유로 중간
  상태(`UPLOADED`)를 두지 않는다(`#375` 결정을 그대로 따름). 같은 이유라 `StoreImageUploadStatus`
  enum을 새로 만들지 않고 재사용한다.
- **메뉴 `sortOrder`는 점주가 지정하지 않고 서버가 등록 순서대로 자동으로 매긴다.** 인테리어
  이미지(`#375`)와 같은 이유로, 재배치 기능이 없는 지금 범위에서는 점주 입력을 받을 이유가
  없다. 재배치가 생기면 이 결정을 다시 봐야 한다.
- **메뉴 수정·삭제는 이 이슈 범위 밖이다.** 등록만 다루며, `store_menu`에 수정이나 삭제 API를
  두지 않는다. 삭제 기능이 생기면 메뉴 할당량이나 카운터를 따로 감소시킬 경로가 필요하다.
- **기존 조회 전용 `StoreMenu` 엔티티를 확장해 쓴다.** 새 쓰기 전용 엔티티를 같은 테이블에
  중복 매핑하지 않기 위해 `@Immutable`을 떼고 `description`, `sortOrder` 컬럼 매핑과
  `register(...)` 팩터리를 추가했다. 기존에 읽던 `id`, `name`, `price`는 그대로라
  `StoreMenuInfo`(쿠폰 contract)와 `StoreRepositoryImpl`의 메뉴 조회에는 영향이 없다.
- **`menuId` 소유권 확인 실패는 "없음"과 "다른 가게 소유"를 구분하지 않고 같은 `403`
  (`STORE-012`)으로 묶는다.** 업로드 확인 실패(`STORE-008`)와 같은 근거다(`api-spec.md`).
