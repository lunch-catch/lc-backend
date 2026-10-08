package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.util.Arrays;
import java.util.Locale;
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
        String value = declaration.substring(colon + 1);
        return COLOR_PROPERTIES.contains(property) && !PALETTE.contains(normalize(value));
    }

    // 소문자와 세 자리 표기(#abc)를 팔레트 표기(#AABBCC)로 맞춘다
    private String normalize(String color) {
        return color.strip().toUpperCase(Locale.ROOT)
                .replaceAll("^#([0-9A-F])([0-9A-F])([0-9A-F])$", "#$1$1$2$2$3$3");
    }
}
