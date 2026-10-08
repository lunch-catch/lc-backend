package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/*
 * 인라인 style 의 색상이 플랫폼 팔레트 안의 값인지 확인한다.
 * 어긋나면 POSTER-002 이다.
 */
@Component
public class TemplatePaletteValidator {

    // 플랫폼 팔레트다. 값이 확정되면 이 목록을 바꾼다
    private static final Set<String> PALETTE = Set.of("#FFFFFF", "#000000", "#FF6B35");

    private static final Set<String> COLOR_PROPERTIES = Set.of(
            "color", "background-color", "border-color", "outline-color");

    // 색상이 다른 값과 같이 오는 단축 속성이다. 값 전체를 팔레트와 비교할 수 없어 토큰 단위로 본다
    private static final Set<String> SHORTHAND_COLOR_PROPERTIES = Set.of(
            "border", "background", "outline");

    // CSS 기본 16색 이름이다. 팔레트에 있는 16진값으로 바꿔서 비교한다
    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", "#000000"),
            Map.entry("white", "#FFFFFF"),
            Map.entry("red", "#FF0000"),
            Map.entry("lime", "#00FF00"),
            Map.entry("blue", "#0000FF"),
            Map.entry("yellow", "#FFFF00"),
            Map.entry("aqua", "#00FFFF"),
            Map.entry("fuchsia", "#FF00FF"),
            Map.entry("silver", "#C0C0C0"),
            Map.entry("gray", "#808080"),
            Map.entry("maroon", "#800000"),
            Map.entry("olive", "#808000"),
            Map.entry("green", "#008000"),
            Map.entry("purple", "#800080"),
            Map.entry("teal", "#008080"),
            Map.entry("navy", "#000080"),
            Map.entry("orange", "#FFA500"));

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}");

    private static final Pattern COLOR_FUNCTION = Pattern.compile(
            "\\b(?:rgb|rgba|hsl|hsla)\\s*\\(", Pattern.CASE_INSENSITIVE);

    public void validate(String html) {
        validate(Jsoup.parseBodyFragment(html));
    }

    public void validate(Document document) {
        for (Element element : document.select("[style]")) {
            if (violatesPalette(element.attr("style"))) {
                throw new CampaignException(PosterErrorCode.COLOR_NOT_ALLOWED);
            }
        }
    }

    private boolean violatesPalette(String style) {
        return COLOR_FUNCTION.matcher(style).find()
                || HEX_COLOR.matcher(style).results()
                .anyMatch(found -> !PALETTE.contains(normalize(found.group())))
                || Arrays.stream(style.split(";")).anyMatch(this::hasColorOutsidePalette);
    }

    private boolean hasColorOutsidePalette(String declaration) {
        int colon = declaration.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
        String value = declaration.substring(colon + 1).strip();
        if (COLOR_PROPERTIES.contains(property)) {
            return !PALETTE.contains(normalize(value));
        }
        if (SHORTHAND_COLOR_PROPERTIES.contains(property)) {
            return Arrays.stream(value.split("\\s+")).anyMatch(this::isNamedColorOutsidePalette);
        }
        return false;
    }

    private boolean isNamedColorOutsidePalette(String token) {
        String hex = NAMED_COLORS.get(token.toLowerCase(Locale.ROOT));
        return hex != null && !PALETTE.contains(hex);
    }

    // 소문자와 세 자리 표기(#abc)를 팔레트 표기(#AABBCC)로 맞춘다
    private String normalize(String color) {
        return color.strip().toUpperCase(Locale.ROOT)
                .replaceAll("^#([0-9A-F])([0-9A-F])([0-9A-F])$", "#$1$1$2$2$3$3");
    }
}
