package com.launchcatch.owner.service;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.response.PageResponse;
import com.launchcatch.owner.dto.AdminOwnerListQuery;
import com.launchcatch.owner.dto.AdminOwnerResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.AdminOwnerRepository;
import com.launchcatch.owner.repository.AdminOwnerRow;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.contract.StoreSummary;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminOwnerService {

    private static final int PAGE_SIZE = 20;
    private static final int STORE_SEARCH_LIMIT = 10000;

    private final AdminOwnerRepository owners;
    private final StoreQueryService stores;

    @Transactional(readOnly = true)
    public PageResponse<AdminOwnerResponse> search(AdminOwnerListQuery query) {
        List<Long> ownerIds = null;
        if (query.keyword() != null) {
            if (query.keyword().matches("[0-9]+")) {
                try {
                    ownerIds = List.of(Long.parseLong(query.keyword()));
                } catch (NumberFormatException e) {
                    return PageResponse.of(List.of(), query.page(), PAGE_SIZE, 0);
                }
            } else {
                ownerIds = stores.findOwnerIdsByNameContaining(query.keyword(), STORE_SEARCH_LIMIT);
            }
            if (ownerIds.isEmpty()) {
                return PageResponse.of(List.of(), query.page(), PAGE_SIZE, 0);
            }
        }
        var page = owners.search(query.status(),
                query.joinedFrom() == null ? null : query.joinedFrom().atStartOfDay(),
                query.joinedTo() == null ? null : query.joinedTo().plusDays(1).atStartOfDay(),
                ownerIds, PageRequest.of(query.page(), PAGE_SIZE));
        if (page.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), PAGE_SIZE, page.getTotalElements());
        }
        var summaries = stores.findSummariesByOwnerIds(page.stream().map(AdminOwnerRow::ownerId).toList())
                .stream().collect(Collectors.toMap(StoreSummary::ownerId, Function.identity()));
        var items = page.stream().map(owner -> response(owner, summaries.get(owner.ownerId()))).toList();
        return PageResponse.of(items, query.page(), PAGE_SIZE, page.getTotalElements());
    }

    private AdminOwnerResponse response(AdminOwnerRow owner, StoreSummary store) {
        boolean withdrawn = owner.status() == OwnerStatus.WITHDRAWN;
        String businessNo = store == null ? null : store.businessRegistrationNumber();
        if (withdrawn && businessNo != null) {
            businessNo = "***-**-*****";
        }
        return new AdminOwnerResponse(owner.ownerId(), store == null ? null : store.name(), owner.status(),
                store != null && store.finalized(), businessNo,
                owner.joinedAt().atZone(ClockConfig.ZONE).toOffsetDateTime(),
                withdrawn || owner.lastLoginAt() == null ? null
                        : owner.lastLoginAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
    }
}
