package com.launchcatch.ops.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.ops.scheduler.BatchJobRegistry.Kind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 멈춘 실행의 이름을 묶음, 단계, 묶음 밖 작업, 알 수 없음으로 가르는 판정과 기동 시점의 이름 검증을 본다(#585).
 *
 * 여기서 막는 사고는 단계와 묶음 밖 작업의 이름이 겹친 채 기동되는 것이다. 그러면 이어받기에서 단계로
 * 먼저 분류되어 그 묶음 밖 작업은 조용히 다시 돌지 않는다.
 */
class BatchJobRegistryTest {

    private static final String STEP = "RESERVE_DAILY_BUDGET";
    private static final String STANDALONE = "EVENT_LOG_RETENTION";

    private DailyJob step(String name) {
        return new DailyJob() {
            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate) {
                // 테스트는 이름만 본다
            }
        };
    }

    private StandaloneJob standalone(String name) {
        return new StandaloneJob() {
            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate, int attemptNo) {
                // 테스트는 이름만 본다
            }
        };
    }

    private BatchJobRegistry registry() {
        return new BatchJobRegistry(List.of(step(STEP)), List.of(standalone(STANDALONE)));
    }

    @Test
    @DisplayName("단계 이름과 묶음 밖 작업 이름이 겹치면 기동이 실패한다")
    void 단계와_묶음_밖_작업_이름이_겹치면_실패한다() {
        assertThatThrownBy(() -> new BatchJobRegistry(List.of(step(STEP)), List.of(standalone(STEP))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(STEP);
    }

    @Test
    @DisplayName("묶음 밖 작업끼리 이름이 겹치면 기동이 실패한다")
    void 묶음_밖_작업끼리_이름이_겹치면_실패한다() {
        assertThatThrownBy(() -> new BatchJobRegistry(List.of(),
                List.of(standalone(STANDALONE), standalone(STANDALONE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(STANDALONE);
    }

    @Test
    @DisplayName("이름을 묶음, 단계, 묶음 밖 작업, 알 수 없음으로 가른다")
    void 이름을_네_가지로_가른다() {
        BatchJobRegistry registry = registry();

        assertThat(registry.classify(BatchJobRegistry.BUNDLE)).isEqualTo(Kind.BUNDLE);
        assertThat(registry.classify(STEP)).isEqualTo(Kind.STEP);
        assertThat(registry.classify(STANDALONE)).isEqualTo(Kind.STANDALONE);
        assertThat(registry.classify("MEMBER_KAKAO_UNLINK_RETRY")).isEqualTo(Kind.UNKNOWN);
    }

    /*
     * 단계는 묶음 드라이버로만 다시 돈다. 단계 하나만 따로 다시 돌리면 앞 단계의 결과를 확인하지 않고
     * 뒤 단계가 돌 수 있다(규칙 6).
     */
    @Test
    @DisplayName("다시 실행할 수 있는 것은 묶음과 묶음 밖 작업뿐이다")
    void 묶음과_묶음_밖_작업만_다시_실행할_수_있다() {
        assertThat(Kind.BUNDLE.isRetryable()).isTrue();
        assertThat(Kind.STANDALONE.isRetryable()).isTrue();
        assertThat(Kind.STEP.isRetryable()).isFalse();
        assertThat(Kind.UNKNOWN.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("등록된 묶음 밖 작업을 이름으로 찾는다")
    void 묶음_밖_작업을_이름으로_찾는다() {
        StandaloneJob job = standalone(STANDALONE);
        BatchJobRegistry registry = new BatchJobRegistry(List.of(), List.of(job));

        assertThat(registry.standaloneJob(STANDALONE)).isSameAs(job);
    }

    @Test
    @DisplayName("등록되지 않은 이름으로 묶음 밖 작업을 찾으면 그 이름을 담아 던진다")
    void 등록되지_않은_이름이면_던진다() {
        assertThatThrownBy(() -> registry().standaloneJob(STEP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(STEP);
    }
}
