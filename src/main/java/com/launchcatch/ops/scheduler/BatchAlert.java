package com.launchcatch.ops.scheduler;

import java.time.LocalDate;

/*
 * 사람이 손을 써야 하는 일을 관리자에게 알린다 (배치 운영 문서 2장).
 *
 * 둘뿐이다. 이어받기는 한 서버가 죽었다는 뜻이고, 실패는 자동으로 다시 돌지 않아 관리자가
 * 수동 재실행을 눌러야 한다. 둘 다 알리지 않으면 아무도 모르는 채로 그날 집계가 비어 있게 된다.
 *
 * 알림 채널은 ops 가 모른다. 알림 도메인이 이 인터페이스를 구현해 @Primary 로 올리면 그쪽으로
 * 간다. 그때까지는 LoggingBatchAlert 가 받는다.
 */
public interface BatchAlert {

    void takenOver(String jobName, LocalDate businessDate, String previousOwner, String newOwner);

    void failed(String jobName, LocalDate businessDate, String reason);
}
