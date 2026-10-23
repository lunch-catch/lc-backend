# 화면 없는 백엔드 시나리오

범위: 화면이 부르지 않는 API, 웹훅, 배치, 인증과 토큰, 속도 제한. 시나리오 431개 (B-001 ~ B-431).

기준 코드: lc-backend develop `9f4ab523`, lc-frontend `3f3e056` (2026-10-22). 개요와 갱신 방법은 [README](README.md)에 있다.

기준 코드: wt-loadtest-pg (src/main/java/com/launchcatch), 프론트: lc-frontend apps/user, owner, admin. 시각은 모두 KST 다.

## 엔드포인트 호출 대조

기준: lc-frontend apps/user, apps/owner, apps/admin 의 src/api 함수와 그 함수를 import 하는 화면 파일. api 파일에 함수만 있고 어떤 화면도 부르지 않으면 "없음(함수만 있음)"으로 적었다. 웹훅 2개는 외부(토스페이먼츠, 카카오)가 부른다.

| METHOD path | 호출하는 앱 (user/owner/admin/없음) | 화면 파일 |
|---|---|---|
| POST /v1/admin/admins | admin | features/admin-account/useIssueAdminAccount.ts |
| GET /v1/admin/admins | admin | features/admin-account/useAdminAccounts.ts |
| GET /v1/admin/aggregation-runs | 없음 | - |
| POST /v1/admin/aggregations:recalculate | 없음 | - |
| POST /v1/admin/auth/tokens | admin | auth/AdminAuthProvider.tsx, auth/adminAuthStore.ts |
| DELETE /v1/admin/auth/tokens | admin | auth/AdminAuthProvider.tsx, auth/adminAuthStore.ts |
| POST /v1/admin/auth/tokens:refresh | admin | auth/adminAuthStore.ts |
| GET /v1/admin/batch-executions | 없음 | - |
| POST /v1/admin/batch-executions/{jobName}/{businessDate}:retry | 없음 | - |
| GET /v1/admin/campaign-reports | admin | features/campaign/useCampaignReports.ts |
| GET /v1/admin/campaigns | admin | features/campaign/useCampaigns.ts |
| GET /v1/admin/campaigns/{campaignId} | admin | features/campaign/CampaignDetails.tsx |
| GET /v1/admin/dashboard/funnel | admin | features/dashboard/useDashboardMetrics.ts |
| GET /v1/admin/dashboard/members | admin | features/dashboard/useDashboardSnapshots.ts, useDashboardMetrics.ts |
| GET /v1/admin/dashboard/stores | admin | features/dashboard/useDashboardSnapshots.ts, useDashboardMetrics.ts |
| GET /v1/admin/fraud/campaign-warnings | admin | features/fraud/useFraudManagement.ts |
| GET /v1/admin/fraud/invalid-impressions | admin | features/fraud/useFraudManagement.ts |
| GET /v1/admin/members | admin | features/member/UserMemberListContent.tsx |
| GET /v1/admin/owners | admin | features/member/OwnerMemberListContent.tsx |
| GET /v1/admin/payments | admin | features/billing/usePayments.ts |
| POST /v1/admin/payments/{paymentId}:recheck | admin | features/billing/ui/PaymentDetails.tsx |
| POST /v1/admin/point-adjustments | admin | features/billing/ui/PointAdjustmentModal.tsx |
| GET /v1/admin/point-ledger | admin | features/billing/usePointLedger.ts |
| GET /v1/admin/point-ledger/summary | admin | features/billing/usePointSummary.ts |
| GET /v1/admin/point-policy | admin | features/billing/tabs/PointPolicyTab.tsx |
| GET /v1/admin/point-policy/history | admin | features/billing/tabs/PointPolicyTab.tsx |
| PUT /v1/admin/point-policy/next | admin | features/billing/ui/PointPolicyEditor.tsx |
| GET /v1/admin/refund-requests | admin | features/billing/tabs/RefundRequestsTab.tsx |
| POST /v1/admin/refund-requests/{refundRequestId}:approve | admin | features/billing/ui/RefundDetails.tsx |
| POST /v1/admin/refund-requests/{refundRequestId}:reject | admin | features/billing/ui/RefundDetails.tsx |
| GET /v1/admin/sales | admin | features/dashboard/useDashboardMetrics.ts, features/billing/tabs/SalesOverviewTab.tsx |
| GET /v1/admin/settlement-mismatches | admin | features/billing/tabs/SettlementMismatchesTab.tsx, features/dashboard/usePendingSettlementCount.ts |
| POST /v1/admin/templates | admin | features/template/useTemplateEditor.ts |
| GET /v1/admin/templates | admin | features/template/useTemplateManagementState.ts |
| DELETE /v1/admin/templates/{templateId} | admin | features/template/useTemplateManagementState.ts |
| GET /v1/admin/templates/{templateId} | admin | features/template/useTemplateEditor.ts |
| PATCH /v1/admin/templates/{templateId}/active | admin | features/template/useTemplateManagementState.ts |
| POST /v1/admin/templates/{templateId}/publish | admin | features/template/useTemplateEditor.ts |
| POST /v1/admin/templates/{templateId}/versions | admin | features/template/useTemplateEditor.ts |
| GET /v1/admin/templates/{templateId}/versions | admin | features/template/useTemplateEditor.ts |
| GET /v1/auth/kakao/authorize | user | auth/kakaoLogin.ts, api/client.ts (세션 만료 시) |
| POST /v1/auth/tokens | user | features/login/KakaoCallbackStep.tsx |
| DELETE /v1/auth/tokens | user | features/mypage/SettingsScreen.tsx, features/login/KakaoCallbackStep.tsx, auth/AuthProvider.tsx |
| POST /v1/auth/tokens:refresh | user | api/client.ts (401 시 자동) |
| GET /v1/campaigns/{campaignId}/poster | user | features/store-detail/StoreDetailScreen.tsx |
| POST /v1/coupon-events:getStatus | user | features/swipe/SwipeHint.tsx |
| POST /v1/coupons | user | features/store-detail/StoreDetailScreen.tsx, features/wishlist/WishlistScreen.tsx |
| GET /v1/coupons | user | features/coupon/useCouponHistory.ts |
| GET /v1/coupons/issuance-limit | 없음(함수만 있음) | api/coupon.ts fetchIssuanceLimit 를 부르는 화면 없음 |
| GET /v1/coupons/summary | user | features/coupon/CouponHistory.tsx, features/mypage/ProfileSummary.tsx |
| GET /v1/coupons/{memberCouponId} | user | features/coupon/useLatestCoupon.ts |
| DELETE /v1/coupons/{memberCouponId} | user | features/coupon/AvailableCoupons.tsx |
| POST /v1/coupons/{memberCouponId}:generateQr | user | features/coupon/useQrToken.ts |
| POST /v1/events | 없음 | - |
| GET /v1/feed | user | features/swipe/SwipeFeed.tsx |
| GET /v1/feed-filter | user | features/swipe/useFeedFilter.ts |
| PUT /v1/feed-filter | user | features/swipe/FeedFilterSheet.tsx, features/swipe/SwipeFeed.tsx |
| POST /v1/impressions | user | features/swipe/SwipeFeed.tsx |
| PUT /v1/member-profiles/location | user | features/location/LocationSettingsScreen.tsx |
| GET /v1/member-profiles/location | user | features/location/LocationSettingsScreen.tsx, hooks/useMyLocation.ts |
| DELETE /v1/member-profiles/location | user | features/location/LocationSettingsScreen.tsx |
| GET /v1/members/me | user | auth/AuthProvider.tsx, features/mypage/MyPageScreen.tsx, SettingsScreen.tsx, LocationSettingsScreen.tsx |
| PATCH /v1/members/me | user | features/mypage/NicknameSheet.tsx, features/mypage/ConsentSettings.tsx |
| PUT /v1/members/me/onboarding | user | features/onboarding/PersonalizationStep.tsx, features/tutorial/TutorialStep.tsx |
| GET /v1/members/me/usage-stats | 없음 | - |
| POST /v1/members/me:withdraw | user | features/login/KakaoCallbackStep.tsx (재인증 후) |
| DELETE /v1/owner/auth/tokens | owner | features/store/LogoutSheet.tsx, features/signup/SignupFlowLayout.tsx |
| POST /v1/owner/auth/tokens | owner | features/login/useLoginForm.ts, pages/LoginPage.tsx |
| POST /v1/owner/auth/tokens:refresh | owner | api/client.ts (401 시 자동) |
| POST /v1/owner/campaigns | owner | features/campaign/form/CampaignFormLayout.tsx (saveCouponStep) |
| GET /v1/owner/campaigns | owner | features/campaign/list/useCampaignList.ts, useEndedCampaignList.ts, features/home/useHome.ts |
| PATCH /v1/owner/campaigns/{campaignId} | owner | features/campaign/form/CampaignFormLayout.tsx |
| GET /v1/owner/campaigns/{campaignId} | owner | features/campaign/form/CampaignFormProvider.tsx, detail/useCampaignDetail.ts |
| GET /v1/owner/campaigns/{campaignId}/analytics/funnel | 없음 | - |
| GET /v1/owner/campaigns/{campaignId}/coupons/summary | 없음 | - |
| POST /v1/owner/campaigns/{campaignId}/poster | owner | features/campaign/form/CampaignFormLayout.tsx (savePosterStep) |
| GET /v1/owner/campaigns/{campaignId}/poster | owner | features/campaign/form/steps/ReviewStep.tsx, detail/useCampaignDetail.ts |
| POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload | owner | features/campaign/form/steps/PosterStep.tsx |
| POST /v1/owner/campaigns/{campaignId}:activate | owner | features/campaign/form/CampaignFormLayout.tsx |
| POST /v1/owner/campaigns/{campaignId}:cancel | owner | features/campaign/list/CurrentCampaignActions.tsx |
| POST /v1/owner/campaigns/{campaignId}:end | owner | features/campaign/list/CurrentCampaignActions.tsx |
| POST /v1/owner/campaigns/{campaignId}:pause | owner | features/campaign/list/CurrentCampaignActions.tsx |
| POST /v1/owner/campaigns/{campaignId}:resume | owner | features/campaign/list/CurrentCampaignActions.tsx |
| POST /v1/owner/coupon-usages | 없음 | - |
| GET /v1/owner/me | owner | auth/AuthProvider.tsx, features/signup/SignupCompleteAction.tsx |
| PATCH /v1/owner/me/tutorial | owner | features/home/FirstVisitTutorial.tsx |
| GET /v1/owner/onboarding | 없음 | - |
| POST /v1/owner/payments | owner | features/points/PointCheckout.tsx |
| GET /v1/owner/payments | 없음 | - |
| GET /v1/owner/payments/{paymentId} | owner | features/points/useChargeConfirmation.ts |
| POST /v1/owner/payments/{paymentId}:confirm | owner | features/points/useChargeConfirmation.ts |
| GET /v1/owner/point-policy | owner | hooks/useChargePolicy.ts |
| GET /v1/owner/points/balance | owner | features/home/useHome.ts, features/points/usePointHistory.ts, campaign/form/steps/BudgetStep.tsx |
| GET /v1/owner/points/ledger | owner | features/points/usePointHistory.ts |
| GET /v1/owner/points/spend-summary | owner | features/points/usePointHistory.ts |
| POST /v1/owner/refund-requests | 없음 | - |
| GET /v1/owner/refund-requests | 없음 | - |
| GET /v1/owner/refund-requests/preview | 없음 | - |
| GET /v1/owner/report | 없음(함수만 있음) | api/report.ts getOwnerReport 를 부르는 화면 없음 |
| GET /v1/owner/store | owner | features/store/useStoreView.ts 외 다수, features/signup/SignupFlowProvider.tsx |
| POST /v1/owner/store-images:complete | owner | features/signup/signupSteps.ts |
| POST /v1/owner/store-images:confirmUpload | owner | features/signup/steps/ImagesStep.tsx |
| POST /v1/owner/store-images:prepareUpload | owner | features/signup/steps/ImagesStep.tsx |
| POST /v1/owner/store-menu-images:confirmUpload | owner | features/signup/steps/MenuStep.tsx |
| POST /v1/owner/store-menu-images:prepareUpload | owner | features/signup/steps/MenuStep.tsx |
| POST /v1/owner/store-menus | owner | features/signup/steps/MenuStep.tsx |
| POST /v1/owner/store-menus:complete | owner | features/signup/signupSteps.ts |
| PUT /v1/owner/store/basic | owner | features/signup/signupSteps.ts |
| PUT /v1/owner/store/hours | owner | features/signup/signupSteps.ts |
| PUT /v1/owner/store/location | owner | features/signup/signupSteps.ts |
| POST /v1/owner/store:complete | owner | features/signup/SignupFlowLayout.tsx |
| GET /v1/owner/stores/{storeId}/coupon-usages | 없음 | - |
| GET /v1/owner/stores/{storeId}/coupon-usages/daily | 없음 | - |
| GET /v1/owner/templates | owner | features/campaign/form/steps/PosterStep.tsx |
| PUT /v1/owner/terms-agreements | owner | features/signup/signupSteps.ts |
| POST /v1/owners | owner | features/signup/useSignupAccountForm.ts |
| GET /v1/stores | user | features/explore/ExploreScreen.tsx |
| GET /v1/stores/{storeId} | user | features/store-detail/StoreDetailScreen.tsx |
| POST /v1/swipes | user | features/swipe/SwipeFeed.tsx |
| GET /v1/wishlist | user | features/wishlist/WishlistScreen.tsx, store-detail/StoreDetailScreen.tsx, hooks/useWishCount.ts |
| POST /v1/wishlist | user | features/store-detail/StoreDetailScreen.tsx |
| DELETE /v1/wishlist/{campaignId} | user | features/store-detail/StoreDetailScreen.tsx, features/wishlist/WishlistScreen.tsx |
| POST /webhook/payments | 없음(토스페이먼츠 호출) | - |
| GET/POST /webhook/kakao/unlink | 없음(카카오 호출) | - |

## 시나리오

### 화면 없는 API

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-001 | 집계 실행 조회 성공 | 관리자가 영업일 지정 조회 | GET /v1/admin/aggregation-runs?businessDate= | - | ADMIN 또는 SUPER_ADMIN, 그날 DAILY_AGGREGATION 실행 행 있음 | 200, 상태/세대/시각 반환 | - |
| B-002 | 집계 실행 조회 기록 없음 | 실행된 적 없는 날짜 조회 | GET /v1/admin/aggregation-runs | - | ADMIN, 그날 행 없음 | 200, data=null | - |
| B-003 | 집계 실행 조회 날짜 누락/형식 오류 | businessDate 빠짐 또는 2026-13-01 | GET /v1/admin/aggregation-runs | COMMON-003 | ADMIN | 400 | - |
| B-004 | 수동 재계산 접수 | 어제 이전 날짜 지정 | POST /v1/admin/aggregations:recalculate | - | ADMIN, 그날 행 없음/FAILED/SUCCESS | 202, runId 반환, 감사 로그 ANALYTICS_RECALCULATE, 별도 스레드에서 집계, 행 RUNNING -> SUCCESS | 오늘 미만, 13개월 이내 |
| B-005 | 이미 FINAL 날짜 재계산 | SUCCESS 날짜 재요청 | POST /v1/admin/aggregations:recalculate | - | 그날 행 SUCCESS | 202, rerun 으로 재점유, 공개 트랜잭션이 그날 행 일괄 교체 | 오늘 미만 |
| B-006 | 재계산 원천 확정 검사 생략 | 00:00 묶음 미확정 날짜 재계산 | POST /v1/admin/aggregations:recalculate | - | 그날 원천 배치 미완료 | 202, 정기 실행과 달리 SOURCE_NOT_READY 검사 없이 집계 | 어제 |
| B-007 | 재계산 날짜 불가 | 오늘/미래/13개월 초과/본문 {} | POST /v1/admin/aggregations:recalculate | ANALYTICS-002 | ADMIN | 422 | businessDate >= 오늘 또는 < 오늘-13개월 |
| B-008 | 재계산 중복 실행 | 같은 날짜 RUNNING 중 재요청 | POST /v1/admin/aggregations:recalculate | ANALYTICS-003 | 그날 행 RUNNING | 409 | - |
| B-009 | 재계산 본문 없음 | 빈 본문 | POST /v1/admin/aggregations:recalculate | COMMON-003 | ADMIN | 400 | - |
| B-010 | 재계산 대기열 초과 | 실행 1 + 대기 4 가득 찬 상태에서 요청 | POST /v1/admin/aggregations:recalculate | COMMON-001 | 실행기 큐 가득 | 500, 잡은 행 abandon 으로 FAILED 닫음 | - |
| B-011 | 배치 이력 조회 성공 | 기간 지정 조회 | GET /v1/admin/batch-executions?from&to | - | ADMIN, 실행 기록 있음 | 200, 영업일 내림차순, FAILED 이면서 BUNDLE/STANDALONE 이면 retryable=true, 단계 행은 false | 기간 31일 이내 |
| B-012 | 배치 이력 필터 | jobName, status=FAILED, pageSize, pageToken | GET /v1/admin/batch-executions | - | ADMIN | 200, 조건 일치 행만, 다음 토큰 | - |
| B-013 | 배치 이력 기간 위반 | from/to 누락, to<from, 31일 초과 | GET /v1/admin/batch-executions | OPS-004 | ADMIN | 400 | to > from+30일 |
| B-014 | 배치 이력 형식 오류 | 날짜 형식, status 값, pageSize 0/101, 풀 수 없는 pageToken | GET /v1/admin/batch-executions | COMMON-002 | ADMIN | 400 | - |
| B-015 | 수동 재실행 접수 | FAILED 묶음 재실행 | POST /v1/admin/batch-executions/DAILY_0000/{date}:retry | - | ADMIN, DAILY_0000 행 FAILED | 202, 행 RUNNING, attemptNo 증가, 감사 로그 BATCH_EXECUTION_RETRY, 별도 스레드 resume | - |
| B-016 | 묶음 밖 작업 재실행 | FAILED STANDALONE 재실행 | POST /v1/admin/batch-executions/COUPON_OPEN/{date}:retry | - | ADMIN, 등록된 묶음 밖 작업 행 FAILED | 202, 해당 작업 resume | - |
| B-017 | 단계/미등록 이름 재실행 | BILLING_RESERVE 같은 단계명 또는 모르는 이름 | POST .../{jobName}/{date}:retry | OPS-001 | ADMIN | 422 | - |
| B-018 | 재실행 기록 없음 | 행 없는 날짜 | POST .../{jobName}/{date}:retry | OPS-002 | ADMIN, 등록 작업, 그날 행 없음 | 404 | - |
| B-019 | 재실행 FAILED 아님 | RUNNING/SUCCESS 행 | POST .../{jobName}/{date}:retry | OPS-003 | 행 RUNNING 또는 SUCCESS | 409 | - |
| B-020 | 재실행 동시 요청 | 같은 FAILED 행에 두 요청 동시 | POST .../{jobName}/{date}:retry | OPS-003 | 행 FAILED | 하나 202, 다른 하나 조건부 UPDATE 실패로 409 | - |
| B-021 | 재실행 날짜 형식 오류 | businessDate=20261021 | POST .../{jobName}/{date}:retry | COMMON-003 | ADMIN | 400 | - |
| B-022 | 재실행 시작 실패 | 대기열 가득 또는 감사 로그 실패 | POST .../{jobName}/{date}:retry | COMMON-001 | 실행기 큐 가득 | 500, 되돌린 행 다시 FAILED | - |
| B-023 | 이벤트 수집 성공 | poster_open/map_view/wishlist_view 묶음 전송 | POST /v1/events | - | MEMBER, 이벤트 1~50건, UUID eventId | 202, accepted/duplicated 수 반환 | - |
| B-024 | 이벤트 중복 전송 | 같은 eventId 재전송 | POST /v1/events | - | 이미 저장된 eventId | 202, duplicated 증가, 새 행 없음 | - |
| B-025 | 이벤트 빈 배열 | events=[] | POST /v1/events | - | MEMBER | 202, accepted=0 | - |
| B-026 | 허용 안 된 이벤트 유형 | impression, wish, issue 등 서버 유형 전송 | POST /v1/events | ANALYTICS-004 | MEMBER | 400, 묶음 전체 거절 | - |
| B-027 | 이벤트 요청 형식 오류 | events 누락, 51건, eventId 비UUID, 필수값 null | POST /v1/events | ANALYTICS-005 | MEMBER | 400 | - |
| B-028 | 이벤트 본문 해석 불가 | JSON 아님, occurredAt 형식 오류, 본문 없음 | POST /v1/events | COMMON-003 | MEMBER | 400 | - |
| B-029 | 회원 사용 통계 조회 | 마이페이지 통계 | GET /v1/members/me/usage-stats | - | MEMBER, daily_member_usage 행 | 200, 전날까지 확정 합계, 행 없으면 0 | 오늘 사용분은 다음 날 01:00 집계 뒤 반영 |
| B-030 | 당일 발급 한도 조회 | 발급 한도 확인 | GET /v1/coupons/issuance-limit | - | MEMBER, 오늘 발급 N건 | 200, businessDate, issuedCount, dailyLimit, reached=(N>=한도) | KST 날짜 기준 |
| B-031 | 점주 등록 진행 조회 | 진행 단계 조회 | GET /v1/owner/onboarding | - | OWNER, 상태 ONBOARDING 또는 ACTIVE | 200, 단계별 완료, 다음 단계, canFinalize, 진행 행 없으면 version=0 | - |
| B-032 | 점주 등록 진행 상태 불가 | SUSPENDED/WITHDRAWN 점주 | GET /v1/owner/onboarding | OWNER-002 | OWNER 상태 SUSPENDED 또는 WITHDRAWN | 403 | - |
| B-033 | 점주 등록 진행 점주 없음 | 토큰 유효하나 점주 행 없음 | GET /v1/owner/onboarding | AUTH-005 | 삭제된 점주 | 401 | - |
| B-034 | 캠페인 퍼널 조회 | 기간 지정 조회 | GET /v1/owner/campaigns/{id}/analytics/funnel?from&to | - | OWNER, 본인 캠페인, 상태 무관(ONBOARDING/SUSPENDED도 통과) | 200, 노출/찜/패스/발급/사용 수, 분모 0이면 비율 null | 양끝 포함 31일 이내 |
| B-035 | 퍼널 기간 오류 | to<from, 32일 이상 | GET .../analytics/funnel | ANALYTICS-001 | OWNER | 400 | - |
| B-036 | 퍼널 남의/없는 캠페인 | 다른 점주 또는 존재하지 않는 캠페인 | GET .../analytics/funnel | AUTH-006 | OWNER | 403, CAMPAIGN-012 아님 | - |
| B-037 | 퍼널 파라미터 오류 | campaignId 0 또는 from 누락 | GET .../analytics/funnel | COMMON-002, COMMON-003 | OWNER | 400 | - |
| B-038 | 점주 리포트 조회 | 기간 지정 | GET /v1/owner/report?from&to | - | OWNER, 상태 검사 없음 | 200, 일별 캠페인 성과, totalSpentAmount(음수 정정 포함), 없으면 빈 목록 0 | 양끝 포함 30일 이내 |
| B-039 | 점주 리포트 기간 오류 | to<from 또는 31일 이상 | GET /v1/owner/report | ANALYTICS-001 | OWNER | 400 | - |
| B-040 | 점주 리포트 파라미터 누락 | from 누락/형식 오류 | GET /v1/owner/report | COMMON-003 | OWNER | 400 | - |
| B-041 | 캠페인 쿠폰 요약 | 기간 지정 | GET /v1/owner/campaigns/{id}/coupons/summary?from&to | - | OWNER ACTIVE, 본인 캠페인 | 200, 총수량/발급/사용/잔여 | 양끝 포함 31일 이내 |
| B-042 | 쿠폰 요약 기간 오류 | to<from, 32일 이상, campaignId<=0 | GET .../coupons/summary | COMMON-002 | OWNER | 400 (IllegalArgumentException) | - |
| B-043 | 쿠폰 요약 비ACTIVE 점주 | ONBOARDING/SUSPENDED | GET .../coupons/summary | AUTH-006 | OWNER 상태 ACTIVE 아님 | 403 | - |
| B-044 | 쿠폰 요약 남의 캠페인 | 다른 점주/없는 캠페인 | GET .../coupons/summary | COUPON-011 | OWNER ACTIVE | 403 | - |
| B-045 | QR 사용 확정 | 점주가 회원 QR 스캔 | POST /v1/owner/coupon-usages | - | OWNER ACTIVE, 매장 관리 권한, 쿠폰 ISSUED, 회원 ACTIVE, 매장 100m 이내, 최신 QR | 201, 쿠폰 USED, 사용 이력 저장, CouponRedeemedEvent -> redeem 이벤트 | 사용 가능 시간 안, QR 만료 60초 창 안 |
| B-046 | QR 사용 비ACTIVE 점주 | ONBOARDING/SUSPENDED 점주 스캔 | POST /v1/owner/coupon-usages | COUPON-013 | OWNER 상태 ACTIVE 아님 | 403 | - |
| B-047 | QR 토큰 모름/교체됨 | 없는 토큰 또는 재생성 전 토큰 | POST /v1/owner/coupon-usages | COUPON-010 | qrToken 불일치 또는 qrVersion 다름 | 409 | - |
| B-048 | QR 만료 | 생성 뒤 60초 지남 | POST /v1/owner/coupon-usages | COUPON-010 | qrExpiresAt 경과 | 409 | now >= qrExpiresAt |
| B-049 | 남의 매장 쿠폰 | 다른 매장 쿠폰 QR 스캔 | POST /v1/owner/coupon-usages | COUPON-011 | 쿠폰 이벤트 매장 관리 권한 없음 | 403 | - |
| B-050 | 같은 QR 재요청 | 사용 확정 뒤 같은 토큰 재전송 | POST /v1/owner/coupon-usages | COUPON-012 | 쿠폰 USED, 마지막 토큰 일치 | 409, 멱등 재응답 아님 | - |
| B-051 | 사용된 쿠폰 다른 토큰 | USED 쿠폰의 옛 토큰 | POST /v1/owner/coupon-usages | COUPON-010 | 쿠폰 USED, 토큰 불일치 | 409 | - |
| B-052 | 회원 비활성 | 정지/탈퇴 회원 쿠폰 | POST /v1/owner/coupon-usages | COUPON-003 | 회원 상태 ACTIVE 아님 또는 회원 없음 | 403 | - |
| B-053 | 매장 밖 스캔 | 매장에서 100m 초과 위치 | POST /v1/owner/coupon-usages | COUPON-009 | 매장 좌표 있음 | 403 | - |
| B-054 | 사용 불가 쿠폰 | EXPIRED/REMOVED 쿠폰 또는 시간 밖 | POST /v1/owner/coupon-usages | COUPON-002 | 쿠폰 ISSUED 아님 또는 사용 시간 밖 | 409 | now < usableStartAt 또는 >= usableEndAt |
| B-055 | 쿠폰 데이터 손상 | 쿠폰 이벤트 없음, 매장 좌표 없음 | POST /v1/owner/coupon-usages | COMMON-001 | 스냅샷 누락 | 500, ERROR 로그 | - |
| B-056 | QR 사용 요청 검증 실패 | 위도 91, qrToken 누락, 본문 JSON 오류 | POST /v1/owner/coupon-usages | COMMON-002, COMMON-003 | OWNER | 400 | - |
| B-057 | 동시 사용 경합 | 같은 쿠폰 두 기기 동시 스캔 | POST /v1/owner/coupon-usages | COUPON-012 | 쿠폰 ISSUED | 행 잠금 직렬화, 하나 201, 다른 하나 409 | - |
| B-058 | 매장 사용 이력 조회 | 기간 지정 커서 조회 | GET /v1/owner/stores/{storeId}/coupon-usages?from&to | - | OWNER ACTIVE, 매장 관리 권한 | 200, 사용 시각 내림차순, 다음 토큰 | 기간 길이 제한 없음 |
| B-059 | 사용 이력 비ACTIVE/남의 매장 | 상태 비ACTIVE 또는 권한 없음 | GET .../coupon-usages | AUTH-006 | OWNER | 403 | - |
| B-060 | 사용 이력 입력 오류 | to<from, 다른 조건의 pageToken, 깨진 토큰, pageSize 0/101 | GET .../coupon-usages | COMMON-002 | OWNER ACTIVE | 400 | - |
| B-061 | 사용 이력 파라미터 누락 | from 누락 | GET .../coupon-usages | COMMON-003 | OWNER | 400 | - |
| B-062 | 매장 일별 사용 수 | 기간 지정 | GET /v1/owner/stores/{storeId}/coupon-usages/daily?from&to | - | OWNER ACTIVE, 매장 관리 권한 | 200, 사용 없는 날 0으로 채움, 합계 | 양끝 포함 31일 이내 |
| B-063 | 일별 사용 기간 오류 | to<from, 32일 이상, storeId<=0 | GET .../coupon-usages/daily | COMMON-002 | OWNER | 400 | - |
| B-064 | 일별 사용 비ACTIVE 점주 | 상태 비ACTIVE | GET .../coupon-usages/daily | AUTH-006 | OWNER | 403 | - |
| B-065 | 일별 사용 남의 매장 | 권한 없는 storeId | GET .../coupon-usages/daily | COUPON-011 | OWNER ACTIVE | 403 | - |
| B-066 | 환불 미리보기 가능 | 잔액 확인 | GET /v1/owner/refund-requests/preview | - | OWNER ACTIVE, 잔액>0, 집행 중 캠페인 없음, 대기 요청 없음 | 200, refundable=잔액, allowed=true | - |
| B-067 | 환불 미리보기 막힘 | 잔액 0/집행 중 캠페인/대기 요청 | GET /v1/owner/refund-requests/preview | - | 각 차단 조건 | 200, allowed=false, blockedReason=NO_BALANCE/ACTIVE_CAMPAIGN/PENDING_REQUEST | - |
| B-068 | 환불 요청 성공 | 전액 환불 요청 | POST /v1/owner/refund-requests | - | OWNER ACTIVE, 계정 행 있음, 잔액>0, DRAFT/ENDED 외 캠페인 없음, REQUESTED 없음 | 201, REQUESTED 행 생성(전액), 원장 변화 없음 | - |
| B-069 | 환불 계정 없음/잔액 0 | 충전 이력 없음 또는 잔액 0 이하 | POST /v1/owner/refund-requests | BILLING-012 | 계정 행 없음 또는 잔액<=0 | 422 | - |
| B-070 | 환불 집행 중 캠페인 | SCHEDULED/ACTIVE/PAUSED 캠페인 있음 | POST /v1/owner/refund-requests | BILLING-010 | 캠페인 상태 DRAFT/ENDED 외 | 409 | - |
| B-071 | 환불 대기 요청 있음 | 처리 전 재요청 또는 동시 요청 | POST /v1/owner/refund-requests | BILLING-011 | REQUESTED 행 존재 또는 UNIQUE 위반 | 409 | - |
| B-072 | 환불 잠금 대기 초과 | 00:00 예약과 계정 행 잠금 경합 | POST /v1/owner/refund-requests | BILLING-006 | 계정 행 잠금 중 | 503 | 00:00 묶음 실행 중 |
| B-073 | 환불 사유 초과 | reason 256자 | POST /v1/owner/refund-requests | COMMON-002 | OWNER ACTIVE | 400 | - |
| B-074 | 내 환불 요청 목록 | 커서 조회 | GET /v1/owner/refund-requests | - | OWNER ACTIVE | 200, 요청 시각 내림차순, 깨진 토큰은 첫 페이지, pageSize 1~100으로 보정 | - |
| B-075 | 환불 경로 비ACTIVE 점주 | ONBOARDING/SUSPENDED 점주가 미리보기/요청/목록 | /v1/owner/refund-requests/** | BILLING-023 | OWNER 상태 ACTIVE 아님 (인터셉터, 캐시 우회) | 403 | - |
| B-076 | 내 결제 내역 | 커서 조회 | GET /v1/owner/payments | - | OWNER ACTIVE | 200, pgTid 있는 결제만 최신순, 결제창 이탈 주문 제외, 깨진 토큰은 첫 페이지 | - |
| B-077 | 결제 내역 비ACTIVE 점주 | ONBOARDING/SUSPENDED | GET /v1/owner/payments | BILLING-023 | OWNER 상태 ACTIVE 아님 | 403 | - |
| B-078 | 결제/환불 목록 pageSize 형식 오류 | pageSize=abc | GET /v1/owner/payments, /v1/owner/refund-requests | COMMON-003 | OWNER ACTIVE | 400 | - |

### 웹훅

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-079 | 결제 웹훅 승인 반영 | PG 상태 변경 통보 -> 재조회 APPROVED | POST /webhook/payments | - | 인증 없음, 결제 UNKNOWN 또는 REQUESTED(pgTid 있음), 주문/금액 일치 | 200, APPROVED, 계정 행 생성, CHARGE 원장, 재조회 아웃박스 닫음, PointCharged -> 커밋 뒤 NO_POINTS 캠페인 재예약 | 재예약은 그날 계획 행 있는 캠페인만 |
| B-080 | 결제 웹훅 결제창 전 주문 승인 | REQUESTED, pgTid 없음 -> APPROVED | POST /webhook/payments | - | 결제 REQUESTED, pgTid null | 200, 본문 paymentKey 를 pgTid 로 적고 승인 | - |
| B-081 | 결제 웹훅 pgTid 충돌 | 다른 결제가 같은 키 사용 또는 다른 키가 이미 적힘 | POST /webhook/payments | - | uk_payment_pg_tid 위반 또는 기존 pgTid 다름 | 200, 무시(ignored) | - |
| B-082 | 결제 웹훅 실패 반영 | 재조회 FAILED | POST /webhook/payments | - | 결제 UNKNOWN 또는 pgTid 있는 REQUESTED | 200, FAILED, 사유 기록, 아웃박스 닫음, 충전 없음 | - |
| B-083 | 결제 웹훅 실패지만 pgTid 없음 | 재조회 FAILED, 승인 시도 전 | POST /webhook/payments | - | REQUESTED, pgTid null | 200, 상태 유지 | - |
| B-084 | 결제 웹훅 만료/취소 | 재조회 EXPIRED 또는 CANCELED | POST /webhook/payments | - | 미확정 결제 | 200, CANCELLED, 충전 없음 | - |
| B-085 | 결제 웹훅 대기/없음 | 재조회 PENDING | POST /webhook/payments | - | 미확정 결제 | 200, 변화 없음 | - |
| B-086 | 결제 웹훅 본문 위조/불일치 | 재조회 NOT_FOUND 또는 orderId/금액 다름 | POST /webhook/payments | - | 본문 상태는 믿지 않고 PG 재조회 결과만 따른다 | 200, 무시, 상태 그대로 | - |
| B-087 | 결제 웹훅 형식 불량 | eventType != PAYMENT_STATUS_CHANGED, paymentKey/orderId 빈값, JSON 해석 불가 | POST /webhook/payments | - | - | 200, 무시 (400 아님) | - |
| B-088 | 결제 웹훅 모르는 주문 | orderId 없는 주문 | POST /webhook/payments | - | 결제 행 없음 | 200, 무시 | - |
| B-089 | 결제 웹훅 중복 전달 | 이미 확정된 결제 재통보 | POST /webhook/payments | - | 결제 APPROVED/FAILED/CANCELLED | 200, PG 재조회 없이 무시, 이중 충전 없음 | - |
| B-090 | 결제 웹훅 연속 통보 제한 | 같은 orderId 10초 안 두 번째 | POST /webhook/payments | - | 미확정 결제, 서버 메모리 가드 | 429 (본문 없음), PG 재전송 유도 | 첫 통보 후 10초 이내, 서버별 |
| B-091 | 결제 웹훅 PG 조회 실패 | PG 오류 또는 서킷 열림 | POST /webhook/payments | COMMON-001 | 미확정 결제 | 500, PG 재전송 유도, 가드 창은 이미 시작됨 | - |
| B-092 | 카카오 해제 통보 탈퇴 | 카카오 연결 해제 통보 | GET 또는 POST /webhook/kakao/unlink?app_id&user_id | - | Authorization=KakaoAK 관리자키, app_id 일치, 최초 가입 회원(탈퇴 이력 없음) | 200, 회원 WITHDRAWN, 탈퇴 이력 저장, 프로필 삭제, providerUserId 비움, 토큰 폐기 | - |
| B-093 | 카카오 해제 정지 회원 | SUSPENDED 회원 통보 | /webhook/kakao/unlink | - | 회원 SUSPENDED, 이력 없음 | 200, 탈퇴 처리 | - |
| B-094 | 카카오 해제 재가입 계정 | 탈퇴 이력 있는 providerUserId | /webhook/kakao/unlink | - | 같은 카카오 번호 탈퇴 이력 존재 | 200, 계정 유지, ambiguous 지표, ERROR 로그 | - |
| B-095 | 카카오 해제 모르는 회원 | 없는 user_id 또는 이미 탈퇴(번호 비워짐) | /webhook/kakao/unlink | - | 매칭 회원 없음 | 200, 변화 없음 | - |
| B-096 | 카카오 해제 인증 실패 | 관리자키 불일치, app_id 다름, 설정값 빈값 | /webhook/kakao/unlink | - | - | 200, AUTH_MISMATCH 로그, 처리 안 함 | - |
| B-097 | 카카오 해제 user_id 누락 | user_id 없음 | /webhook/kakao/unlink | - | 인증 통과 | 200, USER_ID_MISSING 로그 | - |
| B-098 | 카카오 해제 처리 중 예외 | 토큰 폐기 저장소 장애 등 | /webhook/kakao/unlink | - | 인증 통과, 회원 있음 | 200, 트랜잭션 롤백으로 회원 유지, ERROR 로그만, 카카오 재전송 없음 | - |
| B-099 | 카카오 해제 중복 통보 | 같은 통보 재수신 | /webhook/kakao/unlink | - | 첫 통보로 이미 탈퇴 | 200, providerUserId 비워져 매칭 없음, 변화 없음 | - |

### 00:00 묶음 단계별

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-100 | 묶음 정상 실행 | cron 0 0 0 (Asia/Seoul) -> claim(DAILY_0000, D) 성공 -> @Order 10,20,30,35,40,50,60,75,80,90 순서 실행 | DailyJobScheduler.runDailyBundle -> BatchJobRunner.runBundle | - | batch_execution_log 에 (DAILY_0000, D) 없음, 배치 서버(launchcatch.scheduler.enabled=true) | 단계 행 10개와 (BILLING_DAILY_RELEASE, D-1) 가 SUCCESS, 마지막에 (DAILY_0000, D) SUCCESS | D 00:00 KST, businessDate=D |
| B-101 | 배치 서버 두 대 동시 시도 | 두 서버가 같은 시각 upsertClaim | DailyJobScheduler.runDailyBundle | - | 두 서버 모두 스케줄러 켜짐 | owner_id 가 자기인 서버만 RUNNING 으로 점유해 실행, 다른 서버는 "다른 배치 서버가 점유" 로그 후 종료 | D 00:00 |
| B-102 | 앱 인스턴스에서는 돌지 않음 | 스케줄러 설정 꺼짐 | SchedulingConfig(@ConditionalOnProperty) | - | launchcatch.scheduler.enabled 미설정 또는 false | @Scheduled 작업 전부 미기동 | 상시 |
| B-103 | 단계 사이 묶음 점유 상실 | 각 단계 전 renew(DAILY_0000) 0건 | BatchJobRunner.runBundle | - | 다른 서버가 묶음 행을 이어받음 | 다음 단계로 가지 않고 중단, 묶음 행은 이어받은 서버가 판정 | 실행 중 |
| B-104 | 앞 단계 실패 시 뒤 단계 미실행 | 단계 예외(비일시적) | BatchJobRunner.runStep | FAILED | 예: 40 단계 실패 | 그 단계 행 FAILED, (DAILY_0000, D) FAILED 사유 "<단계> 단계가 실패했다", 50 이후 단계 행 없음 | D 00:00 |
| B-105 | 전일 소진 대사 일치 | 40 BILLING_SPEND_RECON_PREV | SpendReconciliationService.reconcilePreviousDay | - | D-1 budget_daily_plan 존재, Valkey 카운터 = 원장 DEDUCT 합, DEDUCT 합 <= daily_budget | settlement_mismatch 기록 없음, 단계 SUCCESS | 대상일 D-1 |
| B-106 | 전일 계획 없음 | 40 단계 | SpendReconciliationService | - | D-1 budget_daily_plan 0건 | 0 반환, 기록 없음, SUCCESS | 대상일 D-1 |
| B-107 | 카운터와 원장 불일치 | 40 단계 | SpendMismatchRecorder.insertCampaignSpendMismatchIfAbsent | CAMPAIGN_SPEND_VS_LEDGER | 카운터 spend != 원장 DEDUCT 합 | 불일치 행 1건(종류+캠페인당 1건), resolution_type 비움, 카운터 재구성 안 함, 단계 SUCCESS | 대상일 D-1 |
| B-108 | 초과 소진 | 40 단계 | SpendMismatchRecorder.insertCampaignOverspendIfAbsent | CAMPAIGN_OVERSPEND | 원장 DEDUCT 합 > 계획 daily_budget, 또는 해제된 D-2 의 DEDUCT 합 + RELEASE 합 > daily_budget(자정 직전에 시작해 해제 뒤 커밋된 전날 차감, reconcileReleasedDay) | 기대값=예약액, 실제값=DEDUCT 합(해제 뒤 대조는 DEDUCT 합 + RELEASE 합)으로 기록, 되돌리지 않음, 카운터 없는 캠페인도 검사 | 대상일 D-1(해제 뒤 대조는 D-2) |
| B-109 | Valkey 해시 없음 | 40 단계 | SpendReconciliationService | - | 계획은 있으나 카운터 해시 없음(재시작/만료) | 카운터 대조 건너뜀, BILLING_SPEND_RECON_HASH_MISSING WARN, 초과 소진 검사는 수행 | 대상일 D-1 |
| B-110 | 대사 재실행 멱등 | 40 단계 재실행 | SettlementMismatchRepository(ON DUPLICATE KEY) | - | 같은 D-1 불일치가 이미 있음 | 불일치 행 중복 없음 | 같은 D |
| B-111 | 전일 남은 예약 해제 | 50 BILLING_RESERVE 앞부분 release(D-1) | DailyReservationService.release -> DailyReservationRecorder.releaseForOwner | - | D-1 계획 daily_budget - DEDUCT 합 > 0, RELEASE 없음 | 점주마다 계정 행 잠금 후 RELEASE 원장 1줄, 잔액 복귀 | 대상일 D-1 |
| B-112 | 해제 대상 아님(음수/0/이미 해제) | 50 단계 release | DailyReservationService.release | - | 남은 몫 <= 0 또는 RELEASE 이미 있음 | RELEASE 미기록(음수는 40 단계가 CAMPAIGN_OVERSPEND 로 남김) | 대상일 D-1 |
| B-113 | 단가 설정 오류 | 50 단계 unitPriceOf | DailyReservationService | FAILED | D 계획 없음, platform_setting 노출 단가 <= 0 | IllegalStateException, 아무것도 쓰지 않고 단계 FAILED, 묶음 FAILED | D 00:00 |
| B-114 | 같은 날 단가 여럿 저장 | 50 단계 재실행 | DailyReservationService.unitPriceOf | FAILED | D 계획에 서로 다른 unit_price 2개 이상 | IllegalStateException 으로 단계 FAILED | 같은 D 재실행 |
| B-115 | 재실행 시 저장 단가 고정 | 50 단계 재실행 | DailyReservationService.unitPriceOf | - | D 계획이 일부 있고 그 뒤 설정 단가 변경 | 계획에 저장된 단가를 그대로 사용, 설정 변경은 다음 영업일부터 | 같은 D |
| B-116 | 잔액 충분 예약 | 50 단계 reserveForOwner | DailyReservationRecorder | - | 대상 캠페인 ACTIVE, 기간 내, 잔액 >= 하루 예산 | RESERVE = 하루 예산, 계획 paused=true 로 생성 후 confirmStatus 가 ACTIVE 확인해 paused=false | businessDate=D |
| B-117 | 잔액 부분 예약 | 50 단계 | DailyReservationRecorder | - | 0 < 잔액 < 하루 예산 | RESERVE = 남은 잔액, 계획 daily_budget = 그 값 | D |
| B-118 | 잔액 0 예약 | 50 단계 | DailyReservationRecorder | - | 잔액 0(계정 행 없으면 createIfAbsent 로 0 행 생성) | RESERVE 없이 daily_budget=0 계획만 생성 | D |
| B-119 | 한 점주 여러 캠페인 배분 | 50 단계 | DailyReservationRecorder | - | 점주 캠페인 2개 이상(코드상 집행 중 1개 제한 있으나 계약은 다건) | 캠페인 ID 오름차순으로 잔액을 앞에서부터 채움 | D |
| B-120 | 잔액 음수 | 50 단계 balanceOf | DailyReservationRecorder | - | 원장 합계 < 0 | 0 으로 보고 RESERVE_NEGATIVE_BALANCE ERROR, 계속 진행 | D |
| B-121 | 하루 예산 없는 대상 캠페인 | 50 단계 | DailyReservationRecorder.dailyBudgetOf | FAILED | 서빙 대상인데 daily_budget null | IllegalStateException, 그 점주 트랜잭션 롤백, 단계 FAILED | D |
| B-122 | 서빙 대상 1만 건 초과 | 50/60/75/90 단계 findServableOn | CampaignQueryServiceImpl.findServableOn | FAILED | 대상 캠페인 > 10000 | IllegalStateException 으로 해당 단계 FAILED | D |
| B-123 | PAUSED(OWNER) 캠페인 예약 | 50 단계 | findServableOn + confirmStatus | - | PAUSED, pausedReason=OWNER, 기간 내 | 예약과 계획은 생성, 계획은 paused 유지(과금 안 함) | D |
| B-124 | PAUSED(ADMIN) 캠페인 | 50 단계 | findServableOn | - | PAUSED, pausedReason=ADMIN | 서빙 대상 아님, 예약 없음 | D |
| B-125 | 예약 후 계획 상태 확인 | 50 단계 confirmPlans | DailyReservationRecorder.confirmStatus | - | 예약 중 캠페인이 중단/종료로 바뀜 | 캠페인 현재 상태로 paused 맞춤(ACTIVE 만 false), 캠페인 조회에서 빠진 계획은 paused 유지 | D |
| B-126 | 당일 소진 해시 생성 실패 | 50 단계 mirror | SpendHashRebuildService.rebuildAllIfAbsent | - | Valkey DataAccessException | RESERVE_HASH_MIRROR_FAILED WARN, 단계는 SUCCESS(차감 시 계획으로 재구성) | D |
| B-127 | 해제 완료 표시 | 50 단계 날짜별 해제 직후 DailyReleaseMarker.mark | BatchExecutionService.begin/succeed | - | 해당 날짜의 해제가 끝남(오늘 예약의 성공과 무관, 전날은 남은 해제액이 0이어도 표시) | (BILLING_DAILY_RELEASE, 그 날짜) SUCCESS 기록, 01:00 집계의 원천 확정 신호 | 행 날짜는 해제한 날짜(D-1 포함) |
| B-128 | 해제 표시 막힘 | 50 단계 DailyReleaseMarker.mark | DailyReleaseMarker | - | (BILLING_DAILY_RELEASE, 날짜) 가 남의 RUNNING(FAILED 는 다시 열어 SUCCESS 로 닫는다) | BILLING_RELEASE_MARK_BLOCKED WARN 만 남기고 단계는 계속, 그 날짜 집계는 SOURCE_NOT_READY 지속 | D-1 |
| B-129 | 예약 중간 실패 후 재실행 | 50 단계 재실행 | DailyReservationRecorder(insertIfAbsent) | - | 일부 점주만 계획/RESERVE 커밋됨 | 계획 있는 캠페인 건너뛰고 나머지만 배분, 중복 RESERVE 없음 | 같은 D |
| B-130 | 판정 OK | 60 BILLING_FUNDING_JUDGE | FundingJudgementService.judge | - | ACTIVE, 계획 daily_budget >= unit_price | Judgement.OK, 상태 변화 없음 | D |
| B-131 | 판정 NO_POINTS 중단 | 60 단계 | CampaignStatusService.pauseFromFunding | - | ACTIVE, daily_budget < unit_price(잔액 0 또는 부분) | PAUSED(NO_POINTS), status_log(SYSTEM, cause=DAILY), CampaignStatusChangedEvent(servable=false) -> 계획 paused, 후보 PAUSED, 쿠폰 회차 반영 | D |
| B-132 | 판정 RESUME | 60 단계 | CampaignStatusService.resumeFromFunding | - | PAUSED(NO_POINTS), daily_budget >= unit_price(전날 충전 등) | ACTIVE 로 재개, CampaignStatusChangedEvent(servable=true) | D |
| B-133 | NO_POINTS 재판정 유지 | 60 단계 | FundingJudgementService | - | PAUSED(NO_POINTS), daily_budget < unit_price | NO_POINTS 판정, pauseFromFunding 은 이미 목표 상태라 무변경 | D |
| B-134 | 계획 없는 대상 건너뜀 | 60 단계 | FundingJudgementService | - | 대상인데 D 계획 없음(예약 뒤 대상 됨) | BILLING_FUNDING_JUDGE_SKIPPED reason=NO_PLAN, 판정 없음 | D |
| B-135 | PAUSED(OWNER) 판정 제외 | 60 단계 isJudged | FundingJudgementService | - | PAUSED(OWNER) | 판정 대상 아님, 점주 재개 때 별도 판단 | D |
| B-136 | 판정 재실행 멱등 | 60 단계 재실행 | CampaignStatusService(*FromFunding) | - | 이미 판정 반영됨 | 같은 판정 재전송해도 결과 동일, 캠페인별 트랜잭션이라 앞 판정 유지 | 같은 D |
| B-137 | 충전 직후 재개 | 충전 커밋 AFTER_COMMIT | PointChargedListener -> RechargeReservationService.reserve | - | PAUSED(NO_POINTS), 기간 내, D 계획 있음, 충전 후 잔액 | RECHARGE_RESERVE 원장(delta), 계획 daily_budget 증가, newReserved >= unit_price 면 RESUME(cause=RECHARGE) -> ACTIVE | D 중 아무 때나(시각 제한 없음) |
| B-138 | 충전했으나 여전히 부족 | 충전 커밋 후 | RechargeReservationService | - | newReserved < unit_price | 예약액만 늘리고 PAUSED(NO_POINTS) 유지 | D |
| B-139 | 충전 시 계획 없음 | 충전 커밋 후 | RechargeReservationService | - | 00:00 예약 전 또는 계획 없는 캠페인 | 건너뜀, 다음 00:00 예약이 충전 잔액으로 배분 | D |
| B-140 | 같은 결제 이벤트 중복 | 충전 이벤트 재수신 | insertRechargeReserveIfAbsent | - | 같은 paymentId RECHARGE_RESERVE 존재 | 0건 삽입, 잔액 다시 빼지 않음 | D |
| B-141 | 재예약 실패 | 충전 커밋 후 예외 | PointChargedListener | - | 재예약 중 예외 | 예외 삼키고 BILLING_RECHARGE_RESERVE_FAILED WARN, 충전은 성공 유지, 다음 00:00 예약이 반영 | D |
| B-145 | 후보 적재 SERVABLE | 75 ADSERVING_CANDIDATE_LOAD | CandidateLoadService.load | - | ACTIVE, 가게 좌표 있음 | ad_candidate(D) upsert, state=SERVABLE | D |
| B-146 | 후보 적재 PAUSED | 75 단계 | CandidateLoadService.load | - | PAUSED(OWNER 또는 NO_POINTS) | state=PAUSED 로 적재(피드 미노출) | D |
| B-147 | 가게 없음/메뉴명 없음 | 75 단계 | CandidateLoadService.skip | - | 가게 미등록/좌표 없음, 또는 MENU 할인인데 메뉴 없음 | 해당 캠페인만 건너뜀, adserving_candidate_load_skipped_total{reason=store_missing/menu_missing} 증가 | D |
| B-148 | 적재 중 상태 전이 반영 | 75 단계 reconcile | CandidateLoadService.reconcile | - | 적재 후 statusChangedAt 이 더 늦은 캠페인 | 별도 트랜잭션에서 상태를 SERVABLE/PAUSED 로 갱신 | D |
| B-149 | 7일 지난 후보 삭제 | 75 단계 | deleteByBusinessDateBefore | - | business_date < D-7 후보 | 삭제 | D |
| B-150 | 후보 적재 미실행 영향 | 10~60 중 실패 | BatchJobRunner | FAILED | 앞 단계 FAILED | D 후보 없음 -> 그날 피드 후보 없음, 10분 예산 갱신도 할 일 없음, 쿠폰 준비도 영향 | D |
| B-151 | 끝난 찜 정리 | 80 ADSERVING_ENDED_WISHLIST_CLEANUP | WishlistService.deleteExpiredEndedWishes | - | campaign_ended_at < (D-3) 00:00 | 해당 찜 삭제, 그 이후 끝난 찜은 유지 | D, 예: 10/5 실행 -> 10/1 까지 끝난 찜 삭제 |
| B-152 | 찜 정리 재실행 | 80 단계 재실행 | WishlistService | - | 이미 정리됨 | 지울 행 없음, 결과 동일 | 같은 D |
| B-153 | 가게 반경 대상 인원 | 90 ADSERVING_STORE_AUDIENCE | StoreAudienceService.aggregate | - | 서빙 대상 캠페인의 가게, D-7~D-1 feed_visit 위치, 회원 성별/연령대 | 칸 계산 후 StoreAudienceUpdater.overwrite 로 덮어쓰기 | 접속 D-7~D-1 |
| B-154 | 대상 가게 없음 | 90 단계 | StoreAudienceService | - | 서빙 대상 0건 | 0 반환, SUCCESS | D |
| B-155 | 프로필 없는 회원 제외 | 90 단계 | StoreAudienceService.readProfiles | - | gender 또는 ageGroup null | 세지 않음 | D |
| B-156 | 대상 인원 중간 실패 | 90 단계 예외 | StoreAudienceUpdater | FAILED | 일부 가게만 덮어씀 | 앞 가게만 오늘 값, 나머지는 전날 값, 단계 FAILED 후 재실행으로 덮어쓰기 | D |
| B-157 | SCHEDULED 캠페인 자동 시작 | 20 CAMPAIGN_START_SCHEDULED | CampaignDailyTransitionService.startScheduled -> CampaignStatusService.activate(id, D) | - | SCHEDULED, startDate <= D <= endDate(전날 묶음이 빠져 startDate < D 인 것도 포함) | 캠페인마다 행 잠금 트랜잭션: ACTIVE, status_log(SCHEDULED -> ACTIVE, SYSTEM), store_campaign_summary(D, ACTIVE) upsert. CampaignStatusChangedEvent 는 내지 않음. 같은 묶음 50 예약, 75 적재, 03:00 COUPON_PREPARE 대상이 됨 | startDate<=D |
| B-158 | 기간 끝난 캠페인 자동 종료 | 10 CAMPAIGN_END_EXPIRED | CampaignDailyTransitionService.endExpired -> CampaignStatusService.end(id, SYSTEM, D) | - | ACTIVE, endDate < D | 캠페인마다 행 잠금 트랜잭션: ENDED, paused_reason/pending_daily_budget 비움, status_log(SYSTEM), store_campaign_summary(D, ENDED) upsert, CampaignStatusChangedEvent(servable=false) + CampaignEndedEvent 발행 | endDate < D |
| B-402 | 중단 캠페인도 기간 끝나면 종료 | 10 단계 | CampaignRepository.findIdsToEnd | - | PAUSED(OWNER/ADMIN/NO_POINTS), endDate < D | ACTIVE 와 같이 ENDED, paused_reason 비움 | endDate < D |
| B-403 | 캠페인 단계 할 일 없음 | 10/20/35 단계 | endExpired, startScheduled, applyPendingDailyBudgets | - | 기간 끝난 ACTIVE/PAUSED, 시작일 된 SCHEDULED, 예약값 있는 ACTIVE 모두 없음(endDate=D 인 마지막 날 캠페인은 종료 대상 아님) | 0 반환, 단계 SUCCESS, 다음 단계 진행 | D |
| B-404 | 종료 연쇄 반영 | 10 단계 end 의 동기 리스너(같은 트랜잭션) | CampaignEventListener, CampaignEndedListener, CandidateStatusChangedListener, CampaignStatusChangedListener | - | 끝나는 캠페인의 지난 coupon_event, 찜 | 그 캠페인 coupon_event 전부 ENDED, wishlist campaign_ended_at=종료 시각(80 단계는 3일 지난 것만 삭제), D 후보와 D 계획이 아직 없어 adserving_status_candidate_missing_total 증가, 계획 무변경 | D 00:00 |
| B-405 | 종료 데이터 오류 건너뜀 | 10 단계 | CampaignDailyTransitionService.endExpired | CAMPAIGN_DAILY_END_SKIPPED 로그 | MENU 할인인데 target_menu_id 가 그 가게 메뉴 아님(요약 갱신이 IllegalArgumentException), 또는 캠페인 행 없음 | 그 캠페인 트랜잭션 롤백, ACTIVE/PAUSED 로 남음, ERROR 로그 reason=DATA_ERROR, 단계 SUCCESS, 다음 00:00 에 다시 시도 | D |
| B-406 | 종료 개별 실패 | 10 단계 | CampaignDailyTransitionService.failIfAny | FAILED | 한 캠페인 전이 중 RuntimeException(DB 오류 등) | CAMPAIGN_DAILY_END_FAILED ERROR, 나머지 캠페인은 처리, 끝에 IllegalStateException "campaign daily transition failed: step=END_EXPIRED ..." 으로 단계 FAILED. 원인 없는 IllegalStateException 이라 자동 재실행 안 함, 묶음 FAILED, 20 이후 미실행 | D |
| B-407 | 종료 재실행 멱등 | 10 단계 재실행(:retry, 이어받기) | findIdsToEnd, CampaignStatusService.end | - | 앞 실행이 일부 캠페인을 ENDED 로 바꿈 | ENDED 는 조회에서 빠지고 end 도 ENDED 면 무변경, 남은 캠페인만 종료 | 같은 D |
| B-408 | 시작 시 같은 가게 기존 ACTIVE 종료 | 20 단계 | CampaignRepository.findOtherActiveIdsByStoreId -> end(SYSTEM) -> activate | - | 같은 가게에 다른 ACTIVE 캠페인 + 시작일 된 SCHEDULED | 기존 ACTIVE 를 먼저 ENDED(B-158 과 같은 연쇄), 그 뒤 새 캠페인 ACTIVE. 두 전이는 다른 트랜잭션. 같은 가게 PAUSED 캠페인은 끝내지 않음 | startDate<=D |
| B-409 | 기간 지난 SCHEDULED 미시작 | 10/20 단계 | findToStart, findIdsToEnd | - | SCHEDULED, endDate < D | 시작 대상(endDate >= D 조건)도 종료 대상(ACTIVE/PAUSED 만)도 아니어서 SCHEDULED 로 계속 남음 | endDate < D |
| B-410 | 시작 데이터 오류 건너뜀 | 20 단계 | CampaignDailyTransitionService.startScheduled | CAMPAIGN_DAILY_START_SKIPPED 로그 | MENU 할인 메뉴가 그 가게 메뉴 아님 | activate 트랜잭션 롤백, SCHEDULED 유지, ERROR reason=DATA_ERROR, 단계 SUCCESS. 앞서 끝낸 같은 가게 ACTIVE 는 ENDED 로 남아 그 가게 진행 캠페인 없음, 다음 00:00 에 다시 시도 | D |
| B-411 | 시작 개별 실패 | 20 단계 | CampaignDailyTransitionService.failIfAny | FAILED | 전이 중 DB 오류, 또는 조회 뒤 점주 취소가 먼저 커밋돼 activate 가 DRAFT 를 보고 IllegalStateException | CAMPAIGN_DAILY_START_FAILED ERROR, 나머지 처리 후 IllegalStateException step=START_SCHEDULED 로 단계 FAILED, 묶음 FAILED, 30 이후 미실행. 기존 ACTIVE 만 끝난 채로 남을 수 있음 | D |
| B-412 | 시작 재실행 멱등 | 20 단계 재실행 | findToStart, CampaignStatusService.activate | - | 앞 실행이 일부를 ACTIVE 로 바꿈, 또는 기존 ACTIVE 만 끝나고 실패 | 이미 ACTIVE 는 조회에서 빠지고 activate 도 null 반환(로그 없음), 남은 SCHEDULED 만 시작, 취소돼 DRAFT 가 된 것은 대상 아님 | 같은 D |
| B-413 | 가게 요약 새 영업일 생성 | 30 CAMPAIGN_STORE_SUMMARY | CampaignStoreSummaryService.prepare -> StoreCampaignSummaryUpdater.updateAll | - | ACTIVE, endDate >= D(10, 20 단계 반영 뒤) | 한 트랜잭션에서 id 순 500건씩 store_campaign_summary(D, 캠페인) upsert, 방금 시작한 캠페인 포함, 방금 끝난 캠페인 제외 | D 00:00 |
| B-414 | 중단 캠페인 요약 미생성 | 30 단계 | CampaignRepository.findActiveSummaryRowsAfter | - | PAUSED(OWNER/ADMIN/NO_POINTS) | D 요약 행 만들지 않음(전날 행만 남음), 재개 때 CampaignStatusService.resume 이 그날 요약을 씀 | D |
| B-415 | 요약 데이터 오류로 단계 실패 | 30 단계 | StoreCampaignSummaryUpdaterImpl.updateAll | FAILED | ACTIVE 캠페인 1건이 MENU 할인 메뉴 불일치 또는 할인 값/사용 시간 검증 실패 | IllegalArgumentException, 앞 청크까지 전부 롤백, 단계 FAILED, 묶음 FAILED, 35 이후(정산, 후보 적재) 미실행. 10, 20 단계와 달리 한 건만 건너뛰지 않음 | D |
| B-416 | 요약 재실행 멱등 | 30 단계 재실행 | store_campaign_summary upsert | - | 같은 D 요약이 이미 있음 | (영업일, 캠페인) 기준 덮어쓰기, 중복 행 없음 | 같은 D |
| B-417 | 하루 예산 변경 적용 | 35 CAMPAIGN_APPLY_PENDING_BUDGET | CampaignDailyTransitionService.applyPendingDailyBudgets -> CampaignStatusService.applyPendingDailyBudget | - | ACTIVE, pending_daily_budget 있음(PATCH /v1/owner/campaigns/{campaignId} 로 예약) | 행 잠금 후 daily_budget=예약값, pending_daily_budget 비움, 같은 묶음 50 단계가 새 하루 예산으로 RESERVE | D 00:00 |
| B-418 | 중단 캠페인 예약값 유지 | 35 단계 | findIdsWithPendingDailyBudget, Campaign.applyPendingDailyBudget | - | PAUSED 이고 pending_daily_budget 있음(목록 조회 뒤 PAUSED/ENDED 로 바뀐 것 포함) | 적용 안 함, 예약값 유지, 재개 뒤 다음 00:00 에 적용(ENDED 는 종료 때 예약값 삭제) | D |
| B-419 | 예산 적용 개별 실패 | 35 단계 | CampaignDailyTransitionService.failIfAny | FAILED | 적용 중 RuntimeException(캠페인 행 없음 포함, 이 단계는 데이터 오류도 건너뛰지 않음) | CAMPAIGN_DAILY_BUDGET_APPLY_FAILED ERROR, 나머지 적용 후 IllegalStateException step=APPLY_PENDING_BUDGET 으로 단계 FAILED, 묶음 FAILED, 40 이후 미실행 | D |
| B-420 | 예산 적용 재실행 멱등 | 35 단계 재실행 | Campaign.applyPendingDailyBudget | - | 앞 실행이 일부 적용함 | 적용된 캠페인은 예약값이 없어 조회에서 빠짐, 남은 것만 적용 | 같은 D |
| B-421 | 후보 적재 끝 예산 반영 | 75 단계 마지막 | LoadDailyCandidateJob.run -> CandidateBudgetService.refresh(D) | - | D 후보 적재됨, D 계획 있음 | ad_candidate reserved_amount, unit_price 를 정산 값으로 채움, 계획 없는 후보는 0. 정산 조회 실패면 ADSERVING_BUDGET_REFRESH_FAILED WARN, adserving_budget_unavailable_total 증가, 값 유지, 단계 SUCCESS(10분 작업이 다시 채움) | D 00:00 |

### 01:00 집계

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-159 | 정기 집계 정상 | cron 0 0 1 (Asia/Seoul) | DailyAggregationJob.run -> DailyAggregationRunner.runAll | SUCCEEDED | (BILLING_DAILY_RELEASE, T-1) SUCCESS | 날짜별 (DAILY_AGGREGATION, 날짜) claim 후 단계 100~900 collect, 한 트랜잭션에서 publish + succeed(행 수) + pending 삭제 | 실행일 T, 대상 T-1, T-2, pending |
| B-160 | 대상 날짜 구성 | runAll targetDates | DailyAggregationRunner.targetDates | - | aggregation_pending_date 에 과거 날짜, 또는 원천 확정(BILLING_DAILY_RELEASE SUCCESS)인데 집계(DAILY_AGGREGATION)가 SUCCESS 가 아닌 과거 날짜 | 오래된 날짜부터 pending + 미집계 확정 날짜 + T-1 + T-2, T 이후와 13개월 밖 제외 | T 01:00 |
| B-161 | 보존 기간 밖 pending 삭제 | targetDates | pending.removeBefore | - | pending 날짜 < T-13개월 | pending 에서 삭제, 집계 안 함 | T |
| B-162 | 원천 미확정 | runDate | DailyAggregationRunner.sourceConfirmed | SOURCE_NOT_READY | (BILLING_DAILY_RELEASE, 날짜) SUCCESS 없음(T 00:00 묶음 40/50 실패 등) | pending(SOURCE_NOT_READY) 기록, 실행 행 안 만듦, 다음 01:00 재시도 | 날짜=T-1 |
| B-163 | 다른 서버가 집계 중 | acquire | DailyAggregationRunner.acquire | BUSY | (DAILY_AGGREGATION, 날짜) RUNNING | 건너뜀 | T 01:00 |
| B-164 | 이미 성공한 날짜 재집계 | acquire SUCCESS | BatchRerunner.rerun(restartFinished) | SUCCEEDED | T-2 가 어제 SUCCESS | RUNNING 으로 되돌려(attemptNo+1) 다시 집계해 덮어씀 | 날짜=T-2 |
| B-165 | 실패한 날짜 재집계 | acquire FAILED | BatchExecutionService.retry(restartFailed) | SUCCEEDED 또는 FAILED | pending(FAILED) 날짜 | RUNNING 으로 되돌려 다시 집계 | pending 날짜 |
| B-166 | 일시적 오류 재시도 | attemptOnce 예외(교착, 락 대기, 연결, Timeout) | shouldRetry -> recordRetry -> backoff | - | retry_count < 2 | 30초, 1분 대기 후 모으기부터 재시작(대기 중 커넥션 미보유) | T 01:00 |
| B-167 | 재시도 소진 | 3번째 일시적 실패 | DailyAggregationRunner.fail | FAILED | retry_count = 2 | 행 FAILED(TRANSIENT_EXHAUSTED 로그), pending(FAILED) | T |
| B-168 | 비일시적 오류 | collect/publish 예외 | DailyAggregationRunner.fail | FAILED | 데이터/제약 오류 | 바로 FAILED(SYSTEM_ERROR), pending(FAILED), 다른 날짜는 계속 | T |
| B-169 | 집계 중 임대 상실 | 단계마다 leaseHeld | DailyAggregationRunner.leaseHeld | LEASE_LOST | 다른 서버가 이어받음 | 공개 없이 중단, 결과는 이어받은 쪽 판정 | 실행 중 |
| B-170 | 세대 불일치 | inAttempt assertCurrentAttempt | BatchAttemptGuard | LEASE_LOST | 행 attemptNo 또는 소유자 변경 | 공개 트랜잭션 롤백, 아무것도 쓰지 않음 | 실행 중 |
| B-171 | 예상 못한 예외 | runGuarded | DailyAggregationRunner | FAILED | runDate 자체 예외 | ERROR 로그, pending(FAILED), 다음 날짜 진행 | T |
| B-172 | 세대 읽기 실패 | acquire 직후 재조회 실패 | DailyAggregationRunner.abandon | FAILED | 점유 성공 후 조회 예외 | 행 FAILED, pending(FAILED) | T |
| B-173 | 정정 날짜 기록 | 100 SettlementMetricsStep | rememberCorrectionDates | ADJUSTED | 그날 원장에 다른 영업일 대상 정정(ADJUST) | 그 날짜를 pending(ADJUSTED)로 남겨 다음 01:00 재집계(자기 날짜, 오늘 이후, 보존 밖 제외) | T |
| B-174 | 정산 정합성 위반 | 900 SettlementConsistencyStep | V-1, V-2, V-3, V-7 검사 | - | BILLED x 단가 != DEDUCT 등 | 집계 실패시키지 않고 analytics_consistency_violations{check} 지표와 로그만 | T |
| B-175 | 이어받은 집계 | takeOverStale -> resume(STANDALONE) | DailyAggregationJob.run(date, attemptNo) -> runner.aggregate | SUCCEEDED 등 | (DAILY_AGGREGATION, 날짜) RUNNING, 2분 넘게 갱신 없음 | 넘겨받은 세대로 그 날짜만 집계, 집계가 행을 건수와 함께 닫고 BatchJobRunner 의 succeedAttempt 는 무시됨 | 1분 주기 스캔 |

### 01:30 PG 대조

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-176 | PG 대조 정상(일치) | cron 0 30 1 (Asia/Seoul) -> claim(BILLING_PG_VS_CHARGE_RECON, D) | PgChargeReconciliationScheduler.reconcile | - | D-1 생성, pg_tid 있는 결제, PG APPROVED 금액 = 우리 APPROVED 금액, CHARGE 원장 있음 | 기록 없음, 행 SUCCESS | 실행일 D, 대상 [D-1 00:00, min(D 00:00, now-5분)) |
| B-177 | 대상 결제 없음 | 01:30 | PgChargeReconciliationService | - | D-1 결제 0건 또는 모두 pg_tid 없음 | 0 반환, SUCCESS | D 01:30 |
| B-178 | 다른 서버가 점유 | 01:30 claim 실패 | PgChargeReconciliationScheduler | - | 같은 행 RUNNING/SUCCESS/FAILED 존재 | BILLING_PG_RECON_SKIPPED reason=ALREADY_CLAIMED, 실행 안 함 | D 01:30 |
| B-179 | 빠진 CHARGE 자동 복구 | 01:30 | PgChargeMismatchRecorder.recordMissingCharge | PG_VS_CHARGE, LEDGER_CORRECTION | 양쪽 APPROVED 같은 금액, 원장 CHARGE 없음 | CHARGE(source=RECONCILIATION) 기록 + 해결된(LEDGER_CORRECTION) 불일치 행, recovered 지표 | D-1 |
| B-180 | 우리만 승인 | 01:30 | recordMismatch | PG_VS_CHARGE | payment APPROVED, PG CANCELED 또는 FAILED | 미해결 불일치(expected=우리 금액, actual=0), 자동 복구 없음 | D-1 |
| B-181 | PG 만 승인 | 01:30 | recordMismatch | PG_VS_CHARGE | payment UNKNOWN/REQUESTED/FAILED/CANCELLED, PG APPROVED | 미해결 불일치(expected=0, actual=PG 금액) | D-1 |
| B-182 | 금액 불일치 | 01:30 | recordMismatch | PG_VS_CHARGE | 양쪽 APPROVED, 금액 다름 | 미해결 불일치 | D-1 |
| B-183 | 양쪽 미승인 | 01:30 | reconcileOne | - | payment 미승인, PG CANCELED/FAILED | 0 = 0 이라 기록 없음 | D-1 |
| B-184 | PG PENDING | 01:30 | reconcileOne | - | PG 조회 결과 PENDING | 재검증 대기 행(pg_charge_reconciliation_pending)으로 남기고 다음 실행이 다시 조회 | D-1 |
| B-185 | PG 조회 실패 | 01:30 inquireWithRetry | PaymentGateway.inquireByPaymentKey | - | PgNotSentException/PgUnknownException 2회 | 1회 재시도 후 포기, inquiry_failed 지표, 불일치로 기록 안 함, 재검증 대기 행으로 남김 | D-1 |
| B-186 | 건별 예외(기록 실패, PG 응답 주문번호 불일치) | 01:30 | PgChargeReconciliationService | - | 한 결제 기록 중 RuntimeException, 또는 PG 응답의 주문번호가 결제와 다름 | error 지표와 ERROR 로그 후 그 결제를 재검증 대기 행으로 남기고 다음 결제 계속, 보정하지 않음, 작업은 SUCCESS | D-1 |
| B-187 | 시각 직전 결제 | 01:30 | to = D 00:00 | - | D-1 23:55 이후 생성된 결제 | 워터마크 없이 D-1 전체를 조회, PG 상태가 확정 전이면 재검증 대기 | D |
| B-188 | 대조 재실행 멱등 | 이어받기/수동 재실행 | uk_point_ledger_payment_entry, insert...IfAbsent | - | 이미 보정/기록된 결제 | 중복 CHARGE, 중복 불일치 없음 | 같은 D |
| B-189 | 정기 실행 중 예외 | reconcile 예외 | executions.fail | FAILED | 결제 목록 조회 등 서비스 전체 예외 | 자동 재시도 없이 바로 FAILED + 알림, 예외 재던짐 | D 01:30 |
| B-190 | 이어받은 PG 대조 | takeOverStale -> resume(STANDALONE) | PgChargeReconciliationScheduler.run | - | 행 RUNNING, 2분 넘게 갱신 없음 | 다른 서버가 다시 돌림(일시적 오류면 자동 재시도 2회), 세대 맞을 때만 닫음 | 1분 주기 |

### 02:00

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-191 | 광고 로그 보존 정상 삭제 | cron 0 0 2 (Asia/Seoul), claim(ADSERVING_LOG_RETENTION, D) 성공 | ADSERVING_LOG_RETENTION | - | feed_visit_daily business_date < D-7, swipe_log < D-30, serve_log와 impression_log < D-13개월 행 존재 | 네 테이블을 1,000행씩 따로 커밋해 삭제, 합계를 processed_count 로 SUCCESS | 매일 02:00 |
| B-192 | 광고 로그 보존 경계 유지 | 같은 실행 | ADSERVING_LOG_RETENTION | - | feed_visit_daily D-7, swipe_log D-30, serve_log/impression_log D-13개월 같은 날 행 | 경계 날짜 행은 남음(strict <) | 02:00 |
| B-193 | 광고 로그 삭제 대상 없음 | 같은 실행 | ADSERVING_LOG_RETENTION | - | 기준보다 오래된 행 없음 | 삭제 0건, SUCCESS processed_count=0 | 02:00 |
| B-194 | 광고 로그 같은 날 재실행 | 두 번째 서버 또는 재기동 후 같은 D 로 cron | ADSERVING_LOG_RETENTION | ALREADY_CLAIMED 로그 | (ADSERVING_LOG_RETENTION, D) 행이 RUNNING(남의 것)/SUCCESS/FAILED | claim false -> 건너뜀, 행 변화 없음 | 02:00 |
| B-195 | 광고 로그 삭제 중 DB 오류 | 중간 테이블에서 예외 | ADSERVING_LOG_RETENTION | FAILED(failure_reason 500자) | 삭제 중 DB 오류 | 남은 테이블 건너뜀, 이미 커밋한 청크는 유지, FAILED 기록 후 알림, 예외 재던짐(스케줄러 경로 자동 재시도 없음) | 02:00 |
| B-196 | 광고 로그 실패 후 수동 재실행 | POST /v1/admin/batch-executions/ADSERVING_LOG_RETENTION/{D}:retry | ADSERVING_LOG_RETENTION | - | 행 FAILED | RUNNING 으로 되돌린 뒤 runCounting -> 남은 행만 삭제, 건수로 SUCCESS | 아무 때나 |
| B-197 | 쿠폰 정합성 검증 정상(불일치 없음) | cron 0 0 2, claim(COUPON_VERIFY, D-1) | COUPON_VERIFY | - | 어제 영업일 coupon_event, coupon_inventory, member_coupon 모두 일치 | REPEATABLE_READ 한 스냅샷에서 조회, 원본 수정 없음, SUCCESS processed_count=0 | 02:00, 대상일은 D-1 |
| B-198 | 검증 INVENTORY_COUNT | 같은 실행 | COUPON_VERIFY | INVENTORY_COUNT | 회차 issue_quantity <> 재고 행 수 | COUPON_CONSISTENCY_MISMATCH ERROR 로그, 불일치 수 가산 | 02:00 |
| B-199 | 검증 INVENTORY_FIRST / INVENTORY_LAST | 같은 실행 | COUPON_VERIFY | INVENTORY_FIRST, INVENTORY_LAST | 재고 sequence_no 최소가 1 아님 또는 최대가 issue_quantity 아님 | 각 회차 ERROR 로그 | 02:00 |
| B-200 | 검증 ISSUED_INVENTORY | 같은 실행 | COUPON_VERIFY | ISSUED_INVENTORY | 재고 ISSUED 인데 같은 coupon_code 회원 쿠폰 없음 또는 회차 다름 | ERROR 로그 | 02:00 |
| B-201 | 검증 ISSUED_COUPON | 같은 실행 | COUPON_VERIFY | ISSUED_COUPON | 회원 쿠폰의 재고가 없거나 ISSUED 아님 또는 회차 다름 | ERROR 로그 | 02:00 |
| B-202 | 검증 DAILY_COUNTER | 같은 실행 | COUPON_VERIFY | DAILY_COUNTER | coupon_daily_limit(D-1).issued_count <> D-1 created_at 기준 REMOVED 제외 발급 수, 또는 한도 행 없음 | ERROR 로그 | 02:00 |
| B-203 | 검증 USAGE_HISTORY / USAGE_STORE | 같은 실행 | COUPON_VERIFY | USAGE_HISTORY, USAGE_STORE | USED 인데 사용 이력 수 <> 1, 미사용인데 이력 있음, 이력 store_id <> 회차 store_id | ERROR 로그 | 02:00 |
| B-204 | 검증 COUPON_START_TIME / INVENTORY_TIME | 같은 실행 | COUPON_VERIFY | COUPON_START_TIME, INVENTORY_TIME | 회원 쿠폰 또는 재고의 usable_start_at/usable_end_at 이 회차와 다름 | ERROR 로그 | 02:00 |
| B-205 | 검증 STORE_EVENT_COUNT | 같은 실행 | COUPON_VERIFY | STORE_EVENT_COUNT | 같은 영업일 한 매장에 회차 2개 이상 | ERROR 로그 | 02:00 |
| B-206 | 검증 EXPIRATION_STATUS | 같은 실행 | COUPON_VERIFY | EXPIRATION_STATUS | ISSUED 인데 usable_end_at <= 검사 시각, 또는 EXPIRED 인데 usable_end_at > 검사 시각 | ERROR 로그 | 02:00 |
| B-207 | 검증 불일치 1,000건 초과 | 같은 실행 | COUPON_VERIFY | - | 불일치 1,000건 이상 | (유형, 대상 ID) 커서로 페이지를 이어 읽어 중복 없이 모두 출력, SUMMARY 로그, processed_count=불일치 수, 상태는 SUCCESS | 02:00 |
| B-208 | 검증 일시 실패 후 자동 재시도 | 조회 중 예외 | COUPON_VERIFY | retry_count 1~2 | DB 오류 | 모든 RuntimeException 을 30초, 1분 뒤 최대 2회 재시도, 성공하면 SUCCESS | 02:00 이후 |
| B-209 | 검증 재시도 소진 | 3회 연속 예외 | COUPON_VERIFY | FAILED | 계속 오류 | failAttempt 로 FAILED 후 알림, COUPON_BATCH_FAILED 로그, 예외는 밖으로 안 던짐 | 02:00 이후 |

### 03:00

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-210 | 카카오 unlink 재시도 성공 | cron 0 0 3, claim(MEMBER_KAKAO_UNLINK_RETRY, D) | MEMBER_KAKAO_UNLINK_RETRY | - | kakao_unlink_failure resolved=false, processing=false, 회원 WITHDRAWN | 행 잠금 후 processing=true, 카카오 unlink 성공 -> 행 삭제, KAKAO_UNLINK_RETRY_SUCCESS, 작업 SUCCESS | 매일 03:00 |
| B-211 | unlink 대상 없음 | 같은 실행 | MEMBER_KAKAO_UNLINK_RETRY | - | 미해결 행 없음 | 호출 없음, SUCCESS | 03:00 |
| B-212 | unlink 일시 실패(횟수 증가) | unlink 가 5xx, 429, 401, 403, 타임아웃 | MEMBER_KAKAO_UNLINK_RETRY | KAKAO_UNLINK_RETRY_FAILED | attempt_count 0~3 | processing=false, attempt_count+1, 다음 날 다시 대상 | 03:00 |
| B-213 | unlink 한도 소진 EXHAUSTED | 일시 실패로 attempt_count 가 5 도달 | MEMBER_KAKAO_UNLINK_RETRY | EXHAUSTED | attempt_count=4 | resolved=true, stop_reason=EXHAUSTED, ERROR 로그, 이후 후보에서 빠짐 | 03:00 |
| B-214 | unlink 영구 거부 REJECTED | 카카오 400 또는 404 | MEMBER_KAKAO_UNLINK_RETRY | REJECTED | 미해결 행 | attempt_count 유지, resolved=true, stop_reason=REJECTED | 03:00 |
| B-215 | 서킷 열림 | CallNotPermittedException | MEMBER_KAKAO_UNLINK_RETRY | CIRCUIT_OPEN 로그 | 카카오 서킷 OPEN | processing 해제만, attempt_count 유지, 다음 날 재시도 | 03:00 |
| B-216 | 재가입으로 재활성된 회원 | 호출 직전 재확인 | MEMBER_KAKAO_UNLINK_RETRY | DISCARDED_REACTIVATED 로그 | 회원 상태가 WITHDRAWN 아님 | 카카오 호출 없이 행 삭제 | 03:00 |
| B-217 | 이미 선점 또는 해결된 행 | 재확인 | MEMBER_KAKAO_UNLINK_RETRY | - | resolved=true 또는 processing=true | 건너뜀 | 03:00 |
| B-218 | 성공 후 행 삭제 실패 | markSucceeded DB 오류 | MEMBER_KAKAO_UNLINK_RETRY | KAKAO_UNLINK_RETRY_CLEANUP_FAILED | unlink 성공 | 행이 processing=true 로 남아 자동 재호출 안 됨(운영 해제 필요), 다음 행 계속, 작업 SUCCESS | 03:00 |
| B-219 | 실패 기록 자체 실패 | markFailed DB 오류 | MEMBER_KAKAO_UNLINK_RETRY | KAKAO_UNLINK_RETRY_MARK_FAILED | 호출 실패 | 그 행만 멈추고 다음 행 계속, processing 남음 | 03:00 |
| B-220 | 한 번에 100건 상한 | 같은 실행 | MEMBER_KAKAO_UNLINK_RETRY | - | 미해결 행 100건 초과 | created_at 오래된 순 100건만 처리, 나머지는 다음 날 | 03:00 |
| B-221 | unlink 같은 날 재실행 | 같은 D 로 다시 cron | MEMBER_KAKAO_UNLINK_RETRY | ALREADY_CLAIMED 로그 | 행 존재 | 건너뜀 | 03:00 |
| B-222 | unlink 후보 조회 실패 | loadPending 예외 | MEMBER_KAKAO_UNLINK_RETRY | FAILED | DB 오류 | FAILED 후 알림, 예외 재던짐, 자동 재시도 없음(수동 :retry) | 03:00 |
| B-223 | 쿠폰 회차 준비 정상 | cron 0 0 3, claim(COUPON_PREPARE, D) | COUPON_PREPARE | - | campaign start_date <= D <= end_date, ACTIVE 또는 PAUSED(OWNER/NO_POINTS) | 캠페인마다 한 트랜잭션: 캠페인 FOR UPDATE, coupon_event(D) 생성, 재고 issue_quantity 개 AVAILABLE(sequence 1..N, UUID 코드) 생성, processed_count=생성 회차 수 | 매일 03:00 |
| B-224 | 준비 회차 상태 ACTIVE / PAUSED | 같은 실행 | COUPON_PREPARE | - | 잠근 캠페인 ACTIVE / PAUSED | 회차 status ACTIVE / PAUSED, statusChangedAt=캠페인 값 | 03:00 |
| B-225 | 준비 MENU 할인 스냅샷 | 같은 실행 | COUPON_PREPARE | - | discount_target_type=MENU, 메뉴가 같은 매장 | 메뉴명, 가격, 할인가 스냅샷 저장 | 03:00 |
| B-226 | 준비 건너뜀(이미 회차 있음) | 재시도 또는 재실행 | COUPON_PREPARE | - | (campaign, D) 회차 존재 | 0 반환, 중복 생성 없음 | 03:00 |
| B-227 | 준비 건너뜀(상태 변경) | 잠금 후 재확인 | COUPON_PREPARE | - | 조회 뒤 ENDED/CANCELLED 등으로 바뀜 또는 기간 밖 | 0 반환 | 03:00 |
| B-228 | 준비 대상 제외 PAUSED(ADMIN) | 후보 조회 | COUPON_PREPARE | - | PAUSED, paused_reason=ADMIN | findServableOn 에서 빠져 회차 없음 | 03:00 |
| B-229 | 준비 대상 없음 | 같은 실행 | COUPON_PREPARE | - | 기간 안 캠페인 없음 | SUCCESS processed_count=0 | 03:00 |
| B-230 | 준비 데이터 오류 | 매장 없음, 메뉴 없음, 메뉴 매장 불일치, 스냅샷 검증 실패 | COUPON_PREPARE | IllegalStateException/IllegalArgumentException | 잘못된 캠페인 1건 | 그 캠페인 트랜잭션 롤백, 앞 회차는 커밋 유지, 30초/1분 뒤 전체 재시도 2회(완료 회차 건너뜀), 계속 실패하면 FAILED | 03:00 |
| B-231 | 준비 후보 10,000건 초과 | 후보 조회 | COUPON_PREPARE | IllegalStateException | servable 캠페인 10,001건 이상 | 재시도 후 FAILED | 03:00 |
| B-232 | 준비 세대 불일치 | 다른 서버가 이어받은 뒤 낡은 시도 | COUPON_PREPARE | attempt no longer current | owner 또는 attempt_no 변경 | assertCurrentAttempt 예외로 그 트랜잭션 롤백, 낡은 시도는 행을 닫지 못함 | 03:00 |

### 04:00

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-233 | 이벤트 로그 보존 정상 삭제 | cron 0 0 4, claim(EVENT_LOG_RETENTION, D) | EVENT_LOG_RETENTION | - | event_log occurred_at < (D - 13개월) 00:00 | 1,000행씩 커밋, 청크 사이 100ms 대기, 합계로 SUCCESS | 매일 04:00 |
| B-234 | 이벤트 로그 대상 없음 | 같은 실행 | EVENT_LOG_RETENTION | - | 기준 이전 행 없음 | SUCCESS processed_count=0 | 04:00 |
| B-235 | 이벤트 로그 경계 | 같은 실행 | EVENT_LOG_RETENTION | - | occurred_at = 기준 시각 정확히 | 남음(strict <) | 04:00 |
| B-236 | 이벤트 로그 중단 | 청크 대기 중 인터럽트 | EVENT_LOG_RETENTION | FAILED | 대량 행 | IllegalStateException -> FAILED, 지운 청크는 유지, 다음 실행이 이어서 삭제 | 04:00 |
| B-237 | 이벤트 로그 같은 날 재실행 | 같은 D cron | EVENT_LOG_RETENTION | ALREADY_CLAIMED 로그 | 행 존재 | 건너뜀 | 04:00 |
| B-238 | 템플릿 버전 하드 삭제 | cron 0 0 4, claim(CAMPAIGN_TEMPLATE_VERSION_PURGE, D) | CAMPAIGN_TEMPLATE_VERSION_PURGE | - | template_version deleted_at IS NOT NULL(게시 시 전체 소프트삭제, 21번째 버전 생성 시 newest-20 이하 소프트삭제) | 500행씩 커밋 삭제, 합계로 SUCCESS | 매일 04:00 |
| B-239 | 템플릿 버전 대상 없음 | 같은 실행 | CAMPAIGN_TEMPLATE_VERSION_PURGE | - | 소프트삭제 버전 없음 | SUCCESS processed_count=0, 살아 있는 버전은 그대로 | 04:00 |
| B-240 | 템플릿 버전 삭제 실패 | DB 오류 | CAMPAIGN_TEMPLATE_VERSION_PURGE | FAILED | 삭제 중 오류 | 앞 청크 유지, FAILED 후 알림, 예외 재던짐, 수동 :retry 로 남은 행만 삭제 | 04:00 |
| B-241 | 템플릿 버전 같은 날 재실행 | 같은 D cron | CAMPAIGN_TEMPLATE_VERSION_PURGE | ALREADY_CLAIMED 로그 | 행 존재 | 건너뜀 | 04:00 |

### 쿠폰 시각 작업

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-242 | 발급 오픈 이벤트 발행 | cron 0 0 11, claim(COUPON_OPEN, D) | COUPON_OPEN | - | coupon_event(D) status=ACTIVE | 회차마다 잠금 후 CouponIssueOpenedEvent(id "coupon-issue-opened:{id}", 발생 D 11:00) 발행 -> 분석 ISSUE_OPEN 기록, processed_count=발행 수. 회차 상태는 바꾸지 않음 | 11:00 <= now < 14:00 |
| B-243 | 오픈 건너뜀(비활성 회차) | 같은 실행 | COUPON_OPEN | - | 회차 PAUSED/SCHEDULED/ENDED | 이벤트 없음, 0 가산 | 11:00 |
| B-244 | 오픈 늦은 재실행 | 이어받기 또는 :retry 로 14:00 이후 또는 다른 날 실행 | COUPON_OPEN | - | 행 FAILED 후 재실행 | 모든 회차 0, SUCCESS processed_count=0(오픈 이벤트 누락) | now >= 14:00 또는 date <> 오늘 |
| B-245 | 오픈 재시도 중복 이벤트 | 일부 회차 처리 후 예외로 재시도 | COUPON_OPEN | retry_count | 회차 다수 | 같은 이벤트 ID, 발생 시각으로 재발행, 분석이 멱등키로 중복 제거 | 11:00~14:00 |
| B-246 | 발급 마감 기록 | cron 0 0 14, claim(COUPON_CLOSE, D) | COUPON_CLOSE | - | coupon_event(D) n건 | 상태 변경 없이 COUPON_ISSUE_CLOSED 로그, processed_count=회차 수 | now >= 14:00 |
| B-247 | 마감 회차 없음 | 같은 실행 | COUPON_CLOSE | - | 회차 0건 | SUCCESS processed_count=0 | 14:00 |
| B-248 | 마감 시각 전 실행 | 14:00 전 재실행 또는 미래 date | COUPON_CLOSE | IllegalStateException | - | 모든 오류 2회 재시도 후 FAILED | now < 14:00 |
| B-249 | 품절 판정(작업 아님) | POST /v1/coupon-events:getStatus | API 시각 판정 | - | AVAILABLE 재고 0 | 시각과 재고로 계산: now>=14:00 CLOSED, now<11:00 또는 회차 SCHEDULED 이면 SCHEDULED, 재고 0 SOLD_OUT, 그 외 OPEN. 오픈/마감 작업은 이 값을 바꾸지 않음 | 11:00, 14:00 경계 |
| B-250 | 쿠폰 만료 정상 | cron 0 */5, ISSUED 이고 usable_end_at <= now 인 회원 쿠폰의 영업일마다 | COUPON_EXPIRE | - | 해당 영업일 행 없음 | claim 후 쿠폰마다 잠금 재확인 -> EXPIRED, CouponExpiredEvent 발행(분석 EXPIRE), 쿠폰 단위 커밋, processed_count=만료 수 | 5분마다 |
| B-251 | 만료 대상 없음 | 같은 cron | COUPON_EXPIRE | - | 만료 대상 쿠폰 없음 | 영업일 목록 비어 실행 이력 생성 안 함 | 5분마다 |
| B-252 | 만료 같은 날 추가 대상 | 다음 5분 실행 | COUPON_EXPIRE | - | 이전 행 SUCCESS, 새 만료 대상 생김 | rerun 으로 SUCCESS 행을 RUNNING 재점유(attempt_no+1, retry_count 0), 남은 쿠폰만 처리 | 5분마다 |
| B-253 | 만료 과거 영업일 누락분 | 서버 중단 후 | COUPON_EXPIRE | - | 과거 영업일 ISSUED 쿠폰이 시간 지남 | 과거 날짜도 조회해 그 영업일 행으로 처리 | 5분마다 |
| B-254 | 만료 잠금 후 상태 변경 | 사용 확정과 경합 | COUPON_EXPIRE | - | 잠근 뒤 USED/REMOVED 이거나 종료 전 | 0, 변경 없음 | 5분마다 |
| B-255 | 만료 RUNNING 행 | 같은 cron | COUPON_EXPIRE | - | 그 영업일 행 RUNNING | 건너뜀, 공통 이어받기에 맡김 | 5분마다 |
| B-256 | 만료 FAILED 행 | 같은 cron | COUPON_EXPIRE | - | 그 영업일 행 FAILED | 자동 재점유 안 함, 수동 :retry 필요(그동안 그 날 쿠폰 ISSUED 유지) | 5분마다 |
| B-257 | 쿠폰 작업 공통 재시도 | 스케줄러 경로 예외 | COUPON_PREPARE/QUEUE_PREPARE/OPEN/CLOSE/EXPIRE/VERIFY | retry_count, FAILED | 모든 RuntimeException | 같은 attempt 로 30초, 1분 대기 후 최대 2회, DB retry_count 상한 공유, 대기 중 임대 잃으면 중단, 소진 시 failAttempt, 예외 삼킴 | 각 시각 |
| B-258 | 쿠폰 작업 같은 날 재실행 | 같은 D cron | COUPON_PREPARE/QUEUE_PREPARE/OPEN/CLOSE/VERIFY | - | 행 존재(SUCCESS/FAILED/남의 RUNNING) | claim false -> 아무것도 안 함 | 각 시각 |
| B-259 | 쿠폰 작업 이어받기 | 2분 넘게 갱신 없는 RUNNING | COUPON_* | - | 남의 RUNNING 행 | takeOver(attempt_no+1) 후 runCounting -> 내부 재시도(모든 오류)와 실행기 재시도(일시 오류) 같은 상한 | 1분 스캔 |
| B-422 | 발급 큐 미리 준비 | cron 0 55 10 (Asia/Seoul), claim(COUPON_QUEUE_PREPARE, D) | COUPON_QUEUE_PREPARE -> CouponQueuePreparationService.prepare | - | coupon_event(D) ACTIVE 또는 PAUSED, AVAILABLE + ISSUED 수 = issue_quantity, Valkey 큐 없음 | 회차 id 1,000건씩, 회차마다 트랜잭션: assertCurrentAttempt, 회차 잠금, AVAILABLE 순번과 ISSUED 수로 큐 생성(state READY, expiresAt=D 14:00+1h), processed_count=만든 회차 수. 11:00 첫 발급 요청이 복구를 기다리지 않음 | 10:55 |
| B-423 | 큐 준비 건너뜀 | 같은 실행 | CouponSequenceRecoveryService.prepare | - | 회차 SCHEDULED/ENDED, 또는 큐가 이미 READY/SOLD_OUT, 또는 expiresAt 지남(늦은 재실행) | 0 가산, 진행 중인 큐 덮어쓰지 않음 | 10:55 |
| B-424 | 큐 준비 회차 실패 | 같은 실행 | CouponQueuePreparationService.prepare | COUPON_QUEUE_PREPARE_FAILED 로그 | 재고 수가 issue_quantity 와 다름(IllegalStateException) 또는 Valkey 오류 | 그 회차 롤백, WARN, 0 가산, 다음 회차 계속, 작업 SUCCESS. 차단은 걸지 않아 첫 발급 요청의 recover 가 다시 만듦 | 10:55 |
| B-425 | 큐 준비 대상 없음 | 같은 실행 | COUPON_QUEUE_PREPARE | - | coupon_event(D) 0건 | SUCCESS processed_count=0 | 10:55 |
| B-426 | 큐 준비 세대 불일치 | 다른 서버가 이어받은 뒤 낡은 시도 | BatchAttemptGuard.assertCurrentAttempt | attempt no longer current | owner 또는 attempt_no 변경 | 회차 catch 밖의 예외라 남은 회차 처리 중단, 낡은 시도는 행을 닫지 못함 | 10:55 |

### 재조회 워커

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-260 | 재조회 승인 확정 | fixedDelay PT1M, outbox 선점 | PaymentRecheckWorker | - | payment UNKNOWN, outbox PENDING, next_retry_at <= now, PG 조회 APPROVED, orderId와 금액 일치 | payment APPROVED(승인 시각 없으면 now), point_account 없으면 생성, CHARGE 원장(REALTIME), outbox RESOLVED, PointCharged 커밋 후 재예약 | 1분 주기 |
| B-261 | 재조회 실패 확정 | PG 조회 FAILED | PaymentRecheckWorker | - | UNKNOWN | payment FAILED(사유 "코드: 메시지"), outbox RESOLVED, 충전 없음 | 1분 주기 |
| B-262 | 재조회 취소 확정 | PG 조회 EXPIRED 또는 CANCELED | PaymentRecheckWorker | - | UNKNOWN | payment CANCELLED, outbox RESOLVED, 충전 없음 | 1분 주기 |
| B-263 | 재조회 미확정 | PG 조회 PENDING, NOT_FOUND, 조회 예외(PgNotSent/PgUnknown) | PaymentRecheckWorker | MISS | outbox PENDING, attempt_count < 4 | payment UNKNOWN 유지, attempt_count+1, 다음 시각 = 2m,5m,10m,30m(이후 30m) 에 지터 ±10% | 1분 주기 |
| B-264 | 재조회 금액/주문 불일치 | APPROVED 인데 orderId 또는 amount 다름 | PaymentRecheckWorker | PAYMENT_INQUIRY_MISMATCH 로그 | UNKNOWN | 승인하지 않고 MISS 처리 | 1분 주기 |
| B-265 | 재조회 한도 소진 | MISS 로 attempt_count 가 5 도달 | PaymentRecheckWorker | FAILED_PERMANENT | attempt_count=4 | outbox FAILED_PERMANENT, PAYMENT_RECHECK_EXHAUSTED ERROR, 더 선점 안 함, 관리자 :recheck 로 확정 | 첫 1분 후 약 48분 |
| B-266 | 재조회 대상 없음 | 주기 실행 | PaymentRecheckWorker | - | 시각 된 PENDING 없음 | 0건, 로그 없음 | 1분 주기 |
| B-267 | 다른 경로가 먼저 확정 | 웹훅이나 관리자 재조회 | PaymentRecheckWorker | - | payment APPROVED/FAILED/CANCELLED | PG 호출 없이 현재 상태 반환, 결과 기록 메서드도 잠금 후 확정이면 무변경 | 1분 주기 |
| B-268 | 두 서버 동시 선점 | 두 배치 서버 | PaymentRecheckWorker | - | PENDING 다수 | FOR UPDATE SKIP LOCKED, 최대 20건, next_retry_at 을 now+5분으로 미뤄 중복 조회 없음. batch_execution_log 미사용 | 1분 주기 |
| B-269 | 처리 중 예외 | 결과 기록 중 예외 또는 결제 행 없음 | PaymentRecheckWorker | BILLING-004 등 | 선점됨 | 그 건만 error 지표, 다음 건 계속, 5분 선점 만료 후 재대상 | 1분 주기 |

### 후보 예산 갱신

10분마다 도는 작업이고 batch_execution_log 를 쓰지 않는다.

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-427 | 후보 예산 10분 갱신 | cron 0 */10 * * * * (Asia/Seoul), 배치 서버만 | CandidateBudgetRefreshJob.refresh -> CandidateBudgetService.refresh(오늘) | - | ad_candidate(D), D 계획 있음, 장중 충전 재예약(B-137)으로 예약액 증가 | 후보 campaign_id 를 5,000건씩 BudgetQueryService.getProgress 로 읽어 reserved_amount, unit_price 를 500건씩 덮어씀, 늘어난 예약액이 피드 예산 규칙에 반영. batch_execution_log 미사용 | 10분마다 |
| B-428 | 갱신할 후보 없음 | 같은 cron | CandidateBudgetService.refresh | - | ad_candidate(D) 0건(00:00 적재 전, 또는 묶음 실패) | 0 반환, 정산 호출 없음 | 10분마다 |
| B-429 | 계획 없는 후보 | 같은 cron | CandidateBudgetService.refresh | - | 후보는 있으나 D budget_daily_plan 없음 | reserved_amount=0, unit_price=0 으로 덮어씀, 노출 상한에 걸려 피드에서 빠짐 | 10분마다 |
| B-430 | 정산 조회 실패 | 같은 cron | CandidateBudgetService.refresh | ADSERVING_BUDGET_REFRESH_FAILED 로그 | getProgress 예외 | WARN(fallback=KEEP_PREVIOUS, campaignCount), adserving_budget_unavailable_total 증가, 기존 값 유지, 예외 안 올림, 다음 10분에 다시 시도 | 10분마다 |
| B-431 | 두 서버 동시 갱신 | 두 배치 서버 같은 cron | CandidateBudgetRefreshJob | - | 스케줄러 켜진 서버 2대 | 점유 없이 둘 다 실행, 같은 값 덮어써 결과 같음 | 10분마다 |

### 배치 공통(멱등/인계/재시도)

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-270 | 같은 작업+날짜 재시도 차단 | claim upsert(ON DUPLICATE KEY) | BatchExecutionService.claim | - | (job, date) 행 SUCCESS 또는 FAILED | 점유 false, 재실행 안 함(재실행은 관리자 수동만) | 같은 영업일 |
| B-271 | 점유 경합 일시적 오류 | upsertClaim 교착/락 대기 | BatchExecutionService.claim | - | 두 서버 동시 upsert | 일시적 오류면 1회 재시도로 승패 결정 | 동시 |
| B-272 | 성공 단계 건너뛰기 | runStep begin | BatchExecutionService.begin | ALREADY_DONE | 단계 행 SUCCESS | 건너뛰고 다음 단계 | 재개 시 |
| B-273 | 생존 신호 | 30초마다 | DailyJobScheduler.sendHeartbeat -> renewOwned | - | 내 소유 RUNNING 행 | updated_at 갱신(스케줄러 풀 > 1 이어야 묶음 중에도 동작) | 30s fixed delay |
| B-274 | 멈춘 묶음 인계 | 1분마다 takeOverStale | DailyJobScheduler.takeOverStale | - | 남의 RUNNING 행, updated_at < now-2분 | 소유자 변경, attemptNo+1, takenOver 알림, 묶음이면 runBundle 재개(SUCCESS 단계 건너뜀) | 1m fixed delay, 2분 경과 |
| B-275 | 멈춘 단계 행 인계 | takeOverStale | Kind.STEP | - | 단계 행 stale | 소유자만 바꾸고 직접 실행 안 함, 묶음 재개 시 begin 이 내 RUNNING 으로 보고 STARTED | 2분 경과 |
| B-276 | 멈춘 묶음 밖 작업 인계 | takeOverStale | Kind.STANDALONE -> runner.resume | - | DAILY_AGGREGATION, BILLING_PG_VS_CHARGE_RECON, EVENT_LOG_RETENTION 등 StandaloneJob | runCounting(date, attemptNo) 재실행, 세대 기준으로 닫음 | 2분 경과 |
| B-277 | 재개 경로 없는 행 인계 | takeOverStale | Kind.UNKNOWN | FAILED | 예: BILLING_DAILY_RELEASE 가 RUNNING 으로 멈춤 | "다시 돌릴 경로가 없다" 사유로 FAILED + 알림 | 2분 경과 |
| B-278 | 단계 시작 불가 | begin BLOCKED, retry 실패 | BatchJobRunner.runStep | FAILED | 단계 행이 남의 RUNNING(아직 stale 아님) 또는 행 없음 | 묶음 FAILED "<단계> 단계를 시작할 수 없다" | 실행 중 |
| B-279 | 일시적 오류 자동 재실행 | 단계 예외 isTransient | BatchJobRunner.retryOrFail | - | TransientDataAccessException, DataAccessResourceFailureException, SocketTimeout, 이름에 Timeout | recordRetry(retry_count+1) 후 30초, 1분 대기, renew 확인 뒤 그 단계만 재실행 | 같은 실행 |
| B-280 | 자동 재실행 상한 | 3번째 일시적 실패 | recordRetry 0건 | FAILED | retry_count = 2 (chk_batch_retry) | 단계 FAILED, 묶음 FAILED + 알림 | 같은 실행 |
| B-281 | 비일시적 오류 | 단계 예외 | BatchJobRunner | FAILED | 제약 위반, IllegalStateException 등 | 재실행 없이 FAILED | 즉시 |
| B-282 | 대기 중 임대 상실 | backoff 뒤 renew 0건 | BatchJobRunner.retryOrFail | - | 대기 중 다른 서버가 이어받음 | 재실행하지 않고 종료, 행은 건드리지 않음 | 대기 중 |
| B-283 | 늦게 깨어난 서버의 닫기 | close 조건 불일치 | BatchExecutionService.close | - | 행 소유자 또는 세대가 바뀜 | 0건, 알림 없음(이어받은 쪽이 판정) | 인계 후 |
| B-284 | 실패 사유 길이 | fail | BatchExecutionService.shorten | - | 사유 > 500자 | 500자로 잘라 저장 | - |
| B-285 | 관리자 묶음 수동 재실행 | POST /v1/admin/batch-executions/DAILY_0000/{D}:retry | BatchExecutionRetryService.retry -> runner.resume | - | (DAILY_0000, D) FAILED, 실패 단계 행 FAILED | 묶음 행 RUNNING(attemptNo+1, retry_count 0), 감사 로그, 별도 스레드에서 runBundle, 실패 단계는 begin BLOCKED -> restartFailed 로 다시 실행 | 요청 시 |
| B-286 | 단계 이름 수동 재실행 | POST .../BILLING_RESERVE/{D}:retry | BatchExecutionRetryService | OPS-001 | Kind.STEP | 422 NOT_RETRYABLE_JOB(묶음으로만 재실행) | - |
| B-287 | 알 수 없는 이름 수동 재실행 | POST .../BILLING_DAILY_RELEASE/{D}:retry | BatchExecutionRetryService | OPS-001 | Kind.UNKNOWN | 422 | - |
| B-288 | 실행 기록 없음 | 수동 재실행 | BatchExecutionRetryService | OPS-002 | (job, date) 행 없음 | 404 | - |
| B-289 | FAILED 아님 | 수동 재실행 | BatchExecutionRetryService | OPS-003 | 행 RUNNING 또는 SUCCESS | 409 | - |
| B-290 | 동시 두 번 재실행 | 수동 재실행 경합 | restartFailed 조건부 UPDATE | OPS-003 | 같은 FAILED 행에 동시 요청 | 하나만 202/성공, 뒤 요청 409 | 동시 |
| B-291 | 재실행 큐 가득 | executor.execute 거절 | BatchExecutionRetryService.start | COMMON-001 | 단일 스레드 실행기 대기열 4건 꽉 참 | RejectedExecutionException, 되돌린 행을 다시 FAILED 로 닫고 예외 전파 | 요청 시 |
| B-292 | 수동 재개 중 예외 | 별도 스레드 resume 예외 | BatchExecutionRetryService.resume | - | runner.resume 예외(예: StandaloneJob 미등록) | 구조화 ERROR 로그만, 행은 이 서버 RUNNING 으로 남을 수 있음 | 비동기 |
| B-293 | 수동 재실행 후 이어받기 | 재개 중 서버 다운 | takeOverStale | - | 재실행 행 RUNNING, 2분 경과 | 다른 서버가 attemptNo+1 로 이어받아 재실행 | 2분 경과 |
| B-294 | 세대 확인 | StandaloneJob 결과 공개 트랜잭션 | BatchAttemptGuard.assertCurrentAttempt | - | 행 잠금 후 소유자/attemptNo 비교 | 다르면 IllegalStateException 으로 롤백 | 공개 시 |

### 인증/토큰/역할

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-295 | 쿠키 없이 보호 API 호출 | accessToken 쿠키 없음 | 인증이 필요한 모든 API (예: GET /v1/members/me) | AUTH-005 | 없음 | 401, 봉투에 AUTH-005, 핸들러 미실행 | 없음 |
| B-296 | Authorization 헤더로만 토큰 전송 | Bearer 헤더, 쿠키 없음 | 인증 필요 API | AUTH-005 | 유효 AT | 헤더를 읽지 않아 401 | 없음 |
| B-297 | 서명 위조 또는 형식 깨진 AT | 변조한 accessToken 쿠키 | 인증 필요 API | AUTH-005 | 없음 | 파싱 실패 -> 익명 -> 401 | 없음 |
| B-298 | 목록에 없는 role 클레임 | role 값이 enum 밖인 서명 토큰 | 인증 필요 API | AUTH-005 | 없음 | Role.from null -> 익명 -> 401 (500 아님) | 없음 |
| B-299 | AT 만료 | 발급 후 30분 경과 AT 사용 | 인증 필요 API | AUTH-005 | AT 발급 시각 기록 | 401, 클라이언트가 재발급 1회 호출 | iat + 30분(JWT_ACCESS_VALIDITY_MS 1800000) 이후 |
| B-300 | 역할 불일치: 회원 -> 점주 API | MEMBER AT 로 호출 | /v1/owner/** | AUTH-006 | MEMBER AT | 403, 상세 없음 | 없음 |
| B-301 | 역할 불일치: 점주 -> 관리자 API | OWNER AT 로 호출 | /v1/admin/** | AUTH-006 | OWNER AT | 403 | 없음 |
| B-302 | 역할 불일치: 관리자 -> 회원 API | ADMIN AT 로 호출 | GET /v1/feed, /v1/stores 등 | AUTH-006 | ADMIN AT | 403 | 없음 |
| B-303 | ADMIN 이 관리자 계정 발급, 조회 | ADMIN(비 SUPER) AT | POST, GET /v1/admin/admins | AUTH-006 | role=ADMIN | 체인에서 403 (SUPER_ADMIN 전용) | 없음 |
| B-304 | SUPER_ADMIN 계정 발급, 조회 정상 | SUPER_ADMIN AT | POST, GET /v1/admin/admins | 없음 | ACTIVE SUPER_ADMIN, 발급 순번 유효 | 성공 | 없음 |
| B-305 | SUPER_ADMIN 로그아웃 뒤 남은 AT 로 계정 발급 | 로그아웃 후 이전 AT | POST, GET /v1/admin/admins | AUTH-005 | DB 폐기 순번 > 토큰 순번 | REQUIRED 검증 실패 -> 401 | 로그아웃 이후 30분 안 |
| B-306 | SUPER_ADMIN 계정 발급 중 토큰 저장소 장애 | Redis 버전 조회 실패 | POST, GET /v1/admin/admins | AUTH-002 | Redis 또는 DB 장애 | REQUIRED 정책이라 503, Retry-After 1 | 없음 |
| B-307 | 어느 체인도 맡지 않은 경로 | 비로그인으로 /v1/unknown 호출 | 기본 체인 denyAll | AUTH-005 | 없음 | 401 (COMMON-004 아님) | 없음 |
| B-308 | 어느 체인도 맡지 않은 경로, 로그인 상태 | 유효 AT 로 /v1/unknown | 기본 체인 denyAll | AUTH-006 | 유효 AT | 403 (COMMON-004 아님) | 없음 |
| B-309 | 체인 안 경로지만 핸들러 없음 | OWNER 로 GET /v1/owner/campaigns/1/unknown | 캠페인 체인 hasRole OWNER | COMMON-004 | OWNER AT | 404 ENDPOINT_NOT_FOUND | 없음 |
| B-310 | 메서드 불일치(체인이 역할만 검사) | OWNER 로 GET /v1/owner/store-menus | 가게 체인 anyRequest hasRole OWNER | COMMON-005 | OWNER AT | 405 | 없음 |
| B-311 | 메서드 불일치(체인이 메서드별 선언) | MEMBER 로 PUT /v1/feed | 광고 체인 anyRequest denyAll | AUTH-006 | MEMBER AT | 405 가 아니라 403 | 없음 |
| B-312 | 본문 형식 오류 | 깨진 JSON 또는 타입 불일치 | JSON 본문 API (예: POST /v1/admin/auth/tokens) | COMMON-003 | 없음 | 400, 로그에는 예외 종류만 | 없음 |
| B-313 | Bean Validation 실패 | 필수 필드 누락 | JSON 본문 API | COMMON-002 | 없음 | 400, 필드명은 로그에만 | 없음 |
| B-314 | 지원하지 않는 Content-Type | text/plain 으로 로그인 | POST /v1/admin/auth/tokens (consumes JSON) | COMMON-007 | 없음 | 415 | 없음 |
| B-315 | multipart 크기 초과 | multipart 본문 1MB 초과 | 아무 인증 통과 경로 (multipart 엔드포인트는 없음) | COMMON-006 | 없음 | 413 (스프링 기본 상한 기준, 실제 쓰임 없음) | 없음 |
| B-316 | 처리하지 못한 예외 | 서비스 내부 런타임 예외 | 모든 API | COMMON-001 | 없음 | 500, 예외 종류와 문구 숨김 | 없음 |
| B-317 | 공개 경로 | 비로그인 호출 | POST /v1/auth/tokens, /v1/auth/tokens:refresh, /v1/owner/auth/tokens, :refresh, /v1/admin/auth/tokens, :refresh, POST /v1/owners, GET /v1/auth/kakao/authorize, /webhook/payments, /webhook/kakao/** | 없음 | 없음 | 인증 없이 핸들러 실행 | 없음 |
| B-318 | 운영 경로 | 8081 액추에이터, swagger | /actuator/health, prometheus, /swagger-ui/** | 없음 | 없음 | 인증 없이 열림 (보안 그룹이 경계) | 없음 |
| B-319 | CORS 허용 밖 오리진 | 다른 오리진에서 쿠키 포함 호출 | 전체 | 없음 | CORS_ALLOWED_ORIGINS 미포함 | 브라우저 차단, 허용 헤더는 Content-Type 하나, Retry-After 노출 | 없음 |
| B-320 | 토큰 쿠키 속성 | 로그인 또는 재발급 성공 | 로그인 3종, 재발급 3종 | 없음 | 없음 | AT: HttpOnly, SameSite=Strict, path=/, Max-Age 30분. RT: path /v1/auth/, /v1/owner/auth/, /v1/admin/auth/, Max-Age 회원 14일, 점주와 관리자 1일. Secure 는 JWT_COOKIE_SECURE(기본 false) | 없음 |
| B-321 | 카카오 로그인 state 만료 | authorize 후 5분 넘겨 토큰 교환 | POST /v1/auth/tokens | AUTH-007 | state 키 TTL 5분 | 401 | authorize + 5분 이후 |
| B-322 | 카카오 state 재사용 | 같은 state 로 두 번 교환 | POST /v1/auth/tokens | AUTH-007 | state 는 consume 로 1회용 | 두 번째 401 | 없음 |
| B-323 | 카카오 nonce 불일치 또는 ID 토큰 검증 실패 | 위조된 code, 다른 nonce | POST /v1/auth/tokens | AUTH-007 | 없음 | 401 | 없음 |
| B-324 | 카카오 로그인 서킷 열림 | 최근 60초 5회 이상 중 실패율 50% 또는 3초 초과 느린 호출 50% | POST /v1/auth/tokens | AUTH-007 | kakaoLogin 서킷 OPEN | 카카오 호출 없이 401, 30초 뒤 다음 요청이 반개방 판정 | OPEN 후 30초 |
| B-325 | 비활성 기존 회원 로그인 | status != ACTIVE 회원 | POST /v1/auth/tokens | MEMBER-003 | 회원 status SUSPENDED 등 (상태 전환 API 는 없음, DB 직접) | 403 | 없음 |
| B-326 | 정지 이력 탈퇴자 재가입 | 탈퇴 이력 suspendedAt 존재 | POST /v1/auth/tokens | MEMBER-003 | withdrawal_history.suspended_at not null | 403 | 없음 |
| B-327 | 연결 해제 미완료 재가입 | kakao_unlink_failure 행 존재 | POST /v1/auth/tokens | MEMBER-005 | 해당 provider_user_id 실패 행(해결 여부 무관) | 409 | 없음 |
| B-328 | 탈퇴 당일 재가입 | 당일 탈퇴 후 신규 가입 | POST /v1/auth/tokens | 없음(MEMBER-004 주석 처리) | 당일 탈퇴 이력 | 가입 허용 | 탈퇴 당일 |
| B-329 | 회원 로그인 중 RT DB 백업 실패 | DB 저장 실패 | POST /v1/auth/tokens | AUTH-002 | DB 장애 | 503, 쿠키 미발급 | 없음 |
| B-330 | 회원 로그인 중 Redis 저장 실패 | Redis 장애 | POST /v1/auth/tokens | 없음 | Redis 장애 | 로그인 성공 (DB 백업 기준) | 없음 |
| B-331 | 회원 재발급 정상 | 유효 RT 쿠키 | POST /v1/auth/tokens:refresh | 없음 | Redis 레코드 존재, DB 해시 일치, ACTIVE | 새 AT, RT 쿠키. 옛 RT 는 남은 TTL 동안 REVOKED tombstone | RT 만료 전 |
| B-332 | 회원 재발급 RT 쿠키 없음 | 쿠키 없음 또는 빈 값 | POST /v1/auth/tokens:refresh | AUTH-003 | 없음 | 401 | 없음 |
| B-333 | 회원 RT 만료 또는 없음 | 14일 지난 RT | POST /v1/auth/tokens:refresh | AUTH-003 | Redis 키 없음 | 401. Redis 정상 응답이면 DB 로 폴백하지 않음 | 발급 + 14일 이후 |
| B-334 | 회원 RT 재사용 탐지 | 이미 회전한 RT 재제출 | POST /v1/auth/tokens:refresh | AUTH-004 | tombstone 존재 | 계정 RT 폐기, 지금 이전 AT 전부 거부(cutoff TTL 30분) -> 모든 기기 로그아웃 | 옛 RT 남은 수명 안 |
| B-335 | 회원 동시 재발급(두 탭) | 같은 RT 로 거의 동시 두 건 | POST /v1/auth/tokens:refresh | AUTH-004 | 같은 RT | 하나 성공, 다른 하나 tombstone 을 보고 재사용으로 판정 -> 방금 발급된 세션까지 폐기 | 동시 |
| B-336 | 회원 다른 기기 로그인 뒤 이전 기기 재발급 | 기기 B 로그인 후 기기 A RT | POST /v1/auth/tokens:refresh | AUTH-002 -> 재시도 시 AUTH-004 | DB 해시 = B, Redis 에 A 레코드 남음 | Redis 회전 성공 후 DB CAS 실패 -> 503(Retry-After 1). A 키는 tombstone 이 되어 재시도하면 재사용 판정 -> B 세션도 폐기 (코드 경로 추론) | 없음 |
| B-337 | 회원 재발급 중 Redis 장애 | Redis 예외 | POST /v1/auth/tokens:refresh | 없음 또는 AUTH-003 | DB 해시 일치, 만료 전, ACTIVE | DB CAS 로 회전 성공. 이 경로는 재사용 탐지 불가. 조건 불일치면 401 | 없음 |
| B-338 | 회원 재발급 중 Redis 와 DB 모두 장애 | 두 저장소 실패 | POST /v1/auth/tokens:refresh | AUTH-002 | 장애 | 503 | 없음 |
| B-339 | 비활성 회원 재발급 | status != ACTIVE, Redis 레코드 유효 | POST /v1/auth/tokens:refresh | AUTH-002 | DB 상태 SUSPENDED | DB CAS 가 ACTIVE 조건으로 0건 -> Redis 롤백 -> 503 (401 아님) | 없음 |
| B-340 | 회원 로그아웃 정상 | 유효 MEMBER AT | DELETE /v1/auth/tokens | 없음 | 활성 RT | RT 폐기, 지금 이전 AT 차단, 두 쿠키 만료, 커밋 뒤 카카오 로그아웃(실패해도 응답 성공) | 없음 |
| B-341 | 회원 로그아웃 저장소 장애 | Redis 또는 DB 실패 | DELETE /v1/auth/tokens | AUTH-002 | 장애 | 503 | 없음 |
| B-342 | AT 만료 상태로 로그아웃 | 30분 지난 AT | DELETE /v1/auth/tokens (MEMBER 필요) | AUTH-005 | 없음 | 401, 재발급 후 다시 로그아웃 필요. 점주, 관리자 로그아웃도 같음 | iat + 30분 이후 |
| B-343 | 로그아웃 또는 탈퇴 뒤 남은 회원 AT 사용 | 이전 AT | 회원 API | AUTH-005 | accessTokenValidAfter:MEMBER:{id} 기록 | 401 | 로그아웃 후 30분 안 |
| B-344 | 로그아웃 뒤 AT 검사 중 Redis 장애 | cutoff 조회 실패 | 회원 API | 없음 | Redis 장애 | LENIENT 로 통과 (최대 30분 더 사용 가능) | 없음 |
| B-345 | 점주 로그인 정상 | 이메일, 비밀번호 | POST /v1/owner/auth/tokens | 없음 | status ONBOARDING 또는 ACTIVE | AT(발급 순번 클레임), RT 쿠키. tutorialViewed 는 ACTIVE 일 때만 | 없음 |
| B-346 | 점주 로그인 실패 | 없는 이메일, 틀린 비밀번호, SUSPENDED, WITHDRAWN | POST /v1/owner/auth/tokens | AUTH-001 | 상태별 | 모두 같은 401, 없는 계정도 더미 BCrypt 비교 | 없음 |
| B-347 | 점주 로그인 DB 저장 실패 | 트랜잭션 실패(5초 타임아웃 포함) | POST /v1/owner/auth/tokens | AUTH-002 | DB 장애 | 503, 쿠키 미발급 | 없음 |
| B-348 | 점주 로그인 Redis 게시 실패 | Redis 장애 | POST /v1/owner/auth/tokens | 없음 | Redis 장애 | 로그인 성공 | 없음 |
| B-349 | 점주 재발급 정상 | 유효 RT | POST /v1/owner/auth/tokens:refresh | 없음 | Redis 레코드, DB 해시 일치, canLogin | 옛 RT PENDING -> DB 회전 -> 새 RT 게시 -> 옛 RT REVOKED 확정 | RT 1일 안 |
| B-350 | 점주 동시 재발급 | 같은 RT 두 건 | POST /v1/owner/auth/tokens:refresh | AUTH-003 | PENDING 상태 | 두 번째 401, 계정 폐기 안 함 | 동시 |
| B-351 | 점주 RT 재사용 | REVOKED RT 재제출 | POST /v1/owner/auth/tokens:refresh | AUTH-004 | tombstone | DB RT 삭제와 순번 증가, 그 순번 이하 AT 전부 거부 | 옛 RT 남은 수명 안 |
| B-352 | 점주 다른 기기 로그인 뒤 이전 RT | 기기 B 로그인 후 A 재발급 | POST /v1/owner/auth/tokens:refresh | AUTH-003 | DB 해시 = B | 401, A 키는 PENDING 으로 남아 이후도 401 (단일 세션) | 없음 |
| B-353 | 점주 재발급 중 상태 변경 | SUSPENDED, WITHDRAWN 으로 바뀐 뒤 재발급 | POST /v1/owner/auth/tokens:refresh | AUTH-003 | canLogin false | 401 | 없음 |
| B-354 | 점주 재발급 Redis 장애 폴백 | Redis 예외 | POST /v1/owner/auth/tokens:refresh | 없음 또는 AUTH-003, AUTH-002 | DB 해시 조회 | DB 해시로 점주 찾아 회전, 없으면 401, DB 도 실패면 503 | 없음 |
| B-355 | 점주 로그아웃 | 유효 OWNER AT | DELETE /v1/owner/auth/tokens | 없음 또는 AUTH-002 | 활성 RT | DB RT 삭제 후 Redis RT 폐기와 순번까지 AT 차단. Redis 실패면 503 (DB 는 이미 커밋) | 없음 |
| B-356 | 점주 AT 검증 DB 장애 | 폐기 순번 DB 조회 실패 | 점주 API | AUTH-005 | DB 장애 | 인증 거부(fail-closed). Redis 버전 조회 실패만 통과 | 없음 |
| B-357 | 점주 로그아웃 뒤 이전 AT | 순번 이하 AT | 점주 API | AUTH-005 | 폐기 순번 기록 | 401 | 로그아웃 후 30분 안 |
| B-358 | ONBOARDING 점주의 결제, 포인트, 환불 API | ONBOARDING AT | /v1/owner/payments/**, point-policy, points/**, refund-requests/** | BILLING-023 | status ONBOARDING | 인터셉터가 DB 직접 조회 -> 403 | 없음 |
| B-359 | 정지된 점주가 남은 AT 로 결제 API | SUSPENDED 전환 후 AT | /v1/owner/payments 등 | BILLING-023 | status SUSPENDED (DB 직접) | 캐시 TTL 과 무관하게 즉시 403 | AT 수명 안 |
| B-360 | 비 ACTIVE 점주의 캠페인 쓰기 | ONBOARDING, SUSPENDED | 캠페인 생성, 수정, 활성화, 취소, 중단, 재개, 포스터 업로드 | CAMPAIGN-001 | status != ACTIVE | 403 | 없음 |
| B-361 | 비 ACTIVE 점주의 쿠폰 사용 처리 | QR 사용 요청 | POST /v1/owner/coupon-usages | COUPON-013 | status != ACTIVE | 403 | 없음 |
| B-362 | 비 ACTIVE 점주의 쿠폰 조회 | 사용 내역, 일별, 캠페인 요약 | GET /v1/owner/stores/{storeId}/coupon-usages, /daily, /v1/owner/campaigns/{id}/coupons/summary | AUTH-006 | status != ACTIVE | 403 (쿠폰 도메인이 AUTH-006 사용) | 없음 |
| B-363 | ACTIVE 점주가 입점 단계 API 호출 | ACTIVE AT | PUT /v1/owner/store/basic, /location, PUT /v1/owner/terms-agreements, POST /v1/owner/store:complete | OWNER-002 | status ACTIVE | 403 (ONBOARDING 전용) | 없음 |
| B-364 | ONBOARDING 점주가 튜토리얼 완료 | ONBOARDING AT | PATCH /v1/owner/me/tutorial | OWNER-002 | status ONBOARDING | 403 (ACTIVE 전용) | 없음 |
| B-365 | 비활성 점주의 가게 조회 | SUSPENDED AT | GET /v1/owner/store, GET /v1/owner/onboarding | OWNER-002 | status SUSPENDED, WITHDRAWN | 403 (ONBOARDING, ACTIVE 만 허용) | 없음 |
| B-366 | 점주 행이 없는 AT | 삭제된 점주 id | 점주 프로필, 가게 API | AUTH-005 | 점주 행 없음 | 정책 검증 실패 또는 LOGIN_REQUIRED -> 401 | 없음 |
| B-367 | 점주 상태 캐시 사용과 무효화 | ACTIVE 전환 커밋, 관리자 포인트 조정 | OwnerStoreActivationService, POST /v1/admin/point-adjustments | BILLING-019 | 캐시 TTL 30초(owner.status-cache.ttl-seconds) | 전환 커밋 뒤 캐시 삭제, 삭제 실패면 TTL 까지 이전 값. 캐시를 읽는 곳은 포인트 조정의 점주 존재 확인뿐, 없으면 404 | 전환 후 30초 안 |
| B-368 | 관리자 로그인 정상 | loginId, 비밀번호 | POST /v1/admin/auth/tokens | 없음 | ACTIVE, role ADMIN 또는 SUPER_ADMIN | AT(발급 순번), RT 쿠키(1일), 감사 기록 | 없음 |
| B-369 | 관리자 로그인 실패 | 없는 ID, 틀린 비밀번호, 비활성, 72바이트 초과 비밀번호 | POST /v1/admin/auth/tokens | AUTH-001 | 상태별 | 모두 같은 401, 더미 BCrypt 비교 | 없음 |
| B-370 | 관리자 로그인 DB 실패 | 트랜잭션 실패 | POST /v1/admin/auth/tokens | AUTH-002 | DB 장애 | 503 | 없음 |
| B-371 | 관리자 RT 형식 불량 | 43자 base64url 이 아닌 쿠키 | POST /v1/admin/auth/tokens:refresh | AUTH-003 | 없음 | 저장소 조회 없이 401 | 없음 |
| B-372 | 관리자 재발급 정상 | 유효 RT | POST /v1/admin/auth/tokens:refresh | 없음 | Redis 레코드, DB 해시 일치 | 회전, 감사 ADMIN_TOKEN_REISSUE | RT 1일 안 |
| B-373 | 관리자 Redis 에 RT 없음 | 캐시 유실 | POST /v1/admin/auth/tokens:refresh | 없음 또는 AUTH-003 | DB 해시 존재 | 회원과 달리 NOT_FOUND 도 DB 폴백, DB 일치하면 성공 | 없음 |
| B-374 | 관리자 RT 재사용 | tombstone RT 재제출 | POST /v1/admin/auth/tokens:refresh | AUTH-004 | tombstone | DB RT 폐기와 순번 증가, 감사 REUSE_DETECTED. AT 폐기 순번은 갱신하지 않아 기존 AT 는 만료까지 유효 | 옛 RT 남은 수명 안 |
| B-375 | 관리자 다른 기기 로그인 뒤 이전 RT | B 로그인 후 A 재발급 | POST /v1/admin/auth/tokens:refresh | AUTH-003 -> 재시도 시 AUTH-004 | DB 해시 = B | Redis 회전 후 DB 실패 -> 401. A 키가 tombstone 이 되어 재시도하면 B 세션 폐기 (코드 경로 추론) | 없음 |
| B-376 | 관리자 회전 Redis 타임아웃 | 회전 결과 모름 | POST /v1/admin/auth/tokens:refresh | AUTH-002 | Redis 타임아웃, 새 키 확인 실패 | 503, 감사 ROTATION_RESULT_UNKNOWN | 없음 |
| B-377 | 관리자 로그아웃 | 유효 ADMIN AT | DELETE /v1/admin/auth/tokens | 없음 또는 AUTH-002, AUTH-005 | ACTIVE 관리자 | DB RT 폐기, AT 폐기 순번과 validAfter 기록, Redis 반영, 감사. 저장소 실패 503, 비활성이거나 역할 변경이면 401 | 없음 |
| B-378 | 관리자 AT 검증 DB 장애 | 폐기 상태 조회 실패 | 관리자 API | AUTH-005 | DB 장애 | fail-closed 로 401. Redis 버전 조회 실패만 통과 | 없음 |
| B-379 | 웹훅 경로 인증 | 토스, 카카오 서버 호출 | POST /webhook/payments, GET/POST /webhook/kakao/unlink | 없음 | 없음 | 쿠키 없이 permitAll. 결제 웹훅 체인은 JWT 필터와 속도 제한 필터도 없음 | 없음 |

### 속도 제한

| ID | 시나리오 | 단계/트리거 | API 또는 작업 | 오류 코드 | 필요한 데이터 상태 | 기대 결과 | 시각 조건 |
|---|---|---|---|---|---|---|---|
| B-380 | 로그인, 재발급 IP 제한 이내 | 같은 IP 에서 1분 안 10회 POST | 로그인 3종, 재발급 3종 | 없음 | authRateLimit:{ip} 카운터 | 통과 | 첫 요청 후 60초 창 |
| B-381 | 로그인, 재발급 IP 제한 초과 | 같은 창에서 11번째 POST | 같은 6 경로 | 없음(본문 없음) | 카운터 > 10 | 429, 응답 본문과 오류 코드 없음, Retry-After = 키 남은 TTL 초 | 창 안 |
| B-382 | 역할 간 합산 | 같은 IP 에서 회원 재발급과 점주 로그인, 관리자 로그인 섞음 | 6 경로 | 없음 | 키가 IP 하나로 공유 | 세 역할과 재발급까지 합쳐 10회. NAT 뒤 다수 사용자도 합산 | 창 안 |
| B-383 | 창 만료 뒤 회복 | 첫 요청 60초 후 재요청 | 6 경로 | 없음 | 카운터 만료 | 다시 허용 | 첫 요청 + 60초 이후 |
| B-384 | 제한 대상 아님 | DELETE 로그아웃, POST /v1/owners, GET kakao authorize | 해당 경로 | 없음 | 없음 | 횟수 세지 않음 | 없음 |
| B-385 | IP 결정 | X-Forwarded-For 여러 항목, 위조 앞 항목 | 6 경로 | 없음 | 없음 | 마지막 항목만 사용, 형식 이상이면 접속 IP | 없음 |
| B-386 | 로그인 제한 저장소 장애 | Redis 실패 | 6 경로 | 없음 | Redis 장애 | 세지 못해 통과. TTL 조회 실패 시 Retry-After 60 | 없음 |
| B-387 | 피드 제한 이내 | 회원 1명이 60초 안 6회 | GET /v1/feed | 없음 | adserving:feedRequests:{memberId} 슬라이딩 로그 | 200, 6번째 응답의 nextFeedAvailableAt = 가장 오래된 요청 + 60초 | 최근 60초 |
| B-388 | 피드 제한 초과 | 7번째 요청 | GET /v1/feed | ADSERVING-003 | 최근 60초 6건 | 429, Retry-After = 올림(가장 오래된 요청 + 60초 - 지금), 거부 건은 세지 않음 | 최근 60초 안 |
| B-389 | 피드 제한 전 거름 | 온보딩 미완료 또는 위치 없음 | GET /v1/feed | ADSERVING-001, ADSERVING-002 | 미완료, 위치 없음 | 403, 422 로 끝나고 횟수 세지 않음 | 없음 |
| B-390 | 피드 제한 저장소 장애 | Redis 실패 | GET /v1/feed | 없음 | Redis 장애 | 통과, 우회 지표 증가 | 없음 |
| B-391 | 캠페인 활성화 제한 초과 | 점주 1명이 1분 안 4번째 활성화 | POST /v1/owner/campaigns/{id}:activate | CAMPAIGN-011 | campaignActivationRateLimit:{ownerId} > 3 | 429, Retry-After 1 고정(남은 시간 아님). 점주 상태 검사보다 먼저 세므로 실패 요청도 셈 | 첫 요청 후 60초 창 |
| B-392 | 캠페인 활성화 제한 저장소 장애 | Redis 실패 | POST /v1/owner/campaigns/{id}:activate | 없음 | Redis 장애 | 통과 | 없음 |
| B-393 | 템플릿 생성 제한 초과 | 관리자 1명이 1분 안 4번째 생성 또는 수정 | POST /v1/admin/templates, POST /v1/admin/templates/{id}/versions | POSTER-014 | templateGenerationRateLimit:{adminId} > 3 (생성과 수정 합산) | 429, Retry-After 1 고정, 처리 중 표식 삭제 | 첫 요청 후 60초 창 |
| B-394 | 같은 requestId 처리 중 재요청 | 첫 요청 LLM 호출 중 같은 requestId | 템플릿 생성, 수정 | POSTER-004 | templateGenerationInFlight:{requestId} 존재 | 503, 횟수 세지 않음 | 첫 요청 후 60초 안 |
| B-395 | 제한에 걸린 requestId 재시도 | POSTER-014 받은 같은 requestId | 템플릿 생성, 수정 | POSTER-014 또는 없음 | 표식 삭제됨 | 다시 제한 판정, 창이 지나면 처리 | 창 경과 여부 |
| B-396 | 문구 검증 거부도 셈 | POSTER-022~025 에 걸린 요청 | 템플릿 생성, 수정 | POSTER-022, 023, 024, 025 | 카운터 증가 | 거부되지만 분당 3회에 포함, 표식은 지움 | 창 안 |
| B-397 | 템플릿 제한 저장소 장애 | Redis 실패 | 템플릿 생성, 수정 | 없음 | Redis 장애 | 표식 판정 실패는 첫 시도로 보고, 카운트 실패는 통과 | 없음 |
| B-398 | 쿠폰 일일 발급 한도 | 하루 4번째 발급 | POST /v1/coupons | COUPON-007 | 오늘 발급 3장 (COUPON_DAILY_LIMIT 3) | 409 | 영업일 단위 |
| B-399 | 쿠폰 발급 대기열 포화 | 대기열 2000 가득 또는 작업자 정지 | POST /v1/coupons | COUPON-008 | 작업자 4, 배치 500, 대기 10초 | 즉시 503, Retry-After 1 | 11:00~14:00 발급 시간 |
| B-400 | 카카오 로그아웃, 연결 해제 서킷 | 최근 300초 3회 이상 전부 실패 | DELETE /v1/auth/tokens 후속, 탈퇴와 연결 해제 | 없음 | kakaoLogout, kakaoUnlink OPEN | 로그아웃은 응답 성공 유지하고 로그만, 30초 뒤 반개방 | OPEN 후 30초 |
| B-401 | 포인트 조정 멱등 키 충돌 | 같은 requestId 로 내용이 다른 조정을 API 로 직접 다시 보냄 | POST /v1/admin/point-adjustments | BILLING-018 | 같은 키로 먼저 들어간 ADJUST 1건 | 409, 원장은 첫 요청만 남음. 화면에서는 내용이 바뀌면 새 requestId 를 만들어 나지 않는다 | - |

## 오류 코드 대조

기준: 각 ErrorCode 상수를 참조하는 코드를 찾아 컨트롤러, 필터, 작업까지 거슬러 올라갔다. "가능"은 정상 요청 흐름에서 날 수 있음, "조건부"는 설정이나 DB 직접 조작이 있어야만 남, "불가"는 어떤 경로로도 던지지 않음이다.

| 코드 | 던지는 곳(API/작업) | 도달 가능 여부 |
|---|---|---|
| ADMIN-001 | POST /v1/admin/admins (loginId 중복) | 가능 |
| ADSERVING-001 | GET /v1/feed (온보딩 미완료) | 가능 |
| ADSERVING-002 | GET /v1/feed (저장 위치 없음, 좌표 미전송) | 가능 |
| ADSERVING-003 | GET /v1/feed (회원별 피드 속도 제한) | 가능 |
| ADSERVING-004 | POST /v1/swipes, POST /v1/wishlist (오늘 노출 캠페인 아님) | 가능 |
| ANALYTICS-001 | 관리자 대시보드/매출/부정/캠페인 리포트, GET /v1/owner/report, GET /v1/owner/campaigns/{id}/analytics/funnel (기간 오류) | 가능 |
| ANALYTICS-002 | POST /v1/admin/aggregations:recalculate (오늘 이후 등 집계 불가 날짜) | 가능 (화면 없음) |
| ANALYTICS-003 | POST /v1/admin/aggregations:recalculate (같은 날짜 RUNNING) | 가능 (화면 없음) |
| ANALYTICS-004 | POST /v1/events (허용 외 eventType) | 가능 (화면 없음) |
| ANALYTICS-005 | POST /v1/events (필수 필드 누락 등) | 가능 (화면 없음) |
| AUTH-001 | POST /v1/owner/auth/tokens, POST /v1/admin/auth/tokens | 가능 |
| AUTH-002 | 세 역할 로그인/갱신/로그아웃, JwtAuthenticationFilter 의 AT 차단 시각 조회 (Redis 장애), 회원 SUSPENDED 갱신 | 가능 |
| AUTH-003 | 세 역할 tokens:refresh (RT 없음/만료/무효), 회원 :withdraw 재인증 | 가능 |
| AUTH-004 | 세 역할 tokens:refresh (회전된 RT 재사용) | 가능 |
| AUTH-005 | 모든 인증 필요 경로 (AT 없음/만료/위조), 어느 체인에도 없는 경로 비로그인 | 가능 |
| AUTH-006 | 역할 불일치 전 경로, 관리자 SUPER_ADMIN 전용 API, 점주 쿠폰 요약/사용 내역 (점주 비활성), 캠페인 퍼널 (남의 캠페인) | 가능 |
| AUTH-007 | POST /v1/auth/tokens, POST /v1/members/me:withdraw (카카오 토큰 교환/검증 실패) | 가능 |
| BILLING-001 | POST /v1/owner/payments (최소 충전액 미만) | 가능 |
| BILLING-002 | POST /v1/owner/payments (허용 상품 아님) | 가능 |
| BILLING-003 | POST /v1/owner/payments/{id}:confirm (PG 거절) | 가능 |
| BILLING-004 | GET/confirm /v1/owner/payments/{id}, POST /v1/admin/payments/{id}:recheck, PaymentRecheckWorker | 가능 |
| BILLING-005 | POST /v1/admin/payments/{id}:recheck (UNKNOWN 아님) | 가능 |
| BILLING-006 | GET /v1/owner/points/balance, 점주 환불 신청/미리보기, 관리자 환불 승인, 포인트 조정 (원장 조회 실패) | 가능 |
| BILLING-007 | POST /v1/owner/payments/{id}:confirm (PG 요청 전송 실패) | 가능 |
| BILLING-008 | POST /v1/owner/payments (UNKNOWN 결제 존재) | 가능 |
| BILLING-009 | POST /v1/owner/payments/{id}:confirm (취소된 주문) | 가능 |
| BILLING-010 | POST /v1/owner/refund-requests, preview, 관리자 :approve (집행 중 캠페인) | 가능 (점주 쪽 화면 없음) |
| BILLING-011 | POST /v1/owner/refund-requests (PENDING 존재) | 가능 (화면 없음) |
| BILLING-012 | POST /v1/owner/refund-requests (환불 가능 잔액 0) | 가능 (화면 없음) |
| BILLING-013 | POST /v1/admin/refund-requests/{id}:approve (잔액 부족) | 가능 |
| BILLING-014 | 관리자 :approve, :reject (요청 없음) | 가능 |
| BILLING-015 | 관리자 :approve, :reject (이미 처리) | 가능 |
| BILLING-016 | POST /v1/admin/point-adjustments (음수 잔액) | 가능 |
| BILLING-017 | :confirm, POST /webhook/payments (내부 처리, 응답은 200), :recheck, 재조회 워커 (orderId 불일치) | 가능 |
| BILLING-018 | POST /v1/admin/point-adjustments (같은 키 다른 내용) | 가능 |
| BILLING-019 | POST /v1/admin/point-adjustments (점주 없음) | 가능 |
| BILLING-020 | POST /v1/admin/point-adjustments (대사 불일치 건 없음) | 가능 |
| BILLING-021 | POST /v1/admin/point-adjustments (이미 해결된 불일치) | 가능 |
| BILLING-022 | POST /v1/admin/point-adjustments (불일치 건 점주 다름) | 가능 |
| BILLING-023 | OwnerActiveInterceptor: /v1/owner/payments, points, point-policy, refund-requests (점주 비 ACTIVE) | 가능 |
| CAMPAIGN-001 | 점주 캠페인 생성/수정/상태 전이, 포스터 API (점주 비 ACTIVE) | 가능 |
| CAMPAIGN-002 | POST/PATCH /v1/owner/campaigns (할인값) | 가능 |
| CAMPAIGN-003 | POST/PATCH /v1/owner/campaigns (발급 수량) | 가능 |
| CAMPAIGN-004 | POST/PATCH /v1/owner/campaigns (이용 시간) | 가능 |
| CAMPAIGN-005 | 점주 캠페인 단건/수정/전이/포스터, GET /v1/campaigns/{id}/poster | 가능 |
| CAMPAIGN-006 | 점주 캠페인 수정/전이, 포스터 업로드 (상태 불가) | 가능 |
| CAMPAIGN-007 | PATCH /v1/owner/campaigns (하루 예산 하한) | 가능 |
| CAMPAIGN-008 | PATCH /v1/owner/campaigns (기간 1일 미만) | 가능 |
| CAMPAIGN-009 | PATCH /v1/owner/campaigns (시작일 오늘 이하) | 가능 |
| CAMPAIGN-010 | POST /v1/owner/campaigns (진행 중 캠페인 존재) | 가능 |
| CAMPAIGN-011 | POST /v1/owner/campaigns/{id}:activate (속도 제한) | 가능 |
| CAMPAIGN-012 | GET /v1/admin/campaigns/{id} (없음) | 가능 |
| COMMON-001 | 모든 API의 미처리 예외, 쿠폰 데이터 불일치, 수동 재실행/재집계 큐 포화 | 가능 |
| COMMON-002 | Bean Validation, IllegalArgumentException, 점주 쿠폰 요약/일별 기간 오류, 배치 이력 등 | 가능 |
| COMMON-003 | 본문 JSON 깨짐, 타입 불일치, 필수 파라미터 누락 | 가능 |
| COMMON-004 | 보안 체인이 맡은 경로 안의 없는 핸들러만 (체인 밖 경로는 AUTH-005/006) | 가능 (일부 경로만) |
| COMMON-005 | 체인이 역할만 검사하는 경로의 메서드 불일치 | 가능 (일부 경로만) |
| COMMON-006 | MaxUploadSizeExceededException 핸들러뿐, multipart 엔드포인트 없음 | 사실상 불가 (조작한 multipart 요청으로만) |
| COMMON-007 | JSON consumes 경로에 다른 Content-Type | 가능 |
| COUPON-001 | POST /v1/coupons (이벤트 없음), GET /v1/coupons/{id} (없음/남의 것) | 가능 |
| COUPON-002 | POST /v1/coupons (발급 시간 밖/이벤트 상태), QR 생성/사용 (ISSUED 아님) | 가능 |
| COUPON-003 | POST /v1/coupons, :generateQr, POST /v1/owner/coupon-usages (회원 비 ACTIVE) | 가능 |
| COUPON-004 | POST /v1/coupons (찜 안 함) | 가능 |
| COUPON-005 | POST /v1/coupons (같은 이벤트 중복) | 가능 |
| COUPON-006 | POST /v1/coupons (재고 소진) | 가능 |
| COUPON-007 | POST /v1/coupons (일일 한도) | 가능 |
| COUPON-008 | POST /v1/coupons (Redis 순번 예약 실패, 대기 초과) | 가능 |
| COUPON-009 | POST /v1/owner/coupon-usages (위치 반경 밖) | 가능 (화면 없음) |
| COUPON-010 | POST /v1/owner/coupon-usages (QR 만료/불일치) | 가능 (화면 없음) |
| COUPON-011 | 쿠폰 단건/삭제/QR (남의 쿠폰), 점주 쿠폰 사용 (남의 가게) | 가능 |
| COUPON-012 | POST /v1/owner/coupon-usages (이미 사용), 삭제/QR | 가능 |
| COUPON-013 | POST /v1/owner/coupon-usages (점주 비 ACTIVE) | 가능 (화면 없음) |
| MEMBER-001 | PATCH /v1/members/me, 위치 API, :withdraw (탈퇴 회원) | 가능 (AT 만료 전 탈퇴 직후만) |
| MEMBER-002 | PUT /v1/members/me/onboarding (이미 완료) | 가능 |
| MEMBER-003 | POST /v1/auth/tokens (비 ACTIVE 회원, 정지 이력) | 조건부 (회원 정지 API 없음, DB 직접 조작 시만) |
| MEMBER-004 | MemberLoginService 의 검사 코드가 주석 처리됨 | 불가 |
| MEMBER-005 | POST /v1/auth/tokens (unlink 실패 행 존재), :withdraw | 가능 |
| OPS-001 | POST /v1/admin/batch-executions/{job}/{date}:retry (묶음 단계, UNKNOWN 작업) | 가능 (화면 없음) |
| OPS-002 | 같은 API (실행 행 없음) | 가능 (화면 없음) |
| OPS-003 | 같은 API (FAILED 아님) | 가능 (화면 없음) |
| OPS-004 | GET /v1/admin/batch-executions (기간 오류) | 가능 (화면 없음) |
| OWNER-001 | POST /v1/owners (이메일 중복) | 가능 |
| OWNER-002 | 점주 로그인/프로필/가게 등록 단계 API (상태가 단계와 맞지 않음) | 가능 |
| POSTER-001 | 관리자 템플릿 생성/수정, 점주 포스터 저장 (슬롯 위반) | 가능 |
| POSTER-002 | POST /v1/admin/templates/{id}/versions (구조 변경) | 가능 |
| POSTER-003 | POST /v1/admin/templates (개수 상한 100) | 가능 |
| POSTER-004 | 템플릿 생성/수정 (Bedrock 30초 초과, 동시 한도 대기 초과) | 가능 |
| POSTER-005 | 템플릿 단건/수정/게시/활성/삭제, GET /v1/owner/templates, 포스터 저장 | 가능 |
| POSTER-006 | 템플릿 수정/삭제 (게시됨) | 가능 |
| POSTER-007 | POST /v1/admin/templates/{id}/publish (이미 게시) | 가능 |
| POSTER-008 | POST /v1/admin/templates/{id}/publish (버전 없음) | 가능 |
| POSTER-009 | PATCH /v1/admin/templates/{id}/active (미게시) | 가능 |
| POSTER-010 | BedrockTemplateHtmlGenerator (출력 잘림) | 조건부 (bedrock.model-id 설정 시만, 없으면 Fake 생성기) |
| POSTER-011 | BedrockTemplateHtmlGenerator (마지막 시도까지 금지 요소) | 조건부 (bedrock.model-id 설정 시만) |
| POSTER-012 | BedrockTemplateHtmlGenerator (스로틀링) | 조건부 (bedrock.model-id 설정 시만) |
| POSTER-013 | BedrockTemplateHtmlGenerator (가드레일 차단) | 조건부 (bedrock.model-id 설정 시만) |
| POSTER-014 | 템플릿 생성/수정 (관리자별 속도 제한) | 가능 |
| POSTER-015 | POST /v1/owner/campaigns/{id}/poster (미게시/비활성 템플릿) | 가능 |
| POSTER-016 | 포스터 저장 (S3 객체 없음) | 가능 |
| POSTER-017 | poster:prepareUpload, 포스터 저장 (형식) | 가능 |
| POSTER-018 | poster:prepareUpload, 포스터 저장 (10MB 초과) | 가능 |
| POSTER-019 | 포스터 저장 (다른 캠페인 객체 키) | 가능 |
| POSTER-020 | 점주 포스터 조회, :activate (포스터 없음), 회원 포스터 조회 | 가능 |
| POSTER-021 | GET /v1/campaigns/{id}/poster, 점주 포스터 조회 (조회 불가) | 가능 |
| POSTER-022 | 템플릿 생성/수정 프롬프트 검사 | 가능 |
| POSTER-023 | 템플릿 생성/수정 프롬프트 검사 | 가능 |
| POSTER-024 | 템플릿 생성/수정 프롬프트 검사 | 가능 |
| POSTER-025 | 템플릿 생성/수정 프롬프트 검사 | 가능 |
| STORE-001 | 가게 등록 단계 저장 (version 충돌) | 가능 |
| STORE-002 | 가게 등록 단계 저장, 약관, 이미지/메뉴 업로드 (단계 불일치) | 가능 |
| STORE-003 | 가게 등록 단계 저장, 업로드, :complete (진행 상태 없음) | 가능 |
| STORE-004 | 가게 등록 단계 저장, 업로드, :complete (가게 연결 불가) | 가능 |
| STORE-005 | store-images/menu-images :prepareUpload (형식) | 가능 |
| STORE-006 | 같은 업로드 API (10MB 초과) | 가능 |
| STORE-007 | store-images (인테리어 3개 초과) | 가능 |
| STORE-008 | store-images:confirmUpload (업로드 요청 없음) | 가능 |
| STORE-009 | 이미지/메뉴 이미지 confirmUpload (S3 객체 없음) | 가능 |
| STORE-010 | store-images:complete (로고 없음) | 가능 |
| STORE-011 | POST /v1/owner/store-menus (3개 초과) | 가능 |
| STORE-012 | 메뉴 이미지 업로드 (남의 메뉴) | 가능 |
| STORE-013 | store-menus:complete (메뉴 이미지 누락) | 가능 |
| STORE-014 | 메뉴 이미지 업로드 (이미 있음) | 가능 |
| STORE-015 | POST /v1/owner/store:complete (요건 미충족) | 가능 |
| STORE-016 | GET /v1/stores/{storeId} (없음) | 가능 |
| TERMS-001 | PUT /v1/owner/terms-agreements (이미 기록) | 가능 |
