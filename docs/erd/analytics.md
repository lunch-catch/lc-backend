# analytics ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 13개.

```mermaid
erDiagram
    campaign_daily_impression_reason_report {
        bigint campaign_daily_impression_reason_report_id PK "캠페인 일별 노출 사유 집계 PK"
        bigint campaign_daily_report_id "캠페인 일별 리포트 ID"
        varchar(20) impression_type "노출 구분(UNBILLED, INVALID)"
        varchar(50) reason_code "미과금 사유(NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_SOLD_OUT) 또는 무효 사유(EXPIRED_OR_UNKNOWN, NOT_OWNER)"
        bigint impression_count "해당 사유의 노출 수"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    campaign_daily_report {
        bigint campaign_daily_report_id PK "캠페인 일별 집계 PK"
        bigint campaign_id "캠페인 ID. 소프트 참조(캠페인 도메인)"
        date business_date "집계 날짜"
        bigint daily_budget "해당 날짜의 하루 예산"
        bigint charged_amount "유효 노출로 실제 차감된 포인트"
        bigint refunded_amount "무효 처리로 환급된 포인트"
        bigint spent_amount "실제 소진 포인트"
        decimal(9_6) budget_spend_rate "예산 소진율"
        bigint impression_unit_cost "적용된 노출 단가"
        bigint valid_impression_count "유효 노출 수"
        bigint billable_impression_count "과금된 유효 노출 수"
        bigint not_billed_impression_count "미과금 유효 노출 수"
        bigint invalid_impression_count "무효 노출 수"
        bigint wish_count "찜 수"
        bigint issued_count "쿠폰 발급 수"
        bigint redeem_count "쿠폰 사용 수"
        varchar(20) report_status "리포트 상태(AGGREGATING, FINALIZED)"
        datetime(6) finalized_at "일별 리포트 확정 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    campaign_daily_report_history {
        bigint campaign_daily_report_history_id PK "캠페인 일별 리포트 변경 이력 ID"
        bigint campaign_daily_report_id "캠페인 일별 리포트 ID"
        int revision_no "변경 차수"
        varchar(255) change_reason "변경 사유"
        json before_data "변경 전 데이터"
        json after_data "변경 후 데이터"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at
    }
    campaign_daily_slot_report {
        bigint campaign_daily_slot_report_id PK "캠페인 일별 슬롯 집계 PK"
        bigint campaign_daily_report_id "캠페인 일별 리포트 ID"
        varchar(20) slot_type "슬롯 유형(ALLOCATION, RELEVANCE)"
        bigint impression_count "해당 슬롯 유형의 노출 수"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    campaign_hourly_report {
        bigint campaign_hourly_report_id PK "캠페인 시간대별 리포트 PK"
        bigint campaign_daily_report_id "캠페인 일별 리포트 ID"
        tinyint report_hour "집계 시간(10, 11, 12)"
        bigint impression_count "해당 시간대 노출 수"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    daily_campaign_not_billed_analytics {
        date business_date PK
        bigint campaign_id PK "소프트 참조"
        varchar(30) not_billed_reason PK
        bigint not_billed_count
        datetime(6) created_at
        datetime(6) updated_at
    }
    daily_invalid_analytics {
        bigint daily_invalid_analytics_id PK
        date business_date
        varchar(50) reason_code
        bigint campaign_id "캠페인별 분해 시에만 값 존재. NULL이면 플랫폼 전체 합계 행"
        bigint member_id "[2026-10-01 신규] 사용자별 분해 시에만 값 존재(A-09 memberId 필터). NULL이면 해당 축 미분해"
        bigint invalid_count
        tinyint(1) concentration_suspected
        datetime(6) created_at
        datetime(6) updated_at
    }
    daily_member_analytics {
        date business_date PK
        int new_member_count
        int cumulative_member_count
        int active_member_count
        int withdrawn_count
        int suspended_count
        datetime(6) created_at
        datetime(6) updated_at
    }
    daily_owner_spend {
        date business_date PK
        bigint owner_id PK "소프트 참조"
        bigint spent_amount "DEDUCT 합의 절댓값"
        datetime(6) created_at
        datetime(6) updated_at
    }
    daily_segment_analytics {
        date business_date PK
        varchar(50) category_code PK
        varchar(50) region PK
        bigint impression_count
        bigint wish_count
        bigint issued_count
        bigint redeem_count
        bigint spent_amount
        datetime(6) created_at
        datetime(6) updated_at
    }
    daily_settlement_analytics {
        date business_date PK
        bigint charge_amount
        bigint deduct_amount
        bigint reserved_amount "[2026-10-01 신규] 그날 예약액(RESERVE 합)"
        bigint released_amount "[2026-10-01 신규] 그날 예약 중 쓰지 않아 해제한 금액(RELEASE 합)"
        bigint refund_amount
        bigint adjust_amount "[2026-10-01 신규] 관리자 조정액(ADJUST 합, ±). CHECK에서 제외(음수 허용)"
        bigint unspent_balance "미소진 잔액 합계 = 점주 잔액 합 + 예약 중 포인트(그날 예약액 − 당일 소진)"
        datetime(6) created_at
        datetime(6) updated_at
    }
    daily_store_analytics {
        date business_date PK
        int new_store_count
        int cumulative_store_count
        int active_campaign_count
        datetime(6) created_at
        datetime(6) updated_at
    }
    event_log {
        bigint event_log_id PK
        varchar(64) event_id UK "중복 방지용 멱등키"
        varchar(30) event_type
        bigint member_id "소프트 참조"
        bigint campaign_id "소프트 참조"
        bigint store_id "소프트 참조"
        binary(16) serve_id "[2026-10-01 변경] BIGINT -> BINARY(16). point_ledger.serve_id와 같은 사유다. 광고서빙(박준서) 실제 구현이 UUID(BINARY(16))"
        bigint wish_id "nullable"
        bigint issue_id "nullable"
        datetime(6) occurred_at
        datetime(6) created_at
        datetime(6) updated_at
    }
    campaign_daily_report ||--o{ campaign_daily_impression_reason_report : "campaign_daily_report_id"
    campaign_daily_report ||--o{ campaign_daily_report_history : "campaign_daily_report_id"
    campaign_daily_report ||--o{ campaign_daily_slot_report : "campaign_daily_report_id"
    campaign_daily_report ||--o{ campaign_hourly_report : "campaign_daily_report_id"
```
