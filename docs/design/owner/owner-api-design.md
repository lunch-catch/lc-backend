# 점주 API 설계

회원가입·로그인·내 정보 조회·최초 튜토리얼 완료의 서버 내부 처리를 설명한다. 프론트 계약은 [owner-api-spec.md](../../api-spec/owner-api-spec.md), 공통 토큰 정책과 점주 재발급·로그아웃 설계는 [auth-api-design.md](../auth/auth-api-design.md#점주)를 따른다. 현재 브랜치 구현을 기준으로 한다. 가게 최종 등록과 ACTIVE 전환은 [가게 API 설계](../store/store-api-design.md#가게-최종-등록-502)를 따른다.

## 회원가입

「기능 명세서」 FR-028과 기존 `OwnerSignupController`·`OwnerSignupService`를 기준으로 한다.

1. 요청 DTO에서 이메일·비밀번호의 필수값·형식·길이를 검증한다.
2. 이메일 중복을 먼저 확인하고, BCrypt 해싱은 DB 저장 트랜잭션 밖에서 수행한다.
3. `Owner.create`로 OWNER·ONBOARDING 계정을 생성한다. `tutorialViewed`는 false이며 RT 정보는 비어 있고 발급 순번은 0이다.
4. `TransactionTemplate` 안에서 저장하고 커밋한 뒤 가입 정보를 반환한다. 토큰은 발급하지 않는다.

사전 중복 검사를 동시에 통과해도 DB의 `uk_owner_email` UNIQUE 제약으로 중복을 막는다. 해당 제약 위반만 OWNER-001로 바꾸고, 다른 DB 실패는 공통 예외 처리에 맡긴다. 이메일을 임의로 소문자화하거나 비밀번호를 정리하지 않으며 중복 판정은 DB 제약을 따른다.

## 로그인

「기능 명세서」 FR-029와 기존 `OwnerLoginController`·`OwnerLoginService`를 기준으로 한다.

1. 요청을 검증하고 이메일로 계정을 조회한다. 계정이 없어도 Dummy BCrypt Hash와 비교하며, BCrypt 연산은 저장 트랜잭션 밖에서 수행한다.
2. 계정 없음·비밀번호 불일치·SUSPENDED·WITHDRAWN은 모두 AUTH-001로 처리한다.
3. 독립된 트랜잭션에서 `findByIdForLogin`으로 점주 행을 잠근다. 상태와 비밀번호 해시를 다시 확인한다.
4. `OwnerSessionService.issue`로 별도 세션을 저장하고 최근 로그인 시각을 갱신한다. 기존 기기의 세션은 유지한다.
5. DB 커밋 후 공통 `AuthCookieFactory`로 토큰 쿠키를 만들고 응답한다. ACTIVE인 경우에만 `tutorialViewed`를 포함한다.

기기별 세션의 저장 구조·갱신·폐기와 기존 토큰의 호환은 [점주 세션 설계](owner-sessions.md)를 따른다.
저장·커밋과 Redis 게시 전 DB 확인 실패는 `AUTH-002`다. DB 저장 후 Redis 게시만 실패하면 DB 백업으로 로그인을 유지한다. 최초 계정 조회 실패는 기존 공통 서버 오류를 따른다.

점주 상태는 JWT나 공통 인증 객체에 넣지 않는다. 로그인은 DB로 허용 여부를 판단하며, 일반 상태 조회·캐시 무효화 조건은 [점주 상태 조회 설계](owner-status-cache.md)를 따른다. 비밀번호·토큰 원문·해시는 응답이나 로그에 노출하지 않는다.

## 최초 튜토리얼 완료

계약은 [owner-api-spec.md](../../api-spec/owner-api-spec.md)를 따른다. 근거는 「기능 명세서」 FR-029의 튜토리얼 확인 상태와 첨부 owner.md의 완료 API다. 최신 브랜치의 아키텍처·공통 응답 규칙을 우선한다.

1. 공통 인증을 통해 OWNER 주체를 확인한다.
2. `OwnerTutorialService`의 트랜잭션에서 기존 `OwnerRepository.findByIdForLogin`으로 점주 행을 잠근다.
3. 캐시 대신 DB의 현재 상태가 `ACTIVE`인지 확인하고 `tutorialViewed`를 `true`로 저장한다.
4. 커밋 후 성공 응답을 반환한다. 저장·커밋 실패 시 롤백하고 기존 공통 예외 처리로 오류를 반환한다.

상태 확인과 저장 사이에 정지·탈퇴 변경이 끼어들지 못하도록 같은 행 잠금을 유지한다. 먼저 확정된 상태 변경을 읽으며, 나중의 상태 변경을 영구적으로 막는 의미는 아니다. 반복·동시 완료는 모두 같은 완료 상태로 수렴한다.

로그인·재발급·로그아웃도 같은 점주 행 잠금을 사용하므로 해당 작업과 DB 변경을 직렬화한다. 튜토리얼 완료는 토큰 정보·발급 순번·계정 상태·Redis 상태 캐시를 수정하지 않는다. 기존 `tutorial_viewed` 컬럼을 사용하므로 마이그레이션은 추가하지 않는다.

`OwnerSecurityConfig`에서 회원가입·로그인·재발급의 공개 경로, 내 정보 조회·로그아웃·튜토리얼 완료의 OWNER 규칙을 함께 유지한다. 공용 auth 수정은 필요하지 않다.

가게 최종 등록·ACTIVE 전환·상태 변경 서비스·일반 업무 API의 상태 제한은 별도 구현이다. 현재 가게 등록 기능이 없어도 이 API는 DB에 저장된 현재 상태를 기준으로 동작한다.

## 내 정보 조회

계약은 [owner-api-spec.md](../../api-spec/owner-api-spec.md)의 내 정보 조회 절을 따른다. 관련 이슈는 #151이다.

공용 JWT 필터가 쿠키를 검증하고 컨트롤러는 인증된 CustomUserDetails의 ID만 서비스에 전달한다.
서비스는 읽기 전용 트랜잭션에서 점주를 한 번 조회한다. 상태 캐시나 토큰 클레임 대신 같은 DB 행의 이메일, 상태와 튜토리얼 확인 여부를 반환한다.
ONBOARDING 점주는 store.contract.StoreRegistrationQuery로 현재 재개 단계를 읽어 registrationStep으로 반환한다. 그 외 상태에서는 null을 반환한다. 진행 상태가 없는 경우의 기본 단계와 조회 계약은 [가게 등록 진행 상태 설계](../store/store-registration-progress.md#공개-조회-계약)를 따른다.
계정이 없으면 LOGIN_REQUIRED로 처리한다. 저장소 장애는 공통 예외 처리로 전파하며 정상 응답으로 대체하지 않는다.

현재 상태를 확인하는 API이므로 ONBOARDING을 차단하지 않고 별도의 상태별 업무 인가를 추가하지 않는다.
토큰 무효화는 공용 인증 필터의 검증을 따르며 인증 로직을 복제하지 않는다.
OwnerSecurityConfig의 회원가입·로그인·재발급·로그아웃·튜토리얼 완료 규칙과 조회 규칙을 함께 유지한다.

## 가게 기본 정보 저장의 점주 확인 (#415)

OwnerStoreInfoController와 HTTP 요청·응답 DTO는 owner에 두고 OwnerStoreInfoService가 호출 순서를 담당한다.
가게 기본 정보의 업무 검증과 저장은 store가 담당하며 API 경로와 공개 요청·응답 형식은 유지한다.
OwnerStoreInfoService는 쓰기 트랜잭션 안에서 requireOnboarding으로 점주 행을 잠그고 현재 상태를 확인한다.
requireOnboarding은 owner 서비스의 내부 메서드이며 다른 도메인에 공개하지 않는다.
기존 findByIdForLogin의 비관적 쓰기 잠금을 사용한다.
점주 부재는 AUTH-005, ONBOARDING 이외의 상태는 기존 OWNER-002로 거부하고 DB 장애는 전파한다.
확인을 마치면 store.contract.StoreInfoSaver에 StoreInfoSaveCommand를 전달한다.
StoreInfoSaveResult는 owner의 StoreInfoResponse로 변환하며 HTTP DTO를 도메인 간 계약으로 사용하지 않는다.
토큰·계정 상태·튜토리얼·상태 캐시는 변경하지 않는다.
저장 계약과 트랜잭션의 단일 기준은 [가게 API 설계](../store/store-api-design.md#접근-확인과-트랜잭션)를 따른다.

## 입점 등록 진행 상태 조회의 점주 확인 (#453)

OwnerStoreOnboardingController와 OwnerStoreOnboardingService가 기존 진행 상태 GET의 진입점을 담당한다.
읽기 전용 트랜잭션에서 OwnerRepository.findStatusById로 DB 현재 상태를 확인한다.
ONBOARDING·ACTIVE만 허용하며 계정 부재는 AUTH-005, 정지·탈퇴는 OWNER-002로 거부한다.
DB 장애는 그대로 전파하고 상태 캐시나 토큰 상태값으로 대체하지 않는다.
가게 판정은 store.contract.StoreOnboardingQuery에 위임하고 결과를 OwnerStoreOnboardingResponse로 변환한다.
owner → store 방향을 유지하며 store에서 owner 상태를 역으로 조회하지 않는다.
HTTP 계약은 [가게 API 명세](../../api-spec/store-api-spec.md#get-v1owneronboarding)가 소유한다.
