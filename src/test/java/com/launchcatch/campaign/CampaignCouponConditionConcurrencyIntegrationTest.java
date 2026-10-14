package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * findByStoreIdAndStatus의 PESSIMISTIC_WRITE 잠금이 실제 DB 행 잠금으로 동작하는지 확인한다.
 * 메서드를 직접 부르는 입구라 테스트 메서드에 @Transactional을 붙이지 않는다(붙이면 테스트
 * 트랜잭션에 참여해 커밋 뒤에 도는 것을 못 본다, LLM_구현_플로우.md "테스트 깊이" 절).
 */
@SpringBootTest
class CampaignCouponConditionConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private CampaignCouponConditionService service;
    @MockitoSpyBean
    private CampaignRepository campaignRepository;
    @MockitoBean
    private OwnerStatusQuery ownerStatusQuery;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long storeId;

    @BeforeEach
    void setUp() {
        when(ownerStatusQuery.findCurrentStatus(any())).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        storeId = System.nanoTime();
        Campaign existing = Campaign.create(
                OWNER_ID, storeId,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
        campaignRepository.saveAndFlush(existing);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            campaignRepository.findByStoreIdAndStatus(storeId, CampaignStatus.DRAFT)
                    .ifPresent(campaign -> campaignRepository.deleteById(campaign.getId()));
            return null;
        });
    }

    @Test
    void 같은_가게의_DRAFT를_동시에_덮어쓰면_뒤에_커밋한_값이_그대로_남는다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean(false);
        /*
         * campaignRepository는 Spring Data가 런타임에 만든 프록시라 Mockito의
         * call.callRealMethod()로 위임할 수 없다("Cannot call abstract real method").
         * 그래서 같은 조회를 EntityManager로 직접 재현한다. 잠금 모드는 리포지토리의
         * @Lock(PESSIMISTIC_WRITE)와 같다.
         */
        doAnswer(call -> {
            Optional<Campaign> found = entityManager.createQuery(
                            "select c from Campaign c where c.storeId = :storeId and c.status = :status",
                            Campaign.class)
                    .setParameter("storeId", storeId)
                    .setParameter("status", CampaignStatus.DRAFT)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
            if (paused.compareAndSet(false, true)) {
                locked.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return found;
        }).when(campaignRepository).findByStoreIdAndStatus(storeId, CampaignStatus.DRAFT);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> service.handle(OWNER_ID, request(20)));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> service.handle(OWNER_ID, request(30)));
            /*
             * 첫 트랜잭션이 행 잠금을 쥔 채 아직 커밋 전이라, 둘째 호출의
             * findByStoreIdAndStatus(SELECT ... FOR UPDATE)는 실제 DB에서 블록되어야
             * 한다. 잠금이 없다면(회귀) 이 구간에서 바로 끝나 isDone()이 true가 된다.
             */
            assertThat(isDoneWithin(second, 500)).isFalse();

            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        int discountValue = transactionTemplate.execute(status ->
                campaignRepository.findByStoreIdAndStatus(storeId, CampaignStatus.DRAFT).orElseThrow().getDiscountValue());
        assertThat(discountValue).isEqualTo(30);
    }

    private boolean isDoneWithin(Future<?> future, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (future.isDone()) {
                return true;
            }
            Thread.sleep(20);
        }
        return future.isDone();
    }

    private CampaignCouponConditionRequest request(int discountValue) {
        return new CampaignCouponConditionRequest(
                storeId, DiscountTargetType.ALL, DiscountType.PERCENT, discountValue, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30), null, null);
    }
}
