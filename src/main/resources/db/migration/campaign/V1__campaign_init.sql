-- campaign 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 5장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

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
    daily_budget          BIGINT        NOT NULL                  COMMENT '포인트 단위',
    start_date            DATE          NOT NULL,
    end_date              DATE          NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    active_store_marker   BIGINT        GENERATED ALWAYS AS ((case when (`status` = 'ACTIVE') then `store_id` else NULL end)) STORED  COMMENT '47행 가게당 활성 캠페인 1건 UNIQUE 제약용. ACTIVE일 때만 store_id, 그 외 NULL',

    PRIMARY KEY (campaign_id),
    UNIQUE KEY uk_campaign_active_store (active_store_marker),

    CONSTRAINT chk_campaign_discount_target CHECK ((discount_target_type = 'ALL' AND target_menu_id IS NULL) OR (discount_target_type = 'MENU' AND target_menu_id IS NOT NULL)),
    CONSTRAINT chk_campaign_discount_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT chk_campaign_discount_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    CONSTRAINT chk_campaign_paused_reason CHECK ((status = 'PAUSED' AND paused_reason IN ('ADMIN', 'OWNER', 'NO_POINTS')) OR (status <> 'PAUSED' AND paused_reason IS NULL)),
    CONSTRAINT chk_campaign_period CHECK (end_date >= start_date),
    CONSTRAINT chk_campaign_quantity_budget CHECK (issue_quantity > 0 AND daily_budget > 0),
    CONSTRAINT chk_campaign_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_campaign_target_age_groups CHECK (REGEXP_LIKE(target_age_groups,'^(AGE_20S|AGE_30S|AGE_40S|AGE_50_PLUS)(,(AGE_20S|AGE_30S|AGE_40S|AGE_50_PLUS))*$')),
    CONSTRAINT chk_campaign_target_gender CHECK (target_gender IN ('ALL', 'MALE', 'FEMALE')),
    CONSTRAINT chk_campaign_target_radius CHECK (target_radius IN (500, 1000, 2000, 3000)),
    CONSTRAINT chk_campaign_usable_time CHECK (usable_end_time > usable_start_time)
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
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,

    PRIMARY KEY (campaign_status_log_id),
    KEY fk_campaign_status_log_campaign (campaign_id),

    CONSTRAINT fk_campaign_status_log_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),

    CONSTRAINT chk_campaign_status_log_actor CHECK (actor_type IN ('ADMIN', 'OWNER', 'SYSTEM')),
    CONSTRAINT chk_campaign_status_log_status CHECK (from_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED') AND to_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED') AND from_status <> to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- template  |  [2026-10-04] 포스터 도메인에서 캠페인 도메인으로 이동
--   소프트 참조: activated_by -> admin(관리자)
-- ----------------------------------------------------------------------------
CREATE TABLE template (
    template_id       BIGINT        NOT NULL AUTO_INCREMENT,
    name              VARCHAR(100)  NOT NULL,
    status            VARCHAR(20)   NOT NULL                 COMMENT 'DRAFT(임시저장중)/PUBLISHED(게시됨)',
    html_content      TEXT                                   COMMENT '게시 시점에 TemplateVersion에서 값 복사(FK 아님), 첫 게시 전까지 NULL',
    active         BOOLEAN       NOT NULL,
    published_at      DATETIME(6),
    activated_by      BIGINT                                 COMMENT '계정 도메인 - 관리자 테이블 참조(활성화 관리)',
    activated_at      DATETIME(6),
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,

    PRIMARY KEY (template_id),

    CONSTRAINT chk_template_status CHECK ((status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- poster  |  [2026-10-04] 포스터 도메인이 캠페인 도메인에 합쳐져 campaign 참조가 실제 FK가 됐다
-- ----------------------------------------------------------------------------
CREATE TABLE poster (
    poster_id     BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id   BIGINT        NOT NULL                 COMMENT '캠페인 테이블 참조. UNIQUE',
    template_id   BIGINT        NOT NULL                 COMMENT '템플릿 테이블 참조',
    title         VARCHAR(100)  NOT NULL                 COMMENT '목록 검색과 정렬용',
    slot_values   JSON          NOT NULL                 COMMENT '슬롯별 값 저장, {슬롯키: {type, value}} 형태',
    html_content  TEXT          NOT NULL                 COMMENT 'slot_values를 렌더링한 최종 HTML',
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    poster_image  VARCHAR(255),

    PRIMARY KEY (poster_id),
    UNIQUE KEY uk_poster_campaign_id (campaign_id),
    KEY fk_poster_template (template_id),

    CONSTRAINT fk_poster_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),
    CONSTRAINT fk_poster_template FOREIGN KEY (template_id) REFERENCES template (template_id)
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
    UNIQUE KEY uk_template_version_template_id_version_number (template_id, version_number),

    CONSTRAINT fk_template_version_template FOREIGN KEY (template_id) REFERENCES template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- poster_moderation_result
-- ----------------------------------------------------------------------------
CREATE TABLE poster_moderation_result (
    poster_moderation_result_id  BIGINT        NOT NULL AUTO_INCREMENT,
    poster_id                    BIGINT        NOT NULL                 COMMENT '포스터 테이블 참조',
    result                       VARCHAR(10)   NOT NULL                 COMMENT 'PASS/FAIL',
    reason                       VARCHAR(200),
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,

    PRIMARY KEY (poster_moderation_result_id),
    KEY fk_poster_moderation_result_poster (poster_id),

    CONSTRAINT fk_poster_moderation_result_poster FOREIGN KEY (poster_id) REFERENCES poster (poster_id),

    CONSTRAINT chk_poster_moderation_result CHECK (result = 'PASS' OR (result = 'FAIL' AND reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_audience  |  가게별 반경 내 대상 인원(45행 추천 하루 예산과 예상 노출 범위의 입력)
--   광고 서빙이 00:00 (10)단계에서 세어 StoreAudienceUpdater로 넘기고, 캠페인이 칸마다 덮어쓴다
--   한 칸 = 점주 반경 4단계 x 성별 x 연령대. 최근 7일 점심 접속 사용자 중복 제거 인원
--   (10)단계가 실패하면 전날 값이 그대로 남아 추천에 쓰인다
--   소프트 참조: store_id -> store(가게)
-- ----------------------------------------------------------------------------
CREATE TABLE store_audience (
    store_audience_id  BIGINT       NOT NULL AUTO_INCREMENT,
    store_id           BIGINT       NOT NULL,
    target_radius      INT          NOT NULL                 COMMENT '단위: 미터. 500/1000/2000/3000. campaign.target_radius와 같은 값',
    gender             VARCHAR(10)  NOT NULL                 COMMENT 'MALE/FEMALE/OTHER. member_profile.gender와 같은 값',
    age_group          VARCHAR(10)  NOT NULL                 COMMENT 'AGE_20S/AGE_30S/AGE_40S/AGE_50_PLUS. member_profile.age_group와 같은 값',
    member_count       INT          NOT NULL                 COMMENT '최근 7일 점심 접속 사용자 수(중복 제거)',
    business_date      DATE         NOT NULL                 COMMENT '세어 넘긴 영업일(00:00 (10)단계)',
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (store_audience_id),
    UNIQUE KEY uk_store_audience_cell (store_id, target_radius, gender, age_group),

    CONSTRAINT chk_store_audience_radius CHECK (target_radius IN (500, 1000, 2000, 3000)),
    CONSTRAINT chk_store_audience_gender CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT chk_store_audience_age_group CHECK (age_group IN ('AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS')),
    CONSTRAINT chk_store_audience_count CHECK (member_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='가게별 반경 내 대상 인원. 45행 추천 입력';
