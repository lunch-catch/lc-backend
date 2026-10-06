package com.launchcatch.member.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "kakao_unlink_failure")
@AttributeOverride(name = "id", column = @Column(name = "kakao_unlink_failure_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoUnlinkFailure extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private boolean resolved;

    private KakaoUnlinkFailure(Member member) {
        this.member = member;
        this.providerUserId = member.getProviderUserId();
    }

    public static KakaoUnlinkFailure create(Member member) {
        return new KakaoUnlinkFailure(member);
    }
}
