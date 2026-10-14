package com.launchcatch.billing.cache;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/*
 * 캠페인 하루치 차감 판정 입력을 모은 당일 소진 카운터다. 정본은 budget_daily_plan 이고 이것은 미러다.
 *
 * 구현은 Valkey 해시(ValkeyDailySpendCounter)이고 테스트는 같은 규칙의 인메모리 Fake 를 쓴다.
 * 해시의 필드는 reserved, spend, paused, changedAt 이다. 서비스는 이 인터페이스만 알고 Valkey 를 모른다.
 * 카운터 장애는 예외로 올린다. 대체값을 돌려주지 않는다.
 */
public interface DailySpendCounter {

    // 해시가 있고 저장된 시각보다 늦은 변경이면 중단 표시를 바꾸고 true 를 돌려준다
    boolean applyStatus(LocalDate businessDate, Long campaignId, boolean paused, LocalDateTime changedAt);

    // 해시를 지운다. 다음 차감이 DB 정본으로 다시 만든다
    void evict(LocalDate businessDate, Long campaignId);

    /*
     * 노출 하나를 판정한다. 같은 serveId 를 다시 부르면 처음 판정을 돌려주고 소진을 늘리지 않는다.
     * 해시가 없으면 아무것도 쓰지 않고 해시 없음을 돌려준다. 재구성은 부른 쪽이 정한다.
     */
    DeductOutcome deduct(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice);

    /*
     * 이번 호출이 새로 남긴 판정을 되돌린다. 노출 저장 트랜잭션이 롤백됐을 때 부른다.
     * 처리 표시를 지우고, 그 판정이 BILLED 였으면 올린 소진을 단가만큼 내린다. 처리 표시가 없으면 아무것도 하지 않는다.
     * 처리 표시가 있었으면 true 다. 이미 처리된 노출을 다시 받은 호출(replayed)에는 부르지 않는다.
     */
    boolean undo(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice);

    /*
     * 해시가 없을 때만 DB 정본으로 만든다. 만들었으면 true, 이미 있어 그대로 두었으면 false 다.
     * changedAt 이 아직 없는 계획(상태가 바뀐 적 없음)은 null 로 넘긴다.
     */
    boolean rebuildIfAbsent(LocalDate businessDate, Long campaignId, long reserved, long spend,
                            boolean paused, LocalDateTime changedAt);

    /*
     * 여러 캠페인의 예약액과 소진을 한 번에 읽는다. 해시가 없는 캠페인은 결과에 없다.
     * 캠페인마다 나눠 부르지 않는다.
     */
    Map<Long, DailySpend> readAll(LocalDate businessDate, Collection<Long> campaignIds);
}
