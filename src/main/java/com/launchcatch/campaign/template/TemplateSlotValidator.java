package com.launchcatch.campaign.template;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/*
 * 슬롯 4개(eventName, discount, period, image)가 각각 정확히 하나씩 있고 허용된 태그 안에 있는지
 * 확인한다. 점주가 포스터에서 텍스트와 글자 크기를 고치는 eventName 과 discount 는 자식 요소 없이 글자만 갖는지도
 * 본다(#884). 어긋나면 POSTER-001 이다. 포스터에 광고 표시를 넣지 않게 되어(#628) adLabel은 요구하지
 * 않는다. 광고 표시가 들어 있는 옛 템플릿도 막지 않으려고 있어도 검사하지 않는다.
 */
@Component
public class TemplateSlotValidator {

    private static final Set<String> TEXT_TAGS = Set.of("div", "span", "p", "h1", "h2", "h3", "strong", "em");

    public static final String EVENT_NAME_SLOT = "eventName";
    public static final String DISCOUNT_SLOT = "discount";
    public static final String PERIOD_SLOT = "period";
    public static final String IMAGE_SLOT = "image";

    /*
     * 광고 표시 슬롯 이름이다. 포스터에는 광고 표시를 넣지 않기로 해서(#628) 요구하거나 검사하지 않지만, 옛 템플릿에 든
     * 이 슬롯을 지우는 템플릿 생성기(BedrockTemplateHtmlGenerator)와 포스터 생성(PosterUploadService)이 같은 이름을
     * 가리키도록 여기에 한 번만 정의한다(#658).
     */
    public static final String AD_LABEL_SLOT = "adLabel";

    /*
     * 패키지 밖에는 안 열지만, 같은 service 패키지의 TemplateStructureValidator 가
     * 슬롯 이름 목록을 그대로 재사용하도록 package-private 으로 둔다.
     */
    static final Map<String, Set<String>> SLOT_TAGS = Map.of(
            EVENT_NAME_SLOT, TEXT_TAGS,
            DISCOUNT_SLOT, TEXT_TAGS,
            PERIOD_SLOT, TEXT_TAGS,
            IMAGE_SLOT, Set.of("img"));

    /*
     * 점주가 텍스트와 글자 크기를 고치는 슬롯이다. 글자 크기를 슬롯 요소의 인라인 font-size 로 바꾸므로, 안에 자식
     * 요소가 있고 그 자식이 자기 크기를 가지면 값이 먹지 않고 텍스트를 바꿀 때 안쪽 꾸밈도 사라진다. period 는 수정하지
     * 않아서 걸지 않는다.
     */
    private static final Set<String> EDITABLE_TEXT_SLOTS = Set.of(EVENT_NAME_SLOT, DISCOUNT_SLOT);

    public void validate(String html) {
        validate(Jsoup.parseBodyFragment(html));
    }

    public void validate(Document document) {
        SLOT_TAGS.forEach((slot, allowedTags) -> {
            Elements found = document.select("[data-slot=" + slot + "]");
            if (found.size() != 1 || !allowedTags.contains(found.first().normalName())
                    || (EDITABLE_TEXT_SLOTS.contains(slot) && !isTextOnly(found.first()))) {
                throw new CampaignException(PosterErrorCode.SLOT_CONTRACT_VIOLATION);
            }
        });
    }

    // 슬롯 요소가 자식 요소 없이 글자만 갖는지 본다. 포스터 수정도 같은 기준으로 슬롯을 거른다.
    public static boolean isTextOnly(Element slot) {
        return slot.children().isEmpty();
    }
}
