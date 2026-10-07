/*
 * 포스터 이미지를 URL 이 아니라 객체 스토리지 키로 저장하도록 poster_image 를 바꾼다.
 * 컬럼 이름을 image_object_key 로 바꾸고 NOT NULL 로 하며 키로 찾을 인덱스를 더한다.
 * URL 은 응답에서 cdn.base-url 과 키를 붙여 만든다.
 */
ALTER TABLE poster
    CHANGE COLUMN poster_image image_object_key VARCHAR(255) NOT NULL
    COMMENT '포스터 메뉴 이미지의 객체 스토리지 키. URL 은 응답에서 cdn.base-url 과 붙여 만든다',
    ADD KEY idx_poster_image_object_key (image_object_key);

