package com.lunchcatch.analytics.query.service;

import com.lunchcatch.analytics.batch.service.SpendFormula;
import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.SalesBucket;
import com.lunchcatch.analytics.query.SalesSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.SalesDailyRow;
import com.lunchcatch.analytics.query.repository.SalesQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/*
 * A-07 매출 현황이다. 일별 정산 지표를 일, 주, 월로 묶는다.
 *
 * 흐름 지표(충전, 환불, 조정)는 합계, 소진액은 묶음 안의 날마다 계산한 소진액의 합, 미소진 잔액은
 * 스냅샷이라 묶음 마지막 날의 값이다. 구간이 일부만 걸친 주와 월은 상태 서비스가 이미 요청 범위로
 * 잘라 준 시작과 끝을 쓴다.
 *
 * 집계가 끝나지 않은 구간(IN_PROGRESS)은 금액을 null 로 내린다. 예약액과 해제액은 내려주지 않는다.
 */
@Service
public class SalesQueryService {

    private final TimeSeriesStatusService statusService;
    private final SalesQueryRepository repository;

    public SalesQueryService(TimeSeriesStatusService statusService, SalesQueryRepository repository) {
        this.statusService = statusService;
        this.repository = repository;
    }

    public SalesSeries query(AnalyticsPeriod period, LocalDate from, LocalDate to) {
        TimeSeriesState state = statusService.resolve(period, from, to);
        List<SalesDailyRow> rows = repository.findBetween(from, to);
        List<SalesBucket> items = state.items().stream()
                .map(bucket -> toBucket(bucket, rows))
                .toList();
        return new SalesSeries(period, from, to, state.aggregatedAt(), items);
    }

    private SalesBucket toBucket(TimeSeriesBucketState bucket, List<SalesDailyRow> rows) {
        if (bucket.status() != AggregationStatus.FINAL) {
            return new SalesBucket(bucket.label(), bucket.status(), null, null, null, null, null);
        }
        long charge = 0;
        long spent = 0;
        long refund = 0;
        long adjust = 0;
        Long unspent = null;
        for (SalesDailyRow row : rows) {
            if (row.businessDate().isBefore(bucket.start()) || row.businessDate().isAfter(bucket.end())) {
                continue;
            }
            charge += row.chargeAmount();
            spent += SpendFormula.spentAmount(row.deductAmount(), row.positiveAdjustAmount());
            refund += row.refundAmount();
            adjust += row.adjustAmount();
            if (row.businessDate().equals(bucket.end())) {
                unspent = row.unspentBalance();
            }
        }
        return new SalesBucket(bucket.label(), bucket.status(), charge, spent, refund, adjust, unspent);
    }
}
