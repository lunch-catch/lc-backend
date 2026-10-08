# campaign ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 7개.

```mermaid
erDiagram
    campaign {
        bigint campaign_id PK
        bigint owner_id
        bigint store_id
        varchar(20) status "DRAFT/SCHEDULED/ACTIVE/PAUSED/ENDED"
        datetime(6) status_changed_at "현재 status로 바뀐 시각. ad_candidate.state_changed_at에 전달되는 값"
        varchar(20) paused_reason "ADMIN/OWNER/NO_POINTS"
        varchar(20) discount_target_type "ALL/MENU"
        varchar(20) discount_type "PERCENT/AMOUNT"
        int discount_value
        bigint target_menu_id "discount_target_type='MENU'일 때만 값 존재"
        int issue_quantity
        time usable_start_time
        time usable_end_time
        int min_order_amount "선택 입력"
        varchar(500) notice "선택입력"
        int target_radius "단위: 미터. 500/1000/2000/3000"
        varchar(10) target_gender "ALL/MALE/FEMALE"
        varchar(50) target_age_groups "AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS 중 다중 선택, 콤마 구분. 기본값은 전체"
        bigint daily_budget "포인트 단위"
        date start_date "nullable"
        date end_date "nullable"
        datetime(6) created_at
        datetime(6) updated_at
        bigint active_store_marker UK "47행 가게당 활성 캠페인 1건 UNIQUE 제약용. ACTIVE일 때만 store_id, 그 외 NULL"
    }
    campaign_status_log {
        bigint campaign_status_log_id PK
        bigint campaign_id "캠페인 테이블 참조"
        varchar(20) from_status
        varchar(20) to_status
        varchar(20) actor_type "ADMIN/OWNER/SYSTEM"
        varchar(200) reason "선택 입력"
        datetime(6) created_at
        datetime(6) updated_at
    }
    poster {
        bigint poster_id PK
        bigint campaign_id UK "캠페인 테이블 참조. UNIQUE"
        bigint template_id "템플릿 테이블 참조"
        varchar(100) title "목록 검색과 정렬용"
        json slot_values "슬롯별 값 저장, (슬롯키: (type, value)) 형태"
        text html_content "slot_values를 렌더링한 최종 HTML"
        datetime(6) created_at
        datetime(6) updated_at
        varchar(255) image_object_key "포스터 메뉴 이미지의 객체 스토리지 키. URL 은 응답에서 cdn.base-url 과 붙여 만든다"
    }
    poster_moderation_result {
        bigint poster_moderation_result_id PK
        bigint poster_id "포스터 테이블 참조"
        varchar(10) result "PASS/FAIL"
        varchar(200) reason "nullable"
        datetime(6) created_at
        datetime(6) updated_at
    }
    store_audience {
        bigint store_audience_id PK
        bigint store_id
        int target_radius "단위: 미터. 500/1000/2000/3000. campaign.target_radius와 같은 값"
        varchar(10) gender "MALE/FEMALE/OTHER. member_profile.gender와 같은 값"
        varchar(10) age_group "AGE_20S/AGE_30S/AGE_40S/AGE_50_PLUS. member_profile.age_group와 같은 값"
        int member_count "최근 7일 점심 접속 사용자 수(중복 제거)"
        date business_date "세어 넘긴 영업일(00:00 (10)단계)"
        datetime(6) created_at
        datetime(6) updated_at
    }
    template {
        bigint template_id PK
        varchar(100) name
        varchar(20) status "DRAFT(임시저장중)/PUBLISHED(게시됨)"
        text html_content "게시 시점에 TemplateVersion에서 값 복사(FK 아님), 첫 게시 전까지 NULL"
        tinyint(1) active
        datetime(6) published_at "nullable"
        bigint activated_by "계정 도메인 - 관리자 테이블 참조(활성화 관리)"
        datetime(6) activated_at "nullable"
        datetime(6) created_at
        datetime(6) updated_at
    }
    template_version {
        bigint template_version_id PK
        bigint template_id "템플릿 테이블 참조"
        int version_number
        text request_prompt "관리자 요청 문장"
        text html_content
        datetime(6) created_at
        datetime(6) updated_at
        datetime(6) deleted_at "nullable"
    }
    campaign ||--o{ campaign_status_log : "campaign_id"
    campaign ||--o{ poster : "campaign_id"
    template ||--o{ poster : "template_id"
    poster ||--o{ poster_moderation_result : "poster_id"
    template ||--o{ template_version : "template_id"
```
