package com.launchcatch.billing.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/*
 * 토스 오류 응답을 코드로 읽는 판단이다. 게이트웨이의 실패 분류와 서킷의 실패 집계가 같은 기준을 쓰도록 한곳에 둔다.
 *
 * 토스는 HTTP 상태 코드만으로 원인을 가를 수 없다. 카드사 거절(REJECT_CARD_COMPANY)과 한도 초과, FDS 차단도
 * 403 으로 오기 때문이다. 그래서 키 설정 오류인지는 상태가 아니라 오류 코드로 본다.
 */
public final class TossErrorCodes {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // 우리 쪽 키 설정이 틀렸다는 코드다. 모든 호출이 같은 이유로 실패한다.
    private static final Set<String> CONFIG_ERROR_CODES = Set.of(
            "UNAUTHORIZED_KEY", "FORBIDDEN_REQUEST", "INCORRECT_BASIC_AUTH_FORMAT", "INVALID_API_KEY");

    private TossErrorCodes() {
    }

    /** 본문에서 토스 오류 코드를 읽는다. 토스 오류 본문이 아니면 null 이다. */
    public static String codeOf(WebClientResponseException e) {
        try {
            JsonNode code = MAPPER.readTree(e.getResponseBodyAsByteArray()).path("code");
            return code.isTextual() ? code.asText() : null;
        } catch (Exception parseFailure) {
            return null;
        }
    }

    /*
     * 키 설정이 틀려 토스가 인증 단계에서 거부한 응답이다. 결제 처리까지 가지 않았다.
     * 401 은 항상 해당하고, 그 밖의 상태는 설정 오류 코드가 올 때만 해당한다.
     */
    public static boolean isConfigFailure(WebClientResponseException e) {
        // Set.of 는 contains(null) 에서 NPE 를 던지므로 코드가 있을 때만 묻는다.
        String code = codeOf(e);
        return e.getStatusCode().value() == 401 || (code != null && CONFIG_ERROR_CODES.contains(code));
    }

    // 상태가 403 인데 토스 오류 본문이 아니다. 게이트웨이나 프록시가 앞에서 막은 경우라 설정 문제로 본다.
    public static boolean isUnexplainedForbidden(WebClientResponseException e) {
        return e.getStatusCode().value() == 403 && codeOf(e) == null;
    }
}
