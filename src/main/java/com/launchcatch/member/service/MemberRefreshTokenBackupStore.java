package com.launchcatch.member.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.opaque.RefreshTokenBackupStore;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class MemberRefreshTokenBackupStore implements RefreshTokenBackupStore {

    private static final Role ROLE = Role.MEMBER;

    private final MemberRepository memberRepository;

    @Override
    public Role role() {
        return ROLE;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<String> findCurrentHash(Long subjectId) {
        return memberRepository.findById(subjectId).map(Member::getRefreshTokenHash);
    }

    @Override
    public boolean save(Long subjectId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
        return memberRepository.updateRefreshTokenBackup(
                subjectId, tokenHash, expiresAt, now, MemberStatus.ACTIVE) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rotateIfMatches(
            Long subjectId, String oldTokenHash, String newTokenHash, LocalDateTime newExpiresAt, LocalDateTime now) {
        return memberRepository.rotateRefreshTokenBackupIfMatches(
                subjectId, oldTokenHash, newTokenHash, newExpiresAt, now, now, MemberStatus.ACTIVE) == 1;
    }

    @Override
    public boolean clearIfMatches(Long subjectId, String tokenHash, LocalDateTime now) {
        return memberRepository.clearRefreshTokenBackupIfHashMatches(subjectId, tokenHash, now) == 1;
    }

}
