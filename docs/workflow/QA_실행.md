# QA 실행

| 항목 | 내용 |
|---|---|
| 목적 | `docs/scenarios/` 의 시나리오를 QA 에이전트로 로컬에서 실행해 기대 결과와 견준다 |
| 읽는 사람 | 에이전트를 부르는 사람과 에이전트(`.claude/agents/qa.md`) |
| 관련 문서 | `../scenarios/README.md`(시나리오), `로컬_실행.md`(앱 띄우기) |

## 1. 준비

처음 한 번만 한다.

| 준비 | 하는 법 |
|---|---|
| `application-local.yml` | `로컬_실행.md` 2절대로 만든다. 비밀값이라 에이전트가 만들지 않는다 |
| Docker | Docker Desktop 을 켠다 |
| Node 20 이상 | 프론트의 pnpm 이 Node 18 에서 죽는다. 없으면 화면 시나리오는 막힘이다 |
| lc-frontend | 클론해 두고 위치를 알려 준다 |
| Playwright | `scripts/qa/browser-setup.sh` 를 돌린다 |

## 2. 부르기

    QA 돌려라 demo
    QA 돌려라 owner
    O-001~O-026 QA

범위는 `demo`, 문서 이름(`backend`, `owner`, `admin`, `user`), ID 범위 중 하나다. `demo` 97개가 1시간 남짓 걸린다.

앱 시계는 고칠 수 없다. 발급과 사용 시나리오는 11:30 ~ 14:00 에 불러야 돈다.

## 3. 수행 순서

1. **빠진 시나리오를 찾는다.** 범위가 속한 문서마다 아래 목록을 지금 코드에서 뽑고 시나리오 문서에 없는 항목을 찾는다. 찾은 항목은 보고서에만 적고 시나리오 문서는 고치지 않는다.

   | 문서 | 뽑는 목록 |
   |---|---|
   | `owner`, `admin`, `user` | 그 앱 `src/app/router.tsx` 의 경로, 그 앱 `src/api` 가 부르는 API |
   | `backend` | 컨트롤러의 API, `*ErrorCode` 의 코드, `@Scheduled` 와 `DailyJob` 작업 |

2. **환경을 띄운다.**
   - 앱: `CDN_BASE_URL=http://localhost:5174/qa-cdn ./gradlew bootRun`. 이 값이 비면 이미지가 있는 가게 응답이 500 이다.
   - 컨테이너: 다른 체크아웃의 `launchcatch-mysql` 과 이름이 부딪히면 `.env` 에 QA 전용 이름(`lc-qa-mysql`, `lc-qa-valkey`)과 포트(3316, 6389)를 준다.
   - 프론트: lc-frontend `develop` 을 별도 worktree 로 받아 `pnpm dev:user`(5173), `dev:owner`(5174), `dev:admin`(5175)으로 띄운다. 쓰던 체크아웃은 건드리지 않는다.
   - 관리자 앱: 프록시가 없어 `VITE_API_BASE_URL=http://localhost:5175` 로 띄우고, Playwright `context.route` 로 그 요청을 8080 에 넘기면서 Origin 과 Referer 를 지운다.
3. **스냅샷을 뜬다.** `scripts/qa/snapshot.sh save`. 날이 바뀌면 기본 세계 기준일이 지나 `docker compose down -v` 뒤 새로 띄워 다시 뜬다.
4. **묶음마다 돌리고 되돌린다.** 시나리오 문서의 `###` 절 하나가 묶음이다. 절이 끝나면 앱을 멈추고 `scripts/qa/snapshot.sh restore` 뒤 다시 띄운다.
5. **시나리오 하나를 돌린다.** `data-map.md` 와 `data.md` 에서 데이터를 고르고, `backend` 는 curl 로, 앱은 Playwright 로 실행한다. 화면은 기대한 문구가 보일 때까지 기다린 뒤 판정한다.

| 역할 | 로그인 |
|---|---|
| 관리자 | `POST /v1/admin/auth/tokens`. 쿠키 `adminAccessToken` |
| 점주 | `POST /v1/owner/auth/tokens`. 쿠키 `ownerAccessToken` |
| 회원 | `scripts/qa/member-token.sh <memberId>` 의 토큰을 쿠키 `accessToken` 에 넣는다 |

기본 계정 비밀번호는 `lunchcatch1234!` 이고 로그인은 IP 하나당 1분에 10번이다.

## 4. 지키는 것

- `localhost` 만 부른다. 개발 서버와 운영 서버는 되돌릴 수 없어 쓰지 않는다.
- 저장소 파일을 고치지 않는다. 쓰는 곳은 `.qa/` 와 임시 디렉터리뿐이다.
- 시나리오 표가 기대 결과의 기준이다. 응답이나 화면을 실제로 확인한 것만 통과다.
- 카카오, 토스, S3, Bedrock 을 실제로 불러야 하는 단계는 막힘이다.

## 5. 결과

`.qa/report/<범위>-<YYYYMMDD-HHMM>.md` 에 쓰고 실패 화면은 `.qa/report/shots/` 에 둔다. `.qa/` 는 git 이 무시하고 `gradle clean` 에도 남는다.

| 절 | 내용 |
|---|---|
| 빠진 시나리오 | 3절 1단계에서 찾은 항목 |
| 판정 | 시나리오마다 통과, 실패, 막힘과 근거. 실패는 원인별로 묶는다 |
| 손댄 것 | DB 에 직접 넣은 데이터, 절 중간의 되돌리기 |

## 6. 실패를 이슈로 옮기기 전에

실패가 곧 코드 결함은 아니다. 아래 순서로 원인을 가린다.

1. 시나리오 표의 기준 커밋 뒤에 코드가 바뀌었으면 고칠 것은 표다.
2. `docs/api-spec/` 이 정한 동작이면 결함이 아니다.
3. 앱이 만들지 않는 상태를 기본 세계 데이터(`db/base`)가 넣었으면 고칠 것은 데이터다.
4. 서버는 명세대로인데 화면이 잘못 보냈으면 lc-frontend 에 연다.
