package com.lunchcatch.campaign.poster;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/*
 * 포스터 자동 검수의 금지 표현 사전이다(명세서 29행, 57행, #535).
 * 서버가 뜰 때 리소스 파일을 UTF-8 로 한 번 읽는다. 파일이 없거나 비어 있으면 서버가 뜨지 않게 한다.
 * 금지 표현 없이 검수가 조용히 전부 통과하는 것을 막기 위해서다.
 *
 * 비교는 양쪽 공백을 지우고 소문자로 맞춘 뒤 글자 그대로 한다. 정규식으로 해석하지 않는다.
 * 잘못 넣은 패턴 하나가 검사를 느리게 만들 수 있고, "100%" 같은 특수 문자도 그대로 쓰기 위해서다.
 */
@Component
public class ForbiddenExpressionDictionary {

    static final String DEFAULT_PATH = "campaign/moderation/forbidden-expressions.txt";

    private final Set<String> normalizedExpressions;

    public ForbiddenExpressionDictionary() {
        this(DEFAULT_PATH);
    }

    ForbiddenExpressionDictionary(String classpathLocation) {
        this.normalizedExpressions = load(classpathLocation);
    }

    // 걸린 표현이 하나라도 있으면 그 첫 표현을 돌려준다.
    public Optional<String> findIn(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String target = normalize(text);
        return normalizedExpressions.stream().filter(target::contains).findFirst();
    }

    public boolean containsAny(List<String> texts) {
        return texts.stream().anyMatch(text -> findIn(text).isPresent());
    }

    private static Set<String> load(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("forbidden expression file not found: " + classpathLocation);
        }
        try (InputStream in = resource.getInputStream()) {
            Set<String> expressions = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(ForbiddenExpressionDictionary::normalize)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            if (expressions.isEmpty()) {
                throw new IllegalStateException("forbidden expression file is empty: " + classpathLocation);
            }
            return expressions;
        } catch (IOException e) {
            throw new UncheckedIOException("forbidden expression file could not be read: " + classpathLocation, e);
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
