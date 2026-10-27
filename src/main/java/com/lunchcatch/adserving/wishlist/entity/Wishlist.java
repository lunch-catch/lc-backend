package com.lunchcatch.adserving.wishlist.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
 * 찜 한 건이다. (사용자, 캠페인)마다 하나다(uk_wishlist_member_campaign).
 * 찜을 지우면 행을 지운다. 캠페인이 끝나면 종료 시각을 채우고 끝난 날을 빼고 3일 뒤 00:00 작업이 지운다(#548).
 * 같은 찜의 두 번째 저장은 INSERT IGNORE 로 넘겨야 해서 저장은 저장소의 insertIgnore 가 한다. 종료 표시도 저장소의 벌크 UPDATE 가 한다.
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

    // 캠페인이 끝난 시각이다. 진행 중이면 null 이다
    @Column(name = "campaign_ended_at", insertable = false, updatable = false)
    private LocalDateTime campaignEndedAt;

    private Wishlist(Long memberId, Long campaignId, Long storeId, UUID serveId) {
        this.memberId = Objects.requireNonNull(memberId);
        this.campaignId = Objects.requireNonNull(campaignId);
        this.storeId = Objects.requireNonNull(storeId);
        this.serveId = serveId;
    }

    public boolean isCampaignEnded() {
        return campaignEndedAt != null;
    }

    // 피드에서 오른쪽으로 넘긴 찜이다.
    public static Wishlist fromFeed(Long memberId, Long campaignId, Long storeId, UUID serveId) {
        return new Wishlist(memberId, campaignId, storeId, Objects.requireNonNull(serveId));
    }

    /*
     * 가게 상세에서 찜해두기로 넣은 찜이다.
     * 출처 카드가 없다.
     */
    public static Wishlist fromStoreDetail(Long memberId, Long campaignId, Long storeId) {
        return new Wishlist(memberId, campaignId, storeId, null);
    }
}
