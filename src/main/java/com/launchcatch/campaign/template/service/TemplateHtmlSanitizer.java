package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/*
 * LLM 이 만든 HTML 에서 허용된 태그와 속성만 남긴다.
 * 스크립트, 이벤트 속성, 외부 리소스 로딩은 제거하고, 제거한 내용은 결과의 removedElements 로 돌려준다.
 * 제거만 하고 거부하지는 않는다.
 */
@Component
public class TemplateHtmlSanitizer {

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "div", "span", "p", "h1", "h2", "h3", "strong", "em", "br", "img");

    /*
     * 태그 안의 내용까지 버리는 태그다.
     * 나머지 허용되지 않은 태그는 태그만 벗기고 안의 내용은 남긴다.
     */
    private static final Set<String> DROPPED_WITH_CONTENT = Set.of(
            "script", "style", "iframe", "object", "embed", "link", "meta", "base");

    private static final Set<String> COMMON_ATTRIBUTES = Set.of("class", "style", "data-slot");

    private static final Map<String, Set<String>> TAG_ATTRIBUTES = Map.of("img", Set.of("alt"));

    private static final Pattern EXTERNAL_RESOURCE_IN_STYLE = Pattern.compile(
            "url\\s*\\(|@import|expression\\s*\\(|javascript:", Pattern.CASE_INSENSITIVE);

    public TemplateSanitizeResult sanitize(String html) {
        return sanitize(Jsoup.parseBodyFragment(html));
    }

    /*
     * 같은 요청 안에서 슬롯 검증도 이 결과를 이어서 쓸 수 있도록, 이미 파싱된
     * Document 를 받는 자리를 따로 둔다. 파싱을 여러 번 하지 않으려는 것이다.
     */
    public TemplateSanitizeResult sanitize(Document document) {
        document.outputSettings().prettyPrint(false);
        Set<String> removed = new LinkedHashSet<>();
        clean(document.body(), removed);
        return new TemplateSanitizeResult(document.body().html(), List.copyOf(removed));
    }

    /*
     * 원래는 clean(child) 를 재귀로 부른 뒤 필요하면 unwrap() 하는 모양이었다. HTML 이
     * 깊게 중첩되면 재귀 깊이가 그만큼 쌓여 StackOverflowError 가 날 수 있어, 콜스택 대신
     * 힙에 쌓는 명시적 스택으로 바꾼다. 각 프레임(Frame)은 "이 엘리먼트의 자식 중 아직
     * 안 본 것들"의 반복자와, 그 자식들을 다 보고 나서(원래 코드의 재귀 호출이 끝난 뒤와
     * 같은 시점에) unwrap 할 대상을 함께 들고 있다. removed 에 담기는 순서와 최종 HTML 은
     * 재귀 버전과 같다. ArrayDeque 는 null 원소를 받지 않아 unwrap 대상이 없는 프레임도
     * Frame.unwrapTarget 을 null 로 두는 식으로 감싼다.
     */
    private void clean(Element root, Set<String> removed) {
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(List.copyOf(root.children()).iterator(), null));
        while (!frames.isEmpty()) {
            Frame frame = frames.peek();
            if (!frame.children().hasNext()) {
                frames.pop();
                if (frame.unwrapTarget() != null) {
                    frame.unwrapTarget().unwrap();
                }
                continue;
            }
            Element child = frame.children().next();
            String tag = child.normalName();
            if (DROPPED_WITH_CONTENT.contains(tag)) {
                removed.add(tag);
                child.remove();
            } else if (ALLOWED_TAGS.contains(tag)) {
                cleanAttributes(child, removed);
                frames.push(new Frame(List.copyOf(child.children()).iterator(), null));
            } else {
                removed.add(tag);
                frames.push(new Frame(List.copyOf(child.children()).iterator(), child));
            }
        }
    }

    private record Frame(Iterator<Element> children, Element unwrapTarget) {
    }

    private void cleanAttributes(Element element, Set<String> removed) {
        String tag = element.normalName();
        for (Attribute attribute : List.copyOf(element.attributes().asList())) {
            if (!isAllowed(tag, attribute)) {
                element.removeAttr(attribute.getKey());
                removed.add(tag + "[" + attribute.getKey() + "]");
            }
        }
    }

    private boolean isAllowed(String tag, Attribute attribute) {
        String key = attribute.getKey();
        boolean listed = COMMON_ATTRIBUTES.contains(key)
                || TAG_ATTRIBUTES.getOrDefault(tag, Set.of()).contains(key);
        return listed && !isUnsafeStyle(key, attribute.getValue());
    }

    /*
     * url(), @import, expression(), javascript: 를 그대로 찾는 블랙리스트다.
     * CSS 는 역슬래시 이스케이프(예: \75rl() 는 url() 로 해석됨)로 이 글자들을 그대로
     * 안 쓰면서도 브라우저가 똑같이 해석하게 만들 수 있어서, 역슬래시가 하나라도 있으면
     * 그 자체로 안전하지 않다고 본다. 색상값에는 역슬래시가 쓰일 일이 없다.
     */
    private boolean isUnsafeStyle(String key, String value) {
        return "style".equals(key)
                && (value.indexOf('\\') >= 0 || EXTERNAL_RESOURCE_IN_STYLE.matcher(value).find());
    }
}