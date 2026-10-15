package com.launchcatch.store.service;

import com.launchcatch.store.StoreImageUrlResolver;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 쿠폰이 사용하는 공개 인터페이스의 구현으로, DB 조회 결과의 이미지 저장 키를 URL로 바꿔 전달한다.
@Service
@Transactional(readOnly = true)
class StoreQueryServiceImpl implements StoreQueryService {
    private final StoreQueryRepository stores;
    private final StoreImageUrlResolver images;

    StoreQueryServiceImpl(StoreQueryRepository stores, @Value("${cdn.base-url:}") String baseUrl) {
        this.stores = stores;
        this.images = new StoreImageUrlResolver(baseUrl);
    }

    @Override
    public List<StoreInfo> findAllById(List<Long> storeIds) {
        validateIds(storeIds);
        if (storeIds.isEmpty()) {
            return List.of();
        }
        // 위치를 아직 저장하지 않은 가게도 반환하며 null 좌표를 그대로 전달해 쿠폰이 누락을 구분하게 한다.
        return stores.findAllById(storeIds).stream()
                .map(row -> new StoreInfo(row.storeId(), row.name(), images.resolve(row.imageObjectKey()),
                        row.latitude(), row.longitude()))
                .toList();
    }

    @Override
    public boolean canManageStore(Long ownerId, Long storeId) {
        if (ownerId == null || ownerId <= 0 || storeId == null || storeId <= 0) {
            throw new IllegalArgumentException("양수인 점주 ID와 가게 ID가 필요합니다.");
        }
        // 조회 장애는 false로 바꾸지 않고 전파해 권한 없음과 시스템 장애를 구분한다.
        return stores.canManageStore(ownerId, storeId);
    }

    @Override
    public List<StoreMenuInfo> findMenusByIdIn(List<Long> menuIds) {
        validateIds(menuIds);
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return stores.findMenusByIdIn(menuIds).stream()
                .map(row -> new StoreMenuInfo(row.menuId(), row.storeId(), images.resolve(row.imageObjectKey()),
                        row.name(), row.price()))
                .toList();
    }

    private static void validateIds(List<Long> ids) {
        Objects.requireNonNull(ids, "ID 목록이 필요합니다.");
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("ID 목록에는 양수인 ID만 사용할 수 있습니다.");
        }
    }
}