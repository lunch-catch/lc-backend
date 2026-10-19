package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class CampaignCouponConditionServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long STORE_ID = 17L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private OwnerStatusQuery ownerStatusQuery;
    @Mock
    private TransactionTemplate transactionTemplate;

    private CampaignCouponConditionService service;

    @BeforeEach
    void setUp() {
        service = new CampaignCouponConditionService(campaignRepository, ownerStatusQuery, transactionTemplate, FIXED_CLOCK);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 저장소를 건드리지 않는다")
    void ACTIVE가_아니면_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.handle(OWNER_ID, request()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
        verifyNoInteractions(campaignRepository, transactionTemplate);
    }

    @Test
    @DisplayName("PERCENT 할인율이 100을 넘으면 CAMPAIGN-002로 거부한다")
    void 할인율이_100_초과면_거부한다() {
        CampaignCouponConditionRequest request = request(DiscountTargetType.MENU, DiscountType.PERCENT, 101, 42L);

        assertThatThrownBy(() -> service.handle(OWNER_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.DISCOUNT_VALUE_INVALID));
        verifyNoInteractions(campaignRepository, transactionTemplate);
    }

    /*
     * 팀 논의로 바뀐 사양이다(#431). discountTargetType이 ALL이면 discountType은 AMOUNT만
     * 허용한다. MENU는 변동 없이 PERCENT/AMOUNT 둘 다 허용한다.
     */
    @Test
    @DisplayName("전체 메뉴(ALL)에 PERCENT를 쓰면 CAMPAIGN-002로 거부한다")
    void 전체_메뉴에_PERCENT를_쓰면_거부한다() {
        CampaignCouponConditionRequest request = request(DiscountTargetType.ALL, DiscountType.PERCENT, 10, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.DISCOUNT_VALUE_INVALID));
        verifyNoInteractions(campaignRepository, transactionTemplate);
    }

    @Test
    @DisplayName("전체 메뉴(ALL)에 AMOUNT는 허용한다")
    void 전체_메뉴에_AMOUNT는_허용한다() throws Exception {
        CampaignCouponConditionRequest request = request(DiscountTargetType.ALL, DiscountType.AMOUNT, 3000, null);
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT)).thenReturn(Optional.empty());
        when(campaignRepository.saveAndFlush(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign saved = invocation.getArgument(0);
            setId(saved, 501L);
            return saved;
        });

        CampaignCouponConditionResult result = service.handle(OWNER_ID, request);

        assertThat(result.response().campaignId()).isEqualTo(501L);
    }

    @Test
    @DisplayName("특정 메뉴(MENU)는 PERCENT, AMOUNT 둘 다 허용한다")
    void 특정_메뉴는_PERCENT_AMOUNT_둘다_허용한다() throws Exception {
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT)).thenReturn(Optional.empty());
        when(campaignRepository.saveAndFlush(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign saved = invocation.getArgument(0);
            setId(saved, 501L);
            return saved;
        });

        CampaignCouponConditionResult percentResult = service.handle(
                OWNER_ID, request(DiscountTargetType.MENU, DiscountType.PERCENT, 15, 42L));
        CampaignCouponConditionResult amountResult = service.handle(
                OWNER_ID, request(DiscountTargetType.MENU, DiscountType.AMOUNT, 3000, 42L));

        assertThat(percentResult.response().campaignId()).isEqualTo(501L);
        assertThat(amountResult.response().campaignId()).isEqualTo(501L);
    }

    @Test
    @DisplayName("발급 수량이 0 이하면 CAMPAIGN-003으로 거부한다")
    void 수량이_0_이하면_거부한다() {
        CampaignCouponConditionRequest request = requestWithQuantity(0);

        assertThatThrownBy(() -> service.handle(OWNER_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.ISSUE_QUANTITY_INVALID));
        verifyNoInteractions(campaignRepository, transactionTemplate);
    }

    @Test
    @DisplayName("이용 시간이 11:30~15:00 범위 밖이면 CAMPAIGN-004로 거부한다")
    void 이용시간이_범위_밖이면_거부한다() {
        CampaignCouponConditionRequest request = requestWithUsableTime(LocalTime.of(11, 0), LocalTime.of(13, 0));

        assertThatThrownBy(() -> service.handle(OWNER_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.USABLE_TIME_INVALID));
    }

    @Test
    @DisplayName("이용 시간이 30분 단위가 아니면 CAMPAIGN-004로 거부한다")
    void 이용시간이_30분_단위가_아니면_거부한다() {
        CampaignCouponConditionRequest request = requestWithUsableTime(LocalTime.of(11, 45), LocalTime.of(13, 0));

        assertThatThrownBy(() -> service.handle(OWNER_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.USABLE_TIME_INVALID));
    }

    @Test
    @DisplayName("이용 시간 간격이 1시간 미만이면 CAMPAIGN-004로 거부한다")
    void 이용시간_간격이_1시간_미만이면_거부한다() {
        CampaignCouponConditionRequest request = requestWithUsableTime(LocalTime.of(11, 30), LocalTime.of(12, 0));

        assertThatThrownBy(() -> service.handle(OWNER_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.USABLE_TIME_INVALID));
    }

    @Test
    @DisplayName("가게에 DRAFT가 없으면 기본값으로 새로 만들고 201에 해당하는 결과를 돌려준다")
    void DRAFT가_없으면_새로_만든다() throws Exception {
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT)).thenReturn(Optional.empty());
        when(campaignRepository.saveAndFlush(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign saved = invocation.getArgument(0);
            setId(saved, 501L);
            return saved;
        });

        CampaignCouponConditionResult result = service.handle(OWNER_ID, request());

        assertThat(result.created()).isTrue();
        assertThat(result.response().campaignId()).isEqualTo(501L);
        assertThat(result.response().status()).isEqualTo(CampaignStatus.DRAFT);
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTargetRadius()).isEqualTo(1000);
        assertThat(captor.getValue().getTargetGender()).isEqualTo(TargetGender.ALL);
        assertThat(captor.getValue().getTargetAgeGroups())
                .containsExactly(AgeGroup.AGE_20S, AgeGroup.AGE_30S, AgeGroup.AGE_40S, AgeGroup.AGE_50_PLUS);
        assertThat(captor.getValue().getDailyBudget()).isNull();
        assertThat(captor.getValue().getStartDate()).isNull();
        assertThat(captor.getValue().getEndDate()).isNull();
    }

    @Test
    @DisplayName("가게에 DRAFT가 있으면 그 1단계 필드만 덮어쓰고 200에 해당하는 결과를 돌려준다")
    void DRAFT가_있으면_덮어쓴다() throws Exception {
        Campaign existing = existingDraft();
        setId(existing, 501L);
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT))
                .thenReturn(Optional.of(existing));

        CampaignCouponConditionResult result = service.handle(OWNER_ID, request());

        assertThat(result.created()).isFalse();
        assertThat(result.response().campaignId()).isEqualTo(501L);
        assertThat(existing.getDiscountValue()).isEqualTo(15);
        verify(campaignRepository, never()).saveAndFlush(any());
        // 이어 쓰기는 새 캠페인을 만드는 게 아니라 "진행 중 1개" 제한을 볼 이유가 없다(#525).
        verify(campaignRepository, never()).existsByOwnerIdAndStatusNotIn(any(), any());
    }

    /*
     * #525. ENDED가 아닌 캠페인이 이미 있으면(DRAFT가 아닌 다른 캠페인, 즉 이 가게가 아닌
     * 다른 가게를 가진 경우는 owner:store가 1:1이라 있을 수 없고, 같은 가게가 SCHEDULED
     * 이상으로 넘어간 뒤 또 새로 만들려는 경우다) 새 등록을 막는다.
     */
    @Test
    @DisplayName("이미 ENDED가 아닌 캠페인이 있으면 새 등록을 CAMPAIGN-010으로 거부한다")
    void 진행중인_캠페인이_있으면_새_등록을_거부한다() {
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT)).thenReturn(Optional.empty());
        when(campaignRepository.existsByOwnerIdAndStatusNotIn(OWNER_ID, List.of(CampaignStatus.ENDED)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.handle(OWNER_ID, request()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_ALREADY_IN_PROGRESS));
        verify(campaignRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("기존 캠페인이 ENDED뿐이면 새로 등록할 수 있다")
    void ENDED뿐이면_새로_등록할_수_있다() throws Exception {
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT)).thenReturn(Optional.empty());
        when(campaignRepository.existsByOwnerIdAndStatusNotIn(OWNER_ID, List.of(CampaignStatus.ENDED)))
                .thenReturn(false);
        when(campaignRepository.saveAndFlush(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign saved = invocation.getArgument(0);
            setId(saved, 501L);
            return saved;
        });

        CampaignCouponConditionResult result = service.handle(OWNER_ID, request());

        assertThat(result.created()).isTrue();
        assertThat(result.response().campaignId()).isEqualTo(501L);
    }

    @Test
    @DisplayName("동시에 들어온 생성 요청이 DRAFT UNIQUE 제약에 걸리면 재조회해 덮어쓰기로 전환한다")
    void 제약_위반이면_재조회해_덮어쓴다() throws Exception {
        Campaign existing = existingDraft();
        setId(existing, 501L);
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(campaignRepository.saveAndFlush(any(Campaign.class)))
                .thenThrow(new DataIntegrityViolationException("uk_campaign_draft_store"));

        CampaignCouponConditionResult result = service.handle(OWNER_ID, request());

        assertThat(result.created()).isFalse();
        assertThat(result.response().campaignId()).isEqualTo(501L);
        assertThat(existing.getDiscountValue()).isEqualTo(15);
    }

    @Test
    @DisplayName("제약 위반이 DRAFT UNIQUE가 아니면 그대로 전파한다")
    void 다른_제약_위반이면_그대로_전파한다() {
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT)).thenReturn(Optional.empty());
        DataIntegrityViolationException violation = new DataIntegrityViolationException("다른 제약 위반");
        when(campaignRepository.saveAndFlush(any(Campaign.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.handle(OWNER_ID, request())).isSameAs(violation);
    }

    @Test
    @DisplayName("동시에 들어온 생성 요청이 갭 락 교착 상태로 롤백되면 재조회해 덮어쓰기로 전환한다")
    void 교착_상태면_재조회해_덮어쓴다() throws Exception {
        Campaign existing = existingDraft();
        setId(existing, 501L);
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(campaignRepository.saveAndFlush(any(Campaign.class)))
                .thenThrow(new DeadlockLoserDataAccessException("1213", null));

        CampaignCouponConditionResult result = service.handle(OWNER_ID, request());

        assertThat(result.created()).isFalse();
        assertThat(result.response().campaignId()).isEqualTo(501L);
        assertThat(existing.getDiscountValue()).isEqualTo(15);
    }

    @Test
    @DisplayName("제약 위반 뒤 재조회해도 DRAFT가 없으면 상태 불일치로 끝낸다")
    void 제약_위반_뒤_재조회해도_없으면_예외를_던진다() {
        when(campaignRepository.findByStoreIdAndStatus(STORE_ID, CampaignStatus.DRAFT))
                .thenReturn(Optional.empty(), Optional.empty());
        when(campaignRepository.saveAndFlush(any(Campaign.class)))
                .thenThrow(new DataIntegrityViolationException("uk_campaign_draft_store"));

        assertThatThrownBy(() -> service.handle(OWNER_ID, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storeId=" + STORE_ID);
    }

    private Campaign existingDraft() {
        return Campaign.create(
                OWNER_ID, STORE_ID,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                2000, TargetGender.MALE, List.of(AgeGroup.AGE_30S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now(FIXED_CLOCK));
    }

    private CampaignCouponConditionRequest request() {
        return request(DiscountTargetType.ALL, DiscountType.AMOUNT, 15, null);
    }

    private CampaignCouponConditionRequest request(
            DiscountTargetType targetType, DiscountType discountType, int discountValue, Long targetMenuId) {
        return new CampaignCouponConditionRequest(
                STORE_ID, targetType, discountType, discountValue, targetMenuId,
                100, LocalTime.of(11, 30), LocalTime.of(13, 0), null, null);
    }

    private CampaignCouponConditionRequest requestWithQuantity(int issueQuantity) {
        return new CampaignCouponConditionRequest(
                STORE_ID, DiscountTargetType.ALL, DiscountType.AMOUNT, 15, null,
                issueQuantity, LocalTime.of(11, 30), LocalTime.of(13, 0), null, null);
    }

    private CampaignCouponConditionRequest requestWithUsableTime(LocalTime start, LocalTime end) {
        return new CampaignCouponConditionRequest(
                STORE_ID, DiscountTargetType.ALL, DiscountType.AMOUNT, 15, null,
                100, start, end, null, null);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
