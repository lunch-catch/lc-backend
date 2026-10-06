package com.launchcatch.ops.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 두 배치 서버가 같은 식별자를 쓰면 액티브-액티브가 조용히 망가진다.
 *
 * 살아 있는 서버의 생존 신호가 죽은 서버의 행까지 갱신하고, 이어받기는 "내 것이 아닌 행" 만
 * 찾으므로 그 행을 영원히 가져오지 않는다. 그날 작업이 RUNNING 으로 남은 채 아무도 모른다.
 * 그래서 값을 주지 않았을 때 서로 달라지는지가 이 테스트의 핵심이다.
 */
class BatchServerIdTest {

    private static final int COLUMN_LENGTH = 100;

    @Test
    @DisplayName("값을 주면 그것을 쓴다")
    void 지정한_값() {
        assertThat(new BatchServerId("batch-1").value()).isEqualTo("batch-1");
    }

    @Test
    @DisplayName("값이 없으면 스스로 만든다")
    void 스스로_만든다() {
        assertThat(new BatchServerId("").value()).isNotBlank();
    }

    /*
     * 호스트 이름만 쓰면 한 호스트에 두 프로세스를 띄울 때 겹치고, HOSTNAME 이 없는 환경에서는
     * 두 서버가 같은 값을 받는다. 임의 접미사가 그 둘을 함께 막는다.
     */
    @Test
    @DisplayName("스스로 만든 값은 프로세스마다 다르다")
    void 서로_다르다() {
        assertThat(new BatchServerId("").value()).isNotEqualTo(new BatchServerId("").value());
    }

    /** owner_id 가 100자 컬럼이다. 넘치면 저장이 거부되어 점유 자체가 실패한다. */
    @Test
    @DisplayName("컬럼 길이를 넘는 값은 자른다")
    void 길이를_자른다() {
        assertThat(new BatchServerId("a".repeat(200)).value()).hasSize(COLUMN_LENGTH);
    }
}
