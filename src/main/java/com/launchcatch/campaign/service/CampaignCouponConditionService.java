package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
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
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 캠페인 등록 1단계(쿠폰 조건). 가게에 작성 중인 DRAFT가 있으면 그 1단계 필드만 덮어쓰고,
 * 없으면 노출 대상 기본값과 예산·기간 null로 새로 만든다(#244, campaign-api-design.md).
 *
 * 요청의 storeId와 targetMenuId는 클라이언트가 보낸 값이라 로그인한 점주의 것인지 저장 전에 확인한다(#874).
 * store 조회는 다른 도메인의 조회이므로 트랜잭션을 열기 전에 끝낸다.
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
    private final StoreQueryService storeQueryService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CampaignCouponConditionResult handle(Long ownerId, CampaignCouponConditionRequest request) {
        validateActiveOwner(ownerId);
        validateStoreOwnership(ownerId, request.storeId());
        validateDiscountValue(request.discountTargetType(), request.discountType(), request.discountValue());
        validateIssueQuantity(request.issueQuantity());
        validateUsableTime(request.usableStartTime(), request.usableEndTime());
        validateTargetMenu(request);

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
            return transactionTemplate.execute(status -> overwriteAfterConflict(ownerId, request));
        } catch (ConcurrencyFailureException e) {
            /*
             * findByStoreIdAndStatus의 PESSIMISTIC_WRITE는 그 가게에 DRAFT가 하나도 없으면
             * 갭 락이 된다. 같은 가게로 동시에 들어온 두 생성 요청이 이 갭을 같이 잠근 뒤
             * 각자 INSERT하면 상대의 갭 락에 막혀 MySQL이 교착 상태로 판정해 한쪽을
             * 롤백시킨다(DeadlockLoserDataAccessException, 에러 1213). 잠금 대기가 길어져
             * 포기하는 경우(CannotAcquireLockException, 에러 1205)도 같은 자리에서 난다. 위
             * DataIntegrityViolationException 분기와 이유가 같다. 이 트랜잭션이 건드리는 게
             * 이 가게의 DRAFT 한 행뿐이라 조건 확인 없이 바로 재조회해 덮어쓰는 경로로
             * 들어간다(코드래빗 리뷰로 찾음, #244).
             */
            return transactionTemplate.execute(status -> overwriteAfterConflict(ownerId, request));
        }
    }

    private CampaignCouponConditionResult save(Long ownerId, CampaignCouponConditionRequest request) {
        return campaignRepository.findByStoreIdAndStatus(request.storeId(), CampaignStatus.DRAFT)
                .map(existing -> overwrite(ownerId, existing, request))
                .orElseGet(() -> create(ownerId, request));
    }

    /*
     * 점주당 ENDED가 아닌 캠페인은 하나만 허용한다(#525). 같은 가게의 DRAFT를 다시 저장하는
     * 경로(overwrite)는 이 메서드를 타지 않아서, 여기서는 "새 캠페인을 만드는 것"만 막는다.
     */
    private CampaignCouponConditionResult create(Long ownerId, CampaignCouponConditionRequest request) {
        if (campaignRepository.existsByOwnerIdAndStatusNotIn(ownerId, List.of(CampaignStatus.ENDED))) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_ALREADY_IN_PROGRESS);
        }
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

    private CampaignCouponConditionResult overwriteAfterConflict(Long ownerId, CampaignCouponConditionRequest request) {
        Campaign campaign = campaignRepository.findByStoreIdAndStatus(request.storeId(), CampaignStatus.DRAFT)
                .orElseThrow(() -> new IllegalStateException(
                        "draft not found after unique constraint violation: storeId=" + request.storeId()));
        return overwrite(ownerId, campaign, request);
    }

    /*
     * 가게 소유를 이미 확인했으므로 그 가게의 DRAFT는 로그인한 점주의 것이어야 한다. 아니면 소유 확인이 생기기 전에
     * 만들어진 데이터이므로 덮어쓰지 않고 거절한다. 같은 가게를 가리키는 서로 다른 점주의 DRAFT가 있다는 뜻이라 운영이
     * 볼 수 있게 WARN 으로 남긴다.
     */
    private CampaignCouponConditionResult overwrite(
            Long ownerId, Campaign campaign, CampaignCouponConditionRequest request) {
        if (!campaign.getOwnerId().equals(ownerId)) {
            log.warn("event=CAMPAIGN_COUPON_CONDITION_FAILED reason=DRAFT_OWNER_MISMATCH errorCode={} ownerId={} "
                            + "campaignId={} draftOwnerId={}",
                    CampaignErrorCode.CAMPAIGN_NOT_OWNED.getCode(), ownerId, campaign.getId(), campaign.getOwnerId());
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED);
        }
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
            log.info("event=CAMPAIGN_COUPON_CONDITION_FAILED reason=OWNER_NOT_ACTIVE errorCode={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    /*
     * discountTargetType이 ALL(전체 메뉴)이면 discountType은 AMOUNT(정액)만 허용한다(#431,
     * 팀 논의로 바뀐 사양). MENU(특정 메뉴)는 PERCENT/AMOUNT 둘 다 그대로 허용한다. 엔티티의
     * requireDiscountValue(Campaign.create/updateCouponCondition)에도 같은 제약이 있다.
     */
    /*
     * 최종 등록된 본인 가게만 통과한다. 가게가 없거나 다른 점주의 것이거나 등록 중이어도 같은 응답이라 존재 여부를
     * 알려 주지 않는다. 조회 장애는 권한 없음으로 바꾸지 않고 그대로 올라간다.
     */
    private void validateStoreOwnership(Long ownerId, Long storeId) {
        if (!storeQueryService.canManageStore(ownerId, storeId)) {
            log.info("event=CAMPAIGN_COUPON_CONDITION_FAILED reason=STORE_NOT_OWNED errorCode={} ownerId={} storeId={}",
                    CampaignErrorCode.STORE_NOT_OWNED.getCode(), ownerId, storeId);
            throw new CampaignException(CampaignErrorCode.STORE_NOT_OWNED);
        }
    }

    /*
     * MENU 할인이면 targetMenuId 가 그 가게의 메뉴이고 검증된 이미지가 있어야 한다. 없는 메뉴, 다른 가게의 메뉴, 이미지가
     * 없거나 검증되지 않은 메뉴는 findMenusByIdIn 의 정상 목록에 들어오지 않으므로 한 번에 걸러진다. 이 메뉴는 포스터와
     * 쿠폰 준비에서도 그 이미지가 필요해 어차피 뒤에서 실패한다. 조합이 틀린 요청(MENU 인데 메뉴 ID 가 없음 등)은 여기서
     * 판단하지 않고 엔티티 검증에 맡긴다.
     */
    private void validateTargetMenu(CampaignCouponConditionRequest request) {
        if (request.discountTargetType() != DiscountTargetType.MENU || request.targetMenuId() == null) {
            return;
        }
        boolean usable = storeQueryService.findMenusByIdIn(List.of(request.targetMenuId())).menus().stream()
                .anyMatch(menu -> request.storeId().equals(menu.storeId()));
        if (!usable) {
            log.info("event=CAMPAIGN_COUPON_CONDITION_FAILED reason=TARGET_MENU_INVALID errorCode={} storeId={} menuId={}",
                    CampaignErrorCode.TARGET_MENU_INVALID.getCode(), request.storeId(), request.targetMenuId());
            throw new CampaignException(CampaignErrorCode.TARGET_MENU_INVALID);
        }
    }

    private void validateDiscountValue(DiscountTargetType discountTargetType, DiscountType discountType, int discountValue) {
        boolean positive = discountValue > 0;
        boolean withinPercent = discountType != DiscountType.PERCENT || discountValue <= 100;
        boolean allowedForAll = discountTargetType != DiscountTargetType.ALL || discountType == DiscountType.AMOUNT;
        if (!positive || !withinPercent || !allowedForAll) {
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
