# 사용자 앱 시나리오

범위: lc-frontend `apps/user`. 시나리오 289개 (U-001 ~ U-289).

기준 코드: lc-backend develop `9f4ab523`, lc-frontend `3f3e056` (2026-10-22). 개요와 갱신 방법은 [README](README.md)에 있다.

기준: lc-frontend `apps/user/src`(라우터 `app/router.tsx`)와 백엔드 develop(`wt-loadtest-pg`)의 구현 코드. 명세가 아니라 코드에서 뽑았다.
하루 발급 한도 3장, 발급 11:00~14:00, QR 60초, 피드 60초 6회, 인증 경로 IP 분당 10회는 서버 기본값(`application.yml`, `FeedRateLimitRepository`, `AuthRateLimitFilter`)이다.
화면의 시각 판단(11:00, 14:00, 00:00)은 휴대폰 시계, 서버 판정은 Asia/Seoul 서버 시계를 쓴다.

시나리오 수: 289

### 인증/온보딩

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-001 | 첫 실행 스플래시 | 앱 주소 / 접속 | / | - | - | 비로그인(localStorage 로그인 정보 없음) | 스플래시 표시 후 /login 으로 replace 이동, 뒤로 가기로 스플래시에 돌아오지 않음 | 1.5초 타이머 |
| U-002 | 로그인 회원이 guest 화면 접근 | / 또는 /login 직접 접근 | /, /login | - | - | 로그인 정보 있음, isOnboarded=true | RequireAuth(guest)가 /swipe 로 replace | - |
| U-003 | 온보딩 미완료 회원이 다른 화면 접근 | /, /swipe 등 접근 | /, /login, /swipe, /onboarding | - | - | 로그인 정보 있음, isOnboarded=false | /onboarding 으로 보낸 뒤 index 가 /onboarding/consent 로 replace | - |
| U-004 | 비로그인으로 보호 화면 접근 | /swipe, /onboarding 등 접근 | /swipe, /onboarding, /my | - | - | 로그인 정보 없음 | / 로 replace, 스플래시 후 로그인 | - |
| U-005 | 없는 주소 접근 | /abc 접근 | * | - | - | 아무 상태 | / 로 replace 후 가드가 상태별 첫 화면으로 다시 보냄 | - |
| U-006 | 카카오 로그인 시작 | "카카오로 10초 만에 시작하기" 누름 | /login | GET /v1/auth/kakao/authorize | - | 비로그인 | 버튼만 "카카오로 이동 중이에요" 로딩, state 와 목적(login)을 sessionStorage 저장 후 카카오 인가 화면으로 이동 | state 유효 5분(서버 Redis) |
| U-007 | 로그인 버튼 연타 | 이동 전 버튼 여러 번 누름 | /login | GET /v1/auth/kakao/authorize | - | 같은 목적 진행 중 | 인가 주소를 한 번만 받아 state 덮어쓰기 없음 | - |
| U-008 | 인가 주소 발급 실패 | 버튼 누름 | /login | GET /v1/auth/kakao/authorize | COMMON-001 | 서버 오류 또는 네트워크 끊김 | "로그인하지 못했어요, 네트워크 상태를 확인한 뒤 다시 시도해 주세요" 화면과 "카카오로 다시 시도하기" | - |
| U-009 | 인가 주소에 state 없음 | 버튼 누름 | /login | GET /v1/auth/kakao/authorize | - | 응답 URL 에 state 파라미터 없음 | 클라이언트 예외로 로그인 실패 화면 | - |
| U-010 | 카카오 화면에서 뒤로 가기 | 카카오에서 브라우저 뒤로 가기(bfcache 복원) | /login | - | - | 페이지 persisted 복원 | 버튼이 idle 로 돌아오고 시작 잠금 해제, 다시 누를 수 있음 | - |
| U-011 | 로그인 성공(온보딩 완료 회원) | 카카오 동의 후 콜백 | /oauth/callback | POST /v1/auth/tokens | - | 기존 ACTIVE 회원, onboardingCompleted=true, state 일치 | "로그인하고 있어요" 후 HttpOnly 쿠키 설정, /swipe 로 replace | - |
| U-012 | 로그인 성공(신규 또는 온보딩 미완료) | 카카오 동의 후 콜백 | /oauth/callback | POST /v1/auth/tokens | - | 처음 가입 또는 onboardingCompleted=false | 서버가 가입까지 처리, /onboarding 으로 replace | - |
| U-013 | 탈퇴 후 재가입 | 탈퇴했던 카카오 계정으로 로그인 | /oauth/callback | POST /v1/auth/tokens | - | 탈퇴 이력 있음, 정지 이력 없음, 연결 해제 완료 | newMember=true, 처음부터 온보딩(당일 재가입 차단 코드는 주석 처리돼 당일도 가능) | - |
| U-014 | 카카오 동의 화면에서 취소 | 취소 후 code 없이 콜백 | /oauth/callback | - | - | 쿼리에 code 없음 | 서버 요청 없이 "로그인하지 못했어요" 화면 | - |
| U-015 | 다른 탭에서 시작한 콜백 | 다른 탭/브라우저 state 로 콜백 | /oauth/callback | - | - | sessionStorage state 없음 또는 불일치 | 서버 요청 없이 로그인 실패 화면(개발 모드 콘솔 경고) | - |
| U-016 | 카카오 인증 실패 | 콜백 후 서버 교환 실패 | /oauth/callback | POST /v1/auth/tokens | AUTH-007 | state 만료(5분 초과)/재사용, ID 토큰 검증 실패 | 일반 로그인 실패 화면(코드별 문구 구분 없음) | state 5분 |
| U-017 | 정지 이력 회원 로그인 | 콜백 | /oauth/callback | POST /v1/auth/tokens | MEMBER-003 | 회원 상태 ACTIVE 아님 또는 정지 이력 | 일반 로그인 실패 화면, 서버 문구 미노출 | - |
| U-018 | 연결 해제 대기 중 재가입 | 탈퇴 직후 재로그인 | /oauth/callback | POST /v1/auth/tokens | MEMBER-005 | kakao_unlink_failure 행 남음 | 일반 로그인 실패 화면, 서버 문구 미노출 | 03:00 연결 해제 재시도 후 해소 |
| U-019 | 토큰 저장소 장애 로그인 | 콜백 | /oauth/callback | POST /v1/auth/tokens | AUTH-002 | Refresh Token 백업 저장 실패 | 일반 로그인 실패 화면 | - |
| U-020 | 로그인 요청 IP 제한 | 같은 IP 에서 로그인 분당 10회 초과 | /oauth/callback | POST /v1/auth/tokens | (코드 없음, 429 본문 없음) | 1분 고정 창 10회 초과 | 일반 로그인 실패 화면, Retry-After 미사용 | 1분 창 |
| U-021 | 로그인 입력 검증 실패 | 콜백 | /oauth/callback | POST /v1/auth/tokens | COMMON-002, COMMON-003 | code/state 비어 있음 등 | 일반 로그인 실패 화면 | - |
| U-022 | 로그인 실패 후 다시 시도 | "카카오로 다시 시도하기" | /oauth/callback, /login | GET /v1/auth/kakao/authorize | - | 실패 화면 | 다시 카카오로 이동, 실패하면 실패 화면 유지 | - |
| U-023 | 앱 재실행 시 세션 확인 | 앱 로드 | 모든 화면 | GET /v1/members/me | - | localStorage 로그인 정보 있음, 쿠키 유효 | 닉네임과 온보딩 여부를 서버 값으로 갱신 | - |
| U-024 | 앱 재실행 시 세션 만료 | 앱 로드 | 모든 화면 | GET /v1/members/me, POST /v1/auth/tokens:refresh | AUTH-005, AUTH-003 | Access/Refresh 모두 만료 | 로그인 정보 삭제, / 로 이동 | Refresh 14일 |
| U-025 | 앱 재실행 시 네트워크 오류 | 앱 로드 | 모든 화면 | GET /v1/members/me | COMMON-001 | 서버 오류 또는 오프라인 | 저장된 로그인 정보 그대로 유지 | - |
| U-026 | 약관 필수 미동의 | 약관 화면 진입 | /onboarding/consent | - | - | 필수 2개 중 하나라도 미체크 | "동의하고 계속하기" 비활성, "위치정보 미동의 시 피드와 지도는 이용할 수 없어요" 안내 | - |
| U-027 | 전체 동의 | "전체 동의" 체크/해제 | /onboarding/consent | - | - | - | 이용약관, 개인정보, 위치정보 3개 동시 on/off | - |
| U-028 | 약관 내용 보기 | 항목 오른쪽 버튼 | /onboarding/consent | - | - | mock 약관(mocks/terms) | 바텀시트에 버전과 섹션 표시, 내용만 스크롤 | - |
| U-029 | 약관 동의 후 계속 | 필수 체크 후 버튼 | /onboarding/consent | - | - | 필수 동의 완료 | /onboarding/personalization 이동(약관 동의 내역은 서버에 저장하지 않음) | - |
| U-030 | 맞춤 정보 미선택 | 화면 진입 | /onboarding/personalization | - | - | 성별 또는 연령대 미선택 | 버튼 비활성 "성별, 연령대를 선택해 주세요" | - |
| U-031 | 온보딩 저장 성공 | 성별/연령 선택 후 "계속하기" | /onboarding/personalization | PUT /v1/members/me/onboarding | - | 미완료 ACTIVE 회원 | locationOptIn=약관 위치 동의값, notificationOptIn=false 저장, /onboarding/location 으로 replace | - |
| U-032 | 온보딩 이미 완료 | 재전송 또는 뒤로 와서 다시 저장 | /onboarding/personalization | PUT /v1/members/me/onboarding | MEMBER-002 | 서버에 이미 완료 | 성공으로 보고 /onboarding/location 이동 | - |
| U-033 | 온보딩 저장 실패 | "계속하기" | /onboarding/personalization | PUT /v1/members/me/onboarding | MEMBER-001, COMMON-002, COMMON-001 | 탈퇴 회원, 검증 실패, 서버 오류, 네트워크 | 오류 토스트 "저장하지 못했어요" 2.5초, 버튼 다시 활성 | - |
| U-034 | 맞춤 정보 화면 직접 진입/새로고침 | 주소로 바로 열거나 새로고침 후 저장 | /onboarding/personalization | PUT /v1/members/me/onboarding | - | OnboardingProvider 상태 초기화(동의 모두 false) | 필수 약관 미동의로도 진행 가능, 위치 동의는 false 로 저장됨 | - |
| U-035 | 위치 단계에서 새로고침 | 온보딩 위치 화면 새로고침 | /onboarding/location | GET /v1/members/me | - | 서버 onboardingCompleted=true, 로컬 false | AuthProvider 가 isOnboarded=true 로 갱신, 가드가 /swipe 로 보내 위치와 튜토리얼을 건너뜀 | - |
| U-036 | 튜토리얼 넘기기 | "다음" 또는 가로 50px 이상 밀기 | /onboarding/tutorial | - | - | 3장 | 왼쪽 밀기 다음 장, 오른쪽 밀기 이전 장, 마지막 장에서 밀어도 종료 안 함 | - |
| U-037 | 튜토리얼 완료/건너뛰기 | "런치캐치 시작하기" 또는 "건너뛰기" | /onboarding/tutorial | - | - | 온보딩 중 | 로컬 isOnboarded=true, /swipe 로 replace | - |

### 위치

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-038 | 위치 설정 불러오기 | 화면 진입 | /my/location, /onboarding/location | GET /v1/members/me, GET /v1/member-profiles/location | - | - | 로딩 중 회색 틀, 이후 내 위치 영역 표시 | - |
| U-039 | 위치 설정 불러오기 실패 | 화면 진입 | /my/location, /onboarding/location | GET /v1/members/me, GET /v1/member-profiles/location | MEMBER-001, COMMON-001 | 둘 중 하나 실패 | "위치 정보를 불러오지 못했어요" + 다시 시도 | - |
| U-040 | 저장 위치 있음 | 화면 진입 | /my/location, /onboarding/location | GET /v1/member-profiles/location | - | 좌표 있음 | 이름(없으면 도로명, 둘 다 없으면 "GPS로 찾은 위치") 카드와 "삭제" 버튼, 이름이 있으면 주소를 아래 줄에 | - |
| U-041 | 저장 위치 없음 | 화면 진입 | /my/location, /onboarding/location | GET /v1/member-profiles/location | - | 네 값 모두 null | 점선 칸 "아직 설정한 위치가 없어요" | - |
| U-042 | 위치 이용 미동의 | 화면 진입 | /my/location, /onboarding/location | GET /v1/members/me | - | consents.locationOptIn=false | "현재 위치로 설정" 비활성, "위치 이용에 동의하지 않아 주소로만 설정할 수 있어요" | - |
| U-043 | GPS 현재 위치 찾기 성공 | "현재 위치로 설정" | /my/location, /onboarding/location | - | - | 위치 동의, 브라우저 권한 허용 | 카카오 좌표->주소 변환 후 "현재 위치" 시트(지도 핀, 주소, 이름 칸) | 캐시 1분, 타임아웃 10초 |
| U-044 | GPS 주소 변환 실패 | "현재 위치로 설정" | /my/location, /onboarding/location | - | - | 카카오 coord2Address 오류 | 시트에 "주소를 찾지 못했어요", 좌표만으로 저장 가능 | - |
| U-045 | GPS 권한 거부 | "현재 위치로 설정" | /my/location, /onboarding/location | - | - | 브라우저 위치 권한 거부 | 오류 토스트 "위치 권한이 꺼져 있어요, 주소로 찾아 주세요" | - |
| U-046 | GPS 실패 | "현재 위치로 설정" | /my/location, /onboarding/location | - | - | geolocation 미지원, 위치 불가, 10초 초과 | 오류 토스트 "현재 위치를 찾지 못했어요, 주소로 찾아 주세요" | 10초 |
| U-047 | 주소 검색 1글자 | 검색어 1글자 입력 | /my/location, /onboarding/location | - | - | - | 검색하지 않고 내 위치 영역과 하단 버튼 유지 | - |
| U-048 | 주소 검색 진행 | 2글자 이상 입력 | /my/location, /onboarding/location | - | - | 카카오 SDK | 하단 버튼 숨김, 스켈레톤 후 장소 검색 결과(없으면 주소 검색), 검색어 부분 강조 | 입력 멈춤 300ms |
| U-049 | 주소 검색 결과 없음 | 검색 | /my/location, /onboarding/location | - | - | 장소/주소 모두 0건 | "검색 결과가 없어요" | - |
| U-050 | 주소 검색 실패 | 검색 | /my/location, /onboarding/location | - | - | 카카오 검색 ERROR 또는 SDK 로드 실패 | "검색하지 못했어요" + 다시 시도 | - |
| U-051 | 검색 결과 선택 | 결과 항목 누름 | /my/location, /onboarding/location | - | - | - | "선택한 위치" 시트, 장소 이름을 이름 칸에 미리 채움 | - |
| U-052 | 위치 저장 성공(설정) | 시트 "저장" | /my/location | PUT /v1/member-profiles/location | - | 좌표 소수 7자리 반올림, 이름 50자 이하 | 시트 닫힘, 검색 초기화, 토스트 "현재 위치로 설정했어요" 또는 "선택한 위치로 설정했어요", 헤더 위치 즉시 갱신 | - |
| U-053 | 위치 저장 실패 | 시트 "저장" | /my/location, /onboarding/location | PUT /v1/member-profiles/location | MEMBER-001, COMMON-002, COMMON-001 | 탈퇴 회원, 도로명 255자 초과 등 | 시트 안 "위치를 저장하지 못했어요", 시트 유지 | - |
| U-054 | 위치 시트 닫기 | 시트 바깥/닫기 | /my/location, /onboarding/location | - | - | - | 저장하지 않고 닫힘, 입력한 이름 버림 | - |
| U-055 | 위치 삭제 성공 | "삭제" | /my/location, /onboarding/location | DELETE /v1/member-profiles/location | - | 저장 위치 있음(없어도 204) | 토스트 "위치를 삭제했어요", 빈 상태 표시 | - |
| U-056 | 위치 삭제 실패 | "삭제" | /my/location, /onboarding/location | DELETE /v1/member-profiles/location | COMMON-001 | 서버 오류, 네트워크 | 오류 토스트 "위치를 삭제하지 못했어요" | - |
| U-057 | 온보딩 위치 건너뛰기 | 상단 "나중에 하기" | /onboarding/location | - | - | 저장 위치 없음 | /onboarding/tutorial 로 replace | - |
| U-058 | 온보딩 위치 다음 | 상단 "다음" | /onboarding/location | - | - | 저장 위치 있음 | /onboarding/tutorial 로 replace | - |
| U-059 | 온보딩 위치 저장 | 시트 "저장" | /onboarding/location | PUT /v1/member-profiles/location | - | - | 저장 후 바로 /onboarding/tutorial 로 replace | - |
| U-060 | 헤더 위치 문구 | 피드/탐색 진입 | /swipe, /explore | GET /v1/member-profiles/location | - | 로딩/실패/없음/있음 | 로딩 중 빈칸, 실패 "내 위치", 없음 "위치를 설정해 주세요", 있음 "{이름 또는 주소} 주변", 누르면 /my/location | - |

### 피드

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-061 | 하단 탭 이동 | 탭바 누름 | /swipe, /explore, /coupons, /my | - | - | 회원 | 오늘 점심/탐색/쿠폰함/마이 이동, 하위 경로에서도 해당 탭 선택 | - |
| U-062 | 피드 첫 진입 | /swipe 진입 | /swipe | GET /v1/feed, GET /v1/feed-filter, GET /v1/wishlist, GET /v1/member-profiles/location | - | 저장 위치 있음, 후보 있음 | 포스터 회색 틀 후 카드 3장 미리 그림, 받은 순서 유지 | - |
| U-063 | 포스터 표시 | 카드 표시 | /swipe | GET /v1/campaigns/{campaignId}/poster | - | 공개 캠페인, 포스터 있음 | sandbox iframe 에 360px 기준으로 축소해 그림, 같은 주소는 캐시 | - |
| U-064 | 포스터 실패 | 카드 표시 | /swipe | GET /v1/campaigns/{campaignId}/poster | POSTER-022, COMMON-001 | 비공개/없는 캠페인/포스터 없음, 빈 HTML | 대체 포스터(업종, 가게명, 할인 문구), 실패 결과는 캐시하지 않음 | - |
| U-065 | 노출 기록 | 맨 위 카드가 바뀜 | /swipe | POST /v1/impressions | - | 저장 위치 좌표 받음, 탭이 보임 | serveId 당 1회 전송(sessionStorage 50개 기억), 실패 시 1회 재전송 후 포기, 화면 변화 없음 | 서버 30분 노출 인정 |
| U-066 | 노출 지연 | 앱이 백그라운드 | /swipe | POST /v1/impressions | - | visibilityState hidden | 다시 보일 때 전송 | - |
| U-067 | 저장 위치 조회 실패 시 노출 | 카드 표시 | /swipe | GET /v1/member-profiles/location, POST /v1/impressions | COMMON-001 | 피드는 받았으나 위치 조회 실패 | 위도/경도가 없어 노출을 보내지 않음 | - |
| U-068 | 찜 스와이프 | 오른쪽으로 카드 폭 30% 이상 밀기 또는 "찜하기" | /swipe | POST /v1/swipes | - | 오늘 후보 카드 | 응답 기다리지 않고 다음 카드, 헤더 찜 수 +1, 서버 찜 목록에 추가되고 그날 피드 제외 | - |
| U-069 | 첫 찜 토스트(오픈 전) | 화면 진입 후 첫 찜 | /swipe | POST /v1/swipes | - | - | "찜 목록에 담았어요" 둘째 줄 "11:00부터 쿠폰을 받을 수 있어요" | 11:00 전 |
| U-070 | 첫 찜 토스트(발급 중) | 화면 진입 후 첫 찜 | /swipe | POST /v1/swipes | - | - | 둘째 줄 "지금 바로 쿠폰을 받을 수 있어요" | 11:00~14:00 |
| U-071 | 첫 찜 토스트(마감 후) | 화면 진입 후 첫 찜 | /swipe | POST /v1/swipes | - | - | 둘째 줄 "내일 11:00부터 쿠폰을 받을 수 있어요" | 14:00 이후 |
| U-072 | 패스 스와이프 | 왼쪽으로 밀기 또는 "패스" | /swipe | POST /v1/swipes | - | - | 다음 카드, 그날 피드 제외, 실패해도 알리지 않음 | - |
| U-073 | 찜 전송 실패 | 찜 후 서버 실패(1회 재전송 포함) | /swipe | POST /v1/swipes, GET /v1/wishlist | ADSERVING-004, COMMON-002, COMMON-001 | 오늘 후보가 아닌 캠페인 등 | 찜 수를 서버 값으로 다시 받고 토스트 "찜하지 못했어요, 잠시 후 다시 시도해 주세요" | - |
| U-074 | 같은 카드 재전송 | 같은 serveId 로 다시 스와이프 | /swipe | POST /v1/swipes | - | 이미 넘긴 카드 | 서버가 처음 것만 반영(applied=false), 화면 변화 없음 | - |
| U-075 | 짧게 밀기 | 30% 미만 밀고 놓기 | /swipe | - | - | - | 제자리 복귀, 20px 이상 끌면 버튼 위 안내가 방향 표시 | - |
| U-076 | 다음 피드 즉시 받기 | 마지막 카드 넘김 | /swipe | GET /v1/feed | - | nextFeedAvailableAt 지남 | 로딩 후 새 묶음 | - |
| U-077 | 다음 피드 대기 | 마지막 카드 넘김 | /swipe | GET /v1/feed | - | nextFeedAvailableAt 전(60초 6회 소진) | "N초 후 새 카드" 대기 화면 1초 갱신, 시각이 되고 화면이 보이면 자동 재요청 | 60초 창 |
| U-078 | 오늘 카드 모두 봄 | 다음 피드가 비어 옴 | /swipe | GET /v1/feed | - | 이번 화면에서 카드 받은 적 있음, 업종 필터 없음 | "오늘의 포스터를 모두 확인했어요" + "찜한 포스터 보기"(/coupons/wishlist), "주변 가게 둘러보기"(/explore) | - |
| U-079 | 주변에 오늘 쿠폰 없음 | 첫 피드 NO_CANDIDATE | /swipe | GET /v1/feed | - | 반경 내 후보 없음, 업종 필터 없음 | "주변에 오늘 쿠폰이 없어요" + "주변 가게 보기", "위치 바꾸기" | - |
| U-080 | 업종 필터로 비어 있음 | 피드가 비어 옴 | /swipe | GET /v1/feed | - | 업종 필터 1개 이상 | "고른 업종에 오늘 쿠폰이 없어요" + "전체 업종 보기" | - |
| U-081 | 전체 업종 보기 성공 | "전체 업종 보기" | /swipe | PUT /v1/feed-filter, GET /v1/feed | - | 정렬은 유지 | categories=[] 저장, 버튼 표시 "필터"로 복귀, 바로 재요청 | - |
| U-082 | 전체 업종 보기 실패 | "전체 업종 보기" | /swipe | PUT /v1/feed-filter | COMMON-001 | 서버 오류 | 토스트 "필터를 바꾸지 못했어요, 잠시 후 다시 시도해 주세요" | - |
| U-083 | 저장 위치 없음 | 피드 요청 | /swipe | GET /v1/feed | ADSERVING-002 | 저장 좌표 없음 | "위치를 설정해 주세요" + "위치 설정하기"(/my/location), 필터 줄 숨김 | - |
| U-084 | 피드 오류 | 피드 요청 | /swipe | GET /v1/feed | ADSERVING-001, COMMON-001 | 서버 온보딩 미완료, 서버 오류, 네트워크 | "포스터를 불러오지 못했어요" + "다시 시도" | - |
| U-085 | 피드 요청 제한(첫 회) | 피드 요청 | /swipe | GET /v1/feed | ADSERVING-003 | 최근 60초 6회 초과 | Retry-After 초(없으면 60초) 대기 화면, 시각 sessionStorage 저장, 그 시각에 1회 재요청 | 60초 창 |
| U-086 | 피드 요청 제한(재요청도 거절) | 대기 후 재요청 | /swipe | GET /v1/feed | ADSERVING-003 | 이미 1회 대기함 | 반복하지 않고 오류 화면 "포스터를 불러오지 못했어요" | - |
| U-087 | 요청 제한 대기 중 새로고침 | 새로고침 | /swipe | GET /v1/feed | - | 저장된 대기 시각 남음 | 바로 요청하지 않고 남은 시간 이어서 대기 | - |
| U-088 | 피드 세션 복원 | 탭 이동 또는 새로고침 후 복귀 | /swipe | - | - | sessionStorage 묶음, 받은 지 30분 미만, 같은 날 | 피드를 다시 받지 않고 보던 카드부터 | 30분 |
| U-089 | 피드 세션 만료 | 복귀 | /swipe | GET /v1/feed | - | 30분 경과 또는 날짜 바뀜 | 묶음과 노출 기록을 지우고 새로 요청 | 30분, 00:00 |
| U-090 | 자정 후보 재적재 | 날짜가 바뀐 뒤 피드 | /swipe | GET /v1/feed | - | 00:00 ADSERVING_CANDIDATE_LOAD | 새 날 후보로 피드, 전날 넘긴 캠페인도 다시 나올 수 있음 | 00:00 |
| U-091 | 자정 넘긴 카드 스와이프 | 23시대 받은 카드를 00:00 뒤 찜 | /swipe | POST /v1/swipes, GET /v1/wishlist | ADSERVING-004 | 그 캠페인이 새 날 후보 아님 | 찜 실패 토스트, 찜 수 보정 | 00:00 |
| U-092 | 필터 버튼 로딩 | 진입 직후 | /swipe | GET /v1/feed-filter | - | 필터 응답 전 | 필터 버튼 비활성 | - |
| U-093 | 필터 불러오기 실패 | 진입 후 버튼 누름 | /swipe | GET /v1/feed-filter | COMMON-001 | 첫 조회 실패 | 누르면 다시 조회, 성공 시 시트 열림, 실패 시 토스트 "필터를 불러오지 못했어요" | - |
| U-094 | 필터 요약 표시 | 필터 저장 상태 | /swipe | GET /v1/feed-filter | - | 업종/정렬 값 | 기본값 "필터", 업종 2개 이하 "한식,일식"식, 3개 이상 "한식 외 2", 할인율순이면 "할인율순" 추가 | - |
| U-095 | 필터 시트 고르기 | 버튼 누름 | /swipe | - | - | - | 업종 여러 개 선택, "전체"는 모두 해제, 기본값이면 "초기화" 비활성, 정렬 거리순/할인율순 | - |
| U-096 | 필터 저장(업종만, 카드 남음) | 시트 "저장" | /swipe | PUT /v1/feed-filter | - | 보는 카드 있음 | 남은 카드 중 고른 업종 아닌 카드 숨김, 토스트 "필터를 적용했어요" | - |
| U-097 | 필터 저장(정렬 변경) | 시트 "저장" | /swipe | PUT /v1/feed-filter | - | 보는 카드 있음, 정렬 바뀜 | 토스트 "필터를 저장했어요, 정렬은 새로 받는 카드부터 적용돼요" | - |
| U-098 | 필터 적용 후 남은 카드 0 | 시트 "저장" | /swipe | PUT /v1/feed-filter, GET /v1/feed | - | 남은 카드가 모두 다른 업종 | 다음 피드 규칙(즉시 받기 또는 대기)으로 이동 | - |
| U-099 | 카드 없는 화면에서 필터 저장 | 빈/오류/대기 화면에서 저장 | /swipe | PUT /v1/feed-filter, GET /v1/feed | - | 보는 카드 없음 | 토스트 "필터를 저장했어요", 로딩/대기 중이 아니면 바로 재요청 | - |
| U-100 | 필터 저장 실패 | 시트 "저장" | /swipe | PUT /v1/feed-filter | COMMON-002, COMMON-001 | 서버 거절 | 시트 안 "필터를 저장하지 못했어요", 시트 유지 | - |
| U-101 | 오픈 카운트다운 | 카드 위 줄 | /swipe | - | - | - | "쿠폰 오픈까지 HH:MM:SS" 1초마다 갱신, 11:00 되면 사라짐 | 11:00 전 |
| U-102 | 카드 아래 안내(오픈 전) | 카드 표시 | /swipe | - | - | - | "찜해 두면 11:00부터 쿠폰을 받을 수 있어요"(좁으면 짧은 문구) | 11:00 전 |
| U-103 | 카드 아래 안내(발급 중) | 카드 표시 | /swipe | GET /v1/coupons/issuance-limit | - | 오늘 받은 수 < 3 | "오늘 받은 쿠폰 n/3장, 찜 목록에서 바로 받을 수 있어요" | 11:00~14:00 |
| U-104 | 카드 아래 안내(한도 도달) | 카드 표시 | /swipe | GET /v1/coupons/issuance-limit | - | 오늘 받은 수 >= 3 | "오늘 3장을 모두 받았어요, 내일 다시 받을 수 있어요" | 11:00~14:00 |
| U-105 | 카드 아래 안내(한도 조회 실패) | 카드 표시 | /swipe | GET /v1/coupons/issuance-limit | COMMON-001 | 조회 실패 또는 응답 전 | 수 없이 "쿠폰 오픈, 찜 목록에서 바로 받을 수 있어요" | 11:00~14:00 |
| U-106 | 카드 아래 안내(마감 후) | 카드 표시 | /swipe | - | - | - | "찜해 두면 내일 11:00부터 쿠폰을 받을 수 있어요" | 14:00 이후 |
| U-107 | 헤더 찜 수와 로고 | 헤더 | /swipe, /explore | GET /v1/wishlist | - | 찜 목록 | 이름 있고 끝나지 않은 찜만 센 수 표시(0이면 숨김), 실패 시 마지막 값 유지, 로고는 /swipe, 찜 목록은 /coupons/wishlist | - |

### 탐색/가게

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-108 | 탐색 목록 진입 | /explore 진입 | /explore | GET /v1/member-profiles/location, GET /v1/stores, GET /v1/wishlist | - | 저장 위치 있음, 가게 있음 | filter=ALL, pageSize=50, 거리순, "쿠폰 진행 중 N" 구역 첫 가게는 큰 카드 "가장 가까운 쿠폰", 이어서 "다른 가게 N" | 응답 300ms 넘으면 회색 틀 |
| U-109 | 쿠폰 구역 라벨(오픈 전) | 목록 표시 | /explore | - | - | 쿠폰 진행 가게 있음 | 구역 제목 옆 "11:00 오픈" | 11:00 전 |
| U-110 | 쿠폰 구역 라벨(발급 중) | 목록 표시 | /explore | - | - | 쿠폰 진행 가게 있음 | "선착순 발급 중" | 11:00~14:00 |
| U-111 | 쿠폰 구역 라벨(마감 후) | 목록 표시 | /explore | - | - | 쿠폰 진행 가게 있음 | "내일 11:00 오픈" | 14:00 이후 |
| U-112 | 탐색 저장 위치 없음 | 진입 | /explore | GET /v1/member-profiles/location | - | 좌표 null | "위치를 설정해 주세요" + "위치 설정하기" | - |
| U-113 | 가게 목록 실패 | 진입 | /explore | GET /v1/stores | COMMON-002, COMMON-003, COMMON-001 | 서버 오류 등 | "가게를 불러오지 못했어요" + "다시 시도" | - |
| U-114 | 위치 조회 실패 시 탐색 | 진입 | /explore | GET /v1/member-profiles/location | COMMON-001 | 위치 조회 실패, 기억한 위치 없음 | 같은 오류 화면, "다시 시도"는 위치를 다시 받지 않아 화면이 그대로 | - |
| U-115 | 가게 검색 | 검색어 입력 후 검색 키 | /explore | GET /v1/stores | - | query 파라미터 | 주소 ?q= 갱신, "'검색어' 검색 결과 N개 가게"(다음 페이지 있으면 N+), 모든 카드 작은 카드 | - |
| U-116 | 검색 결과 없음 | 검색 | /explore | GET /v1/stores | - | 0건, 검색어 있음 | "검색 결과가 없어요" + "주변 가게 보기"(검색 지움) | - |
| U-117 | 검색어 지우기 | X 버튼 | /explore | GET /v1/stores | - | - | 입력과 ?q 제거 후 다시 받음 | - |
| U-118 | 업종 고르기 | 업종 아이콘 | /explore | GET /v1/stores | - | filter=업종 코드 | 주소 ?category= 갱신, 다시 받음, 고른 아이콘을 줄 안에서 보이게 스크롤 | - |
| U-119 | 업종 결과 없음 | 업종 선택 | /explore | GET /v1/stores | - | 0건, 업종 선택 | "주변에 {업종} 가게가 없어요" + "전체 가게 보기" | - |
| U-120 | 주변 가게 없음 | 진입 | /explore | GET /v1/stores | - | 0건, 검색/업종 없음 | "주변에 가게가 없어요"(버튼 없음) | - |
| U-121 | 다음 페이지 | 목록 끝 300px 앞 | /explore | GET /v1/stores | - | nextPageToken 있음 | 이어 받아 각 구역 아래에 붙임, 중복 가게 제거 | - |
| U-122 | 다음 페이지 실패 | 스크롤 | /explore | GET /v1/stores | COMMON-002, COMMON-001 | 커서 오류 등 | "가게를 더 불러오지 못했어요" + "다시 시도" | - |
| U-123 | 지도 보기 | "지도" 누름 | /explore | - | - | 카카오 SDK, ?view=map | 내 위치 점, 쿠폰 진행 가게만 할인 핀, 모두 보이게 영역 맞춤 | - |
| U-124 | 지도 핀 선택 | 핀 누름 | /explore | - | - | - | 핀 강조, 아래에 가게 카드, 지도 빈 곳 누르면 닫힘 | - |
| U-125 | 지도 쿠폰 가게 없음 | 지도 보기 | /explore | - | - | 쿠폰 진행 가게 0 | "주변에 쿠폰 진행 중인 가게가 없어요", 내 위치 가운데 | - |
| U-126 | 지도 로드 실패 | 지도 보기 | /explore | - | - | 카카오 SDK 로드 실패 | "지도를 불러오지 못했어요" + "다시 시도" | - |
| U-127 | 상세 다녀와 뒤로 가기 | 가게 상세 후 뒤로 | /explore | - | - | 같은 검색/업종/위치, 저장 5분 이내 | 다시 받지 않고 목록과 스크롤 위치 복원 | 5분 |
| U-128 | 스냅샷 만료 | 뒤로 또는 탭으로 진입 | /explore | GET /v1/stores | - | 5분 초과 또는 조건 다름, 탭 진입 | 새로 받음, 탭 진입은 맨 위부터 | 5분 |
| U-129 | 가게 이미지 없음 | 카드 표시 | /explore | - | - | storeImageUrl null 또는 로드 실패 | 회색 바탕 가게 아이콘 | - |
| U-130 | 쿠폰 없는 가게 카드 | 카드 표시 | /explore | - | - | activeCampaign null | "진행 중인 쿠폰 없음" | - |
| U-131 | 가게 상세 불러오기 | 카드 누름 | /stores/:storeId | GET /v1/stores/{storeId}, GET /v1/wishlist, POST /v1/coupon-events:getStatus, GET /v1/coupons/issuance-limit | - | 활성 캠페인 있음 | 가게와 쿠폰 상태를 다 받은 뒤 한 번에 그림, 로딩 중 회색 틀 | - |
| U-132 | 없는 가게 | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | STORE-016 | 없거나 최종 등록 전 | "가게를 찾을 수 없어요" + "다른 가게 둘러보기"(/explore) | - |
| U-133 | 가게 정보 실패 | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | COMMON-003, COMMON-001 | 숫자 아닌 storeId, 서버 오류 | "가게 정보를 불러오지 못했어요" + "다시 시도", "뒤로 가기" | - |
| U-134 | 진행 쿠폰 없는 가게 | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | activeCampaign null | "지금 진행 중인 쿠폰이 없어요", 하트와 하단 버튼 없음 | - |
| U-135 | 쿠폰 정보 실패 | 진입 | /stores/:storeId | GET /v1/wishlist, POST /v1/coupon-events:getStatus, GET /v1/coupons/issuance-limit | COMMON-001 | 셋 중 하나 실패 또는 상태 응답에 그 가게 없음 | 할인 요약 "오늘 ... 쿠폰이 있어요" + "쿠폰 정보를 불러오지 못했어요" + 다시 시도, 하트/하단 버튼 없음 | - |
| U-136 | 가게 포스터 있음 | 진입 | /stores/:storeId | GET /v1/campaigns/{campaignId}/poster | - | 공개 캠페인 포스터 | "오늘의 쿠폰" 아래 A4 포스터 | - |
| U-137 | 가게 포스터 없음 | 진입 | /stores/:storeId | GET /v1/campaigns/{campaignId}/poster | POSTER-022, COMMON-001 | 포스터 없음/실패 | 포스터 자리 없이 표시 | - |
| U-138 | 대표 메뉴 | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | 메뉴 1개 이상 | 받은 순서대로, 할인 대상 메뉴에 "쿠폰 20%" 또는 "쿠폰 3,000원" 표시, 사진 실패 시 아이콘 | - |
| U-139 | 영업 상태(영업 중) | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | 오늘 영업, 영업시간 안 | "영업 중" | 영업시간 안 |
| U-140 | 영업 상태(브레이크) | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | 브레이크 시간 등록 | "브레이크타임" | 브레이크 시간 |
| U-141 | 영업 상태(영업 전) | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | - | "영업 전" | 오픈 전 |
| U-142 | 영업 상태(영업 종료) | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | - | "영업 종료" | 마감 후 |
| U-143 | 영업 상태(오늘 휴무) | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | 오늘 isClosed 또는 시간 미등록 | "오늘 휴무" | - |
| U-144 | 도보 거리 | 진입 | /stores/:storeId | GET /v1/member-profiles/location | - | 저장 좌표 있음/없음 | 있으면 직선거리 기반 도보 표시, 없거나 로딩 중이면 숨김 | - |
| U-145 | 전화와 길찾기 | 빠른 버튼 | /stores/:storeId | - | - | 전화번호 있을 때만 전화 버튼 | tel: 링크, 길찾기는 카카오맵 새 탭 | - |
| U-146 | 공유 | "공유" | /stores/:storeId | - | - | navigator.share 지원 여부 | 지원하면 공유창(닫아도 무알림), 아니면 링크 복사 토스트 "링크를 복사했어요" | - |
| U-147 | 복사 실패 | 공유 또는 주소 복사 | /stores/:storeId | - | - | 클립보드 거부 | 오류 토스트 "복사하지 못했어요" | - |
| U-148 | 주소 복사 | 위치 영역 | /stores/:storeId | - | - | - | 토스트 "주소를 복사했어요", 지도 실패 시 가짜 지도 판 | - |
| U-149 | 상세 뒤로 가기 | 사진 위 뒤로 | /stores/:storeId | - | - | 앱 안 이동 여부 | 기록 있으면 이전 화면, 링크로 바로 열었으면 /explore 로 replace | - |
| U-150 | 가게 사진 | 진입 | /stores/:storeId | GET /v1/stores/{storeId} | - | 대표+내부 0~3장 | 옆으로 넘기며 번호 표시, 실패 사진은 빼고, 모두 없으면 가게 아이콘 | - |

### 찜

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-151 | 쿠폰함 탭 줄 | 탭 누름 | /coupons, /coupons/wishlist, /coupons/history | - | - | - | "찜 목록/사용 가능/사용 내역" 주소별 탭, 각 탭 바로 열기 가능 | - |
| U-152 | 찜 목록 불러오기 | 진입 | /coupons/wishlist | GET /v1/wishlist, POST /v1/coupon-events:getStatus, GET /v1/coupons/issuance-limit | - | 찜 있음 | 두 요청이 끝날 때까지 회색 틀, 상태 요청은 가게 100개씩 나눠 보냄, 끝난 찜은 묻지 않음 | - |
| U-153 | 찜 목록 실패 | 진입 | /coupons/wishlist | GET /v1/wishlist | COMMON-001 | 서버 오류 | "찜 목록을 불러오지 못했어요" + "다시 시도" | - |
| U-154 | 찜 없음 | 진입 | /coupons/wishlist | GET /v1/wishlist | - | 0건 | "아직 찜한 포스터가 없어요" + "점심 포스터 보기"(/swipe) | - |
| U-155 | 발급 상태 실패 | 진입 | /coupons/wishlist | POST /v1/coupon-events:getStatus, GET /v1/coupons/issuance-limit | COMMON-002, COMMON-001 | 상태 또는 한도 조회 실패 | 경고 "발급 상태를 불러오지 못해 지금은 쿠폰을 받을 수 없어요" + 다시 시도, 카드는 버튼 없이 찜 순서, 끝난 찜만 맨 아래 | - |
| U-156 | 배너(오픈 전) | 진입 | /coupons/wishlist | - | - | 상태 받음 | "쿠폰 오픈까지 HH:MM:SS", "오픈하면 여기서 선착순으로 받아요" | 11:00 전 |
| U-157 | 배너(발급 중) | 진입 | /coupons/wishlist | - | - | 남은 횟수 > 0 | "선착순 발급이 열렸어요", "오늘 n개 더 받을 수 있어요, 하루 최대 3개" | 11:00~14:00 |
| U-158 | 배너(한도 소진) | 진입 | /coupons/wishlist | - | - | 남은 횟수 0 | "오늘의 발급을 모두 마쳤어요", "내일 11:00에 또 만나요", 상자 닫는 마스코트 | 11:00~14:00 |
| U-159 | 배너(마감 후) | 진입 | /coupons/wishlist | - | - | - | "오늘 발급이 끝났어요", "찜해 둔 쿠폰은 내일 11:00부터 받을 수 있어요" | 14:00 이후 |
| U-160 | 카드(오픈 전) | 진입 | /coupons/wishlist | - | - | 미발급 | 회색 "11:00 오픈", 사용 시간과 잔여 n장 | 11:00 전 |
| U-161 | 카드(받기 가능) | 진입 | /coupons/wishlist | - | - | 미발급, 잔여 > 0, 남은 횟수 > 0 | 주황 "쿠폰 받기", 사용 시간과 잔여 | 11:00~14:00 |
| U-162 | 카드(받음) | 진입 | /coupons/wishlist | - | - | hasIssuedToday=true | 초록 "받았어요" 누르면 /coupons | - |
| U-163 | 카드(수량 소진) | 진입 | /coupons/wishlist | - | - | 잔여 0 | 회색 "수량 소진" | 11:00~14:00 |
| U-164 | 카드(한도 도달) | 진입 | /coupons/wishlist | - | - | 남은 횟수 0(소진보다 우선) | 회색 "오늘 3개 완료" | 11:00~14:00 |
| U-165 | 카드(마감) | 진입 | /coupons/wishlist | - | - | 미발급 | 회색 "내일 11:00 오픈" | 14:00 이후 |
| U-166 | 카드(끝난 캠페인) | 진입 | /coupons/wishlist | GET /v1/wishlist | - | campaignEnded=true | 이름 "할인이 끝난 가게", "할인이 끝나 받을 수 없어요", "종료됨", "3일 뒤 목록에서 사라져요", 맨 아래 | - |
| U-167 | 끝난 찜 정리 | 다음 날들 진입 | /coupons/wishlist | GET /v1/wishlist | - | 끝난 날 제외 3일 경과 | 00:00 작업이 지워 목록에서 사라짐 | 00:00 ADSERVING_ENDED_WISHLIST_CLEANUP |
| U-168 | 오늘 후보 아닌 찜 | 진입 | /coupons/wishlist | GET /v1/wishlist, POST /v1/coupon-events:getStatus | - | storeName/사진/할인 null, 가게는 존재(NO_EVENT, 잔여 0) | 이름 "가게"로 표시, 할인 줄 없음, 발급 시간에는 "수량 소진"으로 보임(헤더 찜 수에는 안 셈) | - |
| U-169 | 상태 응답 없는 찜 | 진입 | /coupons/wishlist | POST /v1/coupon-events:getStatus | - | 가게가 없어 상태 항목 없음, 끝나지 않음 | 카드 숨김 | - |
| U-170 | 카드 순서 고정 | 화면 보는 중 | /coupons/wishlist | - | - | 상태 받음 | 받을 수 있는 카드 > 받은 카드 > 못 받는 카드 > 끝난 카드, 열 때와 11:00, 14:00 순간에만 다시 정렬 | 11:00, 14:00 |
| U-171 | 찜 삭제 | 카드 X | /coupons/wishlist | DELETE /v1/wishlist/{campaignId} | - | 받는 중 아님 | 즉시 목록에서 빼고 토스트 "찜 목록에서 삭제했어요" + "되돌리기" 4초, 그날 피드 제외 | - |
| U-172 | 받은 카드 찜 삭제 | 카드 X | /coupons/wishlist | DELETE /v1/wishlist/{campaignId} | - | 그 캠페인 쿠폰 받음 | 토스트 "찜 목록에서 뺐어요" 둘째 줄 "받은 쿠폰은 쿠폰함에 그대로 있어요" | - |
| U-173 | 끝난 찜 삭제 | 카드 X | /coupons/wishlist | DELETE /v1/wishlist/{campaignId} | - | campaignEnded=true | 되돌리기 없는 토스트 | - |
| U-174 | 찜 삭제 실패 | 카드 X | /coupons/wishlist | DELETE /v1/wishlist/{campaignId} | COMMON-001 | 서버 오류 | 원래 자리로 복귀, 오류 토스트 "찜을 삭제하지 못했어요" | - |
| U-175 | 찜 삭제 되돌리기 | "되돌리기" | /coupons/wishlist | DELETE /v1/wishlist/{campaignId}, POST /v1/wishlist | - | 오늘 후보 캠페인 | 원래 자리에 넣고, 진행 중인 삭제가 끝난 뒤 다시 찜 | - |
| U-176 | 되돌리기 실패 | "되돌리기" | /coupons/wishlist | POST /v1/wishlist | ADSERVING-004, COMMON-001 | 오늘 노출 후보 아님 | 다시 빼고 오류 토스트 "찜을 되돌리지 못했어요" | - |
| U-177 | 상세 찜(오픈 전) | 하트 또는 "찜해두기" | /stores/:storeId | POST /v1/wishlist | - | 오늘 후보 | 하트 채움, 토스트 "찜했어요, 11:00부터 쿠폰을 받을 수 있어요", 그날 피드 제외 | 11:00 전 |
| U-178 | 상세 찜(발급 중) | 하트 | /stores/:storeId | POST /v1/wishlist | - | 오늘 후보 | 토스트 "찜했어요, 지금 바로 쿠폰을 받을 수 있어요", 하단이 받기 버튼으로 | 11:00~14:00 |
| U-179 | 상세 찜(마감 후) | 하트 또는 "찜해두기" | /stores/:storeId | POST /v1/wishlist | - | 오늘 후보 | 토스트 "찜했어요, 내일 11:00부터 쿠폰을 받을 수 있어요" | 14:00 이후 |
| U-180 | 상세 찜 실패 | 하트 | /stores/:storeId | POST /v1/wishlist | ADSERVING-004, COMMON-001 | 오늘 노출 후보 아님 | 오류 토스트 "찜하지 못했어요" | - |
| U-181 | 상세 찜 취소 | 채워진 하트 | /stores/:storeId | DELETE /v1/wishlist/{campaignId} | - | 찜함, 미발급 | 바로 하트 비움, 토스트 "찜을 취소했어요" + "되돌리기" 4초 | - |
| U-182 | 상세 찜 취소 실패 | 채워진 하트 | /stores/:storeId | DELETE /v1/wishlist/{campaignId} | COMMON-001 | 서버 오류 | 하트 복구, 오류 토스트 "찜을 취소하지 못했어요" | - |
| U-183 | 상세 찜 취소 되돌리기 | "되돌리기" | /stores/:storeId | POST /v1/wishlist | ADSERVING-004, COMMON-001 | - | 하트 채움, 실패 시 다시 비우고 "찜을 되돌리지 못했어요" | - |
| U-184 | 받은 쿠폰 하트 잠김 | 상세 진입 | /stores/:storeId | - | - | 오늘 발급받음 | 하트 비활성(찜 취소 불가) | - |

### 쿠폰 발급

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-185 | 11:00 자동 오픈 | 화면 켜 둔 채 11:00 | /coupons/wishlist, /stores/:storeId, /swipe, /explore | - | - | 찜함, 잔여 있음 | 새로고침 없이 카운트다운이 사라지고 카드/하단 버튼이 받기로 바뀜, 찜 목록 재정렬 | 11:00(휴대폰 시계 기준) |
| U-186 | 14:00 자동 마감 | 화면 켜 둔 채 14:00 | /coupons/wishlist, /stores/:storeId, /swipe, /explore | - | - | - | 받기 버튼이 마감 표시로, 안내가 내일 문구로 | 14:00 |
| U-187 | 00:00 날짜 전환 | 화면 켜 둔 채 자정 | /coupons/wishlist, /stores/:storeId, /swipe, /explore | - | - | - | 마감 표시가 다시 오픈 전 카운트다운으로, 하루 발급 수는 새 영업일 기준 0 | 00:00 |
| U-188 | 찜 목록 발급 성공 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons, POST /v1/coupon-events:getStatus, GET /v1/coupons/issuance-limit | - | 찜함, 이벤트 ACTIVE, 잔여 > 0, 한도 남음 | 그 카드 로딩, 다른 카드 받기 잠김, 성공 후 상태 재조회, 배너 "쿠폰을 받았어요", 카드 "받았어요" | 11:00~14:00 |
| U-189 | 발급 성공 후 재조회 실패 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons, POST /v1/coupon-events:getStatus | COMMON-001 | 재조회 실패 | 그 카드만 받음으로, 남은 횟수 1 감소, 성공 배너 | 11:00~14:00 |
| U-190 | 찜 목록 발급 소진 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-006 | 받는 사이 재고 0 | 오류 토스트 "아쉽게도 그사이 수량이 모두 소진됐어요", 재조회로 "수량 소진" | 11:00~14:00 |
| U-191 | 찜 목록 발급 한도 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-007 | 오늘 3장 받음(다른 기기 포함) | "오늘 받을 수 있는 쿠폰을 모두 받았어요" | 11:00~14:00 |
| U-192 | 찜 목록 이미 받음 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-005 | 오늘 이 이벤트 이미 발급(삭제한 쿠폰 포함) | "오늘 이미 받은 쿠폰이에요" | 11:00~14:00 |
| U-193 | 찜 목록 찜 아님 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-004 | 다른 기기에서 찜 삭제 또는 캠페인 종료 | "찜한 뒤에 받을 수 있어요" | 11:00~14:00 |
| U-194 | 찜 목록 발급 시간 아님 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-002 | 휴대폰 시계가 서버보다 빠름, 이벤트 SCHEDULED/PAUSED | "지금은 받을 수 없는 시간이에요" | 서버 11:00 전 또는 14:00 이후 |
| U-195 | 오늘 이벤트 없음 발급 | "쿠폰 받기" | /coupons/wishlist, /stores/:storeId | - | - | 상태 eventId null(NO_EVENT) | 서버 요청 없이 "지금은 받을 수 없는 시간이에요" | - |
| U-196 | 발급 혼잡 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-008 | 대기 10초 초과, 순번 복구 실패 | 일반 토스트 "쿠폰 발급에 실패했어요"(재시도 안내 문구 미사용) | 11:00 직후 몰림 |
| U-197 | 발급 기타 오류 | "쿠폰 받기" | /coupons/wishlist | POST /v1/coupons | COUPON-001, COUPON-003, COMMON-002, COMMON-001 | 이벤트 없음, 계정 ACTIVE 아님, 서버 오류, 네트워크 | "쿠폰 발급에 실패했어요", 상태 재조회 안 함 | - |
| U-198 | 상세 발급(찜함) | 하단 "쿠폰 받기" | /stores/:storeId | POST /v1/coupons, GET /v1/wishlist, POST /v1/coupon-events:getStatus, GET /v1/coupons/issuance-limit | - | 찜함, 받기 가능 | 토스트 "쿠폰함에 쿠폰을 담았어요", 하단 "쿠폰함에서 보기", 상태 "발급 완료, 쿠폰함에서 확인" | 11:00~14:00 |
| U-199 | 상세 찜하고 발급 | 하단 "찜하고 쿠폰 받기" | /stores/:storeId | POST /v1/wishlist, POST /v1/coupons | - | 미찜, 받기 가능 | 먼저 찜한 뒤 발급, 성공 토스트 | 11:00~14:00 |
| U-200 | 상세 찜 단계 실패 | 하단 "찜하고 쿠폰 받기" | /stores/:storeId | POST /v1/wishlist | ADSERVING-004 | 오늘 후보 아님 | "쿠폰 발급에 실패했어요" | 11:00~14:00 |
| U-201 | 상세 발급 소진 | 하단 받기 | /stores/:storeId | POST /v1/coupons | COUPON-006 | 받는 사이 재고 0 | "아쉽게도 그사이 수량이 모두 소진됐어요", 재조회 후 비활성 "오늘 수량이 모두 소진됐어요" | 11:00~14:00 |
| U-202 | 상세 발급 한도 | 하단 받기 | /stores/:storeId | POST /v1/coupons | COUPON-007 | 오늘 3장 | "오늘 받을 수 있는 쿠폰을 모두 받았어요", 재조회 후 비활성 "오늘 3개를 모두 받았어요" | 11:00~14:00 |
| U-203 | 상세 이미 받음 | 하단 받기 | /stores/:storeId | POST /v1/coupons | COUPON-005 | 다른 기기에서 받음 | "오늘 이미 받은 쿠폰이에요" | 11:00~14:00 |
| U-204 | 상세 찜 아님 | 하단 받기 | /stores/:storeId | POST /v1/coupons | COUPON-004 | 다른 기기에서 찜 삭제 | "찜한 뒤에 받을 수 있어요" | 11:00~14:00 |
| U-205 | 상세 발급 시간 아님 | 하단 받기 | /stores/:storeId | POST /v1/coupons | COUPON-002 | 휴대폰 시계 빠름, 이벤트 ACTIVE 아님 | "지금은 받을 수 없는 시간이에요" | 서버 시각 경계 |
| U-206 | 상세 발급 기타 오류 | 하단 받기 | /stores/:storeId | POST /v1/coupons | COUPON-001, COUPON-003, COUPON-008, COMMON-001 | - | "쿠폰 발급에 실패했어요" | - |
| U-207 | 상세 발급 성공 후 재조회 실패 | 하단 받기 | /stores/:storeId | POST /v1/coupons, POST /v1/coupon-events:getStatus | COMMON-001 | 발급은 성공, 재조회 실패 | 실제로 받았는데 "쿠폰 발급에 실패했어요"가 뜨고 버튼은 그대로 | 11:00~14:00 |
| U-208 | 상세 하단(오픈 전, 미찜) | 진입 | /stores/:storeId | - | - | 미찜 | "찜해두기" + "찜해두면 11:00부터 찜 목록에서 쿠폰을 받을 수 있어요", 상태 "잔여 n장" | 11:00 전 |
| U-209 | 상세 하단(오픈 전, 찜함) | 진입 | /stores/:storeId | - | - | 찜함 | 비활성 "쿠폰 11:00 오픈" + "오픈까지 HH:MM:SS", 상태 "찜 완료, 잔여 n장" | 11:00 전 |
| U-210 | 상세 하단(받기 가능) | 진입 | /stores/:storeId | - | - | 잔여 > 0, 한도 남음 | "쿠폰 받기" 또는 "찜하고 쿠폰 받기" + "선착순이라 금방 마감될 수 있어요", 상태 "발급 가능, 잔여 n장" | 11:00~14:00 |
| U-211 | 상세 하단(받음) | 진입 | /stores/:storeId | - | - | 오늘 발급받음 | "쿠폰함에서 보기"(/coupons) | - |
| U-212 | 상세 하단(소진) | 진입 | /stores/:storeId | - | - | 잔여 0 | 비활성 "오늘 수량이 모두 소진됐어요", 상태 "마감, 수량 소진" | 11:00~14:00 |
| U-213 | 상세 하단(한도) | 진입 | /stores/:storeId | - | - | 남은 횟수 0 | 비활성 "오늘 3개를 모두 받았어요", 상태 "발급 제한, 오늘 3개 완료" | 11:00~14:00 |
| U-214 | 상세 하단(마감, 미찜) | 진입 | /stores/:storeId | - | - | 미찜 | "찜해두기" + "오늘 발급은 끝났어요, 찜해두면 내일 11:00부터 받을 수 있어요", 상태 "오늘 발급 마감" | 14:00 이후 |
| U-215 | 상세 하단(마감, 찜함) | 진입 | /stores/:storeId | - | - | 찜함 | 비활성 "쿠폰 내일 11:00 오픈", 상태 "오늘 발급 마감, 찜 완료" | 14:00 이후 |
| U-216 | 오늘 이벤트 없는 활성 캠페인 | 진입 | /stores/:storeId | POST /v1/coupon-events:getStatus | - | activeCampaign 있음, 이벤트 NO_EVENT(03:00 준비 전, PAUSED/ENDED 이벤트) | 잔여 0으로 보여 발급 시간에는 "오늘 수량이 모두 소진됐어요"로 표시 | 00:00~03:00 COUPON_PREPARE 전 등 |

### 쿠폰함/QR

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-217 | 사용 가능 쿠폰 불러오기 | 진입 | /coupons | GET /v1/coupons | - | filter state="ISSUED", pageSize=100, 마지막 페이지까지 | 회색 틀 후 카드 목록 "사용 HH:MM~HH:MM, 오늘" + "QR 보기" + X | - |
| U-218 | 사용 가능 쿠폰 실패 | 진입 | /coupons | GET /v1/coupons | COMMON-002, COMMON-001 | 서버 오류 | "쿠폰을 불러오지 못했어요" + "다시 시도" | - |
| U-219 | 사용 가능 쿠폰 없음 | 진입 | /coupons | GET /v1/coupons | - | ISSUED 0건 | "사용 가능한 쿠폰이 없어요" + "점심 포스터 보기"(/swipe) | - |
| U-220 | 사용 시간 지난 쿠폰 정리 | 진입 | /coupons | GET /v1/coupons | - | 사용 종료 지난 ISSUED | 조회 시 서버가 EXPIRED 로 바꿔 목록에서 빠지고 사용 내역 "만료"로 | 5분 COUPON_EXPIRE, 조회 시 즉시 |
| U-221 | QR 시트 열기 | "QR 보기" | /coupons | GET /v1/coupons/{memberCouponId}, POST /v1/coupons/{memberCouponId}:generateQr | - | ISSUED, 사용 시간 안 | 시트 "쿠폰 사용", 단건 즉시 조회 후 5초마다 확인, QR 이미지 표시 | - |
| U-222 | QR 사용 시작 전 | "QR 보기" | /coupons | GET /v1/coupons/{memberCouponId} | - | 지금 < usableStartTime | "사용 시작 전" + 시작 시각, "HH:MM부터 QR을 볼 수 있어요", QR 요청 안 함 | 사용 시작 전 |
| U-223 | QR 표시 중 | QR 받음 | /coupons | POST /v1/coupons/{memberCouponId}:generateQr | - | 토큰 유효 > 10초 | "직원에게 보여주세요", "mm:ss 뒤 새 QR로 바뀌어요" | QR 60초 |
| U-224 | QR 곧 바뀜 | 남은 10초 이하 | /coupons | - | - | 토큰 유효 <= 10초 | 강조 배경 "곧 새 QR로 바뀌어요", "mm:ss 뒤 자동으로 바뀌어요" | 마지막 10초 |
| U-225 | QR 60초 교체 | 만료 시각 도달 | /coupons | POST /v1/coupons/{memberCouponId}:generateQr | - | 토큰 만료(휴대폰 시각 요청+60초) | "QR 갱신 중" 후 새 QR, 이전 QR 즉시 무효 | 60초마다 |
| U-226 | QR 받기 실패 | QR 요청 | /coupons | POST /v1/coupons/{memberCouponId}:generateQr | COUPON-002, COUPON-011, COUPON-003, COMMON-001 | 사용 시간 밖, 본인 쿠폰 아님, 계정 ACTIVE 아님, 응답이 늦어 이미 만료 | "QR을 불러오지 못했어요" + "다시 시도", 자동 재요청 멈춤 | - |
| U-227 | QR 요청 중 사용 완료 | QR 요청 | /coupons | POST /v1/coupons/{memberCouponId}:generateQr, GET /v1/coupons/{memberCouponId} | COUPON-012 | 이미 USED | 잠깐 실패 상태 후 단건 확인이 USED 를 받아 "사용 완료!" | - |
| U-228 | QR 다시 시도 | "다시 시도" | /coupons | POST /v1/coupons/{memberCouponId}:generateQr | - | 실패 상태 | 실패 표시 해제 후 다시 요청 | - |
| U-229 | 오프라인 QR | 연결 끊김 | /coupons | - | - | navigator offline, 마지막 QR 유효 | "인터넷 연결 없음", 흐린 QR, "마지막 QR, mm:ss 남음, 연결되면 새 QR을 받아요" | - |
| U-230 | 오프라인 QR 만료 | 연결 끊긴 채 만료 | /coupons | - | - | 마지막 QR 만료 | "연결되면 새 QR을 받아요" | - |
| U-231 | 연결 복구 | online 이벤트 | /coupons | POST /v1/coupons/{memberCouponId}:generateQr | - | 토큰 없음/만료 | 자동으로 새 QR 요청 | - |
| U-232 | 점주 스캔 반영 | 점주가 QR 사용 처리 | /coupons | GET /v1/coupons/{memberCouponId} | - | 서버 상태 USED | 5초 안에 "사용 완료!" + "M월 D일 HH:MM 사용, 다시 사용할 수 없어요", 확인 중지 | 5초 확인 |
| U-233 | 사용 시간 종료 | 시트 연 채 종료 시각 | /coupons | - | - | 지금 >= usableEndTime 또는 EXPIRED | "사용 시간이 끝났어요", "만료", "쿠폰함에서 다른 쿠폰을 확인해 주세요" | usableEndTime |
| U-234 | 단건 확인 실패 | 5초 확인 | /coupons | GET /v1/coupons/{memberCouponId} | COUPON-001, COUPON-011, COMMON-001 | 삭제됨, 본인 아님, 서버 오류 | 무시하고 마지막 상태 유지 | 5초 |
| U-235 | 쿠폰 혜택 표시 | QR 시트 | /coupons | - | - | 메뉴 할인 또는 전 메뉴 할인 | 메뉴 할인은 정가 취소선과 할인가, 전 메뉴는 할인 문구, "오늘 사용 시간 HH:MM~HH:MM" | - |
| U-236 | QR 시트 닫기 | 시트 닫기 | /coupons | GET /v1/coupons | - | - | 확인 중지, 목록 조용히 다시 받아 사용한 쿠폰은 빠짐 | - |
| U-237 | 쿠폰 삭제 확인 | 카드 X | /coupons | - | - | 미사용 쿠폰 | 시트 "쿠폰을 삭제할까요?", "삭제하면 오늘 쿠폰을 1장 더 받을 수 있어요", "같은 가게 쿠폰은 오늘 다시 받을 수 없어요" | - |
| U-238 | 쿠폰 삭제 취소 | "취소" | /coupons | GET /v1/coupons | - | - | 시트 닫고 목록 다시 받음 | - |
| U-239 | 쿠폰 삭제 성공 | "삭제" | /coupons | DELETE /v1/coupons/{memberCouponId}, GET /v1/coupons | - | 미사용 또는 만료, 이미 삭제여도 204 | 토스트 "쿠폰을 삭제했어요", 목록 재조회, 받은 날 발급 수 1 감소(처음 삭제 시) | - |
| U-240 | 삭제 후 추가 발급 | 삭제 후 다른 찜 받기 | /coupons/wishlist | POST /v1/coupons | - | 한도 3장 찼다가 1장 삭제 | 다른 캠페인 쿠폰 1장 더 받을 수 있음 | 11:00~14:00 |
| U-241 | 사용한 쿠폰 삭제 | "삭제" | /coupons | DELETE /v1/coupons/{memberCouponId} | COUPON-012 | QR 띄운 사이 사용됨 | 시트 안 "이미 사용한 쿠폰이라 삭제할 수 없어요" | - |
| U-242 | 쿠폰 삭제 기타 실패 | "삭제" | /coupons | DELETE /v1/coupons/{memberCouponId} | COUPON-011, COMMON-001 | 본인 아님, 서버 오류 | 시트 안 "쿠폰을 삭제하지 못했어요" | - |
| U-243 | 삭제 중 다른 쿠폰 열기 | 삭제 응답 전 시트 닫고 다른 QR | /coupons | DELETE /v1/coupons/{memberCouponId}, GET /v1/coupons | - | - | 앞 삭제 응답은 새 시트를 건드리지 않고 목록만 다시 받음 | - |

### 사용 내역

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-244 | 사용 내역 첫 로딩 | 진입 | /coupons/history | GET /v1/coupons, GET /v1/coupons/summary | - | 필터 없음, pageSize=20 | 목록과 요약이 모두 끝날 때까지 칩 없이 회색 틀 | - |
| U-245 | 사용 내역 없음 | 진입 | /coupons/history | GET /v1/coupons | - | 마지막 페이지까지 USED/EXPIRED 0 | "아직 사용 내역이 없어요", "쿠폰을 사용하면 여기에 기록돼요" | - |
| U-246 | 사용 내역 표시 | 진입 | /coupons/history | GET /v1/coupons, GET /v1/coupons/summary | - | USED/EXPIRED 있음 | 칩(전체/사용 완료/만료), "지금까지 쿠폰을 n번 사용했어요", 날짜별 묶음, 흐린 카드 "사용 완료, HH:MM" 또는 "만료, HH:MM", 미사용은 뺌 | - |
| U-247 | 사용 횟수 조회 실패 | 진입 | /coupons/history | GET /v1/coupons/summary | COMMON-001 | 요약 실패 | 사용 횟수 문구만 숨기고 목록 표시 | - |
| U-248 | 사용 완료 필터 | "사용 완료" 칩 | /coupons/history | GET /v1/coupons | - | filter state="USED" | 첫 페이지부터 다시 받음, 칩 유지 | - |
| U-249 | 만료 필터 | "만료" 칩 | /coupons/history | GET /v1/coupons | - | filter state="EXPIRED" | 첫 페이지부터 다시 받음 | - |
| U-250 | 사용 완료 없음 | "사용 완료" 칩 | /coupons/history | GET /v1/coupons | - | USED 0 | "사용한 쿠폰이 없어요" | - |
| U-251 | 만료 없음 | "만료" 칩 | /coupons/history | GET /v1/coupons | - | EXPIRED 0 | "만료된 쿠폰이 없어요" | - |
| U-252 | 필터 후 전체 비어 있음 | "전체" 칩으로 복귀 | /coupons/history | GET /v1/coupons | - | 첫 로딩 뒤 전체가 비게 됨 | 빈 화면 "아직 사용 내역이 없어요" | - |
| U-253 | 사용 내역 실패 | 진입 또는 칩 변경 | /coupons/history | GET /v1/coupons | COMMON-002, COMMON-001 | 첫 페이지 실패 | "쿠폰을 불러오지 못했어요" + "다시 시도" | - |
| U-254 | 사용 내역 다음 페이지 | 목록 끝 300px 앞 | /coupons/history | GET /v1/coupons | - | nextPageToken 있음 | 20개씩 이어 받음, 전체에서 미사용을 빼 짧아지면 계속 이어 받음 | - |
| U-255 | 다음 페이지 실패 | 스크롤 | /coupons/history | GET /v1/coupons | COMMON-002, COMMON-001 | 커서 오류 등 | 목록 아래 "쿠폰을 불러오지 못했어요" + 다시 시도(이어서 받음) | - |

### 마이페이지/설정/탈퇴

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-256 | 마이 진입 | 마이 탭 | /my | GET /v1/members/me, GET /v1/coupons/summary | - | - | 프로필 카드(사진 없으면 아이콘, "카카오 계정, YYYY.MM.DD 가입"), 받은 쿠폰/사용한 쿠폰/절약한 금액 | - |
| U-257 | 내 정보 실패 | 마이 탭 | /my | GET /v1/members/me | MEMBER-001, COMMON-001 | - | 프로필 자리 "내 정보를 불러오지 못했어요" + 다시 시도, 요약은 따로 표시 | - |
| U-258 | 쿠폰 요약 실패 | 마이 탭 | /my | GET /v1/coupons/summary | COMMON-001 | - | 요약 자리 "쿠폰 요약을 불러오지 못했어요" + 다시 시도 | - |
| U-259 | 마이 바로가기 | 메뉴 누름 | /my | - | - | - | 찜 목록(/coupons/wishlist), 쿠폰 사용 내역(/coupons/history), 위치 설정(/my/location), 설정(/my/settings) | - |
| U-260 | 닉네임 시트 | "수정" | /my | - | - | - | 지금 닉네임 채움, n/20 표시, 공백뿐이거나 같으면 "저장" 비활성 | - |
| U-261 | 닉네임 저장 성공 | "저장" | /my | PATCH /v1/members/me | - | 1~20자, 공백만 아님 | 프로필과 저장된 로그인 정보 닉네임 갱신, 시트 닫힘 | - |
| U-262 | 닉네임 저장 실패 | "저장" | /my | PATCH /v1/members/me | COMMON-002, MEMBER-001, COMMON-001 | - | 시트 안 "닉네임을 바꾸지 못했어요" | - |
| U-263 | 설정 진입 | 설정 | /my/settings | GET /v1/members/me | - | - | "위치 정보 이용" 토글(현재 동의값), 화면 모드, 계정 | - |
| U-264 | 설정 불러오기 실패 | 설정 | /my/settings | GET /v1/members/me | MEMBER-001, COMMON-001 | - | "위치 설정을 불러오지 못했어요" + 다시 시도, 화면 모드와 계정은 표시 | - |
| U-265 | 위치 동의 바꾸기 | 토글 | /my/settings | PATCH /v1/members/me | - | - | 먼저 바뀐 값으로 보여주고 서버 값으로 확정, 끄면 위치 설정의 GPS 버튼 비활성 | - |
| U-266 | 위치 동의 바꾸기 실패 | 토글 | /my/settings | PATCH /v1/members/me | MEMBER-001, COMMON-002, COMMON-001 | - | 원래 값으로 되돌리고 "설정을 바꾸지 못했어요, 다시 눌러 주세요" | - |
| U-267 | 화면 모드 | 라이트/다크 | /my/settings | - | - | - | 즉시 적용, 이 기기에 저장(서버 없음) | - |
| U-268 | 로그아웃 성공 | "로그아웃" | /my/settings | DELETE /v1/auth/tokens | - | - | "로그아웃하는 중" 후 쿠키 삭제 확인 뒤 로그인 정보 지움, / 로 이동 | - |
| U-269 | 이미 로그아웃됨 | "로그아웃" | /my/settings | DELETE /v1/auth/tokens, POST /v1/auth/tokens:refresh | AUTH-005, AUTH-003 | 세션 만료 | 재발급 실패로 세션 만료 처리되어 / 로 이동(AUTH-005 는 성공으로 봄) | - |
| U-270 | 로그아웃 실패 | "로그아웃" | /my/settings | DELETE /v1/auth/tokens | AUTH-002, COMMON-001 | 토큰 폐기 실패, 네트워크 | "로그아웃하지 못했어요, 다시 눌러 주세요", 로그인 유지 | - |
| U-271 | 탈퇴 안내 | "회원 탈퇴" | /my/withdraw | - | - | - | 안내 3개, 사유 칩(선택, 다시 누르면 해제), 확인 체크 전 "카카오 계정 확인 후 탈퇴" 비활성 | - |
| U-272 | 탈퇴 재인증 시작 | 확인 체크 후 버튼 | /my/withdraw | GET /v1/auth/kakao/authorize | - | reauth=true | 목적(withdraw)과 사유를 sessionStorage 에 두고 카카오 재로그인(prompt=login) 화면으로 이동 | state 5분 |
| U-273 | 탈퇴 재인증 시작 실패 | 버튼 | /my/withdraw | GET /v1/auth/kakao/authorize | COMMON-001 | - | "카카오 계정 확인을 시작하지 못했어요, 다시 눌러 주세요" | - |
| U-274 | 탈퇴 완료 | 카카오 재로그인 후 콜백 | /oauth/callback, /login | POST /v1/members/me:withdraw | - | 같은 카카오 계정, state 일치 | "탈퇴하는 중이에요" 후 서버가 프로필/위치 삭제와 토큰 폐기, 로그인 정보 지우고 /login 에 토스트 "탈퇴했어요, 그동안 이용해 주셔서 고마워요" 4초(새로고침 시 다시 안 뜸) | - |
| U-275 | 이미 탈퇴됨 | 콜백 | /oauth/callback, /login | POST /v1/members/me:withdraw | MEMBER-001 | 이미 WITHDRAWN | 성공으로 보고 탈퇴 완료와 같게 처리 | - |
| U-276 | 탈퇴 재인증 취소 | 카카오에서 취소 또는 다른 탭 state | /oauth/callback | - | - | code 없음 또는 state 불일치 | "탈퇴하지 못했어요", "카카오 계정 확인이 끝나지 않았어요, 다시 시도해 주세요" + "설정으로 돌아가기" | - |
| U-277 | 다른 카카오 계정으로 확인 | 콜백 | /oauth/callback | POST /v1/members/me:withdraw | AUTH-007 | 다른 계정 또는 state 만료/재사용 | "카카오 계정 확인에 실패했어요, 지금 로그인한 계정으로 다시 확인해 주세요" | state 5분 |
| U-278 | 탈퇴 서버 실패 | 콜백 | /oauth/callback | POST /v1/members/me:withdraw | MEMBER-005, AUTH-002, COMMON-002, COMMON-001 | 연결 해제 선점 실패, 토큰 폐기 실패 등 | "지금은 탈퇴할 수 없어요, 잠시 후 다시 시도해 주세요" + "설정으로 돌아가기" | - |
| U-279 | 설정으로 돌아가기 | 실패 화면 버튼 | /oauth/callback, /my/settings | - | - | 로그인 유지 | /my/settings 로 replace | - |

### 공통 오류

| ID | 시나리오 | 단계 | 화면(route) | API (METHOD path, exactly as in endpoints.txt) | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|---|
| U-280 | Access Token 만료 자동 재발급 | 아무 회원 API 호출 | 모든 회원 화면 | POST /v1/auth/tokens:refresh | AUTH-005 | Access 만료, Refresh 유효 | 401 AUTH-005 를 받으면 재발급 1회 후 원요청 다시 보내 성공, 화면 변화 없음 | Access 30분 |
| U-281 | 동시 401 | 여러 요청이 동시에 401 | 모든 회원 화면 | POST /v1/auth/tokens:refresh | AUTH-005 | - | 재발급은 한 번만 보내 재사용 판정을 피함 | - |
| U-282 | Refresh 만료/무효 | 재발급 | 모든 회원 화면 | POST /v1/auth/tokens:refresh | AUTH-003 | Refresh 없음/만료/폐기 | 로그인 정보 삭제, 가드가 / 로 보냄 | Refresh 14일 |
| U-283 | Refresh 재사용 감지 | 재발급 | 모든 회원 화면 | POST /v1/auth/tokens:refresh | AUTH-004 | 교체된 토큰 재사용 | 서버가 모든 기기 로그아웃, 앱은 / 로 이동(전용 문구 없음) | - |
| U-284 | 재발급 저장소 장애 | 재발급 | 모든 회원 화면 | POST /v1/auth/tokens:refresh | AUTH-002 | 503 | 로그인 유지, 원요청 실패로 각 화면 오류 상태 | - |
| U-285 | 재발급 IP 제한 | 재발급 | 모든 회원 화면 | POST /v1/auth/tokens:refresh | (코드 없음, 429 본문 없음) | 같은 IP 분당 10회 초과 | 로그인 유지, 원요청 실패로 각 화면 오류 상태 | 1분 창 |
| U-286 | 권한 없는 역할 | 회원 API 호출 | 모든 회원 화면 | GET /v1/members/me | AUTH-006 | 점주/관리자 토큰 | 재발급하지 않고 각 화면 오류 상태 | - |
| U-287 | 서버 오류 | 회원 API 호출 | 모든 회원 화면 | GET /v1/members/me | COMMON-001 | 500 | 각 화면의 일반 오류와 다시 시도 | - |
| U-288 | 네트워크 끊김/본문 없는 오류 | 회원 API 호출 | 모든 회원 화면 | GET /v1/members/me | (코드 없음) | fetch 실패, 프록시 HTML 응답 | 봉투를 못 읽으면 code=null, 각 화면 일반 오류 | - |
| U-289 | 카카오에서 앱 연결 해제 | 사용자가 카카오 설정에서 연결 끊음 | 모든 회원 화면 | GET/POST /webhook/kakao/unlink, POST /v1/auth/tokens:refresh | AUTH-005, AUTH-003 | 탈퇴 이력 없는 회원 | 서버가 탈퇴 처리와 토큰 폐기, 다음 요청에서 세션 만료로 / 이동 | - |

## 대조

### (a) 사용자 앱 경로와 시나리오

- `/` (guest 가드): U-001, U-002, U-003, U-023, U-024, U-025
- `/login` (guest 가드): U-002, U-003, U-006, U-007, U-008, U-009, U-010, U-022, U-023, U-024, U-025, U-274, U-275
- `/oauth/callback` (가드 밖): U-011, U-012, U-013, U-014, U-015, U-016, U-017, U-018, U-019, U-020, U-021, U-022, U-023, U-024, U-025, U-274, U-275, U-276, U-277, U-278, U-279
- `/onboarding` (index, consent 로 replace, onboarding 가드 그룹): U-003, U-004, U-023, U-024, U-025
- `/onboarding/consent`: U-023, U-024, U-025, U-026, U-027, U-028, U-029
- `/onboarding/personalization`: U-023, U-024, U-025, U-030, U-031, U-032, U-033, U-034
- `/onboarding/location`: U-023, U-024, U-025, U-035, U-038, U-039, U-040, U-041, U-042, U-043, U-044, U-045, U-046, U-047, U-048, U-049, U-050, U-051, U-053, U-054, U-055, U-056, U-057, U-058, U-059
- `/onboarding/tutorial`: U-023, U-024, U-025, U-036, U-037
- `/stores/:storeId`: U-023, U-024, U-025, U-131, U-132, U-133, U-134, U-135, U-136, U-137, U-138, U-139, U-140, U-141, U-142, U-143, U-144, U-145, U-146, U-147, U-148, U-149, U-150, U-177, U-178, U-179, U-180, U-181, U-182, U-183, U-184, U-185, U-186, U-187, U-195, U-198, U-199, U-200, U-201, U-202, U-203, U-204, U-205, U-206, U-207, U-208, U-209, U-210, U-211, U-212, U-213, U-214, U-215, U-216, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/my/location`: U-023, U-024, U-025, U-038, U-039, U-040, U-041, U-042, U-043, U-044, U-045, U-046, U-047, U-048, U-049, U-050, U-051, U-052, U-053, U-054, U-055, U-056, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/my/settings`: U-023, U-024, U-025, U-263, U-264, U-265, U-266, U-267, U-268, U-269, U-270, U-279, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/my/withdraw`: U-023, U-024, U-025, U-271, U-272, U-273, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/swipe`: U-003, U-004, U-023, U-024, U-025, U-060, U-061, U-062, U-063, U-064, U-065, U-066, U-067, U-068, U-069, U-070, U-071, U-072, U-073, U-074, U-075, U-076, U-077, U-078, U-079, U-080, U-081, U-082, U-083, U-084, U-085, U-086, U-087, U-088, U-089, U-090, U-091, U-092, U-093, U-094, U-095, U-096, U-097, U-098, U-099, U-100, U-101, U-102, U-103, U-104, U-105, U-106, U-107, U-185, U-186, U-187, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/explore`: U-023, U-024, U-025, U-060, U-061, U-107, U-108, U-109, U-110, U-111, U-112, U-113, U-114, U-115, U-116, U-117, U-118, U-119, U-120, U-121, U-122, U-123, U-124, U-125, U-126, U-127, U-128, U-129, U-130, U-185, U-186, U-187, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/coupons` (index, 사용 가능 탭): U-023, U-024, U-025, U-061, U-151, U-217, U-218, U-219, U-220, U-221, U-222, U-223, U-224, U-225, U-226, U-227, U-228, U-229, U-230, U-231, U-232, U-233, U-234, U-235, U-236, U-237, U-238, U-239, U-241, U-242, U-243, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/coupons/wishlist`: U-023, U-024, U-025, U-151, U-152, U-153, U-154, U-155, U-156, U-157, U-158, U-159, U-160, U-161, U-162, U-163, U-164, U-165, U-166, U-167, U-168, U-169, U-170, U-171, U-172, U-173, U-174, U-175, U-176, U-185, U-186, U-187, U-188, U-189, U-190, U-191, U-192, U-193, U-194, U-195, U-196, U-197, U-240, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/coupons/history`: U-023, U-024, U-025, U-151, U-244, U-245, U-246, U-247, U-248, U-249, U-250, U-251, U-252, U-253, U-254, U-255, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `/my`: U-004, U-023, U-024, U-025, U-061, U-256, U-257, U-258, U-259, U-260, U-261, U-262, U-280, U-281, U-282, U-283, U-284, U-285, U-286, U-287, U-288, U-289
- `*` (Navigate to /): U-005

### (b) 사용자 앱이 부르는 엔드포인트와 시나리오

- `GET /v1/auth/kakao/authorize`: U-006, U-007, U-008, U-009, U-022, U-272, U-273
- `POST /v1/auth/tokens`: U-011, U-012, U-013, U-016, U-017, U-018, U-019, U-020, U-021
- `DELETE /v1/auth/tokens`: U-268, U-269, U-270
- `POST /v1/auth/tokens:refresh`: U-024, U-269, U-280, U-281, U-282, U-283, U-284, U-285, U-289
- `GET /v1/members/me`: U-023, U-024, U-025, U-035, U-038, U-039, U-042, U-256, U-257, U-263, U-264, U-286, U-287, U-288
- `PATCH /v1/members/me`: U-261, U-262, U-265, U-266
- `PUT /v1/members/me/onboarding`: U-031, U-032, U-033, U-034
- `POST /v1/members/me:withdraw`: U-274, U-275, U-277, U-278
- `GET /v1/member-profiles/location`: U-038, U-039, U-040, U-041, U-060, U-062, U-067, U-108, U-112, U-114, U-144
- `PUT /v1/member-profiles/location`: U-052, U-053, U-059
- `DELETE /v1/member-profiles/location`: U-055, U-056
- `GET /v1/feed`: U-062, U-076, U-077, U-078, U-079, U-080, U-081, U-083, U-084, U-085, U-086, U-087, U-089, U-090, U-098, U-099
- `GET /v1/feed-filter`: U-062, U-092, U-093, U-094
- `PUT /v1/feed-filter`: U-081, U-082, U-096, U-097, U-098, U-099, U-100
- `POST /v1/impressions`: U-065, U-066, U-067
- `POST /v1/swipes`: U-068, U-069, U-070, U-071, U-072, U-073, U-074, U-091
- `GET /v1/campaigns/{campaignId}/poster`: U-063, U-064, U-136, U-137
- `GET /v1/stores`: U-108, U-113, U-115, U-116, U-117, U-118, U-119, U-120, U-121, U-122, U-128
- `GET /v1/stores/{storeId}`: U-131, U-132, U-133, U-134, U-138, U-139, U-140, U-141, U-142, U-143, U-150
- `GET /v1/wishlist`: U-062, U-073, U-091, U-107, U-108, U-131, U-135, U-152, U-153, U-154, U-166, U-167, U-168, U-198
- `POST /v1/wishlist`: U-175, U-176, U-177, U-178, U-179, U-180, U-183, U-199, U-200
- `DELETE /v1/wishlist/{campaignId}`: U-171, U-172, U-173, U-174, U-175, U-181, U-182
- `POST /v1/coupon-events:getStatus`: U-131, U-135, U-152, U-155, U-168, U-169, U-188, U-189, U-198, U-207, U-216
- `POST /v1/coupons`: U-188, U-189, U-190, U-191, U-192, U-193, U-194, U-196, U-197, U-198, U-199, U-201, U-202, U-203, U-204, U-205, U-206, U-207, U-240
- `GET /v1/coupons`: U-217, U-218, U-219, U-220, U-236, U-238, U-239, U-243, U-244, U-245, U-246, U-248, U-249, U-250, U-251, U-252, U-253, U-254, U-255
- `GET /v1/coupons/issuance-limit`: U-103, U-104, U-105, U-131, U-135, U-152, U-155, U-188, U-198
- `GET /v1/coupons/summary`: U-244, U-246, U-247, U-256, U-258
- `GET /v1/coupons/{memberCouponId}`: U-221, U-222, U-227, U-232, U-234
- `DELETE /v1/coupons/{memberCouponId}`: U-239, U-241, U-242, U-243
- `POST /v1/coupons/{memberCouponId}:generateQr`: U-221, U-223, U-225, U-226, U-227, U-228, U-231
- `GET/POST /webhook/kakao/unlink` (앱이 부르지 않는다. 카카오가 서버로 보내는 연결 해제 통보로 사용자 세션에 영향): U-289

### (c) 그 엔드포인트에서 나올 수 있는 오류 코드와 시나리오

- `AUTH-002` REFRESH_TOKEN_STORE_UNAVAILABLE (SERVICE_UNAVAILABLE): U-019, U-270, U-278, U-284
- `AUTH-003` REFRESH_TOKEN_INVALID (UNAUTHORIZED): U-024, U-269, U-282, U-289
- `AUTH-004` REFRESH_TOKEN_REUSED (UNAUTHORIZED): U-283
- `AUTH-005` LOGIN_REQUIRED (UNAUTHORIZED): U-024, U-269, U-280, U-281, U-289
- `AUTH-006` ROLE_NOT_ALLOWED (FORBIDDEN): U-286
- `AUTH-007` KAKAO_AUTHENTICATION_FAILED (UNAUTHORIZED): U-016, U-277
- `MEMBER-001` ALREADY_WITHDRAWN (BAD_REQUEST): U-033, U-039, U-053, U-257, U-262, U-264, U-266, U-275
- `MEMBER-002` ONBOARDING_ALREADY_COMPLETED (CONFLICT): U-032
- `MEMBER-003` SUSPENDED_REJOIN_NOT_ALLOWED (FORBIDDEN): U-017
- `MEMBER-005` UNLINK_PENDING (CONFLICT): U-018, U-278
- `ADSERVING-001` ONBOARDING_REQUIRED (FORBIDDEN): U-084
- `ADSERVING-002` LOCATION_REQUIRED (UNPROCESSABLE_ENTITY): U-083
- `ADSERVING-003` RATE_LIMITED (TOO_MANY_REQUESTS): U-085, U-086
- `ADSERVING-004` CAMPAIGN_NOT_SERVING (NOT_FOUND): U-073, U-091, U-176, U-180, U-183, U-200
- `COUPON-001` COUPON_NOT_FOUND (NOT_FOUND): U-197, U-206, U-234
- `COUPON-002` COUPON_NOT_AVAILABLE (CONFLICT): U-194, U-205, U-226
- `COUPON-003` MEMBER_ACCOUNT_NOT_ALLOWED (FORBIDDEN): U-197, U-206, U-226
- `COUPON-004` CAMPAIGN_NOT_WISHED (FORBIDDEN): U-193, U-204
- `COUPON-005` EVENT_ALREADY_ISSUED (CONFLICT): U-192, U-203
- `COUPON-006` INVENTORY_EXHAUSTED (CONFLICT): U-190, U-201
- `COUPON-007` DAILY_LIMIT_REACHED (CONFLICT): U-191, U-202
- `COUPON-008` ISSUANCE_BUSY (SERVICE_UNAVAILABLE): U-196, U-206
- `COUPON-011` COUPON_ACCESS_DENIED (FORBIDDEN): U-226, U-234, U-242
- `COUPON-012` COUPON_ALREADY_USED (CONFLICT): U-227, U-241
- `STORE-016` STORE_NOT_FOUND (NOT_FOUND): U-132
- `POSTER-022` POSTER_UNAVAILABLE (NOT_FOUND): U-064, U-137
- `COMMON-001` INTERNAL_ERROR (INTERNAL_SERVER_ERROR): U-008, U-025, U-033, U-039, U-053, U-056, U-064, U-067, U-073, U-082, U-084, U-093, U-100, U-105, U-113, U-114, U-122, U-133, U-135, U-137, U-153, U-155, U-174, U-176, U-180, U-182, U-183, U-189, U-197, U-206, U-207, U-218, U-226, U-234, U-242, U-247, U-253, U-255, U-257, U-258, U-262, U-264, U-266, U-270, U-273, U-278, U-287
- `COMMON-002` INVALID_INPUT (BAD_REQUEST): U-021, U-033, U-053, U-073, U-100, U-113, U-122, U-155, U-197, U-218, U-253, U-255, U-262, U-266, U-278
- `COMMON-003` MALFORMED_REQUEST (BAD_REQUEST): U-021, U-113, U-133
- 코드 없는 오류(봉투 없음, 인증 경로 IP 제한 429, 네트워크 끊김, 프록시 HTML 응답): U-020, U-285, U-288

### (d) 코드에 있지만 시나리오로 만들지 않은 것

- `MEMBER-004` SAME_DAY_REJOIN_NOT_ALLOWED: 서버 `MemberLoginService.validateRegistration` 의 검사 코드가 주석 처리돼 도달하지 않는다. 당일 재가입이 되는 쪽으로 U-013 에 넣었다.
- `COUPON-009`, `COUPON-010`, `COUPON-013`: 점주 쿠폰 사용 처리(`POST /v1/owner/coupon-usages`)에서만 나와 사용자 앱 엔드포인트로는 도달하지 않는다. 사용자 쪽 결과는 사용 완료 반영(U-232)으로만 보인다.
- `COMMON-004`~`COMMON-007`: 앱이 고정 경로와 메서드, JSON 본문만 보내 정상 사용으로는 나오지 않는다. 피드 posterUrl 도 서버가 `/v1/campaigns/{id}/poster` 로 만들어 준다.
- 그 밖의 오류 코드(ADMIN, ANALYTICS, BILLING, CAMPAIGN, OPS, OWNER, POSTER-001~021, POSTER-023~026, STORE-001~015, TERMS-001, AUTH-001): 사용자 앱이 부르는 엔드포인트에서 나오지 않는다.
- 사용자 앱이 부르지 않는 회원용 엔드포인트: `POST /v1/events`(분석 이벤트 수집), `GET /v1/members/me/usage-stats`. 앱 코드에 호출이 없다. 관리자, 점주, 결제 웹훅 엔드포인트는 사용자 앱 범위 밖이다.
- `api/mocks/*` 의 mock 경로(fetchMockFeed, getMockCoupons, mock 찜/가게/포스터, `?mockDailyLimit`, `?mockIssueSoldOut`, `?mockCouponsEmpty`, `?mockStoresEmpty`, `?mockWishlistEmpty`): `USE_FEED_API`, `USE_COUPON_*`, `USE_WISHLIST_API`, `USE_STORE_*`, `USE_POSTER_API`, `USE_FEED_FILTER_API` 가 모두 true 라 실행되지 않는 죽은 코드다. 단 `mocks/terms`(약관 본문)는 실제로 화면에 쓰여 U-028 에 반영했다.
- `?mockTime=HH:MM:SS` 시계 조작(`api/clock.ts`): 운영 빌드에도 남은 테스트 훅이라 시나리오로 만들지 않았다. 카운트다운, 발급 단계, QR 만료는 이 값을 따르지만 피드 세션과 대기 시간은 `Date.now()` 를 써서 서로 어긋날 수 있다.
- 알림: `router.tsx` StackLayout 그룹에 "알림 아이콘으로 들어온다" 주석만 있고 경로가 없다. 온보딩은 `notificationOptIn=false` 를 항상 보내고 설정에도 알림 토글이 없다.
- 약관 동의 기록: 이용약관과 개인정보 동의는 화면 상태로만 쓰이고 서버로 보내지 않는다(사용자 약관 API 없음). 화면 동작만 U-029 에 넣었다.
- 서버는 지원하지만 앱이 쓰지 않는 값: `GET /v1/feed` 의 latitude/longitude, `GET /v1/coupons` 의 `usable = true` 필터, `POST /v1/coupon-events:getStatus` 응답의 `wished`, `canIssue`, `cannotIssueReason`, `eventState`.
- `*.stories.tsx`, `ui/README.md`: Storybook 과 문서라 앱 경로에 없다.
- 피드 `moveToNextFeed` 의 `nextFeedAt === null` 분기: 실제 API 응답은 항상 다음 피드 시각을 주므로 mock 에서만 탄다. 실제로 "모두 확인" 화면은 U-078 경로로만 나온다.
- 알 수 없는 업종 코드는 피드에서 "기타"로 바꿔 보여 주는 표시 규칙이라 따로 행을 만들지 않았다.
