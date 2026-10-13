package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/*
 * 새 버전의 슬롯별 태그가 최신 버전과 같은지 확인한다. 어긋나면 POSTER-002 이다.
 * 두 문서 모두 TemplateSlotValidator 를 이미 통과했다고 가정한다(슬롯 5개가 각각
 * 정확히 하나씩 있음). 그래서 여기서는 존재 자체를 다시 확인하지 않는다.
 */
@Component
public class TemplateStructureValidator {

    public void validate(String newHtml, String previousHtml) {
        validate(Jsoup.parseBodyFragment(newHtml), Jsoup.parseBodyFragment(previousHtml));
    }

    public void validate(Document newDocument, Document previousDocument) {
        if (!slotTags(newDocument).equals(slotTags(previousDocument))) {
            throw new CampaignException(PosterErrorCode.STRUCTURE_CHANGED);
        }
    }

    /*
     * BedrockTemplateHtmlGenerator(수정 프롬프트)도 같은 계산을 써서, 검증과 프롬프트가
     * 서로 다른 로직으로 슬롯 태그를 뽑다가 어긋나는 것을 막는다(#182).
     */
    public Map<String, String> slotTags(Document document) {
        return TemplateSlotValidator.SLOT_TAGS.keySet().stream()
                .collect(Collectors.toMap(
                        slot -> slot,
                        slot -> document.selectFirst("[data-slot=" + slot + "]").normalName()));
    }
}
