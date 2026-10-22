package com.launchcatch.campaign.template;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/*
 * 슬롯 4개(eventName, discount, period, image)가 각각 정확히 하나씩 있고 허용된 태그 안에 있는지
 * 확인한다. 어긋나면 POSTER-001 이다. 포스터에 광고 표시를 넣지 않게 되어(#628) adLabel은 요구하지
 * 않는다. 광고 표시가 들어 있는 옛 템플릿도 막지 않으려고 있어도 검사하지 않는다.
 */
@Component
public class TemplateSlotValidator {

    private static final Set<String> TEXT_TAGS = Set.of("div", "span", "p", "h1", "h2", "h3", "strong", "em");

    /*
     * 패키지 밖에는 안 열지만, 같은 service 패키지의 TemplateStructureValidator 가
     * 슬롯 이름 목록을 그대로 재사용하도록 package-private 으로 둔다.
     */
    static final Map<String, Set<String>> SLOT_TAGS = Map.of(
            "eventName", TEXT_TAGS,
            "discount", TEXT_TAGS,
            "period", TEXT_TAGS,
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
