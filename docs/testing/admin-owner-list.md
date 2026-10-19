# 관리자 점주 목록의 로컬 검증

#536의 서비스 분기는 `AdminOwnerServiceTest`, HTTP 권한과 응답 마스킹은 `AdminOwnerIntegrationTest`가 확인한다. 전체 `./gradlew check`와 통합 테스트의 재실행은 사용자가 로컬에서 진행한다.

## 서비스 단위 테스트와 커버리지 확인

```powershell
.\gradlew.bat test --tests com.launchcatch.owner.service.AdminOwnerServiceTest
.\gradlew.bat jacocoTestReport -x test
```

두 번째 명령은 테스트를 다시 실행하지 않고 방금 실행한 테스트의 커버리지 보고서만 만든다. 테스트 결과는 `build/test-results/test/TEST-com.launchcatch.owner.service.AdminOwnerServiceTest.xml`, 커버리지 보고서는 `build/reports/jacoco/test/jacocoTestReport.xml`에서 확인한다.

2026-10-19에 위 두 명령을 실행했다. 단위 테스트 10개가 모두 통과했으며 `AdminOwnerService`의 메서드는 3/3, 분기는 30/30, 라인은 29/29가 커버됐다. 실행한 범위에는 전체 check와 통합 테스트가 포함되지 않는다.

앞선 전체 검사에서 해당 서비스의 커버리지가 0%로 보고됐을 때 새 단위 테스트의 결과 XML은 결과 폴더에 없었다. 해당 테스트만 다시 실행한 결과 100% 기록이 생성됐다. 이전 실행에서 테스트가 누락된 이유는 확인하지 못했으므로 런타임 코드나 커버리지 기준을 변경하지 않았다.

테스트를 지정해 실행하면 `build/jacoco/test.exec`에는 지정한 테스트의 기록만 남는다. 이 상태에서 전체 클래스의 커버리지 검증만 별도로 실행하면 다른 서비스가 0%로 나올 수 있다. 전체 커버리지는 테스트 필터 없이 전체 테스트를 실행한 데이터로 판정해야 한다.

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
