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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // TODO: S3 대표 이미지 준비 후 쿠폰 테스트용 null 허용을 제거하고 필수 이미지 검증을 복구한다.
        var logo = stores.findImages(storeId, "LOGO").stream().findFirst()
                .map(row -> image(row.objectKey(), row.sortOrder())).orElse(null);
        return new StoreDetailResponse(store.storeId(), store.name(), store.businessCategory(), store.roadAddress(),
                store.phone(), store.latitude(), store.longitude(), details.findBusinessHours(storeId).stream().map(this::businessHour).toList(),
                logo,
                stores.findImages(storeId, "INTERIOR").stream().map(row -> image(row.objectKey(), row.sortOrder())).toList(),
                details.findMenus(storeId).stream().map(this::menu).toList(),
                stores.findActiveCampaign(storeId, LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))
                        .map(this::campaign).orElse(null));
    }

    private Image image(String objectKey, Integer sortOrder) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException("등록 완료된 가게의 검증된 이미지가 누락되었습니다.");
        }
        return new Image(objectKey, images.resolve(objectKey), sortOrder);
    }

    private Menu menu(OwnerStoreSnapshot.Menu menu) {
        var menuImage = menu.image();
        // TODO: S3 메뉴 이미지 준비 후 테스트용 null 허용을 제거하고 필수 이미지 검증을 복구한다.
        return new Menu(menu.menuId(), menu.name(), menu.price(), menu.description(), menu.sortOrder(),
                menuImage == null ? null : image(menuImage.objectKey(), null));
    }

    private BusinessHour businessHour(OwnerStoreSnapshot.BusinessHour hour) {
        return new BusinessHour(hour.dayOfWeek(), hour.openTime(), hour.closeTime(), hour.isClosed(),
                hour.breakStartTime(), hour.breakEndTime(), hour.lastOrderTime());
    }

    private ActiveCampaign campaign(StoreDetailData.Campaign campaign) {
        TargetMenu target = null;
        if (campaign.discountTargetType() == StoreDiscountTargetType.MENU) {
            if (campaign.targetMenuId() == null) {
                throw new IllegalStateException("활성 캠페인의 할인 대상 메뉴가 누락되었습니다.");
            }
            target = new TargetMenu(campaign.targetMenuId(), campaign.targetMenuName(), campaign.targetMenuPrice());
        }
        return new ActiveCampaign(campaign.campaignId(), campaign.discountTargetType(), campaign.discountType(),
                campaign.discountValue(), target, campaign.usableStartTime(), campaign.usableEndTime());
    }
}
