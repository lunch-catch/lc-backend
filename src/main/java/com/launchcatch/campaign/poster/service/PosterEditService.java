package com.launchcatch.campaign.poster.service;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.PosterEditLimits;
import com.launchcatch.campaign.poster.PosterSlotValue;
import com.launchcatch.campaign.poster.PosterSlots;
import com.launchcatch.campaign.poster.dto.PosterCreateResponse;
import com.launchcatch.campaign.poster.dto.PosterUpdateRequest;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주가 포스터의 가게명 텍스트와 글자 크기, 할인 문구 뒤 텍스트와 글자 크기를 고친다(#884). 시간(period)과
 * 이미지는 받지 않는다.
 *
 * 템플릿에서 다시 렌더링하지 않고 저장된 포스터 HTML(html_content)의 슬롯 요소만 고친다. 포스터는 만들 때 완성
 * HTML 을 자기 행에 저장하고 템플릿을 다시 읽지 않는 구조이고, 그 사이 템플릿이 다시 게시됐어도 이 포스터가 바뀌면
 * 안 되기 때문이다. 그래서 슬롯을 못 찾거나 자식 요소를 가진 슬롯이면 POSTER-001 로 거절한다. 자식 요소가 있으면
 * 글자 크기가 먹지 않고 텍스트를 바꿀 때 안쪽 꾸밈이 사라진다.
 *
 * 외부 호출이 없어 메서드 전체를 한 트랜잭션으로 묶는다. 캠페인 행을 잠가(findByIdForUpdate) 활성화와 겹쳐도
 * 순서가 정해지고, 포스터 행이 바뀌면 updatedAt 이 달라져 활성화의 포스터 변경 확인(verifyPosterUnchanged)이
 * 검수 시점과 다른 포스터를 걸러 낸다. 캠페인이 DRAFT 일 때만 고칠 수 있다.
 *
 * 금지 표현은 여기서 검사하지 않는다. 수정한 문구는 slot_values 의 value 에 들어가서 활성화 때 자동 검수가 본다.
 * 할인 추가 텍스트의 % 와 원 경고는 프론트가 하고 서버는 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PosterEditService {

    private final CampaignRepository campaignRepository;
    private final PosterRepository posterRepository;
    private final OwnerStatusQuery ownerStatusQuery;

    @Transactional
    public PosterCreateResponse update(Long ownerId, Long campaignId, PosterUpdateRequest request) {
        validateActiveOwner(ownerId);
        lockOwnedDraftCampaign(ownerId, campaignId);
        Poster poster = posterRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_NOT_SAVED));

        if (request.eventName() != null || request.discount() != null) {
            Map<String, PosterSlotValue> slots = PosterSlots.read(poster.getSlotValues());
            Document document = Jsoup.parseBodyFragment(poster.getHtmlContent());
            // 줄바꿈과 들여쓰기를 새로 만들지 않아 고치지 않는 부분의 HTML 이 그대로 남는다.
            document.outputSettings().prettyPrint(false);
            if (request.eventName() != null) {
                editEventName(document, slots, request.eventName());
            }
            if (request.discount() != null) {
                editDiscount(document, slots, request.discount());
            }
            poster.edit(PosterSlots.write(slots), document.body().html());
            log.info("event=POSTER_EDITED campaignId={} ownerId={} eventName={} discount={}",
                    campaignId, ownerId, request.eventName() != null, request.discount() != null);
        }
        return new PosterCreateResponse(
                poster.getId(), campaignId, poster.getTemplateId(), poster.getTitle(), poster.getHtmlContent(),
                PosterSlots.view(poster.getSlotValues(), poster.getHtmlContent()));
    }

    private void editEventName(Document document, Map<String, PosterSlotValue> slots,
            PosterUpdateRequest.EventName edit) {
        PosterSlotValue current = requireSlotValue(slots, TemplateSlotValidator.EVENT_NAME_SLOT);
        Element element = requireEditableSlot(document, TemplateSlotValidator.EVENT_NAME_SLOT);
        String text = current.value();
        if (edit.text() != null) {
            text = validateText(edit.text(), PosterEditLimits.EVENT_NAME_MIN_LENGTH,
                    PosterEditLimits.EVENT_NAME_MAX_LENGTH);
            element.text(text);
        }
        Integer fontSizePx = current.fontSizePx();
        if (edit.fontSizePx() != null) {
            fontSizePx = validateFontSize(edit.fontSizePx(), PosterEditLimits.EVENT_NAME_FONT_SIZE_MIN,
                    PosterEditLimits.EVENT_NAME_FONT_SIZE_MAX);
            element.attr("style", PosterSlots.withFontSize(element.attr("style"), fontSizePx));
        }
        // 수정 전의 자동 문구(가게 이름)를 baseText 로 남겨, 포스터를 다시 만들 때 수정한 텍스트인지 가려낸다.
        String baseText = current.baseText() != null ? current.baseText() : current.value();
        slots.put(TemplateSlotValidator.EVENT_NAME_SLOT, new PosterSlotValue("text", text, baseText, null, fontSizePx));
    }

    private void editDiscount(Document document, Map<String, PosterSlotValue> slots,
            PosterUpdateRequest.Discount edit) {
        PosterSlotValue current = requireSlotValue(slots, TemplateSlotValidator.DISCOUNT_SLOT);
        Element element = requireEditableSlot(document, TemplateSlotValidator.DISCOUNT_SLOT);
        // 이 기능 이전에 만든 포스터에는 baseText 가 없고, 그때는 value 가 곧 쿠폰 조건으로 만든 기본 문구다.
        String baseText = current.baseText() != null ? current.baseText() : current.value();
        String suffix = current.suffix() == null ? "" : current.suffix();
        String text = current.value();
        if (edit.suffix() != null) {
            suffix = validateText(edit.suffix(), PosterEditLimits.DISCOUNT_SUFFIX_MIN_LENGTH,
                    PosterEditLimits.DISCOUNT_SUFFIX_MAX_LENGTH);
            text = PosterSlots.joinDiscount(baseText, suffix);
            element.text(text);
        }
        Integer fontSizePx = current.fontSizePx();
        if (edit.fontSizePx() != null) {
            fontSizePx = validateFontSize(edit.fontSizePx(), PosterEditLimits.DISCOUNT_FONT_SIZE_MIN,
                    PosterEditLimits.DISCOUNT_FONT_SIZE_MAX);
            element.attr("style", PosterSlots.withFontSize(element.attr("style"), fontSizePx));
        }
        slots.put(TemplateSlotValidator.DISCOUNT_SLOT,
                new PosterSlotValue("text", text, baseText, suffix.isEmpty() ? null : suffix, fontSizePx));
    }

    private Campaign lockOwnedDraftCampaign(Long ownerId, Long campaignId) {
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
        return campaign;
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private PosterSlotValue requireSlotValue(Map<String, PosterSlotValue> slots, String slot) {
        PosterSlotValue value = slots.get(slot);
        if (value == null) {
            log.warn("event=POSTER_EDIT_REJECTED reason=SLOT_VALUE_MISSING slot={}", slot);
            throw new CampaignException(PosterErrorCode.SLOT_CONTRACT_VIOLATION);
        }
        return value;
    }

    // 슬롯이 정확히 하나이고 자식 요소 없이 글자만 갖는 포스터만 고친다.
    private Element requireEditableSlot(Document document, String slot) {
        Elements found = document.select("[data-slot=" + slot + "]");
        if (found.size() != 1 || !TemplateSlotValidator.isTextOnly(found.first())) {
            log.warn("event=POSTER_EDIT_REJECTED reason=SLOT_NOT_EDITABLE slot={} slotCount={}", slot, found.size());
            throw new CampaignException(PosterErrorCode.SLOT_CONTRACT_VIOLATION);
        }
        return found.first();
    }

    /*
     * 앞뒤 공백을 자른 뒤 길이를 유니코드 코드 포인트로 센다. 줄바꿈과 탭을 포함한 제어 문자, 보이지 않는 서식 문자
     * (zero-width 등), 줄과 문단 구분 문자는 거부한다. 보이지 않는 문자로 금지 표현 검수를 피하는 일을 막는다.
     */
    private String validateText(String raw, int minLength, int maxLength) {
        String text = raw.strip();
        int length = text.codePointCount(0, text.length());
        if (length < minLength || length > maxLength || hasForbiddenCharacter(text)) {
            log.info("event=POSTER_EDIT_REJECTED reason=TEXT_INVALID length={}", length);
            throw new CampaignException(PosterErrorCode.POSTER_TEXT_INVALID);
        }
        return text;
    }

    private boolean hasForbiddenCharacter(String text) {
        return text.codePoints().anyMatch(codePoint -> {
            int type = Character.getType(codePoint);
            return type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
        });
    }

    private int validateFontSize(int fontSizePx, int min, int max) {
        if (fontSizePx < min || fontSizePx > max) {
            log.info("event=POSTER_EDIT_REJECTED reason=FONT_SIZE_OUT_OF_RANGE fontSizePx={}", fontSizePx);
            throw new CampaignException(PosterErrorCode.POSTER_FONT_SIZE_OUT_OF_RANGE);
        }
        return fontSizePx;
    }
}
