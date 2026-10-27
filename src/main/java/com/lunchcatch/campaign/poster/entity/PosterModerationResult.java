package com.lunchcatch.campaign.poster.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 포스터 자동 검수 한 번의 결과다(#535). 검수할 때마다 한 행을 쌓고 고치지 않는다.
 * Poster 를 연관관계가 아니라 posterId 로만 참조한다. 포스터가 자기 검수 이력을 조회할 기능이 없어서다.
 */
@Entity
@Table(name = "poster_moderation_result")
@AttributeOverride(name = "id", column = @Column(name = "poster_moderation_result_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PosterModerationResult extends BaseTimeEntity {

    private static final int REASON_MAX_LENGTH = 200;

    @Column(name = "poster_id", nullable = false)
    private Long posterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ModerationResult result;

    @Column(length = REASON_MAX_LENGTH)
    private String reason;

    private PosterModerationResult(Long posterId, ModerationResult result, String reason) {
        this.posterId = Objects.requireNonNull(posterId, "posterId");
        this.result = Objects.requireNonNull(result, "result");
        this.reason = reason;
    }

    public static PosterModerationResult pass(Long posterId) {
        return new PosterModerationResult(posterId, ModerationResult.PASS, null);
    }

    // chk_poster_moderation_result 와 같이 FAIL 은 사유가 하나 이상 있어야 한다.
    public static PosterModerationResult fail(Long posterId, List<ModerationFailReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("FAIL needs at least one reason");
        }
        String joined = reasons.stream().map(Enum::name).collect(Collectors.joining(","));
        if (joined.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + REASON_MAX_LENGTH + " characters");
        }
        return new PosterModerationResult(posterId, ModerationResult.FAIL, joined);
    }
}
