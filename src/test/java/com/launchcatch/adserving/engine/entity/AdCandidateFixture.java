package com.launchcatch.adserving.engine.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 읽기 매핑만 있는 AdCandidate 를 테스트에서 만든다.
 * 기본값은 강남역 근처 가게, 전체 성별, 모든 연령대, 반경 3000m, 20% 할인이다. 바꿀 값만 고쳐 쓴다.
 */
public final class AdCandidateFixture {

    public static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    public static final double LATITUDE = 37.4979;
    public static final double LONGITUDE = 127.0276;

    private final AdCandidate candidate = BeanUtils.instantiateClass(AdCandidate.class);

    private AdCandidateFixture(long campaignId) {
        AdCandidateId id = BeanUtils.instantiateClass(AdCandidateId.class);
        ReflectionTestUtils.setField(id, "businessDate", DATE);
        ReflectionTestUtils.setField(id, "campaignId", campaignId);
        ReflectionTestUtils.setField(candidate, "id", id);
        set("storeId", campaignId + 1000);
        set("storeName", "가게" + campaignId);
        set("categoryCode", "KOREAN");
        set("latitude", BigDecimal.valueOf(LATITUDE));
        set("longitude", BigDecimal.valueOf(LONGITUDE));
        set("targetRadius", (short) 3000);
        set("targetGender", TargetGender.ALL);
        set("targetAgeGroups", "AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS");
        set("discountTarget", DiscountTarget.ALL);
        set("discountType", DiscountType.PERCENT);
        set("discountValue", 20);
        set("usableStartTime", LocalTime.of(12, 0));
        set("usableEndTime", LocalTime.of(14, 0));
        set("issueQuantity", 30);
        set("campaignCreatedAt", LocalDateTime.of(2026, 9, 1, 9, 0).plusMinutes(campaignId));
        set("campaignState", CandidateCampaignState.SERVABLE);
    }

    public static AdCandidateFixture campaign(long campaignId) {
        return new AdCandidateFixture(campaignId);
    }

    // 기준 좌표에서 북쪽으로 meters 만큼 떨어진 자리에 둔다
    public AdCandidateFixture northBy(double meters) {
        return set("latitude", BigDecimal.valueOf(LATITUDE + meters / 111_195.0));
    }

    public AdCandidateFixture set(String field, Object value) {
        ReflectionTestUtils.setField(candidate, field, value);
        return this;
    }

    public AdCandidate build() {
        return candidate;
    }
}
