package com.lunchcatch.adserving.feed.job;

import com.lunchcatch.adserving.feed.service.StoreAudienceService;
import com.lunchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 가게별 반경 안의 대상 인원을 세어 캠페인에 넘기는 00:00 단계다(FR-099 (10)). 앞 작업과 순서 의존이 없어 맨 뒤 90 에 둔다(#592).
 * 실패하면 캠페인은 전날 값으로 추천한다(FR-042). 덮어쓰기라 같은 영업일로 다시 불려도 결과가 같다.
 */
@Component
@Order(90)
@RequiredArgsConstructor
public class StoreAudienceJob implements DailyJob {

    private final StoreAudienceService storeAudienceService;

    @Override
    public String jobName() {
        return "ADSERVING_STORE_AUDIENCE";
    }

    @Override
    public void run(LocalDate businessDate) {
        storeAudienceService.aggregate(businessDate);
    }
}
