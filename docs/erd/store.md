# store ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.

테이블 7개.

```mermaid
erDiagram
    owner_terms_agreement {
        bigint owner_terms_agreement_id PK "점주 약관 동의 PK"
        bigint owner_id "점주 ID. 소프트 참조(점주 도메인)"
        varchar(50) terms_type "약관 종류"
        varchar(30) terms_version "약관 버전"
        tinyint(1) required "필수 약관 여부"
        tinyint(1) agreed "동의 여부"
        datetime(6) withdrawn_at "선택 동의 철회 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store {
        bigint store_id PK "가게 PK"
        bigint owner_id UK "점주 PK. 점주 계정 1개당 가게 1개 등록"
        varchar(100) name "상호명"
        varchar(30) category_code "업종 코드(KOREAN, CHINESE, JAPANESE, WESTERN, BUNSIK, ASIAN, FAST_FOOD, CAFE_DESSERT, OTHER)"
        varchar(100) representative_name "대표자 성명"
        varchar(30) phone "카카오맵에서 가져온 전화번호"
        varchar(255) road_address "카카오맵에서 가져온 도로명 주소"
        decimal(10_7) latitude "카카오맵에서 가져온 위도"
        decimal(10_7) longitude "카카오맵에서 가져온 경도"
        varchar(50) kakao_place_id "카카오맵에서 가져온 카카오 장소 ID"
        varchar(10) business_registration_number "사업자등록번호(기호 제외 숫자 10자)"
        varchar(20) business_verification_status "사업자등록번호 Mock 검증 상태(VERIFIED, FAILED)"
        datetime(6) business_verified_at "사업자등록번호 Mock 검증 성공 시각"
        tinyint(1) finalized "가게 최종 등록 여부"
        datetime(6) finalized_at "가게 최종 등록 완료 시각(NULL 허용)"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store_business_hour {
        bigint store_business_hour_id PK "영업시간 PK"
        bigint store_id "가게 PK"
        varchar(10) day_of_week "영업 요일(MONDAY ~ SUNDAY)"
        time open_time "오픈 시간"
        time close_time "마감 시간"
        tinyint(1) closed "휴무 여부"
        time break_start_time "브레이크 시작"
        time break_end_time "브레이크 타임 종료"
        time last_order_time "라스트오더 시간"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store_campaign_summary {
        bigint store_campaign_summary_id PK "가게 캠페인 요약 PK"
        bigint store_id "가게 PK"
        bigint campaign_id "캠페인 ID"
        date business_date "캠페인 요약이 적용되는 날짜"
        varchar(20) campaign_status "캠페인 상태(ACTIVE, PAUSED, ENDED 등)"
        varchar(20) discount_target_type "할인 대상 유형(ALL: 전체, MENU: 특정 메뉴)"
        varchar(20) discount_type "할인 유형(PERCENT: 퍼센트, AMOUNT: 금액)"
        int discount_value "할인 값"
        time usable_start_time "캠페인별 사용 가능 시작 시간"
        time usable_end_time "캠페인별 사용 가능 종료 시간"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store_image {
        bigint store_image_id PK "가게 이미지 PK"
        bigint store_id "가게 PK"
        char(36) upload_id UK "객체 스토리지 업로드 요청 식별자"
        varchar(20) image_type "가게 이미지 타입(LOGO, INTERIOR)"
        varchar(255) object_key UK "객체 스토리지 Key"
        varchar(255) original_filename "원본 파일명"
        varchar(100) content_type "파일 MIME 타입(image/jpeg, image/png)"
        bigint file_size "파일 크기(byte)"
        int width "이미지 가로 픽셀"
        int height "이미지 세로 픽셀"
        tinyint sort_order "인테리어 이미지 표시 순서(1~3)"
        bigint logo_store_id UK "가게당 LOGO 1개 제한용 생성 컬럼"
        varchar(20) upload_status "이미지 업로드 상태(PENDING, UPLOADED, VERIFIED)"
        datetime(6) verified_at "이미지 객체 존재 확인 완료 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store_menu {
        bigint store_menu_id PK "대표 메뉴 PK"
        bigint store_id "가게 PK"
        varchar(100) name "메뉴명"
        bigint price "메뉴 가격(원 단위)"
        varchar(500) description "메뉴 상세설명(선택. NULL 가능)"
        tinyint sort_order "대표 메뉴 순서(1~3)"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store_menu_image {
        bigint store_menu_image_id PK "대표 메뉴 이미지 PK"
        bigint store_menu_id UK "가게 대표 메뉴 PK"
        char(36) upload_id UK "객체 스토리지 업로드 요청 식별자"
        varchar(255) object_key UK "객체 스토리지 Key"
        varchar(255) original_filename "원본 파일명"
        varchar(100) content_type "파일 MIME 타입(image/jpeg, image/png)"
        bigint file_size "파일 크기(byte)"
        varchar(20) upload_status "이미지 업로드 상태(PENDING, UPLOADED, VERIFIED)"
        datetime(6) verified_at "이미지 객체 존재 확인 완료 시각"
        datetime(6) created_at "생성일시"
        datetime(6) updated_at "수정일시"
    }
    store ||--o{ store_business_hour : "store_id"
    store ||--o{ store_campaign_summary : "store_id"
    store ||--o{ store_image : "store_id"
    store ||--o{ store_menu : "store_id"
    store_menu ||--o{ store_menu_image : "store_menu_id"
```
