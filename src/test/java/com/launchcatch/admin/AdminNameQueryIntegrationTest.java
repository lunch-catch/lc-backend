package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.admin.contract.AdminNameQuery;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.auth.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 실제 MySQL 로 JPQL 생성자 식(AdminIdName)과 IN 조회가 동작하는지 확인한다(#949).
class AdminNameQueryIntegrationTest extends AbstractAdminIntegrationTest {

    @Autowired private AdminNameQuery adminNameQuery;
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
    void ID로_이름을_조회하고_없는_ID는_결과에서_뺀다() {
        long suffix = System.nanoTime();
        firstId = admins.saveAndFlush(Admin.register("name.query.a" + suffix, encoder.encode("Freshman!2026"),
                "홍길동", Role.ADMIN)).getId();
        secondId = admins.saveAndFlush(Admin.register("name.query.b" + suffix, encoder.encode("Freshman!2026"),
                "김관리", Role.SUPER_ADMIN)).getId();
        long missingId = Long.MAX_VALUE;

        Map<Long, String> names = adminNameQuery.findNamesByIds(List.of(firstId, secondId, missingId));

        assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.of(firstId, "홍길동", secondId, "김관리"));
    }
}
