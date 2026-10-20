package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.dto.AdminCampaignDetailResponse;
import com.launchcatch.campaign.dto.AdminCampaignListItemResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminCampaignQueryServiceTest {

    private static final Long STORE_ID = 20L;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignStatusLogRepository campaignStatusLogRepository;

    @Mock
    private PosterRepository posterRepository;

    @Mock
    private StoreQueryService storeQueryService;

    private AdminCampaignQueryService service() {
        return new AdminCampaignQueryService(
                campaignRepository, campaignStatusLogRepository, posterRepository, storeQueryService);
    }

    private Campaign campaign(Long id) {
        Campaign campaign = org.mockito.Mockito.mock(Campaign.class);
        lenient().when(campaign.getId()).thenReturn(id);
        lenient().when(campaign.getStoreId()).thenReturn(STORE_ID);
        return campaign;
    }

    private StoreInfo store(String name) {
        StoreInfo store = org.mockito.Mockito.mock(StoreInfo.class);
        when(store.storeId()).thenReturn(STORE_ID);
        when(store.storeName()).thenReturn(name);
        return store;
    }

    @Test
    @DisplayName("목록은 가게 상호를 한 번에 조회해 항목에 채운다")
    void 가게_상호를_한번에_조회한다() {
        Campaign first = campaign(502L);
        Campaign second = campaign(501L);
        when(campaignRepository.findAdminPage(isNull(), isNull(), isNull(), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of(first, second));
        StoreInfo storeInfo = store("런치캐치 식당");
        when(storeQueryService.findAllById(List.of(STORE_ID))).thenReturn(List.of(storeInfo));

        CursorPageResponse<AdminCampaignListItemResponse> response = service().list(null, null, null, 20);

        assertThat(response.items()).extracting(AdminCampaignListItemResponse::storeName)
                .containsExactly("런치캐치 식당", "런치캐치 식당");
        assertThat(response.nextPageToken()).isNull();
        verify(storeQueryService).findAllById(List.of(STORE_ID));
    }

    @Test
    @DisplayName("다음 페이지가 더 있으면 요청한 개수만 자르고 nextPageToken을 채운다")
    void 다음_페이지가_있으면_토큰을_채운다() {
        Campaign first = campaign(502L);
        Campaign second = campaign(501L);
        when(campaignRepository.findAdminPage(isNull(), isNull(), isNull(), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(first, second));
        when(storeQueryService.findAllById(any())).thenReturn(List.of());

        CursorPageResponse<AdminCampaignListItemResponse> response = service().list(null, null, null, 1);

        assertThat(response.items()).extracting(AdminCampaignListItemResponse::campaignId).containsExactly(502L);
        PageCursor decoded = PageTokens.decode(response.nextPageToken());
        assertThat(decoded.id()).isEqualTo(502L);
        assertThat(response.items().get(0).storeName()).isNull();
    }

    @Test
    @DisplayName("필터와 커서에 맞는 캠페인이 없으면 빈 페이지를 돌려주고 가게를 조회하지 않는다")
    void 결과가_없으면_빈_페이지를_돌려준다() {
        String token = PageTokens.encode(new PageCursor(600L, null));
        when(campaignRepository.findAdminPage(
                eq(STORE_ID), eq(CampaignStatus.ACTIVE), eq(600L), eq(PageRequest.of(0, 21))))
                .thenReturn(List.of());

        CursorPageResponse<AdminCampaignListItemResponse> response =
                service().list(STORE_ID, CampaignStatus.ACTIVE, token, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextPageToken()).isNull();
        verify(storeQueryService, never()).findAllById(any());
    }

    @Test
    @DisplayName("상세는 캠페인이 없으면 CAMPAIGN-012로 거부한다")
    void 상세는_없으면_거부한다() {
        when(campaignRepository.findById(501L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().detail(501L))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_FOUND));
    }

    @Test
    @DisplayName("상세는 상태 이력이 없고 포스터도 없으면 빈 배열과 null을 담는다")
    void 상세는_이력과_포스터가_없어도_응답한다() {
        Campaign campaign = campaign(501L);
        when(campaignRepository.findById(501L)).thenReturn(Optional.of(campaign));
        StoreInfo storeInfo = store("런치캐치 식당");
        when(storeQueryService.findAllById(List.of(STORE_ID))).thenReturn(List.of(storeInfo));
        when(campaignStatusLogRepository.findByCampaignIdOrderByIdAsc(501L)).thenReturn(List.of());
        when(posterRepository.findByCampaignId(501L)).thenReturn(Optional.empty());

        AdminCampaignDetailResponse response = service().detail(501L);

        assertThat(response.campaignId()).isEqualTo(501L);
        assertThat(response.storeName()).isEqualTo("런치캐치 식당");
        assertThat(response.statusLogs()).isEmpty();
        assertThat(response.poster()).isNull();
    }
}
