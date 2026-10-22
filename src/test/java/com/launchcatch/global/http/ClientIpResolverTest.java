package com.launchcatch.global.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/members/me");
        request.setRemoteAddr("10.0.1.20");
        return request;
    }

    @Test
    void 헤더가_하나면_그_주소를_쓴다() {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        // when
        String ip = ClientIpResolver.resolve(request);

        // then
        assertThat(ip).isEqualTo("203.0.113.5");
    }

    // ALB 가 덧붙인 마지막 항목만 믿는다. 앞쪽은 클라이언트가 꾸민 값이다
    @Test
    void 꾸민_앞쪽_항목은_버리고_마지막_주소를_쓴다() {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 203.0.113.5");

        // when
        String ip = ClientIpResolver.resolve(request);

        // then
        assertThat(ip).isEqualTo("203.0.113.5");
    }

    @Test
    void IPv6_주소도_받아들인다() {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 2001:db8::1");

        // when
        String ip = ClientIpResolver.resolve(request);

        // then
        assertThat(ip).isEqualTo("2001:db8::1");
    }

    @Test
    void 헤더가_없으면_접속_주소를_쓴다() {
        // when
        String ip = ClientIpResolver.resolve(request());

        // then
        assertThat(ip).isEqualTo("10.0.1.20");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "1.2.3.4, not an ip", "1.2.3.4, 203.0.113.5\nINFO fake", "1.2.3.4,"})
    void 마지막_항목이_비거나_형식이_틀리면_접속_주소를_쓴다(String forwarded) {
        // given
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", forwarded);

        // when
        String ip = ClientIpResolver.resolve(request);

        // then
        assertThat(ip).isEqualTo("10.0.1.20");
    }
}
