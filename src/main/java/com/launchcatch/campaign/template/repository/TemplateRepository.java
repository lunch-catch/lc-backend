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
     * 수정 저장 시 행 잠금을 걸고 조회한다(SELECT ... FOR UPDATE). 같은 템플릿을
     * 동시에 수정하는 두 트랜잭션이 있으면 하나가 끝날 때까지 다른 하나를 기다리게
     * 해서, 버전 번호를 매기는 중간에 서로 끼어들지 못하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Template t where t.id = :id")
    Optional<Template> findByIdForUpdate(@Param("id") Long id);
}
