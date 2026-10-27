package com.lunchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.admin.contract.AdminSummary;
import com.lunchcatch.admin.contract.AdminSummaryQuery;
import com.lunchcatch.admin.entity.Admin;
import com.lunchcatch.auth.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 실제 MySQL 로 JPQL 생성자 식(AdminIdSummary)과 IN 조회가 동작하는지 확인한다(#949).
class AdminSummaryQueryIntegrationTest extends AbstractAdminIntegrationTest {

    @Autowired private AdminSummaryQuery adminSummaryQuery;
    private Long firstId;
    private Long secondId;

    @AfterEach
    void tearDown() {
        if (firstId != null) {
            admins.deleteById(firstId);
        }
        if (secondId != null) {
            admins.deleteById(secondId);
        }
    }

    @Test
    void ID로_이름과_로그인_ID를_조회하고_없는_ID는_결과에서_뺀다() {
        long suffix = System.nanoTime();
        String firstLoginId = "summary.query.a" + suffix;
        String secondLoginId = "summary.query.b" + suffix;
        firstId = admins.saveAndFlush(Admin.register(firstLoginId, encoder.encode("Freshman!2026"),
                "홍길동", Role.ADMIN)).getId();
        secondId = admins.saveAndFlush(Admin.register(secondLoginId, encoder.encode("Freshman!2026"),
                "홍길동", Role.SUPER_ADMIN)).getId();
        long missingId = Long.MAX_VALUE;

        Map<Long, AdminSummary> summaries = adminSummaryQuery.findSummariesByIds(List.of(firstId, secondId, missingId));

        // 이름이 같아도 로그인 ID 가 달라 구분된다.
        assertThat(summaries).containsExactlyInAnyOrderEntriesOf(Map.of(
                firstId, new AdminSummary("홍길동", firstLoginId), secondId, new AdminSummary("홍길동", secondLoginId)));
    }
}
