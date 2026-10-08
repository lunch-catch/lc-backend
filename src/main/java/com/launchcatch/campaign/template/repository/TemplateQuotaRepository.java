package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.TemplateQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateQuotaRepository extends JpaRepository<TemplateQuota, Long> {

    /*
     * 행 하나짜리 카운터를 조건부로 올린다. WHERE 가 걸려 있어서 이미 한도에 찬 상태면
     * 이 UPDATE 자체가 아무 행도 못 올리고 0을 돌려준다. 두 트랜잭션이 동시에 들어와도
     * MySQL 이 이 행에 쓰기 잠금을 걸어 순서대로 처리하므로, 조건을 다시 확인하는
     * 시점에는 하나는 반드시 한도를 넘겨 0을 받는다.
     */
    @Modifying
    @Query("UPDATE TemplateQuota q SET q.currentCount = q.currentCount + 1 WHERE q.currentCount < :max")
    int tryReserve(@Param("max") int max);
}
