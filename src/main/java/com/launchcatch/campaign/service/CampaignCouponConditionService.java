package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.exception.ConstraintViolations;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 캠페인 등록 1단계(쿠폰 조건). 가게에 작성 중인 DRAFT가 있으면 그 1단계 필드만 덮어쓰고,
 * 없으면 노출 대상 기본값과 예산·기간 null로 새로 만든다(#244, campaign-api-design.md).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignCouponConditionService {

    private static final String DRAFT_STORE_CONSTRAINT = "uk_campaign_draft_store";

    private static final LocalTime WINDOW_START = LocalTime.of(11, 30);
    private static final LocalTime WINDOW_END = LocalTime.of(15, 0);
    private static final int STEP_MINUTES = 30;
    private static final int MIN_DURATION_MINUTES = 60;

    private static final int DEFAULT_TARGET_RADIUS = 1000;
    private static final List<AgeGroup> DEFAULT_TARGET_AGE_GROUPS =
            List.of(AgeGroup.AGE_20S, AgeGroup.AGE_30S, AgeGroup.AGE_40S, AgeGroup.AGE_50_PLUS);

    private final CampaignRepository campaignRepository;
    private final OwnerStatusQuery ownerStatusQuery;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CampaignCouponConditionResult handle(Long ownerId, CampaignCouponConditionRequest request) {
        validateActiveOwner(ownerId);
        validateDiscountValue(request.discountType(), request.discountValue());
        validateIssueQuantity(request.issueQuantity());
        validateUsableTime(request.usableStartTime(), request.usableEndTime());

        try {
            return transactionTemplate.execute(status -> save(ownerId, request));
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isConstraintViolation(e, DRAFT_STORE_CONSTRAINT)) {
                throw e;
            }
            /*
             * 장애_처리_설계_가이드.md의 DB 기본 처리("제약 위반, 데이터 불일치: 재시도하지
             * 않고 그대로 올린다")에서 벗어난다. 이 제약은 데이터가 어긋나서 나는 게 아니라
             * "가게당 DRAFT 1개"를 두 요청이 동시에 쫓다가 늦게 온 쪽이 걸리는 경쟁의
             * 흔적이다. 늦은 쪽이 받아야 할 결과(그 가게의 DRAFT에 자기 쿠폰 조건을
             * 덮어쓰는 것)는 먼저 조회했을 때 DRAFT가 있었다면 탔을 경로와 같다. 그래서
             * 재조회 후 덮어쓰기는 재시도가 아니라 원래 의도한 "이어 쓰기" 경로로 들어가는
             * 것일 뿐이다(#244, 2026-10-14 확정. campaign_장애_처리_설계.md에도 기록한다).
             */
            return transactionTemplate.execute(status -> overwriteAfterConflict(request));
        }
    }

    private CampaignCouponConditionResult save(Long ownerId, CampaignCouponConditionRequest request) {
        // TODO: store 공개 contract가 생기면 request.storeId()가 ownerId의 가게인지 검증을 추가한다.
        return campaignRepository.findByStoreIdAndStatus(request.storeId(), CampaignStatus.DRAFT)
                .map(existing -> overwrite(existing, request))
                .orElseGet(() -> create(ownerId, request));
    }

    private CampaignCouponConditionResult create(Long ownerId, CampaignCouponConditionRequest request) {
        Campaign campaign = Campaign.create(
                ownerId, request.storeId(),
                request.discountTargetType(), request.discountType(), request.discountValue(), request.targetMenuId(),
                request.issueQuantity(), request.usableStartTime(), request.usableEndTime(),
                request.minOrderAmount(), request.notice(),
                DEFAULT_TARGET_RADIUS, TargetGender.ALL, DEFAULT_TARGET_AGE_GROUPS,
                null, null, null,
                LocalDateTime.now(clock));
        // saveAndFlush로 이 트랜잭션 안에서 바로 INSERT해 uk_campaign_draft_store 위반을 여기서 드러낸다.
        campaignRepository.saveAndFlush(campaign);
        return new CampaignCouponConditionResult(toResponse(campaign), true);
    }

    private CampaignCouponConditionResult overwriteAfterConflict(CampaignCouponConditionRequest request) {
        Campaign campaign = campaignRepository.findByStoreIdAndStatus(request.storeId(), CampaignStatus.DRAFT)
                .orElseThrow(() -> new IllegalStateException(
                        "draft not found after unique constraint violation: storeId=" + request.storeId()));
        return overwrite(campaign, request);
    }

    private CampaignCouponConditionResult overwrite(Campaign campaign, CampaignCouponConditionRequest request) {
        campaign.updateCouponCondition(
                request.discountTargetType(), request.discountType(), request.discountValue(), request.targetMenuId(),
                request.issueQuantity(), request.usableStartTime(), request.usableEndTime(),
                request.minOrderAmount(), request.notice());
        return new CampaignCouponConditionResult(toResponse(campaign), false);
    }

    private CampaignCouponConditionResponse toResponse(Campaign campaign) {
        return new CampaignCouponConditionResponse(campaign.getId(), campaign.getStatus());
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.warn("event=CAMPAIGN_COUPON_CONDITION_FAILED reason=OWNER_NOT_ACTIVE code={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private void validateDiscountValue(DiscountType discountType, int discountValue) {
        boolean positive = discountValue > 0;
        boolean withinPercent = discountType != DiscountType.PERCENT || discountValue <= 100;
        if (!positive || !withinPercent) {
            throw new CampaignException(CampaignErrorCode.DISCOUNT_VALUE_INVALID);
        }
    }

    private void validateIssueQuantity(int issueQuantity) {
        if (issueQuantity <= 0) {
            throw new CampaignException(CampaignErrorCode.ISSUE_QUANTITY_INVALID);
        }
    }

    /*
     * 엔티티의 requireUsableTime은 end > start만 본다. 11:30~15:00 범위, 30분 단위,
     * 최소 1시간 간격은 플랫폼이 정한 값(V51 43행)이라 서비스가 직접 검사한다
     * (campaign-api-design.md "usableTime 검증은 서비스 레이어에 둔다").
     */
    private void validateUsableTime(LocalTime usableStartTime, LocalTime usableEndTime) {
        boolean withinWindow = !usableStartTime.isBefore(WINDOW_START) && !usableEndTime.isAfter(WINDOW_END);
        boolean aligned = isAlignedToStep(usableStartTime) && isAlignedToStep(usableEndTime);
        boolean minDuration = !usableEndTime.isBefore(usableStartTime.plusMinutes(MIN_DURATION_MINUTES));
        if (!withinWindow || !aligned || !minDuration) {
            throw new CampaignException(CampaignErrorCode.USABLE_TIME_INVALID);
        }
    }

    private static boolean isAlignedToStep(LocalTime time) {
        return time.getSecond() == 0 && time.getNano() == 0 && time.getMinute() % STEP_MINUTES == 0;
    }
}
