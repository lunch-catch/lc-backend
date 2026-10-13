-- 기존 이미지 데이터가 없는 환경에서 이미지 URL을 저장할 컬럼으로 변경한다.
ALTER TABLE coupon_event
    CHANGE COLUMN store_image_object_key store_image_url VARCHAR(512) NULL;
