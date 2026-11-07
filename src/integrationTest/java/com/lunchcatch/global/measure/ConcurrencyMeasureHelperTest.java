package com.lunchcatch.global.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * 동시성 측정 보조 클래스(#1090)가 약속한 대로 도는지 본다. 컨텍스트를 띄우지 않아 몇 초 안에 끝난다.
 * 시나리오 테스트가 이 클래스들의 결과를 믿고 쓰므로, 시나리오를 쓰기 전에 보조 클래스 자체를 먼저 확인한다.
 */
class ConcurrencyMeasureHelperTest {

    @Test
    void 환경변수가_없으면_기본값을_쓴다() {
        MeasureSettings settings = MeasureSettings.from(name -> null);

        assertThat(settings.rounds()).isEqualTo(5);
        assertThat(settings.delaysMillis()).containsExactly(0L, 100L, 500L);
    }

    @Test
    void 환경변수로_반복_횟수와_지연을_바꾼다() {
        Map<String, String> environment = Map.of(
                MeasureSettings.ROUNDS_ENV, " 200 ",
                MeasureSettings.DELAYS_ENV, "0, 100,3000");

        MeasureSettings settings = MeasureSettings.from(environment::get);

        assertThat(settings.rounds()).isEqualTo(200);
        assertThat(settings.delaysMillis()).containsExactly(0L, 100L, 3000L);
    }

    @Test
    void 잘못된_환경변수는_기본값으로_대신하지_않고_예외를_던진다() {
        assertThatThrownBy(() -> MeasureSettings.from(Map.of(MeasureSettings.ROUNDS_ENV, "abc")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeasureSettings.from(Map.of(MeasureSettings.ROUNDS_ENV, "0")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeasureSettings.from(Map.of(MeasureSettings.DELAYS_ENV, "100,x")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeasureSettings.from(Map.of(MeasureSettings.DELAYS_ENV, "5001")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /*
     * 장벽은 두 스레드가 동시에 와야 열린다. 한 동작이 끝난 뒤 다른 동작이 시작하는 구조라면
     * 장벽이 시간 초과로 깨져 둘 다 실패한다.
     */
    @Test
    void 동작들은_서로_기다리지_않고_함께_실행된다() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<String> action = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return "함께 실행됐다";
        };

        List<RaceRunner.Outcome<String>> outcomes = RaceRunner.race(List.of(action, action), 50, new Random(1));

        assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
    }

    @Test
    void 첫_동작은_바로_시작하고_나머지는_지연_범위_안에서_시작한다() {
        Callable<Integer> action = () -> 1;

        List<RaceRunner.Outcome<Integer>> outcomes =
                RaceRunner.race(List.of(action, action, action), 30, new Random(MeasureSettings.SEED));

        long jitterNanos = TimeUnit.MILLISECONDS.toNanos(30);
        // 스레드 스케줄링 지연을 허용하려고 상한에 2초를 더한다.
        long slackNanos = TimeUnit.SECONDS.toNanos(2);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.startOffsetNanos()).isNotNegative());
        assertThat(outcomes.subList(1, 3)).allSatisfy(
                outcome -> assertThat(outcome.startOffsetNanos()).isLessThan(jitterNanos + slackNanos));
    }

    @Test
    void 지연이_0이면_모든_동작이_바로_시작한다() {
        Callable<Integer> action = () -> 1;

        List<RaceRunner.Outcome<Integer>> outcomes = RaceRunner.race(List.of(action, action), 0, new Random(1));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.value()).isEqualTo(1));
    }

    @Test
    void 동작이_던진_예외는_삼키지_않고_결과에_담는다() {
        IllegalStateException failure = new IllegalStateException("실패");
        Callable<String> fails = () -> {
            throw failure;
        };
        Callable<String> succeeds = () -> "성공";

        List<RaceRunner.Outcome<String>> outcomes = RaceRunner.race(List.of(fails, succeeds), 0, new Random(1));

        assertThat(outcomes.get(0).succeeded()).isFalse();
        assertThat(outcomes.get(0).failure()).isSameAs(failure);
        assertThat(outcomes.get(1).value()).isEqualTo("성공");
    }

    @Test
    void 위반을_불변식별로_세고_위반이_있으면_단언이_실패한다() {
        ScenarioMeasure measure = new ScenarioMeasure("sample");
        ScenarioMeasure.Condition condition = measure.condition("지연 100ms", "X-1", "X-2");
        for (int i = 1; i <= 4; i++) {
            ScenarioMeasure.Condition.Round round = condition.startRound();
            round.check("X-1", true, "항상 참");
            round.check("X-2", i % 2 == 0, "짝수 회차가 아니다");
        }

        assertThat(condition.rounds()).isEqualTo(4);
        assertThat(condition.violations("X-1")).isZero();
        assertThat(condition.violations("X-2")).isEqualTo(2);
        assertThatThrownBy(measure::assertNoViolations)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("X-2")
                .hasMessageContaining("회차 1")
                .hasMessageContaining("지연 100ms");
    }

    @Test
    void 위반이_없으면_단언이_통과하고_알리지_않은_번호도_표에_더해진다() {
        ScenarioMeasure measure = new ScenarioMeasure("sample");
        ScenarioMeasure.Condition condition = measure.condition("기본");
        condition.startRound().check("Y-1", true, "참");

        measure.assertNoViolations();

        assertThat(measure.summaryTable()).contains("| Y-1 | 0 |");
    }

    @Test
    void 횟수와_표본은_기록만_하고_단언하지_않는다() {
        ScenarioMeasure measure = new ScenarioMeasure("sample");
        ScenarioMeasure.Condition condition = measure.condition("기본", "Z-1");
        for (int i = 1; i <= 20; i++) {
            ScenarioMeasure.Condition.Round round = condition.startRound();
            round.check("Z-1", true, "참");
            round.count("duplicateModeration", i % 2);
            round.sample("waitMillis", i);
        }

        measure.assertNoViolations();

        assertThat(condition.counter("duplicateModeration")).isEqualTo(10);
        // 1..20 의 가장 가까운 순위 백분위다. p50 은 10번째, p95 는 19번째 값이다.
        assertThat(measure.summaryTable()).contains("| waitMillis | 20 | 1.00 | 10.50 | 10.00 | 19.00 | 20.00 |");
    }

    @Test
    void 결과를_시나리오_이름의_JSON_으로_쓴다(@TempDir Path directory) throws IOException {
        ScenarioMeasure measure = new ScenarioMeasure("sample-json");
        ScenarioMeasure.Condition condition = measure.condition("지연 0ms", "W-1");
        ScenarioMeasure.Condition.Round round = condition.startRound();
        round.check("W-1", false, "어긋났다");
        round.count("duplicateModeration");
        round.sample("waitMillis", 7);

        Path file = measure.writeJson(directory);

        assertThat(file.getFileName().toString()).isEqualTo("sample-json.json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(file));
        assertThat(root.get("scenario").asText()).isEqualTo("sample-json");
        assertThat(root.get("seed").asLong()).isEqualTo(MeasureSettings.SEED);
        JsonNode first = root.get("conditions").get(0);
        assertThat(first.get("label").asText()).isEqualTo("지연 0ms");
        assertThat(first.get("rounds").asInt()).isEqualTo(1);
        assertThat(first.get("invariants").get("W-1").get("violations").asInt()).isEqualTo(1);
        assertThat(first.get("counters").get("duplicateModeration").asLong()).isEqualTo(1);
        assertThat(first.get("samples").get("waitMillis").get("max").asDouble()).isEqualTo(7.0);
    }
}
