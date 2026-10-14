package com.launchcatch.adserving.feed;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/*
 * serve_id 와 feed_id 를 UUIDv7(RFC 9562)로 만든다. 앞 48비트가 밀리초 시각이라 시간 순서로 쌓여
 * serve_log 의 PK 인덱스에 순서대로 들어간다.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate(Instant now) {
        long random = RANDOM.nextLong();
        long mostSignificant = (now.toEpochMilli() << 16) | 0x7000L | (random >>> 52 & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
