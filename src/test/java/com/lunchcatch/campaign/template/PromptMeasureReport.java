package com.lunchcatch.campaign.template;

import static com.lunchcatch.campaign.template.PromptMeasureSamples.CATEGORY_BORDERLINE;
import static com.lunchcatch.campaign.template.PromptMeasureSamples.CATEGORY_HIDDEN_CHARS;
import static com.lunchcatch.campaign.template.PromptMeasureSamples.CATEGORY_INJECTION;
import static com.lunchcatch.campaign.template.PromptMeasureSamples.CATEGORY_NORMAL;
import static com.lunchcatch.campaign.template.PromptMeasureSamples.CATEGORY_PERSONAL_INFO;
import static com.lunchcatch.campaign.template.PromptMeasureSamples.CATEGORY_TOO_SHORT;

import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.template.PromptMeasureSamples.Sample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/*
 * 표본을 검증기에 넣은 결과를 모아 범주별 차단율, 코드 일치율, 정상 오탐률, 미탐과 오탐 목록,
 * 경계 표본의 반응, 처리 시간을 마크다운 표로 쓴다(#1089). 결과 문서에 그대로 옮길 수 있게 하려는 모양이다.
 * 검증기가 CampaignException 이 아닌 예외를 던지면 기록하지 않고 그대로 올려 테스트를 실패시킨다.
 */
final class PromptMeasureReport {

    private static final List<String> BLOCK_CATEGORIES =
            List.of(CATEGORY_HIDDEN_CHARS, CATEGORY_INJECTION, CATEGORY_PERSONAL_INFO, CATEGORY_TOO_SHORT);
    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURED_ROUNDS = 20;
    private static final double P95 = 0.95;
    private static final double NANOS_PER_MICRO = 1_000.0;

    record Outcome(Sample sample, String actualCode) {

        boolean blocked() {
            return actualCode != null;
        }

        boolean codeMatches() {
            return blocked() && sample.expectsBlock() && actualCode.equals(sample.expectedCode());
        }
    }

    record Timing(int samples, int rounds, double meanMicros, double p95Micros, double maxMicros) {
    }

    private final List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
    private volatile Timing timing;

    void record(Sample sample, TemplatePromptValidator validator) {
        String actualCode = null;
        try {
            validator.validate(sample.text());
        } catch (CampaignException e) {
            actualCode = e.getErrorCode().getCode();
        }
        outcomes.add(new Outcome(sample, actualCode));
    }

    void recordTiming(TemplatePromptValidator validator, List<Sample> samples) {
        List<String> texts = samples.stream().map(Sample::text).toList();
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            texts.forEach(text -> validateQuietly(validator, text));
        }
        long[] nanos = new long[MEASURED_ROUNDS * texts.size()];
        int index = 0;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            for (String text : texts) {
                long start = System.nanoTime();
                validateQuietly(validator, text);
                nanos[index++] = System.nanoTime() - start;
            }
        }
        Arrays.sort(nanos);
        double mean = Arrays.stream(nanos).average().orElse(0) / NANOS_PER_MICRO;
        double p95 = nanos[(int) Math.ceil(P95 * nanos.length) - 1] / NANOS_PER_MICRO;
        double max = nanos[nanos.length - 1] / NANOS_PER_MICRO;
        this.timing = new Timing(texts.size(), MEASURED_ROUNDS, mean, p95, max);
    }

    private static void validateQuietly(TemplatePromptValidator validator, String text) {
        try {
            validator.validate(text);
        } catch (CampaignException ignored) {
            // 차단은 시간을 재는 대상이다. 결과는 쓰지 않는다.
        }
    }

    void writeTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, render(), StandardCharsets.UTF_8);
    }

    String render() {
        List<Outcome> all = List.copyOf(outcomes);
        List<String> lines = new ArrayList<>();
        lines.add("# 요청 문구 검증기 측정 결과");
        lines.add("");
        lines.add("JDK " + System.getProperty("java.version") + ", " + System.getProperty("os.name")
                + ", 표본 " + all.size() + "건");
        lines.add("");
        lines.add("## 범주별 차단율");
        lines.add("");
        lines.add("| 범주 | 표본 수 | 차단 | 차단율 | 기대 코드 | 코드 일치 | 코드 일치율 | 다른 코드로 차단 |");
        lines.add("|---|---|---|---|---|---|---|---|");
        BLOCK_CATEGORIES.forEach(category -> lines.add(blockRow(category, all)));
        lines.add("");
        lines.add("## 정상 표본 오탐률");
        lines.add("");
        lines.add(normalSummary(all));
        lines.add("");
        lines.addAll(listSection("미탐 목록 (차단돼야 하는데 통과)", all,
                o -> o.sample().expectsBlock() && !o.blocked(), false));
        lines.addAll(listSection("코드 불일치 목록 (다른 코드로 차단)", all,
                o -> o.sample().expectsBlock() && o.blocked() && !o.codeMatches(), true));
        lines.addAll(listSection("오탐 목록 (통과해야 하는데 차단)", all,
                o -> CATEGORY_NORMAL.equals(o.sample().category()) && o.blocked(), true));
        lines.addAll(borderlineSection(all));
        lines.addAll(timingSection());
        return String.join("\n", lines) + "\n";
    }

    private static String blockRow(String category, List<Outcome> all) {
        List<Outcome> rows = inCategory(all, category);
        long blocked = rows.stream().filter(Outcome::blocked).count();
        long matched = rows.stream().filter(Outcome::codeMatches).count();
        String expectedCode = rows.isEmpty() ? "-" : rows.get(0).sample().expectedCode();
        return "| " + category + " | " + rows.size() + " | " + blocked + " | " + percent(blocked, rows.size())
                + " | " + expectedCode + " | " + matched + " | " + percent(matched, rows.size())
                + " | " + (blocked - matched) + " |";
    }

    private static String normalSummary(List<Outcome> all) {
        List<Outcome> rows = inCategory(all, CATEGORY_NORMAL);
        long falsePositives = rows.stream().filter(Outcome::blocked).count();
        return "표본 " + rows.size() + "건 중 오탐 " + falsePositives + "건, 오탐률 "
                + percent(falsePositives, rows.size()) + ".";
    }

    private static List<String> listSection(String title, List<Outcome> all, Predicate<Outcome> filter,
                                            boolean withActualCode) {
        List<Outcome> rows = all.stream().filter(filter).toList();
        List<String> lines = new ArrayList<>();
        lines.add("## " + title + ": " + rows.size() + "건");
        lines.add("");
        if (!rows.isEmpty()) {
            lines.add(withActualCode ? "| id | variant | 기대 | 실제 | text |" : "| id | variant | text |");
            lines.add(withActualCode ? "|---|---|---|---|---|" : "|---|---|---|");
            rows.forEach(o -> lines.add(withActualCode
                    ? "| " + o.sample().id() + " | " + o.sample().variant() + " | " + expectedLabel(o.sample())
                            + " | " + o.actualCode() + " | " + cell(o.sample().rawText()) + " |"
                    : "| " + o.sample().id() + " | " + o.sample().variant() + " | " + cell(o.sample().rawText())
                            + " |"));
            lines.add("");
        }
        return lines;
    }

    private static List<String> borderlineSection(List<Outcome> all) {
        List<Outcome> rows = inCategory(all, CATEGORY_BORDERLINE);
        List<String> lines = new ArrayList<>();
        lines.add("## 경계 표본의 반응: " + rows.size() + "건");
        lines.add("");
        lines.add("| id | variant | 반응 | 코드 | text |");
        lines.add("|---|---|---|---|---|");
        rows.forEach(o -> lines.add("| " + o.sample().id() + " | " + o.sample().variant() + " | "
                + (o.blocked() ? "차단" : "통과") + " | " + (o.blocked() ? o.actualCode() : "-") + " | "
                + cell(o.sample().rawText()) + " |"));
        lines.add("");
        return lines;
    }

    private List<String> timingSection() {
        List<String> lines = new ArrayList<>();
        lines.add("## 검증 1건 처리 시간");
        lines.add("");
        Timing t = timing;
        if (t == null) {
            lines.add("측정하지 않았다.");
        } else {
            lines.add("표본 " + t.samples() + "건을 워밍업 " + WARMUP_ROUNDS + "회 뒤 " + t.rounds()
                    + "회 반복해 " + (t.samples() * t.rounds()) + "번 쟀다.");
            lines.add("");
            lines.add("| 평균 | 95번째 백분위 | 최대 |");
            lines.add("|---|---|---|");
            lines.add(String.format(Locale.ROOT, "| %.1f µs | %.1f µs | %.1f µs |",
                    t.meanMicros(), t.p95Micros(), t.maxMicros()));
        }
        lines.add("");
        return lines;
    }

    private static List<Outcome> inCategory(List<Outcome> all, String category) {
        return all.stream().filter(o -> category.equals(o.sample().category())).collect(Collectors.toList());
    }

    private static String expectedLabel(Sample sample) {
        return sample.expectsBlock() ? sample.expectedCode() : sample.expected();
    }

    private static String percent(long count, long total) {
        if (total == 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * count / total);
    }

    // 표의 칸 안에서 세로선이 칸을 가르지 않게 한다.
    private static String cell(String text) {
        return text.replace("|", "\\|");
    }
}
