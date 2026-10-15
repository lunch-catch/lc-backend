package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.BudgetPlanStatusChanged;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 캠페인 상태 변경을 그날 예산 계획의 중단 표시로 옮긴다.
 *
 * 정본(DB)은 발행자와 같은 트랜잭션에서 바꾸고, 차감 핫패스가 읽는 Valkey 해시는 커밋 뒤에 맞춘다.
 * 커밋 전에 해시를 쓰면 발행자가 롤백했을 때 롤백된 상태가 해시에만 남는다.
 *
 * 그날 계획 행이 없으면 아무것도 하지 않는다. 행은 00:00 예약이 만들고, 예약이 없는 날은 차감도 없다.
 * 다음 00:00 예약은 캠페인의 그때 상태로 행을 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetPlanStatusService {

    private final BudgetDailyPlanRepository budgetDailyPlanRepository;
    private final DailySpendCounter spendCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /*
     * 발행자와 같은 트랜잭션에서 돈다. 여기서 던진 예외는 캠페인 상태 변경까지 되돌리므로
     * 조건부 UPDATE 한 문장과 통보 하나만 한다.
     */
    @Transactional
    public void apply(CampaignStatusChangedEvent event) {
        applyStatus(event.campaignId(), event.businessDate(), !event.servable(), event.changedAt());
    }

    /*
     * 계획 행의 중단 표시를 changedAt 시점의 상태로 맞추고 바꿨는지 돌려준다. 행이 없거나 이미 그보다 늦은 상태를
     * 반영했으면 바꾸지 않는다. 바꿨으면 커밋 뒤에 해시도 맞춘다.
     *
     * 상태 변경 수신과 00:00 예약의 상태 재확인(DailyReservationRecorder.resyncStatus)이 함께 쓴다. 예약이 캠페인을
     * 읽은 뒤 계획을 만들기 전에 커밋된 중단은 행이 없어 수신에서 사라지므로, 예약이 계획을 커밋한 뒤 캠페인을 다시
     * 읽어 이 메서드로 반영한다. changedAt 이 없으면 견줄 시각이 없어 바꾸지 않는다.
     */
    @Transactional
    public boolean applyStatus(Long campaignId, LocalDate businessDate, boolean paused, LocalDateTime changedAt) {
        if (changedAt == null) {
            return false;
        }
        int updated = budgetDailyPlanRepository.applyStatus(campaignId, businessDate,
                paused, changedAt, LocalDateTime.now(clock));
        if (updated == 0) {
            return false;
        }
        eventPublisher.publishEvent(new BudgetPlanStatusChanged(campaignId, businessDate, paused, changedAt));
        return true;
    }

    /*
     * 커밋 뒤에 해시를 맞춘다. 실패해도 캠페인 상태 변경은 이미 커밋됐으므로 던지지 않는다.
     *
     * 실패하면 해시를 지운다. 낡은 중단 표시가 남아 있으면 차감이 그것을 믿고 중단된 캠페인에
     * 과금하는데, 해시가 없으면 차감이 DB 정본으로 다시 만든다. 지우기까지 실패하면 ERROR 로 남긴다.
     */
    public void mirror(BudgetPlanStatusChanged changed) {
        try {
            spendCounter.applyStatus(changed.businessDate(), changed.campaignId(),
                    changed.paused(), changed.changedAt());
        } catch (DataAccessException e) {
            log.warn("event=BUDGET_STATUS_MIRROR_FAILED campaignId={} businessDate={} paused={}",
                    changed.campaignId(), changed.businessDate(), changed.paused(), e);
            evict(changed);
        }
    }

    private void evict(BudgetPlanStatusChanged changed) {
        try {
            spendCounter.evict(changed.businessDate(), changed.campaignId());
        } catch (DataAccessException e) {
            log.error("event=BUDGET_STATUS_HASH_EVICT_FAILED campaignId={} businessDate={} paused={}",
                    changed.campaignId(), changed.businessDate(), changed.paused(), e);
        }
    }
}
