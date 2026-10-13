package com.launchcatch.store.client;

import com.launchcatch.store.contract.PlaceSearchResult;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
public class KakaoLocalClient implements PlaceSearchClient {
    private static final int RESULT_LIMIT = 15;
    private final WebClient webClient;
    private final boolean apiKeyConfigured;
    private final Duration requestTimeout;

    public KakaoLocalClient(@Qualifier("kakaoLocalWebClient") WebClient webClient,
                            @Value("${kakao.local.rest-api-key}") String restApiKey,
                            @Value("${kakao.local.request-timeout}") String requestTimeout) {
        this.webClient = webClient;
        this.apiKeyConfigured = !restApiKey.isBlank();
        this.requestTimeout = DurationStyle.detectAndParse(requestTimeout);
        if (this.requestTimeout.isZero() || this.requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    @Override
    @CircuitBreaker(name = "kakaoPlaceSearch", fallbackMethod = "unavailable")
    public PlaceSearchResult search(String query) {
        if (!apiKeyConfigured) {
            throw new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE);
        }
        /*
         * 사용자가 다시 검색할 수 있으므로 서버는 자동 재시도하지 않는다.
         * 반복 호출로 카카오 쿼터와 요청 대기 시간이 늘어나는 것을 막고 일시적 장애를 즉시 알린다.
         */
        return webClient.get()
                .uri(builder -> builder.path("/v2/local/search/keyword.json")
                        .queryParam("query", "{query}")
                        .queryParam("sort", "accuracy")
                        .queryParam("page", 1)
                        .queryParam("size", RESULT_LIMIT)
                        .build(query))
                .retrieve()
                .bodyToMono(PlaceKeywordSearchResponse.class)
                .switchIfEmpty(Mono.error(new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED)))
                .map(this::toResult)
                .timeout(requestTimeout)
                .onErrorMap(WebClientResponseException.class, this::responseFailure)
                .onErrorMap(WebClientRequestException.class,
                        cause -> new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE, cause))
                .onErrorMap(TimeoutException.class,
                        cause -> new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE, cause))
                .onErrorMap(DecodingException.class,
                        cause -> new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED, cause))
                .onErrorMap(UnsupportedMediaTypeException.class,
                        cause -> new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED, cause))
                .onErrorMap(DataBufferLimitException.class,
                        cause -> new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED, cause))
                .block();
    }

    private PlaceSearchResult toResult(PlaceKeywordSearchResponse response) {
        if (response.documents() == null) {
            throw new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED);
        }
        try {
            return new PlaceSearchResult(response.documents().stream().limit(RESULT_LIMIT)
                    .map(this::toPlace).toList());
        } catch (IllegalArgumentException cause) {
            throw new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED, cause);
        }
    }

    private PlaceSearchResult.Place toPlace(PlaceKeywordSearchResponse.Document document) {
        if (document == null || document.id() == null || document.id().isBlank()
                || document.placeName() == null || document.placeName().isBlank()
                || document.x() == null || document.y() == null) {
            throw new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED);
        }
        BigDecimal latitude = new BigDecimal(document.y());
        BigDecimal longitude = new BigDecimal(document.x());
        if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED);
        }
        return new PlaceSearchResult.Place(document.id(), document.placeName(), nullableText(document.phone()),
                nullableText(document.roadAddress()), latitude, longitude);
    }

    private String nullableText(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private StoreException responseFailure(WebClientResponseException cause) {
        boolean unavailable = cause.getStatusCode().value() == 429 || cause.getStatusCode().is5xxServerError();
        return new StoreException(unavailable ? StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE
                : StoreErrorCode.KAKAO_PLACE_SEARCH_FAILED, cause);
    }

    private PlaceSearchResult unavailable(String query, CallNotPermittedException cause) {
        throw new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE, cause);
    }
}
