package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.TemplateVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, Long> {

    @Query("select v from TemplateVersion v join fetch v.template where v.requestId = :requestId")
    Optional<TemplateVersion> findByRequestId(String requestId);
}
