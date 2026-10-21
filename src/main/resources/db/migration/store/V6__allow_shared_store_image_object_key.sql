-- 여러 가게가 같은 이미지 객체를 참조할 수 있도록 객체 키의 유일성 제약을 제거한다.
ALTER TABLE store_image
    DROP INDEX uk_store_image_object_key;
