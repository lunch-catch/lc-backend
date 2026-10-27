# 점주 앱 시나리오

범위: lc-frontend `apps/owner`. 시나리오 266개 (O-001 ~ O-266).

기준 코드: lc-backend develop `9f4ab523`, lc-frontend `3f3e056` (2026-10-22). 개요와 갱신 방법은 [README](README.md)에 있다.

근거: 프론트 `lc-frontend/apps/owner/src` (router.tsx, api/*, features/*), 백엔드 `wt-loadtest-pg` develop (`com.lunchcatch`), 인벤토리 endpoints.txt, errors.txt, jobs.txt.
표기: "mock" 은 서버를 부르지 않는 프론트 내부 처리다. 오류 코드 열의 "429" 는 본문 없이 처리되는 요청 제한(클라이언트가 code 를 읽지 않음)이다. "응답 없음" 은 네트워크 끊김, 10초 타임아웃, 프록시 502 처럼 code 가 없는 실패다.

### 가입/로그인

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-001 | 앱 시작 시 로그인 상태 확인 중 | 앱 로드 -> AuthProvider 가 내 정보 조회 | 모든 route | GET /v1/owner/me | - | 아무 상태 | "로그인 정보를 확인하는 중이에요" 화면, 확인 전에는 어떤 화면으로도 보내지 않음 | - |
| O-002 | 앱 시작, 로그인 전 | 쿠키 없음 -> me 401 -> 재발급 401 -> guest | / -> /login | GET /v1/owner/me, POST /v1/owner/auth/tokens:refresh | AUTH-005, AUTH-003 | 인증 쿠키 없음 또는 만료 | / 는 /login 으로, 보호 화면은 RequireAuth 가 /login 으로 보냄 | - |
| O-003 | 앱 시작, 입점 신청 중(ONBOARDING) | me 200 status ONBOARDING | /signup/terms -> 이어갈 단계 | GET /v1/owner/me, GET /v1/owner/store | - | ONBOARDING 점주 쿠키 | onboarding 영역만 접근, 진행 상태의 resumeStep 단계로 이동 | - |
| O-004 | 앱 시작, 입점 완료(ACTIVE) | me 200 status ACTIVE | /home | GET /v1/owner/me | - | ACTIVE 점주 쿠키 | active 영역 접근, tutorialViewed 에 따라 튜토리얼 (O-082) | - |
| O-005 | 앱 시작, 정지 또는 탈퇴 계정 쿠키 | me 200 status SUSPENDED/WITHDRAWN | /login | GET /v1/owner/me | - | 쿠키는 유효하나 계정 상태 SUSPENDED 또는 WITHDRAWN | 로그인 전으로 간주해 /login | - |
| O-006 | 앱 시작, 상태 확인 실패 | me 5xx 또는 응답 없음 | 모든 route | GET /v1/owner/me | COMMON-001, AUTH-002(재발급 503), 응답 없음 | 서버 장애 또는 네트워크 단절 | "로그인 정보를 확인하지 못했어요" + 다시 시도, 자동 재시도 없음 | 요청 10초 초과 시 끊음 |
| O-007 | 앱 시작, 재발급 요청 제한 | me 401 -> 재발급 429 | 모든 route | GET /v1/owner/me, POST /v1/owner/auth/tokens:refresh | 429 | 같은 IP 에서 로그인/재발급 1분 한도 초과 | 실패 화면에 남은 초 표시, 카운트다운 동안 다시 시도 버튼 비활성 | Retry-After+1초, 헤더 없으면 60초 |
| O-008 | 로그인 전 사용자가 보호 화면 진입 | 주소 직접 입력 | /home, /campaigns/*, /store/*, /points/*, /qr-scan, /signup/* | - | - | guest | /login 으로 replace | - |
| O-009 | 로그인 상태와 맞지 않는 화면 진입 | ONBOARDING 이 /home, ACTIVE 가 /signup/*, 로그인 상태가 /login 또는 /signup | 해당 route | - | - | ONBOARDING 또는 ACTIVE | ONBOARDING -> /signup/terms, ACTIVE -> /home | - |
| O-010 | 회원가입 입력 검증 | 이메일 형식, 비밀번호 10~20자, 비밀번호 확인 일치 | /signup | - | - | guest | 필드를 벗어난 뒤 오류 표시, 오류가 있으면 제출 막음 | - |
| O-011 | 회원가입 성공 후 자동 로그인 | 가입 -> 같은 계정으로 로그인 -> 약관 단계 | /signup -> /signup/terms | POST /v1/owners, POST /v1/owner/auth/tokens | - | 미가입 이메일 | ONBOARDING 으로 로그인되어 약관 동의 단계로 이동 | - |
| O-012 | 회원가입 이메일 중복 | 가입 409 | /signup | POST /v1/owners | OWNER-001 | 이미 가입된 이메일 | 이메일 칸 "이미 가입된 이메일입니다.", 이메일을 바꾸기 전까지 제출 막음 | - |
| O-013 | 회원가입 서버 입력 거절 | 가입 400 | /signup | POST /v1/owners | COMMON-002, COMMON-003 | 서버 검증(@Email, 255자 등) 위반 | "입력한 정보를 다시 확인해 주세요." | - |
| O-014 | 회원가입 서버 오류 | 가입 5xx 또는 응답 없음 | /signup | POST /v1/owners | COMMON-001, 응답 없음 | 서버 장애 | "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요." | - |
| O-015 | 가입은 됐으나 이어진 로그인 실패 | 가입 201 -> 로그인 실패 | /signup | POST /v1/owners, POST /v1/owner/auth/tokens | AUTH-001, AUTH-002, 429, 응답 없음 | 가입 직후 로그인 실패 | "가입은 완료되었습니다. 로그인 화면에서 다시 로그인해 주세요." | - |
| O-016 | 로그인 입력 검증 | 이메일 비어 있음/형식 오류, 비밀번호 비어 있음 | /login | - | - | guest | 필드 오류 표시, 제출 막음 (길이 규칙은 검사하지 않음) | - |
| O-017 | 로그인 성공 ACTIVE | 로그인 200 ACTIVE | /login -> /home | POST /v1/owner/auth/tokens | - | ACTIVE 계정, tutorialViewed 포함 응답 | /home 으로 replace, 미열람이면 튜토리얼 | - |
| O-018 | 로그인 성공 ONBOARDING | 로그인 200 ONBOARDING | /login -> /signup/terms -> 이어갈 단계 | POST /v1/owner/auth/tokens, GET /v1/owner/store | - | ONBOARDING 계정 | 약관 주소로 들어간 뒤 resumeStep 단계로 이동 | - |
| O-019 | 로그인 인증 실패 | 계정 없음, 비밀번호 불일치, 정지/탈퇴 | /login | POST /v1/owner/auth/tokens | AUTH-001 | 해당 계정 상태 | "로그인에 실패했습니다 / 이메일 또는 비밀번호가 올바르지 않습니다." 사유 구분 없음 | - |
| O-020 | 로그인 서버 형식 거절 | 비밀번호 10자 미만 등 서버 @Size 위반 | /login | POST /v1/owner/auth/tokens | COMMON-002, COMMON-003 | 짧은 비밀번호 입력 | O-019 와 같은 문구 | - |
| O-021 | 로그인 요청 제한 | 로그인 429 | /login | POST /v1/owner/auth/tokens | 429 | 1분 고정 구간 한도 초과 | "요청이 많아 잠시 후 다시 시도해 주세요." + "N초 후에 다시 시도할 수 있어요", 끝나면 안내 지움 | Retry-After+1초, 없으면 60초 |
| O-022 | 로그인 서버 오류 | 로그인 5xx 또는 응답 없음 | /login | POST /v1/owner/auth/tokens | AUTH-002, COMMON-001, 응답 없음 | DB 또는 토큰 저장소 장애 | "일시적인 오류가 발생했습니다..." | - |
| O-023 | 로그아웃 성공 | 가게 관리 -> 로그아웃 시트 -> 로그아웃 | /store -> /login | DELETE /v1/owner/auth/tokens | - | ACTIVE 로그인 | 204, 로그인 상태 지움, /login 으로 replace | - |
| O-024 | Access 만료 상태 로그아웃 | 로그아웃 401 AUTH-005 -> 재발급 -> 재요청, 재발급도 401 이면 성공 처리 | /store -> /login | DELETE /v1/owner/auth/tokens, POST /v1/owner/auth/tokens:refresh | AUTH-005, AUTH-003, AUTH-004 | Access 30분 경과 | 재발급 성공 시 다시 보내 폐기, Refresh 도 없으면 지울 로그인이 없어 성공으로 보고 /login | Access 30분 |
| O-025 | 로그아웃 실패 | 로그아웃 503 또는 403 또는 응답 없음 | /store | DELETE /v1/owner/auth/tokens | AUTH-002, AUTH-006, 응답 없음 | 토큰 저장소 장애 | 시트에 "로그아웃하지 못했어요. 잠시 후 다시 시도해 주세요.", 로그인 유지 | - |
| O-026 | 로그아웃 시트 닫기 | 닫기 또는 배경 탭, 처리 중에는 닫기 막힘 | /store | - | - | ACTIVE | 시트 닫힘, 처리 중 배경/드래그/Esc 무시 | - |

### 입점 등록 단계별

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-027 | 입점 진행 상태 불러오기 | 단계 화면 진입 시 저장값과 진행 상태 조회 | /signup/* | GET /v1/owner/store | - | ONBOARDING | "입점 신청 정보를 불러오는 중이에요" 후 값 복원 | - |
| O-028 | 입점 진행 상태 불러오기 실패 | 조회 실패 | /signup/* | GET /v1/owner/store | OWNER-002, COMMON-001, 응답 없음 | 서버 장애 또는 상태 불일치 | "입점 신청 정보를 불러오지 못했어요" + 다시 시도 (빈 값 덮어쓰기 방지) | - |
| O-029 | 이어서 진행 (resumeStep 별) | resumeStep TERMS/STORE_INFO/LOCATION/HOURS/IMAGES/MENU 로 이동 | /signup/terms, /signup/store, /signup/location, /signup/hours, /signup/images, /signup/menu | GET /v1/owner/store | - | 각 단계까지 저장된 진행 상태 | 들어온 주소와 무관하게 서버 단계로 replace, 약관 완료면 4개 모두 체크, 위치는 도로명만, 사진은 saved, 메뉴는 id 포함 | - |
| O-030 | 이전 단계로 이동 | 하단 이전 또는 상단 뒤로 (2단계 이후) | /signup/* | - | - | ONBOARDING | 이전 단계로 이동, 입력값 유지, 진행 중 업로드는 끝까지 진행 | - |
| O-031 | 첫 단계에서 뒤로 (입점 중단) | 약관 단계 뒤로 -> 로그아웃 | /signup/terms -> /login | DELETE /v1/owner/auth/tokens | AUTH-002, 응답 없음 | ONBOARDING | 성공 시 /login replace, 실패 시 토스트 "로그아웃에 실패했습니다 / 잠시 후 다시 시도해 주세요." | - |
| O-032 | 약관 동의 입력 | 모두 동의, 개별 동의, 상세 시트 열기 | /signup/terms | - (약관 본문 mock) | - | ONBOARDING, 약관 4개 모두 필수 | 안내 "필수 약관 4개 중 n개에 동의했어요", 모두 동의 전 다음 비활성 | - |
| O-033 | 약관 동의 저장 성공 | 다음 -> 저장 -> 버전 수신 | /signup/terms -> /signup/store | PUT /v1/owner/terms-agreements | - | 약관 미동의, resumeStep TERMS | 진행 상태 생성, 버전 받아 가게 정보 단계로 | - |
| O-034 | 약관 이미 동의함 | 약관 단계로 돌아와 다음 -> 409 -> 완료 여부 확인 | /signup/terms -> /signup/store | PUT /v1/owner/terms-agreements, GET /v1/owner/store | TERMS-001 | 약관 동의 기록 있음 | TERMS 완료면 현재 버전으로 그대로 진행, 확인 실패면 "저장에 실패했습니다 / 잠시 후 다시 시도해 주세요." | - |
| O-035 | 약관 저장 단계 불일치 | 동의 기록 없는데 resumeStep 이 TERMS 아님 | /signup/terms | PUT /v1/owner/terms-agreements, GET /v1/owner/store | STORE-002 | 진행 상태 꼬임 | 진행 상태 다시 불러와 이어갈 단계로 이동 + "앞 단계가 저장되지 않아 이어서 진행할 단계로 옮겼어요." | - |
| O-036 | 가게 기본 정보 입력 검증 | 상호명, 대표자, 전화(0 시작 9~11자리), 업종, 사업자번호 10자리 | /signup/store | - | - | ONBOARDING | 하이픈 자동 서식, 벗어난 칸 오류, "필수 항목 5개 중 n개" | - |
| O-037 | 가게 기본 정보 저장 성공 | 다음 -> 첫 저장은 가게 초안 생성, 재저장은 덮어씀 | /signup/store -> /signup/location | PUT /v1/owner/store/basic | - | resumeStep STORE_INFO 이후, 사업자번호 0000000000 아님 | 버전 갱신, 위치 단계로 | - |
| O-038 | 사업자 검증 실패 번호 (mock 검증기) | 0000000000 저장 -> 200 이지만 단계 미완료 -> 위치 저장 시 거절 | /signup/store -> /signup/location -> /signup/store | PUT /v1/owner/store/basic, PUT /v1/owner/store/location, GET /v1/owner/store | STORE-002 | 사업자번호 0000000000 | 화면은 위치 단계로 넘어가지만 위치 저장에서 거절, 다시 불러와 가게 정보 단계로 되돌림 + "앞 단계가 저장되지 않아..." (검증 실패 안내 없음) | - |
| O-039 | 단계 저장 버전 충돌 | 다른 기기/창에서 먼저 저장 | /signup/store, /signup/location, /signup/hours, /signup/images, /signup/menu | PUT /v1/owner/store/basic, PUT /v1/owner/store/location, PUT /v1/owner/store/hours, POST /v1/owner/store-images:complete, POST /v1/owner/store-menus:complete, POST /v1/owner/store:complete, GET /v1/owner/store | STORE-001 | 서버 version 이 화면 version 보다 큼 | 다시 불러와 이어갈 단계로 + "다른 곳에서 먼저 저장했어요. 저장된 내용을 다시 불러왔으니 확인해 주세요." | - |
| O-040 | 단계 저장 후 다시 불러오기 실패 | STORE-001/002/003/015 -> 재조회 실패 | /signup/* | GET /v1/owner/store | STORE-001, STORE-002, STORE-003, STORE-015 | 재조회 중 장애 | "저장된 내용을 불러오지 못했어요. 잠시 후 다시 시도해 주세요." | - |
| O-041 | 단계 저장 기타 거절 | 가게 연결 불가, 형식 오류, 서버 오류 | /signup/store, /signup/location, /signup/hours | PUT /v1/owner/store/basic, PUT /v1/owner/store/location, PUT /v1/owner/store/hours | STORE-004, COMMON-002, COMMON-001, 응답 없음 | 확정된 가게 또는 잘못된 값 | 토스트 "저장에 실패했습니다 / 잠시 후 다시 시도해 주세요.", 입력값 유지 | - |
| O-042 | 진행 상태 없음 | 진행 상태 없이 단계 저장 | /signup/store, /signup/location, /signup/hours, /signup/images, /signup/menu | PUT /v1/owner/store/basic 등 단계 저장 API, GET /v1/owner/store | STORE-003 | 약관 단계 없이 진행 상태 미생성 | 다시 불러와 이어갈 단계로 + "앞 단계가 저장되지 않아..." | - |
| O-043 | 매장 위치 검색 | 검색어 입력 -> 카카오 키워드 검색 | /signup/location | - (카카오 SDK) | - | 카카오 키 설정 | 검색 중 표시, 결과 목록, 결과 없음 안내, 연달아 검색하면 마지막 결과만, 검색어 지우고 벗어나면 목록 닫힘 | - |
| O-044 | 도로명 주소 없는 장소 | 결과 중 도로명 없음 | /signup/location | - | - | 지번만 있는 장소 | 지번 표시, "도로명 주소가 없는 장소라 선택할 수 없어요", 선택 불가 | - |
| O-045 | 장소 검색 불가 | SDK 로드 실패 또는 검색 ERROR | /signup/location | - | - | 키 없음, 도메인 미등록, 네트워크 | "지금은 장소를 검색할 수 없어요..." 또는 "검색하지 못했어요. 다시 시도해주세요." | - |
| O-046 | 장소 선택과 초기화 | 결과 선택 -> 지도 핀 -> 초기화 | /signup/location | - | - | ONBOARDING | 지도 미리보기, 도로명/상호/지번 표시, 초기화하면 선택 해제와 다음 비활성 | - |
| O-047 | 매장 위치 저장 성공 | 다음 -> 장소 ID, 도로명, 좌표 저장 | /signup/location -> /signup/hours | PUT /v1/owner/store/location | - | resumeStep LOCATION 이후, 약관 동의, 사업자 VERIFIED | 버전 갱신, 영업시간 단계로 | - |
| O-048 | 매장 위치 저장 앞 단계 미완료 | 약관 철회, 사업자 미검증, resumeStep 이 앞 | /signup/location | PUT /v1/owner/store/location, GET /v1/owner/store | STORE-002 | 해당 상태 | 다시 불러와 이어갈 단계로 + 안내 (O-035 와 같음) | - |
| O-049 | 영업시간 입력 검증 | 요일 선택, 시작/종료 시각 | /signup/hours | - | - | ONBOARDING | "영업 요일을 하루 이상 골라주세요", "영업 시작/종료 시간을 입력해주세요", 종료가 시작보다 이르면 "시작 시간보다 늦어야 해요", 점심 피크 안내는 고정 문구 | 자정 넘김 영업 불가 |
| O-050 | 영업시간 저장 성공 | 다음 -> 영업 요일마다 같은 시각 HH:mm:ss | /signup/hours -> /signup/images | PUT /v1/owner/store/hours | - | resumeStep HOURS 이후 | 고르지 않은 요일은 휴무로 저장, 사진 단계로 | - |
| O-051 | 영업시간 저장 거절 | 버전/단계/가게/형식 | /signup/hours | PUT /v1/owner/store/hours, GET /v1/owner/store | STORE-001, STORE-002, STORE-003, STORE-004, COMMON-002 | 해당 상태 | 001/002/003 은 재조회 이동 + 안내, 004/002(형식) 는 기본 저장 실패 토스트 | - |
| O-052 | 대표 이미지 업로드 성공 | 사진 선택 -> 축소 -> 주소 발급 -> S3 PUT -> 업로드 확인 | /signup/images | POST /v1/owner/store-images:prepareUpload, POST /v1/owner/store-images:confirmUpload | - | 진행 상태와 초안 가게 있음, jpg/png 10MB 이하 | 진행률 표시 후 attached, "대표 이미지를 등록했어요" | 업로드 주소 10분 유효 |
| O-053 | 이미지 파일 사전 거름 | jpg/png 아님 또는 10MB 초과 선택 | /signup/images, /signup/menu, /campaigns/:id/edit/poster | - | - | 해당 파일 | "jpg, png 파일만 등록할 수 있어요" 또는 "10MB 이하 사진만 등록할 수 있어요", 업로드하지 않음 | - |
| O-054 | 대표 이미지 업로드 실패 | 발급 또는 S3 또는 확인 실패 | /signup/images | POST /v1/owner/store-images:prepareUpload, POST /v1/owner/store-images:confirmUpload | STORE-005, STORE-006, STORE-002, STORE-003, STORE-004, STORE-009, 응답 없음 | 서버 거절 또는 S3 실패 | "업로드 실패" + 다시 시도, "대표 이미지가 비었어요. 다시 올려 주세요.", 다음 비활성 | 발급 순간 기존 로고 빠짐 |
| O-055 | 실패한 업로드 다시 시도 | 다시 시도 -> 올린 상태면 확인만, 9분 이내면 같은 주소, 아니면 새 주소 | /signup/images, /signup/menu, /campaigns/:id/edit/poster | POST /v1/owner/store-images:confirmUpload, POST /v1/owner/store-images:prepareUpload 등 해당 종류 API | - | 직전 업로드가 failed | 실패한 지점부터 다시 진행 | 9분 지나면 주소 재발급 |
| O-056 | S3 가 업로드 주소 거절 | S3 PUT 403 | /signup/images, /signup/menu, /campaigns/:id/edit/poster | - (S3) | - | 주소 만료 또는 서명 불일치 | 실패 처리, 다음 시도는 주소부터 새로 받음 | 10분 만료 |
| O-057 | 다른 창에서 대표 이미지 교체 | 이 업로드 확인 403 -> 다시 시도 시 새 주소 | /signup/images | POST /v1/owner/store-images:confirmUpload, POST /v1/owner/store-images:prepareUpload | STORE-008 | 더 나중에 받은 로고 업로드 있음 | 실패 표시, 다시 시도하면 기존 티켓 버리고 새로 발급 | - |
| O-058 | 매장 사진 여러 장 업로드 | 최대 3장 선택 -> 한 장씩 차례로 | /signup/images | POST /v1/owner/store-images:prepareUpload, POST /v1/owner/store-images:confirmUpload | - | 기존 매장 사진 3장 미만 | 순서대로 자리 확보, 올리는 중 추가/다시 시도 막힘, "사진을 올리고 있어요" | - |
| O-059 | 매장 사진 한도 초과 | 발급 422 | /signup/images | POST /v1/owner/store-images:prepareUpload | STORE-007 | 확인 안 된 업로드 포함 3자리 점유 | "사진을 더 올릴 수 없어요. 잠시 후 다시 시도해 주세요.", 실패 칸은 빼거나 다시 시도 | - |
| O-060 | 실패한 매장 사진 남음 | 실패 칸 존재 | /signup/images | - | - | interior 에 failed | "올리지 못한 매장 사진을 다시 올리거나 빼주세요", 다음 비활성 | - |
| O-061 | 사진 단계 완료 | 다음 -> 단계 완료 | /signup/images -> /signup/menu | POST /v1/owner/store-images:complete | - | 로고 VERIFIED, 모든 매장 사진 attached | 버전 갱신, 메뉴 단계로 | - |
| O-062 | 사진 단계 완료 로고 없음 | 409 | /signup/images | POST /v1/owner/store-images:complete | STORE-010 | 로고 VERIFIED 없음 (화면 검사 우회) | "대표 이미지를 먼저 등록해 주세요." 머무름 | - |
| O-063 | 이미 마친 사진 단계 재완료 | 409 -> 완료 확인 -> 진행 | /signup/images -> /signup/menu | POST /v1/owner/store-images:complete, GET /v1/owner/store | STORE-002 | IMAGES 완료됨 | 현재 버전으로 그대로 진행, 확인 실패면 재조회 이동 + 안내 | - |
| O-064 | 대표 메뉴 입력 검증 | 메뉴 추가 시트 | /signup/menu | - | - | 메뉴 3개 미만 | 사진 필수, 메뉴명 1~20자, 가격 100~1,000,000원, 설명 500자 이내 | - |
| O-065 | 대표 메뉴 등록과 사진 업로드 성공 | 시트 확인 -> 메뉴 등록 -> ID 로 사진 발급 -> S3 -> 확인 | /signup/menu | POST /v1/owner/store-menus, POST /v1/owner/store-menu-images:prepareUpload, POST /v1/owner/store-menu-images:confirmUpload | - | 메뉴 3개 미만 | 메뉴 한 개씩 차례로 처리, 등록된 메뉴는 수정/삭제 불가 안내 | - |
| O-066 | 메뉴 등록 실패 | 메뉴 등록 거절 | /signup/menu | POST /v1/owner/store-menus | STORE-011, STORE-002, STORE-003, STORE-004, COMMON-002, 응답 없음 | 3개 등록됨 또는 상태 불일치 | 메뉴 칸 실패, 등록 안 된 메뉴라 고치거나 지울 수 있음, 다시 시도하면 다시 등록 | - |
| O-067 | 메뉴 사진 실패 (메뉴는 등록됨) | 발급/확인 거절 | /signup/menu | POST /v1/owner/store-menu-images:prepareUpload, POST /v1/owner/store-menu-images:confirmUpload | STORE-012, STORE-014, STORE-005, STORE-006, STORE-009 | 메뉴 ID 보유 | 실패 표시, 다시 시도 시 메뉴 재등록 없이 사진만 다시 | - |
| O-068 | 새로고침 후 사진 없는 메뉴 | 저장된 메뉴 image null -> 사진만 다시 선택 | /signup/menu | GET /v1/owner/store, POST /v1/owner/store-menu-images:prepareUpload, POST /v1/owner/store-menu-images:confirmUpload | - | 등록됐으나 사진 미확인 메뉴 | "사진이 없는 메뉴에 사진을 등록해주세요", 사진 선택하면 업로드 | - |
| O-069 | 메뉴 없이 등록 신청 완료 | 등록 신청 완료 -> 메뉴 단계 완료 -> 최종 등록 | /signup/menu -> /signup/complete | POST /v1/owner/store-menus:complete, POST /v1/owner/store:complete | - | 메뉴 0개, 필수 단계 모두 완료 | 점주 ACTIVE 전환, 완료 화면으로 replace | - |
| O-070 | 메뉴 포함 등록 신청 완료 | 모든 메뉴 사진 attached -> 완료 | /signup/menu -> /signup/complete | POST /v1/owner/store-menus:complete, POST /v1/owner/store:complete | - | 메뉴 1~3개 사진 있음 | 완료 화면 "매장 등록이 완료되었어요!" | - |
| O-071 | 메뉴 단계 완료 사진 없는 메뉴 | 409 | /signup/menu | POST /v1/owner/store-menus:complete | STORE-013 | 사진 미확인 메뉴 존재 | "사진이 없는 메뉴가 있어요. 메뉴 사진을 등록해 주세요." 머무름 | - |
| O-072 | 최종 등록 요건 부족 | 409 | /signup/menu | POST /v1/owner/store:complete, GET /v1/owner/store | STORE-015 | resumeStep MENU 아님 또는 readiness 미충족 | 재조회 후 이어갈 단계로 + "앞 단계가 저장되지 않아..." | - |
| O-073 | 최종 등록 실패 후 다시 누름 | 메뉴 단계 완료부터 다시 보냄 | /signup/menu | POST /v1/owner/store-menus:complete, POST /v1/owner/store:complete | STORE-001, STORE-004, COMMON-001 | 직전 최종 등록 실패 | resumeStep MENU 그대로라 메뉴 완료 다시 받고 최종 등록 재시도 | - |
| O-074 | 단계 저장 중 이미 ACTIVE | 403 -> 내 정보 재조회 | /signup/* -> /home 또는 /login | 단계 저장 API, POST /v1/owner/store:complete, GET /v1/owner/me | OWNER-002 | 다른 기기에서 입점 완료 | ACTIVE 면 상태 갱신 후 /home, 로그인 끊김이면 /login, 여전히 ONBOARDING 이면 기본 저장 실패 토스트 | - |
| O-075 | 등록 완료 후 홈으로 | 홈으로 -> 내 정보 조회 | /signup/complete -> /home | GET /v1/owner/me | - | 최종 등록 완료 | ACTIVE 로 갱신, RequireAuth 가 /home 으로 | - |
| O-076 | 등록 완료 후 홈 이동 실패 | 조회 실패 또는 아직 ONBOARDING | /signup/complete | GET /v1/owner/me | COMMON-001, 응답 없음 | 상태 전환 지연 | "홈으로 가지 못했어요. 잠시 후 다시 시도해 주세요." | - |
| O-077 | 등록 완료 화면 새로고침 | 앱 재시작 -> me ACTIVE | /signup/complete -> /home | GET /v1/owner/me | - | 이미 ACTIVE | 완료 화면 대신 /home 으로 바로 이동 | - |

### 홈/튜토리얼

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-078 | 홈 불러오기 | 가게, 잔액, 캠페인 목록(끝나지 않은 건 상세, DRAFT 는 포스터까지), 최근 실적(mock) 동시 조회 | /home | GET /v1/owner/store, GET /v1/owner/points/balance, GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster | - | ACTIVE, 가게 확정 | 스켈레톤 후 헤더(가게명, 잔액), QR 링크, 현재 캠페인, 쿠폰 사용 추이 | - |
| O-079 | 홈 불러오기 실패 | 넷 중 하나라도 실패 | /home | 위와 같음 | BILLING-006, BILLING-023, OWNER-002, CAMPAIGN-005, COMMON-001, 응답 없음 | 원장 장애, 가게 없음(storeId null), DRAFT 포스터 조회 실패 등 | "홈 화면을 불러오지 못했어요" + 다시 시도 | - |
| O-080 | 홈 현재 캠페인 없음 | 끝나지 않은 캠페인 없음 | /home -> /campaigns/new | - | - | 캠페인 없음 또는 모두 ENDED | "운영 중인 캠페인이 없어요" + 캠페인 만들기 | - |
| O-081 | 홈 작성 중 캠페인 | DRAFT 카드 + 이어서 작성 | /home -> /campaigns/:id/edit | - | - | DRAFT 1건 | 배지 "작성 중", "M.D 등록", 기간 미정이면 "기간 미정", 이어서 작성 링크 | - |
| O-082 | 홈 대기 중 캠페인 | SCHEDULED 카드 | /home -> /campaigns/:id | - | - | SCHEDULED | 배지 "대기 중", 시작일이 미래면 "M.D 시작, N일 뒤", 시작일 도래 후엔 "M.D 시작" | 시작일 기준 |
| O-083 | 홈 진행 중 캠페인 | ACTIVE 카드 | /home -> /campaigns/:id | GET /v1/owner/campaigns/{campaignId}/coupons/summary, GET /v1/owner/points/spend-summary | - | ACTIVE | 배지 "진행 중", 종료일까지 "N일 남음", 당일 이후 "오늘 종료", 오늘 실적(오늘 쿠폰 전체, 발급, 사용, 남은 쿠폰, 오늘 남은 예산) 표시 | 종료일 기준 |
| O-084 | 홈 직접 일시중지 캠페인 | PAUSED OWNER | /home | - | - | PAUSED, pausedReason OWNER | 배지 "일시중지"(경고색) + "직접 일시중지한 캠페인이에요. 언제든 다시 시작할 수 있어요." | - |
| O-085 | 홈 잔액 부족 중단 캠페인 | PAUSED NO_POINTS | /home | GET /v1/owner/point-policy | - | PAUSED, pausedReason NO_POINTS | 배지 "잔액 부족"(위험색), 상단 "포인트가 부족해 캠페인이 멈췄어요" + 충전 -> 충전 시트 | 00:00 판정 결과 |
| O-086 | 홈 관리자 중단 캠페인 | PAUSED ADMIN | /home | - | - | PAUSED, pausedReason ADMIN | 배지 "관리자 중단" + "운영 정책에 따라 관리자가 노출을 중단했어요." | - |
| O-087 | 캠페인 제목 규칙 | 쿠폰 조건으로 제목 생성 | /home, /campaigns, /campaigns/:id, /store/analytics | GET /v1/owner/store | - | 할인값 유무, 대상 | "전 메뉴 N원 할인", "메뉴명 N% 할인", 메뉴 못 찾으면 "특정 메뉴", 할인값 없으면 "새 캠페인" | - |
| O-088 | 홈 잔액에서 충전 | 잔액 버튼 -> 충전 시트 | /home -> /points/charge | GET /v1/owner/point-policy | BILLING-023 | ACTIVE | 포인트 충전 시트 (O-160 이후 흐름), 결제 뒤 /home 으로 복귀 | - |
| O-089 | 홈 QR 사용 처리 진입 | QR 링크 | /home -> /qr-scan | - | - | ACTIVE | QR 스캔 화면 | - |
| O-090 | 홈 쿠폰 사용 추이 | 최근 집행일 5회 막대 | /home -> /store/analytics | GET /v1/owner/stores/{storeId}/coupon-usages/daily | - | 최근 집행일 사용 기록 | 날짜별 실제 사용 수로 막대, 오늘 막대는 점선 + "집계 중", 분석 링크 | 오늘은 미확정 |
| O-091 | 첫 방문 튜토리얼 완료 | 4단계 시트, 다음/이전, 마지막 시작하기 | /home | PATCH /v1/owner/me/tutorial | - | ACTIVE, tutorialViewed false | 저장 성공 후 tutorialViewed true, 시트 닫힘, 새로고침해도 다시 안 뜸 | 시간표 10:00/11:00/12:59 안내 |
| O-092 | 튜토리얼 건너뛰기 또는 닫기 | 건너뛰기, Esc, 아래로 끌기 (배경 탭 무시) | /home | PATCH /v1/owner/me/tutorial | - | tutorialViewed false | 완료로 저장 후 닫힘 | - |
| O-093 | 튜토리얼 저장 실패 | PATCH 실패 | /home | PATCH /v1/owner/me/tutorial | COMMON-001, 응답 없음 | 서버 장애 | 시트 유지 "완료를 저장하지 못했어요. 잠시 후 다시 시도해 주세요." + 다시 시도 | - |
| O-094 | 튜토리얼 저장 시 ACTIVE 아님 | 403 -> 내 정보 재조회 | /home -> /signup/terms 또는 /login | PATCH /v1/owner/me/tutorial, GET /v1/owner/me | OWNER-002 | 점주 상태 변경 | 받은 상태로 갱신, RequireAuth 가 맞는 화면으로 보냄 | - |
| O-095 | 사용법 다시 보기 | "런치캐치 사용법" | /home | - | - | ACTIVE | 같은 시트, 끝내도 서버 저장 없이 닫힘 | - |

### 가게 관리

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-096 | 가게 관리 불러오기 | 가게와 잔액 조회 | /store | GET /v1/owner/store, GET /v1/owner/points/balance, GET /v1/owner/point-policy | - | ACTIVE | 로고(없으면 빈 자리), 가게명, 도로명, 분석, 잔액, 포인트 내역, 영업시간/사진/메뉴 값, 로그아웃 | - |
| O-097 | 가게 관리 불러오기 실패 | 가게 또는 잔액 실패 | /store | GET /v1/owner/store, GET /v1/owner/points/balance | BILLING-006, BILLING-023, OWNER-002, 응답 없음 | 장애 | "가게 정보를 불러오지 못했어요" + 다시 시도 | - |
| O-098 | 가게 정보 요약 값 | 영업시간, 사진 수, 메뉴 수 | /store | - | - | 영업일 7개, 연속/불연속, 0개 | "매일 HH:mm~HH:mm", "월~금 ...", "월~수, 금 ...", 없으면 "미등록", 사진 "n장", 메뉴 "n개" | - |
| O-099 | 가게 관리에서 충전 | 충전 버튼 -> 시트 | /store -> /points/charge | GET /v1/owner/point-policy | - | ACTIVE | 충전 시트, 결제 뒤 /store 로 복귀 | - |
| O-100 | 영업시간 수정 화면 불러오기 | 가게 조회 | /store/hours | GET /v1/owner/store | OWNER-002, 응답 없음 | ACTIVE | 로딩 스켈레톤, 실패 시 "가게 정보를 불러오지 못했어요" + 다시 시도 | - |
| O-101 | 영업시간 수정 검증 | 값 변경 | /store/hours | - | - | 불러온 영업시간 | 변경 없으면 저장 비활성, 요일 0개면 "영업 요일을 하루 이상 골라 주세요", 시각 역전 오류, "고르지 않은 요일은 휴무로 저장돼요." | - |
| O-102 | 영업시간 수정 저장 | 저장 -> 성공 -> 뒤로 | /store/hours -> /store | PUT /v1/owner/store/hours | - | 유효한 변경 | 가게 관리로 돌아가고 다시 불러와도 바뀐 영업시간. 화면에 브레이크와 라스트오더 입력이 없어 그 값은 지워진다(lc-frontend #328) | - |
| O-103 | 영업시간 바꾼 채 나가기 | 상단 뒤로 또는 브라우저 뒤로 | /store/hours | - | - | 변경 있음, 미저장 | "저장하지 않고 나갈까요?" 계속 수정 / 나가기 | - |
| O-104 | 매장 사진 보기 | 보기 전용 | /store/images | GET /v1/owner/store | OWNER-002, 응답 없음 | 로고와 매장 사진 0~3장 | 대표 이미지, "n/3", 없으면 "등록한 대표 이미지가 없어요", "등록한 매장 이미지가 없어요", 실패 시 다시 시도 | - |
| O-105 | 대표 메뉴 보기 | 보기 전용 | /store/menus | GET /v1/owner/store | OWNER-002, 응답 없음 | 메뉴 0~3개 | 이름, 가격, 설명, 사진 없으면 아이콘, 없으면 "등록한 대표 메뉴가 없어요" | - |
| O-106 | 하위 화면 뒤로 가기 | 상단 뒤로 | /store/hours, /store/images, /store/menus, /store/analytics, /points/history | - | - | 앱 안 이동 또는 주소 직접 진입 | 이동해 왔으면 이전 화면, 직접 진입이면 /store 로 replace | - |

### 캠페인 생성 단계별

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-107 | 새 캠페인 시작 | 캠페인 확인 -> 1단계 | /campaigns/new -> /campaigns/new/edit/coupon | GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId} | - | 끝나지 않은 캠페인 없음 | "새 캠페인을 준비하고 있어요" 후 replace 이동 | - |
| O-108 | 새 캠페인 시작 시 작성 중 DRAFT 있음 | DRAFT 로 이어 쓰기 | /campaigns/new -> /campaigns/:id/edit/coupon | GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster | - | DRAFT 1건 | 그 DRAFT 1단계로 replace | - |
| O-109 | 새 캠페인 시작 제한 | SCHEDULED/ACTIVE/PAUSED 존재 | /campaigns/new | GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId} | - | 운영 중 캠페인 | "아직 끝나지 않은 캠페인이 있어요" + 캠페인 목록으로 | - |
| O-110 | 새 캠페인 시작 조회 실패 | 목록 조회 실패 | /campaigns/new | GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster | CAMPAIGN-005, COMMON-002, 응답 없음 | 장애 | "캠페인 작성을 시작하지 못했어요" + 다시 시도 / 목록으로 | - |
| O-111 | 캠페인 탭 새 캠페인 제한 시트 | 운영 중일 때 "새 캠페인" | /campaigns -> /campaigns/:id | - | - | SCHEDULED 또는 ACTIVE 또는 PAUSED | "운영 중인 캠페인이 있어요", SCHEDULED 는 "M.D에 시작할 ...", 그 밖에는 "...M.D까지 운영돼요" + 닫기 / 캠페인 보기 | - |
| O-112 | 작성 화면 불러오기 | DRAFT 와 플랫폼 설정(mock) 로드 | /campaigns/:id/edit/* | GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster | - | DRAFT, 새 캠페인은 id "new" 로 서버 조회 없음 | 스켈레톤 후 저장된 단계 값 표시 | - |
| O-113 | 작성 화면 불러오기 실패 | 상세 또는 포스터 조회 실패 | /campaigns/:id/edit/* | GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster | CAMPAIGN-005, COMMON-003, 응답 없음 | 없는/남의 캠페인, 포스터 조회 장애 | "작성 중인 캠페인을 불러오지 못했어요" + "캠페인을 찾을 수 없습니다." 또는 기본 문구 + 다시 시도 / 목록으로 | - |
| O-114 | 작성 중이 아닌 캠페인 수정 진입 | 상태 확인 | /campaigns/:id/edit/* -> /campaigns/:id | GET /v1/owner/campaigns/{campaignId} | - | SCHEDULED, ACTIVE, PAUSED, ENDED | 상세로 replace | - |
| O-115 | 단계 주소 보정 | /edit 기본 경로, 새 캠페인의 2단계 이후 주소 | /campaigns/:id/edit -> coupon, /campaigns/new/edit/poster 등 -> coupon | - | - | - | 1단계로 replace (새 캠페인은 1단계 저장 전 다른 단계 불가) | - |
| O-116 | 쿠폰 조건 기본값과 할인 대상 | 기본 전체 메뉴 금액 할인, 특정 메뉴 고르기 | /campaigns/:id/edit/coupon | GET /v1/owner/store | - | 대표 메뉴 0~3개 | 특정 메뉴는 메뉴 로딩/실패("대표 메뉴를 불러오지 못했어요...") 표시, 메뉴 없으면 특정 메뉴 비활성 + 안내 | - |
| O-117 | 쿠폰 할인값과 수량 검증 | 할인율 1~100, 금액 1원 이상, 하루 선착순 1~9999 | /campaigns/:id/edit/coupon | - | - | - | 오류 문구, 빠른 조정 버튼, 메뉴 할인가 미리보기, 메뉴 가격보다 큰 금액이면 "(메뉴 가격까지만 할인돼요)" | 수량은 매일 00:00 초기화 안내 |
| O-118 | 쿠폰 사용 가능 시간 | 11:30~15:00, 30분 단위, 최소 1시간 | /campaigns/:id/edit/coupon | - | - | - | 허용 밖이면 진행 막힘, "선착순 오픈은 매일 11:00이에요" 안내 | 11:30~15:00 |
| O-119 | 쿠폰 선택 입력 | 최소 주문 금액, 유의사항 500자 | /campaigns/:id/edit/coupon | - | - | - | 비우면 보내지 않음 | - |
| O-120 | 예전 DRAFT 의 막힌 조합 | 전체 메뉴 + 퍼센트, 메뉴 지운 특정 메뉴 | /campaigns/:id/edit/coupon | - | - | 그런 값의 DRAFT | "전체 메뉴에는 퍼센트 할인을 쓸 수 없어요...", "...전체 메뉴로 바꿔 주세요." 진행 막힘 | - |
| O-121 | 쿠폰 조건 저장 성공 | 다음 -> 가게 ID 조회 -> DRAFT 생성(201) 또는 덮어쓰기(200) | /campaigns/new/edit/coupon -> /campaigns/:id/edit/poster | GET /v1/owner/store, POST /v1/owner/campaigns | - | ACTIVE, 끝나지 않은 캠페인 없음 또는 DRAFT 있음 | 새 캠페인은 서버 ID 로 replace 이동, 기존 DRAFT 는 push 이동 | - |
| O-122 | 쿠폰 저장 운영 중 캠페인 있음 | 409 | /campaigns/:id/edit/coupon | POST /v1/owner/campaigns | CAMPAIGN-010 | 그사이 다른 캠페인이 SCHEDULED 이상 | "캠페인은 한 번에 하나만 운영할 수 있습니다..." | - |
| O-123 | 쿠폰 저장 ACTIVE 아님 | 403 | /campaigns/:id/edit/coupon | POST /v1/owner/campaigns | CAMPAIGN-001 | 점주 상태 변경 | "입점을 마친 점주만 캠페인을 만들 수 있습니다." | - |
| O-124 | 쿠폰 저장 값 거절 | 422 또는 400 | /campaigns/:id/edit/coupon | POST /v1/owner/campaigns | CAMPAIGN-002, CAMPAIGN-003, CAMPAIGN-004, COMMON-002, COMMON-003 | 화면 검사 우회 값 | 422 는 서버 문구, 400 은 "쿠폰 조건을 다시 확인해 주세요." | - |
| O-125 | 쿠폰 저장 기타 실패 | 가게 조회 실패, 5xx, 응답 없음 | /campaigns/:id/edit/coupon | GET /v1/owner/store, POST /v1/owner/campaigns | OWNER-002, COMMON-001, 응답 없음 | 장애 | 토스트 "저장하지 못했습니다 / 잠시 후 다시 시도해 주세요." | - |
| O-126 | 노출 대상 입력 | 반경 500m/1km/2km/3km, 성별, 연령대 다중(비우면 전체) | /campaigns/:id/edit/target | - | - | DRAFT | 시간대는 점심 10:00~12:59 고정 안내, 항상 다음 가능 | 서빙 10:00~12:59 |
| O-127 | 노출 대상 저장 성공 | 다음 -> 반경, 성별, 연령대 함께 저장 (전체는 4개 값) | /campaigns/:id/edit/target -> /campaigns/:id/edit/budget | PATCH /v1/owner/campaigns/{campaignId} | - | DRAFT | 응답 값으로 화면 값 맞춤 | - |
| O-128 | 노출 대상 저장 거절 | 상태/소유/형식 | /campaigns/:id/edit/target | PATCH /v1/owner/campaigns/{campaignId} | CAMPAIGN-006, CAMPAIGN-005, CAMPAIGN-001, COMMON-002 | DRAFT 아님, 남의 캠페인 등 | "작성 중인 캠페인만 수정할 수 있습니다.", "캠페인을 찾을 수 없습니다.", "입점을 마친 점주만...", 400 은 "노출 대상을 다시 확인해 주세요." | - |
| O-129 | 하루 예산과 집행 기간 입력 | 하루 예산, 날짜 범위 | /campaigns/:id/edit/budget | GET /v1/owner/points/balance | - | DRAFT, 최소 하루 예산 mock 5,000 | 최소 미만 오류, 시작일 최소 내일, "총 N일 동안 노출돼요", 3일 미만이면 권장 경고 | 시작일 >= 내일(브라우저 날짜) |
| O-130 | 저장된 시작일이 지남 | 이전에 저장한 시작일이 오늘 이전 또는 오늘 | /campaigns/:id/edit/budget | - | - | 어제 이전에 저장한 DRAFT | "시작일이 지났어요. 내일 이후로 다시 골라 주세요." 진행 막힘 | 자정 경과 |
| O-131 | 필요 포인트 비교 | 하루 예산 x 일수 vs 잔액 | /campaigns/:id/edit/budget, /campaigns/:id/edit/review | GET /v1/owner/points/balance | BILLING-006, BILLING-023 | 잔액 충분 또는 부족 | 충분: "집행 기간 동안 쓸 포인트가 충분해요", 부족: 부족분 + 안내 + 충전 버튼, 조회 중 "불러오는 중", 실패 "조회 실패" + 안내 | 집행일 00:00 예약 안내 |
| O-132 | 예산과 기간 저장 성공 | 다음 -> 세 값 함께 저장 | /campaigns/:id/edit/budget -> /campaigns/:id/edit/review | PATCH /v1/owner/campaigns/{campaignId} | - | DRAFT, 기간 2일 이상, 서버 최소 예산 이상 | 응답 값으로 맞춤, 잔액 부족은 저장을 막지 않음 | - |
| O-133 | 하루짜리 기간 | 시작일 = 종료일 | /campaigns/:id/edit/budget | PATCH /v1/owner/campaigns/{campaignId} | CAMPAIGN-008 | 같은 날 선택 (화면은 허용) | 서버 422, 서버 문구 "집행 기간은 1일 이상이어야 합니다." | - |
| O-134 | 예산 저장 서버 규칙 거절 | 최소 예산, 시작일 | /campaigns/:id/edit/budget | PATCH /v1/owner/campaigns/{campaignId} | CAMPAIGN-007, CAMPAIGN-009 | 서버 최소 하루 예산이 mock 값보다 큼, 저장 직전 자정 넘어 시작일이 오늘 | 서버 문구 표시 | 서버 Asia/Seoul 기준 내일 |
| O-135 | 예산 저장 기타 거절 | 상태/소유/형식 | /campaigns/:id/edit/budget | PATCH /v1/owner/campaigns/{campaignId} | CAMPAIGN-006, CAMPAIGN-005, CAMPAIGN-001, COMMON-002 | - | 매핑 문구, 400 은 "하루 예산과 집행 기간을 다시 확인해 주세요." | - |
| O-136 | 작성 중 충전 다녀오기 | 부족분 충전 -> 작성 값 sessionStorage 저장 -> 결제 -> 복귀 | /campaigns/:id/edit/budget -> /points/charge -> /points/charge/success -> /campaigns/:id/edit/budget | GET /v1/owner/point-policy, POST /v1/owner/payments, POST /v1/owner/payments/{paymentId}:confirm, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/points/balance | - | 잔액 부족 | 시트는 부족분 이상 최소 금액 미리 선택, 돌아오면 미저장 값 복원, 잔액 다시 계산 | 저장 값 30분 지나면 버림 |
| O-137 | 확인 단계 요약 | 단계별 요약, 채우지 않음 배지, 수정 링크 | /campaigns/:id/edit/review | GET /v1/owner/store, GET /v1/owner/campaigns/{campaignId}/poster, GET /v1/owner/points/balance | - | DRAFT | 서버 포스터 미리보기(불러오는 중/실패/아직 없음), 미완성 단계 있으면 "채우지 않은 단계를 먼저 완성해 주세요" + 활성화 비활성 | - |
| O-138 | 단계 이전/뒤로 | 하단 이전, 1단계 상단 뒤로 | /campaigns/:id/edit/* -> /campaigns | - | - | - | 이전 단계로, 1단계 뒤로는 저장 없이 목록 (저장된 단계는 DRAFT 에 남음) | - |
| O-139 | 활성화 요청 성공 | 활성화 요청 -> 자동 검수 통과 -> SCHEDULED | /campaigns/:id/edit/review -> /campaigns/:id/complete | POST /v1/owner/campaigns/{campaignId}:activate, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/store | - | 모든 단계 완료, 포스터 저장됨, 검수 통과 | replace 로 완료 화면 "활성화 요청을 마쳤어요", 캠페인/기간/"M.D 00:00 (N일 뒤)" + 상세 보기(뒤로 시 목록)/목록으로 | 시작일 00:00 노출 안내 |
| O-140 | 활성화 완료 화면 조회 실패 | 상세 실패 | /campaigns/:id/complete | GET /v1/owner/campaigns/{campaignId} | CAMPAIGN-005, 응답 없음 | - | 요약 카드 없이 안내와 버튼만 | - |

### 포스터/검수

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-141 | 템플릿 불러오기 | 게시+활성 템플릿과 가게 조회 | /campaigns/:id/edit/poster | GET /v1/owner/templates, GET /v1/owner/store | - | 게시된 활성 템플릿 1개 이상 | 썸네일 가로 목록, 고르기 전 "템플릿을 고르면 미리보기가 나와요" | - |
| O-142 | 템플릿 불러오기 실패 | 템플릿 또는 가게 실패 | /campaigns/:id/edit/poster | GET /v1/owner/templates, GET /v1/owner/store | OWNER-002, 응답 없음 | 장애 | "포스터 템플릿을 불러오지 못했어요...", 진행 막힘 | - |
| O-143 | 고를 템플릿 없음 | 빈 배열 | /campaigns/:id/edit/poster | GET /v1/owner/templates | - | 게시/활성 템플릿 0개 | "지금 고를 수 있는 포스터 템플릿이 없어요..." 진행 불가 | - |
| O-144 | 템플릿 선택과 문구 | 선택 -> 기본 슬롯(할인 내용=제목 20자, 이벤트 문구 기본값, 기간=사용 시간) | /campaigns/:id/edit/poster | - | - | 템플릿 있음 | 미리보기, 할인 내용 필수(20자), 이벤트 문구 선택(30자), 기간 고정 표시, 광고 라벨 제거 | - |
| O-145 | 포스터 기간 동기화 | 1단계 사용 시간 변경 후 2단계 | /campaigns/:id/edit/poster | - | - | 저장된 포스터 있음 | 기간 슬롯을 현재 사용 시간으로 갱신 | - |
| O-146 | 포스터 이미지 업로드 성공 | 고르면 바로 발급 -> S3 PUT -> 미리보기 | /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload | - | DRAFT, 집행 기간이 이미 저장됨 | objectKey 보관, "포스터를 모두 채웠어요", 다음 가능 | 주소 10분 유효 |
| O-147 | 기간 미설정 상태 포스터 이미지 업로드 | 정상 순서(1->2단계) 새 캠페인 | /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload | - | DRAFT, startDate/endDate null | 기간 검사 없이 업로드 주소 발급, 포스터를 저장하고 다음 단계로 진행 | - |
| O-148 | 포스터 이미지 업로드 거절 | 형식/크기/상태/소유 | /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload | POSTER-017, POSTER-018, CAMPAIGN-005, CAMPAIGN-006, CAMPAIGN-001 | - | "업로드 실패" + 다시 시도, 올리는 동안 이전 키는 비움 | - |
| O-149 | 포스터 저장 성공 | 다음 -> 템플릿 ID 와 objectKey 로 생성 또는 덮어쓰기 | /campaigns/:id/edit/poster -> /campaigns/:id/edit/target | POST /v1/owner/campaigns/{campaignId}/poster | - | 기간 설정, 이미지 업로드됨, 템플릿 사용 가능 | 서버가 가게명, 할인, 기간(MM.dd~MM.dd)으로 슬롯을 채워 저장 | - |
| O-150 | 포스터 저장 거절 (서버 문구) | 이미지/키/템플릿/슬롯 | /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}/poster | POSTER-016, POSTER-017, POSTER-018, POSTER-019, POSTER-005, POSTER-015, POSTER-001 | 미업로드, 다른 캠페인 키, 템플릿 내림 등 | 토스트 "저장하지 못했습니다" + 서버 문구 | - |
| O-151 | 포스터 저장 거절 (매핑 문구) | 상태/소유/점주 | /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}/poster | CAMPAIGN-001, CAMPAIGN-005, CAMPAIGN-006 | - | "입점을 마친 점주만...", "캠페인을 찾을 수 없습니다.", "작성 중인 캠페인만 수정할 수 있습니다." | - |
| O-152 | 포스터 저장 서버 오류 | 가게/메뉴 조회 실패 등 | /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}/poster | COMMON-001, 응답 없음 | - | "잠시 후 다시 시도해 주세요." | - |
| O-153 | 확인 단계 포스터 글 불일치 | 서버 완성 HTML 표시 | /campaigns/:id/edit/review | GET /v1/owner/campaigns/{campaignId}/poster | - | 포스터 저장됨 | 2단계에서 고친 할인 내용/이벤트 문구 대신 서버가 채운 글이 보임 | - |
| O-154 | 자동 검수 불통과 | 활성화 -> 검수 FAIL(금칙어, 이미지 판독 불가, 해상도 미달, 글자 비율 초과, 검수 도구 실패) -> 200 DRAFT | /campaigns/:id/edit/review | POST /v1/owner/campaigns/{campaignId}:activate | - | 검수 기준 미달 포스터 | "활성화를 요청하지 못했습니다 / 잠시 후 다시 시도해 주세요." 사유는 표시 안 됨, DRAFT 유지 | - |
| O-155 | 활성화 시 포스터 없음 | 404 | /campaigns/:id/edit/review -> /campaigns/:id/edit/poster | POST /v1/owner/campaigns/{campaignId}:activate | POSTER-020 | 포스터 미저장 | 포스터 단계로 이동 + "포스터를 먼저 만들어 주세요." | - |
| O-156 | 활성화 시 상태 변경 | 409 또는 403 -> 닫으면 다시 불러옴 | /campaigns/:id/edit/review -> /campaigns/:id | POST /v1/owner/campaigns/{campaignId}:activate, GET /v1/owner/campaigns/{campaignId} | CAMPAIGN-006, CAMPAIGN-005 | 이미 SCHEDULED, 검수 중 포스터 변경, 남의 캠페인 | 상태 변경 안내, 닫으면 재조회해 DRAFT 아니면 상세로 | - |
| O-157 | 활성화 요청 제한 | 429 (본문 무시) | /campaigns/:id/edit/review | POST /v1/owner/campaigns/{campaignId}:activate | CAMPAIGN-011 | 점주별 고정 창 한도 초과 | "활성화를 요청하지 못했습니다 / 잠시 후 다시 시도해 주세요." | 고정 창 |
| O-158 | 활성화 기타 실패 | ACTIVE 아님, 서버 오류 | /campaigns/:id/edit/review | POST /v1/owner/campaigns/{campaignId}:activate | CAMPAIGN-001, COMMON-001, 응답 없음 | - | "입점을 마친 점주만 캠페인을 관리할 수 있습니다." 또는 기본 | - |

### 캠페인 상태 관리

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-159 | 캠페인 목록 불러오기 | 목록 100건 + 끝나지 않은 건 상세(+DRAFT 포스터) + 가게 메뉴 | /campaigns | GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster, GET /v1/owner/store | - | ACTIVE | 현재 캠페인 카드 + 지난 캠페인 | - |
| O-160 | 캠페인 목록 실패 | 어느 조회든 실패 | /campaigns | 위와 같음 | CAMPAIGN-005, OWNER-002, COMMON-001, 응답 없음 | - | "캠페인 목록을 불러오지 못했어요" + 다시 시도 | - |
| O-161 | 캠페인 하나도 없음 | 빈 목록 | /campaigns -> /campaigns/new | GET /v1/owner/campaigns | - | 캠페인 0건 | "아직 등록된 캠페인이 없어요" + 첫 캠페인 만들기 | - |
| O-162 | 현재 캠페인 없고 지난 캠페인만 | ENDED 만 | /campaigns | GET /v1/owner/campaigns | - | ENDED 1건 이상 | "지금 운영 중인 캠페인이 없어요" + 캠페인 만들기, 지난 캠페인 표시 | - |
| O-163 | 목록 작성 중 카드 | DRAFT 카드 + 이어서 작성 | /campaigns -> /campaigns/:id/edit | - | - | DRAFT | 이어서 작성만, 삭제 없음, 새 캠페인 버튼은 링크(제한 시트 아님) | - |
| O-164 | 대기 중 취소 | 대기 중 취소 -> 확인 시트 -> SCHEDULED -> DRAFT | /campaigns | POST /v1/owner/campaigns/{campaignId}:cancel, GET /v1/owner/campaigns | - | SCHEDULED | 시트 "...시작일 전날 23:59까지 요청해야 시작일부터 노출돼요.", 성공 후 목록 재조회, 작성 중으로 표시 | 시작일 전날 23:59 |
| O-165 | 진행 중 중단 | 캠페인 중단 -> ACTIVE -> PAUSED(OWNER) | /campaigns | POST /v1/owner/campaigns/{campaignId}:pause, GET /v1/owner/campaigns | - | ACTIVE | "다음 피드 요청부터 노출되지 않아요. 오늘 이미 쓴 포인트는 돌려받을 수 없어요." 후 일시중지 표시 | - |
| O-166 | 직접 중단 재개 | 다시 시작 -> PAUSED(OWNER) -> ACTIVE | /campaigns | POST /v1/owner/campaigns/{campaignId}:resume, GET /v1/owner/campaigns | - | PAUSED OWNER | "바로 다시 점심 손님에게 노출돼요." 후 진행 중 | - |
| O-167 | 일시중지 캠페인 종료 | 캠페인 종료(위험색) -> PAUSED -> ENDED | /campaigns | POST /v1/owner/campaigns/{campaignId}:end, GET /v1/owner/campaigns | - | PAUSED OWNER 또는 NO_POINTS | "종료하면 되돌릴 수 없고..." 후 지난 캠페인으로 이동, 새 캠페인 가능 | - |
| O-168 | 잔액 부족 중단 캠페인 처리 | 종료 버튼만 | /campaigns | POST /v1/owner/campaigns/{campaignId}:end | - | PAUSED NO_POINTS | 재개 버튼 없음, 카드 안내 "충전하면 자동으로 다시 시작돼요." | - |
| O-169 | 관리자 중단 캠페인 | 버튼 없음 | /campaigns | - | - | PAUSED ADMIN | 처리 버튼 없이 카드 안내만 | - |
| O-170 | 처리 중 상태가 바뀜 | 409 또는 403 -> 시트 안내 -> 닫으면 목록 재조회 | /campaigns | POST /v1/owner/campaigns/{campaignId}:cancel, POST /v1/owner/campaigns/{campaignId}:pause, POST /v1/owner/campaigns/{campaignId}:resume, POST /v1/owner/campaigns/{campaignId}:end | CAMPAIGN-006, CAMPAIGN-005 | 예: 목록은 ACTIVE 인데 00:00 에 NO_POINTS 로 중단됨, 종료 요청인데 이미 ENDED | 액션별 문구(예: "캠페인 상태가 바뀌어 중단할 수 없습니다...") 또는 "캠페인을 찾을 수 없습니다.", 닫으면 재조회 | 00:00 판정과 경합 |
| O-171 | 처리 기타 실패 | ACTIVE 아님, 서버 오류 | /campaigns | 위 네 API | CAMPAIGN-001, COMMON-001, 응답 없음 | - | "입점을 마친 점주만 캠페인을 관리할 수 있습니다." 또는 "처리하지 못했어요. 잠시 후 다시 시도해 주세요." 시트 유지 | - |
| O-172 | 같은 처리 반복 (멱등) | 이미 같은 결과 상태로 다시 요청 | /campaigns | POST /v1/owner/campaigns/{campaignId}:cancel, POST /v1/owner/campaigns/{campaignId}:pause, POST /v1/owner/campaigns/{campaignId}:resume | - | 이미 DRAFT, 이미 PAUSED(OWNER), 이미 ACTIVE | 서버가 성공으로 응답, 목록 재조회 | - |
| O-173 | 처리 확인 시트 닫기 | 닫기, 처리 중 닫기 막힘 | /campaigns | - | - | - | 시트 닫힘 | - |
| O-174 | 캠페인 상세 보기 | 상세 + 메뉴 + 포스터 + 실적 | /campaigns/:id | GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/store, GET /v1/owner/campaigns/{campaignId}/poster, GET /v1/owner/campaigns/{campaignId}/coupons/summary, GET /v1/owner/points/spend-summary, GET /v1/owner/campaigns/{campaignId}/analytics/funnel | - | 내 캠페인 | 배지, "M.D 등록", 제목, 상태 안내, 포스터, 쿠폰 조건, 노출 대상, 예산과 기간(총 필요 포인트), 오늘 실적(발급, 소진), 누적 실적(발급, 사용과 비율, 소진), 처리 버튼 없음 | - |
| O-175 | 상세 상태별 안내 | DRAFT/SCHEDULED/PAUSED 사유 | /campaigns/:id | GET /v1/owner/campaigns/{campaignId} | - | 각 상태 | DRAFT: 활성화 안내, SCHEDULED: "M.D 00:00부터 피드에 노출돼요.", PAUSED: 사유별 문구, ACTIVE/ENDED: 없음 | - |
| O-176 | 상세 포스터 상태 | 포스터 있음/없음/실패 | /campaigns/:id | GET /v1/owner/campaigns/{campaignId}/poster | POSTER-020 | - | 완성 포스터, "아직 포스터를 만들지 않았어요", "포스터를 불러오지 못했어요" (메뉴/포스터 실패는 상세를 막지 않음) | - |
| O-177 | 상세 불러오기 실패 | 상세 실패 | /campaigns/:id | GET /v1/owner/campaigns/{campaignId} | CAMPAIGN-005, COMMON-003, 응답 없음 | 없는/남의 캠페인, 숫자 아닌 id | "캠페인을 불러오지 못했어요" + "캠페인을 찾을 수 없습니다." 또는 기본 + 다시 시도/목록으로 | - |
| O-178 | 상세 뒤로 가기 | backTo, 이력, 직접 진입 | /campaigns/:id | - | - | 완료 화면에서 온 경우 backTo /campaigns | backTo 우선, 직접 진입이면 /campaigns, 진입 시 항상 맨 위 스크롤 | - |
| O-179 | 00:00 하루 예산 예약 | 전날 남은 예약 해제 -> ACTIVE 와 PAUSED(OWNER, NO_POINTS) 캠페인 예약 | 영향: /home, /points/history | - (BILLING_RESERVE, BILLING_DAILY_RELEASE) | - | 집행 기간 안 ACTIVE 캠페인 | 잔액에서 min(하루 예산, 잔액) 예약, 내역에 예약/해제 원장 | 매일 00:00 |
| O-180 | 00:00 잔액 부족 중단 | 예약액 < 노출 단가 -> PAUSED(NO_POINTS) | 영향: /home, /campaigns, /campaigns/:id | - (BILLING_FUNDING_JUDGE) | - | ACTIVE, 잔액 부족 | 다음 조회부터 "잔액 부족" 배지, 홈 경고 | 매일 00:00 |
| O-181 | 00:00 잔액 회복 재개 | PAUSED(NO_POINTS) 인데 예약액 >= 단가 -> RESUME | 영향: /home, /campaigns | - (BILLING_FUNDING_JUDGE) | - | 전날 충전으로 잔액 회복 | 진행 중으로 표시 | 매일 00:00 |
| O-182 | 충전 직후 자동 재개 | 충전 커밋 -> 그날 계획 있는 PAUSED(NO_POINTS) 재예약 -> 단가 이상이면 RESUME | 영향: /home, /campaigns | POST /v1/owner/payments/{paymentId}:confirm | - | PAUSED(NO_POINTS), 오늘 계획 행 있음, 집행 기간 안 | 충전 후 돌아온 화면에서 진행 중으로 표시, 계획이 없으면 다음 00:00 까지 대기 | 시각 제한 없음 |
| O-183 | 시작일 도래한 대기 중 캠페인 | SCHEDULED 유지 (develop 에 SCHEDULED -> ACTIVE 전이 호출처 없음) | /home, /campaigns, /campaigns/:id | - | - | SCHEDULED, 시작일 <= 오늘 | 계속 "대기 중", "M.D 시작", 00:00 예약 대상(ACTIVE 만) 아님 | 시작일 00:00 |
| O-184 | 종료일 지난 캠페인 | 자동 ENDED 전이 없음 | /home, /campaigns | - | - | ACTIVE 또는 PAUSED, 종료일 < 오늘 | 계속 현재 캠페인, "오늘 종료" 표시, 예약 대상에서 빠짐, 직접 중단 후 종료해야 ENDED | 종료일 다음 00:00 |

### 종료 캠페인

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-185 | 탭의 지난 캠페인 미리보기 | 종료일 최신순 2건 | /campaigns | GET /v1/owner/campaigns | - | ENDED 1건 이상 | "지난 캠페인 N"(전체 개수), 2건 초과 시 "전체 보기" | - |
| O-186 | 지난 캠페인 카드 | 배지 "종료", 기간, 제목 | /campaigns, /campaigns/ended -> /campaigns/:id | - | - | ENDED | 누적 실적 칸은 표시 안 됨(성과 null), 특정 메뉴 이름은 목록에 메뉴 ID 가 없어 "특정 메뉴" | - |
| O-187 | 전체 목록 첫 구간 | 20건 단위, ENDED 만 남김 | /campaigns/ended | GET /v1/owner/campaigns, GET /v1/owner/store | - | ENDED 있음 | 목록 표시 | - |
| O-188 | 전체 목록 빈 결과 | ENDED 없음 | /campaigns/ended | GET /v1/owner/campaigns | - | ENDED 0건 | "아직 끝난 캠페인이 없어요 / 집행 기간이 끝난 캠페인이 여기에 모여요" | - |
| O-189 | 전체 목록 첫 구간 실패 | 조회 실패 | /campaigns/ended | GET /v1/owner/campaigns, GET /v1/owner/store | COMMON-002, OWNER-002, 응답 없음 | - | "지난 캠페인을 불러오지 못했어요" + 다시 시도 | - |
| O-190 | 이어서 불러오기 | 끝 근처 도달 -> 다음 pageToken, ENDED 없는 구간은 자동으로 다음 구간 | /campaigns/ended | GET /v1/owner/campaigns | - | 20건 초과 | 로딩 표시 후 이어 붙임(중복 제거), 끝이면 "모든 캠페인을 봤어요" | - |
| O-191 | 이어서 불러오기 실패 | 실패 또는 같은 토큰 반복 | /campaigns/ended | GET /v1/owner/campaigns | 응답 없음 | - | 기존 목록 유지 + "불러오지 못했어요" 다시 시도 | - |
| O-192 | 상세 다녀와 복원 | 상세 -> 뒤로 | /campaigns/ended -> /campaigns/:id -> /campaigns/ended | - | - | 같은 기록 항목 | 목록과 스크롤 위치 그대로, 다시 조회 안 함, 새로 들어오면 처음부터 | 30분 지난 저장본은 버림 |
| O-193 | 전체 목록 뒤로 | 상단 뒤로 | /campaigns/ended | - | - | 직접 진입 | /campaigns 로 replace | - |

### 포인트 충전

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-194 | 충전 시트 정책 | 정책 로딩/실패/성공 | /home, /store, /points/history, /campaigns/:id/edit/budget, /campaigns/:id/edit/review | GET /v1/owner/point-policy | BILLING-023, 응답 없음 | ACTIVE | 로딩 "충전 정보를 불러오는 중이에요", 실패 "충전 정보를 불러오지 못했어요" + 다시 시도, 성공 시 최소 금액 이상 상품만 오름차순 칩 | 정책은 effectiveDate 기준 |
| O-195 | 충전 가능한 금액 없음 | 상품이 모두 최소 금액 미만 또는 0개 | 위와 같음 | GET /v1/owner/point-policy | - | 정책 상품 없음 | "지금은 충전할 수 있는 금액이 없어요", 충전 버튼 비활성 | - |
| O-196 | 금액 선택과 잔액 미리보기 | 기본 가장 작은 금액, 칩 선택 | 위와 같음 | - | - | 잔액 알 때 | "현재 잔액", "충전 후 잔액", "1원 = 1포인트", "최소 N원부터" | - |
| O-197 | 부족분 미리 선택 (캠페인 작성) | 부족분 이상 최소 상품, 없으면 최대 상품 | /campaigns/:id/edit/budget, /campaigns/:id/edit/review | - | - | 부족분 > 0 | "부족한 NP를 채울 수 있는 금액을 골라 두었어요." 또는 "한 번에 ... 다 채우지 못해요. 충전한 뒤 한 번 더 충전해 주세요." | - |
| O-198 | 결제 화면 금액 확인 실패 | amount 가 양의 정수 아님 | /points/charge | - | - | 주소 직접 조작 | "충전 금액을 확인하지 못했어요" + 돌아가기 | - |
| O-199 | 결제위젯 불러오기 | 토스 위젯 렌더 | /points/charge | - (토스 SDK) | - | 클라이언트 키 있음 | "결제수단을 불러오는 중이에요" 후 결제수단과 약관, 약관 해제 시 "필수 약관에 동의하면 결제할 수 있어요" + 결제 비활성 | - |
| O-200 | 결제위젯 실패 | SDK 로드 실패, 키 없음 | /points/charge | - | - | 키 없음 또는 네트워크 | "결제수단을 불러오지 못했어요" + 다시 시도 | - |
| O-201 | 결제 시작 | 결제하기 -> 충전 주문 201 -> 결제창 | /points/charge -> 토스 결제창 | POST /v1/owner/payments | - | 확인 중 결제 없음 | 서버 금액이 다르면 위젯 금액 맞춤, 성공/실패 주소에 paymentId, returnTo | 결제 요청 뒤 10분 |
| O-202 | 확인 중 결제 있음 | 주문 409 | /points/charge | POST /v1/owner/payments | BILLING-008 | UNKNOWN 결제 존재 | "확인 중인 결제가 있어 지금은 충전할 수 없어요. 잠시 후 다시 충전해 주세요." | - |
| O-203 | 금액 거절 | 주문 400 | /points/charge | POST /v1/owner/payments | BILLING-001, BILLING-002 | 정책이 그사이 바뀜, 주소 금액 조작 | 서버 문구 그대로 | 정책 변경 시점 |
| O-204 | 주문 기타 실패 | ACTIVE 아님, 형식, 서버 | /points/charge | POST /v1/owner/payments | BILLING-023, COMMON-002, COMMON-001, 응답 없음 | - | "결제를 시작하지 못했어요 / 잠시 후 다시 시도해 주세요." | - |
| O-205 | 결제창 닫기 또는 위젯 거절 | USER_CANCEL 또는 기타 위젯 오류 | /points/charge | - | - | - | USER_CANCEL 은 안내 없이 머묾, 그 밖은 토스 문구 표시 | - |
| O-206 | 승인 확정 완료 | 성공 주소 -> confirm 200 COMPLETED | /points/charge/success -> returnTo | POST /v1/owner/payments/{paymentId}:confirm | - | 결제 승인 | "포인트를 충전했어요", 충전 포인트, 확인 -> 돌아갈 화면 | - |
| O-207 | 성공 주소 파라미터 누락 | paymentId/orderId/paymentKey 없음 | /points/charge/success | - | - | 주소 조작 | "결제를 확인할 수 없어요" + 포인트 내역 확인 안내 | - |
| O-208 | 승인 결과 미확정 | confirm 202 CONFIRMING 또는 503 또는 응답 없음 -> 상태 조회 폴링 | /points/charge/success | POST /v1/owner/payments/{paymentId}:confirm, GET /v1/owner/payments/{paymentId} | BILLING-007, 응답 없음 | PG 응답 지연 또는 미전송 | "결제를 확인하고 있어요" 폴링 후 COMPLETED/FAILED/CANCELLED 로 확정 | 3초 간격 10회 |
| O-209 | 폴링해도 미확정 | 10회 모두 미확정 또는 조회 실패 | /points/charge/success | GET /v1/owner/payments/{paymentId}, POST /v1/owner/payments/{paymentId}:confirm | BILLING-004, 응답 없음 | UNKNOWN 지속 | "결제를 확인하지 못했어요" + 다시 확인(승인 확정 재전송) | 약 30초 |
| O-210 | 결제 실패 또는 취소된 주문 | confirm 422/409 또는 displayStatus FAILED/CANCELLED | /points/charge/success | POST /v1/owner/payments/{paymentId}:confirm, GET /v1/owner/payments/{paymentId} | BILLING-003, BILLING-009 | PG 거절, 승인 전 만료 | "포인트가 충전되지 않았어요 / ...다시 충전해 주세요." | 승인 전 만료 |
| O-211 | 결제 정보 불일치 | confirm 409/404 | /points/charge/success | POST /v1/owner/payments/{paymentId}:confirm | BILLING-017, BILLING-004 | 주문 번호 다름, 결제 키 재사용, 남의 결제 | "결제를 확인할 수 없어요 / 결제 정보가 맞지 않아..." | - |
| O-212 | 승인 확정 재진입 | 새로고침, 개발 모드 중복 실행 | /points/charge/success | POST /v1/owner/payments/{paymentId}:confirm | - | 이미 APPROVED | 같은 결과로 응답, 충전은 한 번 | - |
| O-213 | 결제창 실패 주소 | PAY_PROCESS_CANCELED 또는 기타 code/message | /points/charge/fail -> returnTo | - | - | 결제창에서 취소/오류 | "결제를 취소했어요" 또는 "결제하지 못했어요 / 포인트는 충전되지 않았어요. (사유)" | - |
| O-214 | 돌아갈 화면 검증 | returnTo 가 앱 밖 주소 | /points/charge, /points/charge/success, /points/charge/fail | - | - | 조작된 returnTo | /home 으로 대체 | - |

### 포인트 내역

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-215 | 잔액 요약 | 잔액과 예약 중 포인트 | /points/history | GET /v1/owner/points/balance | - | ACTIVE | 잔액, "예약 중 포인트" + 설명 | 오늘 예약분 |
| O-216 | 잔액 조회 실패 | 원장 장애 | /points/history | GET /v1/owner/points/balance | BILLING-006, BILLING-023, 응답 없음 | - | "잔액을 불러오지 못했어요" + 다시 시도(이전 잔액 대신 보여주지 않음), 내역은 따로 표시 | - |
| O-217 | 내역 첫 구간 | 기본 최근 30일, 20건, 오늘 소진 집계 함께 | /points/history | GET /v1/owner/points/ledger, GET /v1/owner/points/spend-summary | - | 원장 있음 | 날짜별(한국 날짜) 묶음, "M월 D일 (오늘/요일)" | 기간 첫날 한국 날짜 |
| O-218 | 내역 비어 있음 | 결과 0건 | /points/history | GET /v1/owner/points/ledger | - | 기간 내 원장 없음 | "이 기간에 포인트 내역이 없어요", 필터 없으면 "충전하거나 캠페인을 집행하면...", 캠페인 필터면 "다른 기간이나 캠페인을 골라 보세요" | - |
| O-219 | 내역 조회 실패 | 원장 실패 | /points/history | GET /v1/owner/points/ledger | BILLING-023, COMMON-002, 응답 없음 | - | "포인트 내역을 불러오지 못했어요" + 다시 시도 | - |
| O-220 | 기간 필터 | 최근 7일/30일/3개월 | /points/history | GET /v1/owner/points/ledger, GET /v1/owner/points/spend-summary | - | - | 목록 비우고 새로 조회, 같은 값 다시 고르면 무시 | 오늘 포함 7일/30일/3개월 |
| O-221 | 캠페인 필터 | 진행 중/일시중지/종료 캠페인 선택 | /points/history | GET /v1/owner/campaigns, GET /v1/owner/store, GET /v1/owner/points/ledger, GET /v1/owner/points/spend-summary | - | 집행된 캠페인 있음 | 선택지에 제목과 기간, 그 캠페인 내역만, 캠페인 이름 조회 실패면 이름 자리에 "캠페인" | - |
| O-222 | 예약 해제 끝난 집행일 | 예약 + 해제 -> 소진 한 줄 | /points/history | GET /v1/owner/points/ledger | - | 같은 날 같은 캠페인 RESERVE 와 RELEASE | 소진(-) 한 줄 + "예약 NP, 예약 해제 NP" 보조 줄 | 다음 날 00:00 해제 |
| O-223 | 오늘 해제 전 집행일 | 예약만 -> 예약 한 줄 + 오늘 소진 집계 | /points/history | GET /v1/owner/points/ledger, GET /v1/owner/points/spend-summary | - | 오늘 RESERVE, 소진 집계 성공 | "지금까지 소진 NP · 남은 예약은 내일 00:00에 해제돼요" | 오늘 |
| O-224 | 오늘 소진 집계 실패 | spend-summary 실패 | /points/history | GET /v1/owner/points/spend-summary | BILLING-023, COMMON-002, 응답 없음 | - | 내역은 표시, 보조 줄 "남은 예약은 13:30에 해제돼요"만 | 오늘 |
| O-225 | 지난날 해제 안 된 예약 | 지난날 RESERVE 만 | /points/history | GET /v1/owner/points/ledger | - | 전날 해제 실패 | 예약 한 줄, 보조 줄 없음 | 지난날 |
| O-226 | 예약 없는 해제 등 단독 원장 | 묶을 예약 없음, 충전/환불/조정 | /points/history | GET /v1/owner/points/ledger | - | 해당 원장 | 한 줄씩, 충전/해제는 +, 예약/소진/환불은 -, 조정은 받은 부호, 캠페인 없는 줄은 시각 표시 | - |
| O-227 | 내역 이어서 불러오기 | 다음 pageToken | /points/history | GET /v1/owner/points/ledger | - | 20건 초과 | 이어 붙인 뒤 날짜 재묶음, 실패 또는 같은 커서 반환이면 기존 유지 + 다시 시도 | - |
| O-228 | 내역에서 충전 | 충전 버튼 -> 시트 | /points/history -> /points/charge | GET /v1/owner/point-policy | - | - | 잔액 조회 실패면 시트에서 잔액 줄 숨김, 결제 뒤 /points/history 복귀 | - |

### 리포트/비교

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-229 | 분석 불러오기 | 캠페인 목록, 가게 메뉴, 특정 메뉴 종료 캠페인은 상세 재조회 | /store/analytics | GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId}, GET /v1/owner/campaigns/{campaignId}/poster, GET /v1/owner/store | - | 집행 기간 있는 ACTIVE/PAUSED/ENDED | 진행 중/일시중지 먼저, 나머지 종료일 최신순 | - |
| O-230 | 분석 불러오기 실패 | 목록 또는 가게 실패 | /store/analytics | GET /v1/owner/campaigns, GET /v1/owner/store | CAMPAIGN-005, OWNER-002, 응답 없음 | - | "분석을 불러오지 못했어요" + 다시 시도 | - |
| O-231 | 분석할 캠페인 없음 | 대상 0건 | /store/analytics -> /campaigns | - | - | DRAFT/SCHEDULED 만 또는 없음 | "아직 집행한 캠페인이 없어요" + 캠페인 보기 | - |
| O-232 | 분석 캠페인 선택 | 기본 선택, ?campaignId, 시트로 변경 | /store/analytics?campaignId= | - | - | 대상 1건 이상 | 잘못된 id 면 기본 캠페인, 시트에서 고르면 주소 replace | - |
| O-233 | 캠페인 리포트 조회 | 집행 시작일부터 min(오늘, 종료일)까지 30일 단위로 나눠 조회, 이 캠페인만 | /store/analytics | GET /v1/owner/report | - | 집계 행 있음 | "M.D ~ M.D 집계, 집행 M.D ~ M.D", 요약(방문, 소진, 유효 노출, 찜, 발급, 방문당 비용, 무효 노출) | 01:00 집계 |
| O-234 | 리포트 조회 실패 | 구간 중 하나라도 실패 | /store/analytics | GET /v1/owner/report | ANALYTICS-001, COMMON-002, COMMON-003, 응답 없음 | - | "리포트를 불러오지 못했어요" + 다시 시도(실패 요청은 캐시에서 지움) | - |
| O-235 | 집계된 날 없음 | 빈 결과 또는 시작 전 | /store/analytics | GET /v1/owner/report (시작 전이면 호출 안 함) | - | 시작일 > 오늘, 첫날 당일, 첫날 다음 날 01:00 전 | "아직 집계된 날이 없어요 / 수치는 하루 한 번 집계돼 집행 다음 날부터 볼 수 있어요", 0 요약 미표시 | 다음 날 01:00 |
| O-236 | 방문 없는 리포트 | 방문 0 | /store/analytics | GET /v1/owner/report | - | redeem 합 0 | 방문당 비용 "-" | - |
| O-237 | 일별 추이 | 노출(막대), 찜/발급/사용(선), 소진 포인트(막대) | /store/analytics | GET /v1/owner/report | - | 집계 행 있음 | 집계 안 된 날은 0 으로 그리지 않고 오늘은 "집계 중", 지난날은 "집계 전" | 오늘/01:00 전 어제 |
| O-238 | 음수 소진 | 잘못 차감 환급이 더 큰 날 | /store/analytics | GET /v1/owner/report | - | spentAmount < 0 | 그대로 합산 표시 | - |
| O-239 | 캠페인 비교 목록 | 현재 캠페인 + 최근 종료 2건, 다른 캠페인 있을 때만 | /store/analytics | GET /v1/owner/report | - | 비교 대상 2건 이상 | 방문, 소진 표시, 집계 없는 캠페인은 "집계 전", 지금 캠페인은 링크 아님 | - |
| O-240 | 캠페인 비교 실패 | 한 캠페인이라도 리포트 실패 | /store/analytics | GET /v1/owner/report | 응답 없음 | - | "캠페인 비교를 불러오지 못했어요" + 다시 시도 | - |
| O-241 | 비교 화면 진입 (미구현) | 다른 캠페인 누름 | /store/analytics/compare?base=&target= | - | - | - | ComingSoon "캠페인 비교", 뒤로는 이전 또는 /store/analytics | - |
| O-242 | 분석 뒤로 가기 | 상단 뒤로 | /store/analytics | - | - | 홈 또는 가게 관리에서 진입, 직접 진입 | 이전 화면, 직접 진입이면 /store | - |

### QR 사용

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-243 | 카메라 켜기 | 권한 요청 | /qr-scan | - | - | 카메라 있음, HTTPS | "카메라를 켜는 중이에요" 후 전체 화면 카메라, "손님의 쿠폰 QR을 비춰 주세요" | - |
| O-244 | 카메라 권한 거부 | NotAllowedError/SecurityError | /qr-scan | - | - | 권한 거부 | "카메라 권한이 필요해요" + iPhone/Android 설정 안내 + 다시 시도 | - |
| O-245 | 카메라 없음 또는 사용 중 | 기타 오류 | /qr-scan | - | - | 카메라 없음 | "카메라를 켤 수 없어요 / 쓸 수 있는 카메라를 찾지 못했어요..." + 다시 시도 | - |
| O-246 | 보안 연결 아님 | HTTP 접속 | /qr-scan | - | - | localhost 아닌 HTTP | "보안 연결(HTTPS) 주소에서만 카메라를 쓸 수 있어요." 다시 시도 없음 | - |
| O-247 | QR 인식기 준비 실패 | 디코더 로드 실패 | /qr-scan | - | - | - | "QR 인식을 준비하지 못했어요" + 다시 시도 | - |
| O-248 | 사용 처리 성공 (mock) | QR 인식 -> 읽기 멈춤 -> 처리 | /qr-scan | - (mock, POST /v1/owner/coupon-usages 호출 안 함) | - | mock 형식 토큰, 60초 이내, 사용 가능 시간 안 | 진동, "사용 처리했어요" + 쿠폰 이름 + 시각, 다음 손님 스캔 | 발급 후 60초, 쿠폰 사용 가능 시간 |
| O-249 | QR 만료 (mock) | 발급 60초 초과 | /qr-scan | - | - | 오래된 토큰 | "QR이 만료됐어요" + 다시 스캔 | 60초 |
| O-250 | 이미 사용된 QR (mock) | 같은 토큰 재사용 | /qr-scan | - | - | 사용한 토큰 | "이미 사용된 QR이에요" | - |
| O-251 | 이미 사용한 쿠폰 (mock) | 쿠폰 REDEEMED | /qr-scan | - | - | mock-redeemed | "이미 사용한 쿠폰이에요" | - |
| O-252 | 다른 가게 쿠폰 (mock) | 가게 불일치 | /qr-scan | - | - | mock-other-store | "다른 가게 쿠폰이에요" | - |
| O-253 | 사용 가능 시간 아님 (mock) | 시간 밖 | /qr-scan | - | - | mock-not-usable-time 또는 현재 시각이 시간 밖 | "지금은 쓸 수 없는 쿠폰이에요" | 사용 가능 시간 |
| O-254 | 쿠폰 QR 아님 (mock) | 형식 불일치 | /qr-scan | - | - | 아무 QR | "쿠폰 QR이 아니에요" | - |
| O-255 | 개발용 테스트 QR | 개발 모드 버튼 7종 | /qr-scan | - | - | import.meta.env.DEV | 카메라 없이 결과 화면 확인 | - |
| O-256 | 스캔 닫기 | 닫기 | /qr-scan -> /home | - | - | - | 이전 화면, 직접 진입이면 /home, 처리 중 결과는 버림 | - |

### 공통 오류

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| O-257 | Access 만료 자동 재발급 | 401 AUTH-005 -> 재발급 -> 원 요청 한 번 더 | 모든 로그인 화면 | POST /v1/owner/auth/tokens:refresh + 원 요청 | AUTH-005 | Access 만료, Refresh 유효 | 사용자 개입 없이 원 요청 성공 | Access 30분 |
| O-258 | 동시 401 | 여러 요청 동시 401 | 모든 로그인 화면 | POST /v1/owner/auth/tokens:refresh | AUTH-005 | - | 재발급은 한 번만, 늦게 401 받은 요청은 바뀐 쿠키로 다시 보냄 (재사용 감지 방지) | - |
| O-259 | 재발급 불가 (세션 종료) | 재발급 401 | 모든 로그인 화면 -> /login | POST /v1/owner/auth/tokens:refresh | AUTH-003, AUTH-004 | Refresh 만료/없음, 재사용 감지로 전부 로그아웃 | 로그인 상태 지움, RequireAuth 가 /login | Refresh 만료 |
| O-260 | 재발급 일시 장애 | 재발급 503 또는 응답 없음 | 모든 로그인 화면 | POST /v1/owner/auth/tokens:refresh | AUTH-002, 응답 없음 | 토큰 저장소 장애 | 같은 Refresh 로 다시 보내지 않고 원 요청 실패로 처리, 각 화면 오류 상태 | - |
| O-261 | 응답 없음 | 네트워크 끊김, 10초 타임아웃, 프록시 502 | 모든 화면 | 모든 API | 응답 없음 | - | code 없는 실패, 각 화면 기본 오류와 다시 시도 | 10초 |
| O-262 | 서버 공통 오류 | 500, 404 경로, 405, 413, 415, 형식 오류 | 모든 화면 | 모든 API | COMMON-001, COMMON-002, COMMON-003, COMMON-004, COMMON-005, COMMON-006, COMMON-007 | - | 화면별 기본 문구 (4xx 는 일부 화면이 "다시 확인" 문구) | - |
| O-263 | 점주 아닌 계정 쿠키 | 역할 불일치 403 | 모든 화면 | GET /v1/owner/me 등 | AUTH-006 | 사용자 또는 관리자 쿠키 | 앱 시작 시 로그인 전으로 처리, 그 밖의 요청은 실패 처리 | - |
| O-264 | 사용 중 점주 상태 변경 | 정지 또는 상태 변경 뒤 ACTIVE 전용 API | /home, /store, /campaigns/*, /points/* | 정산 API, 캠페인 API, GET /v1/owner/store | BILLING-023, CAMPAIGN-001, OWNER-002 | 관리자 정지 등 | 각 화면 오류 또는 "입점을 마친 점주만..." 문구, 새로고침하면 O-005 | - |
| O-265 | 정의되지 않은 주소 | 라우터에 없는 경로 | 예: /foo, /signup/foo | - | - | - | 전용 404 화면 없이 라우터 기본 오류 화면 | - |
| O-266 | 입점 단계 주소 불일치 | 등록된 단계 경로와 pathname 불일치 | /signup/* | - | - | 예: 끝에 슬래시 | /signup/terms 로 replace 후 진행 상태 단계로 | - |

## 대조

### (a) 점주 앱 route 와 커버 ID

| route | 가드 | 커버 ID |
|---|---|---|
| / | 없음 (-> /login) | O-002 |
| /login | guest | O-002, O-008, O-009, O-016~O-022 |
| /signup | guest | O-009, O-010~O-015 |
| /signup/terms | onboarding | O-003, O-018, O-029, O-031~O-035 |
| /signup/store | onboarding | O-029, O-036~O-042 |
| /signup/location | onboarding | O-029, O-038, O-043~O-048 |
| /signup/hours | onboarding | O-029, O-049~O-051 |
| /signup/images | onboarding | O-029, O-052~O-063 |
| /signup/menu | onboarding | O-029, O-064~O-074 |
| /signup/complete | onboarding | O-069, O-070, O-075~O-077 |
| /home | active (TabLayout) | O-004, O-017, O-078~O-095 |
| /campaigns | active (TabLayout) | O-111, O-159~O-173, O-185 |
| /store | active (TabLayout) | O-023~O-026, O-096~O-099 |
| /campaigns/new | active | O-107~O-110 |
| /campaigns/ended | active | O-186~O-193 |
| /campaigns/:id | active | O-114, O-174~O-178 |
| /campaigns/:id/complete | active | O-139, O-140 |
| /campaigns/:id/edit (index -> coupon) | active | O-115 |
| /campaigns/:id/edit/coupon | active | O-112, O-113, O-116~O-125 |
| /campaigns/:id/edit/poster | active | O-141~O-152, O-155 |
| /campaigns/:id/edit/target | active | O-126~O-128 |
| /campaigns/:id/edit/budget | active | O-129~O-136 |
| /campaigns/:id/edit/review | active | O-137~O-139, O-153~O-158 |
| /store/hours | active | O-100~O-103, O-106 |
| /store/images | active | O-104, O-106 |
| /store/menus | active | O-105, O-106 |
| /store/analytics | active | O-229~O-240, O-242 |
| /store/analytics/compare | active (ComingSoon) | O-241 |
| /points/history | active | O-215~O-228 |
| /points/charge | active | O-136, O-198~O-205, O-214 |
| /points/charge/success | active | O-206~O-212, O-214 |
| /points/charge/fail | active | O-213, O-214 |
| /qr-scan | active | O-243~O-256 |
| 정의되지 않은 경로 | - | O-265 |

### (b) 점주 앱이 부르는 endpoint 와 커버 ID

| endpoint | 호출 위치 | 커버 ID |
|---|---|---|
| POST /v1/owner/auth/tokens | 로그인, 가입 직후 로그인 | O-011, O-015, O-017~O-022 |
| POST /v1/owners | 회원가입 | O-011~O-015 |
| DELETE /v1/owner/auth/tokens | 로그아웃, 입점 첫 단계 뒤로 | O-023~O-025, O-031 |
| POST /v1/owner/auth/tokens:refresh | 모든 요청의 401 처리 | O-002, O-007, O-024, O-257~O-260 |
| GET /v1/owner/me | 앱 시작, 입점 완료 후 홈, OWNER-002 복구 | O-001~O-007, O-074~O-077, O-094 |
| PATCH /v1/owner/me/tutorial | 첫 방문 튜토리얼 | O-091~O-094 |
| GET /v1/owner/store | 입점 진행 상태, 가게 화면 전반, 쿠폰 저장 전 storeId | O-027~O-029, O-034, O-078, O-096, O-100, O-104, O-105, O-116, O-121, O-141, O-159, O-229 |
| PUT /v1/owner/terms-agreements | 약관 단계 | O-033~O-035 |
| PUT /v1/owner/store/basic | 가게 기본 정보 | O-037~O-041 |
| PUT /v1/owner/store/location | 매장 위치 | O-038, O-047, O-048 |
| PUT /v1/owner/store/hours | 입점 영업시간 (가게 관리 수정은 mock) | O-050, O-051 |
| POST /v1/owner/store-images:prepareUpload | 사진 단계 | O-052, O-054, O-055, O-057~O-059 |
| POST /v1/owner/store-images:confirmUpload | 사진 단계 | O-052, O-054, O-055, O-057 |
| POST /v1/owner/store-images:complete | 사진 단계 완료 | O-061~O-063 |
| POST /v1/owner/store-menus | 메뉴 등록 | O-065, O-066 |
| POST /v1/owner/store-menu-images:prepareUpload | 메뉴 사진 | O-065, O-067, O-068 |
| POST /v1/owner/store-menu-images:confirmUpload | 메뉴 사진 | O-065, O-067, O-068 |
| POST /v1/owner/store-menus:complete | 메뉴 단계 완료 | O-069~O-071, O-073 |
| POST /v1/owner/store:complete | 최종 등록 | O-069, O-070, O-072~O-074 |
| GET /v1/owner/campaigns | 홈, 목록, 새 캠페인, 종료 목록, 내역 필터, 분석 | O-078, O-107~O-110, O-159~O-162, O-185~O-191, O-221, O-229 |
| GET /v1/owner/campaigns/{campaignId} | 목록 상세 채움, 작성 화면, 상세, 완료 화면, 분석 | O-078, O-112~O-114, O-140, O-156, O-174, O-177, O-229 |
| POST /v1/owner/campaigns | 쿠폰 조건 저장 | O-121~O-125 |
| PATCH /v1/owner/campaigns/{campaignId} | 노출 대상, 예산과 기간 | O-127, O-128, O-132~O-135 |
| GET /v1/owner/templates | 포스터 단계 | O-141~O-143 |
| POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload | 포스터 이미지 | O-146~O-148 |
| POST /v1/owner/campaigns/{campaignId}/poster | 포스터 저장 | O-149~O-152 |
| GET /v1/owner/campaigns/{campaignId}/poster | DRAFT 상세 채움, 확인 단계, 상세 | O-108, O-112, O-113, O-137, O-153, O-176 |
| POST /v1/owner/campaigns/{campaignId}:activate | 활성화 요청 | O-139, O-154~O-158 |
| POST /v1/owner/campaigns/{campaignId}:cancel | 대기 중 취소 | O-164, O-170~O-172 |
| POST /v1/owner/campaigns/{campaignId}:pause | 캠페인 중단 | O-165, O-170~O-172 |
| POST /v1/owner/campaigns/{campaignId}:resume | 다시 시작 | O-166, O-170~O-172 |
| POST /v1/owner/campaigns/{campaignId}:end | 캠페인 종료 | O-167, O-168, O-170, O-171 |
| GET /v1/owner/point-policy | 충전 시트 | O-085, O-088, O-096, O-194, O-195 |
| GET /v1/owner/points/balance | 홈, 가게 관리, 예산/확인 단계, 내역 | O-078, O-096, O-129, O-131, O-137, O-215, O-216 |
| GET /v1/owner/points/ledger | 포인트 내역 | O-217~O-222, O-225~O-227 |
| GET /v1/owner/points/spend-summary | 포인트 내역 오늘 소진 | O-217, O-220, O-221, O-223, O-224 |
| POST /v1/owner/payments | 충전 주문 | O-136, O-201~O-204 |
| POST /v1/owner/payments/{paymentId}:confirm | 승인 확정 | O-136, O-182, O-206, O-208~O-212 |
| GET /v1/owner/payments/{paymentId} | 승인 미확정 폴링 | O-208~O-210 |
| GET /v1/owner/report | 분석, 비교 | O-233~O-240 |

### (c) 위 endpoint 에서 나올 수 있는 오류 코드와 커버 ID

| 오류 코드 | 나오는 endpoint | 커버 ID |
|---|---|---|
| AUTH-001 | POST /v1/owner/auth/tokens | O-015, O-019 |
| AUTH-002 | POST /v1/owner/auth/tokens, POST /v1/owner/auth/tokens:refresh, DELETE /v1/owner/auth/tokens | O-006, O-022, O-025, O-031, O-260 |
| AUTH-003 | POST /v1/owner/auth/tokens:refresh | O-002, O-024, O-259 |
| AUTH-004 | POST /v1/owner/auth/tokens:refresh | O-024, O-259 |
| AUTH-005 | 인증이 필요한 모든 endpoint | O-002, O-024, O-257, O-258 |
| AUTH-006 | 인증이 필요한 모든 endpoint (역할 불일치) | O-025, O-263 |
| OWNER-001 | POST /v1/owners | O-012 |
| OWNER-002 | GET /v1/owner/store, PATCH /v1/owner/me/tutorial, PUT /v1/owner/terms-agreements, PUT /v1/owner/store/basic, PUT /v1/owner/store/location, PUT /v1/owner/store/hours, POST /v1/owner/store:complete | O-028, O-074, O-094, O-264 |
| TERMS-001 | PUT /v1/owner/terms-agreements | O-034 |
| STORE-001 | 단계 저장 PUT 3종, POST /v1/owner/store-images:complete, POST /v1/owner/store-menus:complete, POST /v1/owner/store:complete | O-039, O-040, O-051, O-073 |
| STORE-002 | 약관, 단계 저장, 사진/메뉴 업로드와 완료 | O-035, O-038, O-040, O-048, O-051, O-054, O-063, O-066 |
| STORE-003 | 단계 저장, 사진/메뉴 업로드와 완료, 최종 등록 | O-040, O-042, O-051, O-054, O-066 |
| STORE-004 | 단계 저장, 사진/메뉴 업로드와 완료, 최종 등록 | O-041, O-051, O-054, O-066, O-073 |
| STORE-005 | POST /v1/owner/store-images:prepareUpload, POST /v1/owner/store-menu-images:prepareUpload | O-054, O-067 |
| STORE-006 | prepareUpload 2종, confirmUpload 2종 | O-054, O-067 |
| STORE-007 | POST /v1/owner/store-images:prepareUpload | O-059 |
| STORE-008 | POST /v1/owner/store-images:confirmUpload | O-057 |
| STORE-009 | POST /v1/owner/store-images:confirmUpload, POST /v1/owner/store-menu-images:confirmUpload | O-054, O-067 |
| STORE-010 | POST /v1/owner/store-images:complete | O-062 |
| STORE-011 | POST /v1/owner/store-menus | O-066 |
| STORE-012 | POST /v1/owner/store-menu-images:prepareUpload, POST /v1/owner/store-menu-images:confirmUpload | O-067 |
| STORE-013 | POST /v1/owner/store-menus:complete | O-071 |
| STORE-014 | POST /v1/owner/store-menu-images:prepareUpload | O-067 |
| STORE-015 | POST /v1/owner/store:complete | O-040, O-072 |
| CAMPAIGN-001 | 캠페인 쓰기 API 전부, 포스터 업로드/저장 | O-123, O-128, O-135, O-148, O-151, O-158, O-171, O-264 |
| CAMPAIGN-002 | POST /v1/owner/campaigns | O-124 |
| CAMPAIGN-003 | POST /v1/owner/campaigns | O-124 |
| CAMPAIGN-004 | POST /v1/owner/campaigns | O-124 |
| CAMPAIGN-005 | 캠페인 상세/수정/상태 변경, 포스터 3종 | O-079, O-110, O-113, O-128, O-135, O-148, O-151, O-156, O-160, O-170, O-177, O-230 |
| CAMPAIGN-006 | PATCH, 포스터 업로드/저장, 상태 변경 5종 | O-128, O-135, O-148, O-151, O-156, O-170 |
| CAMPAIGN-007 | PATCH /v1/owner/campaigns/{campaignId} | O-134 |
| CAMPAIGN-008 | PATCH /v1/owner/campaigns/{campaignId} | O-133 |
| CAMPAIGN-009 | PATCH /v1/owner/campaigns/{campaignId} | O-134 |
| CAMPAIGN-010 | POST /v1/owner/campaigns | O-122 |
| CAMPAIGN-011 | POST /v1/owner/campaigns/{campaignId}:activate | O-157 |
| POSTER-001 | POST /v1/owner/campaigns/{campaignId}/poster | O-150 |
| POSTER-005 | POST /v1/owner/campaigns/{campaignId}/poster | O-150 |
| POSTER-015 | POST /v1/owner/campaigns/{campaignId}/poster | O-150 |
| POSTER-016 | POST /v1/owner/campaigns/{campaignId}/poster | O-150 |
| POSTER-017 | poster:prepareUpload, poster | O-148, O-150 |
| POSTER-018 | poster:prepareUpload, poster | O-148, O-150 |
| POSTER-019 | POST /v1/owner/campaigns/{campaignId}/poster | O-150 |
| POSTER-020 | GET /v1/owner/campaigns/{campaignId}/poster, :activate | O-155, O-176 |
| BILLING-001 | POST /v1/owner/payments | O-203 |
| BILLING-002 | POST /v1/owner/payments | O-203 |
| BILLING-003 | POST /v1/owner/payments/{paymentId}:confirm | O-210 |
| BILLING-004 | POST /v1/owner/payments/{paymentId}:confirm, GET /v1/owner/payments/{paymentId} | O-209, O-211 |
| BILLING-006 | GET /v1/owner/points/balance | O-079, O-097, O-131, O-216 |
| BILLING-007 | POST /v1/owner/payments/{paymentId}:confirm | O-208 |
| BILLING-008 | POST /v1/owner/payments | O-202 |
| BILLING-009 | POST /v1/owner/payments/{paymentId}:confirm | O-210 |
| BILLING-017 | POST /v1/owner/payments/{paymentId}:confirm | O-211 |
| BILLING-023 | /v1/owner/payments/**, /v1/owner/point-policy, /v1/owner/points/** (인터셉터) | O-079, O-097, O-131, O-194, O-204, O-216, O-219, O-224, O-264 |
| ANALYTICS-001 | GET /v1/owner/report (30일 초과) | O-234 |
| COMMON-001 | 모든 endpoint | O-006, O-014, O-079, O-093, O-125, O-152, O-158, O-160, O-171, O-204, O-262 |
| COMMON-002 | 요청 본문/파라미터 검증, IllegalArgumentException | O-013, O-020, O-041, O-051, O-066, O-110, O-124, O-128, O-135, O-189, O-204, O-219, O-224, O-234, O-262 |
| COMMON-003 | 본문 해석 실패, 경로 변수 형식 | O-013, O-020, O-113, O-124, O-177, O-234, O-262 |
| COMMON-004, COMMON-005, COMMON-006, COMMON-007 | 경로 없음, 메서드, 크기, 콘텐츠 유형 | O-262 |
| 429 (본문 미해석) | POST /v1/owner/auth/tokens, POST /v1/owner/auth/tokens:refresh, :activate | O-007, O-021, O-157 |

### (d) 코드에 있으나 시나리오로 만들지 않은 것

| 항목 | 이유 |
|---|---|
| VITE_MOCK_OWNER_STATUS 개발용 로그인 상태 | 로컬 개발 모드에서만 동작, 배포 빌드에서 무시 |
| 로그인 응답 status 가 SUSPENDED/WITHDRAWN 인 프론트 분기 | 백엔드가 canLogin 아니면 AUTH-001 로 먼저 거절해 도달 불가 |
| ACTIVE 로그인 응답에 tutorialViewed 누락 분기 | 백엔드가 ACTIVE 면 항상 boolean 을 내려줘 도달 불가 |
| points.ts, report.ts 의 mock 캠페인 ID(숫자 아님) 분기 | 캠페인 API 연동 후 서버 ID 는 항상 숫자 |
| updateBusinessHours 의 같은 요일 중복 검사 | 칩 UI 로 중복 요일을 만들 수 없음 (저장 자체는 O-102) |
| savePosterStep 의 포스터 미완성 거절 | canProceed 가 미완성이면 다음을 막아 호출되지 않음 |
| 확인 단계 PointSummary 외 충전 실패 분기 차이 | O-131, O-136 과 동일 컴포넌트 |
| *.stories.tsx | Storybook 전용 |
| 백엔드에 있으나 점주 앱이 부르지 않는 endpoint: GET /v1/owner/onboarding, GET /v1/owner/payments, POST /v1/owner/refund-requests, GET /v1/owner/refund-requests, GET /v1/owner/refund-requests/preview, POST /v1/owner/coupon-usages, GET /v1/owner/campaigns/{campaignId}/coupons/summary, GET /v1/owner/campaigns/{campaignId}/analytics/funnel, GET /v1/owner/stores/{storeId}/coupon-usages, GET /v1/owner/stores/{storeId}/coupon-usages/daily | 프론트 호출 코드 없음 (QR 사용, 쿠폰 사용 추이, 실적은 mock 또는 미표시) |
| PUT /v1/owner/store/hours 의 ACTIVE 분기 (saveActive) | 가게 관리 영업시간 수정이 mock 이라 호출되지 않음 |
| PATCH 로 ACTIVE 캠페인 하루 예산 변경 | 서버는 허용하나 화면에 기능 없음 |
| :resume 을 ADMIN/NO_POINTS 중단 캠페인에 호출 | 서버는 사유를 보지 않고 허용하나 화면이 OWNER 에만 버튼을 둠 |
| :end 를 ADMIN 중단 캠페인에 호출 | 서버는 PAUSED 면 허용하나 화면에 버튼 없음 |
| 플랫폼 설정 조회 (노출 단가, 최소 하루 예산) | api/platform.ts 가 mock 고정값, 서버 호출 없음 (영향은 O-129, O-134) |
| 약관 본문과 버전 | mocks/terms.ts 고정 문구, 서버 조회 없음 (O-032) |
| errors.txt 의 나머지 코드 (ADMIN-*, ADSERVING-*, ANALYTICS-002~005, AUTH-007, BILLING-005, 010~016, 018~022, CAMPAIGN-012, COUPON-*, MEMBER-*, OPS-*, POSTER-002~004, 006~014, 021~025, STORE-016) | 점주 앱이 부르는 endpoint 의 서비스 경로에서 던지지 않음 |
| 01:30 PG 대사, 02:00/04:00 보존 정리, 03:00/11:00/14:00/5분 쿠폰 작업, 03:00 카카오 해제 재시도, 04:00 템플릿 버전 정리, 결제 재조회 워커 | 점주 화면 상태를 직접 바꾸지 않음 (재조회 워커 결과는 O-208, O-209 폴링으로만 관찰) |
