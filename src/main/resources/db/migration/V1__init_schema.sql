-- ============================================================================
-- 런치캐치 전체 스키마
-- 기준: 드라이브 erd 폴더 SQL 12개 + 통합 무결성 보강 (2026-10-01 16시), 요구사항 명세서 V45
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci
-- ============================================================================
--
-- 공통 규칙
--   1. 다른 도메인 테이블은 FK 없이 ID 값으로만 참조한다(소프트 참조, V45 규칙 1). 테이블마다 머리 주석에 적었다
--   2. 같은 도메인 안의 참조는 FK로 건다
--   3. 코드 값과 컬럼 간 규칙은 CHECK로 막는다. 단, 대량 로그의 DB ENUM 컬럼에는 CHECK를 걸지 않는다(값 추가가 COPY가 됨)
--   4. 시각은 모두 DATETIME(6), Asia/Seoul. business_date는 애플리케이션이 계산한다
--   5. 대리 키는 BIGINT AUTO_INCREMENT. 앱이 먼저 만드는 식별자(serve_id)만 UUIDv7 BINARY(16)
--   6. 모든 테이블에 created_at과 updated_at을 둔다. 이력과 로그도 예외가 없다.
--      엔티티는 global.entity.BaseTimeEntity 하나를 상속해 두 컬럼을 얻는다
--
-- 목차
--   1. 관리자 (admin)            정규동   2개: admin, audit_log
--   2. 점주 (owner)              정규동   2개: owner, owner_terms_agreement
--   3. 사용자 (member)           김현우   3개: member, kakao_unlink_failure, member_profile
--   4. 가게 (store)              정규동   6개: store, store_business_hour, store_image, store_menu, store_menu_image, store_campaign_summary
--   5. 캠페인 (campaign)         최재웅   9개: campaign, campaign_daily_plan, campaign_hourly_target, campaign_daily_report, campaign_daily_impression_reason_report, campaign_daily_report_history, campaign_daily_slot_report, campaign_hourly_report, campaign_status_log
--   6. 포스터 (poster)           최재웅   4개: template, poster, template_version, poster_moderation_result
--   7. 정산 (billing)            김현우   6개: point_policy, payment, payment_reconciliation_outbox, refund_request, point_ledger, settlement_mismatch
--   8. 광고 서빙 (adserving)     박준서   8개: ad_candidate, feed_filter, feed_filter_category, feed_visit_daily, impression_log, serve_log, swipe_log, wishlist
--   9. 쿠폰 (coupon)             최민혁   5개: coupon_daily_limit, coupon_event, coupon_inventory, member_coupon, coupon_usage_history
--   10. 알림 (notification)      정규동   5개: notification_device, notification, notification_delivery, notification_sent, notification_outbox
--   11. 분석 (analytics)         김현우   8개: daily_campaign_not_billed_analytics, daily_invalid_analytics, daily_member_analytics, daily_owner_spend, daily_segment_analytics, daily_settlement_analytics, daily_store_analytics, event_log
--   12. 운영 (ops)               최재웅   3개: platform_setting, platform_setting_history, batch_execution_log
-- ============================================================================



-- ############################################################################
-- 1. 관리자 (admin)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- admin
-- ----------------------------------------------------------------------------
CREATE TABLE admin (
    admin_id                  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '관리자 PK',
    login_id                  VARCHAR(50)   NOT NULL                 COMMENT '관리자 로그인 ID',
    password_hash             VARCHAR(255)  NOT NULL                 COMMENT 'BCrypt 비밀번호 해시',
    name                      VARCHAR(50)   NOT NULL                 COMMENT '관리자 이름',
    role                      VARCHAR(30)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL  COMMENT '관리자 권한(ADMIN, SUPER_ADMIN)',
    status                    VARCHAR(30)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'ACTIVE'  COMMENT '관리자 상태(ACTIVE, DELETED)',
    refresh_token_hash        CHAR(64)                               COMMENT 'Refresh Token SHA-256 해시',
    refresh_token_expires_at  DATETIME(6)                            COMMENT 'Refresh Token 만료 시각',
    deleted_at                DATETIME(6)                            COMMENT '관리자 비활성화 시각',
    created_at                DATETIME(6)   NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,

    PRIMARY KEY (admin_id),
    UNIQUE KEY uk_admin_login_id (login_id),
    KEY idx_admin_refresh_token_hash (refresh_token_hash),

    CONSTRAINT chk_admin_deleted CHECK ((status = 'DELETED' AND deleted_at IS NOT NULL AND refresh_token_hash IS NULL) OR (status <> 'DELETED' AND deleted_at IS NULL)),
    CONSTRAINT chk_admin_refresh_token CHECK ((refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)),
    CONSTRAINT chk_admin_role CHECK (role IN ('SUPER_ADMIN', 'ADMIN')),
    CONSTRAINT chk_admin_status CHECK (status IN ('ACTIVE', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- audit_log
-- ----------------------------------------------------------------------------
CREATE TABLE audit_log (
    audit_log_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '감사 로그 PK',
    admin_id      BIGINT        NOT NULL                 COMMENT '행위 관리자 ID',
    action        VARCHAR(50)   NOT NULL                 COMMENT '관리자 수행 행위',
    target        VARCHAR(100)                           COMMENT '행위 대상',
    detail        TEXT                                   COMMENT '상세 내용',
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,

    PRIMARY KEY (audit_log_id),
    KEY fk_audit_admin (admin_id),

    CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES admin (admin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ############################################################################
-- 2. 점주 (owner)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- owner
-- ----------------------------------------------------------------------------
CREATE TABLE owner (
    owner_id                  BIGINT        NOT NULL AUTO_INCREMENT        COMMENT '점주 PK',
    email                     VARCHAR(255)  NOT NULL                       COMMENT '점주 이메일. UNIQUE 제약 조건 적용',
    password_hash             VARCHAR(255)  NOT NULL                       COMMENT 'BCrypt 해싱한 점주 비밀번호',
    role                      VARCHAR(20)   NOT NULL DEFAULT 'OWNER'       COMMENT '점주 권한. 서버에서 자동으로 OWNER로 설정',
    status                    VARCHAR(20)   NOT NULL DEFAULT 'ONBOARDING'  COMMENT '점주 상태(ONBOARDING, ACTIVE, SUSPENDED, WITHDRAWN)',
    tutorial_viewed           BOOLEAN       NOT NULL DEFAULT 0             COMMENT '최초 로그인 튜토리얼 확인 여부',
    suspended_at              DATETIME(6)                                  COMMENT '정지 시각',
    suspension_reason         VARCHAR(500)                                 COMMENT '정지 사유',
    withdrawn_at              DATETIME(6)                                  COMMENT '탈퇴 시각',
    refresh_token_hash        CHAR(64)                                     COMMENT 'Refresh Token SHA-256 해시. 관계형 DB 백업용',
    refresh_token_expires_at  DATETIME(6)                                  COMMENT 'Refresh Token 만료 시각',
    last_login_at             DATETIME(6)                                  COMMENT '최근 로그인 시각',
    created_at                DATETIME(6)   NOT NULL                       COMMENT '생성일시',
    updated_at                DATETIME(6)   NOT NULL                       COMMENT '수정일시',

    PRIMARY KEY (owner_id),
    UNIQUE KEY UK_OWNER_EMAIL (email),

    CONSTRAINT CHK_OWNER_REFRESH_TOKEN CHECK ((refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)),
    CONSTRAINT CHK_OWNER_ROLE CHECK (role = 'OWNER'),
    CONSTRAINT CHK_OWNER_STATUS CHECK (status IN ('ONBOARDING', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT CHK_OWNER_SUSPENDED CHECK (status <> 'SUSPENDED' OR suspended_at IS NOT NULL),
    CONSTRAINT CHK_OWNER_WITHDRAWN CHECK (status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- owner_terms_agreement
-- ----------------------------------------------------------------------------
CREATE TABLE owner_terms_agreement (
    agreement_id   BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '점주 약관 동의 PK',
    owner_id       BIGINT       NOT NULL                 COMMENT '점주 PK',
    terms_type     VARCHAR(50)  NOT NULL                 COMMENT '약관 종류',
    terms_version  VARCHAR(30)  NOT NULL                 COMMENT '약관 버전',
    required       BOOLEAN      NOT NULL                 COMMENT '필수 약관 여부',
    agreed         BOOLEAN      NOT NULL                 COMMENT '동의 여부',
    agreed_at      DATETIME(6)  NOT NULL                 COMMENT '동의 시각',
    withdrawn_at   DATETIME(6)                           COMMENT '선택 동의 철회 시각',
    created_at     DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at     DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (agreement_id),
    UNIQUE KEY UK_OWNER_TERMS_VERSION (owner_id, terms_type, terms_version),

    CONSTRAINT FK_OWNER_TERMS_AGREEMENT_OWNER FOREIGN KEY (owner_id) REFERENCES owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
    profile_image_url          VARCHAR(512)                                         COMMENT '[13차 확정, 2026-10-01] 카카오 프로필 이미지 URL. 최초 가입 시에만 동기화(명세 62행), 사용자 직접 업로드는 범위 밖',
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
    suspended_at               DATETIME(6)                                          COMMENT '[13차 확정] 마지막 정지 시각. 정지 해제·탈퇴 후에도 지우지 않는다 — 값이 있으면 "정지 이력 있음"으로 보고 같은 provider_user_id의 재가입을 막는다(M-02 MEMBER-024)',
    suspended_until            DATETIME(6)                                          COMMENT '[13차 확정] 정지 종료 예정 시각. NULL이면 무기한. SUSPENDED일 때만 값 존재',
    suspension_reason          VARCHAR(255)                                         COMMENT '[13차 확정] 정지 사유. SUSPENDED일 때만 값 존재. 변경 이력 전체는 운영 도메인 감사 로그',

    PRIMARY KEY (member_id),
    UNIQUE KEY uq_member_provider_user_id (provider_user_id),
    KEY idx_member_refresh_token_hash (refresh_token_hash),
    KEY idx_member_status_created (status, created_at),

    CONSTRAINT ck_member_refresh_token CHECK ((refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)),
    CONSTRAINT ck_member_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT ck_member_suspended_until CHECK (suspended_until IS NULL OR suspended_until > suspended_at),
    CONSTRAINT ck_member_suspension CHECK ((status = 'SUSPENDED' AND suspended_at IS NOT NULL) OR (status <> 'SUSPENDED' AND suspended_until IS NULL AND suspension_reason IS NULL)),
    CONSTRAINT ck_member_withdrawn_at CHECK ((status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL) OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='회원(User)';

-- ----------------------------------------------------------------------------
-- kakao_unlink_failure  |  카카오 unlink 실패 재시도 큐. [13차 확정] lc-demo 기존 테이블을 ERD에 편입
-- ----------------------------------------------------------------------------
CREATE TABLE kakao_unlink_failure (
    kakao_unlink_failure_id  BIGINT        NOT NULL AUTO_INCREMENT,
    member_id                BIGINT        NOT NULL,
    kakao_user_id            VARCHAR(100)  NOT NULL                               COMMENT '탈퇴 시점의 카카오 회원번호 사본(provider_user_id)',
    attempt_count            INT           NOT NULL DEFAULT 0                     COMMENT '재시도 횟수. 원 시도 실패는 포함하지 않음',
    resolved                 BOOLEAN       NOT NULL DEFAULT 0,
    created_at               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (kakao_unlink_failure_id),
    UNIQUE KEY uq_kakao_unlink_failure_member (member_id),

    CONSTRAINT fk_kakao_unlink_failure_member FOREIGN KEY (member_id) REFERENCES member (member_id),

    CONSTRAINT ck_kakao_unlink_failure_attempt CHECK (attempt_count >= 0)
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
    road_address             VARCHAR(255)                            COMMENT 'store.road_address와 명명·타입 통일(점주/가게 도메인 참고)',
    latitude                 DECIMAL(10,7)                           COMMENT 'store.latitude와 명명·타입 통일',
    longitude                DECIMAL(10,7)                           COMMENT 'store.longitude와 명명·타입 통일',
    onboarding_completed_at  DATETIME(6),
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,

    PRIMARY KEY (member_profile_id),
    UNIQUE KEY uq_member_profile_member (member_id),

    CONSTRAINT fk_member_profile_member FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE,

    CONSTRAINT ck_member_profile_age_group CHECK (age_group IS NULL OR age_group IN ('AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS')),
    CONSTRAINT ck_member_profile_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT ck_member_profile_latitude CHECK (latitude IS NULL OR (latitude BETWEEN -(90) AND 90)),
    CONSTRAINT ck_member_profile_longitude CHECK (longitude IS NULL OR (longitude BETWEEN -(180) AND 180)),
    CONSTRAINT ck_member_profile_onboarding CHECK ((onboarding_completed_at IS NOT NULL AND gender IS NOT NULL AND age_group IS NOT NULL) OR onboarding_completed_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='회원 프로필 (member 1:1). [2026-10-01] gender/age_group 코드값 확정 + CHECK 신규(캠페인 도메인과 코드 통일)';

-- ############################################################################
-- 4. 가게 (store)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- store
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE store (
    store_id                      BIGINT         NOT NULL AUTO_INCREMENT  COMMENT '가게 PK',
    owner_id                      BIGINT         NOT NULL                 COMMENT '점주 PK. 점주 계정 1개당 가게 1개 등록',
    name                          VARCHAR(100)   NOT NULL                 COMMENT '상호명',
    business_category             VARCHAR(30)    NOT NULL                 COMMENT '업종 코드(KOREAN, CHINESE, JAPANESE, WESTERN, BUNSIK, ASIAN, FAST_FOOD, CAFE_DESSERT, OTHER)',
    representative_name           VARCHAR(100)   NOT NULL                 COMMENT '대표자 성명',
    phone                         VARCHAR(30)                             COMMENT '카카오맵에서 가져온 전화번호',
    road_address                  VARCHAR(255)                            COMMENT '카카오맵에서 가져온 도로명 주소',
    latitude                      DECIMAL(10,7)                           COMMENT '카카오맵에서 가져온 위도',
    longitude                     DECIMAL(10,7)                           COMMENT '카카오맵에서 가져온 경도',
    kakao_place_id                VARCHAR(50)                             COMMENT '카카오맵에서 가져온 카카오 장소 ID',
    business_registration_number  VARCHAR(10)                             COMMENT '사업자등록번호(기호 제외 숫자 10자)',
    business_verification_status  VARCHAR(20)                             COMMENT '사업자등록번호 Mock 검증 상태(VERIFIED, FAILED)',
    business_verified_at          DATETIME(6)                             COMMENT '사업자등록번호 Mock 검증 성공 시각',
    finalized                     BOOLEAN        NOT NULL DEFAULT 0       COMMENT '가게 최종 등록 여부',
    finalized_at                  DATETIME(6)                             COMMENT '가게 최종 등록 완료 시각(NULL 허용)',
    created_at                    DATETIME(6)    NOT NULL                 COMMENT '생성일시',
    updated_at                    DATETIME(6)    NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (store_id),
    UNIQUE KEY UK_STORE_OWNER (owner_id),

    CONSTRAINT CHK_STORE_BUSINESS_CATEGORY CHECK (business_category IN ('KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN', 'BUNSIK', 'ASIAN', 'FAST_FOOD', 'CAFE_DESSERT', 'OTHER')),
    CONSTRAINT CHK_STORE_BUSINESS_REGISTRATION_NUMBER CHECK (business_registration_number IS NULL OR REGEXP_LIKE(business_registration_number,'^[0-9]{10}$')),
    CONSTRAINT CHK_STORE_BUSINESS_VERIFICATION_STATUS CHECK (business_verification_status IS NULL OR business_verification_status IN ('VERIFIED', 'FAILED')),
    CONSTRAINT CHK_STORE_BUSINESS_VERIFIED CHECK (business_verification_status <> 'VERIFIED' OR business_verified_at IS NOT NULL),
    CONSTRAINT CHK_STORE_FINALIZED CHECK ((finalized = false AND finalized_at IS NULL) OR (finalized = true AND finalized_at IS NOT NULL AND business_verification_status = 'VERIFIED' AND latitude IS NOT NULL AND longitude IS NOT NULL)),
    CONSTRAINT CHK_STORE_LATITUDE CHECK (latitude IS NULL OR (latitude BETWEEN -(90) AND 90)),
    CONSTRAINT CHK_STORE_LONGITUDE CHECK (longitude IS NULL OR (longitude BETWEEN -(180) AND 180))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_business_hour
-- ----------------------------------------------------------------------------
CREATE TABLE store_business_hour (
    business_hour_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '영업시간 PK',
    store_id          BIGINT       NOT NULL                 COMMENT '가게 PK',
    day_of_week       VARCHAR(10)  NOT NULL                 COMMENT '영업 요일(MONDAY ~ SUNDAY)',
    open_time         TIME                                  COMMENT '오픈 시간',
    close_time        TIME                                  COMMENT '마감 시간',
    is_closed         BOOLEAN      NOT NULL DEFAULT 0       COMMENT '휴무 여부',
    break_start_time  TIME                                  COMMENT '브레이크 시작',
    break_end_time    TIME                                  COMMENT '브레이크 타임 종료',
    last_order_time   TIME                                  COMMENT '라스트오더 시간',
    created_at        DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at        DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (business_hour_id),
    UNIQUE KEY UK_STORE_BUSINESS_HOUR_DAY (store_id, day_of_week),

    CONSTRAINT FK_STORE_BUSINESS_HOUR_STORE FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT CHK_STORE_BUSINESS_HOUR_BREAK CHECK ((break_start_time IS NULL AND break_end_time IS NULL) OR (break_start_time IS NOT NULL AND break_end_time IS NOT NULL)),
    CONSTRAINT CHK_STORE_BUSINESS_HOUR_CLOSED CHECK ((is_closed = true AND open_time IS NULL AND close_time IS NULL) OR (is_closed = false AND open_time IS NOT NULL AND close_time IS NOT NULL)),
    CONSTRAINT CHK_STORE_BUSINESS_HOUR_DAY CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_image
-- ----------------------------------------------------------------------------
CREATE TABLE store_image (
    store_image_id     BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '가게 이미지 PK',
    store_id           BIGINT        NOT NULL                 COMMENT '가게 PK',
    upload_id          CHAR(36)                               COMMENT '객체 스토리지 업로드 요청 식별자',
    image_type         VARCHAR(20)   NOT NULL                 COMMENT '가게 이미지 타입(LOGO, INTERIOR)',
    object_key         VARCHAR(255)  NOT NULL                 COMMENT '객체 스토리지 Key',
    original_filename  VARCHAR(255)                           COMMENT '원본 파일명',
    content_type       VARCHAR(100)                           COMMENT '파일 MIME 타입(image/jpeg, image/png)',
    file_size          BIGINT                                 COMMENT '파일 크기(byte)',
    width              INT                                    COMMENT '이미지 가로 픽셀',
    height             INT                                    COMMENT '이미지 세로 픽셀',
    sort_order         TINYINT                                COMMENT '인테리어 이미지 표시 순서(1~3)',
    logo_store_id      BIGINT        GENERATED ALWAYS AS ((case when (`image_type` = 'LOGO') then `store_id` else NULL end)) STORED  COMMENT '가게당 LOGO 1개 제한용 생성 컬럼',
    upload_status      VARCHAR(20)   NOT NULL                 COMMENT '이미지 업로드 상태(PENDING, UPLOADED, VERIFIED)',
    verified_at        DATETIME(6)                            COMMENT '이미지 객체 존재 확인 완료 시각',
    created_at         DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at         DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (store_image_id),
    UNIQUE KEY UK_STORE_IMAGE_OBJECT_KEY (object_key),
    UNIQUE KEY UK_STORE_IMAGE_UPLOAD_ID (upload_id),
    UNIQUE KEY UK_STORE_IMAGE_SINGLE_LOGO (logo_store_id),
    UNIQUE KEY UK_STORE_IMAGE_INTERIOR_ORDER (store_id, image_type, sort_order),

    CONSTRAINT FK_STORE_IMAGE_STORE FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT CHK_STORE_IMAGE_CONTENT_TYPE CHECK (content_type IS NULL OR content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT CHK_STORE_IMAGE_FILE_SIZE CHECK (file_size IS NULL OR (file_size > 0 AND file_size <= 10485760)),
    CONSTRAINT CHK_STORE_IMAGE_SORT_ORDER CHECK ((image_type = 'LOGO' AND sort_order IS NULL) OR (image_type = 'INTERIOR' AND (sort_order BETWEEN 1 AND 3))),
    CONSTRAINT CHK_STORE_IMAGE_TYPE CHECK (image_type IN ('LOGO', 'INTERIOR')),
    CONSTRAINT CHK_STORE_IMAGE_UPLOAD_STATUS CHECK (upload_status IN ('PENDING', 'UPLOADED', 'VERIFIED')),
    CONSTRAINT CHK_STORE_IMAGE_VERIFIED CHECK (upload_status <> 'VERIFIED' OR verified_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_menu
-- ----------------------------------------------------------------------------
CREATE TABLE store_menu (
    menu_id      BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '대표 메뉴 PK',
    store_id     BIGINT        NOT NULL                 COMMENT '가게 PK',
    name         VARCHAR(100)  NOT NULL                 COMMENT '메뉴명',
    price        BIGINT        NOT NULL                 COMMENT '메뉴 가격(원 단위)',
    description  VARCHAR(500)                           COMMENT '메뉴 상세설명(선택. NULL 가능)',
    sort_order   TINYINT       NOT NULL                 COMMENT '대표 메뉴 순서(1~3)',
    created_at   DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at   DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (menu_id),
    UNIQUE KEY UK_STORE_MENU_ORDER (store_id, sort_order),

    CONSTRAINT FK_STORE_MENU_STORE FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT CHK_STORE_MENU_PRICE CHECK (price >= 0),
    CONSTRAINT CHK_STORE_MENU_SORT_ORDER CHECK (sort_order BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_menu_image
-- ----------------------------------------------------------------------------
CREATE TABLE store_menu_image (
    menu_image_id      BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '대표 메뉴 이미지 PK',
    menu_id            BIGINT        NOT NULL                 COMMENT '가게 대표 메뉴 PK',
    upload_id          CHAR(36)                               COMMENT '객체 스토리지 업로드 요청 식별자',
    object_key         VARCHAR(255)  NOT NULL                 COMMENT '객체 스토리지 Key',
    original_filename  VARCHAR(255)                           COMMENT '원본 파일명',
    content_type       VARCHAR(100)                           COMMENT '파일 MIME 타입(image/jpeg, image/png)',
    file_size          BIGINT                                 COMMENT '파일 크기(byte)',
    upload_status      VARCHAR(20)   NOT NULL                 COMMENT '이미지 업로드 상태(PENDING, UPLOADED, VERIFIED)',
    verified_at        DATETIME(6)                            COMMENT '이미지 객체 존재 확인 완료 시각',
    created_at         DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at         DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (menu_image_id),
    UNIQUE KEY UK_STORE_MENU_IMAGE_MENU (menu_id),
    UNIQUE KEY UK_STORE_MENU_IMAGE_OBJECT_KEY (object_key),
    UNIQUE KEY UK_STORE_MENU_IMAGE_UPLOAD_ID (upload_id),

    CONSTRAINT FK_STORE_MENU_IMAGE_MENU FOREIGN KEY (menu_id) REFERENCES store_menu (menu_id),

    CONSTRAINT CHK_STORE_MENU_IMAGE_CONTENT_TYPE CHECK (content_type IS NULL OR content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT CHK_STORE_MENU_IMAGE_FILE_SIZE CHECK (file_size IS NULL OR (file_size > 0 AND file_size <= 10485760)),
    CONSTRAINT CHK_STORE_MENU_IMAGE_UPLOAD_STATUS CHECK (upload_status IN ('PENDING', 'UPLOADED', 'VERIFIED')),
    CONSTRAINT CHK_STORE_MENU_IMAGE_VERIFIED CHECK (upload_status <> 'VERIFIED' OR verified_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_campaign_summary
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE store_campaign_summary (
    store_campaign_summary_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '가게 캠페인 요약 PK',
    store_id                   BIGINT       NOT NULL                 COMMENT '가게 PK',
    campaign_id                BIGINT       NOT NULL                 COMMENT '캠페인 ID',
    business_date              DATE         NOT NULL                 COMMENT '캠페인 요약이 적용되는 날짜',
    campaign_status            VARCHAR(20)  NOT NULL                 COMMENT '캠페인 상태(ACTIVE, PAUSED, ENDED 등)',
    discount_target_type       VARCHAR(20)  NOT NULL                 COMMENT '할인 대상 유형(ALL: 전체, MENU: 특정 메뉴)',
    discount_type              VARCHAR(20)  NOT NULL                 COMMENT '할인 유형(PERCENT: 퍼센트, AMOUNT: 금액)',
    discount_value             INT          NOT NULL                 COMMENT '할인 값',
    usable_start_time          TIME         NOT NULL                 COMMENT '캠페인별 사용 가능 시작 시간',
    usable_end_time            TIME         NOT NULL                 COMMENT '캠페인별 사용 가능 종료 시간',
    created_at                 DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                 DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (store_campaign_summary_id),
    UNIQUE KEY UK_STORE_CAMPAIGN_SUMMARY_DATE_CAMPAIGN (business_date, campaign_id),
    KEY FK_STORE_CAMPAIGN_SUMMARY_STORE (store_id),

    CONSTRAINT FK_STORE_CAMPAIGN_SUMMARY_STORE FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT CHK_STORE_CAMPAIGN_SUMMARY_DISCOUNT_TARGET_TYPE CHECK (discount_target_type IN ('ALL', 'MENU')),
    CONSTRAINT CHK_STORE_CAMPAIGN_SUMMARY_DISCOUNT_TYPE CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT CHK_STORE_CAMPAIGN_SUMMARY_STATUS CHECK (campaign_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT CHK_STORE_CAMPAIGN_SUMMARY_USABLE_TIME CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ############################################################################
-- 5. 캠페인 (campaign)    담당 최재웅
-- ############################################################################

-- ----------------------------------------------------------------------------
-- campaign
--   소프트 참조: owner_id -> owner(점주), store_id -> store(가게), target_menu_id -> store_menu(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE campaign (
    campaign_id           BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id              BIGINT        NOT NULL,
    store_id              BIGINT        NOT NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'DRAFT'  COMMENT 'DRAFT/SCHEDULED/ACTIVE/PAUSED/ENDED',
    status_changed_at     DATETIME(6)   NOT NULL                  COMMENT '현재 status로 바뀐 시각. ad_candidate.state_changed_at에 전달되는 값',
    paused_reason         VARCHAR(20)                             COMMENT 'ADMIN/OWNER/NO_POINTS',
    discount_target_type  VARCHAR(20)   NOT NULL                  COMMENT 'ALL/MENU',
    discount_type         VARCHAR(20)   NOT NULL                  COMMENT 'PERCENT/AMOUNT',
    discount_value        INT           NOT NULL,
    target_menu_id        BIGINT                                  COMMENT 'discount_target_type=''MENU''일 때만 값 존재',
    issue_quantity        INT           NOT NULL,
    usable_start_time     TIME          NOT NULL,
    usable_end_time       TIME          NOT NULL,
    min_order_amount      INT                                     COMMENT '선택 입력',
    notice                VARCHAR(500)                            COMMENT '선택입력',
    target_radius         INT           NOT NULL DEFAULT 1000     COMMENT '단위: 미터. 500/1000/2000/3000',
    target_gender         VARCHAR(10)   NOT NULL DEFAULT 'ALL'    COMMENT 'ALL/MALE/FEMALE',
    target_age_groups     VARCHAR(50)   NOT NULL                  COMMENT 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS 중 다중 선택, 콤마 구분',
    daily_budget          INT           NOT NULL                  COMMENT '포인트 단위',
    start_date            DATE          NOT NULL,
    end_date              DATE          NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    active_store_marker   BIGINT        GENERATED ALWAYS AS ((case when (`status` = 'ACTIVE') then `store_id` else NULL end)) STORED  COMMENT '47행 가게당 활성 캠페인 1건 UNIQUE 제약용. ACTIVE일 때만 store_id, 그 외 NULL',

    PRIMARY KEY (campaign_id),
    UNIQUE KEY UQ_CAMPAIGN_ACTIVE_STORE (active_store_marker),

    CONSTRAINT CHK_CAMPAIGN_DISCOUNT_TARGET CHECK ((discount_target_type = 'ALL' AND target_menu_id IS NULL) OR (discount_target_type = 'MENU' AND target_menu_id IS NOT NULL)),
    CONSTRAINT CHK_CAMPAIGN_DISCOUNT_TYPE CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT CHK_CAMPAIGN_DISCOUNT_VALUE CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    CONSTRAINT CHK_CAMPAIGN_PAUSED_REASON CHECK ((status = 'PAUSED' AND paused_reason IN ('ADMIN', 'OWNER', 'NO_POINTS')) OR (status <> 'PAUSED' AND paused_reason IS NULL)),
    CONSTRAINT CHK_CAMPAIGN_PERIOD CHECK (end_date >= start_date),
    CONSTRAINT CHK_CAMPAIGN_QUANTITY_BUDGET CHECK (issue_quantity > 0 AND daily_budget > 0),
    CONSTRAINT CHK_CAMPAIGN_STATUS CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT CHK_CAMPAIGN_TARGET_AGE_GROUPS CHECK (REGEXP_LIKE(target_age_groups,'^(AGE_20S|AGE_30S|AGE_40S|AGE_50_PLUS)(,(AGE_20S|AGE_30S|AGE_40S|AGE_50_PLUS))*$')),
    CONSTRAINT CHK_CAMPAIGN_TARGET_GENDER CHECK (target_gender IN ('ALL', 'MALE', 'FEMALE')),
    CONSTRAINT CHK_CAMPAIGN_TARGET_RADIUS CHECK (target_radius IN (500, 1000, 2000, 3000)),
    CONSTRAINT CHK_CAMPAIGN_USABLE_TIME CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_plan  |  00:00 배치가 만드는 일자별 읽기전용 스냅샷. 캠페인의 당일 daily_budget을 그대로 찍어두므로, "오늘 값 vs 내일 값" 비교가 필요하면 이 테이블의 오늘 행 vs 캠페인 엔티티의 현재 daily_budget 값을 비교하면 됨(별도 컬럼 불필요)
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_plan (
    campaign_daily_plan_id  BIGINT  NOT NULL AUTO_INCREMENT,
    campaign_id             BIGINT  NOT NULL                 COMMENT '소프트 참조(캠페인 도메인)',
    business_date           DATE    NOT NULL,
    daily_budget            BIGINT  NOT NULL,
    unit_price              BIGINT  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    PRIMARY KEY (campaign_daily_plan_id),
    UNIQUE KEY uq_campaign_daily_plan_campaign_date (campaign_id, business_date),

    CONSTRAINT FK_CAMPAIGN_DAILY_PLAN_CAMPAIGN FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),

    CONSTRAINT ck_campaign_daily_plan_daily_budget CHECK (daily_budget > 0),
    CONSTRAINT ck_campaign_daily_plan_unit_price CHECK (unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='00:00 배치가 만드는 일자별 읽기전용 스냅샷. 캠페인의 당일 daily_budget을 그대로 찍어두므로, "오늘 값 vs 내일 값" 비교가 필요하면 이 테이블의 오늘 행 vs 캠페인 엔티티의 현재 daily_budget 값을 비교하면 됨(별도 컬럼 불필요)';

-- ----------------------------------------------------------------------------
-- campaign_hourly_target  |  시간대별 목표 소진 포인트(페이싱)
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_hourly_target (
    campaign_hourly_target_id  BIGINT   NOT NULL AUTO_INCREMENT,
    campaign_daily_plan_id     BIGINT   NOT NULL                 COMMENT '같은 파일 내 테이블(campaign_daily_plan) 참조 — 실제 FK',
    hour                       TINYINT  NOT NULL,
    target_points              BIGINT   NOT NULL,
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,

    PRIMARY KEY (campaign_hourly_target_id),
    UNIQUE KEY uq_campaign_hourly_target_plan_hour (campaign_daily_plan_id, hour),

    CONSTRAINT fk_campaign_hourly_target_daily_plan FOREIGN KEY (campaign_daily_plan_id) REFERENCES campaign_daily_plan (campaign_daily_plan_id),

    CONSTRAINT ck_campaign_hourly_target_hour CHECK (hour BETWEEN 0 AND 23),
    CONSTRAINT ck_campaign_hourly_target_points CHECK (target_points >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='시간대별 목표 소진 포인트(페이싱)';

-- ----------------------------------------------------------------------------
-- campaign_daily_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_report (
    campaign_daily_report_id   BIGINT        NOT NULL AUTO_INCREMENT         COMMENT '캠페인 일별 집계 PK',
    campaign_id                BIGINT        NOT NULL                        COMMENT '캠페인 ID',
    report_date                DATE          NOT NULL                        COMMENT '집계 날짜',
    daily_budget_points        BIGINT        NOT NULL DEFAULT 0              COMMENT '해당 날짜의 하루 예산',
    charged_points             BIGINT        NOT NULL DEFAULT 0              COMMENT '유효 노출로 실제 차감된 포인트',
    refunded_points            BIGINT        NOT NULL DEFAULT 0              COMMENT '무효 처리로 환급된 포인트',
    spent_points               BIGINT        NOT NULL DEFAULT 0              COMMENT '실제 소진 포인트',
    budget_spend_rate          DECIMAL(9,6)  NOT NULL DEFAULT '0.000000'     COMMENT '예산 소진율',
    impression_unit_cost       BIGINT        NOT NULL DEFAULT 0              COMMENT '적용된 노출 단가',
    valid_impression_count     BIGINT        NOT NULL DEFAULT 0              COMMENT '유효 노출 수',
    billable_impression_count  BIGINT        NOT NULL DEFAULT 0              COMMENT '과금된 유효 노출 수',
    unbilled_impression_count  BIGINT        NOT NULL DEFAULT 0              COMMENT '미과금 유효 노출 수',
    invalid_impression_count   BIGINT        NOT NULL DEFAULT 0              COMMENT '무효 노출 수',
    wish_count                 BIGINT        NOT NULL DEFAULT 0              COMMENT '찜 수',
    issue_count                BIGINT        NOT NULL DEFAULT 0              COMMENT '쿠폰 발급 수',
    redeem_count               BIGINT        NOT NULL DEFAULT 0              COMMENT '쿠폰 사용 수',
    report_status              VARCHAR(20)   NOT NULL DEFAULT 'AGGREGATING'  COMMENT '리포트 상태(AGGREGATING, FINALIZED)',
    finalized_at               DATETIME(6)                                   COMMENT '일별 리포트 확정 시각',
    created_at                 DATETIME(6)   NOT NULL                        COMMENT '생성일시',
    updated_at                 DATETIME(6)   NOT NULL                        COMMENT '수정일시',

    PRIMARY KEY (campaign_daily_report_id),
    UNIQUE KEY UQ_CAMPAIGN_DAILY_REPORT_CAMPAIGN_ID_REPORT_DATE (campaign_id, report_date),

    CONSTRAINT FK_CAMPAIGN_DAILY_REPORT_CAMPAIGN FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),

    CONSTRAINT CHK_CAMPAIGN_DAILY_REPORT_IMPRESSION CHECK (valid_impression_count = (billable_impression_count + unbilled_impression_count)),
    CONSTRAINT CHK_CAMPAIGN_DAILY_REPORT_STATUS CHECK ((report_status = 'AGGREGATING' AND finalized_at IS NULL) OR (report_status = 'FINALIZED' AND finalized_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_impression_reason_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_impression_reason_report (
    campaign_daily_impression_reason_report_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '캠페인 일별 노출 사유 집계 PK',
    campaign_daily_report_id                    BIGINT       NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    impression_type                             VARCHAR(20)  NOT NULL                 COMMENT '노출 구분(UNBILLED, INVALID)',
    reason_code                                 VARCHAR(50)  NOT NULL                 COMMENT '미과금 사유(NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_SOLD_OUT) 또는 무효 사유(EXPIRED_OR_UNKNOWN, NOT_OWNER)',
    impression_count                            BIGINT       NOT NULL DEFAULT 0       COMMENT '해당 사유의 노출 수',
    created_at                                  DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                                  DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (campaign_daily_impression_reason_report_id),
    UNIQUE KEY UQ_CAMPAIGN_IMPRESSION_REASON_REPORT_DAY_TYPE_REASON (campaign_daily_report_id, impression_type, reason_code),

    CONSTRAINT FK_CAMPAIGN_DAILY_IMPRESSION_REASON_REPORT_CAMPAIGN_DAILY_REPORT FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT CHK_CAMPAIGN_DAILY_IMPRESSION_REASON CHECK ((impression_type = 'UNBILLED' AND reason_code IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_SOLD_OUT')) OR (impression_type = 'INVALID' AND reason_code IN ('EXPIRED_OR_UNKNOWN', 'NOT_OWNER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_report_history
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_report_history (
    campaign_daily_report_history_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '캠페인 일별 리포트 변경 이력 ID',
    campaign_daily_report_id          BIGINT        NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    revision_no                       INT           NOT NULL                 COMMENT '변경 차수',
    changed_reason                    VARCHAR(255)                           COMMENT '변경 사유',
    before_data                       JSON          NOT NULL                 COMMENT '변경 전 데이터',
    after_data                        JSON          NOT NULL                 COMMENT '변경 후 데이터',
    changed_at                        DATETIME(6)   NOT NULL                 COMMENT '변경 시각',
    created_at                        DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at                        DATETIME(6)   NOT NULL,

    PRIMARY KEY (campaign_daily_report_history_id),
    KEY FK_CAMPAIGN_DAILY_REPORT_HISTORY_CAMPAIGN_DAILY_REPORT (campaign_daily_report_id),

    CONSTRAINT FK_CAMPAIGN_DAILY_REPORT_HISTORY_CAMPAIGN_DAILY_REPORT FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_daily_slot_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_daily_slot_report (
    campaign_daily_slot_report_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '캠페인 일별 슬롯 집계 PK',
    campaign_daily_report_id       BIGINT       NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    slot_type                      VARCHAR(20)  NOT NULL                 COMMENT '슬롯 유형(ALLOCATION, RELEVANCE)',
    impression_count               BIGINT       NOT NULL DEFAULT 0       COMMENT '해당 슬롯 유형의 노출 수',
    created_at                     DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                     DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (campaign_daily_slot_report_id),
    UNIQUE KEY UQ_CAMPAIGN_DAILY_SLOT_REPORT_CAMPAIGN_DAILY_REPORT_ID_SLOT_TYPE (campaign_daily_report_id, slot_type),

    CONSTRAINT FK_CAMPAIGN_DAILY_SLOT_REPORT_CAMPAIGN_DAILY_REPORT FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT CHK_CAMPAIGN_DAILY_SLOT_TYPE CHECK (slot_type IN ('ALLOCATION', 'RELEVANCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_hourly_report
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_hourly_report (
    campaign_hourly_report_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '캠페인 시간대별 리포트 PK',
    campaign_daily_report_id   BIGINT       NOT NULL                 COMMENT '캠페인 일별 리포트 ID',
    report_hour                TINYINT      NOT NULL                 COMMENT '집계 시간(10, 11, 12)',
    impression_count           BIGINT       NOT NULL DEFAULT 0       COMMENT '해당 시간대 노출 수',
    created_at                 DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                 DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (campaign_hourly_report_id),
    UNIQUE KEY UQ_CAMPAIGN_HOURLY_REPORT_CAMPAIGN_DAILY_REPORT_ID_REPORT_HOUR (campaign_daily_report_id, report_hour),

    CONSTRAINT FK_CAMPAIGN_HOURLY_REPORT_CAMPAIGN_DAILY_REPORT FOREIGN KEY (campaign_daily_report_id) REFERENCES campaign_daily_report (campaign_daily_report_id),

    CONSTRAINT CHK_CAMPAIGN_HOURLY_REPORT_HOUR CHECK (report_hour IN (10, 11, 12))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- campaign_status_log
-- ----------------------------------------------------------------------------
CREATE TABLE campaign_status_log (
    campaign_status_log_id  BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id             BIGINT        NOT NULL                 COMMENT '캠페인 테이블 참조',
    from_status             VARCHAR(20)   NOT NULL,
    to_status               VARCHAR(20)   NOT NULL,
    actor_type              VARCHAR(20)   NOT NULL                 COMMENT 'ADMIN/OWNER/SYSTEM',
    reason                  VARCHAR(200)                           COMMENT '선택 입력',
    changed_at              DATETIME(6)   NOT NULL,
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,

    PRIMARY KEY (campaign_status_log_id),
    KEY FK_CAMPAIGN_STATUS_LOG_CAMPAIGN (campaign_id),

    CONSTRAINT FK_CAMPAIGN_STATUS_LOG_CAMPAIGN FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),

    CONSTRAINT CHK_CAMPAIGN_STATUS_LOG_ACTOR CHECK (actor_type IN ('ADMIN', 'OWNER', 'SYSTEM')),
    CONSTRAINT CHK_CAMPAIGN_STATUS_LOG_STATUS CHECK (from_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED') AND to_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED') AND from_status <> to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ############################################################################
-- 6. 포스터 (poster)    담당 최재웅
-- ############################################################################

-- ----------------------------------------------------------------------------
-- template
--   소프트 참조: activated_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE template (
    template_id       BIGINT        NOT NULL AUTO_INCREMENT,
    name              VARCHAR(100)  NOT NULL,
    status            VARCHAR(20)   NOT NULL                 COMMENT 'DRAFT(임시저장중)/PUBLISHED(게시됨)',
    html_content      TEXT                                   COMMENT '게시 시점에 TemplateVersion에서 값 복사(FK 아님), 첫 게시 전까지 NULL',
    is_active         BOOLEAN       NOT NULL,
    last_modified_at  DATETIME(6)   NOT NULL                 COMMENT '생성, 게시, 활성화 시마다 갱신',
    published_at      DATETIME(6),
    activated_by      BIGINT                                 COMMENT '계정 도메인 - 관리자 테이블 참조(활성화 관리)',
    activated_at      DATETIME(6),
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,

    PRIMARY KEY (template_id),

    CONSTRAINT CHK_TEMPLATE_STATUS CHECK ((status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- poster
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE poster (
    poster_id     BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id   BIGINT        NOT NULL                 COMMENT '캠페인 테이블 참조. UNIQUE',
    template_id   BIGINT        NOT NULL                 COMMENT '템플릿 테이블 참조',
    title         VARCHAR(100)  NOT NULL                 COMMENT '목록 검색·정렬용',
    slot_values   JSON          NOT NULL                 COMMENT '슬롯별 값 저장, {슬롯키: {type, value}} 형태',
    html_content  TEXT          NOT NULL                 COMMENT 'slot_values를 렌더링한 최종 HTML',
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    poster_image  VARCHAR(255),

    PRIMARY KEY (poster_id),
    UNIQUE KEY UQ_POSTER_CAMPAIGN_ID (campaign_id),
    KEY FK_POSTER_TEMPLATE (template_id),

    CONSTRAINT FK_POSTER_TEMPLATE FOREIGN KEY (template_id) REFERENCES template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- template_version
-- ----------------------------------------------------------------------------
CREATE TABLE template_version (
    template_version_id  BIGINT       NOT NULL AUTO_INCREMENT,
    template_id          BIGINT       NOT NULL                 COMMENT '템플릿 테이블 참조',
    version_number       INT          NOT NULL,
    request_prompt       TEXT         NOT NULL                 COMMENT '관리자 요청 문장',
    html_content         TEXT         NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    deleted_at           DATETIME(6),

    PRIMARY KEY (template_version_id),
    UNIQUE KEY UQ_TEMPLATE_VERSION_TEMPLATE_ID_VERSION_NUMBER (template_id, version_number),

    CONSTRAINT FK_TEMPLATE_VERSION_TEMPLATE FOREIGN KEY (template_id) REFERENCES template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- poster_moderation_result
-- ----------------------------------------------------------------------------
CREATE TABLE poster_moderation_result (
    poster_moderation_result_id  BIGINT        NOT NULL AUTO_INCREMENT,
    poster_id                    BIGINT        NOT NULL                 COMMENT '포스터 테이블 참조',
    result                       VARCHAR(10)   NOT NULL                 COMMENT 'PASS/FAIL',
    reason                       VARCHAR(200),
    checked_at                   DATETIME(6)   NOT NULL,
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,

    PRIMARY KEY (poster_moderation_result_id),
    KEY FK_POSTER_MODERATION_RESULT_POSTER (poster_id),

    CONSTRAINT FK_POSTER_MODERATION_RESULT_POSTER FOREIGN KEY (poster_id) REFERENCES poster (poster_id),

    CONSTRAINT CHK_POSTER_MODERATION_RESULT CHECK (result = 'PASS' OR (result = 'FAIL' AND reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ############################################################################
-- 7. 정산 (billing)    담당 김현우
-- ############################################################################

-- ----------------------------------------------------------------------------
-- point_policy  |  과금 정책 이력형 테이블. [2026-10-01] charge_products/change_reason 컬럼 신규
--   소프트 참조: created_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE point_policy (
    point_policy_id           BIGINT        NOT NULL AUTO_INCREMENT,
    unit_price                BIGINT        NOT NULL,
    min_charge_amount         BIGINT        NOT NULL,
    min_daily_budget          BIGINT        NOT NULL,
    bootstrap_daily_budget    BIGINT        NOT NULL                 COMMENT '기본 3,000P. 반경 내 7일 데이터 없는 신규 가게의 예산 추천 fallback(기능 21·45행)',
    recommended_daily_budget  BIGINT        NOT NULL,
    charge_products           JSON          NOT NULL                 COMMENT '[2026-10-01 신규] 허용 충전 금액 목록(예: [10000,30000,50000,100000]). P-01 1단계 검증과 P-04 응답에 씀',
    change_reason             VARCHAR(255)                           COMMENT '[2026-10-01 신규] 이 정책으로 변경한 사유(선택)',
    effective_date            DATE          NOT NULL,
    created_by                BIGINT        NOT NULL                 COMMENT 'admin_id, 소프트 참조',
    created_at                DATETIME(6)   NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,

    PRIMARY KEY (point_policy_id),
    UNIQUE KEY uq_point_policy_effective_date (effective_date),

    CONSTRAINT ck_point_policy_bootstrap_ge_min CHECK (bootstrap_daily_budget >= min_daily_budget),
    CONSTRAINT ck_point_policy_charge_products CHECK ((json_type(charge_products) = 'ARRAY') AND (json_length(charge_products) > 0)),
    CONSTRAINT ck_point_policy_min_charge CHECK (min_charge_amount >= 0),
    CONSTRAINT ck_point_policy_min_daily_budget CHECK (min_daily_budget >= 0),
    CONSTRAINT ck_point_policy_recommended_daily_budget CHECK (recommended_daily_budget >= 0),
    CONSTRAINT ck_point_policy_unit_price CHECK (unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='과금 정책 이력형 테이블. [2026-10-01] charge_products/change_reason 컬럼 신규';

-- ----------------------------------------------------------------------------
-- payment  |  결제(충전). order_id를 PG 결제창 열기 전에 먼저 생성하고, APPROVED 확정 시에만 point_ledger에 CHARGE 기록. [2026-10-01] order_id/fail_reason/pg_cancel_id/canceled_at 추가, approved_at CHECK 완화
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE payment (
    payment_id    BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT        NOT NULL                               COMMENT '소프트 참조',
    order_id      VARCHAR(64)   NOT NULL                               COMMENT '[2026-10-01 신규] 가맹점(우리 서버) 발급 주문번호. PG 결제창을 열기 전에 이 행을 먼저 만들어두고, 승인 확정 시 이 값 기준으로 금액 위변조를 검증한다',
    pg_tid        VARCHAR(100)                                         COMMENT '[2026-10-01] PG사 결제 식별자(토스페이먼츠 paymentKey 등). 주문 생성 시점엔 NULL, 승인 시도(confirm) 시점에 채워짐',
    amount        BIGINT        NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',
    fail_reason   VARCHAR(255)                                         COMMENT '[2026-10-01 신규] PG 실패 사유. FAILED일 때만 값 존재',
    pg_cancel_id  VARCHAR(100)                                         COMMENT '[2026-10-01 신규] PG 취소 식별자. 승인 후 PG측에서 취소된 경우에만 값 존재(승인 전에 취소된 주문은 NULL)',
    requested_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    approved_at   DATETIME(6),
    canceled_at   DATETIME(6)                                          COMMENT '[2026-10-01 신규] 취소 확정 시각. CANCELLED일 때만 값 존재',
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,

    PRIMARY KEY (payment_id),
    UNIQUE KEY uq_payment_order_id (order_id),
    UNIQUE KEY uq_payment_pg_tid (pg_tid),

    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_approved_at CHECK ((status = 'APPROVED' AND approved_at IS NOT NULL) OR status = 'CANCELLED' OR (status IN ('REQUESTED', 'FAILED', 'UNKNOWN') AND approved_at IS NULL)),
    CONSTRAINT ck_payment_canceled_at CHECK ((status = 'CANCELLED' AND canceled_at IS NOT NULL) OR (status <> 'CANCELLED' AND canceled_at IS NULL)),
    CONSTRAINT ck_payment_fail_reason CHECK ((status = 'FAILED' AND fail_reason IS NOT NULL) OR (status <> 'FAILED' AND fail_reason IS NULL)),
    CONSTRAINT ck_payment_pg_cancel_id CHECK (pg_cancel_id IS NULL OR status = 'CANCELLED'),
    CONSTRAINT ck_payment_pg_tid CHECK ((status IN ('APPROVED', 'FAILED', 'UNKNOWN') AND pg_tid IS NOT NULL) OR status IN ('REQUESTED', 'CANCELLED')),
    CONSTRAINT ck_payment_status CHECK (status IN ('REQUESTED', 'APPROVED', 'FAILED', 'CANCELLED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='결제(충전). order_id를 PG 결제창 열기 전에 먼저 생성하고, APPROVED 확정 시에만 point_ledger에 CHARGE 기록. [2026-10-01] order_id/fail_reason/pg_cancel_id/canceled_at 추가, approved_at CHECK 완화';

-- ----------------------------------------------------------------------------
-- payment_reconciliation_outbox  |  외부 PG 상태 미확정(UNKNOWN) 건 재시도용. payment 테이블은 정산.sql 참고
-- ----------------------------------------------------------------------------
CREATE TABLE payment_reconciliation_outbox (
    payment_reconciliation_outbox_id  BIGINT        NOT NULL AUTO_INCREMENT,
    payment_id                        BIGINT        NOT NULL,
    status                            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count                       INT           NOT NULL DEFAULT 0,
    max_retry                         INT           NOT NULL,
    next_retry_at                     DATETIME(6),
    last_checked_at                   DATETIME(6),
    last_error                        VARCHAR(500),
    created_at                        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                        DATETIME(6)   NOT NULL,

    PRIMARY KEY (payment_reconciliation_outbox_id),
    UNIQUE KEY uq_payment_reconciliation_outbox_payment (payment_id),
    KEY idx_payment_reconciliation_outbox_retry (status, next_retry_at),

    CONSTRAINT fk_payment_reconciliation_outbox_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),

    CONSTRAINT ck_payment_reconciliation_outbox_max_retry CHECK (max_retry >= 0),
    CONSTRAINT ck_payment_reconciliation_outbox_next_retry CHECK ((status = 'PENDING' AND next_retry_at IS NOT NULL) OR status <> 'PENDING'),
    CONSTRAINT ck_payment_reconciliation_outbox_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_payment_reconciliation_outbox_status CHECK (status IN ('PENDING', 'RESOLVED', 'FAILED_PERMANENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='외부 PG 상태 미확정(UNKNOWN) 건 재시도용. payment 테이블은 정산.sql 참고';

-- ----------------------------------------------------------------------------
-- refund_request  |  환불 요청. 전액 환불만, 활성 캠페인 보유 시 요청 자체 불가. [2026-10-01] reason 선택으로 완화, reject_reason 컬럼 신규
--   소프트 참조: owner_id -> owner(점주), processed_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE refund_request (
    refund_request_id  BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id           BIGINT        NOT NULL                               COMMENT '소프트 참조',
    payment_id         BIGINT,
    amount             BIGINT        NOT NULL                               COMMENT '항상 요청 시점 미소진 잔액 전액',
    fee                BIGINT                                               COMMENT '환불 정책상 수수료',
    status             VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',
    reason             VARCHAR(255)                                         COMMENT '[2026-10-01 완화] 요청 사유. 명세상 선택이라 NOT NULL 해제',
    reject_reason      VARCHAR(255)                                         COMMENT '[2026-10-01 신규] 반려 사유. REJECTED일 때 필수 — 감사 로그 전용이 아니라 점주에게 직접 보여주는 값이라 컬럼으로 둠',
    requested_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at       DATETIME(6),
    processed_by       BIGINT                                               COMMENT 'admin_id, 소프트 참조',
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    pending_dedup_key  BIGINT        GENERATED ALWAYS AS ((case when (`status` = 'REQUESTED') then `owner_id` else NULL end)) STORED,

    PRIMARY KEY (refund_request_id),
    UNIQUE KEY uq_refund_request_pending (pending_dedup_key),
    KEY fk_refund_request_payment (payment_id),

    CONSTRAINT fk_refund_request_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),

    CONSTRAINT ck_refund_request_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_request_fee CHECK (fee IS NULL OR fee >= 0),
    CONSTRAINT ck_refund_request_processed CHECK ((status = 'REQUESTED' AND processed_at IS NULL AND processed_by IS NULL) OR (status <> 'REQUESTED' AND processed_at IS NOT NULL AND processed_by IS NOT NULL)),
    CONSTRAINT ck_refund_request_reject_reason CHECK ((status = 'REJECTED' AND reject_reason IS NOT NULL) OR (status <> 'REJECTED' AND reject_reason IS NULL)),
    CONSTRAINT ck_refund_request_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='환불 요청. 전액 환불만, 활성 캠페인 보유 시 요청 자체 불가. [2026-10-01] reason 선택으로 완화, reject_reason 컬럼 신규';

-- ----------------------------------------------------------------------------
-- point_ledger  |  포인트 원장(append-only). REFUND_INVALID는 존재하지 않음(ADJUST/MANUAL로 대체)
--   소프트 참조: owner_id -> owner(점주), campaign_id -> campaign(캠페인), serve_id -> serve_log(광고 서빙)
-- ----------------------------------------------------------------------------
CREATE TABLE point_ledger (
    point_ledger_id        BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id               BIGINT        NOT NULL                               COMMENT '소프트 참조. 잔액은 이 테이블 SUM으로 직접 계산(별도 잔액 컬럼/캐시 없음, DEDUCT 제외)',
    campaign_id            BIGINT                                               COMMENT '소프트 참조. DEDUCT/REFUND/RESERVE/RELEASE의 캠페인별 breakdown용',
    serve_id               BINARY(16)                                           COMMENT '[2026-10-01 변경, BIGINT→BINARY(16)] 소프트 참조. DEDUCT만 값 존재. 발급 주체(광고서빙)가 UUID로 구현해 타입을 맞춤',
    payment_id             BIGINT                                               COMMENT 'CHARGE는 항상 값 존재. RESERVE도 값을 가질 수 있음(충전 직후 재예약 트리거). 정기(00:00) RESERVE는 NULL',
    refund_request_id      BIGINT                                               COMMENT 'REFUND만 값 존재',
    business_date          DATE                                                 COMMENT 'RESERVE/RELEASE만 값 존재. 00:00 배치가 처리한 영업일자',
    entry_type             VARCHAR(20)   NOT NULL                               COMMENT 'CHARGE/RESERVE/DEDUCT/RELEASE/REFUND/ADJUST. 매일 00:00 1회 RESERVE(잔액에서 실차감)→노출마다 DEDUCT(그날 예약액 한도 내 실시간 차감, 잔액엔 미반영)→00:00 미소진분 RELEASE(잔액 복원)',
    source                 VARCHAR(20)   NOT NULL,
    amount                 BIGINT        NOT NULL                               COMMENT '변동량. 부호는 entry_type에 종속',
    reason                 VARCHAR(255)                                         COMMENT 'ADJUST 사유(필수)',
    idempotency_key        VARCHAR(64)                                          COMMENT '[13차 확정] ADJUST 전용 멱등 키(P-19 Idempotency-Key 헤더). 같은 점주·같은 키의 조정은 1건만 기록',
    created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)   NOT NULL,
    day_reserve_dedup_key  VARCHAR(50)   GENERATED ALWAYS AS ((case when ((`entry_type` in ('RESERVE','RELEASE')) and (`payment_id` is null)) then concat(`campaign_id`,'|',`business_date`,'|',`entry_type`) else NULL end)) STORED,

    PRIMARY KEY (point_ledger_id),
    UNIQUE KEY uq_point_ledger_serve_entry (serve_id, entry_type),
    UNIQUE KEY uq_point_ledger_payment_entry (payment_id, entry_type),
    UNIQUE KEY uq_point_ledger_refund_request_entry (refund_request_id, entry_type),
    UNIQUE KEY uq_point_ledger_day_reserve_dedup (day_reserve_dedup_key),
    UNIQUE KEY uq_point_ledger_adjust_idem (owner_id, idempotency_key),
    KEY idx_point_ledger_owner_created (owner_id, created_at),
    KEY idx_point_ledger_campaign (campaign_id),

    CONSTRAINT fk_point_ledger_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_point_ledger_refund_request FOREIGN KEY (refund_request_id) REFERENCES refund_request (refund_request_id),

    CONSTRAINT ck_point_ledger_adjust_reason CHECK (entry_type <> 'ADJUST' OR reason IS NOT NULL),
    CONSTRAINT ck_point_ledger_adjust_source CHECK (entry_type <> 'ADJUST' OR source = 'MANUAL'),
    CONSTRAINT ck_point_ledger_amount_sign CHECK ((entry_type = 'CHARGE' AND amount > 0) OR (entry_type = 'RESERVE' AND amount < 0) OR (entry_type = 'DEDUCT' AND amount < 0) OR (entry_type = 'RELEASE' AND amount > 0) OR (entry_type = 'REFUND' AND amount < 0) OR (entry_type = 'ADJUST' AND amount <> 0)),
    CONSTRAINT ck_point_ledger_business_date CHECK ((entry_type IN ('RESERVE', 'RELEASE') AND business_date IS NOT NULL) OR (entry_type NOT IN ('RESERVE', 'RELEASE') AND business_date IS NULL)),
    CONSTRAINT ck_point_ledger_entry_type CHECK (entry_type IN ('CHARGE', 'RESERVE', 'DEDUCT', 'RELEASE', 'REFUND', 'ADJUST')),
    CONSTRAINT ck_point_ledger_idempotency_key CHECK (idempotency_key IS NULL OR entry_type = 'ADJUST'),
    CONSTRAINT ck_point_ledger_payment_id CHECK ((entry_type = 'CHARGE' AND payment_id IS NOT NULL) OR entry_type = 'RESERVE' OR (entry_type NOT IN ('CHARGE', 'RESERVE') AND payment_id IS NULL)),
    CONSTRAINT ck_point_ledger_refund_request_id CHECK ((entry_type = 'REFUND' AND refund_request_id IS NOT NULL) OR (entry_type <> 'REFUND' AND refund_request_id IS NULL)),
    CONSTRAINT ck_point_ledger_serve_id CHECK ((entry_type = 'DEDUCT' AND serve_id IS NOT NULL) OR (entry_type <> 'DEDUCT' AND serve_id IS NULL)),
    CONSTRAINT ck_point_ledger_source CHECK (source IN ('REALTIME', 'RECONCILIATION', 'MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='포인트 원장(append-only). REFUND_INVALID는 존재하지 않음(ADJUST/MANUAL로 대체)';

-- ----------------------------------------------------------------------------
-- settlement_mismatch  |  정산 대사 불일치
--   소프트 참조: resolved_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE settlement_mismatch (
    settlement_mismatch_id  BIGINT       NOT NULL AUTO_INCREMENT,
    check_type              VARCHAR(30)  NOT NULL,
    target_id               BIGINT       NOT NULL                               COMMENT '소프트 참조',
    expected_value          BIGINT       NOT NULL,
    actual_value            BIGINT       NOT NULL,
    resolution_type         VARCHAR(30),
    detected_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    job_execution_id        BIGINT                                              COMMENT 'BatchExecutionLog(운영 도메인) 소프트 참조. Spring Batch 메타테이블 아님',
    resolved_at             DATETIME(6),
    resolved_by             BIGINT                                              COMMENT 'admin_id, 소프트 참조',
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    PRIMARY KEY (settlement_mismatch_id),

    CONSTRAINT ck_settlement_mismatch_check_type CHECK (check_type IN ('PG_VS_CHARGE', 'CAMPAIGN_SPEND_VS_LEDGER', 'SPEND_VS_IMPRESSION')),
    CONSTRAINT ck_settlement_mismatch_resolution_type CHECK (resolution_type IS NULL OR resolution_type IN ('CACHE_REBUILD', 'LEDGER_CORRECTION', 'OVERCHARGE_REVERSAL')),
    CONSTRAINT ck_settlement_mismatch_resolved CHECK ((resolution_type IS NULL AND resolved_at IS NULL AND resolved_by IS NULL) OR (resolution_type IS NOT NULL AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='정산 대사 불일치';

-- ############################################################################
-- 8. 광고 서빙 (adserving)    담당 박준서
-- ############################################################################

-- ----------------------------------------------------------------------------
-- ad_candidate  |  그날의 피드 후보. 서빙 경로는 이 테이블만 읽는다. 후보 조건: campaign_state = SERVABLE AND sold_out_at IS NULL
--   소프트 참조: campaign_id -> campaign(캠페인), store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE ad_candidate (
    business_date        DATE           NOT NULL  COMMENT '영업일',
    campaign_id          BIGINT         NOT NULL  COMMENT 'campaign 도메인 ID',
    store_id             BIGINT         NOT NULL  COMMENT 'store 도메인 ID',
    store_name           VARCHAR(100)   NOT NULL  COMMENT '카드 표시용 상호',
    category_code        VARCHAR(30)    NOT NULL  COMMENT '업종 코드. VARCHAR(30) 확정, 가게 도메인 코드값은 30자 이내',
    latitude             DECIMAL(10,7)  NOT NULL  COMMENT '가게 위도. store.latitude와 같은 이름과 타입',
    longitude            DECIMAL(10,7)  NOT NULL  COMMENT '가게 경도. store.longitude와 같은 이름과 타입',
    owner_radius_m       SMALLINT       NOT NULL  COMMENT '점주 반경 500/1000/2000/3000',
    target_gender        VARCHAR(30)    NOT NULL  COMMENT 'Java enum TargetGender: ALL, MALE, FEMALE',
    target_age_groups    SET('AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS')  NOT NULL  COMMENT '빈 값 없음(CHECK), 전체는 4개 모두. 값 추가는 CHECK 때문에 COPY지만 하루 수천 행이라 감수',
    discount_target      VARCHAR(30)    NOT NULL  COMMENT 'Java enum DiscountTarget: ALL, MENU',
    discount_type        VARCHAR(30)    NOT NULL  COMMENT 'Java enum DiscountType: PERCENT, AMOUNT',
    discount_value       INT            NOT NULL,
    usable_start_time    TIME           NOT NULL  COMMENT '캠페인별 사용 가능 시작',
    usable_end_time      TIME           NOT NULL  COMMENT '캠페인별 사용 가능 종료',
    issue_quantity       INT            NOT NULL  COMMENT '선착순 쿠폰 발급 수량(표시용)',
    campaign_created_at  DATETIME(6)    NOT NULL  COMMENT '배분 슬롯 동률 정렬 키',
    new_priority_until   DATE                     COMMENT '신규 우선 노출 마지막 날. NULL이면 대상 아님',
    campaign_state       VARCHAR(30)    NOT NULL  COMMENT 'Java enum CandidateCampaignState: SERVABLE, PAUSED, ENDED. 캠페인 통보로만 바뀜. 00:00 적재 시 ACTIVE -> SERVABLE, 예약 있는 PAUSED(OWNER, NO_POINTS) -> PAUSED',
    state_changed_at     DATETIME(6)              COMMENT '캠페인이 알려준 마지막 전이 시각. 적재, 적재 직후 재조회, 통보 모두 이 값보다 늦을 때만 갱신',
    sold_out_at          DATETIME(6)              COMMENT '쿠폰 소진 통보 시각. NULL이 아니면 그날 후보 제외',
    loaded_at            DATETIME(6)    NOT NULL,
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,

    PRIMARY KEY (business_date, campaign_id),
    KEY idx_candidate_geo (business_date, latitude, longitude),

    CONSTRAINT chk_candidate_age_groups CHECK (target_age_groups <> ''),
    CONSTRAINT chk_candidate_discount_target CHECK (discount_target IN ('ALL', 'MENU')),
    CONSTRAINT chk_candidate_discount_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT chk_candidate_discount_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    CONSTRAINT chk_candidate_gender CHECK (target_gender IN ('ALL', 'MALE', 'FEMALE')),
    CONSTRAINT chk_candidate_radius CHECK (owner_radius_m IN (500, 1000, 2000, 3000)),
    CONSTRAINT chk_candidate_state CHECK (campaign_state IN ('SERVABLE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_candidate_usable_time CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='그날의 피드 후보. 서빙 경로는 이 테이블만 읽는다. 후보 조건: campaign_state = SERVABLE AND sold_out_at IS NULL';

-- ----------------------------------------------------------------------------
-- feed_filter  |  행이 없으면 기본값(전체 카테고리, 거리순)
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE feed_filter (
    member_id   BIGINT       NOT NULL,
    sort_type   VARCHAR(30)  NOT NULL DEFAULT 'DISTANCE'  COMMENT 'Java enum FeedSort: DISTANCE, DISCOUNT_RATE',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,

    PRIMARY KEY (member_id),

    CONSTRAINT chk_filter_sort CHECK (sort_type IN ('DISTANCE', 'DISCOUNT_RATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='행이 없으면 기본값(전체 카테고리, 거리순)';

-- ----------------------------------------------------------------------------
-- feed_filter_category  |  0건이면 전체 카테고리
-- ----------------------------------------------------------------------------
CREATE TABLE feed_filter_category (
    member_id      BIGINT       NOT NULL,
    category_code  VARCHAR(30)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (member_id, category_code),

    CONSTRAINT fk_filter_category_filter FOREIGN KEY (member_id) REFERENCES feed_filter (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='0건이면 전체 카테고리';

-- ----------------------------------------------------------------------------
-- feed_visit_daily  |  (영업일, 사용자)당 그날 첫 피드 요청 위치 1건. INSERT IGNORE
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE feed_visit_daily (
    business_date  DATE          NOT NULL,
    member_id      BIGINT        NOT NULL,
    lat_rounded    DECIMAL(6,3)  NOT NULL,
    lng_rounded    DECIMAL(7,3)  NOT NULL,
    first_seen_at  DATETIME(6)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,

    PRIMARY KEY (business_date, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='(영업일, 사용자)당 그날 첫 피드 요청 위치 1건. INSERT IGNORE';

-- ----------------------------------------------------------------------------
-- impression_log  |  노출 이벤트. 13개월 보관. 중복 저장은 PK로 거부(INSERT IGNORE). 유효면 billing_status 필수, 무효면 invalid_reason 필수는 엔티티 생성 지점에서 지킨다. 이 컬럼들을 참조하는 CHECK를 두면 ENUM 값 추가가 테이블 복사가 되므로 두지 않는다
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인), store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE impression_log (
    serve_id        BINARY(16)                NOT NULL  COMMENT '클라이언트가 보낸 값. 무효면 서버가 발급하지 않은 값일 수 있음',
    member_id       BIGINT                    NOT NULL  COMMENT '요청 사용자(토큰 기준)',
    business_date   DATE                      NOT NULL  COMMENT '수신 영업일. 보관 기간 삭제 기준',
    campaign_id     BIGINT                    NOT NULL,
    store_id        BIGINT                              COMMENT 'serve 기록이 있을 때만',
    slot_type       ENUM('ALLOCATION', 'RELEVANCE')     COMMENT 'serve 기록이 있을 때만',
    slot_position   TINYINT,
    displayed_at    DATETIME(6)               NOT NULL  COMMENT '클라이언트 표시 시각',
    received_at     DATETIME(6)               NOT NULL  COMMENT '서버 수신 시각',
    lat_rounded     DECIMAL(6,3)              NOT NULL  COMMENT '소수점 3자리 반올림',
    lng_rounded     DECIMAL(7,3)              NOT NULL,
    validity        ENUM('VALID', 'INVALID')  NOT NULL,
    invalid_reason  ENUM('EXPIRED_OR_UNKNOWN', 'NOT_OWNER'),
    billing_status  ENUM('BILLED', 'NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_SOLD_OUT')  COMMENT '유효일 때만. SOLD_OUT은 광고 서빙이 sold_out_at을 보고 판정 호출 없이 기록, 나머지는 campaign의 판정 결과',
    viewed_ms       INT                                 COMMENT '참고 지표(선택 데이터)',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,

    PRIMARY KEY (serve_id, member_id),
    KEY idx_imp_campaign_date (campaign_id, business_date, billing_status),
    KEY idx_imp_date_reason (business_date, invalid_reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='노출 이벤트. 13개월 보관. 중복 저장은 PK로 거부(INSERT IGNORE). 유효면 billing_status 필수, 무효면 invalid_reason 필수는 엔티티 생성 지점에서 지킨다. 이 컬럼들을 참조하는 CHECK를 두면 ENUM 값 추가가 테이블 복사가 되므로 두지 않는다';

-- ----------------------------------------------------------------------------
-- serve_log  |  피드 응답 카드별 기록. 응답 뒤 비동기 저장. 13개월 보관
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인), store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE serve_log (
    serve_id       BINARY(16)   NOT NULL  COMMENT 'UUIDv7',
    business_date  DATE         NOT NULL  COMMENT '영업일. 보관 기간 삭제 기준',
    feed_id        BINARY(16)   NOT NULL  COMMENT '피드 요청 1회 = 카드 10장 묶음',
    member_id      BIGINT       NOT NULL,
    campaign_id    BIGINT       NOT NULL,
    store_id       BIGINT       NOT NULL,
    slot_type      ENUM('ALLOCATION', 'RELEVANCE')  NOT NULL  COMMENT '배분 / 관련성',
    slot_position  TINYINT      NOT NULL  COMMENT '1~10',
    served_at      DATETIME(6)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (serve_id),
    KEY idx_serve_member_date (member_id, business_date),
    KEY idx_serve_campaign_date (campaign_id, business_date),
    KEY idx_serve_date (business_date),

    CONSTRAINT chk_serve_position CHECK (slot_position BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='피드 응답 카드별 기록. 응답 뒤 비동기 저장. 13개월 보관';

-- ----------------------------------------------------------------------------
-- swipe_log  |  (serve_id, 요청 사용자)당 최초 1건. 31일 보관
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE swipe_log (
    serve_id       BINARY(16)   NOT NULL  COMMENT '클라이언트가 보낸 값',
    member_id      BIGINT       NOT NULL  COMMENT '요청 사용자(토큰 기준). 남의 serve_id로 먼저 보내도 정상 행을 막지 못하게 키에 포함',
    business_date  DATE         NOT NULL  COMMENT '영업일. 선호도 집계 기간과 보관 기간 삭제 기준',
    campaign_id    BIGINT       NOT NULL,
    category_code  VARCHAR(30)  NOT NULL  COMMENT 'ad_candidate에서 복사. 선호도 집계용',
    action         VARCHAR(30)  NOT NULL  COMMENT 'Java enum SwipeAction: WISH, PASS',
    occurred_at    DATETIME(6)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (serve_id, member_id),
    KEY idx_swipe_member_date (member_id, business_date),
    KEY idx_swipe_date (business_date),

    CONSTRAINT chk_swipe_action CHECK (action IN ('WISH', 'PASS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='(serve_id, 요청 사용자)당 최초 1건. 31일 보관';

-- ----------------------------------------------------------------------------
-- wishlist  |  삭제와 캠페인 종료 시 행 삭제
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인), store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE wishlist (
    wishlist_id  BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,
    campaign_id  BIGINT       NOT NULL,
    store_id     BIGINT       NOT NULL,
    serve_id     BINARY(16)                            COMMENT '피드 찜이면 출처 serve_id, 가게 상세 찜이면 NULL',
    wished_at    DATETIME(6)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,

    PRIMARY KEY (wishlist_id),
    UNIQUE KEY uk_wishlist_member_campaign (member_id, campaign_id),
    KEY idx_wishlist_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='삭제와 캠페인 종료 시 행 삭제';

-- ############################################################################
-- 9. 쿠폰 (coupon)    담당 최민혁
-- ############################################################################

-- ----------------------------------------------------------------------------
-- coupon_daily_limit
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_daily_limit (
    coupon_daily_limit_id  BIGINT       NOT NULL AUTO_INCREMENT,
    member_id              BIGINT       NOT NULL,
    issued_count           INT          NOT NULL,
    business_date          DATE         NOT NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_daily_limit_id),
    UNIQUE KEY UQ_COUPON_DAILY_LIMIT_MEMBER_DATE (member_id, business_date),

    CONSTRAINT CHK_COUPON_DAILY_LIMIT_COUNT CHECK (issued_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- coupon_event
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_event (
    coupon_event_id    BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id        BIGINT       NOT NULL,
    business_date      DATE         NOT NULL,
    coupon_quantity    INT          NOT NULL,
    usable_start_time  DATETIME(6)  NOT NULL,
    usable_end_time    DATETIME(6)  NOT NULL,
    status             VARCHAR(20)  NOT NULL                 COMMENT 'SCHEDULED/ACTIVE/PAUSED/ENDED',
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_event_id),
    KEY IDX_COUPON_EVENT_CAMPAIGN (campaign_id),

    CONSTRAINT CHK_COUPON_EVENT_QUANTITY CHECK (coupon_quantity > 0),
    CONSTRAINT CHK_COUPON_EVENT_STATUS CHECK (status IN ('SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT CHK_COUPON_EVENT_USABLE_TIME CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- coupon_inventory
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_inventory (
    coupon_inventory_id  BIGINT       NOT NULL AUTO_INCREMENT,
    event_id             BIGINT       NOT NULL,
    sequence_no          INT          NOT NULL,
    coupon_code          BINARY(16)   NOT NULL,
    status               VARCHAR(20)  NOT NULL                 COMMENT 'AVAILABLE/ISSUED',
    usable_start_time    DATETIME(6)  NOT NULL,
    usable_end_time      DATETIME(6)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_inventory_id),
    UNIQUE KEY UQ_COUPON_INVENTORY_EVENT_ID_SEQUENCE_NO (event_id, sequence_no),
    UNIQUE KEY UQ_COUPON_INVENTORY_COUPON_CODE (coupon_code),

    CONSTRAINT FK_COUPON_INVENTORY_EVENT FOREIGN KEY (event_id) REFERENCES coupon_event (coupon_event_id),

    CONSTRAINT CHK_COUPON_INVENTORY_SEQUENCE CHECK (sequence_no > 0),
    CONSTRAINT CHK_COUPON_INVENTORY_STATUS CHECK (status IN ('AVAILABLE', 'ISSUED')),
    CONSTRAINT CHK_COUPON_INVENTORY_USABLE_TIME CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- member_coupon
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE member_coupon (
    member_coupon_id   BIGINT       NOT NULL AUTO_INCREMENT,
    event_id           BIGINT       NOT NULL,
    member_id          BIGINT       NOT NULL,
    coupon_code        BINARY(16)   NOT NULL,
    qr_version         INT          NOT NULL DEFAULT 0,
    qr_token           BINARY(16),
    qr_expires_at      DATETIME(6),
    status             VARCHAR(20)  NOT NULL                 COMMENT 'ISSUED/USED/EXPIRED/REMOVED',
    usable_start_time  DATETIME(6)  NOT NULL,
    usable_end_time    DATETIME(6)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (member_coupon_id),
    UNIQUE KEY UQ_MEMBER_COUPON_EVENT_MEMBER (event_id, member_id),
    UNIQUE KEY UQ_MEMBER_COUPON_COUPON_CODE (coupon_code),
    KEY IDX_MEMBER_COUPON_MEMBER (member_id),

    CONSTRAINT FK_MEMBER_COUPON_EVENT FOREIGN KEY (event_id) REFERENCES coupon_event (coupon_event_id),

    CONSTRAINT CHK_MEMBER_COUPON_STATUS CHECK (status IN ('ISSUED', 'USED', 'EXPIRED', 'REMOVED')),
    CONSTRAINT CHK_MEMBER_COUPON_USABLE_TIME CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- coupon_usage_history
--   소프트 참조: store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE coupon_usage_history (
    coupon_usage_history_id  BIGINT       NOT NULL AUTO_INCREMENT,
    member_coupon_id         BIGINT       NOT NULL,
    store_id                 BIGINT       NOT NULL,
    discount_amount          INT          NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (coupon_usage_history_id),
    KEY FK_COUPON_USAGE_HISTORY_MEMBER_COUPON (member_coupon_id),
    KEY IDX_COUPON_USAGE_HISTORY_STORE (store_id),

    CONSTRAINT FK_COUPON_USAGE_HISTORY_MEMBER_COUPON FOREIGN KEY (member_coupon_id) REFERENCES member_coupon (member_coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ############################################################################
-- 10. 알림 (notification)    담당 정규동
-- ############################################################################

-- ----------------------------------------------------------------------------
-- notification_device
--   소프트 참조: member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE notification_device (
    notification_device_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 대상 기기 PK',
    member_id               BIGINT        NOT NULL                 COMMENT 'Push 등록 정보를 소유한 사용자 ID',
    fid                     VARCHAR(255)  NOT NULL                 COMMENT 'Firebase Installation ID(FID)',
    last_synced_at          DATETIME(6)   NOT NULL                 COMMENT 'FID가 마지막으로 서버에 등록 또는 갱신된 시각',
    created_at              DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at              DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_device_id),
    UNIQUE KEY UK_NOTIFICATION_DEVICE_FID (fid),
    KEY IDX_NOTIFICATION_DEVICE_MEMBER (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE notification (
    notification_id    BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 PK',
    member_id          BIGINT        NOT NULL                 COMMENT '알림 대상 사용자 ID',
    campaign_id        BIGINT        NOT NULL                 COMMENT '알림 대상 캠페인 ID',
    notification_type  VARCHAR(50)   NOT NULL                 COMMENT '알림 유형(CAMPAIGN_OPEN_REMINDER)',
    title              VARCHAR(100)  NOT NULL                 COMMENT '알림 제목',
    content            VARCHAR(500)  NOT NULL                 COMMENT '알림 내용',
    target_url         VARCHAR(500)                           COMMENT '알림 클릭 시 이동할 프론트 페이지 경로',
    business_date      DATE          NOT NULL                 COMMENT '알림 대상 날짜. 중복 발송 판단에 사용',
    read_at            DATETIME(6)                            COMMENT '알림 읽은 시각. NULL이면 안읽음',
    created_at         DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at         DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_id),
    UNIQUE KEY UK_NOTIFICATION_MEMBER_CAMPAIGN_TYPE_DATE (member_id, campaign_id, notification_type, business_date),
    KEY IDX_NOTIFICATION_CAMPAIGN (campaign_id),
    KEY IDX_NOTIFICATION_MEMBER_CREATED_AT (member_id, created_at),

    CONSTRAINT CHK_NOTIFICATION_TYPE CHECK (notification_type = 'CAMPAIGN_OPEN_REMINDER')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification_delivery
-- ----------------------------------------------------------------------------
CREATE TABLE notification_delivery (
    notification_delivery_id  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 발송 정보 PK',
    notification_id           BIGINT        NOT NULL                 COMMENT '알림 PK',
    notification_device_id    BIGINT        NOT NULL                 COMMENT '알림 대상 기기 PK',
    status                    VARCHAR(20)   NOT NULL                 COMMENT '발송 상태(PENDING, PROCESSING, SENT, RETRY, FAILED, EXPIRED)',
    attempt_count             INT           NOT NULL DEFAULT 0       COMMENT 'FCM 실제 발송 시도 횟수. 최초 발송을 포함하며 재시도는 최대 2회',
    next_attempt_at           DATETIME(6)                            COMMENT '다음 재시도 예정 시각',
    last_attempt_at           DATETIME(6)                            COMMENT '마지막 FCM 발송 시도 시각',
    sent_at                   DATETIME(6)                            COMMENT 'FCM 발송 요청 성공 시각',
    last_error_code           VARCHAR(100)                           COMMENT '마지막 발송 실패 오류 코드',
    last_error_message        VARCHAR(500)                           COMMENT '마지막 발송 실패 오류 내용',
    provider_message_id       VARCHAR(255)                           COMMENT 'FCM 발송 성공 시 반환된 메시지 식별자',
    created_at                DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at                DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (notification_delivery_id),
    UNIQUE KEY UK_NOTIFICATION_DELIVERY_NOTIFICATION_DEVICE (notification_id, notification_device_id),
    KEY FK_NOTIFICATION_DELIVERY_DEVICE (notification_device_id),
    KEY IDX_NOTIFICATION_DELIVERY_STATUS_RETRY (status, next_attempt_at),

    CONSTRAINT FK_NOTIFICATION_DELIVERY_DEVICE FOREIGN KEY (notification_device_id) REFERENCES notification_device (notification_device_id),
    CONSTRAINT FK_NOTIFICATION_DELIVERY_NOTIFICATION FOREIGN KEY (notification_id) REFERENCES notification (notification_id),

    CONSTRAINT CHK_NOTIFICATION_DELIVERY_ATTEMPT CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT CHK_NOTIFICATION_DELIVERY_SENT CHECK ((status = 'SENT' AND sent_at IS NOT NULL) OR (status <> 'SENT' AND sent_at IS NULL)),
    CONSTRAINT CHK_NOTIFICATION_DELIVERY_STATUS CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'RETRY', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification_sent
-- ----------------------------------------------------------------------------
CREATE TABLE notification_sent (
    notification_sent_id      BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '알림 발송 성공 이벤트 PK',
    notification_delivery_id  BIGINT       NOT NULL                 COMMENT '발송 성공한 알림 발송 정보 ID',
    sent_at                   DATETIME(6)  NOT NULL                 COMMENT 'FCM 발송 요청 성공 시각',
    created_at                DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at                DATETIME(6)  NOT NULL,

    PRIMARY KEY (notification_sent_id),
    UNIQUE KEY UK_NOTIFICATION_SENT_DELIVERY (notification_delivery_id),

    CONSTRAINT FK_NOTIFICATION_SENT_DELIVERY FOREIGN KEY (notification_delivery_id) REFERENCES notification_delivery (notification_delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- notification_outbox
-- ----------------------------------------------------------------------------
CREATE TABLE notification_outbox (
    outbox_id                 BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '알림 Outbox PK',
    notification_delivery_id  BIGINT        NOT NULL                 COMMENT '발송할 알림 발송 정보 ID',
    event_type                VARCHAR(50)   NOT NULL                 COMMENT 'Outbox 이벤트 유형(NOTIFICATION_SEND)',
    status                    VARCHAR(20)   NOT NULL                 COMMENT 'Outbox 처리 상태(PENDING, PROCESSING, RETRY, PROCESSED, FAILED)',
    attempt_count             INT           NOT NULL DEFAULT 0       COMMENT 'Outbox 처리 시도 횟수',
    next_attempt_at           DATETIME(6)                            COMMENT '다음 처리 예정 시각',
    last_error_code           VARCHAR(100)                           COMMENT '마지막 처리 오류 코드',
    processed_at              DATETIME(6)                            COMMENT 'Outbox 처리 완료 시각',
    created_at                DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at                DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (outbox_id),
    UNIQUE KEY UK_NOTIFICATION_OUTBOX_DELIVERY (notification_delivery_id),
    KEY IDX_NOTIFICATION_OUTBOX_STATUS_RETRY (status, next_attempt_at),

    CONSTRAINT FK_NOTIFICATION_OUTBOX_DELIVERY FOREIGN KEY (notification_delivery_id) REFERENCES notification_delivery (notification_delivery_id),

    CONSTRAINT CHK_NOTIFICATION_OUTBOX_EVENT_TYPE CHECK (event_type = 'NOTIFICATION_SEND'),
    CONSTRAINT CHK_NOTIFICATION_OUTBOX_PROCESSED CHECK ((status = 'PROCESSED' AND processed_at IS NOT NULL) OR (status <> 'PROCESSED' AND processed_at IS NULL)),
    CONSTRAINT CHK_NOTIFICATION_OUTBOX_STATUS CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'PROCESSED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ############################################################################
-- 11. 분석 (analytics)    담당 김현우
-- ############################################################################

-- ----------------------------------------------------------------------------
-- daily_campaign_not_billed_analytics  |  캠페인·사유별 일별 미과금 노출 수(기능 60행 필수 데이터). impression_event(광고서빙 소유).billing_status=NOT_BILLED 집계
--   소프트 참조: campaign_id -> campaign(캠페인)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_campaign_not_billed_analytics (
    date               DATE         NOT NULL,
    campaign_id        BIGINT       NOT NULL            COMMENT '소프트 참조',
    not_billed_reason  VARCHAR(30)  NOT NULL,
    not_billed_count   BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (date, campaign_id, not_billed_reason),

    CONSTRAINT ck_daily_campaign_not_billed_nonneg CHECK (not_billed_count >= 0),
    CONSTRAINT ck_daily_campaign_not_billed_reason CHECK (not_billed_reason IN ('NOT_BILLED_BUDGET', 'NOT_BILLED_PAUSED', 'NOT_BILLED_SOLD_OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='캠페인·사유별 일별 미과금 노출 수(기능 60행 필수 데이터). impression_event(광고서빙 소유).billing_status=NOT_BILLED 집계';

-- ----------------------------------------------------------------------------
-- daily_invalid_analytics  |  사유코드별 일별 무효 노출 지표. [2026-10-01] member_id 축 신규 추가, surrogate PK로 변경(campaign_id/member_id 조합별 분해 허용). [13차 확정] rate_limit_429_count 제거 — 피드 429 집계는 운영 모니터링(Grafana)으로 대체
--   소프트 참조: campaign_id -> campaign(캠페인), member_id -> member(회원)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_invalid_analytics (
    daily_invalid_analytics_id     BIGINT       NOT NULL AUTO_INCREMENT,
    date                           DATE         NOT NULL,
    reason_code                    VARCHAR(30)  NOT NULL,
    campaign_id                    BIGINT                                COMMENT '캠페인별 분해 시에만 값 존재. NULL이면 플랫폼 전체 합계 행',
    member_id                      BIGINT                                COMMENT '[2026-10-01 신규] 사용자별 분해 시에만 값 존재(A-09 memberId 필터). NULL이면 해당 축 미분해',
    invalid_count                  BIGINT       NOT NULL DEFAULT 0,
    suspicious_concentration_flag  BOOLEAN      NOT NULL DEFAULT 0,
    created_at                     DATETIME(6)  NOT NULL,
    updated_at                     DATETIME(6)  NOT NULL,

    PRIMARY KEY (daily_invalid_analytics_id),
    KEY idx_daily_invalid_analytics_date_campaign (date, campaign_id, reason_code),
    KEY idx_daily_invalid_analytics_date_member (date, member_id, reason_code),

    CONSTRAINT ck_daily_invalid_analytics_nonneg CHECK (invalid_count >= 0),
    CONSTRAINT ck_daily_invalid_analytics_reason_code CHECK (reason_code IN ('EXPIRED_OR_UNKNOWN', 'NOT_OWNER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='사유코드별 일별 무효 노출 지표. [2026-10-01] member_id 축 신규 추가, surrogate PK로 변경(campaign_id/member_id 조합별 분해 허용). [13차 확정] rate_limit_429_count 제거 — 피드 429 집계는 운영 모니터링(Grafana)으로 대체';

-- ----------------------------------------------------------------------------
-- daily_member_analytics  |  일별 회원 지표
-- ----------------------------------------------------------------------------
CREATE TABLE daily_member_analytics (
    date                     DATE  NOT NULL,
    new_member_count         INT   NOT NULL DEFAULT 0,
    cumulative_member_count  INT   NOT NULL DEFAULT 0,
    active_member_count      INT   NOT NULL DEFAULT 0,
    withdrawn_count          INT   NOT NULL DEFAULT 0,
    suspended_count          INT   NOT NULL DEFAULT 0,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (date),

    CONSTRAINT ck_daily_member_analytics_nonneg CHECK (new_member_count >= 0 AND cumulative_member_count >= 0 AND active_member_count >= 0 AND withdrawn_count >= 0 AND suspended_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 회원 지표';

-- ----------------------------------------------------------------------------
-- daily_owner_spend  |  점주별 일별 소진액(A-08 상위 소진 목록). [13차 확정] 신규
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_owner_spend (
    date          DATE    NOT NULL,
    owner_id      BIGINT  NOT NULL            COMMENT '소프트 참조',
    spent_amount  BIGINT  NOT NULL DEFAULT 0  COMMENT 'DEDUCT 합의 절댓값',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (date, owner_id),

    CONSTRAINT ck_daily_owner_spend_nonneg CHECK (spent_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='점주별 일별 소진액(A-08 상위 소진 목록). [13차 확정] 신규';

-- ----------------------------------------------------------------------------
-- daily_segment_analytics  |  카테고리/지역별 일별 분해 지표
-- ----------------------------------------------------------------------------
CREATE TABLE daily_segment_analytics (
    date              DATE         NOT NULL,
    category          VARCHAR(50)  NOT NULL,
    region            VARCHAR(50)  NOT NULL,
    impression_count  BIGINT       NOT NULL DEFAULT 0,
    wish_count        BIGINT       NOT NULL DEFAULT 0,
    issue_count       BIGINT       NOT NULL DEFAULT 0,
    redeem_count      BIGINT       NOT NULL DEFAULT 0,
    spend_amount      BIGINT       NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (date, category, region),

    CONSTRAINT ck_daily_segment_analytics_nonneg CHECK (impression_count >= 0 AND wish_count >= 0 AND issue_count >= 0 AND redeem_count >= 0 AND spend_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='카테고리/지역별 일별 분해 지표';

-- ----------------------------------------------------------------------------
-- daily_settlement_analytics  |  일별 정산 지표. [2026-10-01] reserved_amount/released_amount/adjust_amount 신규(A-07 매출 현황에 예약액·해제액·조정액 표시 필요)
-- ----------------------------------------------------------------------------
CREATE TABLE daily_settlement_analytics (
    date             DATE    NOT NULL,
    charge_amount    BIGINT  NOT NULL DEFAULT 0,
    deduct_amount    BIGINT  NOT NULL DEFAULT 0,
    reserved_amount  BIGINT  NOT NULL DEFAULT 0  COMMENT '[2026-10-01 신규] 그날 예약액(RESERVE 합)',
    released_amount  BIGINT  NOT NULL DEFAULT 0  COMMENT '[2026-10-01 신규] 전일 미소진 해제액(RELEASE 합)',
    refund_amount    BIGINT  NOT NULL DEFAULT 0,
    adjust_amount    BIGINT  NOT NULL DEFAULT 0  COMMENT '[2026-10-01 신규] 관리자 조정액(ADJUST 합, ±). CHECK에서 제외(음수 허용)',
    unspent_balance  BIGINT  NOT NULL DEFAULT 0  COMMENT '미소진 잔액 합계 = 점주 잔액 합 + 예약 중 포인트(그날 예약액 − 당일 소진)',
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (date),

    CONSTRAINT ck_daily_settlement_analytics_nonneg CHECK (charge_amount >= 0 AND deduct_amount >= 0 AND reserved_amount >= 0 AND released_amount >= 0 AND refund_amount >= 0 AND unspent_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 정산 지표. [2026-10-01] reserved_amount/released_amount/adjust_amount 신규(A-07 매출 현황에 예약액·해제액·조정액 표시 필요)';

-- ----------------------------------------------------------------------------
-- daily_store_analytics  |  일별 가게/캠페인 지표
-- ----------------------------------------------------------------------------
CREATE TABLE daily_store_analytics (
    date                    DATE  NOT NULL,
    new_store_count         INT   NOT NULL DEFAULT 0,
    cumulative_store_count  INT   NOT NULL DEFAULT 0,
    active_campaign_count   INT   NOT NULL DEFAULT 0,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    PRIMARY KEY (date),

    CONSTRAINT ck_daily_store_analytics_nonneg CHECK (new_store_count >= 0 AND cumulative_store_count >= 0 AND active_campaign_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='일별 가게/캠페인 지표';

-- ----------------------------------------------------------------------------
-- event_log  |  분석 도메인 source of truth, append-only. 13개월 보존(파티셔닝 없이 야간 배치 DELETE)
--   소프트 참조: member_id -> member(회원), campaign_id -> campaign(캠페인), store_id -> store(가게), serve_id -> serve_log(광고 서빙)
-- ----------------------------------------------------------------------------
CREATE TABLE event_log (
    event_log_id  BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      VARCHAR(64)  NOT NULL                 COMMENT '중복 방지용 멱등키',
    event_type    VARCHAR(30)  NOT NULL,
    member_id     BIGINT       NOT NULL                 COMMENT '소프트 참조',
    campaign_id   BIGINT       NOT NULL                 COMMENT '소프트 참조',
    store_id      BIGINT       NOT NULL                 COMMENT '소프트 참조',
    serve_id      BINARY(16)                            COMMENT '[2026-10-01 변경] BIGINT → BINARY(16). point_ledger.serve_id와 동일 사유 — 광고서빙(박준서) 실제 구현이 UUID(BINARY(16))',
    wish_id       BIGINT,
    issue_id      BIGINT,
    occurred_at   DATETIME(6)  NOT NULL,
    received_at   DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (event_log_id),
    UNIQUE KEY uq_event_log_event_id (event_id),
    KEY idx_event_log_member_type_occurred (member_id, event_type, occurred_at),
    KEY idx_event_log_occurred (occurred_at),

    CONSTRAINT ck_event_log_event_type CHECK (event_type IN ('impression', 'wish', 'pass', 'issue_open', 'issue', 'redeem', 'expire', 'map_view', 'wishlist_view', 'notification_sent', 'poster_open')),
    CONSTRAINT ck_event_log_issue_id CHECK ((event_type IN ('issue_open', 'issue') AND issue_id IS NOT NULL) OR (event_type NOT IN ('issue_open', 'issue') AND issue_id IS NULL)),
    CONSTRAINT ck_event_log_received_after_occurred CHECK (received_at >= occurred_at),
    CONSTRAINT ck_event_log_wish_id CHECK ((event_type = 'wish' AND wish_id IS NOT NULL) OR event_type = 'wishlist_view' OR (event_type NOT IN ('wish', 'wishlist_view') AND wish_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='분석 도메인 source of truth, append-only. 13개월 보존(파티셔닝 없이 야간 배치 DELETE)';

-- ############################################################################
-- 12. 운영 (ops)    담당 최재웅
-- ############################################################################

-- ----------------------------------------------------------------------------
-- platform_setting
--   소프트 참조: updated_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE platform_setting (
    platform_setting_id  BIGINT        NOT NULL AUTO_INCREMENT,
    setting_key          VARCHAR(50)   NOT NULL                 COMMENT 'IMPRESSION_VALID_SECONDS, SLOT_RATIO_ALLOCATION, SLOT_RATIO_RELEVANCE, RELEVANCE_WEIGHT',
    setting_value        VARCHAR(500)  NOT NULL                 COMMENT '단일 값 또는 콤마 구분 목록',
    description          VARCHAR(200),
    updated_by           BIGINT                                 COMMENT '관리자 ID',
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,

    PRIMARY KEY (platform_setting_id),
    UNIQUE KEY UQ_PLATFORM_SETTING_SETTING_KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- platform_setting_history
--   소프트 참조: changed_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE platform_setting_history (
    platform_setting_history_id  BIGINT        NOT NULL AUTO_INCREMENT,
    setting_key                  VARCHAR(50)   NOT NULL,
    before_value                 VARCHAR(500)                           COMMENT '최초 등록 시 NULL',
    after_value                  VARCHAR(500)  NOT NULL,
    changed_by                   BIGINT                                 COMMENT '관리자 ID',
    changed_at                   DATETIME(6)   NOT NULL,
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,

    PRIMARY KEY (platform_setting_history_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- batch_execution_log
-- ----------------------------------------------------------------------------
CREATE TABLE batch_execution_log (
    batch_execution_log_id  BIGINT        NOT NULL AUTO_INCREMENT,
    job_name                VARCHAR(50)   NOT NULL                 COMMENT '실행 대상 작업 식별자',
    business_date           DATE          NOT NULL                 COMMENT '처리 대상 날짜(실행 시각 아님)',
    status                  VARCHAR(20)   NOT NULL                 COMMENT 'RUNNING/SUCCESS/FAILED',
    started_at              DATETIME(6)   NOT NULL,
    finished_at             DATETIME(6)                            COMMENT '미완료 시 NULL',
    failure_reason          VARCHAR(500)                           COMMENT 'FAILED일 때만',
    processed_count         INT,
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,

    PRIMARY KEY (batch_execution_log_id),
    UNIQUE KEY UQ_BATCH_EXECUTION_LOG_JOB_NAME_BUSINESS_DATE (job_name, business_date),

    CONSTRAINT CHK_BATCH_FAILURE_REASON CHECK ((status = 'FAILED' AND failure_reason IS NOT NULL) OR (status <> 'FAILED' AND failure_reason IS NULL)),
    CONSTRAINT CHK_BATCH_FINISHED CHECK ((status = 'RUNNING' AND finished_at IS NULL) OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= started_at)),
    CONSTRAINT CHK_BATCH_STATUS CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

