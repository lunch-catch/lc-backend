package com.launchcatch.ops;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.ops.scheduler.StandaloneJob;
import java.time.LocalDate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/*
 * ops 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. 컨텍스트 캐시 키는 빈 오버라이드와 설정의
 * 집합으로 갈리므로 그 집합을 여기 모은다. 지금은 테스트용 묶음 밖 작업(TestStandaloneJobs, #559)만
 * 더한다. 생기면 여기에 더한다.
 *
 * @AutoConfigureMockMvc 는 배치 수동 재실행(#362)이 ops 의 첫 HTTP 입구라 더했다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractOpsIntegrationTest.TestStandaloneJobs.class)
public abstract class AbstractOpsIntegrationTest extends IntegrationTestSupport {

    /*
     * 이어받기와 수동 재실행의 묶음 밖 작업 경로를 보려고 등록하는 작업이다(#559).
     * 운영 코드에는 runCounting 을 재정의한 작업이 아직 없어 테스트가 직접 등록한다.
     */
    @TestConfiguration
    static class TestStandaloneJobs {

        static final String COUNTED = "OPS_TEST_COUNTED";
        static final String UNCOUNTED = "OPS_TEST_UNCOUNTED";
        static final int COUNT = 5;

        @Bean
        StandaloneJob countedTestJob() {
            return new StandaloneJob() {
                @Override
                public String jobName() {
                    return COUNTED;
                }

                @Override
                public void run(LocalDate businessDate, int attemptNo) {
                    runCounting(businessDate, attemptNo);
                }

                @Override
                public Integer runCounting(LocalDate businessDate, int attemptNo) {
                    return COUNT;
                }
            };
        }

        @Bean
        StandaloneJob uncountedTestJob() {
            return new StandaloneJob() {
                @Override
                public String jobName() {
                    return UNCOUNTED;
                }

                @Override
                public void run(LocalDate businessDate, int attemptNo) {
                    // 건수 없이 끝나는 작업이다
                }
            };
        }
    }
}
