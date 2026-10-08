package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.Template;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    /*
     * versions 는 지연 로딩이라, 트랜잭션 밖에서도 latestVersion() 을 쓰려면
     * 같이 가져와야 한다. distinct 는 버전이 여러 개일 때 같은 Template 이
     * 중복된 결과로 돌아오는 것을 막는다(JOIN FETCH 와 단일 결과 메서드의 조합).
     */
    @Query("select distinct t from Template t join fetch t.versions where t.id = :id")
    Optional<Template> findByIdWithVersions(@Param("id") Long id);
}
