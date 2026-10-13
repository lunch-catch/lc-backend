package com.launchcatch.store.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.store.config.KakaoLocalConfig;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

class KakaoLocalClientTest {
    @Test
    void 검색어를_인코딩하고_위치_없이_정확도순으로_검색한다() {
        var sent = new AtomicReference<ClientRequest>();
        var client = client(request -> {
            sent.set(request);
            return json("""
                    {"meta":{"is_end":true},"documents":[
                      {"id":"123","place_name":"런치캐치","phone":"02-1234-5678",
                       "road_address_name":"서울 강남구 테헤란로 1","x":"127.0","y":"37.5",
                       "category_name":"음식점"}
                    ]}
                    """);
        });

        var result = client.search("한글 + &?/#%{가게}");

        var request = sent.get();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.url().getHost()).isEqualTo("dapi.kakao.com");
        assertThat(request.url().getPath()).isEqualTo("/v2/local/search/keyword.json");
        assertThat(request.url().getFragment()).isNull();
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("KakaoAK test-key");
        var params = UriComponentsBuilder.fromUri(request.url()).build().getQueryParams();
        assertThat(params.keySet()).containsExactlyInAnyOrder("query", "sort", "page", "size");
        assertThat(UriUtils.decode(params.getFirst("query"), StandardCharsets.UTF_8))
                .isEqualTo("한글 + &?/#%{가게}");
        assertThat(params.getFirst("sort")).isEqualTo("accuracy");
        assertThat(params.getFirst("page")).isEqualTo("1");
        assertThat(params.getFirst("size")).isEqualTo("15");
        assertThat(result.places()).singleElement().satisfies(place -> {
            assertThat(place.placeId()).isEqualTo("123");
            assertThat(place.placeName()).isEqualTo("런치캐치");
            assertThat(place.phone()).isEqualTo("02-1234-5678");
            assertThat(place.roadAddress()).isEqualTo("서울 강남구 테헤란로 1");
            assertThat(place.latitude()).isEqualByComparingTo("37.5");
            assertThat(place.longitude()).isEqualByComparingTo("127.0");
        });
    }

    @Test
    void 빈_전화번호와_없는_도로명_주소는_null로_반환한다() {
        var client = client(request -> json("""
                {"documents":[{"id":"123","place_name":"런치캐치","phone":"  ","x":"127","y":"37"}]}
                """));

        assertThat(client.search("런치캐치").places()).singleElement().satisfies(place -> {
            assertThat(place.phone()).isNull();
            assertThat(place.roadAddress()).isNull();
        });
    }

    @Test
    void 정상적인_빈_검색_결과는_성공한다() {
        assertThat(client(request -> json("{\"documents\":[]}")).search("없는가게").places()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void 일시적_외부_장애는_재시도하지_않고_503_오류로_변환한다(int status) {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var client = client(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                    .body("upstream unavailable").build());
        });

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE);
                    assertThat(e.getCause()).isNotNull();
                });
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void 카카오가_요청을_거절하면_502_오류로_변환한다(int status) {
        var client = client(request -> Mono.just(ClientResponse.create(HttpStatus.valueOf(status)).build()));

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"documents\":null}", "{\"documents\":[null]}",
            "{\"documents\":[{\"id\":\"123\",\"place_name\":\"런치캐치\",\"x\":\"bad\",\"y\":\"37\"}]}",
            "{\"documents\":[{\"id\":\"123\",\"place_name\":\"런치캐치\",\"x\":\"127\",\"y\":\"91\"}]}",
            "{\"documents\":[{\"id\":\"123\",\"place_name\":\"런치캐치\",\"x\":\"181\",\"y\":\"37\"}]}",
            "{\"documents\":[{\"id\":\"123\"}]}"})
    void 잘못된_응답을_빈_결과로_처리하지_않는다(String body) {
        var client = client(request -> json(body));

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED));
    }

    @Test
    void 연결에_실패하면_503_오류와_원인을_보존한다() {
        var failure = new WebClientRequestException(new IOException("connection failed"), HttpMethod.GET,
                URI.create("https://dapi.kakao.com"), new HttpHeaders());
        var client = client(request -> Mono.error(failure));

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE);
                    assertThat(e.getCause()).isSameAs(failure);
                });
    }

    @Test
    void 카카오가_JSON이_아닌_응답_형식을_주면_502로_실패한다() {
        var client = client(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "text/html").body("<html>upstream error</html>").build()));

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED));
    }

    @Test
    void 응답이_버퍼_한도를_넘으면_502로_실패한다() {
        var client = client(request -> json("{\"ignored\":\"" + "a".repeat(300_000) + "\",\"documents\":[]}"));

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED));
    }

    @Test
    void 응답이_끝나지_않아도_전체_타임아웃에서_503으로_실패한다() {
        var http = new KakaoLocalConfig().kakaoLocalWebClient(
                WebClient.builder().exchangeFunction(request -> Mono.never()), "test-key");
        var client = new KakaoLocalClient(http, "test-key", "50ms");

        assertThatThrownBy(() -> client.search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE));
    }

    @Test
    void API_키가_없으면_외부_요청을_보내지_않는다() {
        var http = new KakaoLocalConfig().kakaoLocalWebClient(WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("키가 없으면 외부 요청을 보내지 않아야 한다");
        }), " ");

        assertThatThrownBy(() -> new KakaoLocalClient(http, " ", "1s").search("런치캐치"))
                .isInstanceOfSatisfying(StoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "invalid"})
    void 타임아웃_설정이_잘못되면_클라이언트를_생성하지_않는다(String timeout) {
        var http = new KakaoLocalConfig().kakaoLocalWebClient(WebClient.builder(), "test-key");

        assertThatThrownBy(() -> new KakaoLocalClient(http, "test-key", timeout))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KakaoLocalClient client(ExchangeFunction exchange) {
        var http = new KakaoLocalConfig().kakaoLocalWebClient(
                WebClient.builder().exchangeFunction(exchange), "test-key");
        return new KakaoLocalClient(http, "test-key", "2s");
    }

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json").body(body).build());
    }
}
