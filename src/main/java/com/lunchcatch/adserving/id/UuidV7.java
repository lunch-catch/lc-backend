package com.lunchcatch.adserving.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/*
 * UUIDv7(RFC 9562)을 만든다. 앞 48비트가 밀리초 시각이라 BINARY(16) 키로 저장하면 시간 순으로 쌓인다.
 * 시각을 인자로 받는다. 부르는 쪽이 주입받은 Clock 의 값을 넘겨 같은 요청의 다른 시각과 맞춘다.
 * 같은 밀리초 안의 순서는 보장하지 않는다. 하위 비트가 난수다.
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
