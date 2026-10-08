# notification ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 5개.

```mermaid
erDiagram
    notification {
        bigint notification_id PK "알림 PK"
        bigint member_id "알림 대상 사용자 ID"
        bigint campaign_id "알림 대상 캠페인 ID"
        varchar(50) notification_type "알림 유형(CAMPAIGN_OPEN_REMINDER)"
        varchar(100) title "알림 제목"
        varchar(500) content "알림 내용"
        varchar(500) target_url "알림 클릭 시 이동할 프론트 페이지 경로"
        date business_date "알림 대상 날짜. 중복 발송 판단에 사용"
        datetime(6) read_at "알림 읽은 시각. NULL이면 안읽음"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    notification_delivery {
        bigint notification_delivery_id PK "알림 발송 정보 PK"
        bigint notification_id "알림 PK"
        bigint notification_device_id "알림 대상 기기 PK"
        varchar(20) status "발송 상태(PENDING, PROCESSING, SENT, RETRY, FAILED, EXPIRED)"
        int attempt_count "FCM 실제 발송 시도 횟수. 최초 발송을 포함하며 재시도는 최대 2회"
        datetime(6) next_attempt_at "다음 재시도 예정 시각"
        datetime(6) last_attempt_at "마지막 FCM 발송 시도 시각"
        datetime(6) sent_at "FCM 발송 요청 성공 시각"
        varchar(100) last_error_code "마지막 발송 실패 오류 코드"
        varchar(500) last_error_message "마지막 발송 실패 오류 내용"
        varchar(255) provider_message_id "FCM 발송 성공 시 반환된 메시지 식별자"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    notification_device {
        bigint notification_device_id PK "알림 대상 기기 PK"
        bigint member_id "Push 등록 정보를 소유한 사용자 ID"
        varchar(255) fid UK "Firebase Installation ID(FID)"
        datetime(6) last_synced_at "FID가 마지막으로 서버에 등록 또는 갱신된 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    notification_outbox {
        bigint notification_outbox_id PK "알림 Outbox PK"
        bigint notification_delivery_id UK "발송할 알림 발송 정보 ID"
        varchar(50) event_type "Outbox 이벤트 유형(NOTIFICATION_SEND)"
        varchar(20) status "Outbox 처리 상태(PENDING, PROCESSING, RETRY, PROCESSED, FAILED)"
        int attempt_count "Outbox 처리 시도 횟수"
        datetime(6) next_attempt_at "다음 처리 예정 시각"
        varchar(100) last_error_code "마지막 처리 오류 코드"
        datetime(6) processed_at "Outbox 처리 완료 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    notification_sent {
        bigint notification_sent_id PK "알림 발송 성공 이벤트 PK"
        bigint notification_delivery_id UK "발송 성공한 알림 발송 정보 ID"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at
    }
    notification_device ||--o{ notification_delivery : "notification_device_id"
    notification ||--o{ notification_delivery : "notification_id"
    notification_delivery ||--o{ notification_outbox : "notification_delivery_id"
    notification_delivery ||--o{ notification_sent : "notification_delivery_id"
```
