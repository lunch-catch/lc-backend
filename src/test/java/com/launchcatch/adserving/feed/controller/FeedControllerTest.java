package com.launchcatch.adserving.feed.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.service.FeedService;
import com.launchcatch.global.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FeedControllerTest {

    private final FeedController controller = new FeedController(mock(FeedService.class));

    @Test
    void 요청_제한에_걸리면_429와_남은_초를_Retry_After에_싣는다() {
        ResponseEntity<ResponseEnvelope<Void>> response = controller.handleRateLimited(new FeedRateLimitedException(37));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(response.getBody().code()).isEqualTo("ADSERVING-003");
    }
}
