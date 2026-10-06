package com.launchcatch.member.repository;

import com.launchcatch.member.entity.MemberProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberProfileRepository extends JpaRepository<MemberProfile, Long> {

    Optional<MemberProfile> findByMember_Id(Long memberId);

    boolean existsByMember_IdAndOnboardingCompletedAtIsNotNull(Long memberId);
}
