-- ----------------------------------------------------------------------------
-- store_review  |  가게 리뷰
-- ----------------------------------------------------------------------------
CREATE TABLE store_review (
    store_review_id  BIGINT        NOT NULL AUTO_INCREMENT,
    store_id         BIGINT,
    member_id        BIGINT,
    rating           INT,
    content          VARCHAR(1000),
    reviewer_email   VARCHAR(255),
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,

    PRIMARY KEY (store_review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='가게 리뷰';

ALTER TABLE store_menu DROP COLUMN price;
