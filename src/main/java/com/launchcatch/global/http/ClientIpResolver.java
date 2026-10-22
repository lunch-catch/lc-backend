package com.launchcatch.global.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * 요청을 보낸 클라이언트의 IP 를 정한다. 로그의 clientIp, 로그인 시도 제한, 관리자 로그인 감사가 같은 값을 쓴다.
 *
 * ALB 는 받은 X-Forwarded-For 끝에 자기가 본 접속 IP 를 덧붙인다. 앞쪽 항목은 클라이언트가 마음대로
 * 넣을 수 있으므로 마지막 항목만 믿는다. 이 앱의 8080 은 보안 그룹에서 ALB 에게만 열려 있어야 한다.
 * 그렇지 않으면 직접 접속한 클라이언트가 마지막 항목까지 꾸밀 수 있다 (lc-backend #722).
 */
public final class ClientIpResolver {

    // IPv4 와 IPv6 에 쓰이는 문자만 받고 길이를 IPv6 최대치로 막는다. 형식 검사가 아니라 로그 인젝션을 막는 것이 목적이다
    private static final Pattern SAFE_IP = Pattern.compile("^[0-9a-fA-F.:]{1,45}$");

    private ClientIpResolver() {
    }

    /*
     * 헤더가 없거나 마지막 항목의 형식이 이상하면 서블릿 컨테이너가 본 접속 IP 로 돌아간다.
     * split 에 -1 을 주는 것은 "1.2.3.4," 처럼 끝이 빈 헤더에서 빈 항목을 버리지 않으려는 것이다. 버리면 앞의 꾸민 값이 마지막이 된다.
     */
    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] addresses = forwarded.split(",", -1);
        String candidate = addresses[addresses.length - 1].trim();
        return SAFE_IP.matcher(candidate).matches() ? candidate : request.getRemoteAddr();
    }
}
