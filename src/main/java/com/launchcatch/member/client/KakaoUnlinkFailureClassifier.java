package com.launchcatch.member.client;

import java.util.Set;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/*
 * unlink 실패를 더 시도해 볼 것인지로 가른다.
 *
 * 이 판정은 KakaoCircuitBreakerConfig 의 판정과 다른 질문이다. 그쪽은 "서킷을 열 것인가",
 * 즉 지금 카카오를 더 부르는 것이 무의미한가를 본다. 여기는 "이 행을 영원히 포기할 것인가"
 * 를 본다. 두 답이 갈리는 자리가 있다.
 *
 *  - 401, 403: 우리 Admin Key 설정 문제다. 서킷은 열지 않는다(설정 실수 하나로 무관한 요청까지
 *    막으면 안 된다). 그러나 포기해서도 안 된다 — 키를 고치면 그대로 성공할 요청이고, 포기하면
 *    오설정 기간에 탈퇴한 회원 전부의 카카오 연결이 영구히 남는다.
 *  - 429: 호출 한도다. 서킷도 일시적 실패로 세고 여기서도 재시도 대상이다.
 *
 * 그래서 영구 포기는 "카카오가 이 대상에 대해 할 일이 없다" 고 답한 경우로 좁힌다.
 * 400 은 대상이 유효하지 않다는 답이고 404 는 그런 사용자가 없다는 답이다. 둘 다 다시 물어도
 * 같은 답이 온다.
 */
public final class KakaoUnlinkFailureClassifier {

    /** 다시 물어도 같은 답이 오는 상태 코드다. 그 밖의 4xx 는 재시도 대상이다. */
    private static final Set<Integer> TERMINAL_STATUSES = Set.of(400, 404);

    private KakaoUnlinkFailureClassifier() {
    }

    public static boolean isTerminal(Throwable failure) {
        WebClientResponseException responseException = findResponseException(failure);
        return responseException != null
                && TERMINAL_STATUSES.contains(responseException.getStatusCode().value());
    }

    public static String causeType(Throwable failure) {
        WebClientResponseException responseException = findResponseException(failure);
        if (responseException != null) {
            return responseException.getStatusCode().value() + "_" + responseException.getClass().getSimpleName();
        }
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static WebClientResponseException findResponseException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException responseException) {
                return responseException;
            }
        }
        return null;
    }
}
