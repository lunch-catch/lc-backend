-- 관리자 계정이 없는 세션이 남지 않도록 같은 도메인 안의 참조를 보장한다.
ALTER TABLE admin_login_session
    ADD CONSTRAINT fk_admin_login_session_admin
    FOREIGN KEY (admin_id) REFERENCES admin (admin_id);
