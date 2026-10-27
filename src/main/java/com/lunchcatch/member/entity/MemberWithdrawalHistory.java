package com.lunchcatch.member.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import com.lunchcatch.member.contract.MemberStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_withdrawal_history")
@AttributeOverride(name = "id", column = @Column(name = "member_withdrawal_history_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWithdrawalHistory extends BaseTimeEntity {

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    @Column(name = "withdrawn_at", nullable = false)
    private LocalDateTime withdrawnAt;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    private MemberWithdrawalHistory(Member member, LocalDateTime withdrawnAt) {
        Objects.requireNonNull(member, "회원은 필수입니다.");
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new IllegalStateException("회원의 카카오 번호를 비우기 전에 탈퇴 이력을 기록해야 합니다.");
        }
        this.memberId = Objects.requireNonNull(member.getId(), "저장한 회원만 탈퇴할 수 있습니다.");
        this.providerUserId = Objects.requireNonNull(member.getProviderUserId(), "카카오 번호는 필수입니다.");
        this.withdrawnAt = Objects.requireNonNull(withdrawnAt, "탈퇴 시각은 필수입니다.");
        this.suspendedAt = member.getSuspendedAt();
    }

    public static MemberWithdrawalHistory create(Member member, LocalDateTime withdrawnAt) {
        return new MemberWithdrawalHistory(member, withdrawnAt);
    }
}
