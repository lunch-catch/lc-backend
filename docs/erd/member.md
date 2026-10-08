# member ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 3개.

```mermaid
erDiagram
    kakao_unlink_failure {
        bigint kakao_unlink_failure_id PK
        bigint member_id UK
        varchar(100) provider_user_id "탈퇴 시점의 카카오 회원번호 사본(member.provider_user_id)"
        int attempt_count "재시도 횟수. 원 시도 실패는 포함하지 않음"
        tinyint(1) resolved
        datetime(6) created_at
        datetime(6) updated_at
    }
    member {
        bigint member_id PK
        varchar(100) provider_user_id UK "카카오 회원번호"
        varchar(50) nickname
        varchar(512) profile_image_url "[13차 확정, 2026-10-01] 카카오 프로필 이미지 URL. 최초 가입 시에만 동기화, 사용자 직접 업로드는 범위 밖"
        varchar(20) status
        datetime(6) last_login_at "nullable"
        datetime(6) created_at
        datetime(6) updated_at
        datetime(6) withdrawn_at "nullable"
        char(64) refresh_token_hash "SHA-256. admin/owner와 동일하게 로컬 컬럼으로 관리(공용 인증 테이블 없음)"
        datetime(6) refresh_token_expires_at "refresh_token_hash와 짝"
        tinyint(1) notification_opt_in "알림 수신 동의"
        datetime(6) notification_opt_in_at "nullable"
        datetime(6) notification_withdrawn_at "알림 수신 동의 철회 시각 (알림 도메인과 합의, 별도 테이블 없이 이 3컬럼으로 표현)"
        tinyint(1) location_opt_in "위치정보 수집 동의"
        datetime(6) location_opt_in_at "nullable"
        datetime(6) location_withdrawn_at "[13차 확정] 위치정보 수집 동의 철회 시각. notification_withdrawn_at과 대칭"
        datetime(6) suspended_at "[13차 확정] 마지막 정지 시각. 정지 해제와 탈퇴 후에도 지우지 않는다. 값이 있으면 '정지 이력 있음'으로 보고 같은 provider_user_id의 재가입을 막는다(M-02 MEMBER-024)"
        datetime(6) suspended_until "[13차 확정] 정지 종료 예정 시각. NULL이면 무기한. SUSPENDED일 때만 값 존재"
        varchar(500) suspension_reason "[13차 확정] 정지 사유. SUSPENDED일 때만 값 존재. 변경 이력 전체는 운영 도메인 감사 로그"
    }
    member_profile {
        bigint member_profile_id PK
        bigint member_id UK
        varchar(10) gender "[2026-10-01 확정] MALE/FEMALE/OTHER"
        varchar(10) age_group "[2026-10-01 확정] AGE_20S/AGE_30S/AGE_40S/AGE_50_PLUS. 캠페인 도메인(노출 대상 판정, 00:00 (10)단계 인원 집계)과 코드 통일"
        varchar(50) location_nickname "저장 위치 별칭(예: 집/회사)"
        varchar(255) road_address "store.road_address와 명명과 타입 통일(점주/가게 도메인 참고)"
        decimal(10_7) latitude "store.latitude와 명명과 타입 통일"
        decimal(10_7) longitude "store.longitude와 명명과 타입 통일"
        datetime(6) onboarding_completed_at "nullable"
        datetime(6) created_at
        datetime(6) updated_at
    }
    member ||--o{ kakao_unlink_failure : "member_id"
    member ||--o{ member_profile : "member_id"
```
