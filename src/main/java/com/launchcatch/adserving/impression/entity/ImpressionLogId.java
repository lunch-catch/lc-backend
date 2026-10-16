package com.launchcatch.adserving.impression.entity;

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

// 중복 키는 (serveId, 요청 사용자)다. 다른 사용자가 먼저 보낸 무효 노출이 원래 사용자의 유효 노출을 막지 못한다(FR-097).
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class ImpressionLogId implements Serializable {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "serve_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID serveId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    public static ImpressionLogId of(UUID serveId, Long memberId) {
        return new ImpressionLogId(serveId, memberId);
    }
}
