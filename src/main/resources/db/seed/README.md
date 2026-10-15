# 시드 규약

`db/seed` 아래의 SQL 은 로컬과 개발 서버에만 넣는 시험 데이터다. 스키마(`db/migration`)와 섞지 않는다.

## 언제 도나

| 환경 | `seed.enabled` | 누가 켜나 |
|---|---|---|
| 로컬 (`local` 프로필) | 켜짐 | `application.yml` 의 local 문서 |
| 개발 서버 | 켜짐 | lc-infra 가 `SEED_ENABLED=true` 를 넣는다 |
| 운영 | 꺼짐 | 기본값 |
| 테스트 | 꺼짐 | `IntegrationTestSupport` 가 끈다. 시드 자체는 `SeedIntegrationTest` 가 따로 본다 |

`DomainFlywayMigrator` 가 모든 도메인 마이그레이션을 마친 뒤 한 번 돌린다. 이력은 `flyway_history_seed` 에 따로 남는다.

## 파일

| 규칙 | 이유 |
|---|---|
| `R__{두 자리 순서}_{설명}.sql` | repeatable 이라 내용이 바뀌면 다시 돈다. Flyway 는 설명 순으로 돌리므로 앞 숫자로 순서를 정한다 |
| 여러 번 돌아도 결과가 같게 쓴다 | `INSERT ... ON DUPLICATE KEY UPDATE pk = pk`. 이미 있는 행은 건드리지 않는다 |
| PK 는 9000번대 | 시험하며 만든 행(1번부터)과 부딪히지 않는다 |
| 날짜에 묶인 행은 `${seedBusinessDate}` 를 쓴다 | 오늘(Asia/Seoul)이 들어간다. 날짜가 바뀌면 체크섬이 바뀌어 하루의 첫 기동에 그날 행이 생긴다. `CURDATE()` 를 쓰면 처음 넣은 날에 멈춘다 |
| 계정 비밀번호는 `${seedPasswordHash}` 를 쓴다 | 로컬 기본값은 `lunchcatch1234!` 의 BCrypt 해시이고 저장소에 공개돼 있다. 개발 서버는 인프라가 `SEED_PASSWORD_HASH` 로 다른 값을 넣는다 |
| SQL 안에 `${` 를 그대로 쓰지 않는다 | Flyway 가 자리표시자로 읽어 실패한다. HTML 이나 JSON 을 넣을 때 조심한다 |

## 스키마가 바뀌면

시드도 함께 고친다. 안 고치면 로컬 기동이 시드에서 실패한다. `SeedIntegrationTest` 가 PR 에서 먼저 잡는다.

## 알려진 한계

* 포스터의 이미지 key(`seed/poster-900x.jpg`)에는 실제 객체가 없다. 이미지는 깨져 보인다
* 시드 회원은 카카오 계정이 아니라 로그인할 수 없다. 피드는 각자 카카오로 로그인해 온보딩한 회원으로 본다
* 점주 포인트 잔액은 0 이다. 결제와 포인트 원장은 넣지 않는다
* 피드는 10:00~13:00 에만 카드를 준다. 시드 후보도 그 시간에만 보인다
