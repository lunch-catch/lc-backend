# 마이그레이션 규약

`src/main/resources/db/migration` 아래의 SQL이 스키마를 소유한다. Hibernate는 `ddl-auto: validate`로 어긋나면 기동을 막는 역할만 한다.

원본은 드라이브 ERD 폴더의 `전체_flyway.sql`이다. 그 파일은 저장소 밖에 있고, 각 도메인 폴더의 SQL이 그 장을 그대로 옮긴 것이다. 아래 규약은 그 파일의 머리 주석을 저장소 안으로 옮긴 것이고, 지금 들어 있는 SQL 63개 테이블을 세어 확인한 것이다.

## 파일 배치

| 항목 | 규칙 |
|---|---|
| 위치 | `db/migration/{도메인}/V{n}__{설명}.sql` |
| 버전 | 도메인마다 1부터 붙인다. 폴더가 다르면 번호가 겹쳐도 되고, 같은 폴더 안에서는 겹치면 안 된다 |
| 적용 | `global.config.FlywayConfig`가 폴더마다 Flyway를 따로 돌리고 이력도 `flyway_history_{도메인}`으로 따로 둔다 |
| 범위 | 한 파일은 자기 도메인 테이블만 만들고 바꾼다 |
| 폴더 밖 | `db/migration` 바로 아래에 SQL을 두면 어느 이력에도 속하지 않아 적용되지 않는다. 기동 때 막는다 |

새 도메인 폴더를 만들면 그것만으로 끝난다. 도메인 목록을 코드에 적어 두지 않고 폴더를 훑어 만든다. 적어 두면 폴더를 추가한 사람이 그 목록도 함께 고쳐야 하고, 잊으면 그 도메인의 테이블만 없는 채로 앱이 기동한다.

## 공통 규칙

| 항목 | 규칙 | 지금 상태 |
|---|---|---|
| 소프트 참조 | 도메인 경계를 넘는 참조는 FK가 아니라 ID 값이다. FK는 같은 도메인 안에서만 쓴다 | FK 29개 전부 같은 도메인 안 |
| CHECK | enum이 가질 수 있는 값과 상태별 필수 열을 DB에서 고정한다. 이름은 `chk_{테이블}_{무엇}` | CHECK 149개 |
| 유일 키 | `uk_{테이블}_{열}`. 보조 인덱스는 `idx_`, FK 제약은 `fk_` | UNIQUE 53개 |
| 시각 | `DATETIME(6)`. 영업일은 `DATE`, 하루 시간표는 `TIME`. 세션 시간대는 `Asia/Seoul`로 고정한다(`application.yml`) | 전부 |
| 생성과 수정 | 모든 테이블에 `created_at`, `updated_at`을 `NOT NULL DATETIME(6)`으로 둔다 | 63개 테이블 전부 |
| 엔진과 콜레이션 | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` | 63개 테이블 전부 |
| 대상 | MySQL 8.4 | |

CHECK를 두지 않는 자리도 있다. 값이 자주 늘어나는 열에 CHECK를 걸면 값 하나를 더할 때마다 테이블 복사가 일어난다. `impression_log`의 머리 주석이 그 경우를 적어 두었다.

## 적용 방식

폴더마다 Flyway 를 따로 돌린다. `global.config.FlywayConfig` 와 `DomainFlywayMigrator` 가 이것을 한다. 왜 도메인별로 나누는지는 설계 문서 1.3절에 있다. 돌려 보고 확인한 함정이 셋이다.

* **`baselineOnMigrate` 가 필요하다.** 두 번째 도메인부터는 앞 도메인이 만든 테이블 때문에 스키마가 비어 있지 않다. 그 상태에서 자기 이력 테이블이 없으면 Flyway 가 기동을 거부한다
* **`baselineVersion` 을 0 으로 내려야 한다.** 기본값이 1 이라 그냥 켜면 각 도메인의 `V1` 이 이미 적용된 것으로 간주되어 건너뛰어진다. 실제로 11개 중 1개만 적용되고 앱은 아무 오류 없이 떴다
* **`ApplicationRunner` 로 두면 늦다.** 그것은 컨텍스트가 다 뜬 뒤에 돌아서 Hibernate 의 `ddl-auto: validate` 보다 뒤다. 빈 DB 의 첫 기동이 테이블이 없다는 이유로 깨진다. Spring Boot 가 자기 Flyway 에 쓰는 것과 같은 후처리기로 엔티티 매니저가 마이그레이션을 기다리게 한다

```java
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    static final String MIGRATOR_BEAN = "domainFlywayMigrator";

    @Bean(MIGRATOR_BEAN)
    public DomainFlywayMigrator domainFlywayMigrator(DataSource dataSource) {
        return new DomainFlywayMigrator(dataSource);
    }

    // 엔티티 매니저가 위 빈을 기다리게 한다
    @Configuration(proxyBeanMethods = false)
    static class JpaDependency extends EntityManagerFactoryDependsOnPostProcessor {

        JpaDependency() {
            super(MIGRATOR_BEAN);
        }
    }
}

// DomainFlywayMigrator 는 InitializingBean 이고, 폴더마다 아래를 돌린다
Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/" + domain)
        .table("flyway_history_" + domain)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate();
```

폴더 밖에 둔 SQL 은 어느 이력에도 속하지 않아 영원히 적용되지 않는데 앱은 정상 기동한다. 그래서 기동 때 그 파일을 찾아 막는다.
