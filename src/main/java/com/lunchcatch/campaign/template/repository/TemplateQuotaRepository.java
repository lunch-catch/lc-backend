package com.lunchcatch.campaign.template.repository;

import com.lunchcatch.campaign.template.entity.TemplateQuota;
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

    /*
     * DRAFT 템플릿을 하드삭제할 때 자리를 돌려준다(#517). 0 이하로는 못 내려가게 조건을
     * 건다. chk_template_quota_current_count CHECK 제약과 같은 전제라, 이 조건이 없어도
     * 결국 커밋 시점에 제약 위반으로 막히겠지만 그 전에 여기서 조용히 0행만 올린다.
     */
    @Modifying
    @Query("UPDATE TemplateQuota q SET q.currentCount = q.currentCount - 1 WHERE q.currentCount > 0")
    int release();
}
