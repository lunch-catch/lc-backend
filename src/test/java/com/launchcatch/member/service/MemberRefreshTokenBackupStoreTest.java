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
        when(memberRepository.findByRefreshTokenHash("old")).thenReturn(Optional.of(member));
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.getId()).thenReturn(1L);
        when(member.getRefreshTokenHash()).thenReturn("old");
        when(member.getRefreshTokenExpiresAt()).thenReturn(now.plusMinutes(1));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.updateRefreshTokenBackup(any(), any(), any(), any(), any())).thenReturn(1);
        when(memberRepository.rotateRefreshTokenBackupIfMatches(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(memberRepository.clearRefreshTokenBackupIfHashMatches(any(), any(), any())).thenReturn(1);

        assertThat(store.role()).isEqualTo(Role.MEMBER);
        assertThat(store.findValidByHash("old", now)).isPresent();
        assertThat(store.findCurrentHash(1L)).contains("old");
        assertThat(store.save(1L, "new", now.plusDays(1), now)).isTrue();
        assertThat(store.rotateIfMatches(1L, "old", "new", now.plusDays(1), now)).isTrue();
        assertThat(store.clearIfMatches(1L, "new", now)).isTrue();
        verify(memberRepository).clearRefreshTokenBackupIfHashMatches(1L, "new", now);
    }
}
