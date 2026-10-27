package com.lunchcatch.campaign.repository;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.entity.Campaign;
import java.time.LocalTime;

/*
 * 가게 요약을 만드는 데 필요한 캠페인 값만 담은 읽기용 행이다(#838). 엔티티로 읽으면 영속성 컨텍스트가 전부 붙들고
 * 있어 청크로 나눠도 메모리가 줄지 않으므로, 필요한 컬럼만 바로 읽는다.
 */
public record CampaignSummaryRow(
        Long storeId,
        Long campaignId,
        CampaignStatus status,
        DiscountTargetType discountTargetType,
        DiscountType discountType,
        int discountValue,
        Long targetMenuId,
        LocalTime usableStartTime,
        LocalTime usableEndTime
) {

    public static CampaignSummaryRow from(Campaign campaign) {
        return new CampaignSummaryRow(
                campaign.getStoreId(),
                campaign.getId(),
                campaign.getStatus(),
                campaign.getDiscountTargetType(),
                campaign.getDiscountType(),
                campaign.getDiscountValue(),
                campaign.getTargetMenuId(),
                campaign.getUsableStartTime(),
                campaign.getUsableEndTime());
    }
}
