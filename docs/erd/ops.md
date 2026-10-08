# ops ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 4개.

```mermaid
erDiagram
    audit_log {
        bigint audit_log_id PK "감사 로그 PK"
        bigint admin_id "행위 관리자 ID. 소프트 참조(관리자 도메인)"
        varchar(50) action "관리자 수행 행위"
        varchar(100) target "행위 대상"
        text detail "상세 내용"
        datetime(6) created_at
        datetime(6) updated_at
    }
    batch_execution_log {
        bigint batch_execution_log_id PK
        varchar(50) job_name "실행 대상 작업 식별자. 00:00 묶음 점유는 DAILY_0000, 캠페인 단위 작업은 작업명:캠페인 id"
        date business_date "처리 대상 날짜(실행 시각 아님)"
        varchar(20) status "RUNNING/SUCCESS/FAILED"
        datetime(6) finished_at "미완료 시 NULL"
        varchar(500) failure_reason "FAILED일 때만"
        int processed_count "nullable"
        varchar(100) owner_id "[2026-10-05 신규] 실행 중인 배치 서버 식별자(호스트명). 이어받으면 새 서버로 바뀐다"
        int retry_count "일시적 오류로 자동 재실행한 횟수(최대 2). 수동 재실행 시 0으로 되돌린다"
        varchar(500) retry_reason "마지막 자동 재실행 사유. 재실행이 없으면 NULL"
        datetime(6) created_at
        datetime(6) updated_at "RUNNING 동안 30초마다 갱신하는 생존 신호. 2분 넘게 갱신이 없으면 다른 서버가 이어받는다"
    }
    platform_setting {
        bigint platform_setting_id PK
        varchar(50) setting_key UK "IMPRESSION_VALID_SECONDS, SLOT_RATIO_ALLOCATION, SLOT_RATIO_RELEVANCE, RELEVANCE_WEIGHT"
        varchar(500) setting_value "단일 값 또는 콤마 구분 목록"
        varchar(200) description "nullable"
        bigint updated_by "관리자 ID"
        datetime(6) created_at
        datetime(6) updated_at
    }
    platform_setting_history {
        bigint platform_setting_history_id PK
        varchar(50) setting_key
        varchar(500) before_value "최초 등록 시 NULL"
        varchar(500) after_value
        bigint changed_by "관리자 ID"
        datetime(6) created_at
        datetime(6) updated_at
    }
```
