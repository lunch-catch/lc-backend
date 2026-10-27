package com.lunchcatch.coupon.repository;

import com.lunchcatch.coupon.CouponSequenceCode;
import com.lunchcatch.coupon.config.CouponProperties;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/*
 * 이벤트마다 Redis 키 세 개로 재고 순번 큐를 관리한다.
 * available은 아직 예약하지 않은 순번 목록이고 pending은 예약한 뒤 커밋이나 반납을 기다리는 순번이다.
 * pending의 값은 "순번:예약 ID"이고 점수는 예약한 시각이다.
 * meta는 state(READY, SOLD_OUT, BLOCKED), total(전체 재고), completed(커밋된 발급 수)를 갖는 해시다.
 * 여러 키를 함께 바꾸는 동작은 Lua 스크립트로 원자적으로 실행한다.
 */
@Repository
@RequiredArgsConstructor
public class CouponSequenceRepository {

    private static final DefaultRedisScript<Long> COMPLETE = script("complete");
    private static final DefaultRedisScript<Long> COMPLETE_MANY = script("complete_many");
    private static final DefaultRedisScript<Long> RELEASE = script("release");
    private static final DefaultRedisScript<List<Long>> RESERVE_MANY = listScript("reserve_many");
    private static final DefaultRedisScript<Long> RETRY_AVAILABILITY = script("retry_availability");
    private static final DefaultRedisScript<Long> BLOCK = script("block");
    private static final DefaultRedisScript<Long> REBUILD = script("rebuild");
    private final CouponProperties properties;
    private final StringRedisTemplate redis;

    /*
     * reserve_many.lua를 한 번 실행해 예약 ID마다 순번을 하나씩 예약한다.
     * 결과는 예약 ID와 같은 순서이며 양수는 순번, 0은 소진, -1은 차단, -2는 일시적으로 남은 순번 없음이다.
     * Redis가 결과 수를 다르게 돌려주면 예약 상태를 알 수 없으므로 예외를 던져 호출자가 차단하게 한다.
     */
    public List<Long> reserveAll(long eventId, List<String> reservationIds, long staleAfterMillis) {
        String[] args = new String[reservationIds.size() + 1];
        args[0] = Long.toString(staleAfterMillis);
        for (int i = 0; i < reservationIds.size(); i++) {
            args[i + 1] = reservationIds.get(i);
        }
        List<Long> result = redis.execute(RESERVE_MANY, keys(eventId), (Object[]) args);
        if (result == null || result.size() != reservationIds.size()) {
            throw new IllegalStateException("Redis did not return a coupon sequence result for each reservation");
        }
        return result;
    }

    // 이벤트별 Lua의 원자성은 유지하며 여러 이벤트의 예약 응답을 파이프라인으로 함께 받는다.
    public Map<Long, List<Long>> reserveAll(Map<Long, List<String>> reservationIds, long staleAfterMillis) {
        List<Long> events = new ArrayList<>(reservationIds.keySet());
        List<Object> replies = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (long eventId : events) {
                List<String> arguments = new ArrayList<>(keys(eventId));
                arguments.add(Long.toString(staleAfterMillis));
                arguments.addAll(reservationIds.get(eventId));
                connection.scriptingCommands().eval(RESERVE_MANY.getScriptAsString().getBytes(StandardCharsets.UTF_8),
                        ReturnType.MULTI, 3, arguments.stream().map(value -> value.getBytes(StandardCharsets.UTF_8))
                                .toArray(byte[][]::new));
            }
            return null;
        });
        if (replies.size() != events.size()) {
            throw new IllegalStateException("Redis did not return a result for each coupon event");
        }
        Map<Long, List<Long>> results = new LinkedHashMap<>();
        for (int i = 0; i < events.size(); i++) {
            long eventId = events.get(i);
            if (!(replies.get(i) instanceof List<?> reply) || reply.size() != reservationIds.get(eventId).size()) {
                throw new IllegalStateException("Redis did not return a result for each coupon reservation");
            }
            List<Long> sequences = new ArrayList<>(reply.size());
            for (Object value : reply) {
                if (!(value instanceof Long sequence) || sequence < -2 || sequence > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Redis returned an invalid coupon sequence");
                }
                sequences.add(sequence);
            }
            results.put(eventId, sequences);
        }
        return results;
    }

    /*
     * 커밋된 예약을 pending에서 지우고 completed를 1 늘린다.
     * 이번 완료로 completed가 total에 닿았으면 SOLD_OUT으로 바꾸고 true를 돌려줘 호출자가 소진을 통보하게 한다.
     * 큐가 READY가 아니거나 같은 예약이 pending에 없으면 아무것도 바꾸지 않고 false를 돌려준다.
     */
    public boolean complete(long eventId, int sequence, String reservationId) {
        return Long.valueOf(1).equals(redis.execute(COMPLETE, keys(eventId), Integer.toString(sequence), reservationId));
    }

    public boolean completeAll(long eventId, List<Integer> sequences, List<String> reservationIds) {
        String[] args = new String[sequences.size() * 2];
        for (int i = 0; i < sequences.size(); i++) {
            args[i * 2] = sequences.get(i).toString();
            args[i * 2 + 1] = reservationIds.get(i);
        }
        return Long.valueOf(1).equals(redis.execute(COMPLETE_MANY, keys(eventId), (Object[]) args));
    }

    // 롤백된 예약을 pending에서 지우고 순번을 available 끝에 돌려놓으며 같은 예약이 없으면 아무것도 하지 않는다.
    public void release(long eventId, int sequence, String reservationId) {
        redis.execute(RELEASE, keys(eventId), Integer.toString(sequence), reservationId);
    }

    // 예약 대기 요청만 사용하는 조회이며 2는 예약이 끝날 때까지 기다려야 한다는 뜻이다.
    public int retryAvailability(long eventId, long staleAfterMillis) {
        Long result = redis.execute(RETRY_AVAILABILITY, keys(eventId), Long.toString(staleAfterMillis));
        return result == null ? CouponSequenceCode.BLOCKED.code() : Math.toIntExact(result);
    }

    // 큐를 BLOCKED로 바꿔 모든 서버의 새 예약을 막는다.
    public void block(long eventId) {
        redis.execute(BLOCK, keys(eventId));
    }

    // 부분 실행 여부를 모르는 여러 이벤트를 한 번의 대기로 차단하며 기존 보존 기한을 유지한다.
    public void blockAll(java.util.Set<Long> eventIds) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (long eventId : eventIds) {
                connection.scriptingCommands().eval(BLOCK.getScriptAsString().getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER, 3, keys(eventId).stream()
                                .map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
            }
            return null;
        });
    }

    // 큐가 준비되지 않았으면 -1, 완료 수가 총량에 닿았으면 0, 남았으면 1을 돌려줘 reserveAll과 같은 부호를 쓴다.
    public int availability(long eventId) {
        List<Object> values = redis.opsForHash().multiGet(keys(eventId).get(2), List.of("state", "total", "completed", "expiresAt"));
        return parseAvailability(values);
    }

    // 같은 묶음의 이벤트 정보를 한 왕복으로 읽되 각 이벤트의 상태는 별도로 판정한다.
    public Map<Long, Integer> availability(List<Long> eventIds) {
        byte[][] fields = List.of("state", "total", "completed", "expiresAt").stream()
                .map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
        List<Object> replies = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (long eventId : eventIds) {
                connection.hashCommands().hMGet(keys(eventId).get(2).getBytes(StandardCharsets.UTF_8), fields);
            }
            return null;
        });
        if (replies.size() != eventIds.size()) {
            throw new IllegalStateException("Redis did not return availability for each coupon event");
        }
        Map<Long, Integer> results = new LinkedHashMap<>();
        for (int i = 0; i < eventIds.size(); i++) {
            if (!(replies.get(i) instanceof List<?> values) || values.size() != 4) {
                throw new IllegalStateException("Redis returned invalid coupon availability");
            }
            results.put(eventIds.get(i), parseAvailability(values));
        }
        return results;
    }

    private int parseAvailability(List<?> values) {
        Object state = values.get(0);
        if ((!"READY".equals(state) && !"SOLD_OUT".equals(state))
                || values.get(1) == null || values.get(2) == null || values.get(3) == null) {
            return CouponSequenceCode.BLOCKED.code();
        }
        boolean soldOut = values.get(1).equals(values.get(2));
        if ("SOLD_OUT".equals(state) && !soldOut) {
            return CouponSequenceCode.BLOCKED.code();
        }
        return soldOut ? CouponSequenceCode.SOLD_OUT.code() : CouponSequenceCode.AVAILABLE.code();
    }

    /*
     * 이벤트의 키를 모두 지우고 DB에서 읽은 AVAILABLE 순번과 발급 수로 큐를 다시 만든다.
     * 채우는 동안은 BLOCKED로 두고 넣은 수가 맞을 때만 남은 재고에 따라 READY 또는 SOLD_OUT으로 바꾼다.
     * 호출자는 이벤트의 DB 배타 잠금을 커밋까지 유지해 큐를 채우는 동안 발급을 막는다.
     */
    public void rebuild(long eventId, List<Integer> sequences, long completed, long expiresAt) {
        List<String> keys = keys(eventId);
        String expiry = Long.toString(expiresAt);
        if (!Long.valueOf(1).equals(redis.execute(REBUILD, keys, "start", expiry,
                Long.toString(sequences.size() + completed), Long.toString(completed)))) {
            throw new IllegalStateException("Coupon sequence retention expired");
        }
        for (int offset = 0; offset < sequences.size(); offset += properties.sequence().rebuildBatchSize()) {
            List<Integer> batch = sequences.subList(offset, Math.min(offset + properties.sequence().rebuildBatchSize(), sequences.size()));
            String[] args = new String[batch.size() + 2];
            args[0] = "append";
            args[1] = expiry;
            for (int i = 0; i < batch.size(); i++) {
                args[i + 2] = batch.get(i).toString();
            }
            if (!Long.valueOf(1).equals(redis.execute(REBUILD, keys, (Object[]) args))) {
                throw new IllegalStateException("Coupon sequence rebuild interrupted");
            }
        }
        if (!Long.valueOf(1).equals(redis.execute(REBUILD, keys, "finish", expiry, Integer.toString(sequences.size())))) {
            throw new IllegalStateException("Coupon sequence rebuild count mismatch");
        }
    }

    /*
     * 이벤트의 available, pending, meta 키를 이 순서로 돌려준다.
     * 이벤트 ID를 해시 태그로 감싸 Redis 클러스터에서도 세 키가 같은 슬롯에 놓이게 한다.
     */
    private static List<String> keys(long eventId) {
        String prefix = "coupon:{" + eventId + "}:";
        return List.of(prefix + "available", prefix + "pending", prefix + "meta");
    }

    // 정수 하나를 돌려주는 Lua 스크립트를 클래스패스의 redis/coupon 에서 읽는다.
    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/coupon/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }

    // 정수 배열을 돌려주는 Lua 스크립트를 읽으며 제네릭 List 타입을 Class로 넘길 수 없어 경고를 끈다.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DefaultRedisScript<List<Long>> listScript(String name) {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/coupon/" + name + ".lua"));
        script.setResultType((Class) List.class);
        return script;
    }
}
