package com.lunchcatch.store;

import static org.assertj.core.api.Assertions.*;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.exception.StoreErrorCode;
import com.lunchcatch.store.exception.StoreException;
import com.lunchcatch.store.repository.StoreRegistrationProgressRepository;
import com.lunchcatch.store.service.StoreRegistrationProgressService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StoreRegistrationProgressIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong OWNER_IDS = new AtomicLong(800000000);

    @Autowired StoreRegistrationProgressService service;
    @Autowired StoreRegistrationProgressRepository progresses;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private TransactionTemplate tx;
    private long ownerId;
    private final List<Long> createdStoreOwnerIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(manager);
        ownerId = OWNER_IDS.incrementAndGet();
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from store_registration_progress where owner_id = ?", ownerId);
            for (Long storeOwnerId : createdStoreOwnerIds) {
                jdbc.update("delete from store where owner_id = ?", storeOwnerId);
            }
        });
    }

    private Long createStore(long storeOwnerId) {
        createdStoreOwnerIds.add(storeOwnerId);
        var now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, created_at, updated_at) "
                + "values (?, '초기 가게', 'KOREAN', '대표자', ?, ?)", storeOwnerId, now, now);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, storeOwnerId);
    }

    @Test
    void DB는_가게_정보_완료_전_가게_연결과_이전_단계_값을_거부한다() {
        tx.executeWithoutResult(status -> {
            service.initialize(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
        });
        Long storeId = tx.execute(status -> createStore(ownerId));
        assertThatThrownBy(() -> jdbc.update(
                "update store_registration_progress set store_id=? where owner_id=?", storeId, ownerId))
                .hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause().hasMessageContaining("Check constraint");
        assertThatThrownBy(() -> jdbc.update(
                "update store_registration_progress set resume_step='STORE' where owner_id=?", ownerId))
                .hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause().hasMessageContaining("Check constraint");
        assertThatThrownBy(() -> jdbc.update(
                "update store_registration_progress set resume_step='BUSINESS_VERIFICATION' where owner_id=?", ownerId))
                .hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause().hasMessageContaining("Check constraint");
    }

    private Long prepareMenuStep() {
        return tx.execute(status -> {
            service.initialize(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
            Long storeId = createStore(ownerId);
            service.completeStep(ownerId, 1, StoreRegistrationStep.STORE_INFO, storeId);
            service.completeStep(ownerId, 2, StoreRegistrationStep.LOCATION, null);
            service.completeStep(ownerId, 3, StoreRegistrationStep.HOURS, null);
            service.completeStep(ownerId, 4, StoreRegistrationStep.IMAGES, null);
            return storeId;
        });
    }

    @Test
    void 완료_단계_수정은_다음_트랜잭션에서도_재개_단계를_유지한다() {
        Long storeId = prepareMenuStep();
        var result = tx.execute(status -> service.reviseStep(ownerId, 5, StoreRegistrationStep.HOURS));
        assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(result.storeId()).isEqualTo(storeId);
        assertThat(result.version()).isEqualTo(6);
        var restored = progresses.findByOwnerId(ownerId).orElseThrow();
        assertThat(restored.getResumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(restored.getVersion()).isEqualTo(6);
    }

    @Test
    void 수정_버전_충돌은_업무_데이터도_롤백한다() {
        Long storeId = prepareMenuStep();
        tx.executeWithoutResult(status -> service.reviseStep(ownerId, 5, StoreRegistrationStep.HOURS));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("update store set name = '충돌 수정' where store_id = ?", storeId);
            service.reviseStep(ownerId, 5, StoreRegistrationStep.IMAGES);
        })).isInstanceOfSatisfying(StoreException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_VERSION_CONFLICT));
        assertThat(jdbc.queryForObject("select name from store where store_id = ?", String.class, storeId)).isEqualTo("초기 가게");
        assertThat(progresses.findByOwnerId(ownerId).orElseThrow().getVersion()).isEqualTo(6);
    }

    @Test
    void 수정_진행_상태를_flush한_후_실패하면_모두_롤백한다() {
        Long storeId = prepareMenuStep();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("update store set name = '롤백 수정' where store_id = ?", storeId);
            service.reviseStep(ownerId, 5, StoreRegistrationStep.IMAGES);
            throw new IllegalStateException("injected after revision flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select name from store where store_id = ?", String.class, storeId)).isEqualTo("초기 가게");
        var restored = progresses.findByOwnerId(ownerId).orElseThrow();
        assertThat(restored.getResumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(restored.getVersion()).isEqualTo(5);
    }

    @Test
    void 같은_버전의_수정과_현재_단계_저장은_하나만_성공한다() throws Exception {
        prepareMenuStep();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var revision = workers.submit(() -> concurrentSave(start, true));
            var completion = workers.submit(() -> concurrentSave(start, false));
            start.countDown();
            assertThat(List.of(revision.get(15, TimeUnit.SECONDS), completion.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "STORE-001");
            var restored = progresses.findByOwnerId(ownerId).orElseThrow();
            assertThat(restored.getResumeStep()).isEqualTo(StoreRegistrationStep.MENU);
            assertThat(restored.getVersion()).isEqualTo(6);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String concurrentSave(CountDownLatch start, boolean revision) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            tx.executeWithoutResult(status -> {
                if (revision) {
                    service.reviseStep(ownerId, 5, StoreRegistrationStep.HOURS);
                } else {
                    service.completeStep(ownerId, 5, StoreRegistrationStep.MENU, null);
                }
            });
            return "saved";
        } catch (StoreException e) {
            return e.getErrorCode().getCode();
        }
    }

    @Test
    void 초기화와_약관_완료를_저장하고_새_트랜잭션에서_복원한다() {
        tx.executeWithoutResult(status -> {
            var initial = service.initialize(ownerId);
            assertThat(initial.version()).isZero();
            assertThat(initial.storeId()).isNull();
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
        });
        var restored = progresses.findByOwnerId(ownerId).orElseThrow();
        assertThat(restored.getResumeStep()).isEqualTo(StoreRegistrationStep.STORE_INFO);
        assertThat(restored.getVersion()).isEqualTo(1);
        assertThat(restored.getCreatedAt()).isNotNull();
        assertThat(restored.getUpdatedAt()).isNotNull();
        assertThat(tx.execute(status -> service.initialize(ownerId)).version()).isEqualTo(1);
    }

    @Test
    void 기본_정보_저장과_가게_연결을_같이_커밋한다() {
        tx.executeWithoutResult(status -> {
            service.initialize(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
            Long storeId = createStore(ownerId);
            var result = service.completeStep(ownerId, 1, StoreRegistrationStep.STORE_INFO, storeId);
            assertThat(result.storeId()).isEqualTo(storeId);
            assertThat(result.resumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        });
        assertThat(progresses.findByOwnerId(ownerId).orElseThrow().getVersion()).isEqualTo(2);
        var restored = progresses.findByOwnerId(ownerId).orElseThrow();
        assertThat(restored.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        assertThat(restored.getVersion()).isEqualTo(2);
    }

    @Test
    void 오래된_요청은_먼저_저장한_기본_정보도_롤백한다() {
        tx.executeWithoutResult(status -> {
            service.initialize(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Long storeId = createStore(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.STORE_INFO, storeId);
        })).isInstanceOfSatisfying(StoreException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_VERSION_CONFLICT));
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id = ?", Long.class, ownerId)).isZero();
        var progress = progresses.findByOwnerId(ownerId).orElseThrow();
        assertThat(progress.getVersion()).isEqualTo(1);
        assertThat(progress.getStoreId()).isNull();
    }

    @Test
    void 진행_상태를_flush한_후_실패해도_기본_정보와_상태를_함께_롤백한다() {
        tx.executeWithoutResult(status -> {
            service.initialize(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Long storeId = createStore(ownerId);
            service.completeStep(ownerId, 1, StoreRegistrationStep.STORE_INFO, storeId);
            throw new IllegalStateException("injected after flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id = ?", Long.class, ownerId)).isZero();
        assertThat(progresses.findByOwnerId(ownerId).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void 같은_버전의_동시_완료는_하나만_성공한다() throws Exception {
        tx.executeWithoutResult(status -> service.initialize(ownerId));
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> save = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    tx.executeWithoutResult(status -> service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null));
                    return "saved";
                } catch (StoreException e) {
                    return e.getErrorCode().getCode();
                }
            };
            var first = workers.submit(save);
            var second = workers.submit(save);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "STORE-001");
            assertThat(progresses.findByOwnerId(ownerId).orElseThrow().getVersion()).isEqualTo(1);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 다른_점주의_가게를_연결하지_못한다() {
        tx.executeWithoutResult(status -> {
            service.initialize(ownerId);
            service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
        });
        Long otherStoreId = tx.execute(status -> createStore(OWNER_IDS.incrementAndGet()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                service.completeStep(ownerId, 1, StoreRegistrationStep.STORE_INFO, otherStoreId)))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        assertThat(progresses.findByOwnerId(ownerId).orElseThrow().getStoreId()).isNull();
    }

    @Test
    void 호출자_트랜잭션_없이_별도로_커밋하지_않는다() {
        assertThatThrownBy(() -> service.initialize(ownerId)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.reviseStep(ownerId, 0, StoreRegistrationStep.HOURS))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void DB도_중복_점주와_불가능한_단계_조합을_거부한다() {
        tx.executeWithoutResult(status -> service.initialize(ownerId));
        assertThatThrownBy(() -> jdbc.update("insert into store_registration_progress "
                + "(owner_id, resume_step, version, created_at, updated_at) values (?, 'TERMS', 0, ?, ?)",
                ownerId, LocalDateTime.now(), LocalDateTime.now()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update store_registration_progress set resume_step='LOCATION' where owner_id=?",
                ownerId)).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().hasMessageContaining("Check constraint");
        assertThatThrownBy(() -> jdbc.update("update store_registration_progress set version=-1 where owner_id=?", ownerId))
                .hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause().hasMessageContaining("Check constraint");
    }
}