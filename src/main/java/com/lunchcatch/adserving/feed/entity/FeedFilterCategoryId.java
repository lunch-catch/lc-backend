package com.lunchcatch.adserving.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedFilterCategoryId implements Serializable {

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "category_code", nullable = false, updatable = false)
    private String categoryCode;
}
