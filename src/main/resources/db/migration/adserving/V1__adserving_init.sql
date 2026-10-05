-- adserving 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 7장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

-- ############################################################################
-- 7. 광고 서빙 (adserving)    담당 박준서
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
    target_radius        SMALLINT       NOT NULL  COMMENT '노출 반경(미터) 500/1000/2000/3000. campaign.target_radius 복사',
    target_gender        VARCHAR(10)    NOT NULL  COMMENT 'Java enum TargetGender: ALL, MALE, FEMALE',
    target_age_groups    VARCHAR(50)    NOT NULL  COMMENT 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS 중 다중 선택, 콤마 구분. campaign.target_age_groups와 같은 타입과 형식',
    discount_target_type VARCHAR(20)    NOT NULL  COMMENT 'Java enum DiscountTarget: ALL, MENU',
    discount_type        VARCHAR(20)    NOT NULL  COMMENT 'Java enum DiscountType: PERCENT, AMOUNT',
    discount_value       INT            NOT NULL,
    usable_start_time    TIME           NOT NULL  COMMENT '캠페인별 사용 가능 시작',
    usable_end_time      TIME           NOT NULL  COMMENT '캠페인별 사용 가능 종료',
    issue_quantity       INT            NOT NULL  COMMENT '선착순 쿠폰 발급 수량(표시용)',
    campaign_created_at  DATETIME(6)    NOT NULL  COMMENT '배분 슬롯 동률 정렬 키',
    new_priority_until   DATE                     COMMENT '신규 우선 노출 마지막 날. NULL이면 대상 아님',
    campaign_state       VARCHAR(30)    NOT NULL  COMMENT 'Java enum CandidateCampaignState: SERVABLE, PAUSED, ENDED. 캠페인 통보로만 바뀜. 00:00 적재 시 ACTIVE -> SERVABLE, 예약 있는 PAUSED(OWNER, NO_POINTS) -> PAUSED',
    state_changed_at     DATETIME(6)              COMMENT '캠페인이 알려준 마지막 전이 시각. 적재, 적재 직후 재조회, 통보 모두 이 값보다 늦을 때만 갱신',
    sold_out_at          DATETIME(6)              COMMENT '쿠폰 소진 통보 시각. NULL이 아니면 그날 후보 제외',
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,

    PRIMARY KEY (business_date, campaign_id),
    KEY idx_candidate_geo (business_date, latitude, longitude),

    CONSTRAINT chk_candidate_age_groups CHECK (REGEXP_LIKE(target_age_groups,'^(AGE_20S|AGE_30S|AGE_40S|AGE_50_PLUS)(,(AGE_20S|AGE_30S|AGE_40S|AGE_50_PLUS))*$')),
    CONSTRAINT chk_candidate_discount_target CHECK (discount_target_type IN ('ALL', 'MENU')),
    CONSTRAINT chk_candidate_discount_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT chk_candidate_discount_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    CONSTRAINT chk_candidate_gender CHECK (target_gender IN ('ALL', 'MALE', 'FEMALE')),
    CONSTRAINT chk_candidate_radius CHECK (target_radius IN (500, 1000, 2000, 3000)),
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
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,

    PRIMARY KEY (business_date, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='(영업일, 사용자)당 그날 첫 피드 요청 위치 1건. INSERT IGNORE';

-- ----------------------------------------------------------------------------
-- impression_log  |  노출 이벤트. 13개월 보관. 중복 저장은 PK로 거부(INSERT IGNORE). 처음 저장된 유효 노출이면 같은 트랜잭션에서 정산의 노출 차감을 부른다(deduction_result, point_ledger가 함께 커밋). 무효면 invalid_reason 필수는 엔티티 생성 지점에서 지킨다. 이 컬럼들을 참조하는 CHECK를 두면 ENUM 값 추가가 테이블 복사가 되므로 두지 않는다
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
    lat_rounded     DECIMAL(6,3)              NOT NULL  COMMENT '소수점 3자리 반올림',
    lng_rounded     DECIMAL(7,3)              NOT NULL,
    validity        ENUM('VALID', 'INVALID')  NOT NULL,
    invalid_reason  ENUM('EXPIRED_OR_UNKNOWN', 'NOT_OWNER'),
    sold_out        BOOLEAN                   NOT NULL DEFAULT FALSE  COMMENT '쿠폰 소진 뒤 도착한 유효 노출(NOT_BILLED_SOLD_OUT). 차감을 부르지 않는다. 나머지 과금 판정은 정산의 deduction_result에 있다',
    viewed_ms       INT                                 COMMENT '참고 지표(선택 데이터)',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,

    PRIMARY KEY (serve_id, member_id),
    KEY idx_imp_campaign_date (campaign_id, business_date),
    KEY idx_imp_date_reason (business_date, invalid_reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='노출 이벤트. 13개월 보관. 중복 저장은 PK로 거부(INSERT IGNORE). 처음 저장된 유효 노출이면 같은 트랜잭션에서 정산의 노출 차감을 부른다(deduction_result, point_ledger가 함께 커밋). 무효면 invalid_reason 필수는 엔티티 생성 지점에서 지킨다. 이 컬럼들을 참조하는 CHECK를 두면 ENUM 값 추가가 테이블 복사가 되므로 두지 않는다';

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
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,

    PRIMARY KEY (wishlist_id),
    UNIQUE KEY uk_wishlist_member_campaign (member_id, campaign_id),
    KEY idx_wishlist_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='삭제와 캠페인 종료 시 행 삭제';
