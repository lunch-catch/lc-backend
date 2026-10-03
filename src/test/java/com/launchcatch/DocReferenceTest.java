package com.launchcatch;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 문서가 가리키는 문서가 실재하는지 본다. 도메인을 가로지르는 검사라 아키텍처 테스트와 같은 자리에 둔다.
 *
 * 끊긴 참조는 조용히 쌓인다. 이름을 바꾸거나 문서를 안 가져오면 가리키는 쪽이 그대로 남고,
 * 읽는 사람은 그 문서를 찾다가 포기한다. 실제로 fm-backend 에서 들여올 때 생긴 참조 다섯이
 * 저장소에 없는 파일을 가리킨 채 오래 남아 있었다.
 *
 * 두 모양을 본다. 상대 경로 링크와 백틱으로 감싼 파일 이름이다. 링크만 보면 놓친다.
 * 아직 안 쓴 문서는 링크로 두면 깨지니 백틱으로 적고 있어서, 검사를 링크로 좁히면
 * 백틱 쪽에서 틀린 이름이 걸리지 않는다.
 */
class DocReferenceTest {

    private static final Path DOCS = Path.of("docs");

    /** `[이름](상대경로)` 의 경로. 앵커(#)는 뺀다. */
    private static final Pattern RELATIVE_LINK = Pattern.compile("]\\((\\.{1,2}/[^)#]+)");

    /** 백틱으로 감싼 마크다운 파일 이름. 경로가 붙어 있을 수도 있다. */
    private static final Pattern BACKTICK_DOC = Pattern.compile("`([A-Za-z0-9_./\\-]+\\.md)`");

    private static final Path API_INDEX = Path.of("docs/api-spec/README.md");

    /** `## 문서` 절 표의 머리 줄. 예고된 문서 목록이 그 아래에 있다. */
    private static final Pattern INDEX_HEADER =
            Pattern.compile("^\\|\\s*문서\\s*\\|\\s*다루는 것\\s*\\|");

    /** 표의 구분 줄. 머리 줄 바로 다음 한 줄이다. */
    private static final int SEPARATOR_ROWS = 1;

    /*
     * 아직 쓰지 않아도 되는 문서를 README 의 문서 표에서 읽는다. 여기 적지 않는다.
     *
     * 그 표가 열두 문서를 예고하고 있고, 링크로 적으면 깨지므로 백틱으로 적혀 있다.
     * 목록을 이 파일에 복사해 두면 팀원이 문서를 쓸 때마다 이 테스트를 고쳐야 하고,
     * 고치지 않으면 올바른 작업이 떨어진다. 표가 소유하게 두면 그 일이 없다.
     */
    private static Set<String> plannedDocs() {
        List<String> lines;
        try {
            lines = Files.readAllLines(API_INDEX);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> planned = new java.util.LinkedHashSet<>();
        for (int header = 0; header < lines.size(); header++) {
            if (!INDEX_HEADER.matcher(lines.get(header).strip()).find()) {
                continue;
            }
            for (int row = header + 1 + SEPARATOR_ROWS; row < lines.size(); row++) {
                String line = lines.get(row).strip();
                if (!line.startsWith("|")) {
                    break;
                }
                Matcher name = BACKTICK_DOC.matcher(line);
                if (name.find()) {
                    planned.add(name.group(1));
                }
            }
        }
        return planned;
    }

    private static List<Path> markdownFiles() {
        try (Stream<Path> paths = Files.walk(DOCS)) {
            return paths.filter(path -> path.toString().endsWith(".md")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** docs 안에 있는 마크다운 파일 이름. 백틱 참조는 경로 없이 이름만 적는 경우가 많아 이름으로 찾는다. */
    private static Set<String> docNames() {
        return markdownFiles().stream()
                .map(path -> path.getFileName().toString())
                .collect(toSet());
    }

    private static List<String> brokenLinks() {
        return markdownFiles().stream()
                .flatMap(DocReferenceTest::brokenLinksIn)
                .toList();
    }

    private static Stream<String> brokenLinksIn(Path doc) {
        Matcher matcher = RELATIVE_LINK.matcher(read(doc));
        return matcher.results()
                .map(result -> result.group(1))
                .filter(target -> !Files.exists(doc.getParent().resolve(target).normalize()))
                .map(target -> doc + " -> " + target);
    }

    /*
     * 백틱 참조는 이름으로만 본다. 경로까지 맞추라고 하면 같은 문서를 가리키는 표기가
     * 문서마다 달라서(상대 경로, 저장소 기준 경로, 이름만) 참을 가리키는 것도 걸린다.
     * 틀린 이름을 잡는 것이 목적이고, 그것은 이름만으로 충분히 걸린다.
     *
     * 별이 든 것은 글롭이라 뺀다. *-guideline.md 처럼 묶음을 가리키는 표기가 있다.
     */
    private static List<String> brokenBacktickRefs() {
        Set<String> names = docNames();
        Set<String> planned = plannedDocs();
        return markdownFiles().stream()
                .flatMap(doc -> BACKTICK_DOC.matcher(read(doc)).results()
                        .map(result -> result.group(1))
                        .filter(ref -> !ref.contains("*"))
                        .map(ref -> ref.substring(ref.lastIndexOf('/') + 1))
                        .filter(name -> !names.contains(name))
                        .filter(name -> !planned.contains(name))
                        .map(name -> doc + " -> `" + name + "`"))
                .distinct()
                .toList();
    }

    @Test
    @DisplayName("상대 경로 링크가 실재하는 파일을 가리킨다")
    void 상대_링크() {
        assertThat(brokenLinks()).as("깨진 상대 경로 링크").isEmpty();
    }

    /*
     * 백틱으로 적은 문서 이름도 실재해야 한다.
     * 이 검사가 없어서 domain-package-boundary-guideline.md 를 가리키는 참조 다섯이 남아 있었다.
     * 그 파일은 fm-backend 쪽 이름이고 이 저장소에는 domain-boundary-guideline.md 가 있다.
     */
    @Test
    @DisplayName("백틱으로 적은 문서 이름이 실재한다")
    void 백틱_참조() {
        assertThat(brokenBacktickRefs())
                .as("실재하지 않는 문서를 가리키는 백틱 참조")
                .isEmpty();
    }

    /*
     * 예고 목록을 읽어 내지 못하면 백틱 검사가 아직 안 쓴 문서를 전부 깨진 참조로 본다.
     * 표의 머리 줄 모양이 바뀌면 그 상태가 되므로 비어 있지 않은지 본다.
     */
    @Test
    @DisplayName("README 의 문서 표에서 예고된 문서를 읽어 낸다")
    void 예고_목록_탐색() {
        assertThat(plannedDocs()).as("README 문서 표가 예고한 문서").isNotEmpty();
    }
}
