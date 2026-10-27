package com.lunchcatch.adserving.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SwipeLogId implements Serializable {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "serve_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID serveId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    public static SwipeLogId of(UUID serveId, Long memberId) {
        return new SwipeLogId(serveId, memberId);
    }
}
