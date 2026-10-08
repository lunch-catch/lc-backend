package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/*
 * 슬롯 5개가 각각 정확히 하나씩 있고 허용된 태그 안에 있는지 확인한다.
 * 어긋나면 POSTER-001 이다.
 */
@Component
public class TemplateSlotValidator {

    private static final Set<String> TEXT_TAGS = Set.of("div", "span", "p", "h1", "h2", "h3", "strong", "em");

    private static final Map<String, Set<String>> SLOT_TAGS = Map.of(
            "eventName", TEXT_TAGS,
            "discount", TEXT_TAGS,
            "period", TEXT_TAGS,
            "adLabel", TEXT_TAGS,
            "image", Set.of("img"));

    public void validate(String html) {
        validate(Jsoup.parseBodyFragment(html));
    }

    public void validate(Document document) {
        SLOT_TAGS.forEach((slot, allowedTags) -> {
            Elements found = document.select("[data-slot=" + slot + "]");
            if (found.size() != 1 || !allowedTags.contains(found.first().normalName())) {
                throw new CampaignException(PosterErrorCode.SLOT_CONTRACT_VIOLATION);
            }
        });
    }
}
