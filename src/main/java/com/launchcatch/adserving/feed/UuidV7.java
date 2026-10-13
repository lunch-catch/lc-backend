package com.launchcatch.adserving.feed;

import java.security.SecureRandom;
import java.util.UUID;

/*
 * 시각 순서로 정렬되는 UUID(RFC 9562 버전 7)를 만든다. feedId 와 serveId 에 쓴다.
 * 앞 48비트가 밀리초 시각이라 serve_log 의 PK 에 넣어도 삽입 위치가 끝으로 몰린다.
 * 나머지는 난수다. serveId 를 맞혀 남의 노출로 보내는 것을 어렵게 하려고 SecureRandom 을 쓴다.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_BITS = 0x7000L;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long LOW_62_BITS = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long LOW_12_BITS = 0x0FFFL;

    private UuidV7() {
    }

    public static UUID at(long epochMillis) {
        long mostSignificant = (epochMillis << 16) | VERSION_BITS | (RANDOM.nextLong() & LOW_12_BITS);
        long leastSignificant = VARIANT_BITS | (RANDOM.nextLong() & LOW_62_BITS);
        return new UUID(mostSignificant, leastSignificant);
    }
}
