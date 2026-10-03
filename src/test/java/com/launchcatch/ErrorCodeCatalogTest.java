package com.launchcatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.owner.exception.OwnerErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 오류 코드 목록을 지킨다. 도메인을 가로지르는 검사라 아키텍처 테스트와 같은 자리에 둔다.
 *
 * 코드는 클라이언트가 화면을 분기하는 값이다. 번호가 겹치거나 비거나, 문서와 코드의 문장이
 * 갈리면 클라이언트가 어느 쪽을 믿을지 알 수 없다. 사람이 대조하면 놓치므로 여기서 막는다.
 */
class ErrorCodeCatalogTest {

    /* `AUTH-001` 과 `400` 과 문장을 뽑는다. 코드가 첫 칸인 표만 걸리므로 절별 오류 표는 섞이지 않는다. */
    private static final Pattern DOC_ROW = Pattern.compile(
            "^\\|\\s*`([A-Z]+-\\d{3})`\\s*\\|\\s*`(\\d{3})`\\s*\\|\\s*(.+?)\\s*\\|$");

    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z]+-\\d{3}$");

    private static List<ErrorCode> all() {
        return Stream.of(
                        CommonErrorCode.values(),
                        AuthErrorCode.values(),
                        OwnerErrorCode.values(),
                        MemberErrorCode.values())
                .flatMap(Stream::of)
                .map(ErrorCode.class::cast)
                .toList();
    }

    @Test
    @DisplayName("코드 형식은 도메인-번호 이고 세 자리 번호다")
    void 코드_형식() {
        List<String> bad = all().stream()
                .map(ErrorCode::getCode)
                .filter(c -> !CODE_FORMAT.matcher(c).matches())
                .toList();
        assertThat(bad).as("도메인-번호 형식이 아닌 코드").isEmpty();
    }

    @Test
    @DisplayName("코드가 겹치지 않는다")
    void 코드_중복() {
        List<String> codes = all().stream().map(ErrorCode::getCode).toList();
        assertThat(codes).as("오류 코드").doesNotHaveDuplicates();
    }

    /*
     * 번호가 1부터 끊기지 않고 이어져야 한다.
     * 중간이 비면 지운 것인지 아직 안 쓴 것인지 알 수 없고, 다음 사람이 빈 번호를 다시 쓴다.
     */
    @Test
    @DisplayName("접두어마다 번호가 1부터 끊기지 않는다")
    void 번호_연속() {
        Map<String, List<Integer>> byPrefix = new LinkedHashMap<>();
        for (ErrorCode e : all()) {
            String[] parts = e.getCode().split("-");
            byPrefix.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(Integer.parseInt(parts[1]));
        }
        List<String> bad = new ArrayList<>();
        byPrefix.forEach((prefix, numbers) -> {
            List<Integer> sorted = numbers.stream().sorted().toList();
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i) != i + 1) {
                    bad.add(prefix + " 번호가 " + sorted);
                    return;
                }
            }
        });
        assertThat(bad).as("번호가 끊긴 접두어").isEmpty();
    }

    @Test
    @DisplayName("메시지는 비어 있지 않고 앞뒤 공백이 없다")
    void 메시지_형식() {
        List<String> bad = all().stream()
                .filter(e -> e.getMessage().isBlank() || !e.getMessage().equals(e.getMessage().strip()))
                .map(ErrorCode::getCode)
                .toList();
        assertThat(bad).as("메시지가 비었거나 공백이 붙은 코드").isEmpty();
    }

    /*
     * 명세와 코드가 갈리지 않게 한다.
     *
     * auth.md 의 "오류 코드" 표가 클라이언트가 읽는 계약이다. 표에 있는 코드가 코드에 없으면
     * 클라이언트가 못 오는 응답을 기다리고, 상태나 문장이 다르면 화면이 문서대로 안 돈다.
     * 이 검사가 없어서 문서를 고칠 때마다 사람이 눈으로 대조했고, 실제로 어긋난 채 올라갔다.
     */
    @Test
    @DisplayName("auth.md 의 오류 코드 표와 코드가 일치한다")
    void 문서와_일치() throws IOException {
        Path doc = Path.of("docs/api-spec/auth.md");
        assertThat(doc).as("오류 코드 표가 있는 명세").exists();

        Map<String, ErrorCode> byCode = new LinkedHashMap<>();
        all().forEach(e -> byCode.put(e.getCode(), e));

        List<String> bad = new ArrayList<>();
        int matched = 0;
        for (String line : Files.readAllLines(doc)) {
            Matcher m = DOC_ROW.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            matched++;
            String code = m.group(1);
            int status = Integer.parseInt(m.group(2));
            String message = m.group(3);

            ErrorCode e = byCode.get(code);
            if (e == null) {
                bad.add(code + " 가 문서에만 있다");
                continue;
            }
            if (e.getHttpStatus().value() != status) {
                bad.add("%s 상태가 다르다. 문서 %d, 코드 %d".formatted(code, status, e.getHttpStatus().value()));
            }
            if (!e.getMessage().equals(message)) {
                bad.add("%s 문장이 다르다.%n    문서 %s%n    코드 %s".formatted(code, message, e.getMessage()));
            }
        }
        assertThat(matched).as("문서에서 읽은 오류 코드 행 수").isPositive();
        assertThat(bad).as("문서와 어긋난 것").isEmpty();
    }
}
