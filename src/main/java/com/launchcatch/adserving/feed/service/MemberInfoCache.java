package com.launchcatch.adserving.feed.service;

import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/*
 * 피드가 요청마다 부르던 회원 조회를 서버 메모리에 30초 둔다(#1042 결정 3). 회원 조회가 요청마다 DB 를 한 번 읽었다.
 * 위치와 노출 대상의 변경은 최대 30초 늦게 반영된다. 피드는 보통 현재 좌표를 함께 보내 저장 위치가 늦어도 영향이 작다.
 * 온보딩 미완료와 없는 회원은 캐시하지 않는다. 캐시하면 온보딩을 마친 직후 최대 30초 동안 온보딩 오류가 난다.
 */
@Service
public class MemberInfoCache {

    static final Duration TTL = Duration.ofSeconds(30);
    // 이 수를 넘기면 넣을 때 만료된 항목을 치운다. 피크에 함께 피드를 받는 회원 수보다 넉넉하다.
    static final int SWEEP_THRESHOLD = 100_000;

    private final MemberQueryService memberQueryService;
    private final Clock clock;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public MemberInfoCache(MemberQueryService memberQueryService, Clock clock) {
        this.memberQueryService = memberQueryService;
        this.clock = clock;
    }

    public Optional<MemberInfo> find(Long memberId) {
        Instant now = clock.instant();
        Entry entry = entries.get(memberId);
        if (entry != null && now.isBefore(entry.expiresAt())) {
            return Optional.of(entry.member());
        }
        Optional<MemberInfo> found = memberQueryService.findById(memberId);
        if (found.isPresent() && found.get().onboardingCompleted()) {
            if (entries.size() >= SWEEP_THRESHOLD) {
                entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
            }
            entries.put(memberId, new Entry(found.get(), now.plus(TTL)));
        } else {
            entries.remove(memberId);
        }
        return found;
    }

    private record Entry(MemberInfo member, Instant expiresAt) {
    }
}
