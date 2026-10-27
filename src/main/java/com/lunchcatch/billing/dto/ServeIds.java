package com.lunchcatch.billing.dto;

import java.nio.ByteBuffer;
import java.util.UUID;

/*
 * serve_id 의 UUID 와 BINARY(16) 를 오가는 변환기다.
 *
 * 바이트 순서는 상위 64비트가 앞이다. 이는 JPA 가 UUID 를 BINARY 로 저장하는 순서와 같아서
 * 네이티브 INSERT 로 쓴 값을 엔티티가 그대로 읽는다. 광고 서빙이 impression_log.serve_id 를
 * 다른 순서로 저장한다면 이 클래스만 고친다.
 */
public final class ServeIds {

    private ServeIds() {
    }

    public static byte[] toBytes(UUID serveId) {
        return ByteBuffer.allocate(16)
                .putLong(serveId.getMostSignificantBits())
                .putLong(serveId.getLeastSignificantBits())
                .array();
    }

    public static UUID fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
