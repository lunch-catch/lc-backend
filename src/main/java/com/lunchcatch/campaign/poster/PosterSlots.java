package com.lunchcatch.campaign.poster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lunchcatch.campaign.poster.dto.PosterSlotsResponse;
import com.lunchcatch.campaign.template.TemplateSlotValidator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/*
 * 포스터의 slot_values JSON 과 슬롯 요소의 인라인 스타일을 다루는 함수 모음이다(#884). 포스터를 만드는 서비스,
 * 수정하는 서비스, 점주용 응답을 만드는 곳이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * JSON 은 트리로 직접 읽고 쓴다. 이 기능 이전에 만든 포스터에는 type 과 value 만 있어서, 없는 필드를 오류로 보지
 * 않고 null 로 읽는다.
 */
public final class PosterSlots {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FONT_SIZE = "font-size";

    /*
     * 같은 템플릿으로 포스터를 다시 만들 때 유지할 수정이다. 가게명 텍스트는 자동 문구(baseText)와 달라졌을 때만
     * 수정으로 본다. 그렇지 않으면 수정하지 않은 포스터가 가게 이름이 바뀐 뒤에도 옛 이름을 붙들게 된다.
     */
    public record Edits(String eventNameText, Integer eventNameFontSizePx, String discountSuffix,
                        Integer discountFontSizePx) {

        public static final Edits NONE = new Edits(null, null, null, null);
    }

    private PosterSlots() {
    }

    public static Map<String, PosterSlotValue> read(String slotValuesJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(slotValuesJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("poster slot values를 읽지 못했습니다.", e);
        }
        Map<String, PosterSlotValue> slots = new LinkedHashMap<>();
        root.fieldNames().forEachRemaining(name -> slots.put(name, toValue(root.get(name))));
        return slots;
    }

    public static String write(Map<String, PosterSlotValue> slots) {
        ObjectNode root = MAPPER.createObjectNode();
        slots.forEach((name, slot) -> {
            ObjectNode node = root.putObject(name);
            node.put("type", slot.type());
            node.put("value", slot.value());
            if (slot.baseText() != null) {
                node.put("baseText", slot.baseText());
            }
            if (slot.suffix() != null) {
                node.put("suffix", slot.suffix());
            }
            if (slot.fontSizePx() != null) {
                node.put("fontSizePx", slot.fontSizePx());
            }
        });
        return root.toString();
    }

    public static Edits editsOf(String slotValuesJson) {
        Map<String, PosterSlotValue> slots = read(slotValuesJson);
        PosterSlotValue eventName = slots.get(TemplateSlotValidator.EVENT_NAME_SLOT);
        PosterSlotValue discount = slots.get(TemplateSlotValidator.DISCOUNT_SLOT);
        String eventNameText = null;
        Integer eventNameFontSizePx = null;
        if (eventName != null) {
            boolean edited = eventName.baseText() != null && !eventName.baseText().equals(eventName.value());
            eventNameText = edited ? eventName.value() : null;
            eventNameFontSizePx = eventName.fontSizePx();
        }
        String suffix = discount == null ? null : discount.suffix();
        Integer discountFontSizePx = discount == null ? null : discount.fontSizePx();
        return new Edits(eventNameText, eventNameFontSizePx, suffix, discountFontSizePx);
    }

    // 할인 기본 문구와 추가 텍스트를 공백 하나로 잇는다. 추가 텍스트가 비어 있으면 기본 문구만 남긴다.
    public static String joinDiscount(String baseText, String suffix) {
        return suffix == null || suffix.isEmpty() ? baseText : baseText + " " + suffix;
    }

    /*
     * 슬롯 요소의 인라인 스타일에서 font-size 만 px 값으로 정한다. 다른 속성은 그대로 두고 font-size 가 이미 있으면
     * 그 자리에서 바꾼다(속성 이름은 대소문자를 구분하지 않는다). 같은 속성이 두 번 들어가지 않는다.
     */
    public static String withFontSize(String existingStyle, int fontSizePx) {
        Map<String, String> declarations = new LinkedHashMap<>();
        for (String declaration : existingStyle.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            declarations.put(declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT), declaration.trim());
        }
        declarations.put(FONT_SIZE, FONT_SIZE + ":" + fontSizePx + "px");
        return String.join(";", declarations.values());
    }

    // font-size 가 px 로 적혀 있으면 그 값을 정수로 돌려주고, 없거나 px 가 아니면 null 이다.
    public static Integer fontSizePxOf(String style) {
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon <= 0 || !FONT_SIZE.equals(declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = declaration.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            if (!value.endsWith("px")) {
                return null;
            }
            try {
                return (int) Math.round(Double.parseDouble(value.substring(0, value.length() - 2).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /*
     * 점주용 응답의 슬롯별 현재 값이다. 글자 크기는 점주가 정한 값이 slot_values 에 있으면 그 값을, 없으면 저장된 HTML
     * 슬롯 요소의 인라인 font-size 를 쓴다. 이 기능 이전에 만든 포스터나 점주가 크기를 건드리지 않은 포스터는 템플릿이
     * 정한 크기가 그대로 보이고, px 가 아니면 null 이다.
     */
    public static PosterSlotsResponse view(String slotValuesJson, String html) {
        Map<String, PosterSlotValue> slots = read(slotValuesJson);
        Document document = Jsoup.parseBodyFragment(html);
        PosterSlotValue eventName = slots.get(TemplateSlotValidator.EVENT_NAME_SLOT);
        PosterSlotValue discount = slots.get(TemplateSlotValidator.DISCOUNT_SLOT);
        PosterSlotValue period = slots.get(TemplateSlotValidator.PERIOD_SLOT);
        return new PosterSlotsResponse(
                new PosterSlotsResponse.EventName(
                        textOf(eventName), fontSizeOf(eventName, document, TemplateSlotValidator.EVENT_NAME_SLOT)),
                new PosterSlotsResponse.Discount(
                        baseTextOf(discount),
                        discount == null || discount.suffix() == null ? "" : discount.suffix(),
                        fontSizeOf(discount, document, TemplateSlotValidator.DISCOUNT_SLOT)),
                new PosterSlotsResponse.Period(textOf(period)));
    }

    private static String textOf(PosterSlotValue slot) {
        return slot == null || slot.value() == null ? "" : slot.value();
    }

    // 할인 기본 문구는 수정 전 자동 문구(baseText)이고, 이 기능 이전 포스터에는 없어서 그때는 value 가 곧 기본 문구다.
    private static String baseTextOf(PosterSlotValue discount) {
        if (discount == null) {
            return "";
        }
        return discount.baseText() != null ? discount.baseText() : textOf(discount);
    }

    private static Integer fontSizeOf(PosterSlotValue slot, Document document, String slotName) {
        if (slot != null && slot.fontSizePx() != null) {
            return slot.fontSizePx();
        }
        Element element = document.selectFirst("[data-slot=" + slotName + "]");
        return element == null ? null : fontSizePxOf(element.attr("style"));
    }

    private static PosterSlotValue toValue(JsonNode node) {
        return new PosterSlotValue(
                textField(node, "type"), textField(node, "value"), textField(node, "baseText"),
                textField(node, "suffix"),
                node.hasNonNull("fontSizePx") ? Integer.valueOf(node.get("fontSizePx").asInt()) : null);
    }

    private static String textField(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
