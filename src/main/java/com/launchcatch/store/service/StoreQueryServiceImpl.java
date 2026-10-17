package com.launchcatch.store.service;

import com.launchcatch.store.StoreImageUrlResolver;
import com.launchcatch.store.contract.OwnerStoreOverview.Onboarding;
import com.launchcatch.store.contract.OwnerStoreOverview.Step;
import com.launchcatch.store.contract.OwnerStoreOverview.TermsAgreement;
import com.launchcatch.store.contract.OwnerStoreOverview;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Menu;
import com.launchcatch.store.contract.OwnerStoreSnapshot;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.contract.StoreSummary;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 쿠폰이 사용하는 공개 인터페이스의 구현으로, DB 조회 결과의 이미지 저장 키를 URL로 바꿔 전달한다.
@Service
@Transactional(readOnly = true)
class StoreQueryServiceImpl implements StoreQueryService {
    private static final int MAX_SUMMARY_OWNER_IDS = 100;
    private static final int MAX_OWNER_SEARCH_LIMIT = 10_000;

    private final StoreQueryRepository stores;
    private final StoreImageUrlResolver images;
    private final OwnerStoreQueryRepository ownerStores;

    StoreQueryServiceImpl(StoreQueryRepository stores, OwnerStoreQueryRepository ownerStores, @Value("${cdn.base-url:}") String baseUrl) {
        this.stores = stores;
        this.ownerStores = ownerStores;
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
                .map(row -> new StoreMenuInfo(row.menuId(), row.storeId(), images.resolve(requireImageKey(row.imageObjectKey())),
                        row.name(), row.price()))
                .toList();
    }

    @Override
    public List<Long> findOwnerIdsByNameContaining(String keyword, int limit) {
        Objects.requireNonNull(keyword, "검색어가 필요합니다.");
        if (keyword.isBlank() || limit <= 0 || limit > MAX_OWNER_SEARCH_LIMIT) {
            throw new IllegalArgumentException("비어 있지 않은 검색어와 1~10000 범위의 limit가 필요합니다.");
        }
        var ownerIds = stores.findOwnerIdsByNameContaining(keyword, (long) limit + 1);
        if (ownerIds.size() > limit) {
            throw new IllegalArgumentException("상호 검색 결과가 limit를 초과했습니다. 검색어를 좁혀 주세요.");
        }
        return ownerIds;
    }

    @Override
    public List<StoreSummary> findSummariesByOwnerIds(Collection<Long> ownerIds) {
        validateIds(ownerIds);
        if (ownerIds.size() > MAX_SUMMARY_OWNER_IDS) {
            throw new IllegalArgumentException("점주 ID는 최대 100개까지 조회할 수 있습니다.");
        }
        if (ownerIds.isEmpty()) {
            return List.of();
        }
        return stores.findSummariesByOwnerIds(ownerIds).stream()
                .map(row -> new StoreSummary(row.ownerId(), row.storeId(), row.name(), row.finalized(),
                        row.businessRegistrationNumber() == null ? null
                                : row.businessRegistrationNumber().substring(0, 3) + "**"
                                        + row.businessRegistrationNumber().substring(5)))
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

    private static void validateIds(Collection<Long> ids) {
        Objects.requireNonNull(ids, "ID 목록이 필요합니다.");
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("ID 목록에는 양수인 ID만 사용할 수 있습니다.");
        }
    }
    @Override
    public Optional<OwnerStoreSnapshot> findOwnerStore(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("양수인 점주 ID가 필요합니다.");
        }
        return ownerStores.findByOwnerId(ownerId).map(store -> {
            var savedImages = ownerStores.findImages(store.storeId());
            Image logo = savedImages.stream().filter(image -> image.sortOrder() == null)
                    .findFirst().map(this::resolveImage).orElse(null);
            var interior = savedImages.stream().filter(image -> image.sortOrder() != null)
                    .map(this::resolveImage).toList();
            var menus = ownerStores.findMenus(store.storeId()).stream().map(menu -> new Menu(
                    menu.menuId(), menu.name(), menu.price(), menu.description(), menu.sortOrder(),
                    resolveImage(menu.image()))).toList();
            return new OwnerStoreSnapshot(store.storeId(), store.finalized(), store.name(), store.businessCategory(),
                    store.representativeName(), store.businessRegistrationNumber(), store.phone(),
                    store.businessVerificationStatus(), store.businessVerifiedAt(), store.kakaoPlaceId(),
                    store.roadAddress(), store.latitude(), store.longitude(), ownerStores.findBusinessHours(store.storeId()),
                    logo, interior, menus);
        });
    }

    private Image resolveImage(Image image) {
        return image == null ? null : new Image(image.objectKey(), images.resolve(image.objectKey()), image.sortOrder());
    }

    @Override
    public OwnerStoreOverview getOwnerStoreOverview(Long ownerId) {
        var store = findOwnerStore(ownerId).orElse(null);
        var progress = ownerStores.findProgress(ownerId);
        var agreement = ownerStores.findTermsAgreement(ownerId).orElse(null);
        boolean terms = agreement != null && agreement.agreed() && agreement.withdrawnAt() == null;
        boolean basic = store != null && present(store.name()) && present(store.businessCategory())
                && present(store.representativeName()) && present(store.businessRegistrationNumber())
                && present(store.phone()) && "VERIFIED".equals(store.businessVerificationStatus())
                && store.businessVerifiedAt() != null;
        boolean location = store != null && present(store.kakaoPlaceId()) && present(store.roadAddress())
                && store.latitude() != null && store.longitude() != null;
        boolean hours = store != null && !store.businessHours().isEmpty();
        boolean imagesComplete = store != null && store.logoImage() != null && store.interiorImages().size() <= 3;
        boolean hasMenus = store != null && !store.menus().isEmpty();
        boolean menusValid = store == null || store.menus().size() <= 3 && store.menus().stream()
                .allMatch(menu -> present(menu.name()) && menu.price() >= 0 && menu.image() != null);
        var steps = List.of(
                new Step("TERMS", false, agreement != null, terms),
                new Step("STORE_INFO", false, store != null, basic),
                new Step("LOCATION", false, store != null && (store.kakaoPlaceId() != null
                        || store.roadAddress() != null || store.latitude() != null || store.longitude() != null), location),
                new Step("HOURS", false, hours, hours),
                new Step("IMAGES", false, store != null && (store.logoImage() != null
                        || !store.interiorImages().isEmpty()), imagesComplete),
                new Step("MENU", true, hasMenus, hasMenus && menusValid));
        boolean finalized = store != null && store.finalized();
        String resume = finalized ? null : steps.stream().filter(step -> !step.optional() && !step.completed())
                .map(Step::step).findFirst().orElse("MENU");
        String current = finalized ? null : progress.map(value -> value.resumeStep().name()).orElse("TERMS");
        return new OwnerStoreOverview(store, new Onboarding(current, resume,
                progress.map(value -> value.version()).orElse(0L),
                !finalized && terms && basic && location && hours && imagesComplete && menusValid,
                agreement, steps));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
