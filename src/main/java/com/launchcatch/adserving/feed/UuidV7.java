package com.launchcatch.adserving.feed;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/*
 * 시각이 앞에 오는 UUID(RFC 9562 의 버전 7)를 만든다. serve_log 의 PK 가 시간 순으로 쌓여 삽입이 B-tree 끝에 붙는다.
 * 나머지 74비트는 보안 난수다. serveId 를 맞혀 남의 카드로 노출을 보내는 시도를 어렵게 한다.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate(Instant now) {
        long mostSigBits = (now.toEpochMilli() << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long leastSigBits = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSigBits, leastSigBits);
    }
}
