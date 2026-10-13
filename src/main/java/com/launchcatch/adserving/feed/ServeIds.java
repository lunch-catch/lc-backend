package com.launchcatch.adserving.feed;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/*
 * serveId 와 feedId 를 UUIDv7 로 만든다(serve_log.serve_id 컬럼 주석).
 * 앞 48비트가 밀리초 시각이라 serve_log 의 기본 키에 시간 순으로 쌓여 인덱스 페이지가 덜 쪼개진다.
 */
public final class ServeIds {

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_4122 = 0x8000_0000_0000_0000L;
    private static final long RANDOM_12_BITS = 0x0FFFL;
    private static final long RANDOM_62_BITS = 0x3FFF_FFFF_FFFF_FFFFL;

    private ServeIds() {
    }

    public static UUID next(Instant at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificant = (at.toEpochMilli() << 16) | VERSION_7 | (random.nextLong() & RANDOM_12_BITS);
        long leastSignificant = VARIANT_RFC_4122 | (random.nextLong() & RANDOM_62_BITS);
        return new UUID(mostSignificant, leastSignificant);
    }
}
