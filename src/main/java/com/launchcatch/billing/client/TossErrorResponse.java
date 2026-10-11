package com.launchcatch.billing.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 토스가 4xx, 5xx로 돌려주는 오류 바디 {"code": "...", "message": "..."} 이다.
@JsonIgnoreProperties(ignoreUnknown = true)
record TossErrorResponse(String code, String message) {
}
