package com.launchcatch.campaign.template.repository;

import com.launchcatch.campaign.template.entity.TemplateVersion;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * 목록·상세 조회의 미리보기 html 용이다. 소프트삭제된 버전은 제외한다.
     * 지금은 DRAFT 템플릿의 버전이 소프트삭제될 길이 없지만(게시할 때만 전부
     * 함께 지워지고, 그 순간 상태도 PUBLISHED 로 바뀌어 이 메서드를 안 탄다),
     * findPage() 와 같은 조건으로 맞춰 둔다.
     */
    Optional<TemplateVersion> findFirstByTemplateIdAndDeletedAtIsNullOrderByVersionNumberDesc(Long templateId);

    /*
     * 다음 버전 번호를 매길 때 쓴다. HTML 을 전혀 안 가져오고 숫자 하나만 집계하므로
     * 버전이 많이 쌓인 템플릿이라도 가볍다. 버전이 하나도 없으면 0을 돌려준다.
     */
    @Query("select coalesce(max(v.versionNumber), 0) from TemplateVersion v where v.template.id = :templateId")
    int findMaxVersionNumber(@Param("templateId") Long templateId);

    /*
     * 게시할 버전을 찾을 때 쓴다. 소프트삭제된 버전은 후보에서 제외한다.
     */
    Optional<TemplateVersion> findByTemplateIdAndVersionNumberAndDeletedAtIsNull(Long templateId, int versionNumber);

    /*
     * 버전이 몇 개든 UPDATE한 번으로 소프트삭제한다(엔티티로 하나씩
     * 불러오면 버전마다 UPDATE가 나가고 안 쓸 HTML까지 읽는다).
     * findByTemplateIdAndVersionNumberAndDeletedAtIsNull()로 게시할
     * 버전을 먼저 찾은 뒤에 호출해야 한다. 순서를 바꾸면 그 조회가
     * 이미 삭제 표시된 행을 걸러내 버전을 못 찾는다.
     */
    @Modifying
    @Query("update TemplateVersion v set v.deletedAt = :now where v.template.id = :templateId and v.deletedAt is null")
    int softDeleteAllByTemplateId(@Param("templateId") Long templateId, @Param("now") LocalDateTime now);

    /*
     * 버전 목록 조회용 커서 페이징이다. versionNumber 가 템플릿 안에서 유일하고
     * 단조증가라 이 값 하나만으로 다음 페이지를 가른다. 소프트삭제된 버전은
     * 제외한다(게시된 템플릿은 버전이 전부 삭제돼 있어 빈 목록이 된다).
     */
    @Query("""
            select v from TemplateVersion v
            where v.template.id = :templateId
              and v.deletedAt is null
              and (:cursorVersionNumber is null or v.versionNumber < :cursorVersionNumber)
            order by v.versionNumber desc
            """)
    List<TemplateVersion> findPage(
            @Param("templateId") Long templateId,
            @Param("cursorVersionNumber") Integer cursorVersionNumber,
            Pageable pageable);

    /*
     * 게시로 소프트삭제된 버전을 완전히 지운다(#412). 아직 게시 전이라 deletedAt 이 null 인
     * 버전은 건드리지 않는다. 지워진 행 수를 그대로 배치의 처리 건수로 쓴다.
     */
    @Modifying
    @Query("delete from TemplateVersion v where v.deletedAt is not null")
    int deleteAllByDeletedAtIsNotNull();
}
