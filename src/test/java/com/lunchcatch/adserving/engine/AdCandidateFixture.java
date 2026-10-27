package com.lunchcatch.adserving.engine;

import com.lunchcatch.adserving.engine.entity.AdCandidate;
import com.lunchcatch.adserving.engine.entity.AdCandidateId;
import com.lunchcatch.adserving.engine.entity.CandidateCampaignState;
import com.lunchcatch.adserving.engine.entity.DiscountTarget;
import com.lunchcatch.adserving.engine.entity.DiscountType;
import com.lunchcatch.adserving.engine.entity.TargetGender;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 후보 스냅샷은 00:00 배치가 만들고 엔티티에 생성 경로가 아직 없다.
 * 테스트는 리플렉션으로 채운다. 기본값은 전체 성별, 전 연령대, 노출 반경 3000m, 예약액 10만 원, 단가 10원이다.
 */
public final class AdCandidateFixture {

    public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 1);

    private final AdCandidate candidate = BeanUtils.instantiateClass(AdCandidate.class);

    private AdCandidateFixture(long campaignId, double latitude, double longitude) {
        AdCandidateId id = BeanUtils.instantiateClass(AdCandidateId.class);
        ReflectionTestUtils.setField(id, "businessDate", BUSINESS_DATE);
        ReflectionTestUtils.setField(id, "campaignId", campaignId);
        set("id", id);
        set("storeId", campaignId + 1000);
        set("storeName", "가게" + campaignId);
        set("categoryCode", "KOREAN");
        set("latitude", BigDecimal.valueOf(latitude));
        set("longitude", BigDecimal.valueOf(longitude));
        set("targetRadius", (short) 3000);
        set("targetGender", TargetGender.ALL);
        set("targetAgeGroups", "AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS");
        set("discountTarget", DiscountTarget.ALL);
        set("discountType", DiscountType.PERCENT);
        set("discountValue", 20);
        set("usableStartTime", LocalTime.of(12, 0));
        set("usableEndTime", LocalTime.of(14, 0));
        set("issueQuantity", 30);
        set("campaignCreatedAt", java.time.LocalDateTime.of(2026, 9, 1, 0, 0));
        set("campaignState", CandidateCampaignState.SERVABLE);
        set("reservedAmount", 100_000L);
        set("unitPrice", 10L);
    }

    public static AdCandidateFixture candidate(long campaignId, double latitude, double longitude) {
        return new AdCandidateFixture(campaignId, latitude, longitude);
    }

    public AdCandidateFixture set(String field, Object value) {
        ReflectionTestUtils.setField(candidate, field, value);
        return this;
    }

    public AdCandidate build() {
        return candidate;
    }
}
