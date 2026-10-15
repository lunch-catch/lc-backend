package com.launchcatch.store.service;

import com.launchcatch.store.StoreImageUrlResolver;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.math.BigDecimal;
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
        // 조회 대상은 최종 등록된 가게이며 필수 이미지 누락을 정상 응답으로 숨기지 않는다.
        return stores.findAllById(storeIds).stream()
                .map(row -> new StoreInfo(row.storeId(), row.name(), images.resolve(requireImageKey(row.imageObjectKey())),
                        toCoordinate(row.latitude()), toCoordinate(row.longitude())))
                .toList();
    }

    @Override
    public boolean canManageFinalizedStore(Long ownerId, Long storeId) {
        if (ownerId == null || ownerId <= 0 || storeId == null || storeId <= 0) {
            throw new IllegalArgumentException("양수인 점주 ID와 가게 ID가 필요합니다.");
        }
        // 조회 장애는 false로 바꾸지 않고 전파해 권한 없음과 시스템 장애를 구분한다.
        return stores.canManageFinalizedStore(ownerId, storeId);
    }

    @Override
    public List<StoreMenuInfo> findMenusByIdIn(List<Long> menuIds) {
        validateIds(menuIds);
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return stores.findMenusByIdIn(menuIds).stream()
                .map(row -> new StoreMenuInfo(row.menuId(), row.storeId(), images.resolve(requireImageKey(row.imageObjectKey())),
                        row.name(), row.price()))
                .toList();
    }

    // DB의 DECIMAL 좌표를 공개 계약의 Double로 바꾸되 누락된 좌표는 null로 유지한다.
    private static Double toCoordinate(BigDecimal coordinate) {
        return coordinate == null ? null : coordinate.doubleValue();
    }

    // 최종 등록 시 필수인 이미지가 없으면 데이터 오류이므로 null이나 기본 이미지로 대신하지 않는다.
    private static String requireImageKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException("최종 등록된 가게 또는 메뉴의 검증된 이미지가 누락되었습니다.");
        }
        return objectKey;
    }

    private static void validateIds(List<Long> ids) {
        Objects.requireNonNull(ids, "ID 목록이 필요합니다.");
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("ID 목록에는 양수인 ID만 사용할 수 있습니다.");
        }
    }
}