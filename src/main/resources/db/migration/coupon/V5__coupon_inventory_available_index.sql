-- 발급 요청은 이벤트의 미발급 재고를 순서대로 조회하므로 이미 발급한 재고를 반복해서 훑지 않게 한다.
ALTER TABLE coupon_inventory
    ADD KEY idx_coupon_inventory_event_status_sequence (coupon_event_id, status, sequence_no);
