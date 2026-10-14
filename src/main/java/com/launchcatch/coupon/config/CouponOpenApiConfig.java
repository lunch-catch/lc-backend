package com.launchcatch.coupon.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CouponOpenApiConfig {

    private static final String COUPON_LIST = "/v1/coupons";
    private static final String COUPON_DETAIL = "/v1/coupons/{memberCouponId}";
    private static final String OWNER_COUPON_USAGE = "/v1/owner/stores/{storeId}/coupon-usages";

    @Bean
    OpenApiCustomizer couponResponseDocumentation() {
        // Swagger가 null 필드를 생략하지 않도록 응답 예시를 JSON 트리로 보존한다.
        ObjectMapper exampleMapper = Json31.mapper().copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
        CouponDetailResponse coupon = couponExample();
        Map<String, ResponseEnvelope<?>> successExamples = Map.of(
                COUPON_LIST, ResponseEnvelope.success(CursorPageResponse.of(List.of(coupon), null)),
                COUPON_DETAIL, ResponseEnvelope.success(coupon),
                "/v1/coupons/issuance-limit", ResponseEnvelope.success(
                        new CouponIssuanceLimitResponse(LocalDate.of(2026, 10, 13), 3, 3, true)),
                "/v1/coupons/summary", ResponseEnvelope.success(new CouponSummaryResponse(12, 8, 24000)),
                OWNER_COUPON_USAGE, ResponseEnvelope.success(CursorPageResponse.of(List.of(
                        new CouponUsageHistoryResponse(5001L, 1001L, 3000,
                                OffsetDateTime.parse("2026-09-30T12:30:00+09:00"))), null)));

        return openApi -> {
            Schema<?> couponPage = openApi.getComponents().getSchemas().get("CursorPageResponseCouponDetailResponse");
            couponPage.getProperties().get("nextPageToken").setTypes(Set.of("string", "null"));
            Schema<?> usagePage = openApi.getComponents().getSchemas().get("CursorPageResponseCouponUsageHistoryResponse");
            usagePage.getProperties().get("nextPageToken").setTypes(Set.of("string", "null"));
            successExamples.forEach((path, successExample) -> openApi.getPaths().get(path).getGet()
                    .getResponses().get("200").getContent().values()
                    .forEach(media -> media.setExample(exampleMapper.valueToTree(successExample))));
        };
    }

    private CouponDetailResponse couponExample() {
        return new CouponDetailResponse(
                1001L, 10L, "런치식당", "https://cdn.example.com/stores/10/logo-v1.jpg",
                DiscountTargetType.MENU, DiscountType.AMOUNT, 3000,
                new CouponDetailResponse.TargetMenu(701L, "숯불갈비 정식", 12000, 9000), MemberCouponStatus.ISSUED, true,
                OffsetDateTime.parse("2026-10-13T11:00:00+09:00"),
                OffsetDateTime.parse("2026-10-13T11:00:00+09:00"),
                OffsetDateTime.parse("2026-10-13T14:00:00+09:00"), null, null);
    }
}
