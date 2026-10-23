package com.launchcatch.store.service;

import com.launchcatch.store.StoreImageUrlResolver;
import com.launchcatch.store.repository.StoreDetailData;
import com.launchcatch.store.contract.OwnerStoreSnapshot;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.dto.StoreDetailResponse.BusinessHour;
import com.launchcatch.store.dto.StoreDetailResponse.Image;
import com.launchcatch.store.dto.StoreDetailResponse.Menu;
import com.launchcatch.store.dto.StoreDetailResponse.ActiveCampaign;
import com.launchcatch.store.dto.StoreDetailResponse.TargetMenu;
import com.launchcatch.store.dto.StoreDetailResponse;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreDetailRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class StoreDetailService {
    private final StoreDetailRepository stores;
    private final OwnerStoreQueryRepository details;
    private final StoreImageUrlResolver images;
    private final Clock clock;

    public StoreDetailService(StoreDetailRepository stores, OwnerStoreQueryRepository details,
            @Value("${cdn.base-url:}") String baseUrl, Clock clock) {
        this.stores = stores;
        this.details = details;
        this.images = new StoreImageUrlResolver(baseUrl);
        this.clock = clock;
    }

    public StoreDetailResponse find(long storeId) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("가게 ID는 양수여야 합니다.");
        }
        var store = stores.findFinalized(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
        var logo = stores.findImages(storeId, "LOGO").stream()
                .map(row -> image(storeId, "LOGO", row.objectKey(), row.sortOrder()))
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new StoreDetailResponse(store.storeId(), store.name(), store.businessCategory(), store.roadAddress(),
                store.phone(), store.latitude(), store.longitude(), details.findBusinessHours(storeId).stream().map(this::businessHour).toList(),
                logo,
                stores.findImages(storeId, "INTERIOR").stream()
                        .map(row -> image(storeId, "INTERIOR", row.objectKey(), row.sortOrder()))
                        .filter(Objects::nonNull).toList(),
                details.findMenus(storeId).stream().map(menu -> menu(storeId, menu)).toList(),
                stores.findActiveCampaign(storeId, LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))
                        .map(campaign -> campaign(storeId, campaign)).orElse(null));
    }

    private Image image(long storeId, String imageType, String objectKey, Integer sortOrder) {
        if (objectKey == null || objectKey.isBlank()) {
            log.warn("event=STORE_DETAIL_IMAGE_SKIPPED storeId={} imageType={} sortOrder={} reason=EMPTY_OBJECT_KEY fallback=OMIT_IMAGE",
                    storeId, imageType, sortOrder);
            return null;
        }
        return new Image(objectKey, images.resolve(objectKey), sortOrder);
    }

    private Menu menu(long storeId, OwnerStoreSnapshot.Menu menu) {
        var menuImage = menu.image();
        return new Menu(menu.menuId(), menu.name(), menu.price(), menu.description(), menu.sortOrder(),
                menuImage == null ? null : image(storeId, "MENU", menuImage.objectKey(), null));
    }

    private BusinessHour businessHour(OwnerStoreSnapshot.BusinessHour hour) {
        return new BusinessHour(hour.dayOfWeek(), hour.openTime(), hour.closeTime(), hour.isClosed(),
                hour.breakStartTime(), hour.breakEndTime(), hour.lastOrderTime());
    }

    private ActiveCampaign campaign(long storeId, StoreDetailData.Campaign campaign) {
        TargetMenu target = null;
        if (campaign.discountTargetType() == StoreDiscountTargetType.MENU) {
            if (campaign.targetMenuId() == null) {
                log.warn("event=STORE_DETAIL_CAMPAIGN_SKIPPED storeId={} campaignId={} reason=TARGET_MENU_MISSING fallback=OMIT_CAMPAIGN",
                        storeId, campaign.campaignId());
                return null;
            }
            target = new TargetMenu(campaign.targetMenuId(), campaign.targetMenuName(), campaign.targetMenuPrice());
        }
        return new ActiveCampaign(campaign.campaignId(), campaign.discountTargetType(), campaign.discountType(),
                campaign.discountValue(), target, campaign.usableStartTime(), campaign.usableEndTime());
    }
}
