package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignDetailResponse;
import com.launchcatch.campaign.dto.CampaignListItemResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignOwnerQueryServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_OWNER_ID = 8L;
    private static final Long CAMPAIGN_ID = 501L;

    @Mock
    private CampaignRepository campaignRepository;

    private CampaignOwnerQueryService service;

    @BeforeEach
    void setUp() {
        service = new CampaignOwnerQueryService(campaignRepository);
    }

    @Test
    @DisplayName("상세 조회는 본인 소유 캠페인을 응답으로 바꾼다")
    void 상세를_조회한다() throws Exception {
        Campaign campaign = campaign(OWNER_ID, CAMPAIGN_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignDetailResponse response = service.detail(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.targetRadius()).isEqualTo(1000);
    }

    @Test
    @DisplayName("본인 소유가 아니면 CAMPAIGN-005로 거부한다")
    void 본인_소유가_아니면_거부한다() throws Exception {
        Campaign campaign = campaign(OTHER_OWNER_ID, CAMPAIGN_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.detail(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("다음 페이지가 없으면 요청한 개수 이하로 돌려주고 nextPageToken은 null이다")
    void 마지막_페이지는_다음_토큰이_없다() throws Exception {
        Campaign campaign = campaign(OWNER_ID, CAMPAIGN_ID);
        when(campaignRepository.findPageByOwnerId(eq(OWNER_ID), isNull(), eq(21)))
                .thenReturn(List.of(campaign));

        CursorPageResponse<CampaignListItemResponse> response = service.list(OWNER_ID, null, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.nextPageToken()).isNull();
    }

    @Test
    @DisplayName("다음 페이지가 더 있으면 요청한 개수만 자르고 nextPageToken을 채운다")
    void 다음_페이지가_있으면_잘라서_토큰을_채운다() throws Exception {
        Campaign first = campaign(OWNER_ID, 502L);
        Campaign second = campaign(OWNER_ID, 501L);
        when(campaignRepository.findPageByOwnerId(eq(OWNER_ID), isNull(), eq(2)))
                .thenReturn(List.of(first, second));

        CursorPageResponse<CampaignListItemResponse> response = service.list(OWNER_ID, null, 1);

        assertThat(response.items()).extracting(CampaignListItemResponse::campaignId)
                .containsExactly(502L);
        assertThat(response.nextPageToken()).isNotNull();
        PageCursor decoded = PageTokens.decode(response.nextPageToken());
        assertThat(decoded.id()).isEqualTo(502L);
    }

    @Test
    @DisplayName("pageToken이 있으면 디코딩한 캠페인 ID를 커서로 넘긴다")
    void pageToken_을_디코딩해서_커서로_넘긴다() {
        String token = PageTokens.encode(new PageCursor(600L, null));
        when(campaignRepository.findPageByOwnerId(eq(OWNER_ID), eq(600L), eq(21)))
                .thenReturn(List.of());

        service.list(OWNER_ID, token, 20);

        verify(campaignRepository).findPageByOwnerId(eq(OWNER_ID), eq(600L), eq(21));
    }

    @Test
    @DisplayName("깨진 pageToken은 예외 없이 첫 페이지로 본다")
    void 깨진_pageToken_은_첫_페이지로_본다() {
        when(campaignRepository.findPageByOwnerId(eq(OWNER_ID), isNull(), eq(21)))
                .thenReturn(List.of());

        service.list(OWNER_ID, "not-a-valid-token", 20);

        verify(campaignRepository).findPageByOwnerId(eq(OWNER_ID), isNull(), eq(21));
    }

    @Test
    @DisplayName("pageSize가 0 이하면 기본값을 쓰고, 최대치를 넘으면 최대치로 자른다")
    void pageSize_를_보정한다() {
        when(campaignRepository.findPageByOwnerId(eq(OWNER_ID), isNull(), anyInt()))
                .thenReturn(List.of());
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);

        service.list(OWNER_ID, null, 0);
        service.list(OWNER_ID, null, 1000);

        verify(campaignRepository, times(2))
                .findPageByOwnerId(eq(OWNER_ID), isNull(), limitCaptor.capture());
        List<Integer> limits = limitCaptor.getAllValues();
        assertThat(limits.get(0)).isEqualTo(21);
        assertThat(limits.get(1)).isEqualTo(101);
    }

    private Campaign campaign(Long ownerId, Long campaignId) throws Exception {
        Campaign campaign = Campaign.create(
                ownerId, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
        setId(campaign, campaignId);
        return campaign;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
