# 관리자 앱 시나리오

범위: lc-frontend `apps/admin`. 시나리오 266개 (A-001 ~ A-266).

기준 코드: lc-backend develop `9f4ab523`, lc-frontend `3f3e056` (2026-10-22). 개요와 갱신 방법은 [README](README.md)에 있다.

근거 코드
- 프론트: `lc-frontend/apps/admin/src` (router.tsx, pages, features, api)
- 백엔드: `wt-loadtest-pg/src/main/java/com/lunchcatch` (develop)
- 인벤토리: `scn/endpoints.txt`, `scn/errors.txt`, `scn/jobs.txt`

공통 전제
- 모든 보호 API는 HttpOnly 쿠키 인증이다. 보호 요청이 401 AUTH-005 이면 프론트가 `POST /v1/admin/auth/tokens:refresh` 로 한 번 재발급하고 같은 요청을 다시 보낸다.
- 프론트 오류 문구는 서버 message 를 쓰지 않는다. HTTP 상태와 일부 코드로만 정한다(client.ts getErrorMessage). 400 -> "입력한 정보를 확인해 주세요.", 401 -> "로그인이 필요합니다...", 403 -> "접근 권한이 없습니다.", 429 -> "요청이 너무 많습니다...", 503 -> "일시적으로 처리할 수 없습니다...", 그 밖(404, 409, 422, 500) -> "요청을 처리하지 못했습니다...". 기능별 매핑표(billing, template, campaign, payment)가 있으면 그 문구가 우선한다.
- 서버는 503, 429 응답에 Retry-After 1초를 붙인다(인증 속도 제한 429는 남은 초). 프론트는 503에 헤더가 없어도 1초로 본다.
- 관리자 권한 ADMIN 과 SUPER_ADMIN 은 관리자 계정 화면을 빼면 같은 기능을 쓴다.

### 로그인/토큰

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-001 | 첫 방문(쿠키 없음) | 아무 주소 진입 -> 세션 복원 시도 | 전체 -> /login | POST /v1/admin/auth/tokens:refresh | AUTH-003 | refresh 쿠키 없음 | 복원 중 빈 화면(300ms 넘으면 스피너) 후 /login. 안내 문구 없음. 원래 주소는 state.from 으로 보존 | - |
| A-002 | 쿠키로 세션 복원 성공 | 새로고침 또는 재방문 | 방문한 route 그대로 | POST /v1/admin/auth/tokens:refresh | - | 유효한 refresh 토큰, ACTIVE 관리자 | 관리자 정보(adminId, name, role) 저장, 방문 주소 그대로 표시 | refresh 만료 전 |
| A-003 | 복원 중 토큰 저장소 장애 | 진입 -> 재발급 503 | /login | POST /v1/admin/auth/tokens:refresh | AUTH-002 | Redis 또는 DB 장애 | 로그인 화면에 "일시적으로 처리할 수 없습니다... 1초 후 다시 시도해 주세요." 안내 | - |
| A-004 | 복원 중 네트워크 실패 | 진입 -> fetch 실패 | /login | POST /v1/admin/auth/tokens:refresh | - | 서버 연결 불가 | "로그인 상태를 확인하지 못했습니다. 연결 상태를 확인한 뒤 로그인해 주세요." | - |
| A-005 | 복원 중 refresh 재사용 탐지 | 이미 회전된 refresh 쿠키로 진입 | /login | POST /v1/admin/auth/tokens:refresh | AUTH-004 | 회전 전 토큰이 다시 제출됨 | 서버가 그 관리자의 토큰 전부 폐기. "보안을 위해 모든 기기에서 로그아웃되었습니다..." 안내 | - |
| A-006 | 재발급 속도 제한 | 짧은 시간 반복 새로고침 | /login | POST /v1/admin/auth/tokens:refresh | - (429, 본문 없음) | IP 기준 인증 요청 한도 초과 | 로그인 화면에 "요청이 너무 많습니다. N초 후 다시 시도해 주세요." 안내. 로그인과 재발급이 같은 대기 시간을 공유 | Retry-After 남은 초 |
| A-007 | ADMIN 로그인 성공 | ID, 비밀번호 입력 -> 로그인 | /login -> /dashboard | POST /v1/admin/auth/tokens | - | ACTIVE, role ADMIN | 쿠키 발급, 대시보드 이동, 사이드바에 관리자 계정 관리 메뉴 없음 | - |
| A-008 | SUPER_ADMIN 로그인 성공 | 로그인 | /login -> /dashboard | POST /v1/admin/auth/tokens | - | ACTIVE, role SUPER_ADMIN | 사이드바에 관리자 계정 관리 메뉴 표시 | - |
| A-009 | 로그인 후 원래 주소 복원 | 보호 주소(검색 조건 포함) 진입 -> /login -> 로그인 | /login -> 원래 route | POST /v1/admin/auth/tokens | - | state.from 이 / 로 시작, // 아님, /login 아님 | 쿼리와 해시까지 원래 주소로 이동. 조건에 맞지 않으면 /dashboard | - |
| A-010 | 로그인 상태로 /login 진입 | 주소창에 /login | /login | - | - | 인증됨 | 즉시 from 또는 /dashboard 로 이동 | - |
| A-011 | 로그인 입력 검증 실패 | ID 빈값, 공백 포함, 50자 초과 또는 비밀번호 빈값 -> 제출 | /login | - | - | - | 요청 없이 필드 오류 "관리자 ID는 공백 없이 1~50자로..." 또는 "비밀번호를 입력해 주세요." | - |
| A-012 | 비밀번호 72바이트 초과 | UTF-8 72바이트 넘는 비밀번호 제출 | /login | - | - | - | 요청 없이 "아이디 또는 비밀번호가 올바르지 않습니다." | - |
| A-013 | 자격 증명 불일치 | 틀린 비밀번호, 없는 ID, 비활성 계정으로 로그인 | /login | POST /v1/admin/auth/tokens | AUTH-001 | 계정 없음 또는 비활성 또는 해시 불일치 | "아이디 또는 비밀번호가 올바르지 않습니다." 계정 존재 여부 숨김 | - |
| A-014 | 로그인 속도 제한 | 연속 실패 후 다시 로그인 | /login | POST /v1/admin/auth/tokens | - (429) | IP 기준 한도 초과 | 문구 + "N초 후 로그인" 버튼 비활성. 입력을 바꿔도 대기 유지 | Retry-After 남은 초 |
| A-015 | 로그인 중 저장소 장애 | 로그인 | /login | POST /v1/admin/auth/tokens | AUTH-002 | refresh 저장 실패 | 503 문구, 1초 대기 후 재시도 가능 | - |
| A-016 | 로그인 중 네트워크 실패 | 로그인 | /login | POST /v1/admin/auth/tokens | - | 서버 연결 불가 | "서버에 연결하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요." | - |
| A-017 | 안내 문구 닫힘 | 만료 안내가 있는 로그인 화면에서 입력 | /login | - | - | authNotice 있음 | 입력 시 안내와 이전 오류 숨김 | - |
| A-018 | 액세스 토큰 만료 후 자동 재발급 | 보호 API 호출 -> 401 -> 재발급 -> 재요청 | 모든 보호 화면 | POST /v1/admin/auth/tokens:refresh | AUTH-005 | access 만료, refresh 유효 | 사용자에게 보이지 않고 원래 요청 성공. 동시 401은 재발급 1회 공유 | access 만료 시점 |
| A-019 | 재발급 후에도 401 | 재요청이 다시 AUTH-005 | 모든 보호 화면 -> /login | POST /v1/admin/auth/tokens:refresh | AUTH-005 | access 폐기 순번이 갱신됨 | 세션 만료, "로그인 상태를 확인할 수 없습니다. 다시 로그인해 주세요." | - |
| A-020 | 사용 중 refresh 만료 | 보호 API 401 -> 재발급 실패 | 모든 보호 화면 -> /login | POST /v1/admin/auth/tokens:refresh | AUTH-003 | refresh 만료 또는 다른 곳 로그아웃 | "로그인 시간이 만료되었습니다. 다시 로그인해 주세요." | refresh 만료 이후 |
| A-021 | 사용 중 재사용 탐지 | 보호 API 401 -> 재발급이 재사용으로 판정 | 모든 보호 화면 -> /login | POST /v1/admin/auth/tokens:refresh | AUTH-004 | 다른 탭이 먼저 회전 | 전 기기 로그아웃 안내 | - |
| A-022 | 사용 중 재발급 일시 장애 | 보호 API 401 -> 재발급 503 또는 429 | 해당 화면 | POST /v1/admin/auth/tokens:refresh | AUTH-002 | 저장소 장애 또는 한도 초과 | 세션 유지, 원래 화면에 오류 문구. 수동 재시도 | - |
| A-023 | 로그아웃 취소 | 사이드바 로그아웃 -> 확인 모달 -> 취소 | 공통 레이아웃 | - | - | 인증됨 | 모달 닫힘, 포커스 복귀 | - |
| A-024 | 로그아웃 성공 | 모달에서 로그아웃 | 공통 레이아웃 -> /login | DELETE /v1/admin/auth/tokens | - | 유효 access | 204, refresh와 access 폐기, /login 이동 | - |
| A-025 | access 만료 상태 로그아웃 | 모달에서 로그아웃 -> 401 | 공통 레이아웃 -> /login | DELETE /v1/admin/auth/tokens | AUTH-005 | access 만료, refresh 유효 | 재발급 없이 로컬만 만료하고 "로그인 상태를 확인할 수 없습니다..." 안내. 서버 refresh는 폐기되지 않아 새로고침 시 A-002로 다시 로그인됨 | access 만료 이후 |
| A-026 | 로그아웃 중 저장소 장애 | 모달에서 로그아웃 -> 503 | 공통 레이아웃 | DELETE /v1/admin/auth/tokens | AUTH-002 | 폐기 저장 실패 | 모달에 오류, 로그인 유지, "N초 후 로그아웃" 대기 | - |
| A-027 | 인증 상태 변경 중 늦은 응답 | 요청 진행 중 로그인 또는 로그아웃 | 모든 보호 화면 | (공통) /v1/admin 보호 엔드포인트 전체 | - | 세션 번호 변경 | "로그인 상태가 변경되어 요청을 중단했습니다. 다시 시도해 주세요." 이전 응답 반영 안 함 | - |
| A-028 | 계정 전환 후 이전 데이터 숨김 | 로그아웃 -> 다른 계정 로그인 | 모든 목록 화면 | (공통) 조회 엔드포인트 전체 | - | adminId 변경 | 이전 계정의 목록, 캐시, 커서 미표시. 테이블 열 설정은 계정별로 보존 | - |

### 관리자 계정

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-029 | ADMIN 의 직접 URL 진입 | /admin-accounts 입력 | /admin-accounts -> /dashboard | - | - | role ADMIN | 메뉴 숨김, 주소 진입 시 대시보드로 교체 이동. 목록 API 미호출 | - |
| A-030 | 관리자 목록 조회 | 메뉴 진입 | /admin-accounts | GET /v1/admin/admins | - | SUPER_ADMIN, ACTIVE 관리자 여러 명 | 최근 발급순 목록(ID, 이름, 권한), "총 N개" | - |
| A-031 | 관리자 검색 | 검색어 입력(300ms 디바운스) | /admin-accounts (accounts.keyword) | GET /v1/admin/admins | - | 로그인 ID 또는 이름 부분 일치(대소문자 무시) | 결과 목록, URL에 검색어 저장 | - |
| A-032 | 검색 결과 없음 | 일치 없는 검색어 | /admin-accounts | GET /v1/admin/admins | - | 일치 없음 | "검색 결과가 없습니다." (검색어 없으면 "조회된 관리자 계정이 없습니다.") | - |
| A-033 | 목록 권한 거부 | 진입 -> 403 | /admin-accounts | GET /v1/admin/admins | AUTH-006 | JWT는 SUPER_ADMIN 이나 DB 상태가 비활성 또는 권한 변경 | 화면 전체에 "관리자 계정 목록에 접근할 권한이 없습니다." 발급 버튼 사용 불가 | - |
| A-034 | 목록 조회 저장소 장애 | 진입 -> 503 | /admin-accounts | GET /v1/admin/admins | AUTH-002 | 폐기 순번 확인 실패(REQUIRED 정책) | 표 오류, 재시도 아이콘은 Retry-After 동안 비활성 | - |
| A-035 | 목록 조회 기타 실패 | 진입 -> 500 또는 네트워크 | /admin-accounts | GET /v1/admin/admins | COMMON-001 | 서버 오류 | 표 오류 + 재시도 | - |
| A-036 | 발급 모달 열고 닫기 | 발급 버튼 -> 취소 | /admin-accounts, 발급 모달 | - | - | SUPER_ADMIN | 닫으면 입력과 오류 초기화. 발급 중에는 닫히지 않음 | - |
| A-037 | 발급 입력 검증 실패 | ID 형식, 이름 규칙, 비밀번호 규칙(10~72자, 대소문자, 숫자, 특수문자, 공백 없음, 72바이트), 확인 불일치 | 발급 모달 | - | - | - | 요청 없이 필드별 오류. 확인란은 blur 시 검사 | - |
| A-038 | ADMIN 계정 발급 | 입력 -> 권한 관리자 -> 발급 | 발급 모달 | POST /v1/admin/admins | - | ID 미사용 | 201, 모달 닫힘, "관리자 계정이 발급되었습니다.", 목록 재조회(현재 검색어에 맞을 때만 보임) | - |
| A-039 | SUPER_ADMIN 계정 발급 | 권한 최고 관리자 선택 -> 발급 | 발급 모달 | POST /v1/admin/admins | - | ID 미사용 | 201, 새 계정이 최고 관리자로 표시 | - |
| A-040 | ID 중복 | 기존 ID 로 발급 | 발급 모달 | POST /v1/admin/admins | ADMIN-001 | 같은 loginId 존재(동시 생성 경쟁 포함) | ID 필드와 하단에 "이미 사용 중인 관리자 ID입니다." | - |
| A-041 | 발급자 권한 상실 | 발급 -> 403 | 발급 모달 | POST /v1/admin/admins | AUTH-006 | 발급자 DB 상태가 비활성 또는 ADMIN | "접근 권한이 없습니다." | - |
| A-042 | 발급 중 토큰 폐기 | 다른 곳 로그아웃 후 발급 -> 401 -> 재발급 | 발급 모달 -> /login | POST /v1/admin/admins, POST /v1/admin/auth/tokens:refresh | AUTH-005, AUTH-003 | access 폐기 순번 증가 | 재발급 실패 시 세션 만료 안내 | - |
| A-043 | 발급 저장소 장애 | 발급 -> 503 | 발급 모달 | POST /v1/admin/admins | AUTH-002 | 폐기 순번 확인 실패 | 503 문구, "1초 후 발급" 대기 | - |
| A-044 | 발급 서버 검증 실패 | 프론트 규칙과 다른 값이 서버에 도달 | 발급 모달 | POST /v1/admin/admins | COMMON-002 | DTO 검증 위반 | "입력한 정보를 확인해 주세요." | - |

### 대시보드

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-045 | 미해결 정산 불일치 있음 | 대시보드 진입 | /dashboard, 처리할 항목 | GET /v1/admin/settlement-mismatches | - | resolved=false 1건 이상(size=1 로 totalElements만 사용) | 강조 테두리, "확인 필요" 배지, 건수, "내역 확인" 링크 | 00:00, 01:30 대사 이후 생김 |
| A-046 | 미해결 불일치 0건 | 진입 | /dashboard, 처리할 항목 | GET /v1/admin/settlement-mismatches | - | 미해결 없음 | "현재 미해결 내역이 없습니다." 0건 | - |
| A-047 | 미해결 건수 조회 실패 | 진입 -> 실패 | /dashboard, 처리할 항목 | GET /v1/admin/settlement-mismatches | COMMON-001 | 서버 오류 또는 네트워크 | "처리 대기 건수를 조회하지 못했습니다." + "다시 확인" | - |
| A-048 | 미해결 내역 이동 | "내역 확인" | /dashboard -> /settlements?tab=mismatches&mismatches.resolved=false | GET /v1/admin/settlement-mismatches | - | - | 정산 검증 탭이 미해결 필터로 열림 | - |
| A-049 | 기본 진입 | 대시보드 진입 | /dashboard, 점주 현황 탭, 7일 | GET /v1/admin/sales, GET /v1/admin/dashboard/stores | - | - | KST 어제까지 7일, 일별 조회. 현재와 이전 7일을 함께 조회 | - |
| A-050 | 점주 현황 확정 기간 | 점주 현황 탭 | /dashboard, 점주 현황 | GET /v1/admin/sales, GET /v1/admin/dashboard/stores | - | 모든 날짜 DAILY_AGGREGATION SUCCESS(FINAL) | 매출과 충전 추이, 광고 매출과 충전액 합계, 종료일 활성 캠페인, 활성 캠페인과 신규 입점 추이, 환불액과 조정액 합계, 이전 기간 대비 증감률 | 01:00 집계 완료 후 |
| A-051 | 점주 현황 미확정 포함 | 오늘 포함 기간 선택 | /dashboard, 점주 현황 | GET /v1/admin/sales, GET /v1/admin/dashboard/stores | - | 일부 버킷 IN_PROGRESS(값 null) | 기간 합계 카드는 "집계 미완료", 차트는 확정 구간만 값 표시 | 01:00 집계 전 또는 집계 실패 |
| A-052 | 전 구간 미확정 차트 | 오늘만 조회 | /dashboard | GET /v1/admin/sales, GET /v1/admin/dashboard/stores, GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | - | 모든 버킷 IN_PROGRESS | 각 추이 영역에 "집계 미완료" | 당일 |
| A-053 | 사용자 현황 확정 기간 | 사용자 현황 탭 | /dashboard (dashboard.view=members) | GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | - | FINAL | 유효 노출, 찜, 발급, 사용 합계 카드, 선택 지표 추이 차트, 유효 노출 비율(합산 재계산), 무효 노출 건수, 종료일 활성 회원, 신규 회원, 활성과 신규 회원 추이 | 01:00 집계 완료 후 |
| A-054 | 이용 지표 선택 | 지표 카드 클릭 | /dashboard (dashboard.metric) | - | - | 사용자 현황 | 선택 지표로 차트 전환, URL 저장 | - |
| A-055 | 이전 기간 비교 켜고 끄기 | 토글 | /dashboard (dashboard.compare) | GET /v1/admin/dashboard/funnel | - | 현재와 이전 기간 버킷 수 같음 | 이전 기간 선이 겹쳐 표시되거나 사라짐 | - |
| A-056 | 비교 불가 | 이전 기간 로딩 중, 실패 또는 길이 다름 | /dashboard | GET /v1/admin/dashboard/funnel | - | 이전 기간 데이터 없음 | 비교 토글 비활성 | - |
| A-057 | 오늘 프리셋 | 오늘 선택 | /dashboard (preset=today) | GET /v1/admin/sales, GET /v1/admin/dashboard/stores, GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | - | 오늘 미집계 | 모든 지표 "집계 미완료" | 선택 시점 KST 날짜 |
| A-058 | 30일 프리셋 | 30일 선택 | /dashboard (preset=30) | GET /v1/admin/sales, GET /v1/admin/dashboard/stores, GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | - | - | 어제까지 30일 조회 | - |
| A-059 | 직접 선택 | 직접 선택 -> 기간 지정 | /dashboard (preset=custom) | GET /v1/admin/sales, GET /v1/admin/dashboard/stores, GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | - | - | 지정 기간 조회, 프리셋과 같으면 프리셋으로 표시 | - |
| A-060 | 기간 범위 오류 | 92일 초과 또는 역전 기간 지정 | /dashboard | - | - | - | 경고 문구("일별 조회는 최대 92일까지..."), 지표 영역 미조회. 스냅샷은 표시 | - |
| A-061 | 일부 지표 실패 | 지표 중 하나 실패 | /dashboard | GET /v1/admin/sales, GET /v1/admin/dashboard/stores, GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | ANALYTICS-001, COMMON-001 | 서버 오류 또는 기간 오류 | "일부 지표를 불러오지 못했습니다." + 첫 오류 문구(ANALYTICS-001은 "조회 기간과 집계 단위를 확인해 주세요.") + 다시 시도(실패한 것만) | - |
| A-062 | 집계 시각 표시 | 진입 | /dashboard 헤더 | GET /v1/admin/sales, GET /v1/admin/dashboard/stores, GET /v1/admin/dashboard/funnel, GET /v1/admin/dashboard/members | - | aggregatedAt 있음 또는 null | 탭별 집계 완료 시각, 없으면 대시 표시 | 01:00 이후 갱신 |
| A-063 | 매출 현황 이동 | 매출 추이의 매출 현황 링크 | /dashboard -> /settlements?tab=sales | GET /v1/admin/sales | - | - | 매출 현황 탭 기본 기간으로 열림 | - |
| A-064 | 부정 관리 이동 | 유효 노출 비율 링크 | /dashboard -> /fraud?fraud.from&fraud.to | GET /v1/admin/fraud/invalid-impressions | - | 대시보드 기간 전달 | 31일 이하는 같은 기간 조회. 31일 초과 기간이면 부정 관리에서 "최대 31일" 안내로 미조회 | - |
| A-065 | 누적과 잔액 스냅샷 | 진입 | /dashboard, 누적과 잔액 현황 | GET /v1/admin/dashboard/members, GET /v1/admin/dashboard/stores, GET /v1/admin/sales | - | 어제 FINAL | 사용자 탭: 누적 회원, 누적 탈퇴, 정지 회원. 점주 탭: 누적 입점 업체, 미소진 잔액 | 01:00 집계 후 |
| A-066 | 스냅샷 기준일 변경 | 날짜 선택, 초기화 | /dashboard (snapshot.date) | GET /v1/admin/dashboard/members, GET /v1/admin/dashboard/stores, GET /v1/admin/sales | - | - | 어제까지만 선택. URL 미래 날짜는 어제로 제한, 초기화는 어제 | - |
| A-067 | 스냅샷 미집계 | 01:00 전 진입 | /dashboard, 스냅샷 | GET /v1/admin/dashboard/members, GET /v1/admin/dashboard/stores, GET /v1/admin/sales | - | 어제 버킷 IN_PROGRESS | 카드 값 "집계 미완료" | 00:00~01:00 또는 집계 실패 |
| A-068 | 스냅샷 값 없음 | 진입 | /dashboard, 스냅샷 | GET /v1/admin/dashboard/members, GET /v1/admin/dashboard/stores, GET /v1/admin/sales | - | FINAL 이나 누적 스냅샷 null | "데이터 없음" | - |
| A-069 | 스냅샷 실패 | 진입 -> 실패 | /dashboard, 스냅샷 | GET /v1/admin/dashboard/members, GET /v1/admin/dashboard/stores, GET /v1/admin/sales | ANALYTICS-001, COMMON-001 | 서버 오류 | "누적과 잔액 현황을 불러오지 못했습니다" 오류와 재시도 | - |
| A-070 | 자정 경과 | 화면을 켠 채 KST 자정 통과, 포커스 복귀 | /dashboard | GET /v1/admin/dashboard/members, GET /v1/admin/dashboard/stores, GET /v1/admin/sales | - | - | 스냅샷 선택 상한이 새 어제로 갱신. 오늘 프리셋은 재선택 시 새 날짜 | 00:00 KST |

### 회원 목록

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-071 | 사용자 탭 진입 | 사용자 목록 탭 클릭 | /members?tab=member | GET /v1/admin/members | - | 회원 존재 | 가입일 내림차순 20건, 상태 배지(활성, 정지, 탈퇴). 숨긴 탭은 조회 중단하고 상태 보존 | - |
| A-072 | 닉네임 검색 | 닉네임 입력(디바운스) | /members?tab=member (users.keyword) | GET /v1/admin/members | - | 닉네임 접두사 일치 | 1페이지부터 결과 | - |
| A-073 | 상태 필터 | 활성, 정지, 탈퇴 선택 | /members?tab=member (users.status) | GET /v1/admin/members | - | 상태별 회원 | 필터 결과. 탈퇴 회원 닉네임은 "탈퇴한회원" | - |
| A-074 | 가입일 범위 | 기간 선택 | /members?tab=member (users.from, users.to) | GET /v1/admin/members | - | - | joinedFrom, joinedTo 포함 조회 | - |
| A-075 | 가입일 역전 | 시작 > 종료 | /members?tab=member | - | - | - | 표 오류 "가입일 시작 날짜는 종료 날짜보다 늦을 수 없습니다." 재시도 없음, 미호출 | - |
| A-076 | 정렬 순환 | 회원 ID, 닉네임, 가입일, 최근 로그인 헤더 클릭 | /members?tab=member (users.sortBy, sortDir) | GET /v1/admin/members | - | - | 내림 -> 오름 -> 기본(가입일 내림) 순환. 상태 열은 정렬 없음. 최근 로그인 없는 회원은 맨 뒤 | - |
| A-077 | 페이지와 크기 | 페이지 이동, 5/10/20/50 선택 | /members?tab=member (users.page, size) | GET /v1/admin/members | - | 20건 초과 | 페이지 변경, 크기 변경 시 1페이지 | - |
| A-078 | 회원 결과 없음 | 일치 없음 | /members?tab=member | GET /v1/admin/members | - | - | "조회된 회원이 없습니다" | - |
| A-079 | 회원 조회 실패 | 실패 | /members?tab=member | GET /v1/admin/members | COMMON-001, COMMON-002 | 서버 오류 | 표 오류 + 재시도 | - |
| A-080 | 확인 대상 배너 | /members?userId=ID 로 진입 -> 확인 대상 지우기 | /members?userId= | GET /v1/admin/members | - | - | 사용자 탭 강제, "사용자 확인 대상" 배너, ID로 검색하지 않음. 지우면 userId 제거 | - |

### 점주 목록

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-081 | 점주 탭 기본 | /members 진입 | /members (점주 목록 탭) | GET /v1/admin/owners | - | 점주 존재 | 가입일 최신순 고정 20건, 상태와 가게 등록 배지 | - |
| A-082 | 점주 ID 검색 | 숫자만 입력 | /members (owners.keyword) | GET /v1/admin/owners | - | - | 점주 ID 정확 일치 1건 또는 0건 | - |
| A-083 | 상호 검색 | 문자 포함 입력 | /members (owners.keyword) | GET /v1/admin/owners | - | 상호 부분 일치 | 해당 상호의 점주 목록 | - |
| A-084 | 상호 검색 결과 과다 | 너무 넓은 검색어 | /members | GET /v1/admin/owners | COMMON-002 | 상호 일치 1만 건 초과 | 표 오류 "입력한 정보를 확인해 주세요." | - |
| A-085 | 상태 필터 | 가입 진행 중, 활성, 정지, 탈퇴 | /members (owners.status) | GET /v1/admin/owners | - | ONBOARDING 포함 | 필터 결과 | - |
| A-086 | 탈퇴 점주 표시 | 탈퇴 필터 | /members | GET /v1/admin/owners | - | WITHDRAWN | 상호 유지, 사업자번호와 최근 로그인은 대시 표시 | - |
| A-087 | 가입일 범위와 역전 | 기간 선택, 역전 | /members (owners.from, owners.to) | GET /v1/admin/owners | - | - | 정상 범위 조회. 역전이면 "가입일 시작일은 종료일보다 늦을 수 없습니다." 미호출 | - |
| A-088 | 점주 페이지 이동 | 페이지 이동 | /members (owners.page) | GET /v1/admin/owners | - | 20건 초과 | 페이지 크기 선택 없음, 총 건수 표시 | - |
| A-089 | 점주 결과 없음, 실패 | 일치 없음 또는 오류 | /members | GET /v1/admin/owners | COMMON-001 | - | "조회된 점주가 없습니다." 또는 표 오류 + 재시도 | - |

### 캠페인 관리

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-090 | 캠페인 목록과 실적 | 진입 | /campaigns | GET /v1/admin/campaigns, GET /v1/admin/campaign-reports | - | 캠페인 존재 | 캠페인 ID 내림차순 20건, 할인, 하루 예산, 집행 기간, 소진 포인트, 유효 노출 | 실적은 전일까지 집계분 |
| A-091 | 가게 ID 검색 | 가게 ID 입력 | /campaigns (campaigns.storeId) | GET /v1/admin/campaigns | - | - | 해당 가게 캠페인 | - |
| A-092 | 가게 ID 형식 오류 | 0, 문자, 안전 정수 초과 | /campaigns | - | - | - | "가게 ID는 양의 정수로 입력해 주세요." 미호출 | - |
| A-093 | 상태 필터 | 임시저장, 집행 예정, 집행 중, 일시 중단, 종료 | /campaigns (campaigns.status) | GET /v1/admin/campaigns | - | - | 단일 상태 결과 | - |
| A-094 | 더 불러오기 | 더 불러오기 | /campaigns | GET /v1/admin/campaigns, GET /v1/admin/campaign-reports | - | nextPageToken 있음 | 행 누적(중복 제거), 새 캠페인 실적만 추가 조회(100개 단위), "불러온 캠페인 N건" | - |
| A-095 | 불러오기 크기 변경 | 5/10/20/50 | /campaigns (campaigns.size) | GET /v1/admin/campaigns | - | - | 커서 초기화 후 처음부터 | - |
| A-096 | 추가 페이지 실패 | 더 불러오기 -> 실패 | /campaigns | GET /v1/admin/campaigns | COMMON-001 | - | 기존 행 유지, 아래 행에 오류와 재시도(같은 커서) | - |
| A-097 | 첫 조회 실패, 없음 | 진입 | /campaigns | GET /v1/admin/campaigns | COMMON-001 | 캠페인 없음 또는 오류 | "조회된 캠페인이 없습니다" 또는 표 오류 + 재시도 | - |
| A-098 | 실적 조회 실패 | 실적 실패 | /campaigns | GET /v1/admin/campaign-reports | COMMON-002, COMMON-001 | - | 실적 셀 "조회 실패", "실적 조회 실패" 문구와 "실적 다시 조회" | - |
| A-099 | 실적 대기, 0 | 실적 응답 전, 집계 없는 캠페인 | /campaigns | GET /v1/admin/campaign-reports | - | campaign_daily_report 행 없음 | 응답 전 "조회 중", 집계 없으면 0P, 0회 | 01:00 집계 전 신규 캠페인 |
| A-100 | 미등록 가게, 미설정 값 | 목록 확인 | /campaigns | GET /v1/admin/campaigns | - | 가게 최종 등록 전, dailyBudget null, 기간 null | "미등록 가게", "미설정" | - |
| A-101 | 캠페인 상세 열기 | 행 클릭 | /campaigns?campaigns.detail=ID | GET /v1/admin/campaigns/{campaignId}, GET /v1/admin/campaign-reports | - | 캠페인 존재 | 드로어: 기본 정보, 실적, 쿠폰 조건, 집행 설정, 상태 변경 이력, 포스터 | - |
| A-102 | 일시 중단 사유 | 상세 확인 | 캠페인 상세 | GET /v1/admin/campaigns/{campaignId} | - | PAUSED, pausedReason ADMIN 또는 OWNER 또는 NO_POINTS | 중단 사유 행 표시 | - |
| A-103 | 상태 이력 있음, 없음 | 상세 확인 | 캠페인 상세 | GET /v1/admin/campaigns/{campaignId} | - | statusLogs 있음 또는 빈 배열 | 전이(이전 -> 다음), 시각, 주체, 사유 또는 "상태 변경 이력이 없습니다." | - |
| A-104 | 포스터 있음, 없음 | 상세 확인 | 캠페인 상세 | GET /v1/admin/campaigns/{campaignId} | - | poster 있음 또는 null | sandbox iframe 미리보기 또는 "등록된 포스터가 없습니다." | - |
| A-105 | 없는 캠페인 상세 | 주소로 없는 ID | /campaigns?campaigns.detail=ID | GET /v1/admin/campaigns/{campaignId} | CAMPAIGN-012 | 캠페인 없음 | "캠페인을 찾을 수 없습니다." + 다시 조회 | - |
| A-106 | 상세 ID 형식 오류 | 주소로 비정수 ID | /campaigns?campaigns.detail=abc | - | - | - | "캠페인 ID는 양의 정수로 입력해 주세요." 미호출 | - |
| A-107 | 상세 실적 실패 | 상세 실적 실패 | 캠페인 상세 | GET /v1/admin/campaign-reports | COMMON-001 | - | 실적 영역 오류 + "실적 다시 조회" | - |
| A-108 | 집계 후 실적 갱신 | 다음 날 재조회 | /campaigns | GET /v1/admin/campaign-reports | - | 전일 집계 SUCCESS | 소진과 유효 노출이 전일분까지 증가 | 01:00 DAILY_AGGREGATION 후 |

### 템플릿

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-109 | 템플릿 목록 | 진입 | /templates | GET /v1/admin/templates | - | 템플릿 존재 | 최신 등록순 카드, 게시됨 또는 임시저장 중 배지, 썸네일, 최초 등록일, 최종 수정일 | - |
| A-110 | 목록 없음, 실패 | 진입 | /templates | GET /v1/admin/templates | COMMON-001 | 0건 또는 오류 | "조회된 템플릿이 없습니다" 또는 오류 문구, 생성 버튼 비활성 | - |
| A-111 | 생성 시작 | 템플릿 생성 -> 이름 입력 -> 시작하기 | /templates -> /templates/new | - | - | 100개 미만 | 이름(앞뒤 공백 제거 1~100자)을 state 로 넘겨 작성 화면, 아직 서버 생성 없음 | - |
| A-112 | 생성 한도 도달 | 목록 확인 | /templates | GET /v1/admin/templates | - | 템플릿 100개 | 생성 버튼 비활성, 포인터 또는 포커스 시 "최대 100개" 툴팁 | - |
| A-113 | 작성 주소 직접 진입 | /templates/new 직접 입력 | /templates/new | - | - | state 없음 | 이름 모달 표시, 취소 시 목록 | - |
| A-114 | 첫 요청으로 생성 | 요청 입력 -> 전송 | /templates/new -> /templates/drafts/:id/edit | POST /v1/admin/templates | - | 한도 미만, 분당 3회 미만 | 201, 버전 1 미리보기와 HTML, 설명 말풍선, 추천 수정 요청, 주소 교체(대화 유지) | - |
| A-115 | 안전하지 않은 요소 제거 | 생성 또는 수정 | 편집 화면 | POST /v1/admin/templates | - | sanitizer 가 요소 제거(removedElements 있음) | "안전하지 않은 태그와 속성은 제거했습니다." 말풍선 | - |
| A-116 | 생성 경쟁으로 한도 초과 | 전송 | 편집 화면 | POST /v1/admin/templates | POSTER-003 | 그사이 100개 도달 | "템플릿은 최대 100개까지 등록할 수 있습니다." 입력 복구 | - |
| A-117 | 요청 문구 너무 짧음 | 의미 문자 2자 미만 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-022 | - | 422. 서버 안내문("요청 문구가 너무 짧습니다. ...") 표시, 입력 복구 | - |
| A-118 | 사용할 수 없는 문자 | 금지 문자 포함 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-023 | - | A-117 과 같은 일반 문구 | - |
| A-119 | 지시 주입 의심 | 템플릿과 무관한 지시 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-024 | - | A-117 과 같은 일반 문구 | - |
| A-120 | 개인정보 포함 | 전화번호, 사업자번호 포함 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-025 | - | A-117 과 같은 일반 문구 | - |
| A-121 | 분당 3회 초과 | 1분 안에 4번째 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-014 | 관리자 ID 기준 고정 창 | "관리자당 1분에 3회까지..." 문구. 대기 카운트다운 없음 | 1분 창 |
| A-122 | 생성 지연, 같은 요청 처리 중 | 전송 -> 503 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-004 | LLM 시간 초과 또는 같은 requestId 처리 중 | 지연 문구 + "1초 후 같은 요청을 다시 보낼 수 있습니다", 입력 잠금. 같은 문구 재전송은 같은 requestId | Retry-After 1초 |
| A-123 | 생성 요청 몰림 | 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-012 | Bedrock throttling | "생성 요청이 몰리고 있습니다..." 대기 표시 없음 | - |
| A-124 | 생성 차단 문구 | 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-013 | 가드레일 차단 | "입력한 문구로는 템플릿을 생성할 수 없습니다..." | - |
| A-125 | 생성 결과 잘림 | 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-010 | 출력 토큰 한도 | "생성된 템플릿이 중간에 잘렸습니다..." | - |
| A-126 | 허용되지 않은 요소 | 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-011 | 모델 출력에 금지 요소 | "허용되지 않은 태그나 속성이 포함된 결과..." | - |
| A-127 | 필수 슬롯 누락 | 전송 | 편집 화면 | POST /v1/admin/templates, POST /v1/admin/templates/{templateId}/versions | POSTER-001 | 슬롯 계약 위반 | "필수 영역을 갖춘 템플릿을 만들지 못했습니다..." | - |
| A-128 | 응답 유실 후 재전송 | 같은 이름과 문구 재전송 | 편집 화면 | POST /v1/admin/templates | - | 첫 요청이 저장 완료 | 같은 requestId 로 저장된 버전 그대로 응답(제거 목록은 빈 배열), 분당 횟수 미차감 | - |
| A-129 | 초안 열기 | 목록에서 수정 | /templates/drafts/:templateId/edit | GET /v1/admin/templates/{templateId}, GET /v1/admin/templates/{templateId}/versions | - | DRAFT | 최신 20개 버전의 요청과 설명 복원, 최신 버전 미리보기 | - |
| A-130 | 이전 버전 불러오기 | "이전 버전 불러오기" | 편집 화면 | GET /v1/admin/templates/{templateId}/versions | - | nextPageToken 있음 | 이전 버전 누적(중복 제거) | - |
| A-131 | 최신 버전 수정 | 요청 전송 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions, GET /v1/admin/templates/{templateId}, GET /v1/admin/templates/{templateId}/versions | - | DRAFT, 최신 버전 선택 | 201 새 버전, 상세와 이력 재조회 | - |
| A-132 | 구조 변경으로 거부 | 수정 전송 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions | POSTER-002 | 새 HTML 구조가 이전과 다름 | "기존 템플릿의 영역 구조를 유지하지 못했습니다..." | - |
| A-133 | 그사이 게시된 초안 수정 | 수정 전송 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions | POSTER-006 | 다른 관리자가 게시 | "게시된 템플릿은 수정하거나 삭제할 수 없습니다." | - |
| A-134 | 삭제된 템플릿 수정 | 수정 전송 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions | POSTER-005 | 다른 관리자가 삭제 | "템플릿을 찾을 수 없습니다." | - |
| A-135 | 저장 후 이력 재조회 실패 | 수정 성공 -> 후속 조회 실패 | 편집 화면 | GET /v1/admin/templates/{templateId}, GET /v1/admin/templates/{templateId}/versions | COMMON-001 | - | "임시저장은 완료했지만 버전 이력을 불러오지 못했습니다. 새로고침해 주세요." | - |
| A-136 | 부분 선택 수정 | 미리보기 요소 또는 HTML 줄 선택 -> 요청 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions | - | - | "수정 대상" 배지, 선택 코드 발췌를 붙인 requestPrompt 전송, 해제 버튼 | - |
| A-137 | 선택 문맥 길이 초과 | 긴 요청 + 선택 | 편집 화면 | - | - | 500자 제한 초과 | "선택 영역과 함께 보낼 수 있도록 요청 내용을 조금 줄여 주세요." 미전송 | - |
| A-138 | 추천 수정 요청 | 추천 버튼 클릭 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions | - | 입력란 빈 상태, 최신 버전 선택 | 추천 원문 그대로 전송(선택 문맥 미첨부) | - |
| A-139 | 이전 버전으로 이동 | 버전 칩 -> 돌아가기 | 편집 화면, 버전 이동 모달 | - | - | 사용 가능한 버전 | 미리보기와 HTML 만 전환, 서버 변경 없음 | - |
| A-140 | 이전 버전에서 수정 시도 | 이전 버전 선택 후 전송 | 편집 화면 | - | - | 선택 버전 != 최신 | "현재는 최신 버전만 수정할 수 있습니다..." 추천 버튼 비활성 | - |
| A-141 | 버전 20개 상한 | 21번째 버전 생성 | 편집 화면 | POST /v1/admin/templates/{templateId}/versions | - | 버전 20개 | 서버가 가장 오래된 버전 삭제. 재조회 전 칩은 "확인 필요", 재조회 후 없는 버전은 "삭제됨" 선택 불가 | - |
| A-142 | 최종 저장(게시) | 저장 -> 확인 모달 -> 저장 | 편집 화면, 템플릿 저장 모달 | POST /v1/admin/templates/{templateId}/publish | - | DRAFT, 선택 버전 존재 | 200 PUBLISHED, 나머지 버전 소프트 삭제, 목록 복귀 | - |
| A-143 | 게시할 버전 사라짐 | 저장 | 템플릿 저장 모달 | POST /v1/admin/templates/{templateId}/publish, GET /v1/admin/templates/{templateId}/versions | POSTER-008 | 버전 삭제됨 | "선택한 버전을 찾을 수 없습니다." 버전 재조회, 해당 버전 삭제 표시 | - |
| A-144 | 다른 버전으로 이미 게시 | 저장 | 템플릿 저장 모달 | POST /v1/admin/templates/{templateId}/publish | POSTER-007 | 다른 관리자가 다른 버전 게시 | "이미 다른 버전으로 게시된 템플릿입니다." | - |
| A-145 | 같은 버전 재게시 | 응답 유실 후 다시 저장 | 템플릿 저장 모달 | POST /v1/admin/templates/{templateId}/publish | - | 같은 버전으로 이미 게시 | 200 현재 상태 그대로, 목록 복귀 | - |
| A-146 | 게시 템플릿 상세 | 상세보기 | /templates/:templateId | GET /v1/admin/templates/{templateId} | - | PUBLISHED | 읽기 전용("최종 저장된 템플릿입니다..."), 입력란, 저장, 선택 없음, 버전 미조회 | - |
| A-147 | 없는 템플릿 진입 | 주소로 없는 ID | /templates/:templateId, /templates/drafts/:templateId/edit | GET /v1/admin/templates/{templateId} | POSTER-005 | 없음 | "템플릿을 찾을 수 없습니다." + 목록으로 | - |
| A-148 | 비정수 템플릿 ID | /templates/abc | /templates/:templateId | GET /v1/admin/templates/{templateId} | COMMON-003 | - | "입력한 정보를 확인해 주세요." + 목록으로 | - |
| A-149 | 템플릿 활성화 | 비활성 토글 -> 확인 모달 -> 활성화 | /templates, 활성화 모달 | PATCH /v1/admin/templates/{templateId}/active | - | PUBLISHED, active=false | 활성 표시, 점주 템플릿 목록에 노출 | - |
| A-150 | 템플릿 비활성화 | 활성 토글 -> 비활성화 | /templates, 비활성화 모달 | PATCH /v1/admin/templates/{templateId}/active | - | PUBLISHED, active=true | 비활성 표시, 점주에게 미노출 | - |
| A-151 | 활성 변경 실패 | 확인 -> 실패 | 활성화 모달 | PATCH /v1/admin/templates/{templateId}/active | POSTER-009, POSTER-005, COMMON-001 | 방어 경로(게시 전, 없음) 또는 서버 오류 | 모달 안 오류, 상태 유지 | - |
| A-152 | 초안 삭제 | 삭제 -> 확인 모달 -> 삭제 | /templates, 삭제 모달 | DELETE /v1/admin/templates/{templateId}, GET /v1/admin/templates | - | DRAFT | 204, 모든 버전 삭제, 한도 1 반환, "... 템플릿을 삭제했습니다." 목록 재조회 | - |
| A-153 | 삭제 실패 | 삭제 | 삭제 모달 | DELETE /v1/admin/templates/{templateId} | POSTER-006, POSTER-005 | 그사이 게시 또는 이미 삭제 | 모달 안 매핑 문구 | - |
| A-154 | 작업 중 목록 이동 차단 | 생성 중 목록으로 | 편집 화면 | - | - | busy 또는 대기 | 목록으로 버튼 비활성. 복귀 시 목록 쿼리 유지 | - |
| A-155 | 게시 버전 영구 삭제 | 배치 실행 | - (화면 변화 없음) | - | - | 게시로 소프트 삭제된 버전 | CAMPAIGN_TEMPLATE_VERSION_PURGE 가 하드 삭제. 게시 템플릿은 버전을 조회하지 않아 관리자 화면 영향 없음 | 매일 04:00 KST |

### 결제

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-156 | 결제 목록 | 결제 내역 탭 | /settlements?tab=payments | GET /v1/admin/payments | - | 결제 존재 | 기본 정렬(서버 기본), 20건, 상태 배지 | - |
| A-157 | 검색 유형 전환 | 점주 ID, 주문 번호, 거래 ID 선택 | /settlements?tab=payments (payments.searchType) | GET /v1/admin/payments | - | - | 검색어 초기화, 1페이지 | - |
| A-158 | 결제 검색 | 검색어 입력 | /settlements?tab=payments (payments.keyword) | GET /v1/admin/payments | - | 일치 결제 | 검색 결과 | - |
| A-159 | 점주 ID 형식 오류 | 점주 ID 유형에 문자 입력 | /settlements?tab=payments | - | - | - | "점주 ID를 양의 정수로 입력해 주세요." 미호출 | - |
| A-160 | 상태와 요청일 필터 | 상태 선택, 기간 선택 | /settlements?tab=payments | GET /v1/admin/payments | - | REQUESTED, APPROVED, FAILED, CANCELLED, UNKNOWN | 필터 결과. 역전 기간은 "시작 날짜는 종료 날짜보다 늦을 수 없습니다." 미호출 | - |
| A-161 | 결제 정렬 | 요청 시각, 결제 금액, 승인 시각 헤더 | /settlements?tab=payments | GET /v1/admin/payments | - | - | 내림 -> 오름 -> 해제 순환, 1페이지 | - |
| A-162 | 결제 페이지 | 페이지, 크기 | /settlements?tab=payments | GET /v1/admin/payments | - | - | 이동 시 상세 닫힘 | - |
| A-163 | 결제 없음, 실패 | 조회 | /settlements?tab=payments | GET /v1/admin/payments | COMMON-002, COMMON-001 | - | "조회된 결제 내역이 없습니다." 또는 표 오류 + 재시도 | - |
| A-164 | 확정 결제 상세 | 행 클릭 | 결제 상세 드로어 | - | - | 상태 UNKNOWN 아님 | 재조회 버튼 없음, 자동 재조회 기록(진행 상태, 시도 횟수, 다음 재조회, 마지막 오류) 또는 "자동 재조회 기록이 없습니다." | - |
| A-165 | 재조회로 승인 확정 | UNKNOWN 상세 -> 결제 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck, GET /v1/admin/payments | - | PG 조회 APPROVED, 금액 일치 | APPROVED, CHARGE 원장 생성, "재조회 결과가 '결제 성공' 상태로 반영되었습니다." 목록 1페이지 갱신 | - |
| A-166 | 재조회로 실패 확정 | 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck | - | PG 조회 FAILED | FAILED, 실패 사유 표시 | - |
| A-167 | 재조회로 취소 확정 | 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck | - | PG 조회 EXPIRED 또는 CANCELED | CANCELLED, 충전 없음 | - |
| A-168 | 재조회 미확정 | 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck | - | PG PENDING, NOT_FOUND, 조회 실패, 금액 불일치 | 200 UNKNOWN 유지, "재조회했지만 아직 결제 결과를 확정하지 못했습니다." 자동 재조회 시도 횟수는 그대로 | - |
| A-169 | 이미 확정되어 재조회 불가 | 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck | BILLING-005 | 워커나 웹훅이 먼저 확정 | "이미 결제 결과가 확정되어... 목록을 갱신했습니다." 버튼 비활성, 목록 갱신 | 워커 실행 직후 |
| A-170 | 결제 없음 | 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck | BILLING-004 | 결제 없음 | "결제를 찾을 수 없습니다..." 버튼 비활성 | - |
| A-171 | 자동 재조회 진행 중 | 상세 확인 | 결제 상세 드로어 | GET /v1/admin/payments | - | 아웃박스 PENDING | "재조회 대기", 시도 n / 5, 다음 재조회 시각 | PaymentRecheckWorker 주기 |
| A-172 | 자동 재조회 종료 | 상세 확인 -> 수동 재조회 | 결제 상세 드로어 | POST /v1/admin/payments/{paymentId}:recheck | - | 시도 5회 도달 FAILED_PERMANENT | "자동 재조회 종료" 표시, 관리자가 재조회로 확정해야 함(관리자 재조회는 자동 재시도를 되살리지 않음) | 첫 미확정 후 약 48분 |
| A-173 | 드로어 닫은 뒤 재조회 완료 | 재조회 중 닫기 | /settlements?tab=payments | POST /v1/admin/payments/{paymentId}:recheck, GET /v1/admin/payments | - | - | 결과 안내 없이 목록만 갱신 | - |

### 환불

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-174 | 환불 요청 목록 | 환불 요청 탭 | /settlements?tab=refunds | GET /v1/admin/refund-requests | - | 요청 존재 | 요청 시각 내림차순, 상태 배지, 현재 잔액과 집행 캠페인 여부 포함 | - |
| A-175 | 환불 필터 | 점주 ID, 상태, 요청일 | /settlements?tab=refunds (refunds.*) | GET /v1/admin/refund-requests | - | - | 필터 결과. 점주 ID 형식 오류나 역전 기간은 표 오류로 미호출 | - |
| A-176 | 승인됨 상태 필터 | 상태 승인됨 선택 | /settlements?tab=refunds | GET /v1/admin/refund-requests | - | 백엔드가 APPROVED 를 쓰지 않음 | 항상 "조회된 환불 요청이 없습니다." | - |
| A-177 | 환불 정렬과 페이지 | 요청 시각, 요청 포인트, 처리 시각 헤더, 페이지 | /settlements?tab=refunds | GET /v1/admin/refund-requests | - | - | 내림 <-> 오름 전환, 1페이지 | - |
| A-178 | 처리된 요청 상세 | 행 클릭 | 환불 요청 상세 드로어 | - | - | COMPLETED 또는 REJECTED | 처리 버튼 없음, 처리 시각, 처리자, 반려 사유, 수수료 | - |
| A-179 | 환불 승인 | 대기 요청 -> 환불 승인 -> 확인 모달 승인 | 환불 상세, 환불 승인 모달 | POST /v1/admin/refund-requests/{refundRequestId}:approve, GET /v1/admin/refund-requests | - | REQUESTED, 집행 캠페인 없음, 잔액 >= 요청액 | COMPLETED(수수료 0), REFUND 원장, "환불 요청을 승인했습니다." 1페이지 갱신. 실제 송금 없음 안내 | - |
| A-180 | 집행 캠페인으로 승인 차단 | 상세 확인 | 환불 상세 | - | - | hasActiveCampaign=true | "집행 중인 캠페인이 있어 승인할 수 없습니다." 승인 버튼 비활성, 반려만 가능 | - |
| A-181 | 잔액 부족으로 승인 차단 | 상세 확인 | 환불 상세 | - | - | currentBalance < amount | "현재 잔액이 요청 금액보다 적어 승인할 수 없습니다." 승인 버튼 비활성 | - |
| A-182 | 승인 시점 캠페인 생김 | 승인 | 환불 승인 모달 | POST /v1/admin/refund-requests/{refundRequestId}:approve | BILLING-010 | 조회 후 집행 캠페인 생성 | "집행 중인 캠페인이 있어 환불할 수 없습니다." 목록 갱신(열린 상세는 이전 값 유지) | - |
| A-183 | 승인 시점 잔액 감소 | 승인 | 환불 승인 모달 | POST /v1/admin/refund-requests/{refundRequestId}:approve | BILLING-013 | 조회 후 예약이나 음수 조정 | "현재 잔액이 환불 요청 금액보다 적습니다." | - |
| A-184 | 이미 처리된 요청 | 승인 또는 반려 | 환불 모달 | POST /v1/admin/refund-requests/{refundRequestId}:approve, POST /v1/admin/refund-requests/{refundRequestId}:reject | BILLING-015 | 다른 관리자가 처리 | "이미 처리된 환불 요청입니다. 목록을 다시 확인해 주세요." 버튼 비활성, 목록 갱신 | - |
| A-185 | 요청 없음 | 승인 또는 반려 | 환불 모달 | POST /v1/admin/refund-requests/{refundRequestId}:approve, POST /v1/admin/refund-requests/{refundRequestId}:reject | BILLING-014 | 요청 없음 | "환불 요청을 찾을 수 없습니다." 버튼 비활성 | - |
| A-186 | 잔액 잠금 대기 초과 | 승인 | 환불 승인 모달 | POST /v1/admin/refund-requests/{refundRequestId}:approve | BILLING-006 | 계정 행 잠금 5초 초과 | "잔액을 조회하거나 처리를 완료하지 못했습니다..." 재시도 가능 | - |
| A-187 | 환불 반려 | 반려 -> 사유 입력 -> 반려 | 환불 반려 모달 | POST /v1/admin/refund-requests/{refundRequestId}:reject | - | REQUESTED | REJECTED, "환불 요청을 반려했습니다." | - |
| A-188 | 반려 사유 누락 | 사유 공란 또는 255자 초과 | 환불 반려 모달 | - | - | - | "반려 사유를 1~255자로 입력해 주세요." 미호출 | - |
| A-189 | 환불 목록 없음, 실패 | 조회 | /settlements?tab=refunds | GET /v1/admin/refund-requests | COMMON-002, COMMON-001 | - | "조회된 환불 요청이 없습니다." 또는 표 오류 + 재시도 | - |

### 원장/조정

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-190 | 원장 탭 초기 | 포인트 변동 내역 탭 | /settlements?tab=ledger | - | - | 점주 ID 없음 | 합계와 원장 모두 "점주 ID와 조회 기간을 입력해 주세요". 기본 기간은 이번 달 | - |
| A-191 | 점주 원장과 합계 조회 | 점주 ID 입력(디바운스) | /settlements?tab=ledger (ledger.ownerId) | GET /v1/admin/point-ledger, GET /v1/admin/point-ledger/summary | - | 원장 존재 | 원장 행(전후 사용 가능 잔액은 서버 누적값)과 기간별 합계 | - |
| A-192 | 입력 중 안내 | 입력 중 | /settlements?tab=ledger | - | - | - | "입력이 끝나면 자동으로 조회됩니다" 기존 요청 취소 | - |
| A-193 | 점주 ID 형식 오류 | 문자 입력 | /settlements?tab=ledger | - | - | - | "점주 ID를 숫자로 입력해 주세요." 원장 미호출, 합계 미호출 | - |
| A-194 | 원장 필터 | 캠페인 ID, 변동 유형(충전, 예약, 소진, 예약 해제, 조정, 환불) | /settlements?tab=ledger | GET /v1/admin/point-ledger | - | - | 필터 결과, 커서 초기화. 캠페인 ID 문자면 "캠페인 ID를 숫자로..." | - |
| A-195 | 원장 기간 31일 초과 | 기간 선택 | /settlements?tab=ledger | - | - | - | "조회 기간을 시작일부터 종료일까지 최대 31일로..." 미호출 | - |
| A-196 | 원장 정렬 | 일시 헤더 | /settlements?tab=ledger (ledger.sortDir) | GET /v1/admin/point-ledger | - | - | 내림 <-> 오름, 커서 초기화 | - |
| A-197 | 원장 더 불러오기 | 더 불러오기, 크기 5/10/20/50 | /settlements?tab=ledger | GET /v1/admin/point-ledger | - | nextPageToken 있음 | 누적(원장 ID 중복 제거), "불러온 내역 N건" | - |
| A-198 | 원장 추가 실패 | 더 불러오기 -> 실패 | /settlements?tab=ledger | GET /v1/admin/point-ledger | COMMON-001 | - | 기존 행 유지, 아래 오류와 같은 커서 재시도 | - |
| A-199 | 원장 없음 | 조회 | /settlements?tab=ledger | GET /v1/admin/point-ledger | - | 내역 없음 또는 존재하지 않는 점주 | "조회된 포인트 변동 내역이 없습니다" (없는 점주도 오류 아님) | - |
| A-200 | 원장 상세 | 행 클릭 | 포인트 변동 상세 드로어 | - | - | - | 원장 상세(서빙 ID, 거래 ID, 출처, 사유) | - |
| A-201 | 합계 단위 전환 | 일별, 주별, 월별 | /settlements?tab=ledger (summary.period) | GET /v1/admin/point-ledger/summary | - | - | 기간을 단위 경계로 맞춤, 주는 월요일 시작 | - |
| A-202 | 합계 기간 한도 초과 | 92일, 53주, 13개월 초과 | /settlements?tab=ledger | - | - | - | "...합계는 최대 N까지 조회할 수 있습니다." 합계만 미호출 | - |
| A-203 | 합계 정렬, 실패 | 기간 헤더, 실패 | /settlements?tab=ledger | GET /v1/admin/point-ledger/summary | COMMON-002, COMMON-001 | - | 정렬 전환, 실패 시 표 오류 + 재시도 | - |
| A-204 | 양수 조정 | 포인트 수동 조정 -> 입력 -> 내용 확인 -> 조정 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | - | 점주 존재 | 201 ADJUST 원장, 탭 재마운트, "포인트 조정을 완료했습니다. (원장 ID: N)" | - |
| A-205 | 음수 조정(차감) | 음수 입력 -> 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | - | 잔액 >= 차감액 | 201 차감 원장 | - |
| A-206 | 캠페인 대상 조정 | 캠페인 ID와 대상 영업일 입력 -> 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | - | 오늘 이하 영업일 | 원장에 캠페인과 대상 영업일 기록 | 대상일은 KST 오늘까지 |
| A-207 | 조정 입력 검증 실패 | 잘못된 ID, 0 또는 비정수, 사유 공란 또는 255 초과, 캠페인 없이 영업일, 미래 영업일 | 포인트 수동 조정 모달 | - | - | - | 모달 오류 문구, 확인 단계로 넘어가지 않음 | - |
| A-208 | 확인 단계에서 수정 | 내용 확인 -> 수정 | 포인트 수동 조정 모달 | - | - | - | 입력 화면 복귀, 값 유지 | - |
| A-209 | 잔액 음수 차감 거부 | 큰 음수 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | BILLING-016 | 잔액 + 금액 < 0 | "조정 후 잔액이 음수가 되어 처리할 수 없습니다." | - |
| A-210 | 없는 점주 조정 | 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | BILLING-019 | 점주 없음(정지, 탈퇴 점주는 허용) | "점주를 찾을 수 없습니다." | - |
| A-211 | 응답 유실 후 재확정 | 같은 내용으로 다시 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | - | 첫 요청 기록됨 | 같은 requestId, 200 기존 원장 반환, 중복 기록 없음 | - |
| A-212 | 조정 잠금 대기 초과 | 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | BILLING-006 | 계정 행 잠금 5초 초과 | 재시도 안내 문구 | - |
| A-213 | 조정 서버 검증 실패 | 확정 | 포인트 수동 조정 모달 | POST /v1/admin/point-adjustments | COMMON-002 | 대상 영업일이 서버 기준 미래(자정 경계) 등 | "입력한 정보를 확인해 주세요." | 00:00 KST 경계 |

### 매출

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-214 | 매출 현황 기본 | 매출 현황 탭 | /settlements?tab=sales | GET /v1/admin/sales | - | - | 어제가 속한 달 1일 ~ 어제, 일별 | - |
| A-215 | 확정 기간 합계 | 조회 | /settlements?tab=sales | GET /v1/admin/sales | - | 모든 버킷 FINAL | 충전액, 소진액, 조정액, 환불액 합계와 마지막 구간의 미소진 잔액 | 01:00 집계 후 |
| A-216 | 미확정 포함 | 오늘 또는 미집계 날짜 포함 | /settlements?tab=sales | GET /v1/admin/sales | - | IN_PROGRESS 버킷 | 합계 대시 표시, "미확정 구간이 포함되어 기간 합계를 표시하지 않습니다." 행 배지 "집계 중" | 01:00 전, 집계 실패 |
| A-217 | 주별, 월별 | 단위 전환 | /settlements?tab=sales (sales.period) | GET /v1/admin/sales | - | - | WEEKLY, MONTHLY 버킷 | - |
| A-218 | 매출 기간 한도 초과 | 92일, 53주, 13개월 초과 또는 역전 | /settlements?tab=sales | - | - | - | 경고와 표 오류, 미호출 | - |
| A-219 | 매출 기간 서버 거부 | 조회 | /settlements?tab=sales | GET /v1/admin/sales | ANALYTICS-001 | 버킷 한도 초과 | "조회 기간과 집계 단위를 확인해 주세요." | - |
| A-220 | 기간별 내역 표 | 정렬, 페이지 | /settlements?tab=sales (sales.table.*) | - | - | - | 클라이언트 정렬과 페이지 | - |
| A-221 | 매출 실패 | 조회 | /settlements?tab=sales | GET /v1/admin/sales | COMMON-001 | - | 표 오류 + 재시도 | - |

### 정산 불일치

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-222 | 정산 검증 기본 | /settlements 진입 | /settlements (기본 탭 mismatches) | GET /v1/admin/settlement-mismatches | - | 불일치 존재 | 발견 시각 내림차순, 차액, 미해결 또는 해결됨 배지 | - |
| A-223 | 불일치 필터와 정렬 | 검증 종류, 해결 여부, 대상 영업일, 발견 시각 또는 영업일 정렬 | /settlements?tab=mismatches (mismatches.*) | GET /v1/admin/settlement-mismatches | - | - | 필터 결과. 역전 기간은 표 오류로 미호출 | - |
| A-224 | 해결된 불일치 상세 | 행 클릭 | 정산 불일치 상세 드로어 | - | - | resolutionType 있음 | 해결 방식(원장 보정, 과과금 정정), 해결 시각, 처리자, 정정 버튼 없음 | - |
| A-225 | 미해결 불일치 상세 | 행 클릭 | 정산 불일치 상세 드로어 | - | - | resolutionType null | "포인트 조정으로 정정" 버튼, 대상 ID 라벨(결제 ID 또는 캠페인 ID), 배치 실행 ID | - |
| A-226 | 결제 불일치 정정 | 정정 -> 점주 ID 직접 입력 -> 양수 -> 확정 | 정산 불일치 정정 모달 | POST /v1/admin/point-adjustments, GET /v1/admin/settlement-mismatches | - | PG_VS_CHARGE 미해결, 결제 점주와 입력 점주 같음 | 원장 생성, 불일치 MANUAL_ADJUSTMENT 로 해결, "정정을 완료했습니다. (원장 ID: N)" 1페이지 갱신 | - |
| A-227 | 캠페인 불일치 정정 | 정정 -> 점주 입력 -> 양수 -> 확정 | 정산 불일치 정정 모달 | POST /v1/admin/point-adjustments | - | CAMPAIGN_SPEND_VS_LEDGER 또는 CAMPAIGN_OVERSPEND 미해결 | 캠페인 ID와 영업일이 불일치 값으로 고정 입력, 해결 처리 | - |
| A-228 | 정정 음수 차단 | 음수 입력 | 정산 불일치 정정 모달 | - | - | - | "정산 불일치 정정은 포인트를 돌려주는 양수 조정만 가능합니다." | - |
| A-229 | 정정 점주 불일치 | 다른 점주 ID 로 확정 | 정산 불일치 정정 모달 | POST /v1/admin/point-adjustments | BILLING-022 | 대상 결제나 캠페인의 점주와 다름 | "정산 불일치 건이 입력한 점주의 내역이 아닙니다." | - |
| A-230 | 이미 해결된 불일치 | 확정 | 정산 불일치 정정 모달 | POST /v1/admin/point-adjustments | BILLING-021 | 다른 관리자가 먼저 정정 | "이미 해결된 정산 불일치 건입니다." 원장 롤백 | - |
| A-231 | 없는 불일치 | 확정 | 정산 불일치 정정 모달 | POST /v1/admin/point-adjustments | BILLING-020 | 불일치 없음 | "정산 불일치 건을 찾을 수 없습니다." | - |
| A-232 | 전일 소진 대사 생성 | 배치 실행 후 조회 | /settlements?tab=mismatches | GET /v1/admin/settlement-mismatches | - | 전날 카운터와 DEDUCT 합 차이, DEDUCT 합 > 예약액, 이틀 전 해제 뒤 DEDUCT 합 + RELEASE 합 > 예약액 | CAMPAIGN_SPEND_VS_LEDGER, CAMPAIGN_OVERSPEND 미해결 행. 재실행해도 종류, 캠페인, 날짜당 1행 | 00:00 BILLING_SPEND_RECON_PREV |
| A-233 | PG 대사 불일치 생성 | 배치 실행 후 조회 | /settlements?tab=mismatches | GET /v1/admin/settlement-mismatches | - | 전일 결제의 우리 승인액과 PG 승인액 다름 | PG_VS_CHARGE 미해결 행(대상 ID 결제 ID) | 01:30 BILLING_PG_VS_CHARGE_RECON |
| A-234 | PG 대사 자동 보정 | 배치 실행 후 조회 | /settlements?tab=mismatches | GET /v1/admin/settlement-mismatches | - | 양쪽 승인 같고 CHARGE 원장 없음 | CHARGE 자동 기록, LEDGER_CORRECTION 해결 상태(처리자 대시)로 남음 | 01:30 |
| A-235 | 불일치 없음, 실패 | 조회 | /settlements?tab=mismatches | GET /v1/admin/settlement-mismatches | COMMON-002, COMMON-001 | - | "조회된 정산 불일치 내역이 없습니다." 또는 표 오류 + 재시도 | - |

### 포인트 정책

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-236 | 예약 없는 정책 | 충전 정책 탭 | /settlements?tab=policy | GET /v1/admin/point-policy, GET /v1/admin/point-policy/history | - | next=null | 현재 정책, "예약된 변경이 없습니다...", "정책 변경 예약" 버튼 | - |
| A-237 | 예약 있는 정책 | 충전 정책 탭 | /settlements?tab=policy | GET /v1/admin/point-policy | - | 내일 effectiveDate 행 있음 | "내일 적용 예정" 카드, "예약 정책 수정" 버튼, 편집 초기값은 예약 정책 | - |
| A-238 | 정책 변경 예약 | 수정 -> 저장 | 충전 정책 변경 예약 모달 | PUT /v1/admin/point-policy/next, GET /v1/admin/point-policy, GET /v1/admin/point-policy/history | - | - | 내일 날짜로 upsert, 감사 로그, "내일 적용할 충전 정책을 저장했습니다." 이력 1페이지 갱신 | 적용일 = 서버 KST 내일 |
| A-239 | 같은 날 예약 덮어쓰기 | 예약 정책 수정 -> 저장 | 예약 정책 수정 모달 | PUT /v1/admin/point-policy/next | - | 이미 내일 예약 | 기존 예약 대체 | 같은 날 |
| A-240 | 정책 입력 검증 실패 | 최소 금액 0 이하 또는 비정수, 상품이 최소 미만, 사유 255자 초과 | 정책 모달 | - | - | - | 모달 오류 문구, 상품 칸 개별 오류, 상품 1개면 삭제 버튼 비활성 | - |
| A-241 | 정책 서버 검증 실패 | 저장 | 정책 모달 | PUT /v1/admin/point-policy/next | COMMON-002 | 엔티티 검증 위반 | "입력한 정보를 확인해 주세요." | - |
| A-242 | 정책 조회 실패 | 진입 | /settlements?tab=policy | GET /v1/admin/point-policy | COMMON-001 | 현재 정책 행 없음 등 | 오류 + 재시도, 편집 버튼 비활성 | - |
| A-243 | 정책 이력 | 페이지, 크기 | /settlements?tab=policy (policy.page, size) | GET /v1/admin/point-policy/history | - | - | 적용일 최신순(내일 예약 포함), 없으면 "등록된 정책 이력이 없습니다." 실패 시 재시도 | - |
| A-244 | 예약 정책 적용 | 자정 이후 재진입 | /settlements?tab=policy | GET /v1/admin/point-policy | - | 어제 저장한 예약 | 예약이 현재 정책으로 바뀌고 next=null. 열어 둔 화면은 새로고침 전까지 이전 값 | 00:00 KST |

### 부정 관리

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-245 | 무효 노출 기본 | 진입 | /fraud (무효 노출 탭) | GET /v1/admin/fraud/invalid-impressions | - | 집계된 무효 노출 | 오늘 포함 최근 31일, 무효 노출 수 내림차순, 사유별 요약 카드 2개 | 집계는 01:00 이후 전일분까지 |
| A-246 | 무효 사유 필터 | 만료와 알 수 없는 노출, 다른 회원의 노출 | /fraud (fraud.reason) | GET /v1/admin/fraud/invalid-impressions | - | - | 해당 사유만, 요약 카드 1개 | - |
| A-247 | ID 검색 | 캠페인 ID, 사용자 ID 입력 | /fraud (fraud.campaignId, fraud.memberId) | GET /v1/admin/fraud/invalid-impressions | - | - | 필터 결과. 비정수면 빈 상태에 "캠페인 ID와 사용자 ID는 양의 정수로..." 미호출 | - |
| A-248 | 무효 노출 정렬 | 사용자 ID, 캠페인 ID, 무효 노출 수 | /fraud (fraud.sortBy) | GET /v1/admin/fraud/invalid-impressions | - | - | 같은 열은 방향 전환, 다른 열은 내림차순 | - |
| A-249 | 부정 관리 기간 오류 | 기간 지움, 역전, 31일 초과 | /fraud | - | - | - | 빈 상태 영역에 기간 안내, 미호출 | - |
| A-250 | 캠페인 경고 탭 | 탭 전환 | /fraud (fraud.tab=warnings) | GET /v1/admin/fraud/campaign-warnings | - | 경고 판정된 캠페인 | 무효 비율 내림차순, 경고 있는 캠페인만, 무효 비율과 소수 회원 집중 배지 | - |
| A-251 | 경고 유형 필터 | 무효 비율, 소수 회원 집중 | /fraud (fraud.type) | GET /v1/admin/fraud/campaign-warnings | - | - | 해당 경고만 | - |
| A-252 | 경고 정렬, 비율 없음 | 캠페인 ID, 무효 비율, 상위 회원 점유율 | /fraud | GET /v1/admin/fraud/campaign-warnings | - | 유효 0이면 invalidRatio null | 정렬 결과, 비율 null 은 대시 표시. 비율은 100% 초과 가능 | - |
| A-253 | 경고에서 내역 보기 | "내역 보기" | /fraud -> 무효 노출 탭 | GET /v1/admin/fraud/invalid-impressions | - | - | campaignId 필터, 사유 전체, count 정렬, 1페이지 | - |
| A-254 | 부정 관리 페이지 | 페이지, 크기 | /fraud (fraud.page, size) | GET /v1/admin/fraud/invalid-impressions, GET /v1/admin/fraud/campaign-warnings | - | - | 페이지 이동 | - |
| A-255 | 부정 관리 실패 | 조회 | /fraud | GET /v1/admin/fraud/invalid-impressions, GET /v1/admin/fraud/campaign-warnings | ANALYTICS-001, COMMON-002, COMMON-001 | 서버 기간 거부, 잘못된 정렬, 서버 오류 | 표 오류 + 재시도(ANALYTICS-001도 "입력한 정보를 확인해 주세요."로 표시) | - |
| A-256 | 당일 데이터 미반영 | 오늘 발생 무효 노출 확인 | /fraud | GET /v1/admin/fraud/invalid-impressions, GET /v1/admin/fraud/campaign-warnings | - | 오늘 이벤트만 있음 | 집계 테이블 기준이라 0건. 다음 날 01:00 후 반영 | 01:00 DAILY_AGGREGATION |

### 공통 오류

| ID | 시나리오 | 단계 | 화면(route, 탭) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| A-257 | 관리자 권한 아님 | 보호 API 호출 | 모든 보호 화면 | (공통) /v1/admin 보호 엔드포인트 전체 | AUTH-006 | 관리자 역할 아닌 토큰 | "접근 권한이 없습니다." 재발급 시도 안 함 | - |
| A-258 | 서버 내부 오류 | 조회 또는 처리 | 모든 화면 | (공통) 호출 엔드포인트 전체 | COMMON-001 | 처리되지 않은 예외 | "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." 또는 기능별 문구 | - |
| A-259 | 잘못된 요청 파라미터 | 비정상 URL 값이 API 까지 전달 | 모든 화면 | (공통) 호출 엔드포인트 전체 | COMMON-002, COMMON-003 | 화면 검증을 우회한 값 | "입력한 정보를 확인해 주세요." | - |
| A-260 | 네트워크 단절 | 조회 또는 처리 | 모든 화면 | (공통) 호출 엔드포인트 전체 | - | 연결 불가 | "서버에 연결하지 못했습니다..." 계열 문구 | - |
| A-261 | 응답 형식 불일치 | 성공 응답이지만 검증 실패 | 모든 화면 | (공통) 호출 엔드포인트 전체 | - | 필드 누락, 상태 코드 불일치, code != SUCCESS | "...응답 형식을 확인할 수 없습니다." | - |
| A-262 | 정의되지 않은 주소 | /없는주소 | * -> /dashboard | - | - | 인증됨 | 대시보드로 교체 이동. 루트 / 도 /dashboard | - |
| A-263 | API 주소 미설정 | 빌드 환경 변수 누락 | 모든 화면 | - | - | VITE_API_BASE_URL 없음 | "VITE_API_BASE_URL 환경변수를 설정해 주세요." | - |
| A-264 | 일시 장애 재시도 대기 | 503 응답 | 모든 화면 | (공통) 호출 엔드포인트 전체 | AUTH-002, BILLING-006, POSTER-004, POSTER-012 | - | 503 문구 뒤 "N초 후 다시 시도해 주세요." (헤더 없으면 1초) | Retry-After |
| A-265 | 테이블 개인 설정 | 열 순서, 너비, 숨김, 밀도 변경 | 모든 목록 화면 | - | - | adminId | 계정별로 브라우저에 저장, 로그아웃 후에도 보존 | - |
| A-266 | 테마 전환 | 사이드바 테마 토글 | 공통 레이아웃 | - | - | - | 라이트 <-> 다크 전환(동작 줄이기 설정 시 전환 효과 없음) | - |

## 대조

### (a) 관리자 route 와 탭 -> 시나리오

| route, 탭 | 시나리오 ID |
|---|---|
| /login | A-001~A-017, A-019~A-021, A-025 |
| / (index) -> /dashboard, * -> /dashboard | A-262 |
| 공통 레이아웃(사이드바, 로그아웃 모달, 테마) | A-007, A-008, A-023~A-027, A-266 |
| /dashboard 처리할 항목 | A-045~A-048 |
| /dashboard 점주 현황 탭 | A-049~A-052, A-057~A-063 |
| /dashboard 사용자 현황 탭(이용 추이, 비교, 지표 선택) | A-052~A-061, A-064 |
| /dashboard 누적과 잔액 현황 | A-065~A-070 |
| /campaigns 목록 | A-090~A-100, A-108 |
| /campaigns 상세 드로어(campaigns.detail) | A-101~A-107 |
| /templates 목록(생성 모달, 활성화 모달, 삭제 모달) | A-109~A-112, A-149~A-153 |
| /templates/new (작성 모드) | A-111, A-113~A-128 |
| /templates/drafts/:templateId/edit (초안 편집 모드, 저장 모달, 버전 이동 모달) | A-114, A-129~A-145, A-147, A-154 |
| /templates/:templateId (게시 상세, DRAFT 이면 편집) | A-146~A-148 |
| /settlements 정산 검증 탭(기본) | A-222~A-235, A-048 |
| /settlements 환불 요청 탭 | A-174~A-189 |
| /settlements 결제 내역 탭 | A-156~A-173 |
| /settlements 포인트 변동 내역 탭(합계 섹션, 수동 조정 모달, 상세 드로어) | A-190~A-213 |
| /settlements 매출 현황 탭 | A-214~A-221, A-063 |
| /settlements 충전 정책 탭(정책 모달, 이력) | A-236~A-244 |
| /members 점주 목록 탭(기본) | A-081~A-089 |
| /members 사용자 목록 탭, ?userId 확인 대상 | A-071~A-080 |
| /fraud 무효 노출 탭 | A-245~A-249, A-253~A-256, A-064 |
| /fraud 캠페인 경고 탭 | A-250~A-256 |
| /admin-accounts (목록, 발급 모달) | A-029~A-044 |

### (b) 관리자 앱이 호출하는 엔드포인트 -> 시나리오

| 엔드포인트 | 시나리오 ID |
|---|---|
| POST /v1/admin/auth/tokens | A-007~A-009, A-013~A-016 |
| POST /v1/admin/auth/tokens:refresh | A-001~A-006, A-018~A-022, A-042 |
| DELETE /v1/admin/auth/tokens | A-024~A-026 |
| GET /v1/admin/admins | A-030~A-035 |
| POST /v1/admin/admins | A-038~A-044 |
| GET /v1/admin/dashboard/members | A-052, A-053, A-057~A-062, A-065~A-070 |
| GET /v1/admin/dashboard/stores | A-049~A-052, A-057~A-062, A-065~A-070 |
| GET /v1/admin/dashboard/funnel | A-052~A-062 |
| GET /v1/admin/sales | A-049~A-052, A-063, A-065~A-069, A-214~A-221 |
| GET /v1/admin/settlement-mismatches | A-045~A-048, A-222~A-235 |
| GET /v1/admin/members | A-071~A-080 |
| GET /v1/admin/owners | A-081~A-089 |
| GET /v1/admin/campaigns | A-090~A-097, A-100 |
| GET /v1/admin/campaigns/{campaignId} | A-101~A-105 |
| GET /v1/admin/campaign-reports | A-090, A-094, A-098, A-099, A-101, A-107, A-108 |
| GET /v1/admin/templates | A-109, A-110, A-112, A-152 |
| POST /v1/admin/templates | A-114~A-128 |
| GET /v1/admin/templates/{templateId} | A-129, A-131, A-135, A-146~A-148 |
| GET /v1/admin/templates/{templateId}/versions | A-129~A-131, A-135, A-143 |
| POST /v1/admin/templates/{templateId}/versions | A-117~A-127, A-131~A-138, A-141 |
| POST /v1/admin/templates/{templateId}/publish | A-142~A-145 |
| PATCH /v1/admin/templates/{templateId}/active | A-149~A-151 |
| DELETE /v1/admin/templates/{templateId} | A-152, A-153 |
| GET /v1/admin/payments | A-156~A-163, A-165, A-171, A-173 |
| POST /v1/admin/payments/{paymentId}:recheck | A-165~A-170, A-172, A-173 |
| GET /v1/admin/refund-requests | A-174~A-177, A-179, A-189 |
| POST /v1/admin/refund-requests/{refundRequestId}:approve | A-179, A-182~A-186 |
| POST /v1/admin/refund-requests/{refundRequestId}:reject | A-184, A-185, A-187 |
| GET /v1/admin/point-ledger | A-191, A-194, A-196~A-199 |
| GET /v1/admin/point-ledger/summary | A-191, A-201~A-203 |
| POST /v1/admin/point-adjustments | A-204~A-213, A-226~A-231 |
| GET /v1/admin/point-policy | A-236~A-238, A-242, A-244 |
| GET /v1/admin/point-policy/history | A-236, A-238, A-243 |
| PUT /v1/admin/point-policy/next | A-238, A-239, A-241 |
| GET /v1/admin/fraud/invalid-impressions | A-064, A-245~A-249, A-253~A-256 |
| GET /v1/admin/fraud/campaign-warnings | A-250~A-252, A-254~A-256 |

### (c) 위 엔드포인트에서 나올 수 있는 오류 코드 -> 시나리오

| 오류 코드 | 발생 엔드포인트 | 시나리오 ID |
|---|---|---|
| AUTH-001 | POST /v1/admin/auth/tokens | A-013 |
| AUTH-002 | 로그인, 재발급, 로그아웃, GET 과 POST /v1/admin/admins(REQUIRED 정책) | A-003, A-015, A-022, A-026, A-034, A-043, A-264 |
| AUTH-003 | POST /v1/admin/auth/tokens:refresh | A-001, A-020, A-042 |
| AUTH-004 | POST /v1/admin/auth/tokens:refresh | A-005, A-021 |
| AUTH-005 | 모든 보호 API, DELETE /v1/admin/auth/tokens | A-018, A-019, A-025, A-042 |
| AUTH-006 | 모든 보호 API, admins 서비스 재검사 | A-033, A-041, A-257 |
| (429 본문 없음) | POST /v1/admin/auth/tokens, POST /v1/admin/auth/tokens:refresh | A-006, A-014 |
| ADMIN-001 | POST /v1/admin/admins | A-040 |
| ANALYTICS-001 | dashboard 3종, sales, fraud 2종, campaign-reports(기간 지정 시) | A-061, A-069, A-219, A-255 |
| CAMPAIGN-012 | GET /v1/admin/campaigns/{campaignId} | A-105 |
| BILLING-004 | POST /v1/admin/payments/{paymentId}:recheck | A-170 |
| BILLING-005 | POST /v1/admin/payments/{paymentId}:recheck | A-169 |
| BILLING-006 | approve, point-adjustments | A-186, A-212, A-264 |
| BILLING-010 | approve | A-182 |
| BILLING-013 | approve | A-183 |
| BILLING-014 | approve, reject | A-185 |
| BILLING-015 | approve, reject | A-184 |
| BILLING-016 | point-adjustments | A-209 |
| BILLING-018 | point-adjustments | 화면 흐름으로는 발생하지 않음. 내용이 바뀌면 프론트가 새 requestId 를 만든다. (d) 참고 |
| BILLING-019 | point-adjustments | A-210 |
| BILLING-020 | point-adjustments(mismatchId) | A-231 |
| BILLING-021 | point-adjustments(mismatchId) | A-230 |
| BILLING-022 | point-adjustments(mismatchId) | A-229 |
| POSTER-001 | POST templates, POST versions | A-127 |
| POSTER-002 | POST versions | A-132 |
| POSTER-003 | POST templates | A-116 |
| POSTER-004 | POST templates, POST versions | A-122 |
| POSTER-005 | GET 상세, GET versions, POST versions, publish, active, DELETE | A-134, A-147, A-151, A-153 |
| POSTER-006 | POST versions, DELETE | A-133, A-153 |
| POSTER-007 | publish | A-144 |
| POSTER-008 | publish | A-143 |
| POSTER-009 | active | A-151 (화면은 게시된 템플릿에만 토글을 보여 방어 경로) |
| POSTER-010 | POST templates, POST versions | A-125 |
| POSTER-011 | POST templates, POST versions | A-126 |
| POSTER-012 | POST templates, POST versions | A-123 |
| POSTER-013 | POST templates, POST versions | A-124 |
| POSTER-014 | POST templates, POST versions | A-121 |
| POSTER-022 | POST templates, POST versions | A-117 |
| POSTER-023 | POST templates, POST versions | A-118 |
| POSTER-024 | POST templates, POST versions | A-119 |
| POSTER-025 | POST templates, POST versions | A-120 |
| COMMON-001 | 전체 | A-035, A-047, A-258 외 실패 행 |
| COMMON-002 | 목록 파라미터 검증, owners 상호 1만 건, admins 와 정책, 조정 DTO 검증 | A-044, A-084, A-213, A-241, A-259 |
| COMMON-003 | 경로 변수와 enum 형식 오류 | A-148, A-259 |

### (d) 코드에는 있지만 시나리오로 만들지 않은 것

| 항목 | 이유 |
|---|---|
| GET /v1/admin/aggregation-runs, POST /v1/admin/aggregations:recalculate, GET /v1/admin/batch-executions, POST /v1/admin/batch-executions/{jobName}/{businessDate}:retry | 백엔드에 관리자 API 로 있으나 관리자 앱 api/ 모듈에 호출 코드가 없다. 따라서 ANALYTICS-002, ANALYTICS-003, OPS-001~OPS-004 도 관리자 앱에서 나오지 않는다 |
| BILLING-018 IDEMPOTENCY_KEY_CONFLICT | 프론트가 조정 내용(JSON)이 바뀌면 새 requestId 를 발급하므로 같은 키에 다른 내용이 가지 않는다 |
| COMMON-004, COMMON-005, COMMON-006, COMMON-007 | 앱이 고정 경로, 고정 메서드, JSON 본문만 보내고 업로드가 없다 |
| MismatchResolutionType CACHE_REBUILD | 라벨 "캐시 재집계"는 있으나 운영 코드에서 이 값을 쓰는 곳이 없다(시드 데이터에만 있음). 전일 소진 대사는 resolution_type 을 비워 둔다 |
| RefundStatus APPROVED | 승인하면 바로 COMPLETED 로 바뀌어 APPROVED 가 저장되지 않는다. 필터 결과가 항상 비는 것만 A-176 에 남겼다 |
| 템플릿 POSTER-015~POSTER-021 | 점주 포스터 API 전용이라 관리자 엔드포인트에서 나오지 않는다 |
| 00:00 묶음의 BILLING_RESERVE, BILLING_DAILY_RELEASE, BILLING_FUNDING_JUDGE, ADSERVING_*, 02:00~03:00 쿠폰과 로그 작업, 11:00, 14:00 쿠폰 작업, 5분 쿠폰 만료 | 관리자 화면에 직접 드러나는 상태 전이가 없다. 결과는 원장(A-191)이나 캠페인 상태(A-093)로 간접 노출될 뿐 관리자 목표가 아니다 |
| 04:00 EVENT_LOG_RETENTION, 02:00 ADSERVING_LOG_RETENTION | 원본 로그 삭제이며 관리자 화면은 집계 테이블만 읽는다 |
| 배치 heartbeat 와 takeover | 서버 내부 동작이다 |
| 캠페인 관리자 중단(pausedReason ADMIN) | 상태 표시만 있고 관리자 앱에 캠페인 상태를 바꾸는 API 호출이 없다 |
| 회원과 점주 정지, 탈퇴 처리 | 목록 조회만 있고 상태 변경 API 가 없다 |
| Storybook stories 파일 | 화면 시나리오가 아니다 |
