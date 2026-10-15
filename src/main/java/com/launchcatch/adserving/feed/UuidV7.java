package com.launchcatch.adserving.feed;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/*
 * serveId 와 feedId 를 UUIDv7(RFC 9562)로 만든다. 앞 48비트가 밀리초 시각이라 serve_log 의 BINARY(16) 키가 시간 순으로 쌓인다.
 * 자바 21 표준 라이브러리에 v7 생성기가 없고 이것 하나로 라이브러리를 들이지 않는다.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID at(Instant instant) {
        long randomA = RANDOM.nextLong();
        long randomB = RANDOM.nextLong();
        long mostSigBits = (instant.toEpochMilli() << 16) | 0x7000L | (randomA & 0x0FFFL);
        long leastSigBits = (randomB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSigBits, leastSigBits);
    }
}
