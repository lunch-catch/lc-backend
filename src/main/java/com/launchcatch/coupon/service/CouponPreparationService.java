package com.launchcatch.coupon.service;

import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.CouponDiscountPrice;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponPreparationService {

    private final CampaignQueryService campaigns;
    private final StoreQueryService stores;
    private final CouponEventRepository events;
    private final CouponBatchRepository batches;
    private final BatchAttemptGuard guard;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CouponPreparationService(CampaignQueryService campaigns, StoreQueryService stores,
            CouponEventRepository events, CouponBatchRepository batches, BatchAttemptGuard guard,
            Clock clock, PlatformTransactionManager manager) {
        this.campaigns = campaigns;
        this.stores = stores;
        this.events = events;
        this.batches = batches;
        this.guard = guard;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public int prepare(LocalDate date, int attemptNo) {
        int count = 0;
        for (CampaignInfo candidate : campaigns.findServableOn(date)) {
            count += Objects.requireNonNull(transactions.execute(status -> prepareOne(candidate.campaignId(), date, attemptNo)));
        }
        return count;
    }

    /*
     * 캠페인을 잠근 뒤 현재 상태를 다시 확인해 캠페인 종료와 회차 생성이 엇갈리지 않게 한다.
     * 회차와 모든 재고를 한 트랜잭션에 저장하므로 재고 일부만 만들어진 회차는 남지 않는다.
     * 배치 전체를 재시도할 때 이미 커밋한 회차는 건너뛰고 실패한 회차부터 다시 만든다.
     */
    private int prepareOne(long campaignId, LocalDate date, int attemptNo) {
        guard.assertCurrentAttempt(CouponBatchTask.PREPARE.jobName(), date, attemptNo);
        CampaignInfo campaign = campaigns.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new IllegalStateException("회차 생성 대상 캠페인이 없다: " + campaignId));
        if ((campaign.status() != CampaignStatus.ACTIVE && campaign.status() != CampaignStatus.PAUSED)
                || date.isBefore(campaign.startDate()) || date.isAfter(campaign.endDate())) {
            return 0;
        }
        if (events.findByCampaignIdAndBusinessDate(campaignId, date).isPresent()) {
            return 0;
        }
        StoreInfo store = stores.findAllById(List.of(campaign.storeId())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("최종 등록된 매장이 없다: " + campaign.storeId()));
        StoreMenuInfo menu = null;
        if (campaign.discountTargetType() == com.launchcatch.campaign.contract.DiscountTargetType.MENU) {
            menu = stores.findMenusByIdIn(List.of(campaign.targetMenuId())).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("할인 대상 메뉴가 없다: " + campaign.targetMenuId()));
            if (!menu.storeId().equals(store.storeId())) {
                throw new IllegalStateException("할인 대상 메뉴가 캠페인의 매장에 속하지 않는다.");
            }
        }
        Long price = menu == null ? null : CouponDiscountPrice.calculate(menu.price(),
                DiscountType.valueOf(campaign.discountType().name()), campaign.discountValue());
        CouponEvent.Snapshot snapshot = new CouponEvent.Snapshot(store.storeId(), store.storeName(), store.storeImageUrl(),
                DiscountTargetType.valueOf(campaign.discountTargetType().name()),
                DiscountType.valueOf(campaign.discountType().name()), campaign.discountValue(),
                menu == null ? null : menu.menuId(), menu == null ? null : menu.menuName(),
                menu == null ? null : menu.price(), price);
        CouponEvent event = CouponEvent.prepare(campaignId, date, campaign.issueQuantity(),
                date.atTime(campaign.usableStartTime()), date.atTime(campaign.usableEndTime()), snapshot);
        event.initializeCampaignState(campaign.status() == CampaignStatus.ACTIVE, campaign.statusChangedAt());
        events.saveAndFlush(event);
        batches.prepareInventory(event.getId(), event.getIssueQuantity(), event.getUsableStartAt(),
                event.getUsableEndAt(), LocalDateTime.now(clock));
        return 1;
    }

}
