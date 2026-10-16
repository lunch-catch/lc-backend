package com.launchcatch.adserving.wishlist.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
 * 찜 한 건이다. (사용자, 캠페인)마다 하나다(uk_wishlist_member_campaign).
 * 찜을 지우거나 캠페인이 끝나면 행을 지운다. 같은 찜의 두 번째 저장은 INSERT IGNORE 로 넘겨야 해서 저장은 저장소의 insertIgnore 가 한다.
 */
@Entity
@Table(name = "wishlist")
@AttributeOverride(name = "id", column = @Column(name = "wishlist_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends BaseTimeEntity {

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    // 피드 찜이면 출처 카드, 가게 상세 찜이면 null 이다
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "serve_id", updatable = false, columnDefinition = "BINARY(16)")
    private UUID serveId;

    private Wishlist(Long memberId, Long campaignId, Long storeId, UUID serveId) {
        this.memberId = Objects.requireNonNull(memberId);
        this.campaignId = Objects.requireNonNull(campaignId);
        this.storeId = Objects.requireNonNull(storeId);
        this.serveId = serveId;
    }

    // 피드에서 오른쪽으로 넘긴 찜이다.
    public static Wishlist fromFeed(Long memberId, Long campaignId, Long storeId, UUID serveId) {
        return new Wishlist(memberId, campaignId, storeId, Objects.requireNonNull(serveId));
    }
}
