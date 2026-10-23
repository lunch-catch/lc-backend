package com.launchcatch.campaign.template;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/*
 * 관리자가 템플릿 생성, 수정 LLM 채팅에 보내는 요청 문구(requestPrompt)를 모델에 보내기 전에 거른다(#428).
 * 모델이 규칙을 지킬 거라 믿을 수 없는 안전 문제는 서버가 코드로 결정적으로 차단한다. 걸리면 Bedrock 을
 * 부르지 않아 비용도 들지 않는다. 사유마다 오류 코드가 달라 프론트가 구분해 안내할 수 있다.
 *
 * 검사 순서는 숨은 문자 거부 → 의미 없는 입력 → 인젝션 → 개인정보다. 숨은 문자를 먼저 거부해야
 * zero-width 문자를 끼워 넣어 뒤의 검사를 피하는 시도를 막는다. 뒤의 검사는 NFKC 정규화(전각 문자 등을
 * 일반 문자로 맞춤)와 소문자 변환, 공백 정리를 거친 문구로 한다. 저장하는 원문은 바꾸지 않는다.
 *
 * 인젝션 패턴은 리소스 파일에 둔다. 욕설 검사는 두지 않는다. 템플릿 요청은 관리자만 보내고 문구가 포스터에
 * 노출되지 않으며, 포스터의 금지 표현은 자동 검수가 따로 거른다. 패턴이 완전하지 않아 우회될 수 있으므로 이 검사만 믿지
 * 않고, 모델 출력 쪽 정화와 슬롯 검증이 그대로 뒤를 받친다.
 */
@Component
public class TemplatePromptValidator {

    static final String INJECTION_PATH = "campaign/prompt/injection-patterns.txt";

    // 글자나 숫자가 이만큼은 있어야 의미 있는 요청으로 본다. "ㅁ", ".", "ㅇㅇ" 같은 입력을 막는다.
    private static final int MIN_MEANINGFUL_CHARACTERS = 2;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<Pattern> PERSONAL_INFO_PATTERNS = List.of(
            // 전화번호: 02-123-4567, 010-1234-5678, 01012345678
            Pattern.compile("0\\d{1,2}[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}"),
            // 사업자등록번호: 123-45-67890
            Pattern.compile("\\d{3}-\\d{2}-\\d{5}"),
            // 주민등록번호: 900101-1234567
            Pattern.compile("\\d{6}-?[1-4]\\d{6}"),
            // 카드번호: 1234-5678-9012-3456
            Pattern.compile("\\d{4}[-\\s]\\d{4}[-\\s]\\d{4}[-\\s]\\d{4}"),
            // 이메일
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"));

    private final List<Pattern> injectionPatterns;

    public TemplatePromptValidator() {
        this(INJECTION_PATH);
    }

    TemplatePromptValidator(String injectionPath) {
        this.injectionPatterns = loadLines(injectionPath).stream()
                .map(line -> Pattern.compile(line, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    public void validate(String requestPrompt) {
        requireVisibleCharacters(requestPrompt);
        String normalized = normalize(requestPrompt);
        requireMeaningful(normalized);
        requireNoInjection(normalized);
        requireNoPersonalInfo(normalized);
    }

    // 개행과 탭은 허용하고, 그 밖의 제어 문자와 서식 문자(zero-width, 방향 전환 등)는 거부한다.
    private void requireVisibleCharacters(String text) {
        boolean invalid = text.codePoints().anyMatch(codePoint -> {
            int type = Character.getType(codePoint);
            boolean allowedControl = codePoint == '\n' || codePoint == '\r' || codePoint == '\t';
            return (type == Character.CONTROL && !allowedControl) || type == Character.FORMAT;
        });
        if (invalid) {
            throw new CampaignException(PosterErrorCode.PROMPT_INVALID_CHARACTERS);
        }
    }

    private void requireMeaningful(String normalized) {
        long meaningful = normalized.codePoints().filter(TemplatePromptValidator::isMeaningful).count();
        if (meaningful < MIN_MEANINGFUL_CHARACTERS) {
            throw new CampaignException(PosterErrorCode.PROMPT_TOO_SHORT);
        }
    }

    /*
     * 자음, 모음 낱자(ㅁ, ㅇ)는 글자로 세지 않는다. NFKC 정규화가 호환 자모(U+3131~318E)를 조합용 자모
     * (U+1100~11FF 등)로 바꾸므로 두 범위를 모두 뺀다.
     */
    private static boolean isMeaningful(int codePoint) {
        return Character.isLetterOrDigit(codePoint) && !isJamo(codePoint);
    }

    private static boolean isJamo(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xD7B0 && codePoint <= 0xD7FF);
    }

    private void requireNoInjection(String normalized) {
        if (injectionPatterns.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) {
            throw new CampaignException(PosterErrorCode.PROMPT_INJECTION_SUSPECTED);
        }
    }

    private void requireNoPersonalInfo(String normalized) {
        if (PERSONAL_INFO_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) {
            throw new CampaignException(PosterErrorCode.PROMPT_PERSONAL_INFO);
        }
    }

    private static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(nfkc).replaceAll(" ").strip();
    }

    private static List<String> loadLines(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("prompt validation file not found: " + classpathLocation);
        }
        try (InputStream in = resource.getInputStream()) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (lines.isEmpty()) {
                throw new IllegalStateException("prompt validation file is empty: " + classpathLocation);
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("prompt validation file could not be read: " + classpathLocation, e);
        }
    }
}
