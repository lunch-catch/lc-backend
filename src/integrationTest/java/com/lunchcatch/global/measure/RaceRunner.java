package com.lunchcatch.global.measure;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/*
 * 동작 여러 개를 래치로 동시에 출발시키고 모든 결과를 모은다(#1090).
 *
 * 첫 동작은 출발과 함께 시작한다. 나머지는 0 이상 maxJitterMillis 이하에서 난수로 정한 만큼 기다렸다가
 * 시작한다. 난수는 호출하는 쪽이 넘긴 Random 에서 동작 순서대로 뽑으므로, 씨앗이 같으면 흔들림의 열이 같다.
 * 스레드의 실제 실행 순서까지 같아진다는 뜻은 아니다.
 *
 * 동작이 던진 예외는 삼키지 않고 Outcome 에 담는다. 어떤 예외로 끝났는지도 불변식이 보는 대상이기 때문이다.
 */
public final class RaceRunner {

    private static final long TIMEOUT_SECONDS = 60;

    private RaceRunner() {
    }

    public record Outcome<T>(T value, Throwable failure, long startOffsetNanos, long elapsedNanos) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    public static <T> List<Outcome<T>> race(List<Callable<T>> actions, long maxJitterMillis, Random random) {
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("동작이 하나도 없다");
        }
        long maxJitterNanos = TimeUnit.MILLISECONDS.toNanos(maxJitterMillis);
        long[] jitters = new long[actions.size()];
        for (int i = 1; i < jitters.length; i++) {
            jitters[i] = random.nextLong(maxJitterNanos + 1);
        }

        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            CountDownLatch ready = new CountDownLatch(actions.size());
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < actions.size(); i++) {
                futures.add(pool.submit(task(actions.get(i), jitters[i], ready, gate)));
            }
            await(ready);
            gate.countDown();
            return collect(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Callable<Outcome<T>> task(
            Callable<T> action, long jitterNanos, CountDownLatch ready, CountDownLatch gate) {
        return () -> {
            ready.countDown();
            gate.await();
            long released = System.nanoTime();
            sleepUntil(released + jitterNanos);
            long started = System.nanoTime();
            try {
                T value = action.call();
                return new Outcome<>(value, null, started - released, System.nanoTime() - started);
            } catch (Throwable failure) {
                return new Outcome<>(null, failure, started - released, System.nanoTime() - started);
            }
        };
    }

    private static void sleepUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        while (remaining > 0) {
            LockSupport.parkNanos(remaining);
            remaining = deadlineNanos - System.nanoTime();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("스레드가 " + TIMEOUT_SECONDS + "초 안에 출발 준비를 마치지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("출발을 기다리다 중단됐다", e);
        }
    }

    private static <T> List<Outcome<T>> collect(List<Future<Outcome<T>>> futures) {
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<Outcome<T>> future : futures) {
            try {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                throw new AssertionError("동작이 " + TIMEOUT_SECONDS + "초 안에 끝나지 않았다", e);
            } catch (ExecutionException e) {
                throw new AssertionError("동작을 실행하는 스레드가 실패했다", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("결과를 기다리다 중단됐다", e);
            }
        }
        return outcomes;
    }
}
