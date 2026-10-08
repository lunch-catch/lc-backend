# lc-backend

## 서비스

점주(`owner`)가 캠페인(`campaign`)을 등록하고 LLM을 통해 쿠폰 전단지를 자동 생성한 후 포인트를 충전하면, 광고 쿠폰 피드가 회원(`member`)에게 서빙(`adserving`)되고 회원이 쿠폰 조회시 해당 노출을 기준으로 포인트가 과금(`billing`)된다. 이후 선착순 쿠폰 이벤트가 시작되면 회원은 쿠폰(`coupon`)을 발급받고, 쿠폰함에 생성된 동적 QR을 가게(`store`)에서 점주가 스캔하여 쿠폰 사용을 처리한다.

## 기술 스택

* 언어: Java 21 (`eclipse-temurin:21.0.9`)
* 빌드: Gradle 8.14.3 (wrapper)
* 프레임워크: Spring Boot 4.0.5
* 데이터베이스: MySQL 8.4, Valkey 9.0
* 데이터 접근: Spring Data JPA, QueryDSL, Flyway
* 인증: Spring Security, OAuth2 Client, JJWT 0.12.7
* API 문서: springdoc-openapi 3.0.3
* 복원력: resilience4j 2.4.0 (`spring-boot4`), 2.3.0 (`circuitbreaker`)
* 관측: Micrometer Prometheus, logstash-logback-encoder 8.0
* 테스트: JUnit 5, Testcontainers, ArchUnit 1.4.1, JaCoCo 0.8.15
* 정적 분석: SonarQube Gradle 플러그인 7.5.0.8588

## 구조

크게 3계층이고 아래 계층은 위를 의존하지 않는다.

| 계층 | 역할 | 규칙 |
|---|---|---|
| 업무 도메인 10개 | 기능과 데이터를 소유한다 | 서로의 `contract` 만 참조한다 |
| `auth`, `ops` | 모든 도메인이 쓰는 하위 모듈 | 업무 도메인을 의존하지 않는다 |
| `global` | 예외, 응답 포맷, 거리 계산 | 아무것도 의존하지 않는다 |

도메인 내 패키지 구조는 `contract/`(public interface, record, enum)와 서브 도메인 혹은 애그리거트(계층 구조)로 구성되고, 도메인 경계를 넘는 참조는 FK 가 아니라 ID 값이다.

| 도메인 | 소유 | 담당 |
|---|---|---|
| `campaign`(캠페인) | 캠페인, 상태 이력, 가게별 대상 인원, 포스터와 검수 결과, 템플릿과 버전 | @yongmaru789 |
| `adserving`(광고 서빙) | 일일 광고 피드 후보, serve 기록, 노출 로그, 스와이프 이력, 찜 목록, 광고 피드 필터 | @devjohnpark |
| `coupon`(쿠폰) | 발급 회차, 하루 발급 한도, 발급된 쿠폰(순번, QR, 사용 이력) | @MinhyeokChoi99 |
| `billing`(정산) | 포인트 원장, 점주 잔액, 결제와 환불, 그날 예약액, 단가 스냅샷, 노출 차감 판정 | @muzimzz |
| `store`(가게) | 가게, 입점 신청, 사업자 검증, 이미지와 메뉴, 그날의 캠페인 요약 | @gyudongjeong |
| `member`(회원) | 회원 계정, 프로필, 저장 위치, 동의 설정 | @muzimzz |
| `owner`(점주) | 점주 계정과 상태 | @gyudongjeong |
| `admin`(관리자) | 관리자 계정 | @gyudongjeong |
| `notification`(알림) | 알림 발송 이력, 푸시 등록 정보 | @gyudongjeong |
| `analytics`(분석) | 전환 퍼널 이벤트 로그, 일별 집계, 캠페인 리포트 | @muzimzz |
| `auth`(공용 인증) | 토큰 발급과 검증, 회전 정책, 역할 | @muzimzz @gyudongjeong |
| `ops`(운영) | 플랫폼 설정값, 스케줄러와 실행 이력, 감사 로그 | @yongmaru789 |
| `global`(기술 공통) | 예외, 응답 포맷, 거리 계산 | @devjohnpark |

소유는 설계 문서 1.2절의 소유 데이터와 같다. 관리자 화면이 점주나 회원 데이터를 다루는 기능은 그 데이터의 도메인이 소유하므로 `admin` 에는 계정만 남는다.

## 문서

| 문서 | 내용 |
|---|---|
| [도메인 구조와 의존성 설계](./docs/architecture/런치캐치_도메인_구조와_의존성_설계.md) | 계층, 도메인 경계, 의존 방향 |
| [API 명세](./docs/api-spec/README.md) | 공통 규약과 도메인별 명세 |
| [마이그레이션 규약](./src/main/resources/db/migration/README.md) | 스키마를 소유하는 SQL 의 배치와 규칙 |
| [코드 리뷰 기준](./docs/CODEREVIEW.md) | 변경 경로별로 적용되는 가이드 |
| [작업 규칙](./AGENTS.md) | 이슈와 PR, 커밋, 문서와 주석 |
| [개발 흐름](./docs/workflow/README.md) | 브랜치 전략, 보호 설정, 게이트, 배포 경로 |
| [로컬 실행](./docs/workflow/로컬_실행.md) | 설정 파일 만들기, 실행, 막혔을 때 보는 곳 |
| [배치 운영](./docs/ops/런치캐치_배치_운영.md) | 배치 서버 구성과 운영 규칙 |
| [알림 운영](./docs/ops/런치캐치_알림_운영.md) | Slack 알림 구성과 설정 절차 |

## 시작

```bash
cp application-local.yml.example application-local.yml   # 값을 채운다
./gradlew bootRun
curl http://localhost:8081/actuator/health
```

**설정 파일(`application-local.yml`)을 만들지 않으면 애플리케이션이 뜨지 않는다.** 매핑할 값과 막혔을 때 참고할 문서는 [로컬 실행](./docs/workflow/로컬_실행.md)에 있다.

| 대상 | 주소 |
|---|---|
| API | `http://localhost:8080` |
| 액추에이터 | `http://localhost:8081/actuator/health` |
| API 문서 | `http://localhost:8080/swagger-ui.html` |
