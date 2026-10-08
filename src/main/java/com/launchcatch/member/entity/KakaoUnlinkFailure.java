package com.launchcatch.member.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 카카오 unlink 아웃박스다. 탈퇴 트랜잭션에서 이 행을 먼저 만들고, unlink 가 성공하면 지운다.
 *
 * 행을 먼저 만드는 것이 핵심이다. "실패한 뒤에 기록" 으로 두면 탈퇴 커밋과 기록 사이에서
 * 프로세스가 죽는 구간이 모두 유실이 되고, 회원은 WITHDRAWN 인데 카카오 연결은 영원히
 * 남는다. 먼저 만들어 두면 그 사이에 무엇이 멈춰도 다음 배치가 집어 올린다.
 *
 * 그래서 이 행의 존재는 "실패" 가 아니라 "아직 해제되지 않음" 을 뜻한다. 더 이상 재시도하지
 * 않기로 한 것만 resolved 로 닫고 이유를 남긴다.
 *
 * attempt_count 는 관측용이다. 조회 조건은 resolved 만 본다.
 */
@Entity
@Table(name = "kakao_unlink_failure")
@AttributeOverride(name = "id", column = @Column(name = "kakao_unlink_failure_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoUnlinkFailure extends BaseTimeEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private boolean resolved;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 20)
    private KakaoUnlinkStopReason stopReason;

    private KakaoUnlinkFailure(Member member) {
        this.member = member;
        this.providerUserId = member.getProviderUserId();
    }

    /*
     * 탈퇴 트랜잭션에서 부른다. 카카오 회원번호를 사본으로 들고 있는 것은 탈퇴 뒤 member 행의
     * 개인정보가 비식별 처리되어도 해제 대상을 알아야 하기 때문이다.
     */
    public static KakaoUnlinkFailure create(Member member) {
        if (member == null) {
            throw new IllegalArgumentException("member must not be null");
        }
        return new KakaoUnlinkFailure(member);
    }

    /*
     * 재시도 한 번이 실패했다. 한도에 닿으면 그 자리에서 닫는다.
     *
     * 원 시도의 실패는 세지 않는다. 그 실패 때문에 이 행이 남아 있는 것이고, 세면 한도가
     * 실제 재시도 횟수보다 한 번 적어진다.
     */
    public int recordRetryFailure(int maxAttempts) {
        attemptCount++;
        if (attemptCount >= maxAttempts) {
            stop(KakaoUnlinkStopReason.EXHAUSTED);
        }
        return attemptCount;
    }

    /*
     * 같은 회원이 다시 탈퇴했다. 남아 있던 행을 처음 상태로 돌린다.
     *
     * 재가입은 대기 행을 지우므로 보통 이 자리에 행이 없다. 그래도 지우지 못한 행이 남아
     * 있으면 UNIQUE(member_id) 때문에 새 행을 넣을 수 없고, 그 행이 resolved 로 닫혀 있으면
     * 이번 탈퇴의 해제가 한 번도 시도되지 않는다. 그 침묵을 막는다.
     */
    public void reopen() {
        this.attemptCount = 0;
        this.resolved = false;
        this.stopReason = null;
    }

    /*
     * 카카오가 영구적으로 거부했다. 재시도 횟수는 건드리지 않는다.
     * 횟수로 종료를 표현하면 "다섯 번 시도했다" 와 "한 번 만에 거부당했다" 가 같은 값이 된다.
     */
    public void rejectPermanently() {
        stop(KakaoUnlinkStopReason.REJECTED);
    }

    private void stop(KakaoUnlinkStopReason reason) {
        this.resolved = true;
        this.stopReason = reason;
    }
}
