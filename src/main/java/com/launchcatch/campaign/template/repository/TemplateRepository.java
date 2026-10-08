package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, Long> {
}
