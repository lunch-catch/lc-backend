# 관리자 점주 목록의 로컬 검증

#536의 변경은 사용자 요청에 따라 Gradle 검증을 실행하지 않은 상태로 제공한다. 로컬에서 먼저 `./gradlew check`를 실행한다. HTTP 권한과 응답 마스킹은 `AdminOwnerIntegrationTest`, 서비스 분기는 `AdminOwnerServiceTest`가 확인한다.

## 1만 건 성능 측정

로컬 전용 DB와 애플리케이션을 사용한다. 관리자 로그인으로 발급받은 쿠키를 curl 쿠키 파일로 저장해 사용한다. 아래 SQL은 동일한 DB 세션에서 실행하며 이번 측정용 점주와 가게만 만든다.

```sql
SET SESSION cte_max_recursion_depth = 10000;
SET @owner_list_prefix = CONCAT('owner-list-perf-', REPLACE(UUID(), '-', ''));

INSERT INTO owner (email, password_hash, role, status, created_at, updated_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
SELECT CONCAT(@owner_list_prefix, '-', n, '@example.com'), 'local-performance-fixture',
       'OWNER', 'ONBOARDING', '2026-10-01 12:00:00', '2026-10-01 12:00:00'
FROM seq;

INSERT INTO store (owner_id, name, category_code, representative_name, created_at, updated_at)
SELECT owner_id, CONCAT(@owner_list_prefix, '-', owner_id), 'KOREAN', '로컬 성능 검증',
       '2026-10-01 12:00:00', '2026-10-01 12:00:00'
FROM owner
WHERE email LIKE CONCAT(@owner_list_prefix, '%');

SELECT @owner_list_prefix AS keyword;
```

각 요청을 한 번 워밍업한 뒤 여러 번 호출한다. `<측정용 접두어>`는 SQL에서 출력한 keyword로 바꾼다. `<쿠키 파일>`은 관리자 쿠키 파일의 경로다.

```powershell
curl.exe --silent --show-error --output NUL --write-out '%{http_code} %{time_total}\n' --cookie '<쿠키 파일>' 'http://localhost:8080/v1/admin/owners'
curl.exe --silent --show-error --output NUL --write-out '%{http_code} %{time_total}\n' --cookie '<쿠키 파일>' 'http://localhost:8080/v1/admin/owners?keyword=<측정용 접두어>'
curl.exe --silent --show-error --output NUL --write-out '%{http_code} %{time_total}\n' --cookie '<쿠키 파일>' 'http://localhost:8080/v1/admin/owners?status=ONBOARDING&joinedFrom=2026-10-01&joinedTo=2026-10-01&keyword=<측정용 접두어>&page=499'
```

세 요청 모두 HTTP 200이며 `time_total`이 1초 미만인지 확인한다. 상호 검색과 필터를 함께 적용한 응답의 `totalElements`는 10000이어야 하며 마지막 페이지는 20건이어야 한다. 이 측정은 동시 요청 부하나 운영 환경의 백분위수 목표를 대신하지 않는다. 실행 환경과 실제 시간을 PR에 남긴다.

측정 후 같은 SQL 세션에서 이번 접두어에 해당하는 데이터만 지운다.

```sql
DELETE s FROM store s JOIN owner o ON o.owner_id = s.owner_id
WHERE o.email LIKE CONCAT(@owner_list_prefix, '%');
DELETE FROM owner WHERE email LIKE CONCAT(@owner_list_prefix, '%');
```
