package com.launchcatch.adserving.wishlist.job;

import com.launchcatch.adserving.wishlist.service.WishlistService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 끝난 날을 빼고 3일이 지난 찜을 지우는 00:00 단계다(#548).
 * 다른 단계의 결과를 읽지도 바꾸지도 않아 정산 단계(40~70) 뒤에 둔다. 앞 단계가 실패하면 정리가 하루 미뤄질 뿐이다(#553).
 * 같은 영업일로 다시 불려도 지울 행이 없어 결과가 같다.
 */
@Component
@Order(80)
@RequiredArgsConstructor
public class EndedWishlistCleanupJob implements DailyJob {

    private final WishlistService wishlistService;

    @Override
    public String jobName() {
        return "ADSERVING_ENDED_WISHLIST_CLEANUP";
    }

    @Override
    public void run(LocalDate businessDate) {
        wishlistService.deleteExpiredEndedWishes(businessDate);
    }
}
