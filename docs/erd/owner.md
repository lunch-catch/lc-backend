# owner ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 1개.

```mermaid
erDiagram
    owner {
        bigint owner_id PK "점주 PK"
        varchar(255) email UK "점주 이메일. UNIQUE 제약 조건 적용"
        varchar(255) password_hash "BCrypt 해싱한 점주 비밀번호"
        varchar(20) role "점주 권한. 서버에서 자동으로 OWNER로 설정"
        varchar(20) status "점주 상태(ONBOARDING, ACTIVE, SUSPENDED, WITHDRAWN)"
        tinyint(1) tutorial_viewed "최초 로그인 튜토리얼 확인 여부"
        datetime(6) suspended_at "정지 시각"
        varchar(500) suspension_reason "정지 사유"
        datetime(6) withdrawn_at "탈퇴 시각"
        char(64) refresh_token_hash "Refresh Token SHA-256 해시. 관계형 DB 백업용"
        datetime(6) refresh_token_expires_at "Refresh Token 만료 시각"
        datetime(6) last_login_at "최근 로그인 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
```
