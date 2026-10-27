package com.lunchcatch.campaign.poster;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.campaign.poster.dto.PosterSlotsResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PosterSlotsTest {

    @Test
    @DisplayName("쓴 JSON 을 다시 읽으면 같은 값이 나온다")
    void 쓰고_읽으면_같다() {
        Map<String, PosterSlotValue> slots = new LinkedHashMap<>();
        slots.put("eventName", new PosterSlotValue("text", "분식집", "분식집", null, 30));
        slots.put("discount", new PosterSlotValue("text", "1000원 할인 오늘만", "1000원 할인", "오늘만", null));

        Map<String, PosterSlotValue> read = PosterSlots.read(PosterSlots.write(slots));

        assertThat(read).isEqualTo(slots);
    }

    @Test
    @DisplayName("이 기능 이전 모양({type, value})도 읽는다")
    void 옛_모양을_읽는다() {
        Map<String, PosterSlotValue> read = PosterSlots.read("{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\"}}");

        assertThat(read.get("eventName")).isEqualTo(PosterSlotValue.of("text", "분식집"));
    }

    @Test
    @DisplayName("읽을 수 없는 JSON 이면 IllegalStateException 이다")
    void 깨진_JSON은_실패한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PosterSlots.read("{"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("가게명이 자동 문구와 같으면 수정이 아니고, 다르면 수정으로 본다")
    void 가게명_수정_여부를_가려낸다() {
        String untouched = "{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\",\"baseText\":\"분식집\"}}";
        String edited = "{\"eventName\":{\"type\":\"text\",\"value\":\"새 이름\",\"baseText\":\"분식집\",\"fontSizePx\":40}}";
        String legacy = "{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\"}}";

        assertThat(PosterSlots.editsOf(untouched).eventNameText()).isNull();
        assertThat(PosterSlots.editsOf(edited).eventNameText()).isEqualTo("새 이름");
        assertThat(PosterSlots.editsOf(edited).eventNameFontSizePx()).isEqualTo(40);
        assertThat(PosterSlots.editsOf(legacy).eventNameText()).isNull();
    }

    @Test
    @DisplayName("할인 추가 텍스트와 글자 크기를 유지할 값으로 돌려주고, 슬롯이 없으면 모두 비어 있다")
    void 할인_수정을_돌려준다() {
        String json = "{\"discount\":{\"type\":\"text\",\"value\":\"a b\",\"baseText\":\"a\",\"suffix\":\"b\",\"fontSizePx\":20}}";

        PosterSlots.Edits edits = PosterSlots.editsOf(json);

        assertThat(edits.discountSuffix()).isEqualTo("b");
        assertThat(edits.discountFontSizePx()).isEqualTo(20);
        assertThat(PosterSlots.editsOf("{}")).isEqualTo(PosterSlots.Edits.NONE);
    }

    @Test
    @DisplayName("추가 텍스트가 비면 기본 문구만, 있으면 공백 하나로 잇는다")
    void 할인_문구를_잇는다() {
        assertThat(PosterSlots.joinDiscount("1000원 할인", null)).isEqualTo("1000원 할인");
        assertThat(PosterSlots.joinDiscount("1000원 할인", "")).isEqualTo("1000원 할인");
        assertThat(PosterSlots.joinDiscount("1000원 할인", "오늘만")).isEqualTo("1000원 할인 오늘만");
    }

    @Test
    @DisplayName("font-size 만 바꾸고 다른 속성은 두며 중복되지 않는다")
    void 글자_크기를_인라인_스타일에_넣는다() {
        assertThat(PosterSlots.withFontSize("", 24)).isEqualTo("font-size:24px");
        assertThat(PosterSlots.withFontSize("color:red;font-size:10px", 24)).isEqualTo("color:red;font-size:24px");
        assertThat(PosterSlots.withFontSize("FONT-SIZE: 1rem; color:red;bad", 24))
                .isEqualTo("font-size:24px;color:red");
    }

    @Test
    @DisplayName("px 로 적힌 font-size 만 정수로 읽는다")
    void 글자_크기를_읽는다() {
        assertThat(PosterSlots.fontSizePxOf("color:red;font-size: 24px")).isEqualTo(24);
        assertThat(PosterSlots.fontSizePxOf("font-size:24.6px")).isEqualTo(25);
        assertThat(PosterSlots.fontSizePxOf("font-size:2rem")).isNull();
        assertThat(PosterSlots.fontSizePxOf("font-size:abcpx")).isNull();
        assertThat(PosterSlots.fontSizePxOf("color:red;nonsense")).isNull();
        assertThat(PosterSlots.fontSizePxOf("")).isNull();
    }

    @Test
    @DisplayName("점주용 응답은 slot_values 의 글자 크기를 우선하고 없으면 HTML 인라인 스타일을 쓴다")
    void 응답_보기를_만든다() {
        String json = "{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\",\"baseText\":\"분식집\",\"fontSizePx\":40},"
                + "\"discount\":{\"type\":\"text\",\"value\":\"1000원 할인 오늘만\",\"baseText\":\"1000원 할인\",\"suffix\":\"오늘만\"},"
                + "\"period\":{\"type\":\"text\",\"value\":\"10.1~10.31\"}}";
        String html = "<h1 data-slot=\"eventName\" style=\"font-size:20px\">분식집</h1>"
                + "<p data-slot=\"discount\" style=\"font-size:18px\">1000원 할인 오늘만</p>";

        PosterSlotsResponse view = PosterSlots.view(json, html);

        assertThat(view.eventName().text()).isEqualTo("분식집");
        assertThat(view.eventName().fontSizePx()).isEqualTo(40);
        assertThat(view.discount().baseText()).isEqualTo("1000원 할인");
        assertThat(view.discount().suffix()).isEqualTo("오늘만");
        assertThat(view.discount().fontSizePx()).isEqualTo(18);
        assertThat(view.period().text()).isEqualTo("10.1~10.31");
    }

    @Test
    @DisplayName("옛 포스터는 할인 기본 문구가 value 이고, 슬롯이 없으면 빈 문자열과 null 이다")
    void 옛_포스터와_빈_슬롯을_보여준다() {
        PosterSlotsResponse legacy = PosterSlots.view(
                "{\"discount\":{\"type\":\"text\",\"value\":\"1000원 할인\"}}", "<p data-slot=\"discount\">1000원 할인</p>");
        PosterSlotsResponse empty = PosterSlots.view("{}", "<div></div>");

        assertThat(legacy.discount().baseText()).isEqualTo("1000원 할인");
        assertThat(legacy.discount().suffix()).isEmpty();
        assertThat(legacy.discount().fontSizePx()).isNull();
        assertThat(empty.eventName().text()).isEmpty();
        assertThat(empty.eventName().fontSizePx()).isNull();
        assertThat(empty.discount().baseText()).isEmpty();
        assertThat(empty.period().text()).isEmpty();
    }
}
