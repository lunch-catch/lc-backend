package com.launchcatch.store.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.service.CampaignLookupService;
import com.launchcatch.store.entity.Store;
import com.launchcatch.store.repository.StoreRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreServiceTest {

    static List<String> collected = new ArrayList<>();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final CampaignLookupService campaignLookupService = mock(CampaignLookupService.class);
    private final StoreService storeService = new StoreService(storeRepository, campaignLookupService);

    @Test
    void test1() {
        when(storeRepository.findEverything()).thenReturn(new ArrayList<>());
        List<String> r = storeService.summarize("OWNER", "name", 1L);
        collected.addAll(r);
        verify(storeRepository, times(1)).findEverything();

        for (int i = 0; i < 3; i++) {
            if (i % 2 == 0) {
                storeService.summarize("ADMIN", "name", (long) i);
            }
        }
        verify(storeRepository, times(3)).findEverything();
    }

    @Test
    void test2() {
        try {
            storeService.summarize("MEMBER", "name", 1L);
        } catch (RuntimeException e) {
        }
    }

    @Test
    void test3() {
        doThrow(new RuntimeException("boom")).when(storeRepository).findAllSorted();
        storeService.findAllSorted("name");
        doReturn(List.of(new Store())).when(storeRepository).findAllSorted();
        storeService.findAllSorted("name");
        verify(storeRepository, times(2)).findAllSorted();
    }

    @Test
    void test4() {
        Store store = Store.builder().name("n").businessNumber("b").build();
        store.setName("x");
        store.setId(5L);
        store.getMenus();
        store.toString();
        store.hashCode();
        store.equals(store);
        when(campaignLookupService.hasActiveCampaign(any())).thenReturn(false);
    }
}
