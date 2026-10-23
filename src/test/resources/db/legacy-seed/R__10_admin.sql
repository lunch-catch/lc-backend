/*
 * 최고 관리자다.
 * 다른 관리자는 이 계정으로 로그인해 POST /v1/admin/admins 로 발급한다.
 * 이미 같은 login_id 가 있으면 건드리지 않는다.
 * 비밀번호를 바꾼 계정을 시드가 되돌리지 않게 한다.
 */
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, created_at, updated_at)
VALUES (9001, 'superadmin', '${seedPasswordHash}', '최고관리자', 'SUPER_ADMIN', 'ACTIVE', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE admin_id = admin_id;
