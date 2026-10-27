package com.lunchcatch.owner.listener;

import com.lunchcatch.owner.service.OwnerStoreActivationService;
import com.lunchcatch.store.contract.StoreFinalizedEvent;
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
