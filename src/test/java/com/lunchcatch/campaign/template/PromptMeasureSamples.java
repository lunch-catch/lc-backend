package com.lunchcatch.campaign.template;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/*
 * 요청 문구 검증기 측정용 표본을 CSV 에서 읽는다(#1089).
 * 칸은 id, category, variant, expected, text 다섯이고 모든 칸이 큰따옴표로 감싸여 있다.
 * text 의 앞뒤 공백은 지우지 않는다. 짧음 범주의 공백뿐인 표본이 그 칸에 의존한다.
 * 보이지 않는 문자와 줄바꿈, 탭은 text 에 이스케이프 표기로 적혀 있어 읽을 때 실제 문자로 푼다.
 * 표기는 백슬래시 뒤에 u 와 16진수 네 자리, u 와 중괄호 안의 16진수, n, t, r 이다.
 */
final class PromptMeasureSamples {

    static final String CATEGORY_HIDDEN_CHARS = "숨은문자";
    static final String CATEGORY_INJECTION = "인젝션";
    static final String CATEGORY_PERSONAL_INFO = "개인정보";
    static final String CATEGORY_TOO_SHORT = "짧음";
    static final String CATEGORY_NORMAL = "정상";
    static final String CATEGORY_BORDERLINE = "경계";

    private static final String DIRECTORY = "campaign/prompt/measure/";
    private static final List<String> FILES = List.of(
            "hidden-chars.csv", "injection.csv", "personal-info.csv",
            "too-short.csv", "normal.csv", "borderline.csv");
    private static final String BLOCK_PREFIX = "BLOCK:";
    private static final int COLUMNS = 5;
    private static final Pattern ESCAPE =
            Pattern.compile("\\\\(u\\{([0-9A-Fa-f]+)\\}|u([0-9A-Fa-f]{4})|([ntr]))");

    private PromptMeasureSamples() {
    }

    record Sample(String id, String category, String variant, String expected, String rawText) {

        String text() {
            return decode(rawText);
        }

        boolean expectsBlock() {
            return expected.startsWith(BLOCK_PREFIX);
        }

        String expectedCode() {
            return expected.substring(BLOCK_PREFIX.length());
        }

        @Override
        public String toString() {
            return id + " " + variant;
        }
    }

    static List<Sample> loadAll() {
        return FILES.stream().flatMap(file -> load(file).stream()).toList();
    }

    private static List<Sample> load(String file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DIRECTORY + file).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .skip(1)
                    .filter(line -> !line.isEmpty())
                    .map(line -> toSample(file, line))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("measure sample file could not be read: " + file, e);
        }
    }

    private static Sample toSample(String file, String line) {
        List<String> fields = splitFields(line);
        if (fields.size() != COLUMNS) {
            throw new IllegalStateException("measure sample must have " + COLUMNS + " columns: " + file + " " + line);
        }
        return new Sample(fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4));
    }

    // 큰따옴표 안의 쉼표는 구분자가 아니고, 큰따옴표 두 개는 큰따옴표 하나다.
    private static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                current.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    static String decode(String raw) {
        return ESCAPE.matcher(raw).replaceAll(match -> Matcher.quoteReplacement(decodeOne(match)));
    }

    private static String decodeOne(MatchResult match) {
        if (match.group(2) != null) {
            return Character.toString(Integer.parseInt(match.group(2), 16));
        }
        if (match.group(3) != null) {
            return Character.toString(Integer.parseInt(match.group(3), 16));
        }
        return switch (match.group(4)) {
            case "n" -> "\n";
            case "t" -> "\t";
            default -> "\r";
        };
    }
}
