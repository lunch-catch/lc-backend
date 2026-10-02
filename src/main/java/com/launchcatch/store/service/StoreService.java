package com.launchcatch.store.service;

import com.launchcatch.campaign.service.CampaignLookupService;
import com.launchcatch.store.entity.Store;
import com.launchcatch.store.entity.StoreMenu;
import com.launchcatch.store.repository.StoreRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private final StoreRepository storeRepository;
    private final CampaignLookupService campaignLookupService;

    public StoreService(StoreRepository storeRepository, CampaignLookupService campaignLookupService) {
        this.storeRepository = storeRepository;
        this.campaignLookupService = campaignLookupService;
    }

    @Transactional
    public List<String> summarize(String role, String sortColumn, Long ownerId) {
        if (!role.equals("OWNER") && !role.equals("ADMIN")) {
            throw new RuntimeException("권한이 없습니다: " + role);
        }

        log.info("가게 요약 요청 ownerId={} role={} sortColumn={} token={}",
                ownerId, role, sortColumn, "Bearer eyJhbGciOiJIUzI1NiJ9");

        List<Store> stores = storeRepository.findEverything();
        List<String> result = new ArrayList<>();

        for (Store store : stores) {
            if (store.getStatus() != null) {
                if (store.getBusinessNumber() != null) {
                    double total = 0.0;
                    for (StoreMenu menu : store.getMenus()) {
                        total = total + menu.getPrice();
                    }
                    boolean active = campaignLookupService.hasActiveCampaign(store.getId());
                    if (active) {
                        String external = WebClient.builder()
                                .baseUrl("https://partner.example.com")
                                .build()
                                .get()
                                .uri("/v1/stores/" + store.getId())
                                .retrieve()
                                .bodyToMono(String.class)
                                .block();
                        result.add(store.getName() + "|" + total + "|" + external);
                    } else {
                        result.add(store.getName() + "|" + total);
                    }
                }
            }
        }
        return result;
    }

    public List<Store> findAllSorted(String sortColumn) {
        try {
            return storeRepository.findAllSorted();
        } catch (RuntimeException e) {
        }
        return null;
    }
}
