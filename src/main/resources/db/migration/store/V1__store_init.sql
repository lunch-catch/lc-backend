-- store 도메인 초기 스키마. 드라이브 ERD 폴더 전체_flyway.sql 의 4장과 같다
-- 공통 규칙(소프트 참조, CHECK, 시각, created_at/updated_at)은 같은 폴더의 README.md 를 따른다
-- 대상: MySQL 8.4, InnoDB, utf8mb4_0900_ai_ci

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
    category_code             VARCHAR(30)    NOT NULL                 COMMENT '업종 코드(KOREAN, CHINESE, JAPANESE, WESTERN, BUNSIK, ASIAN, FAST_FOOD, CAFE_DESSERT, OTHER)',
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
    UNIQUE KEY uk_store_owner (owner_id),

    CONSTRAINT chk_store_category_code CHECK (category_code IN ('KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN', 'BUNSIK', 'ASIAN', 'FAST_FOOD', 'CAFE_DESSERT', 'OTHER')),
    CONSTRAINT chk_store_business_registration_number CHECK (business_registration_number IS NULL OR REGEXP_LIKE(business_registration_number,'^[0-9]{10}$')),
    CONSTRAINT chk_store_business_verification_status CHECK (business_verification_status IS NULL OR business_verification_status IN ('VERIFIED', 'FAILED')),
    CONSTRAINT chk_store_business_verified CHECK (business_verification_status <> 'VERIFIED' OR business_verified_at IS NOT NULL),
    CONSTRAINT chk_store_finalized CHECK ((finalized = false AND finalized_at IS NULL) OR (finalized = true AND finalized_at IS NOT NULL AND business_verification_status = 'VERIFIED' AND latitude IS NOT NULL AND longitude IS NOT NULL)),
    CONSTRAINT chk_store_latitude CHECK (latitude IS NULL OR (latitude BETWEEN -(90) AND 90)),
    CONSTRAINT chk_store_longitude CHECK (longitude IS NULL OR (longitude BETWEEN -(180) AND 180))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_business_hour
-- ----------------------------------------------------------------------------
CREATE TABLE store_business_hour (
    store_business_hour_id  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '영업시간 PK',
    store_id          BIGINT       NOT NULL                 COMMENT '가게 PK',
    day_of_week       VARCHAR(10)  NOT NULL                 COMMENT '영업 요일(MONDAY ~ SUNDAY)',
    open_time         TIME                                  COMMENT '오픈 시간',
    close_time        TIME                                  COMMENT '마감 시간',
    closed         BOOLEAN      NOT NULL DEFAULT 0       COMMENT '휴무 여부',
    break_start_time  TIME                                  COMMENT '브레이크 시작',
    break_end_time    TIME                                  COMMENT '브레이크 타임 종료',
    last_order_time   TIME                                  COMMENT '라스트오더 시간',
    created_at        DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at        DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (store_business_hour_id),
    UNIQUE KEY uk_store_business_hour_day (store_id, day_of_week),

    CONSTRAINT fk_store_business_hour_store FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT chk_store_business_hour_break CHECK ((break_start_time IS NULL AND break_end_time IS NULL) OR (break_start_time IS NOT NULL AND break_end_time IS NOT NULL)),
    CONSTRAINT chk_store_business_hour_closed CHECK ((closed = true AND open_time IS NULL AND close_time IS NULL) OR (closed = false AND open_time IS NOT NULL AND close_time IS NOT NULL)),
    CONSTRAINT chk_store_business_hour_day CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'))
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
    UNIQUE KEY uk_store_image_object_key (object_key),
    UNIQUE KEY uk_store_image_upload_id (upload_id),
    UNIQUE KEY uk_store_image_single_logo (logo_store_id),
    UNIQUE KEY uk_store_image_interior_order (store_id, image_type, sort_order),

    CONSTRAINT fk_store_image_store FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT chk_store_image_content_type CHECK (content_type IS NULL OR content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT chk_store_image_file_size CHECK (file_size IS NULL OR (file_size > 0 AND file_size <= 10485760)),
    CONSTRAINT chk_store_image_sort_order CHECK ((image_type = 'LOGO' AND sort_order IS NULL) OR (image_type = 'INTERIOR' AND (sort_order BETWEEN 1 AND 3))),
    CONSTRAINT chk_store_image_type CHECK (image_type IN ('LOGO', 'INTERIOR')),
    CONSTRAINT chk_store_image_upload_status CHECK (upload_status IN ('PENDING', 'UPLOADED', 'VERIFIED')),
    CONSTRAINT chk_store_image_verified CHECK (upload_status <> 'VERIFIED' OR verified_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_menu
-- ----------------------------------------------------------------------------
CREATE TABLE store_menu (
    store_menu_id      BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '대표 메뉴 PK',
    store_id     BIGINT        NOT NULL                 COMMENT '가게 PK',
    name         VARCHAR(100)  NOT NULL                 COMMENT '메뉴명',
    price        BIGINT        NOT NULL                 COMMENT '메뉴 가격(원 단위)',
    description  VARCHAR(500)                           COMMENT '메뉴 상세설명(선택. NULL 가능)',
    sort_order   TINYINT       NOT NULL                 COMMENT '대표 메뉴 순서(1~3)',
    created_at   DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at   DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (store_menu_id),
    UNIQUE KEY uk_store_menu_order (store_id, sort_order),

    CONSTRAINT fk_store_menu_store FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT chk_store_menu_price CHECK (price >= 0),
    CONSTRAINT chk_store_menu_sort_order CHECK (sort_order BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- store_menu_image
-- ----------------------------------------------------------------------------
CREATE TABLE store_menu_image (
    store_menu_image_id      BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '대표 메뉴 이미지 PK',
    store_menu_id            BIGINT        NOT NULL                 COMMENT '가게 대표 메뉴 PK',
    upload_id          CHAR(36)                               COMMENT '객체 스토리지 업로드 요청 식별자',
    object_key         VARCHAR(255)  NOT NULL                 COMMENT '객체 스토리지 Key',
    original_filename  VARCHAR(255)                           COMMENT '원본 파일명',
    content_type       VARCHAR(100)                           COMMENT '파일 MIME 타입(image/jpeg, image/png)',
    file_size          BIGINT                                 COMMENT '파일 크기(byte)',
    upload_status      VARCHAR(20)   NOT NULL                 COMMENT '이미지 업로드 상태(PENDING, UPLOADED, VERIFIED)',
    verified_at        DATETIME(6)                            COMMENT '이미지 객체 존재 확인 완료 시각',
    created_at         DATETIME(6)   NOT NULL                 COMMENT '생성일시',
    updated_at         DATETIME(6)   NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (store_menu_image_id),
    UNIQUE KEY uk_store_menu_image_store_menu (store_menu_id),
    UNIQUE KEY uk_store_menu_image_object_key (object_key),
    UNIQUE KEY uk_store_menu_image_upload_id (upload_id),

    CONSTRAINT fk_store_menu_image_store_menu FOREIGN KEY (store_menu_id) REFERENCES store_menu (store_menu_id),

    CONSTRAINT chk_store_menu_image_content_type CHECK (content_type IS NULL OR content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT chk_store_menu_image_file_size CHECK (file_size IS NULL OR (file_size > 0 AND file_size <= 10485760)),
    CONSTRAINT chk_store_menu_image_upload_status CHECK (upload_status IN ('PENDING', 'UPLOADED', 'VERIFIED')),
    CONSTRAINT chk_store_menu_image_verified CHECK (upload_status <> 'VERIFIED' OR verified_at IS NOT NULL)
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
    UNIQUE KEY uk_store_campaign_summary_date_campaign (business_date, campaign_id),
    KEY fk_store_campaign_summary_store (store_id),

    CONSTRAINT fk_store_campaign_summary_store FOREIGN KEY (store_id) REFERENCES store (store_id),

    CONSTRAINT chk_store_campaign_summary_discount_target_type CHECK (discount_target_type IN ('ALL', 'MENU')),
    CONSTRAINT chk_store_campaign_summary_discount_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT chk_store_campaign_summary_status CHECK (campaign_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_store_campaign_summary_usable_time CHECK (usable_end_time > usable_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- owner_terms_agreement  |  점주 약관 동의(35행). [2026-10-04] 점주 도메인에서 가게 도메인으로 이동
--   소프트 참조: owner_id -> owner(점주)
-- ----------------------------------------------------------------------------
CREATE TABLE owner_terms_agreement (
    owner_terms_agreement_id   BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '점주 약관 동의 PK',
    owner_id       BIGINT       NOT NULL                 COMMENT '점주 ID. 소프트 참조(점주 도메인)',
    terms_type     VARCHAR(50)  NOT NULL                 COMMENT '약관 종류',
    terms_version  VARCHAR(30)  NOT NULL                 COMMENT '약관 버전',
    required       BOOLEAN      NOT NULL                 COMMENT '필수 약관 여부',
    agreed         BOOLEAN      NOT NULL                 COMMENT '동의 여부',
    withdrawn_at   DATETIME(6)                           COMMENT '선택 동의 철회 시각',
    created_at     DATETIME(6)  NOT NULL                 COMMENT '생성일시',
    updated_at     DATETIME(6)  NOT NULL                 COMMENT '수정일시',

    PRIMARY KEY (owner_terms_agreement_id),
    UNIQUE KEY uk_owner_terms_version (owner_id, terms_type, terms_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
