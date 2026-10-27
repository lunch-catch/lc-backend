package com.lunchcatch.coupon.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// JSON 정수만 받아 소수와 문자열이 다른 이벤트 ID로 변환되지 않게 한다.
public class CouponEventIdDeserializer extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Long.class, "eventId는 JSON 정수여야 한다.");
        }
        return parser.getLongValue();
    }
}
