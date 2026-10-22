package com.launchcatch.coupon.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.CannotIssueReason;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.EventState;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponEventStatusResponse;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.launchcatch.coupon.dto.CouponUseResponse;
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
import java.util.UUID;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CouponOpenApiConfig {

    private static final String COUPON_LIST = "/v1/coupons";
    private static final String COUPON_DETAIL = "/v1/coupons/{memberCouponId}";
    private static final String OWNER_COUPON_USAGE = "/v1/owner/stores/{storeId}/coupon-usages";

    @Bean
    OpenApiCustomizer couponResponseDocumentation(CouponQrImageEncoder imageEncoder) {
        // Swagger가 null 필드를 생략하지 않도록 응답 예시를 JSON 트리로 보존한다.
        ObjectMapper exampleMapper = Json31.mapper().copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
        CouponDetailResponse coupon = couponExample();
        ResponseEnvelope<CouponQrResponse> qrExample = ResponseEnvelope.success(new CouponQrResponse(
                imageEncoder.encode(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                OffsetDateTime.parse("2026-10-13T12:01:00+09:00")));
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
            openApi.getPaths().get("/v1/coupons/{memberCouponId}:generateQr").getPost()
                    .getResponses().get("200").getContent().values()
                    .forEach(media -> media.setExample(exampleMapper.valueToTree(qrExample)));
            openApi.getPaths().get("/v1/coupon-events:getStatus").getPost()
                    .getRequestBody().getContent().values()
                    .forEach(media -> media.setExample(Map.of("storeIds", List.of(10L, 20L, 30L))));
            openApi.getPaths().get("/v1/coupon-events:getStatus").getPost()
                    .getResponses().get("200").getContent().values()
                    .forEach(media -> media.setExample(exampleMapper.valueToTree(
                            ResponseEnvelope.success(eventStatusExample()))));
            openApi.getPaths().get(COUPON_LIST).getPost().getResponses().get("201").getContent().values()
                    .forEach(media -> media.setExample(exampleMapper.valueToTree(ResponseEnvelope.success(
                            new CouponIssueResponse(1001L, OffsetDateTime.parse("2026-10-15T11:00:00+09:00"))))));
            openApi.getPaths().get("/v1/owner/coupon-usages").getPost().getRequestBody().getContent().values()
                    .forEach(media -> media.setExample(Map.of("longitude", 127.0, "latitude", 37.5,
                            "qrToken", "6c75d5bc-cae3-4eeb-a573-fc3bcb1673a5")));
            openApi.getPaths().get("/v1/owner/coupon-usages").getPost().getResponses().get("201").getContent().values()
                    .forEach(media -> media.setExample(exampleMapper.valueToTree(ResponseEnvelope.success(
                            new CouponUseResponse(1001L, 5001L, 3000,
                                    OffsetDateTime.parse("2026-09-30T12:30:00+09:00"))))));
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

    private CouponEventStatusResponse eventStatusExample() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-14T12:00:00.123+09:00");
        return new CouponEventStatusResponse(now, List.of(
                new CouponEventStatusResponse.Item(10L, true, 501L, EventState.OPEN, 25,
                        now.withHour(12).withMinute(30).withNano(0), now.withHour(15).withNano(0),
                        false, false, false, CannotIssueReason.NOT_WISHED),
                new CouponEventStatusResponse.Item(20L, true, 502L, EventState.SOLD_OUT, 0,
                        now.withHour(11).withNano(0), now.withHour(14).withNano(0),
                        true, false, false, CannotIssueReason.ALREADY_ISSUED),
                new CouponEventStatusResponse.Item(30L, false, null, EventState.NO_EVENT, 0,
                        null, null, false, false, false, CannotIssueReason.NO_EVENT)));
    }
}
