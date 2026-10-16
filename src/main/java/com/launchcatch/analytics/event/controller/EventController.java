package com.launchcatch.analytics.event.controller;

import com.launchcatch.analytics.event.ClientEvent;
import com.launchcatch.analytics.event.EventWriteResult;
import com.launchcatch.analytics.event.controller.dto.EventBatchRequest;
import com.launchcatch.analytics.event.controller.dto.EventCollectionResponse;
import com.launchcatch.analytics.event.service.EventCollectionService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {

    private final EventCollectionService service;

    public EventController(EventCollectionService service) {
        this.service = service;
    }

    @PostMapping("/v1/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEnvelope<EventCollectionResponse> collect(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestBody EventBatchRequest request
    ) {
        List<ClientEvent> events = request == null || request.events() == null
                ? null
                : request.events().stream()
                        .map(event -> event == null ? null : event.toClientEvent())
                        .toList();
        EventWriteResult result = service.collect(user.getId(), events);
        return ResponseEnvelope.success(
                new EventCollectionResponse(result.accepted(), result.duplicated()));
    }
}
