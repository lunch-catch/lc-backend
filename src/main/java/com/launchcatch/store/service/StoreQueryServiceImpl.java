package com.launchcatch.store.service;

import com.launchcatch.store.StoreImageUrlResolver;
import com.launchcatch.store.repository.StoreOnboardingQueryRepository;
import com.launchcatch.store.contract.OwnerStoreOverview.Onboarding;
import com.launchcatch.store.contract.OwnerStoreOverview.Step;
import com.launchcatch.store.contract.OwnerStoreOverview;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Menu;
import com.launchcatch.store.contract.OwnerStoreSnapshot;

import com.launchcatch.store.contract.StoreAdCandidateInfo;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreMenuDataException;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.contract.StoreSummary;
import com.launchcatch.store.entity.StoreRegistrationStep;
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

// 가게 조회 공개 인터페이스의 구현으로, DB 조회 결과의 이미지 저장 키를 URL로 바꿔 전달한다.
@Service
@Transactional(readOnly = true)
class StoreQueryServiceImpl implements StoreQueryService {
    private static final int MAX_SUMMARY_OWNER_IDS = 100;
    private static final int MAX_OWNER_SEARCH_LIMIT = 10_000;

    private final StoreQueryRepository stores;
    private final StoreImageUrlResolver images;
    private final OwnerStoreQueryRepository ownerStores;
    private final StoreOnboardingQueryRepository onboarding;

    StoreQueryServiceImpl(StoreQueryRepository stores, OwnerStoreQueryRepository ownerStores, StoreOnboardingQueryRepository onboarding, @Value("${cdn.base-url:}") String baseUrl) {
        this.stores = stores;
        this.ownerStores = ownerStores;
        this.onboarding = onboarding;
        this.images = new StoreImageUrlResolver(baseUrl);
    }

    // 가게 ID 목록으로 광고 후보용 상호·업종·대표 이미지 URL·좌표를 조회하며, 등록 전이거나 좌표가 누락된 가게는 제외한다.
    @Override
    public List<StoreAdCandidateInfo> findAdCandidatesByStoreIds(List<Long> storeIds) {
        validateIds(storeIds);
        if (storeIds.size() > MAX_AD_CANDIDATE_STORE_IDS) {
            throw new IllegalArgumentException("광고 후보용 가게 ID는 최대 5000개까지 조회할 수 있습니다.");
        }
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return stores.findAdCandidatesByStoreIds(storeIds).stream()
                .map(row -> new StoreAdCandidateInfo(row.storeId(), row.name(), row.categoryCode(),
                        images.resolve(row.imageObjectKey()), row.latitude(), row.longitude()))
                .toList();
    }

    @Override
    public List<StoreInfo> findAllById(List<Long> storeIds) {
        validateIds(storeIds);
        if (storeIds.isEmpty()) {
            return List.of();
        }
        // TODO(#647): 테스트 이미지의 S3 업로드·검증·DB 등록과 쿠폰 테스트 완료 후 대표 이미지 필수 검증을 복원한다.
        return stores.findAllById(storeIds).stream()
                .map(row -> new StoreInfo(row.storeId(), row.name(),
                        images.resolve(row.imageObjectKey() == null || row.imageObjectKey().isBlank()
                                ? null : row.imageObjectKey()),
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
                .map(row -> new StoreMenuInfo(row.menuId(), row.storeId(),
                        images.resolve(requireImageKey(row.menuId(), row.imageObjectKey())), row.name(), row.price()))
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
                                : row.businessRegistrationNumber().substring(0, 3) + "-"
                                        + row.businessRegistrationNumber().substring(3, 5) + "-*****"))
                .toList();
    }

    // DB의 DECIMAL 좌표를 공개 계약의 Double로 바꾸되 누락된 좌표는 null로 유지한다.
    private static Double toCoordinate(BigDecimal coordinate) {
        return coordinate == null ? null : coordinate.doubleValue();
    }

    // 최종 등록 시 필수인 이미지가 없으면 데이터 오류이므로 null이나 기본 이미지로 대신하지 않는다.
    private static String requireImageKey(long menuId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new StoreMenuDataException(menuId);
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
        var readiness = onboarding.findByOwnerId(ownerId).readiness();
        boolean terms = readiness.terms();
        boolean basic = readiness.storeInfo();
        boolean location = readiness.location();
        boolean hasHours = store != null && !store.businessHours().isEmpty();
        boolean hours = readiness.hours();
        boolean imagesComplete = readiness.images();
        boolean hasMenus = readiness.menuCount() > 0;
        boolean menusValid = readiness.validMenus();
        var steps = List.of(
                new Step("TERMS", false, agreement != null, terms),
                new Step("STORE_INFO", false, store != null, basic),
                new Step("LOCATION", false, store != null && (store.kakaoPlaceId() != null
                        || store.roadAddress() != null || store.latitude() != null || store.longitude() != null), location),
                new Step("HOURS", false, hasHours, hours),
                new Step("IMAGES", false, store != null && (store.logoImage() != null
                        || !store.interiorImages().isEmpty()), imagesComplete),
                new Step("MENU", true, hasMenus, hasMenus && menusValid));
        boolean finalized = store != null && store.finalized();
        String resume = finalized ? null : steps.stream().filter(step -> !step.optional() && !step.completed())
                .map(Step::step).findFirst().orElse("MENU");
        String current = finalized ? null : progress.map(value -> value.resumeStep().name()).orElse("TERMS");
        return new OwnerStoreOverview(store, new Onboarding(current, resume,
                progress.map(value -> value.version()).orElse(0L),
                !finalized
                        && progress.map(value -> value.resumeStep() == StoreRegistrationStep.MENU).orElse(false)
                        && readiness.canFinalize(),
                agreement, steps));
    }

}
