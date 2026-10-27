package com.launchcatch.billing.cache;

import com.launchcatch.billing.contract.DeductionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*
 * 서비스 시험에서 DailySpendCounter 대신 쓰는 인메모리 구현이다. Valkey 도 Lua 도 없다.
 * billing_deduct.lua, billing_day_rebuild.lua, billing_day_status.lua 와 같은 규칙을 자바로 옮겼다.
 *
 * 규칙이 어긋나면 서비스 시험은 통과하고 운영에서만 틀린다. 그래서 규칙이 바뀌면 스크립트와 이 클래스를 함께 고치고,
 * 스크립트의 실제 동작은 ImpressionDeductionIntegrationTest 가 진짜 Valkey 로 본다.
 * 동시성은 흉내 내지 않는다. 메서드마다 동기화해 한 번에 하나씩 처리하는 것으로 충분하다.
 */
public class InMemoryDailySpendCounter implements DailySpendCounter {

    private static final class Day {
        long reserved;
        long spend;
        boolean paused;
        long changedAtMicros;
    }

    private final Map<String, Day> days = new HashMap<>();
    private final Map<UUID, DeductionStatus> served = new HashMap<>();

    // 해시를 시험이 바라는 상태로 만든다
    public synchronized void seed(LocalDate businessDate, Long campaignId, long reserved, long spend, boolean paused) {
        Day day = new Day();
        day.reserved = reserved;
        day.spend = spend;
        day.paused = paused;
        days.put(ValkeyDailySpendCounter.key(businessDate, campaignId), day);
    }

    public synchronized boolean exists(LocalDate businessDate, Long campaignId) {
        return days.containsKey(ValkeyDailySpendCounter.key(businessDate, campaignId));
    }

    public synchronized long spendOf(LocalDate businessDate, Long campaignId) {
        return days.get(ValkeyDailySpendCounter.key(businessDate, campaignId)).spend;
    }

    public synchronized long reservedOf(LocalDate businessDate, Long campaignId) {
        return days.get(ValkeyDailySpendCounter.key(businessDate, campaignId)).reserved;
    }

    @Override
    public synchronized boolean applyStatus(LocalDate businessDate, Long campaignId, boolean paused, LocalDateTime changedAt) {
        Day day = days.get(ValkeyDailySpendCounter.key(businessDate, campaignId));
        long micros = ValkeyDailySpendCounter.epochMicros(changedAt);
        if (day == null || day.changedAtMicros >= micros) {
            return false;
        }
        day.paused = paused;
        day.changedAtMicros = micros;
        return true;
    }

    @Override
    public synchronized boolean increaseReserved(LocalDate businessDate, Long campaignId, long reserved) {
        Day day = days.get(ValkeyDailySpendCounter.key(businessDate, campaignId));
        if (day == null || day.reserved >= reserved) {
            return false;
        }
        day.reserved = reserved;
        return true;
    }

    @Override
    public synchronized void evict(LocalDate businessDate, Long campaignId) {
        days.remove(ValkeyDailySpendCounter.key(businessDate, campaignId));
    }

    @Override
    public synchronized DeductOutcome deduct(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice) {
        DeductionStatus first = served.get(serveId);
        if (first != null) {
            return DeductOutcome.replay(first);
        }
        Day day = days.get(ValkeyDailySpendCounter.key(businessDate, campaignId));
        if (day == null) {
            return DeductOutcome.hashMissing();
        }
        DeductionStatus status;
        if (day.paused) {
            status = DeductionStatus.NOT_BILLED_PAUSED;
        } else if (day.spend + unitPrice <= day.reserved) {
            day.spend += unitPrice;
            status = DeductionStatus.BILLED;
        } else {
            status = DeductionStatus.NOT_BILLED_BUDGET;
        }
        served.put(serveId, status);
        return DeductOutcome.of(status);
    }

    @Override
    public synchronized List<DeductOutcome> deductAll(List<SpendDeduction> deductions) {
        return deductions.stream()
                .map(d -> deduct(d.businessDate(), d.campaignId(), d.serveId(), d.unitPrice()))
                .toList();
    }

    @Override
    public synchronized void undoAll(List<SpendDeduction> deductions) {
        deductions.forEach(d -> undo(d.businessDate(), d.campaignId(), d.serveId(), d.unitPrice()));
    }

    @Override
    public synchronized boolean undo(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice) {
        DeductionStatus first = served.remove(serveId);
        if (first == null) {
            return false;
        }
        Day day = days.get(ValkeyDailySpendCounter.key(businessDate, campaignId));
        if (first == DeductionStatus.BILLED && day != null) {
            day.spend -= unitPrice;
        }
        return true;
    }

    @Override
    public synchronized boolean rebuildIfAbsent(LocalDate businessDate, Long campaignId, long reserved, long spend,
                                                boolean paused, LocalDateTime changedAt) {
        String key = ValkeyDailySpendCounter.key(businessDate, campaignId);
        if (days.containsKey(key)) {
            return false;
        }
        Day day = new Day();
        day.reserved = reserved;
        day.spend = spend;
        day.paused = paused;
        day.changedAtMicros = changedAt == null ? 0L : ValkeyDailySpendCounter.epochMicros(changedAt);
        days.put(key, day);
        return true;
    }

    @Override
    public synchronized Map<Long, DailySpend> readAll(LocalDate businessDate, Collection<Long> campaignIds) {
        Map<Long, DailySpend> result = new HashMap<>();
        for (Long campaignId : campaignIds) {
            Day day = days.get(ValkeyDailySpendCounter.key(businessDate, campaignId));
            if (day != null) {
                result.put(campaignId, new DailySpend(day.reserved, day.spend));
            }
        }
        return result;
    }
}
