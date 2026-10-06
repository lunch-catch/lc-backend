-- ----------------------------------------------------------------------------
-- ad_candidate  |  찜 목록 표시용 가게 대표 이미지와 할인 메뉴명 (2026-10-06)
--   찜 목록은 그날 후보 스냅샷에서 표시 값을 채운다(75행, 96행)
--   00:00 (8)단계에서 가게 조회 기능으로 받아 복사한다
-- ----------------------------------------------------------------------------
ALTER TABLE ad_candidate
    ADD COLUMN store_image_url    VARCHAR(512)  COMMENT '가게 대표 이미지(LOGO) 주소. 없으면 NULL' AFTER store_name,
    ADD COLUMN discount_menu_name VARCHAR(100)  COMMENT '할인 대상 메뉴명. store_menu.name 복사. discount_target_type=MENU일 때만 값 존재' AFTER discount_value,
    ADD CONSTRAINT chk_candidate_discount_menu CHECK ((discount_target_type = 'ALL' AND discount_menu_name IS NULL) OR (discount_target_type = 'MENU' AND discount_menu_name IS NOT NULL));
