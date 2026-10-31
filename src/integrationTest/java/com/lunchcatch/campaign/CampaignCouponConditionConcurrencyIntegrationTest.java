package com.lunchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.service.CampaignCouponConditionService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * findByStoreIdAndStatus의 PESSIMISTIC_WRITE 잠금이 실제 DB 행 잠금으로 동작하는지 확인한다.
 * campaignRepository 스파이는 AbstractCampaignIntegrationTest가 도메인 컨텍스트를 하나로
 * 묶으려고 모아 둔 것을 그대로 쓴다(#278).
 *
 * AbstractCampaignIntegrationTest의 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮어쓴다.
 * 테스트 메서드를 그 트랜잭션 안에 두면 setUp에서 만든 점주 행과 DRAFT가 커밋 전이라, 다른
 * 연결을 쓰는 워커 스레드의 서비스 호출이 그 행을 보지 못해 OWNER_NOT_ACTIVE로 바로 끝난다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignCouponConditionConcurrencyIntegrationTest extends AbstractCampaignIntegrationTest {

    @Autowired
    private CampaignCouponConditionService service;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private Long ownerId;
    private Long storeId;

    @BeforeEach
    void setUp() {
        ownerId = createActiveOwner();
        storeId = createFinalizedStore(ownerId);
        Campaign existing = Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
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
        jdbc.update("delete from store where store_id = ?", storeId);
        jdbc.update("delete from owner where owner_id = ?", ownerId);
    }

    /*
     * 점주의 최종 등록된 가게 행을 만든다. 서비스가 canManageStore 로 소유를 확인하므로(#874) 임의의 storeId로는
     * 등록이 거절된다. 최종 등록 제약(chk_store_finalized)이 사업자 검증과 좌표를 요구해 함께 채운다.
     */
    private Long createFinalizedStore(Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, "
                        + "business_verification_status, business_verified_at, latitude, longitude, finalized, "
                        + "finalized_at, created_at, updated_at) "
                        + "values (?, ?, 'KOREAN', ?, 'VERIFIED', ?, 37.5, 127.0, true, ?, ?, ?)",
                ownerId, "campaign-concurrency-test-store-" + System.nanoTime(), "대표자", now, now, now, now);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
    }

    /** owner 도메인 내부를 직접 참조하지 않고, 점주 AT 검증이 보는 행만 그대로 준비한다. */
    private Long createActiveOwner() {
        String email = "campaign-concurrency-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        return jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
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
            Future<?> first = executor.submit(() -> service.handle(ownerId, request(20)));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> service.handle(ownerId, request(30)));
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
                storeId, DiscountTargetType.ALL, DiscountType.AMOUNT, discountValue, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30), null, null);
    }
}
