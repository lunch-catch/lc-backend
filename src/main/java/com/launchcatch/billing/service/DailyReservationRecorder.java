package com.launchcatch.billing.service;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 예약 단계의 쓰기를 트랜잭션 하나씩으로 묶는다. DailyReservationService 가 부른다.
 *
 * 예약은 점주 하나가 한 트랜잭션이다. 수천 캠페인을 한 트랜잭션에 묶으면 그동안 점주 계정 잠금이 모두 잡혀
 * 충전 승인과 환불 승인이 기다리고, 중간에 실패하면 끝난 점주의 예약까지 되돌아간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReservationRecorder {

    private final PointAccountRepository accountRepository;
    private final PointLedgerRepository ledgerRepository;
    private final BudgetDailyPlanRepository planRepository;
    private final Clock clock;

    /*
     * 한 점주의 캠페인에 잔액을 배분한다. campaigns 는 캠페인 ID 오름차순이고, 잔액이 모자라면 앞에서부터 채운다(D-4).
     * 예약액은 min(적용 하루 예산, 남은 잔액)이다. 0 이면 RESERVE 없이 예약액 0 인 계획만 만든다(V52 102행 (5)).
     *
     * 점주 계정 행을 먼저 잠근다(D-8). 충전 승인, 조정, 환불 승인이 같은 행을 잠가서 잔액을 읽고 RESERVE 를 쓰는
     * 사이에 잔액이 바뀌지 않는다.
     *
     * 계정 행이 없는 점주(충전한 적이 없다)도 잠그기 전에 행을 만든다(C-3 의 무변경 upsert). 잠글 행이 없으면
     * 이어받기로 두 실행이 겹칠 때 둘 다 "계획 없음"을 보고 같은 계획을 INSERT 해서 한쪽이 UNIQUE 위반으로 실패한다.
     * 잔액은 그대로 0 이다.
     *
     * 그날 계획이 이미 있는 캠페인은 건너뛴다. 계획과 RESERVE 가 이 트랜잭션에서 함께 커밋되므로 계획이 있으면 예약도
     * 끝난 것이다. 남은 캠페인은 그 예약이 빠진 잔액에서 이어서 배분한다.
     *
     * 계획의 처음 중단 여부와 상태 변경 시각은 캠페인 상태를 옮긴다. ACTIVE 가 아니면(PAUSED(OWNER), PAUSED(NO_POINTS))
     * 중단으로 두어야 판정 전에 들어온 노출이 과금되지 않는다.
     */
    @Transactional
    public void reserveForOwner(Long ownerId, List<CampaignInfo> campaigns, LocalDate businessDate, long unitPrice) {
        accountRepository.createIfAbsent(ownerId, LocalDateTime.now(clock));
        accountRepository.lockByOwnerId(ownerId);
        List<Long> campaignIds = campaigns.stream().map(CampaignInfo::campaignId).toList();
        Set<Long> planned = planRepository.findByBusinessDateAndCampaignIdIn(businessDate, campaignIds).stream()
                .map(BudgetDailyPlan::getCampaignId)
                .collect(Collectors.toSet());
        long remaining = balanceOf(ownerId);
        for (CampaignInfo campaign : campaigns) {
            if (planned.contains(campaign.campaignId())) {
                continue;
            }
            long reserved = Math.min(dailyBudgetOf(campaign), remaining);
            remaining -= reserved;
            if (reserved > 0) {
                ledgerRepository.save(PointLedger.reserve(ownerId, campaign.campaignId(), businessDate, reserved));
            }
            planRepository.save(BudgetDailyPlan.create(campaign.campaignId(), ownerId, businessDate, reserved,
                    unitPrice, campaign.status() != CampaignStatus.ACTIVE, campaign.statusChangedAt()));
        }
    }

    /*
     * 전날 남은 예약의 보정 RELEASE 를 한 트랜잭션에 기록한다. 하나라도 실패하면 아무것도 남지 않고 예약도 진행되지 않는다.
     *
     * 이미 RELEASE 가 있는 캠페인은 그대로 둔다. 부른 쪽이 RELEASE 가 없는 캠페인만 골라 넘기지만, 그 조회와 이 쓰기
     * 사이에 이어받은 다른 실행이 먼저 쓸 수 있다. saveAll 이면 그때 UNIQUE 위반으로 단계가 실패하고, 그 예외는
     * 일시 오류 자동 재실행 대상도 아니라 그날 예약까지 멈춘다.
     */
    @Transactional
    public void release(List<PointLedger> releases) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (PointLedger release : releases) {
            if (release.getEntryType() != EntryType.RELEASE) {
                throw new IllegalArgumentException("보정 해제에는 RELEASE 만 넘긴다: " + release.getEntryType());
            }
            ledgerRepository.insertReleaseIfAbsent(release.getOwnerId(), release.getCampaignId(),
                    release.getBusinessDate(), release.getAmount(), now);
        }
    }

    /*
     * 잔액이 음수면 0 으로 보고 ERROR 로 남긴다. 원장이 어긋났다는 신호지만, 예외로 멈추면 뒤 순서 점주의 예약까지
     * 그날 하루 막힌다.
     */
    private long balanceOf(Long ownerId) {
        long balance = ledgerRepository.sumBalance(ownerId);
        if (balance < 0) {
            log.error("event=RESERVE_NEGATIVE_BALANCE ownerId={} balance={}", ownerId, balance);
            return 0L;
        }
        return balance;
    }

    // 서빙 대상 캠페인은 하루 예산이 정해져 있다. 비어 있으면 캠페인 쪽 데이터가 어긋난 것이라 단계를 멈춘다
    private long dailyBudgetOf(CampaignInfo campaign) {
        if (campaign.dailyBudget() == null) {
            throw new IllegalStateException("하루 예산이 없는 서빙 대상 캠페인입니다. campaignId=" + campaign.campaignId());
        }
        return campaign.dailyBudget();
    }
}
