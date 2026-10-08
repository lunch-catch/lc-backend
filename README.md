# lc-backend

Lunch Catch 백엔드. Java 21, Spring Boot 4, MySQL 8.4, Valkey 9.

## 시작하기

| 문서 | 내용 |
|---|---|
| [로컬 실행](./docs/local/README.md) | 설정 파일 만들기, 실행, 막혔을 때 보는 곳 |
| [작업 규칙](./AGENTS.md) | 이슈와 PR, 커밋, 문서와 주석 |
| [코드 리뷰 기준](./docs/CODEREVIEW.md) | 변경 경로별로 적용되는 가이드 |

## 설계와 명세

| 문서 | 내용 |
|---|---|
| [도메인 구조와 의존성 설계](./docs/architecture/런치캐치_도메인_구조와_의존성_설계.md) | 도메인 경계와 의존 방향 |
| [배치 운영](./docs/architecture/런치캐치_배치_운영.md) | 배치 서버 구성과 운영 규칙 |
| [알림 운영](./docs/architecture/런치캐치_알림_운영.md) | Slack 알림 구성과 설정 절차 |
| [API 명세](./docs/api-spec/README.md) | 공통 규약과 도메인별 명세 |
| [마이그레이션 규약](./src/main/resources/db/migration/README.md) | 스키마를 소유하는 SQL 의 배치와 규칙 |
