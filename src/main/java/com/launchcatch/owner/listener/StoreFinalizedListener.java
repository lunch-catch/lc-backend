package com.launchcatch.owner.listener;

import com.launchcatch.owner.service.OwnerStoreActivationService;
import com.launchcatch.store.contract.StoreFinalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreFinalizedListener {
    private final OwnerStoreActivationService activation;

    @EventListener
    public void on(StoreFinalizedEvent event) {
        activation.activate(event.ownerId());
    }
}
