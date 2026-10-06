package com.launchcatch.ops.scheduler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * 이 배치 서버의 식별자다. batch_execution_log.owner_id 로 들어간다.
 *
 * 두 서버가 같은 값을 쓰면 액티브-액티브가 조용히 망가진다. 살아 있는 서버의 생존 신호가
 * 죽은 서버의 행까지 갱신하고, 이어받기는 "내 것이 아닌 행" 만 찾으므로 그 행을 영원히
 * 가져오지 않는다. 그날 작업이 RUNNING 으로 남은 채 아무도 모른다.
 *
 * 그래서 값을 주지 않으면 호스트 이름에 임의 접미사를 붙여 프로세스마다 다르게 만든다.
 * 호스트 이름만 쓰면 한 호스트에 두 프로세스를 띄울 때 겹치고, 환경에 따라 HOSTNAME 이
 * 아예 없을 수도 있다. 접미사가 있으면 어느 쪽이든 겹치지 않는다.
 *
 * 다시 뜬 서버가 새 값을 받는 것은 손해가 아니다. 예전 값으로 남은 자기 행을 "다른 서버 것"
 * 으로 보게 되어 스스로 이어받을 수 있다.
 *
 * 운영이 이름을 고정하고 싶으면 launchcatch.batch.owner-id 로 준다. 그때는 서버마다 다른
 * 값을 주어야 한다.
 */
@Slf4j
@Component
public class BatchServerId {

    /** owner_id 컬럼 길이다. 넘치면 저장이 거부되어 점유 자체가 실패한다. */
    private static final int MAX_LENGTH = 100;

    private final String value;

    public BatchServerId(@Value("${launchcatch.batch.owner-id:}") String configured) {
        this.value = shorten(configured.isBlank() ? generate() : configured);
        log.info("배치 서버 식별자는 {} 다", value);
    }

    public String value() {
        return value;
    }

    private static String generate() {
        return hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String hostName() {
        String fromEnvironment = System.getenv("HOSTNAME");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unknown) {
            return "unknown-host";
        }
    }

    private static String shorten(String candidate) {
        return candidate.length() <= MAX_LENGTH ? candidate : candidate.substring(0, MAX_LENGTH);
    }
}
