package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.Template;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    /*
     * versions 는 지연 로딩이라, 트랜잭션 밖에서도 latestVersion() 을 쓰려면
     * 같이 가져와야 한다. distinct 는 버전이 여러 개일 때 같은 Template 이
     * 중복된 결과로 돌아오는 것을 막는다(JOIN FETCH 와 단일 결과 메서드의 조합).
     * 지금은 쓰는 곳이 없다. 템플릿 목록/상세 조회 쪽에서 필요해지면 그때 쓴다.
     */
    @Query("select distinct t from Template t join fetch t.versions where t.id = :id")
    Optional<Template> findByIdWithVersions(@Param("id") Long id);

    /*
     * 수정 저장 시 행 잠금을 걸고 조회한다(SELECT ... FOR UPDATE). 같은 템플릿을
     * 동시에 수정하는 두 트랜잭션이 있으면 하나가 끝날 때까지 다른 하나를 기다리게
     * 해서, 버전 번호를 매기는 중간에 서로 끼어들지 못하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Template t where t.id = :id")
    Optional<Template> findByIdForUpdate(@Param("id") Long id);
}
