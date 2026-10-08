# admin ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 1개.

```mermaid
erDiagram
    admin {
        bigint admin_id PK "관리자 PK"
        varchar(50) login_id UK "관리자 로그인 ID"
        varchar(255) password_hash "BCrypt 비밀번호 해시"
        varchar(50) name "관리자 이름"
        varchar(30) role "관리자 권한(ADMIN, SUPER_ADMIN)"
        varchar(30) status "관리자 상태(ACTIVE, DELETED)"
        char(64) refresh_token_hash "Refresh Token SHA-256 해시"
        datetime(6) refresh_token_expires_at "Refresh Token 만료 시각"
        datetime(6) deleted_at "관리자 비활성화 시각"
        datetime(6) created_at
        datetime(6) updated_at
    }
```
