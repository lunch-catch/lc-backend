# 가게 API 설계서

백엔드 구현·운영을 위한 문서다. 현재 구현 범위는 점주 약관 동의다.
요청·응답·오류 계약은 `store-api-spec.md`가 소유하며, 공통 도메인 경계는 `런치캐치_도메인_구조와_의존성_설계.md`를 따른다.

## 점주 약관 동의

### 호출 구조와 소유권

```text
OwnerTermsAgreementController
  -> OwnerTermsSubmissionService: 트랜잭션 시작, 점주 행 잠금·현재 상태 확인
  -> store.contract.OwnerTermsAgreementRecorder
  -> store.service.OwnerTermsAgreementService: 정책 검증·약관 내역 저장
  -> OwnerTermsAgreementRepository
```

HTTP 진입점과 계정 상태 확인은 `owner`, 약관 정책·검증·저장은 `store`가 소유한다.
`owner -> store`의 기존 의존 방향을 유지하기 위한 구조다. `store`가 점주 entity·repository를 직접 참조하거나 `store -> owner` 역방향 의존을 추가하지 않는다.

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

### 처리 순서

1. 공통 인증 필터가 인증 주체와 OWNER 역할을 확인하고 요청 DTO를 검증한다.
2. `OwnerTermsSubmissionService`가 5초 제한의 쓰기 트랜잭션을 시작한다.
3. 기존 `findByIdForLogin`으로 점주 행을 잠그고 DB 현재 상태가 `ONBOARDING`인지 확인한다. 상태 캐시는 사용하지 않는다.
4. 점주 ID와 약관 명령 목록을 `OwnerTermsAgreementRecorder`에 전달한다.
5. 가게 서비스가 서버 정책, 지원 종류·버전, 요청 중복, 필수 동의 여부를 검증한다.
6. 기존 내역과 비교해 동일 종류·버전의 중복을 거부하고, 기존 내역과 새 내역을 합쳐 현재 필수 약관의 완료 여부를 확인한다.
7. 새 내역 전체를 저장·flush하고 저장된 내역으로 완료 여부를 다시 계산한다.
8. 트랜잭션 커밋이 성공한 뒤 응답한다. 실패하면 변경 전체를 롤백한다.

### 저장과 트랜잭션


- 점주 ID는 인증 주체에서만 가져온다. 점주 행을 기존 `findByIdForLogin`으로 잠근 뒤 상태를 확인하고 약관 저장까지 같은 쓰기 트랜잭션을 유지한다.
- `store`가 약관 정책·검증·내역을 소유한다. 기존 `owner -> store` 방향을 유지하기 위해 `owner`의 서비스가 상태 확인 후 `store.contract.OwnerTermsAgreementRecorder`를 호출한다. `store -> owner` 참조를 추가하지 않는다.
- `owner_terms_agreement`는 기존 store V1 스키마를 그대로 사용한다. `created_at`을 동의 응답 제출 시점으로 쓰며, 선택 미동의도 해당 시점의 선택으로 저장한다. 공통 JPA auditing의 Asia/Seoul Clock을 사용한다.
- 필수 완료 여부는 저장된 내역과 현재 서버 정책으로 계산하며 별도 컬럼에 저장하지 않는다. 과거 버전·철회 내역은 현재 필수 동의를 대신하지 않는다. 이미 저장된 필수 동의가 있으면 아직 제출하지 않은 선택 약관을 추가할 수 있다.
- 동일 종류·버전은 동의 여부와 관계없이 한 번만 기록하며 기존 내역을 덮어쓰지 않는다. 점주 행 잠금으로 동시 요청을 직렬화하고 DB의 `uk_owner_terms_version`을 최종 방어선으로 사용한다.
- 검증·저장·커밋 실패 시 전체 요청을 롤백한다. 점주 상태·튜토리얼·토큰·발급 순번과 쿠키는 변경하지 않는다.

가게 기본 정보 등록과 단계 진입 제한, 최종 등록·ACTIVE 전환, 선택 동의 변경·철회 API는 후속 기능이다.

### 공개 계약

`store.contract.OwnerTermsAgreementRecorder.recordAgreements(Long ownerId, List<TermsAgreementCommand> agreements)`는 완료 여부를 boolean으로 반환한다.
`TermsAgreementCommand`는 종류·버전·동의 여부만 전달하며, 필수 여부는 가게 서비스의 서버 정책으로 결정한다.
호출자는 같은 쓰기 트랜잭션에서 점주 행 잠금과 ONBOARDING 상태 확인을 완료해야 한다. 구현은 `Propagation.MANDATORY`로 호출자의 트랜잭션에 참여하고 별도 커밋을 만들지 않는다.

### 중복·장애 처리

로그인·재발급·로그아웃과 동일한 점주 행 잠금을 사용한다. 같은 점주의 동의 쓰기는 직렬화하고, 잠금은 약관 저장 트랜잭션 종료까지 유지한다.
사전 조회로 찾지 못한 중복은 기존 `uk_owner_terms_version` 제약이 차단한다. 이 UNIQUE 제약 위반만 중복 동의로 변환하고, 다른 DB 무결성 오류는 중복으로 오인하지 않는다.
저장 후 완료 여부가 충족되지 않거나 저장·커밋에 실패하면 성공 응답을 반환하지 않는다.

### 검증

- 단위 테스트: 정책 바인딩·방어적 복사, 필수 동의·누락·미동의, 선택 미동의, 지원하지 않는 약관, 중복, 점주 상태 제한, 저장 실패
- 실제 MySQL 8.4·Valkey 9 통합 테스트: 인증 쿠키와 HTTP 응답, 동시 요청, 동시 상태 변경, 실제 UNIQUE 제약 충돌, INSERT 후 실패 롤백, 점주·토큰 정보 유지
- 저장소 전체 검사: `./gradlew check` 및 서비스 메서드 커버리지 게이트

### 미확정 사항

실제 운영 약관 종류·버전·필수 여부는 팀의 정책 확정이 필요하다. 명세 예시를 운영 기본값으로 사용하지 않는다.

## 참고


- 이슈 #147
- 사전 작성 자료: 최신 외부 `store.md`의 점주 약관 동의, V51 요구사항명세서 기능 명세서 35행
- 구현 참고: develop의 점주 회원가입·로그인, PR #123의 재발급, PR #133의 로그아웃, PR #139의 튜토리얼 완료
- 공통 규약: `README.md`, `auth-api-spec.md`