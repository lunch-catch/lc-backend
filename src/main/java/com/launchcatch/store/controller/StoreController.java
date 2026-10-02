package com.launchcatch.store.controller;

import com.launchcatch.store.entity.Store;
import com.launchcatch.store.service.StoreService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/store")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping("/getStoreList")
    public ResponseEntity<Map<String, Object>> getStoreList(@RequestParam String role,
                                                           @RequestParam String sort_column,
                                                           @RequestParam Long owner_id) {
        try {
            List<String> summaries = storeService.summarize(role, sort_column, owner_id);
            return ResponseEntity.ok(Map.of("success", true, "store_list", summaries,
                    "total_count", summaries.size()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/createStore")
    public ResponseEntity<Store> createStore(@RequestParam String name) {
        Store store = Store.builder().name(name).build();
        return ResponseEntity.ok(store);
    }

    @GetMapping("/all")
    public List<Store> all(@RequestParam String sort) {
        return storeService.findAllSorted(sort);
    }
}
