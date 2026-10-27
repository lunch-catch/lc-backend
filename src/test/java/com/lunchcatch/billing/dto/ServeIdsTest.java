package com.lunchcatch.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServeIdsTest {

    @Test
    void 열여섯_바이트로_바꿨다가_되돌리면_같은_값이다() {
        UUID serveId = UUID.fromString("0192f3a4-7b5c-7d1e-9a2b-3c4d5e6f7a8b");

        byte[] bytes = ServeIds.toBytes(serveId);

        assertThat(bytes).hasSize(16);
        assertThat(ServeIds.fromBytes(bytes)).isEqualTo(serveId);
    }

    @Test
    void 상위_64비트가_앞에_온다() {
        UUID serveId = new UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L);

        assertThat(ServeIds.toBytes(serveId)).containsExactly(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
    }
}
