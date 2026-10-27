package com.lunchcatch.store.service;

import com.lunchcatch.store.contract.StoreCampaignSummaryCommand;
import com.lunchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.lunchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.lunchcatch.store.contract.StoreDiscountTargetType;
import com.lunchcatch.store.contract.StoreDiscountType;
import com.lunchcatch.store.repository.StoreCampaignSummaryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 호출자의 트랜잭션에 참여하고 트랜잭션 없는 호출은 거부하여 캠페인 상태 변경과 요약 저장을 함께 롤백한다.
@Transactional(propagation = Propagation.MANDATORY)
class StoreCampaignSummaryUpdaterImpl implements StoreCampaignSummaryUpdater {
    // 한 SQL의 VALUES가 지나치게 커지지 않도록 저장할 행을 500개씩 나눈다.
    private static final int BATCH_SIZE = 500;
    private final StoreCampaignSummaryRepository summaryRepository;
    private final Clock clock;

    @Override
    public void update(long storeId, long campaignId, StoreCampaignSummaryCommand command) {
        validate(storeId, campaignId, command);
        if (command.discountTargetType() == StoreDiscountTargetType.MENU
                && !summaryRepository.menuBelongsToStore(storeId, command.targetMenuId())) {
            throw new IllegalArgumentException("할인 대상 메뉴가 해당 가게에 속하지 않습니다.");
        }
        summaryRepository.upsert(storeId, campaignId, command, LocalDateTime.now(clock));
    }

    @Override
    public void updateAll(List<StoreCampaignSummaryUpdate> updates) {
        if (updates == null) {
            throw new IllegalArgumentException("저장할 요약 목록이 필요합니다.");
        }
        // 첫 SQL을 실행하기 전에 전체 항목의 ID와 할인 값, 사용 가능 시간을 검증한다.
        for (StoreCampaignSummaryUpdate update : updates) {
            if (update == null) {
                throw new IllegalArgumentException("요약 목록에 null을 포함할 수 없습니다.");
            }
            validate(update.storeId(), update.campaignId(), update.command());
        }

        // 모든 묶음에 같은 갱신 시각을 사용하며 각 묶음은 호출자의 같은 트랜잭션에서 저장한다.
        LocalDateTime now = LocalDateTime.now(clock);
        for (int start = 0; start < updates.size(); start += BATCH_SIZE) {
            List<StoreCampaignSummaryUpdate> batch = updates.subList(start, Math.min(start + BATCH_SIZE, updates.size()));
            validateTargetMenus(batch);
            summaryRepository.upsertAll(batch, now);
        }
    }

    private void validateTargetMenus(List<StoreCampaignSummaryUpdate> updates) {
        // 전체 할인은 메뉴 소속 검사가 필요하지 않으므로 MENU 항목만 모은다.
        List<StoreCampaignSummaryUpdate> menuUpdates = updates.stream()
                .filter(update -> update.command().discountTargetType() == StoreDiscountTargetType.MENU).toList();
        if (menuUpdates.isEmpty()) {
            return;
        }
        List<Long> menuIds = menuUpdates.stream().map(update -> update.command().targetMenuId()).distinct().toList();
        // 항목마다 조회하지 않고 이 묶음의 메뉴 소유 가게를 한 번에 가져온다.
        Map<Long, Long> menuStoreIds = summaryRepository.findMenuStoreIds(menuIds);
        for (StoreCampaignSummaryUpdate update : menuUpdates) {
            // 메뉴가 없거나 다른 가게에 속하면 전체 일괄 저장을 실패시킨다.
            if (!Objects.equals(menuStoreIds.get(update.command().targetMenuId()), update.storeId())) {
                throw new IllegalArgumentException("할인 대상 메뉴가 해당 가게에 속하지 않습니다.");
            }
        }
    }

    private static void validate(long storeId, long campaignId, StoreCampaignSummaryCommand command) {
        if (storeId <= 0 || campaignId <= 0 || command == null || command.businessDate() == null
                || command.campaignStatus() == null || command.discountTargetType() == null
                || command.discountType() == null || command.usableStartTime() == null
                || command.usableEndTime() == null) {
            throw new IllegalArgumentException("가게와 캠페인 ID 및 요약의 필수값이 필요합니다.");
        }
        if (!command.usableEndTime().isAfter(command.usableStartTime())
                || command.discountValue() <= 0
                || (command.discountType() == StoreDiscountType.PERCENT && command.discountValue() > 100)) {
            throw new IllegalArgumentException("할인 값 또는 사용 가능 시간이 올바르지 않습니다.");
        }
        if ((command.discountTargetType() == StoreDiscountTargetType.MENU
                && (command.targetMenuId() == null || command.targetMenuId() <= 0))
                || (command.discountTargetType() == StoreDiscountTargetType.ALL && command.targetMenuId() != null)) {
            throw new IllegalArgumentException("MENU 할인에는 메뉴 ID가 필요하고 ALL 할인에는 메뉴 ID를 전달할 수 없습니다.");
        }
    }
}
