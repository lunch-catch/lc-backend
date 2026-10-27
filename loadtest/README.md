# 부하 시험

부하 생성기가 받아 쓰는 시나리오, 토큰 서명, 데이터 생성기를 둔다. 도메인마다 무엇을 어떤 조건으로 재는지는 그 도메인의 부하 시험 문서가 갖는다.

| 도메인 | 문서 |
|---|---|
| 광고 서빙 | [`docs/design/adserving/loadtest/adserving_부하_시험.md`](../docs/design/adserving/loadtest/adserving_부하_시험.md) |

부하 시험 환경을 띄우고 내리는 일은 lc-infra 가 갖는다(`docs/wiki/팀원-부하시험-환경.md`, `scripts/member-env.sh`).

## 디렉터리

| 경로 | 하는 일 |
|---|---|
| `lib/` | 여러 도메인이 함께 쓰는 것. `token.js` 가 회원과 관리자의 Access Token 을 `JWT_SECRET` 으로 서명한다 |
| `data/` | 도메인마다 시험 데이터 SQL 을 만드는 생성기 |
| `{도메인}/` | 그 도메인의 k6 시나리오 |
| `aws/` | lc-infra 로 띄운 환경에서 데이터 넣기, 회차 실행, 지표 모으기를 돌리는 스크립트 |

## 공통 규칙

- 앱은 `loadtest` 프로필로 띄운다. 결제가 토스 대신 목을 쓴다.
- 생성기의 SQL 은 부하 시험 DB 와 로컬에만 넣는다. 정해진 번호 대역의 행과 그날 배치 기록을 지우고 다시 넣는다.
- 버전을 비교하려면 측정한 커밋과 조건을 함께 남긴다. 결과는 `docs/troubleshooting/{도메인}/` 에 버전마다 둔다.
