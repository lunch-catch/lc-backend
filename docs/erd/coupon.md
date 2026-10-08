# coupon ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 5개.

```mermaid
erDiagram
    coupon_daily_limit {
        bigint coupon_daily_limit_id PK
        bigint member_id
        int issued_count
        date business_date
        datetime(6) created_at
        datetime(6) updated_at
    }
    coupon_event {
        bigint coupon_event_id PK
        bigint campaign_id
        date business_date
        int issue_quantity
        datetime(6) usable_start_at
        datetime(6) usable_end_at
        varchar(20) status "SCHEDULED/ACTIVE/PAUSED/ENDED. [2026-10-04] 캠페인이 발행하는 CampaignStatusChangedEvent, CampaignEndedEvent로만 바뀐다"
        datetime(6) status_changed_at "마지막으로 반영한 캠페인 전이 시각. 이벤트의 changedAt이 이보다 늦을 때만 status를 바꾼다. 회차를 만들 때 캠페인의 status_changed_at으로 채운다"
        datetime(6) created_at
        datetime(6) updated_at
    }
    coupon_inventory {
        bigint coupon_inventory_id PK
        bigint coupon_event_id
        int sequence_no
        binary(16) coupon_code UK
        varchar(20) status "AVAILABLE/ISSUED"
        datetime(6) usable_start_at
        datetime(6) usable_end_at
        datetime(6) created_at
        datetime(6) updated_at
    }
    coupon_usage_history {
        bigint coupon_usage_history_id PK
        bigint member_coupon_id
        bigint store_id
        int discount_amount
        datetime(6) created_at
        datetime(6) updated_at
    }
    member_coupon {
        bigint member_coupon_id PK
        bigint coupon_event_id
        bigint member_id
        binary(16) coupon_code UK
        int qr_version
        binary(16) qr_token "nullable"
        datetime(6) qr_expires_at "nullable"
        varchar(20) status "ISSUED/USED/EXPIRED/REMOVED"
        datetime(6) usable_start_at
        datetime(6) usable_end_at
        datetime(6) created_at
        datetime(6) updated_at
    }
    coupon_event ||--o{ coupon_inventory : "coupon_event_id"
    member_coupon ||--o{ coupon_usage_history : "member_coupon_id"
    coupon_event ||--o{ member_coupon : "coupon_event_id"
```
