package com.launchcatch.member.repository;

import com.launchcatch.member.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProviderUserId(String providerUserId);
}
