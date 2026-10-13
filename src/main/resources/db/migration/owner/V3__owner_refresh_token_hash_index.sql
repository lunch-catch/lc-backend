-- Redis 장애 시, DB의 Refresh Token 해시로 점주를 조회한다.
CREATE INDEX idx_owner_refresh_token_hash ON owner (refresh_token_hash);
