-- member 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 3장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 3. 사용자 (member)    담당 김현우
-- ############################################################################

-- ----------------------------------------------------------------------------
-- member  |  회원(User)
-- ----------------------------------------------------------------------------
CREATE TABLE member (
    member_id                  BIGINT        NOT NULL AUTO_INCREMENT,
    provider_user_id           VARCHAR(100)  NOT NULL                               COMMENT '카카오 회원번호',
    nickname                   VARCHAR(50)   NOT NULL,
    profile_image_url          VARCHAR(512)                                         COMMENT '[13차 확정, 2026-10-01] 카카오 프로필 이미지 URL. 최초 가입 시에만 동기화, 사용자 직접 업로드는 범위 밖',
    status                     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_login_at              DATETIME(6),
    created_at                 DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6)   NOT NULL,
    withdrawn_at               DATETIME(6),
    refresh_token_hash         CHAR(64)                                             COMMENT 'SHA-256. admin/owner와 동일하게 로컬 컬럼으로 관리(공용 인증 테이블 없음)',
    refresh_token_expires_at   DATETIME(6)                                          COMMENT 'refresh_token_hash와 짝',
    notification_opt_in        BOOLEAN       NOT NULL DEFAULT 0                     COMMENT '알림 수신 동의',
    notification_opt_in_at     DATETIME(6),
    notification_withdrawn_at  DATETIME(6)                                          COMMENT '알림 수신 동의 철회 시각 (알림 도메인과 합의, 별도 테이블 없이 이 3컬럼으로 표현)',
    location_opt_in            BOOLEAN       NOT NULL DEFAULT 0                     COMMENT '위치정보 수집 동의',
    location_opt_in_at         DATETIME(6),
    location_withdrawn_at      DATETIME(6)                                          COMMENT '[13차 확정] 위치정보 수집 동의 철회 시각. notification_withdrawn_at과 대칭',
    suspended_at               DATETIME(6)                                          COMMENT '[13차 확정] 마지막 정지 시각. 정지 해제와 탈퇴 후에도 지우지 않는다. 값이 있으면 "정지 이력 있음"으로 보고 같은 provider_user_id의 재가입을 막는다(M-02 MEMBER-024)',
    suspended_until            DATETIME(6)                                          COMMENT '[13차 확정] 정지 종료 예정 시각. NULL이면 무기한. SUSPENDED일 때만 값 존재',
    suspension_reason          VARCHAR(500)                                         COMMENT '[13차 확정] 정지 사유. SUSPENDED일 때만 값 존재. 변경 이력 전체는 운영 도메인 감사 로그',

    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_provider_user_id (provider_user_id),
    KEY idx_member_refresh_token_hash (refresh_token_hash),
    KEY idx_member_status_created (status, created_at),

    CONSTRAINT chk_member_refresh_token CHECK ((refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)),
    CONSTRAINT chk_member_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT chk_member_suspended_until CHECK (suspended_until IS NULL OR suspended_until > suspended_at),
    CONSTRAINT chk_member_suspension CHECK ((status = 'SUSPENDED' AND suspended_at IS NOT NULL) OR (status <> 'SUSPENDED' AND suspended_until IS NULL AND suspension_reason IS NULL)),
    CONSTRAINT chk_member_withdrawn_at CHECK ((status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL) OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='회원(User)';

-- ----------------------------------------------------------------------------
-- kakao_unlink_failure  |  카카오 unlink 실패 재시도 큐. [13차 확정] lc-demo 기존 테이블을 ERD에 편입
-- ----------------------------------------------------------------------------
CREATE TABLE kakao_unlink_failure (
    kakao_unlink_failure_id  BIGINT        NOT NULL AUTO_INCREMENT,
    member_id                BIGINT        NOT NULL,
    provider_user_id         VARCHAR(100)  NOT NULL                               COMMENT '탈퇴 시점의 카카오 회원번호 사본(member.provider_user_id)',
    attempt_count            INT           NOT NULL DEFAULT 0                     COMMENT '재시도 횟수. 원 시도 실패는 포함하지 않음',
    resolved                 BOOLEAN       NOT NULL DEFAULT 0,
    created_at               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (kakao_unlink_failure_id),
    UNIQUE KEY uk_kakao_unlink_failure_member (member_id),

    CONSTRAINT fk_kakao_unlink_failure_member FOREIGN KEY (member_id) REFERENCES member (member_id),

    CONSTRAINT chk_kakao_unlink_failure_attempt CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='카카오 unlink 실패 재시도 큐. [13차 확정] lc-demo 기존 테이블을 ERD에 편입';

-- ----------------------------------------------------------------------------
-- member_profile  |  회원 프로필 (member 1:1). [2026-10-01] gender/age_group 코드값 확정 + CHECK 신규(캠페인 도메인과 코드 통일)
-- ----------------------------------------------------------------------------
CREATE TABLE member_profile (
    member_profile_id        BIGINT         NOT NULL AUTO_INCREMENT,
    member_id                BIGINT         NOT NULL,
    gender                   VARCHAR(10)                             COMMENT '[2026-10-01 확정] MALE/FEMALE/OTHER',
    age_group                VARCHAR(10)                             COMMENT '[2026-10-01 확정] AGE_20S/AGE_30S/AGE_40S/AGE_50_PLUS. 캠페인 도메인(노출 대상 판정, 00:00 (10)단계 인원 집계)과 코드 통일',
    location_nickname        VARCHAR(50)                             COMMENT '저장 위치 별칭(예: 집/회사)',
    road_address             VARCHAR(255)                            COMMENT 'store.road_address와 명명과 타입 통일(점주/가게 도메인 참고)',
    latitude                 DECIMAL(10,7)                           COMMENT 'store.latitude와 명명과 타입 통일',
    longitude                DECIMAL(10,7)                           COMMENT 'store.longitude와 명명과 타입 통일',
    onboarding_completed_at  DATETIME(6),
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,

    PRIMARY KEY (member_profile_id),
    UNIQUE KEY uk_member_profile_member (member_id),

    CONSTRAINT fk_member_profile_member FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE,

    CONSTRAINT chk_member_profile_age_group CHECK (age_group IS NULL OR age_group IN ('AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS')),
    CONSTRAINT chk_member_profile_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT chk_member_profile_latitude CHECK (latitude IS NULL OR (latitude BETWEEN -(90) AND 90)),
    CONSTRAINT chk_member_profile_longitude CHECK (longitude IS NULL OR (longitude BETWEEN -(180) AND 180)),
    CONSTRAINT chk_member_profile_onboarding CHECK ((onboarding_completed_at IS NOT NULL AND gender IS NOT NULL AND age_group IS NOT NULL) OR onboarding_completed_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='회원 프로필 (member 1:1). [2026-10-01] gender/age_group 코드값 확정 + CHECK 신규(캠페인 도메인과 코드 통일)';
