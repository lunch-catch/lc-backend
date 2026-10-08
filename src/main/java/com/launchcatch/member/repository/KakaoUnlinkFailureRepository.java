package com.launchcatch.member.repository;

import com.launchcatch.member.entity.KakaoUnlinkFailure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KakaoUnlinkFailureRepository extends JpaRepository<KakaoUnlinkFailure, Long> {

    Optional<KakaoUnlinkFailure> findByMember_Id(Long memberId);

    void deleteByMember_Id(Long memberId);
}
