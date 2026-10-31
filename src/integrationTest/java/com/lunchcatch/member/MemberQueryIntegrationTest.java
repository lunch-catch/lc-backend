package com.lunchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.member.contract.MemberQueryService;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class MemberQueryIntegrationTest extends AbstractMemberIntegrationTest {

    @Autowired MemberQueryService query;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void 일괄_상태_조회는_세_상태를_단건_조회와_같게_반환하고_없는_회원은_뺀다() {
        Long active = createMember().getId();
        Long suspended = createMember().getId();
        Long withdrawn = createMember().getId();
        jdbc.update("UPDATE member SET status = 'SUSPENDED', suspended_at = ? WHERE member_id = ?",
                LocalDateTime.now(clock), suspended);
        Member target = members.findById(withdrawn).orElseThrow();
        target.withdraw(LocalDateTime.now(clock));
        members.saveAndFlush(target);
        Long missing = Long.MAX_VALUE;

        Map<Long, MemberStatus> result = query.findCurrentStatuses(Set.of(active, suspended, withdrawn, missing));

        assertThat(result).containsOnly(
                Map.entry(active, MemberStatus.ACTIVE),
                Map.entry(suspended, MemberStatus.SUSPENDED),
                Map.entry(withdrawn, MemberStatus.WITHDRAWN));
        result.forEach((id, status) -> assertThat(query.findCurrentStatus(id)).contains(status));
    }

    private Member createMember() {
        return members.saveAndFlush(Member.create("status-" + UUID.randomUUID(), "점심헌터", "https://image"));
    }
}
