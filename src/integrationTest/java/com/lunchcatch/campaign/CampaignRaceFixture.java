package com.lunchcatch.campaign;

import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.repository.CampaignRepository;
import com.lunchcatch.campaign.template.entity.Template;
import com.lunchcatch.campaign.template.repository.TemplateRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 동시성 측정이 회차마다 쓰는 준비 데이터를 만들고 치운다(#1090).
 *
 * 점주, 가게, 캠페인, 템플릿을 커밋된 행으로 만든다. 측정 테스트는 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮어
 * 워커 스레드가 다른 연결로 읽기 때문이다. 기존 통합 테스트의 준비 코드를 고치지 않고 이 클래스로 복사했다.
 *
 * 만든 행의 번호를 기억해 두었다가 deleteAll 이 외래 키 순서대로 지운다.
 */
final class CampaignRaceFixture {

    static final String TEMPLATE_HTML = "<div>"
            + "<h1 data-slot=\"eventName\">가게</h1>"
            + "<p data-slot=\"discount\">할인 내용</p>"
            + "<p data-slot=\"period\">기간</p>"
            + "<img data-slot=\"image\" alt=\"메뉴 이미지\">"
            + "</div>";

    record Draft(Long ownerId, Long storeId, Long campaignId) {
    }

    private final JdbcTemplate jdbc;
    private final CampaignRepository campaignRepository;
    private final TemplateRepository templateRepository;
    private final List<Draft> drafts = new ArrayList<>();
    private final List<Long> templateIds = new ArrayList<>();

    CampaignRaceFixture(JdbcTemplate jdbc, CampaignRepository campaignRepository,
                        TemplateRepository templateRepository) {
        this.jdbc = jdbc;
        this.campaignRepository = campaignRepository;
        this.templateRepository = templateRepository;
    }

    /*
     * 활성화 요건(하루 예산과 집행 기간)을 갖춘 DRAFT 캠페인을 새 점주와 새 가게로 만든다.
     * 활성화는 점주당 분당 3회로 제한되므로 회차마다 점주를 새로 만들어야 제한에 걸리지 않는다.
     */
    Draft newActivatableDraft() {
        Long ownerId = createActiveOwner();
        Long storeId = createFinalizedStore(ownerId);
        LocalDate today = LocalDate.now();
        Campaign campaign = campaignRepository.saveAndFlush(Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, today.plusDays(1), today.plusDays(30),
                LocalDateTime.now()));
        Draft draft = new Draft(ownerId, storeId, campaign.getId());
        drafts.add(draft);
        return draft;
    }

    // 포스터를 만들 수 있는 게시된 활성 템플릿이다.
    Long newPublishedActiveTemplate() {
        LocalDateTime now = LocalDateTime.now();
        Template template = Template.createDraft("동시성 측정 템플릿 " + System.nanoTime());
        template.addDraftVersion(1L, now, "측정용", "race-" + System.nanoTime(), TEMPLATE_HTML, 1);
        template.publish(TEMPLATE_HTML, now, 1);
        template.changeActive(true, 1L, now);
        Long templateId = templateRepository.saveAndFlush(template).getId();
        templateIds.add(templateId);
        return templateId;
    }

    int statusChangesToScheduled(Long campaignId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from campaign_status_log"
                        + " where campaign_id = ? and from_status = 'DRAFT' and to_status = 'SCHEDULED'",
                Integer.class, campaignId);
        return count == null ? 0 : count;
    }

    // 포스터의 이미지 키와 수정 시각을 별도 연결로 읽는다. 커밋된 값만 보인다.
    PosterSnapshot readPoster(Long campaignId) {
        return jdbc.query(
                "select image_object_key, updated_at from poster where campaign_id = ?",
                rs -> rs.next()
                        ? new PosterSnapshot(rs.getString("image_object_key"),
                                rs.getTimestamp("updated_at").toLocalDateTime())
                        : null,
                campaignId);
    }

    record PosterSnapshot(String imageObjectKey, LocalDateTime updatedAt) {
    }

    void deleteAll() {
        for (Draft draft : drafts) {
            jdbc.update("delete from poster_moderation_result where poster_id in"
                    + " (select poster_id from poster where campaign_id = ?)", draft.campaignId());
            jdbc.update("delete from poster where campaign_id = ?", draft.campaignId());
            jdbc.update("delete from campaign_status_log where campaign_id = ?", draft.campaignId());
            jdbc.update("delete from campaign where campaign_id = ?", draft.campaignId());
            jdbc.update("delete from store where store_id = ?", draft.storeId());
            jdbc.update("delete from owner where owner_id = ?", draft.ownerId());
        }
        for (Long templateId : templateIds) {
            jdbc.update("delete from template_version where template_id = ?", templateId);
            jdbc.update("delete from template where template_id = ?", templateId);
        }
        drafts.clear();
        templateIds.clear();
    }

    private Long createActiveOwner() {
        String email = "race-measure-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        return jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
    }

    private Long createFinalizedStore(Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, "
                        + "business_verification_status, business_verified_at, latitude, longitude, finalized, "
                        + "finalized_at, created_at, updated_at) "
                        + "values (?, ?, 'KOREAN', ?, 'VERIFIED', ?, 37.5, 127.0, true, ?, ?, ?)",
                ownerId, "race-measure-store-" + System.nanoTime(), "대표자", now, now, now, now);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
    }
}
