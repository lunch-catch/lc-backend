package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.Role;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberRefreshTokenBackupStoreTest {
    @Mock MemberRepository memberRepository;
    @Mock Member member;

    @Test
    void member_DB_백업_포트는_역할별_조회와_조건부_갱신을_위임한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 12, 0);
        MemberRefreshTokenBackupStore store = new MemberRefreshTokenBackupStore(memberRepository);
        when(member.getRefreshTokenHash()).thenReturn("old");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.updateRefreshTokenBackup(any(), any(), any(), any(), any())).thenReturn(1);
        when(memberRepository.rotateRefreshTokenBackupIfMatches(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(memberRepository.clearRefreshTokenBackupIfHashMatches(any(), any(), any())).thenReturn(1);

        assertThat(store.role()).isEqualTo(Role.MEMBER);
        assertThat(store.findCurrentHash(1L)).contains("old");
        assertThat(store.save(1L, "new", now.plusDays(1), now)).isTrue();
        assertThat(store.rotateIfMatches(1L, "old", "new", now.plusDays(1), now)).isTrue();
        assertThat(store.clearIfMatches(1L, "new", now)).isTrue();
        verify(memberRepository).clearRefreshTokenBackupIfHashMatches(1L, "new", now);
    }

    @Test
    void 해시로_조회하면_활성이고_만료_전인_회원만_돌려준다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        MemberRefreshTokenBackupStore store = new MemberRefreshTokenBackupStore(memberRepository);
        when(memberRepository.findByRefreshTokenHash("old")).thenReturn(Optional.of(member));
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.getId()).thenReturn(1L);
        when(member.getRefreshTokenHash()).thenReturn("old");
        when(member.getRefreshTokenExpiresAt()).thenReturn(now.plusMinutes(1));

        assertThat(store.findValidByHash("old", now))
                .hasValueSatisfying(backup -> {
                    assertThat(backup.subjectId()).isEqualTo(1L);
                    assertThat(backup.role()).isEqualTo(Role.MEMBER);
                });
    }

    @Test
    void 해시로_조회해도_만료됐거나_활성이_아니면_비어_있다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        MemberRefreshTokenBackupStore store = new MemberRefreshTokenBackupStore(memberRepository);
        when(memberRepository.findByRefreshTokenHash("expired")).thenReturn(Optional.of(member));
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.getRefreshTokenExpiresAt()).thenReturn(now);

        assertThat(store.findValidByHash("expired", now)).isEmpty();

        when(memberRepository.findByRefreshTokenHash("withdrawn")).thenReturn(Optional.of(member));
        when(member.getStatus()).thenReturn(MemberStatus.WITHDRAWN);

        assertThat(store.findValidByHash("withdrawn", now)).isEmpty();
    }
}
