package com.launchcatch.global.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 도메인별 ERD 를 Mermaid 로 만든다. 시험이 아니라 그 재료를 만드는 클래스다.
 *
 * 마이그레이션 파일을 파싱하지 않고 적용된 뒤의 information_schema 를 읽는다. ALTER 가 있는
 * 파일이 넷이라(adserving V2, campaign V2 와 V3, ops V2) CREATE TABLE 만 읽으면 더해진 컬럼과
 * 바뀐 nullable 이 빠진다. 스키마의 진실은 적용 결과이고 파일은 그 과정일 뿐이다.
 *
 * 테이블이 어느 도메인 것인지는 information_schema 가 모른다. 그것만 마이그레이션 폴더에서
 * 가져온다. ArchitectureTest 의 TABLE_OWNER 와 같은 방식이다.
 */
final class ErdWriter {

    static final Path OUTPUT_DIR = Path.of("docs/erd");

    /** 색인 문서의 이름. 도메인 이름과 같은 공간을 쓰므로 도메인에 없는 이름이어야 한다. */
    static final String INDEX_NAME = "README";

    private static final Path MIGRATION_DIR =
            Path.of("src/main/resources", DomainFlywayMigrator.ROOT);

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE(?: IF NOT EXISTS)?\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

    /*
     * Mermaid 의 속성 타입은 영숫자와 밑줄과 괄호만 받는다. MySQL 이 주는 타입에는 그 밖의
     * 글자가 섞여 있다. decimal(10,7) 의 쉼표, bigint unsigned 의 공백,
     * enum('A','B') 의 따옴표가 그대로 들어가면 그림이 렌더링되지 않는다.
     *
     * 따옴표는 지우고 나머지는 밑줄로 바꾼다. decimal(10_7) 과 enum(A_B) 가 되어 정보는
     * 남고 문법은 지켜진다. 남는 글자가 있는지는 이 클래스가 아니라 시험이 전수로 본다.
     */
    private static final Pattern TYPE_QUOTES = Pattern.compile("['\"`]");
    private static final Pattern TYPE_UNSAFE = Pattern.compile("[^A-Za-z0-9_()\\[\\]]+");

    private final JdbcTemplate jdbc;

    ErdWriter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 문서 이름(확장자 없음) -> 그 문서의 전체 내용. 색인인 README 도 함께 만든다. */
    Map<String, String> render() {
        Map<String, List<String>> tablesByDomain = tablesByDomain();
        Map<String, String> documents = new TreeMap<>();
        tablesByDomain.forEach((domain, tables) ->
                documents.put(domain, document(domain, tables)));
        documents.put(INDEX_NAME, index(tablesByDomain));
        return documents;
    }

    /*
     * 색인도 생성한다. 손으로 두면 도메인이 늘 때 잊고, 그러면 목록이 실제와 어긋난다.
     * 생성 대상이면 어긋남 검사가 이것까지 함께 본다.
     */
    private String index(Map<String, List<String>> tablesByDomain) {
        StringBuilder out = new StringBuilder();
        out.append("# 도메인별 ERD\n\n")
                .append("마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서들이다.\n")
                .append("**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.\n")
                .append("`G-BUILD` 가 이 문서들과 스키마가 어긋났는지 검사한다.\n\n")
                .append("| 도메인 | 테이블 | 문서 |\n|---|---|---|\n");
        tablesByDomain.forEach((domain, tables) -> out
                .append("| ").append(domain)
                .append(" | ").append(tables.size())
                .append(" | [").append(domain).append(".md](./").append(domain).append(".md) |\n"));
        out.append("| **합계** | **")
                .append(tablesByDomain.values().stream().mapToInt(List::size).sum())
                .append("** | |\n\n")
                .append("경계를 넘는 참조는 FK 가 아니라 ID 값이라(설계 문서 1.1절) 각 그림이 자기만으로 완결된다.\n")
                .append("도메인을 넘는 FK 가 생기면 그 선은 그려지지 않는다. 그것은 그림의 문제가 아니라\n")
                .append("`ArchitectureTest` 가 잡아야 하는 의존 방향 위반이다.\n");
        return out.toString();
    }

    void write() {
        render().forEach((domain, body) -> {
            Path target = OUTPUT_DIR.resolve(domain + ".md");
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /*
     * 마이그레이션 폴더 이름이 도메인이고, 그 안에서 CREATE TABLE 된 이름이 그 도메인의 테이블이다.
     * ALTER 로만 바뀐 테이블은 소유자가 바뀌지 않으므로 CREATE 만 보면 된다.
     */
    private static Map<String, List<String>> tablesByDomain() {
        Map<String, List<String>> owned = new TreeMap<>();
        for (String domain : DomainFlywayMigrator.domains()) {
            List<String> tables = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(MIGRATION_DIR.resolve(domain))) {
                paths.filter(path -> path.toString().endsWith(".sql"))
                        .sorted()
                        .map(ErdWriter::read)
                        .forEach(body -> {
                            Matcher matcher = CREATE_TABLE.matcher(body);
                            while (matcher.find()) {
                                tables.add(matcher.group(1).toLowerCase());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            tables.sort(null);
            owned.put(domain, tables);
        }
        return owned;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String document(String domain, List<String> tables) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(domain).append(" ERD\n\n")
                .append("마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서다.\n")
                .append("**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.\n")
                .append("`G-BUILD` 가 이 파일과 스키마가 어긋났는지 검사한다.\n\n")
                .append("테이블 ").append(tables.size()).append("개.\n\n")
                .append("```mermaid\nerDiagram\n");
        tables.forEach(table -> appendTable(out, table));
        relations(tables).forEach(relation -> out.append("    ").append(relation).append('\n'));
        out.append("```\n");
        return out.toString();
    }

    private void appendTable(StringBuilder out, String table) {
        out.append("    ").append(table).append(" {\n");
        for (Map<String, Object> column : columns(table)) {
            String type = mermaidType((String) column.get("column_type"));
            out.append("        ").append(type).append(' ').append(column.get("column_name"));
            String key = keyOf((String) column.get("column_key"));
            if (!key.isEmpty()) {
                out.append(' ').append(key);
            }
            String note = noteOf(column);
            if (!note.isEmpty()) {
                out.append(" \"").append(note).append('"');
            }
            out.append('\n');
        }
        out.append("    }\n");
    }

    /** Mermaid 의 속성 타입으로 쓸 수 있는 형태로 줄인다. */
    static String mermaidType(String columnType) {
        String withoutQuotes = TYPE_QUOTES.matcher(columnType).replaceAll("");
        return TYPE_UNSAFE.matcher(withoutQuotes).replaceAll("_");
    }

    /** Mermaid 가 아는 표시는 PK, FK, UK 셋이다. */
    private static String keyOf(String columnKey) {
        return switch (columnKey == null ? "" : columnKey) {
            case "PRI" -> "PK";
            case "UNI" -> "UK";
            default -> "";
        };
    }

    /*
     * 주석이 있으면 그것을 보여 준다. 없을 때만 nullable 을 적는다.
     * 둘을 함께 적으면 그림의 칸이 넘쳐 읽기 어려워진다.
     */
    private static String noteOf(Map<String, Object> column) {
        String comment = (String) column.get("column_comment");
        if (comment != null && !comment.isBlank()) {
            return note(comment);
        }
        return "YES".equals(column.get("is_nullable")) ? "nullable" : "";
    }

    /*
     * 주석을 Mermaid 의 따옴표 문자열에 안전한 형태로 줄인다.
     *
     * 큰따옴표는 문자열을 닫고, 줄바꿈은 문장을 끊는다. 중괄호는 엔터티 본문의 구분자와 같아서
     * 따옴표 안이라도 파서가 어떻게 읽을지 보장할 수 없다. campaign.slot_values 의 주석이
     * "{슬롯키: {type, value}} 형태" 로 그 경우다. 하나가 어긋나면 그 도메인 그림 전체가
     * 코드 블록이 아니라 글자로 남으므로, 뜻이 보존되는 괄호로 바꾼다.
     */
    static String note(String comment) {
        return comment.replace('"', '\'')
                .replace('\n', ' ')
                .replace('{', '(')
                .replace('}', ')')
                .strip();
    }

    private List<Map<String, Object>> columns(String table) {
        return jdbc.queryForList("""
                SELECT column_name, column_type, is_nullable, column_key, column_comment
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                 ORDER BY ordinal_position
                """, table);
    }

    /*
     * 같은 도메인 안의 FK 만 선으로 그린다. 경계를 넘는 참조는 ID 값이라 FK 가 없고(설계 문서
     * 1.1절), 지금 FK 29건이 전부 도메인 안에 있다. 넘는 것이 생기면 그 선은 그려지지 않으므로,
     * 그림이 아니라 ArchitectureTest 가 잡아야 하는 문제다.
     */
    private List<String> relations(List<String> tables) {
        if (tables.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", tables.stream().map(table -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT table_name, referenced_table_name, column_name
                  FROM information_schema.key_column_usage
                 WHERE table_schema = DATABASE()
                   AND referenced_table_name IS NOT NULL
                   AND table_name IN (%s)
                 ORDER BY table_name, column_name
                """.formatted(placeholders), tables.toArray());

        Map<String, String> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String child = ((String) row.get("table_name")).toLowerCase();
            String parent = ((String) row.get("referenced_table_name")).toLowerCase();
            if (!tables.contains(parent)) {
                continue;
            }
            String column = (String) row.get("column_name");
            unique.put(parent + "|" + child + "|" + column,
                    "%s ||--o{ %s : \"%s\"".formatted(parent, child, column));
        }
        return List.copyOf(unique.values());
    }
}
