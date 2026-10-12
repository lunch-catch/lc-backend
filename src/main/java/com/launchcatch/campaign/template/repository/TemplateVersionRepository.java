package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.TemplateVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, Long> {

    @Query("select v from TemplateVersion v join fetch v.template where v.requestId = :requestId")
    Optional<TemplateVersion> findByRequestId(String requestId);

    /*
     * 템플릿을 수정할 때 LLM 에 넘길 "최신 버전 HTML" 하나만 가볍게 가져온다.
     * 이 템플릿의 버전 전체(과거 HTML 포함)를 다 불러올 필요가 없다.
     */
    Optional<TemplateVersion> findFirstByTemplateIdOrderByVersionNumberDesc(Long templateId);

    /*
     * 다음 버전 번호를 매길 때 쓴다. HTML 을 전혀 안 가져오고 숫자 하나만 집계하므로
     * 버전이 많이 쌓인 템플릿이라도 가볍다. 버전이 하나도 없으면 0을 돌려준다.
     */
    @Query("select coalesce(max(v.versionNumber), 0) from TemplateVersion v where v.template.id = :templateId")
    int findMaxVersionNumber(@Param("templateId") Long templateId);
}
