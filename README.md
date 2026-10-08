# lc-backend

## 서비스

점주(`owner`)가 캠페인(`campaign`)을 등록하면 광고 쿠폰 피드를 회원(`member`)에게 서빙(`adserving`)하고, 회원이 쿠폰(`coupon`)을 보면 그 노출이 포인트로 과금(`billing`)된다. 이후 선착순 쿠폰 이벤트가 시작되면 회원이 쿠폰을 발급받고, 쿠폰함의 동적 QR을 가게(`store`)에서 점주로부터 스캔받아 사용 처리된다.

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
| `campaign`(캠페인) | 캠페인 등록 4단계와 상태 전이, 포스터와 템플릿 | @yongmaru789 |
| `adserving`(광고 서빙) | 스와이프 광고 피드 배정과 노출 수집, 찜과 패스, 배정 진단 | @devjohnpark |
| `coupon`(쿠폰) | 선착순 발급, 쿠폰함, 동적 QR, 사용 처리 | @MinhyeokChoi99 |
| `billing`(정산) | 포인트 결제, 잔액과 내역, 환불 | @muzimzz |
| `store`(가게) | 점주 입점 흐름, 가게 목록과 검색과 상세 | @gyudongjeong |
| `member`(회원) | 온보딩, 위치 설정, 회원정보, 탈퇴, 동의 | @muzimzz |
| `owner`(점주) | 점주 계정, 튜토리얼 | @gyudongjeong |
| `admin`(관리자) | 관리자 계정, 점주와 회원 관리, 플랫폼 설정값, 감사 로그 | @gyudongjeong |
| `notification`(알림) | 회원 알림, 점주 발송 현황 | @gyudongjeong |
| `analytics`(분석) | 대시보드, 매출, 무효 노출, 리포트, 전환 퍼널 | @muzimzz |
| `auth`(공용 인증) | 로그인과 토큰, 보안 체인 | @muzimzz @gyudongjeong |
| `ops`(운영) | 플랫폼 설정값, 스케줄러, 감사 로그 | @yongmaru789 |
| `global`(기술 공통) | 예외, 응답 포맷, 거리 계산 | @devjohnpark |

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
