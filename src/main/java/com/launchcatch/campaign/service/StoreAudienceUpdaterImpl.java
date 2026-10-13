package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.StoreAudienceUpdater;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.StoreAudienceGender;
import com.launchcatch.campaign.repository.StoreAudienceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * store_audience를 유일 키(storeId, targetRadius, gender, ageGroup)로 덮어쓴다. 셀마다
 * INSERT ... ON DUPLICATE KEY UPDATE를 보내 한 번의 왕복으로 끝내고, application.yml의
 * rewriteBatchedStatements=true가 호출 여러 번을 드라이버 레벨에서 묶어 보낸다. 끊어서
 * 여러 번 부르는 건 광고 서빙의 몫이라(#178), 이 서비스는 한 번에 최대 몇 행까지만 받는다.
 */
@Service
@RequiredArgsConstructor
public class StoreAudienceUpdaterImpl implements StoreAudienceUpdater {

    static final int MAX_CELLS_PER_CALL = 1000;

    private final StoreAudienceRepository storeAudienceRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void overwrite(List<StoreAudienceCell> cells) {
        Objects.requireNonNull(cells, "cells");
        if (cells.size() > MAX_CELLS_PER_CALL) {
            throw new IllegalArgumentException(
                    "cells must not exceed " + MAX_CELLS_PER_CALL + " per call: " + cells.size());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (StoreAudienceCell cell : cells) {
            storeAudienceRepository.upsert(
                    cell.storeId(), cell.targetRadius(), toGenderColumn(cell.gender()).name(),
                    cell.ageGroup().name(), cell.memberCount(), cell.businessDate(), now);
        }
    }

    /*
     * TargetGender는 ALL을 허용하지만 store_audience.gender는 MALE/FEMALE/OTHER만 받는다.
     * OTHER는 #162(회원 쪽 성별 enum)가 먼저 정해져야 StoreAudienceCell이 받을 수 있어
     * 지금은 넘어올 수 없다. ALL이 들어오면 CHECK 제약 위반으로 터지기 전에 여기서 막는다.
     */
    private StoreAudienceGender toGenderColumn(TargetGender gender) {
        return switch (gender) {
            case MALE -> StoreAudienceGender.MALE;
            case FEMALE -> StoreAudienceGender.FEMALE;
            case ALL -> throw new IllegalArgumentException(
                    "ALL gender cannot be written to store_audience yet: #162 선행 필요");
        };
    }
}
