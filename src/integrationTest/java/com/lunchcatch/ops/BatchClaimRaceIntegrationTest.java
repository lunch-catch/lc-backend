package com.lunchcatch.ops;

import com.lunchcatch.global.measure.MeasureSettings;
import com.lunchcatch.global.measure.RaceRunner;
import com.lunchcatch.global.measure.RaceRunner.Outcome;
import com.lunchcatch.global.measure.ScenarioMeasure;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition;
import com.lunchcatch.ops.entity.BatchExecutionLog;
import com.lunchcatch.ops.repository.BatchExecutionLogRepository;
import com.lunchcatch.ops.scheduler.BatchAlert;
import com.lunchcatch.ops.scheduler.BatchServerId;
import com.lunchcatch.ops.service.BatchExecutionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 배치 서버 여러 대가 같은 행을 동시에 점유하고 이어받는 경쟁을 반복해서 잰다(#1090). 불변식과 시나리오의 정의는
 * docs/troubleshooting/campaign/concurrency-measure-definition.md 3.3 절이 갖는다.
 *
 * 서버마다 다른 BatchServerId 를 가진 BatchExecutionService 를 직접 만든다. 점유는 upsert 뒤 owner_id 가 나인지로
 * 가르므로, 같은 값을 쓰는 두 서비스로는 승자가 하나인지 볼 수 없다. 회차마다 영업일을 새로 써서 앞 회차의 행이
 * 다음 회차에 남지 않게 한다.
 *
 * takeOverStale 은 운영에서 @Transactional 프록시를 거쳐 부른다. 직접 만든 인스턴스에는 프록시가 없으므로
 * 같은 기본 설정의 TransactionTemplate 으로 감싸 그 경계를 맞춘다. claim 은 운영에서도 트랜잭션 밖에서 부르므로
 * 감싸지 않는다.
 */
class BatchClaimRaceIntegrationTest extends AbstractOpsIntegrationTest {

    private static final String JOB = "MEASURE_RACE_JOB";
    private static final String SERVER_A = "measure-server-a";
    private static final String SERVER_B = "measure-server-b";
    private static final String SERVER_C = "measure-server-c";
    private static final String DEAD_SERVER = "measure-server-dead";
    private static final LocalDate FIRST_DATE = LocalDate.of(2031, 1, 1);

    @Autowired
    private BatchExecutionLogRepository logs;
    @Autowired
    private BatchAlert alert;
    @Autowired
    private Clock clock;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private int usedDates;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        usedDates = 0;
        // 다른 테스트가 남긴 멈춘 행을 이어받기가 함께 가져가지 않게 비운다.
        jdbc.update("DELETE FROM batch_execution_log");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM batch_execution_log WHERE job_name = ?", JOB);
    }

    @Test
    void 서버_두_대가_같은_작업을_동시에_점유해도_승자는_하나다() {
        int rounds = MeasureSettings.fromEnvironment().rounds();
        ScenarioMeasure measure = new ScenarioMeasure("batch-claim-concurrent");
        Random random = new Random(MeasureSettings.SEED);
        Condition condition = measure.condition("서버 2대 동시 점유", "C1-1", "C1-2", "C1-3", "C1-4");
        for (int i = 0; i < rounds; i++) {
            claimRound(condition.startRound(), random);
        }
        measure.finish();
    }

    @Test
    void 서버_세_대가_멈춘_행을_동시에_이어받아도_받은_서버는_하나다() {
        int rounds = MeasureSettings.fromEnvironment().rounds();
        ScenarioMeasure measure = new ScenarioMeasure("batch-takeover-concurrent");
        Random random = new Random(MeasureSettings.SEED);
        Condition condition = measure.condition("서버 3대 동시 이어받기", "C2-1", "C2-2", "C2-3", "C2-4");
        for (int i = 0; i < rounds; i++) {
            takeOverRound(condition.startRound(), random);
        }
        measure.finish();
    }

    private void claimRound(Condition.Round round, Random random) {
        LocalDate date = nextBusinessDate();
        List<String> serverIds = List.of(SERVER_A, SERVER_B);
        List<Callable<Boolean>> actions = new ArrayList<>();
        for (String serverId : serverIds) {
            BatchExecutionService server = serverAs(serverId);
            actions.add(() -> server.claim(JOB, date));
        }

        List<Outcome<Boolean>> outcomes = RaceRunner.race(actions, 0, random);

        List<String> winners = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i).succeeded() && outcomes.get(i).value()) {
                winners.add(serverIds.get(i));
            }
        }
        StoredRow stored = storedRow(date);
        round.check("C1-1", winners.size() == 1, "점유에 성공한 서버 " + winners);
        round.check("C1-2", stored != null && winners.stream().allMatch(stored.ownerId()::equals),
                "이긴 서버 " + winners + ", 저장된 소유자 " + (stored == null ? "행 없음" : stored.ownerId()));
        round.check("C1-3", stored != null && stored.attemptNo() == 1,
                "저장된 세대 " + (stored == null ? "행 없음" : stored.attemptNo()));
        round.check("C1-4", outcomes.stream().allMatch(Outcome::succeeded), "실패 " + failures(outcomes));

        round.count("winners." + winners.size());
        winners.forEach(winner -> round.count("winner." + winner));
        recordTimings(round, outcomes, "claimMillis");
    }

    private void takeOverRound(Condition.Round round, Random random) {
        LocalDate date = nextBusinessDate();
        logs.saveAndFlush(BatchExecutionLog.running(JOB, date, DEAD_SERVER));
        jdbc.update("UPDATE batch_execution_log SET updated_at = updated_at - INTERVAL 5 MINUTE"
                + " WHERE job_name = ? AND business_date = ?", JOB, date);
        List<String> serverIds = List.of(SERVER_A, SERVER_B, SERVER_C);
        List<Callable<Boolean>> actions = new ArrayList<>();
        for (String serverId : serverIds) {
            BatchExecutionService server = serverAs(serverId);
            actions.add(() -> takesOver(server, date));
        }

        List<Outcome<Boolean>> outcomes = RaceRunner.race(actions, 0, random);

        List<String> takers = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i).succeeded() && outcomes.get(i).value()) {
                takers.add(serverIds.get(i));
            }
        }
        StoredRow stored = storedRow(date);
        round.check("C2-1", takers.size() == 1, "이어받은 서버 " + takers);
        round.check("C2-2", stored != null && takers.stream().allMatch(stored.ownerId()::equals),
                "이어받은 서버 " + takers + ", 저장된 소유자 " + (stored == null ? "행 없음" : stored.ownerId()));
        round.check("C2-3", stored != null && stored.attemptNo() == 2,
                "저장된 세대 " + (stored == null ? "행 없음" : stored.attemptNo()));
        round.check("C2-4", outcomes.stream().allMatch(Outcome::succeeded), "실패 " + failures(outcomes));

        round.count("takers." + takers.size());
        takers.forEach(taker -> round.count("taker." + taker));
        recordTimings(round, outcomes, "takeOverMillis");
    }

    // 이 서버가 그 영업일의 멈춘 행을 돌려받았는지 돌려준다. 다른 행이 함께 와도 그 행만 본다.
    private boolean takesOver(BatchExecutionService server, LocalDate date) {
        List<BatchExecutionLog> taken = transaction.execute(status -> server.takeOverStale());
        return taken != null && taken.stream()
                .anyMatch(row -> JOB.equals(row.getJobName()) && date.equals(row.getBusinessDate()));
    }

    private BatchExecutionService serverAs(String serverId) {
        return new BatchExecutionService(logs, alert, clock, new BatchServerId(serverId));
    }

    private LocalDate nextBusinessDate() {
        return FIRST_DATE.plusDays(usedDates++);
    }

    private StoredRow storedRow(LocalDate date) {
        return jdbc.query(
                "SELECT owner_id, attempt_no FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                rs -> rs.next() ? new StoredRow(rs.getString("owner_id"), rs.getInt("attempt_no")) : null,
                JOB, date);
    }

    private static void recordTimings(Condition.Round round, List<Outcome<Boolean>> outcomes, String name) {
        outcomes.forEach(outcome -> round.sample(name, outcome.elapsedNanos() / 1_000_000.0));
        outcomes.stream().filter(outcome -> !outcome.succeeded())
                .forEach(outcome -> round.count("failed." + outcome.failure().getClass().getSimpleName()));
    }

    private static List<String> failures(List<Outcome<Boolean>> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.succeeded())
                .map(outcome -> outcome.failure().toString()).toList();
    }

    private record StoredRow(String ownerId, int attemptNo) {
    }
}
