package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class FailureLogDetailsTest {

    @Test
    void DB_원인과_발생_위치를_남기되_예외_메시지는_제외한다() {
        SQLException sql = new SQLException("비밀번호와 토큰 원문", "08001", 1045);
        sql.setStackTrace(new StackTraceElement[]{new StackTraceElement("Database", "connect", "Database.java", 42)});
        Throwable failure = new DataAccessResourceFailureException("주소와 쿼리 원문", sql);

        FailureLogDetails details = FailureLogDetails.from(failure);

        assertThat(details.causeTypes()).containsExactly("DataAccessResourceFailureException", "SQLException");
        assertThat(details.sqlState()).isEqualTo("08001");
        assertThat(details.sqlErrorCode()).isEqualTo(1045);
        assertThat(details.origin()).isEqualTo("Database.connect(Database.java:42)");
        assertThat(details.toString()).doesNotContain("원문", "비밀번호", "주소");
    }

    @Test
    void 감싼_HTTP_오류의_상태를_남기되_응답_본문과_헤더는_제외한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "토큰원문");
        Throwable failure = new IllegalStateException("요청원문", WebClientResponseException.create(
                429, "응답원문", headers, "개인정보원문".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        FailureLogDetails details = FailureLogDetails.from(failure);

        assertThat(details.httpStatus()).isEqualTo(429);
        assertThat(details.causeTypes()).contains("IllegalStateException", "TooManyRequests");
        assertThat(details.toString()).doesNotContain("원문", "Authorization");
    }

    @Test
    void SQL_상태에_정해진_코드가_아닌_문자열이_있으면_기록하지_않는다() {
        FailureLogDetails details = FailureLogDetails.from(new SQLException("비밀", "개인정보원문"));

        assertThat(details.sqlState()).isNull();
        assertThat(details.toString()).doesNotContain("비밀", "원문");
    }
}
