/*
 * 마지막으로 반영한 캠페인 전이 시각을 저장해 중복·과거 이벤트가 회차 상태를 덮지 않게 한다.
 * 기존 행은 전이 시각을 복원할 수 없어 NULL로 두고 처음 수신한 이벤트부터 기록한다.
 */
ALTER TABLE coupon_event
    ADD COLUMN status_changed_at DATETIME(6) NULL
        COMMENT '마지막으로 반영한 캠페인 이벤트의 changedAt. 아직 수신하지 않았으면 NULL' AFTER status;
