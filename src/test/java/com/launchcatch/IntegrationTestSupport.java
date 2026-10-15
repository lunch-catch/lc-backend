package com.launchcatch;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 통합 테스트가 함께 쓰는 MySQL 과 Valkey 다. 통합 테스트는 이 클래스를 상속한다.
 *
 * JVM 당 한 번만 띄운다. @Container 를 쓰지 않는 이유가 그것이다. 그 애너테이션은 수명을
 * 테스트 클래스에 묶어서, 클래스마다 기동하고 내리는 일이 반복된다. 러너가 2 vCPU 7GB 인데
 * 앞 컨테이너가 다 내려가기 전에 다음이 올라오는 구간이 생기면 메모리와 포트가 빠듯해지고,
 * MySQL 이 Connection refused 로 떨어진다. 같은 커밋이 한 번은 통과하고 한 번은 실패했다.
 *
 * 정리는 Ryuk 에 맡긴다. stop 을 부르지 않아도 JVM 이 끝나면 Testcontainers 가 치운다.
 *
 * 이미지는 compose.yaml 과 인프라에 맞춘다. 운영 ElastiCache 가 Valkey 9.0 이라 9.0-alpine 으로
 * 고정한다. 떠다니는 valkey:9 로 두면 지금 9.1 을 받아서 테스트가 운영보다 앞선 서버로 돈다.
 *
 * Valkey 가 필요 없는 테스트도 함께 띄운다. 분기를 두면 어느 테스트가 무엇을 쓰는지를
 * 상속 계층으로 관리해야 한다. JVM 당 한 번이라 그 비용은 한 번뿐이다.
 *
 * 컨테이너를 protected 로 두는 것은 Valkey 에 직접 명령을 보내거나 포트를 끊어 보는 테스트가 있어서다.
 */
public abstract class IntegrationTestSupport {

    protected static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    protected static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.0-alpine")).withExposedPorts(6379);

    static {
        MYSQL.start();
        VALKEY.start();
    }

    /*
     * jwt.secret 은 테스트 전용 값이다. 아홉 테스트가 같은 값을 각자 적고 있었다.
     * 이 값으로 토큰을 만들 수 있는 것은 이 소스셋뿐이다.
     *
     * jwt.cookie.secure 는 운영 기본값이 false 이고 HTTPS 를 쓰는 운영에서 true 로 올린다.
     * 테스트는 운영에 가까운 쪽을 기본으로 삼는다. 쿠키의 Secure 를 보는 테스트가 같은 값을
     * 각자 properties 로 적으면 그 속성이 컨텍스트 캐시 키를 갈라 컨텍스트가 그만큼 늘어난다.
     */
    @DynamicPropertySource
    static void 컨테이너_주소를_등록한다(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("jwt.secret", () -> "test-only-secret-not-used-anywhere-else-0123456789abcdef");
        registry.add("jwt.cookie.secure", () -> "true");
        /*
         * 테스트도 기본 프로필이 local 이라 시드가 켜진다.
         * 시드 행이 테스트 데이터와 섞이지 않게 끈다.
         */
        registry.add("seed.enabled", () -> "false");
    }
}
