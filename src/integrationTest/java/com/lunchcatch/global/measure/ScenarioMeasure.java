package com.lunchcatch.global.measure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/*
 * 동시성 시나리오 하나의 회차별 결과를 모아 요약 표와 JSON 으로 내보낸다(#1090).
 *
 * 한 시나리오가 조건(스텁 지연 등)을 여러 개 가질 수 있어서 조건마다 따로 센다. 조건 하나에서 회차마다
 * 불변식 판정(check), 횟수(count), 수치 표본(sample)을 기록한다.
 *
 * 단언은 불변식에만 건다. check 가 거짓이어도 그 자리에서 실패시키지 않고 위반으로 세어 두고, 모든 회차를
 * 돈 뒤 assertNoViolations 가 한 번에 실패시킨다. 한 번 어긋났다고 멈추면 위반이 몇 회차에서 났는지
 * 알 수 없다. count 와 sample 은 기록만 하고 단언하지 않는다.
 */
public final class ScenarioMeasure {

    private static final int DETAILS_KEPT = 3;
    private static final double P50 = 0.50;
    private static final double P95 = 0.95;

    private final String scenario;
    private final List<Condition> conditions = new ArrayList<>();

    public ScenarioMeasure(String scenario) {
        this.scenario = scenario;
    }

    /*
     * 조건 하나를 연다. 불변식 번호를 미리 알려 두면 위반이 0건이어도 표에 나온다.
     * 알리지 않은 번호를 check 로 기록하면 그때부터 표에 더해진다.
     */
    public Condition condition(String label, String... invariantIds) {
        Condition condition = new Condition(label, invariantIds);
        conditions.add(condition);
        return condition;
    }

    public List<Condition> conditions() {
        return List.copyOf(conditions);
    }

    public int totalViolations() {
        return conditions.stream().mapToInt(Condition::violations).sum();
    }

    public void assertNoViolations() {
        if (totalViolations() == 0) {
            return;
        }
        StringBuilder message = new StringBuilder(scenario + " 불변식 위반 " + totalViolations() + "건");
        for (Condition condition : conditions) {
            condition.invariants.forEach((id, tally) -> {
                if (tally.count > 0) {
                    message.append("\n  [").append(condition.label).append("] ").append(id).append(": ")
                            .append(tally.count).append("건, ").append(String.join(" / ", tally.details));
                }
            });
        }
        throw new AssertionError(message.toString());
    }

    // 요약 표를 표준 출력에 내고 JSON 을 쓴 뒤 불변식을 단언한다. 시나리오 테스트의 마지막에 한 번 부른다.
    public void finish() {
        System.out.println(summaryTable());
        writeJson(Path.of("build", "measure"));
        assertNoViolations();
    }

    public String summaryTable() {
        List<String> lines = new ArrayList<>();
        lines.add("## " + scenario);
        for (Condition condition : conditions) {
            lines.add("");
            lines.add("### " + condition.label + " (N=" + condition.rounds + ")");
            lines.add("");
            lines.add("| 불변식 | 위반 |");
            lines.add("|---|---|");
            condition.invariants.forEach((id, tally) -> lines.add("| " + id + " | " + tally.count + " |"));
            if (!condition.counters.isEmpty()) {
                lines.add("");
                lines.add("| 횟수 | 값 |");
                lines.add("|---|---|");
                condition.counters.forEach((name, value) -> lines.add("| " + name + " | " + value + " |"));
            }
            if (!condition.samples.isEmpty()) {
                lines.add("");
                lines.add("| 표본 | 개수 | 최소 | 평균 | p50 | p95 | 최대 |");
                lines.add("|---|---|---|---|---|---|---|");
                condition.samples.forEach((name, values) -> {
                    Stats stats = Stats.of(values);
                    lines.add(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f | %.2f | %.2f |",
                            name, stats.count, stats.min, stats.mean, stats.p50, stats.p95, stats.max));
                });
            }
        }
        return String.join("\n", lines) + "\n";
    }

    public Path writeJson(Path directory) {
        Path file = directory.resolve(scenario + ".json");
        try {
            Files.createDirectories(directory);
            Files.writeString(file,
                    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(toMap()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("측정 결과를 쓰지 못했다: " + file, e);
        }
        return file;
    }

    private Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", scenario);
        root.put("seed", MeasureSettings.SEED);
        root.put("conditions", conditions.stream().map(Condition::toMap).toList());
        return root;
    }

    public final class Condition {

        private final String label;
        private final Map<String, Tally> invariants = new LinkedHashMap<>();
        private final Map<String, Long> counters = new TreeMap<>();
        private final Map<String, List<Double>> samples = new TreeMap<>();
        private int rounds;

        private Condition(String label, String[] invariantIds) {
            this.label = label;
            Arrays.stream(invariantIds).forEach(id -> invariants.put(id, new Tally()));
        }

        public synchronized Round startRound() {
            rounds++;
            return new Round(rounds);
        }

        public synchronized int rounds() {
            return rounds;
        }

        public synchronized int violations() {
            return invariants.values().stream().mapToInt(tally -> tally.count).sum();
        }

        public synchronized int violations(String invariantId) {
            Tally tally = invariants.get(invariantId);
            return tally == null ? 0 : tally.count;
        }

        public synchronized long counter(String name) {
            return counters.getOrDefault(name, 0L);
        }

        private synchronized Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("label", label);
            map.put("rounds", rounds);
            Map<String, Object> invariantMap = new LinkedHashMap<>();
            invariants.forEach((id, tally) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("violations", tally.count);
                entry.put("details", tally.details);
                invariantMap.put(id, entry);
            });
            map.put("invariants", invariantMap);
            map.put("counters", new LinkedHashMap<>(counters));
            Map<String, Object> sampleMap = new LinkedHashMap<>();
            samples.forEach((name, values) -> sampleMap.put(name, Stats.of(values).toMap()));
            map.put("samples", sampleMap);
            return map;
        }

        public final class Round {

            private final int index;

            private Round(int index) {
                this.index = index;
            }

            // 불변식이 이 회차에서 참인지 기록한다. 거짓이면 위반으로 세고 설명을 앞의 몇 건만 남긴다.
            public void check(String invariantId, boolean holds, String detail) {
                synchronized (Condition.this) {
                    Tally tally = invariants.computeIfAbsent(invariantId, id -> new Tally());
                    if (!holds) {
                        tally.count++;
                        if (tally.details.size() < DETAILS_KEPT) {
                            tally.details.add("회차 " + index + ": " + detail);
                        }
                    }
                }
            }

            public void count(String name) {
                count(name, 1);
            }

            public void count(String name, long amount) {
                synchronized (Condition.this) {
                    counters.merge(name, amount, Long::sum);
                }
            }

            public void sample(String name, double value) {
                synchronized (Condition.this) {
                    samples.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
                }
            }
        }
    }

    private static final class Tally {

        private int count;
        private final List<String> details = new ArrayList<>();
    }

    private record Stats(int count, double min, double mean, double p50, double p95, double max) {

        static Stats of(List<Double> values) {
            double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            if (sorted.length == 0) {
                return new Stats(0, 0, 0, 0, 0, 0);
            }
            return new Stats(sorted.length, sorted[0], Arrays.stream(sorted).average().orElse(0),
                    percentile(sorted, P50), percentile(sorted, P95), sorted[sorted.length - 1]);
        }

        // 가장 가까운 순위 방식이다.
        private static double percentile(double[] sorted, double fraction) {
            return sorted[(int) Math.ceil(fraction * sorted.length) - 1];
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("count", count);
            map.put("min", min);
            map.put("mean", mean);
            map.put("p50", p50);
            map.put("p95", p95);
            map.put("max", max);
            return map;
        }
    }
}
