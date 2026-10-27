package com.lunchcatch.adserving.engine.service;

import com.lunchcatch.adserving.engine.CandidateChange;
import com.lunchcatch.adserving.engine.entity.AdCandidate;
import com.lunchcatch.adserving.engine.entity.CandidateCampaignState;
import com.lunchcatch.adserving.engine.repository.AdCandidateRepository;
import com.lunchcatch.global.geo.BoundingBox;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 그날 후보를 서버 메모리에 두고 피드와 노출이 DB 대신 읽는다(#1042). 쓰기는 지금처럼 DB 에 한다.
 * 피드마다 그날 후보를 범위 조회로 읽고 노출마다 쿠폰 소진을 읽던 것이 부하 시험에서 DB 커넥션 풀을 바닥냈다.
 *
 * 후보를 바꾼 서버가 커밋 뒤에 Valkey 로 알리면 모든 서버가 그 후보만 다시 읽는다(CandidateChangeNotifier).
 * 알림은 받는 쪽이 그 순간 붙어 있지 않으면 사라진다. 그래서 읽을 때 5초가 지났으면 통째로 다시 읽어 놓친 알림을 메운다.
 * 앱 서버는 스케줄러를 켜지 않아 주기 작업 대신 읽을 때 다시 읽는다.
 *
 * 다시 읽기는 한 스레드만 한다. 다른 스레드는 그동안 이전 후보를 읽는다. 처음 읽거나 날이 바뀌면 읽을 때까지 기다린다.
 * 다시 읽기가 실패하면 이전 후보를 그대로 쓰고 5초 뒤 다시 해 본다. 기본 처리(오류로 올린다)를 벗어난다.
 * DB 가 잠깐 멈춘 동안에도 피드와 노출이 이어 가고, 이전 후보는 최대 5초 남짓 낡았을 뿐이다.
 */
@Slf4j
@Service
public class CandidateCache {

    static final Duration MAX_AGE = Duration.ofSeconds(5);
    static final String RELOAD_FAILED_METRIC = "adserving_candidate_cache_reload_failed_total";

    private final AdCandidateRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ReentrantLock loading = new ReentrantLock();
    private volatile Snapshot snapshot;

    public CandidateCache(AdCandidateRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // 그날 서빙할 수 있고 소진되지 않은 후보 가운데 상자 안의 가게만 돌려준다. 원 바깥은 부르는 쪽이 거리로 거른다.
    public List<AdCandidate> findServableWithin(LocalDate businessDate, BoundingBox box) {
        return snapshotFor(businessDate).byCampaign().values().stream()
                .filter(c -> c.getCampaignState() == CandidateCampaignState.SERVABLE && c.getSoldOutAt() == null)
                .filter(c -> box.contains(c.getLatitude().doubleValue(), c.getLongitude().doubleValue()))
                .toList();
    }

    // 그날 후보가 쿠폰 소진으로 표시됐는지 본다. 후보가 없으면 소진이 아니다.
    public boolean isSoldOut(LocalDate businessDate, Long campaignId) {
        AdCandidate candidate = snapshotFor(businessDate).byCampaign().get(campaignId);
        return candidate != null && candidate.getSoldOutAt() != null;
    }

    // 다른 서버가 보낸 변경 알림을 반영한다. 그날 전체면 다음 읽기가 통째로 다시 읽고, 후보 하나면 그 후보만 다시 읽는다.
    public void apply(CandidateChange change) {
        if (change.campaignId() == null) {
            expire(change.businessDate());
        } else {
            refresh(change.businessDate(), change.campaignId());
        }
    }

    // 후보 하나가 바뀌었다는 알림을 받아 그 후보만 다시 읽는다. 아직 읽지 않은 날이면 다음 읽기가 DB 에서 읽으므로 할 일이 없다.
    public void refresh(LocalDate businessDate, Long campaignId) {
        loading.lock();
        try {
            Snapshot current = snapshot;
            if (current == null || !current.businessDate().equals(businessDate)) {
                return;
            }
            Map<Long, AdCandidate> next = new LinkedHashMap<>(current.byCampaign());
            repository.findByIdBusinessDateAndIdCampaignId(businessDate, campaignId)
                    .ifPresentOrElse(c -> next.put(campaignId, c), () -> next.remove(campaignId));
            snapshot = new Snapshot(businessDate, Collections.unmodifiableMap(next), current.loadedAt());
        } finally {
            loading.unlock();
        }
    }

    /*
     * 그날 후보가 통째로 바뀌었다는 알림(적재, 예산)을 받아 다음 읽기가 다시 읽게 한다.
     * 다시 읽기와 같은 잠금 안에서 한다. 알림 전의 값을 읽던 다시 읽기가 끝나며 이 표시를 덮어쓰지 않게 한다.
     */
    public void expire(LocalDate businessDate) {
        loading.lock();
        try {
            Snapshot current = snapshot;
            if (current != null && current.businessDate().equals(businessDate)) {
                snapshot = new Snapshot(businessDate, current.byCampaign(), Instant.EPOCH);
            }
        } finally {
            loading.unlock();
        }
    }

    private Snapshot snapshotFor(LocalDate businessDate) {
        Snapshot current = snapshot;
        if (current != null && current.freshFor(businessDate, clock.instant())) {
            return current;
        }
        boolean sameDay = current != null && current.businessDate().equals(businessDate);
        if (sameDay) {
            if (!loading.tryLock()) {
                return current;
            }
        } else {
            loading.lock();
        }
        try {
            current = snapshot;
            if (current != null && current.freshFor(businessDate, clock.instant())) {
                return current;
            }
            return reload(businessDate, current);
        } finally {
            loading.unlock();
        }
    }

    private Snapshot reload(LocalDate businessDate, Snapshot previous) {
        try {
            Map<Long, AdCandidate> byCampaign = new LinkedHashMap<>();
            repository.findByIdBusinessDate(businessDate).forEach(c -> byCampaign.put(c.getCampaignId(), c));
            snapshot = new Snapshot(businessDate, Collections.unmodifiableMap(byCampaign), clock.instant());
            return snapshot;
        } catch (DataAccessException e) {
            if (previous == null || !previous.businessDate().equals(businessDate)) {
                throw e;
            }
            meterRegistry.counter(RELOAD_FAILED_METRIC).increment();
            log.warn("event=ADSERVING_CANDIDATE_CACHE_RELOAD_FAILED businessDate={} fallback=KEEP_PREVIOUS", businessDate, e);
            // 실패한 뒤 요청마다 다시 읽지 않도록 5초 뒤에 다시 해 본다
            snapshot = new Snapshot(businessDate, previous.byCampaign(), clock.instant());
            return snapshot;
        }
    }

    private record Snapshot(LocalDate businessDate, Map<Long, AdCandidate> byCampaign, Instant loadedAt) {

        boolean freshFor(LocalDate date, Instant now) {
            return businessDate.equals(date) && now.isBefore(loadedAt.plus(MAX_AGE));
        }
    }
}
