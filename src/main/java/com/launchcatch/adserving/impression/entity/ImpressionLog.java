package com.launchcatch.adserving.impression.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
 * 노출 이벤트 1건이다. 무효 노출도 사유와 함께 남는다.
 * 무효면 사유가 반드시 있고 유효면 없다. 스키마가 CHECK 로 막지 않아 이 생성 지점에서 지킨다(V1 의 impression_log 주석).
 * 같은 키의 두 번째 저장은 INSERT IGNORE 로 넘겨야 해서 저장은 저장소의 insertAllIgnore 가 하고 이 엔티티는 읽을 때도 쓴다.
 */
@Entity
@Table(name = "impression_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImpressionLog {

    @EmbeddedId
    private ImpressionLogId id;

    // 노출을 받은 영업일이다. 보관 기간 삭제의 기준이다
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "store_id")
    private Long storeId;

    // 피드 엔진이 붙인 슬롯 이름표이고 serve 기록이 있을 때만 있다(#978)
    @Column(name = "slot_type")
    private String slotType;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "slot_position")
    private Integer slotPosition;

    @Column(name = "displayed_at", nullable = false)
    private LocalDateTime displayedAt;

    @Column(name = "lat_rounded", nullable = false)
    private BigDecimal latRounded;

    @Column(name = "lng_rounded", nullable = false)
    private BigDecimal lngRounded;

    @Enumerated(EnumType.STRING)
    @Column(name = "validity", nullable = false)
    private ImpressionValidity validity;

    @Enumerated(EnumType.STRING)
    @Column(name = "invalid_reason")
    private InvalidReason invalidReason;

    // 쿠폰이 소진된 뒤 도착한 유효 노출이다. 차감을 부르지 않는다(NOT_BILLED_SOLD_OUT)
    @Column(name = "sold_out", nullable = false)
    private boolean soldOut;

    @Column(name = "viewed_ms")
    private Integer viewedMs;

    private ImpressionLog(ImpressionLogId id, LocalDate businessDate, Long campaignId, Placement placement,
                          Sighting sighting, ImpressionValidity validity, InvalidReason invalidReason, boolean soldOut) {
        this.id = Objects.requireNonNull(id);
        this.businessDate = Objects.requireNonNull(businessDate);
        this.campaignId = Objects.requireNonNull(campaignId);
        if (placement != null) {
            this.storeId = placement.storeId();
            this.slotType = placement.slotType();
            this.slotPosition = placement.slotPosition();
        }
        Objects.requireNonNull(sighting);
        this.displayedAt = Objects.requireNonNull(sighting.displayedAt());
        this.latRounded = round(sighting.latitude());
        this.lngRounded = round(sighting.longitude());
        this.viewedMs = sighting.viewedMs();
        this.validity = Objects.requireNonNull(validity);
        this.invalidReason = invalidReason;
        this.soldOut = soldOut;
    }

    // 캠페인은 요청이 아니라 serve 기록의 것이다. 서버가 실제로 내려준 캠페인에만 과금한다(#323).
    public static ImpressionLog valid(UUID serveId, Long memberId, LocalDate businessDate, Placement placement,
                                      Sighting sighting, boolean soldOut) {
        return new ImpressionLog(ImpressionLogId.of(serveId, memberId), businessDate, placement.campaignId(),
                placement, sighting, ImpressionValidity.VALID, null, soldOut);
    }

    // 다른 사용자가 받은 카드다. 기록은 있어 가게와 자리를 남긴다.
    public static ImpressionLog notOwner(UUID serveId, Long memberId, LocalDate businessDate, Placement placement,
                                         Sighting sighting) {
        return new ImpressionLog(ImpressionLogId.of(serveId, memberId), businessDate, placement.campaignId(),
                placement, sighting, ImpressionValidity.INVALID, InvalidReason.NOT_OWNER, false);
    }

    // serve 기록이 없다. 캠페인은 요청이 보낸 값을 남긴다.
    public static ImpressionLog unknown(UUID serveId, Long memberId, LocalDate businessDate, Long requestedCampaignId,
                                        Sighting sighting) {
        return new ImpressionLog(ImpressionLogId.of(serveId, memberId), businessDate, requestedCampaignId, null,
                sighting, ImpressionValidity.INVALID, InvalidReason.EXPIRED_OR_UNKNOWN, false);
    }

    public boolean isValid() {
        return validity == ImpressionValidity.VALID;
    }

    // 정산에 넘길 노출이다. 무효 노출과 쿠폰이 소진된 뒤의 노출은 과금 판정을 받지 않는다(FR-066).
    public boolean needsDispatch() {
        return isValid() && !soldOut;
    }

    // 좌표는 소수점 3자리(약 100m)로 반올림해 남긴다(api-spec.md 개인정보).
    static BigDecimal round(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(3, RoundingMode.HALF_UP);
    }

    /*
     * 서버가 그 카드를 어느 캠페인, 어느 가게, 어느 자리로 내려줬는지다. serve 기록에서 꺼낸다.
     * 같은 타입 인자가 이어지면 순서를 바꿔 넘기기 쉬워 하나로 묶는다.
     */
    public record Placement(Long campaignId, Long storeId, String slotType, int slotPosition) {
    }

    // 카드가 보인 순간의 정보다. 클라이언트가 보낸 값이다.
    public record Sighting(LocalDateTime displayedAt, double latitude, double longitude, Integer viewedMs) {
    }
}
