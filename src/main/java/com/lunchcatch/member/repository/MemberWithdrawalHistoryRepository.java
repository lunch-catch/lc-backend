package com.lunchcatch.member.repository;

import com.lunchcatch.member.entity.MemberWithdrawalHistory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MemberWithdrawalHistoryRepository extends JpaRepository<MemberWithdrawalHistory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberWithdrawalHistory> findFirstByProviderUserIdOrderByWithdrawnAtDescIdDesc(String providerUserId);

    boolean existsByProviderUserIdAndSuspendedAtIsNotNull(String providerUserId);

    boolean existsByProviderUserId(String providerUserId);

    Optional<MemberWithdrawalHistory> findByMemberId(Long memberId);
}
