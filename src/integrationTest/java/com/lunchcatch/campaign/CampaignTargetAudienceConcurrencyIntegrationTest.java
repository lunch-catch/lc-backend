package com.lunchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.campaign.dto.CampaignUpdateRequest;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.service.CampaignTargetAudienceService;
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
 * findByIdForUpdate의 PESSIMISTIC_WRITE 잠금이 실제 DB 행 잠금으로 동작하는지 확인한다.
 * 잠금 없이 findById를 쓰면 같은 캠페인에 거의 동시에 들어온 두 PATCH가 서로의 변경을
 * 조용히 지우는 lost update가 난다(#253 자체 리뷰로 찾음).
 *
 * CampaignCouponConditionConcurrencyIntegrationTest와 같은 이유로 클래스 단위
 * @Transactional을 NOT_SUPPORTED로 덮어쓴다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignTargetAudienceConcurrencyIntegrationTest extends AbstractCampaignIntegrationTest {

    @Autowired
    private CampaignTargetAudienceService service;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private Long ownerId;
    private Long campaignId;

    @BeforeEach
    void setUp() {
        ownerId = createActiveOwner();
        Campaign existing = Campaign.create(
                ownerId, System.nanoTime(),
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
        campaignId = campaignRepository.saveAndFlush(existing).getId();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            campaignRepository.deleteById(campaignId);
            return null;
        });
        jdbc.update("delete from owner where owner_id = ?", ownerId);
    }

    /** owner 도메인 내부를 직접 참조하지 않고, 점주 AT 검증이 보는 행만 그대로 준비한다. */
    private Long createActiveOwner() {
        String email = "campaign-target-audience-concurrency-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        return jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
    }

    @Test
    void 같은_캠페인을_동시에_수정하면_뒤에_커밋한_값이_그대로_남는다() throws Exception {
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
                            "select c from Campaign c where c.id = :id", Campaign.class)
                    .setParameter("id", campaignId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
            if (paused.compareAndSet(false, true)) {
                locked.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return found;
        }).when(campaignRepository).findByIdForUpdate(campaignId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> service.handle(ownerId, campaignId, request(2000)));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> service.handle(ownerId, campaignId, request(3000)));
            /*
             * 첫 트랜잭션이 행 잠금을 쥔 채 아직 커밋 전이라, 둘째 호출의
             * findByIdForUpdate(SELECT ... FOR UPDATE)는 실제 DB에서 블록되어야 한다.
             * 잠금이 없다면(회귀) 이 구간에서 바로 끝나 isDone()이 true가 된다.
             */
            assertThat(isDoneWithin(second, 500)).isFalse();

            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        int targetRadius = transactionTemplate.execute(status ->
                campaignRepository.findById(campaignId).orElseThrow().getTargetRadius());
        assertThat(targetRadius).isEqualTo(3000);
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

    private CampaignUpdateRequest request(int targetRadius) {
        return new CampaignUpdateRequest(targetRadius, TargetGender.ALL, List.of(AgeGroup.AGE_20S), null, null, null);
    }
}
