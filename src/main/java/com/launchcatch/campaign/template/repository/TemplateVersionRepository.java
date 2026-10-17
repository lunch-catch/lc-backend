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
import org.springframework.transaction.annotation.Transactional;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, Long> {

    /*
     * 소프트삭제된 버전은 제외한다(#410 코드래빗 리뷰). 상한에 밀려 지워진 버전의
     * requestId 가 늦게 재요청으로 들어오면, 이 조회가 그 버전을 찾아 "이미 처리된
     * 요청" 으로 보고 지워진 내용을 그대로 돌려줄 수 있다. 그 요청은 더는 idempotent
     * 재시도로 보지 않고 새 요청처럼 다시 처리한다(request_id UNIQUE 제약에 걸려
     * 결국 예외로 끝난다).
     */
    @Query("""
            select v from TemplateVersion v join fetch v.template
            where v.requestId = :requestId and v.deletedAt is null
            """)
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
     * 버전 상한(20개, #410)을 넘긴 만큼을 한 번에 지운다. 호출하는 쪽이 넘기는
     * thresholdVersionNumber(= 새 버전 번호 - 20) 이하인, 아직 살아있는 버전을 전부
     * 소프트삭제한다. 번호는 건드리지 않는다.
     *
     * 몇 개가 넘쳤는지 상관없이 쿼리 한 번으로 20개 이하로 맞춘다(코드래빗 리뷰로
     * 찾음). 가장 오래된 것 하나만 지우면, 이 기능이 배포되기 전부터 20개보다 훨씬
     * 많이 쌓여 있던 템플릿은 수정할 때마다 하나 늘고 하나만 줄어 영원히 상한을
     * 넘긴 채로 남는다.
     *
     * thresholdVersionNumber 가 0 이하이면(상한을 넘기지 않았으면) 그 이하 번호를 가진
     * 버전이 있을 수 없어 자동으로 아무것도 지우지 않는다. 그래서 호출하는 쪽은
     * "넘겼는지" 를 따로 분기하지 않고 매번 그대로 부른다.
     */
    @Modifying
    @Query("""
            update TemplateVersion v set v.deletedAt = :now
             where v.template.id = :templateId
               and v.deletedAt is null
               and v.versionNumber <= :thresholdVersionNumber
            """)
    int softDeleteUpToVersionNumber(
            @Param("templateId") Long templateId,
            @Param("thresholdVersionNumber") int thresholdVersionNumber,
            @Param("now") LocalDateTime now);

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
     * 게시로 소프트삭제된 버전을 batchSize 건씩 나눠 지운다(#412). 아직 게시 전이라
     * deletedAt 이 null 인 버전은 건드리지 않는다.
     *
     * 한 트랜잭션으로 전부 지우면, 처음 도는 날처럼 쌓인 양이 많을 때 잠금을 오래 들고
     * 있어 TemplatePublishService.publish()의 소프트삭제 UPDATE나 새 버전 INSERT와
     * 부딪힌다. JPQL 의 delete 는 LIMIT 을 못 써서 네이티브 쿼리로 쓴다.
     *
     * 커스텀 @Query 메서드는 SimpleJpaRepository의 클래스 레벨 @Transactional을 물려받지
     * 않아서, 트랜잭션 없이 부르면 TransactionRequiredException으로 터진다(코드래빗
     * 리뷰로 찾음). 여기 직접 걸어야 호출하는 쪽(TemplateVersionPurgeService)이 트랜잭션
     * 없이 반복해도 호출마다 새 트랜잭션이 열려 청크별로 따로 커밋된다. 서비스나
     * 스케줄러에 걸면 전체가 한 트랜잭션으로 묶여 청크로 나눈 의미가 없어진다.
     */
    @Transactional
    @Modifying
    @Query(value = "delete from template_version where deleted_at is not null limit :batchSize",
            nativeQuery = true)
    int deleteBatchByDeletedAtIsNotNull(@Param("batchSize") int batchSize);
}
