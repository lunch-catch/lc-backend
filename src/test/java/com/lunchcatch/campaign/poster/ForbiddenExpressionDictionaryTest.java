package com.lunchcatch.campaign.poster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForbiddenExpressionDictionaryTest {

    private final ForbiddenExpressionDictionary dictionary = new ForbiddenExpressionDictionary();

    @Test
    @DisplayName("명세서 기본값 금지 표현을 찾는다")
    void 명세서_기본값을_찾는다() {
        assertThat(dictionary.findIn("오늘만 최저가 도전")).isPresent();
        assertThat(dictionary.findIn("100% 환불 보장")).isPresent();
        assertThat(dictionary.findIn("무조건 할인")).isPresent();
    }

    @Test
    @DisplayName("띄어쓰기를 넣거나 대소문자를 바꿔도 찾는다")
    void 띄어쓰기와_대소문자를_무시한다() {
        assertThat(dictionary.findIn("최 저 가")).isPresent();
        assertThat(dictionary.findIn("100 % 환 불")).isPresent();
    }

    @Test
    @DisplayName("메뉴 이름에 흔한 표현은 걸리지 않는다")
    void 흔한_표현은_통과한다() {
        assertThat(dictionary.findIn("프리미엄 한우 정식 최고의 맛 BEST 메뉴")).isEmpty();
    }

    @Test
    @DisplayName("여러 글자 중 하나라도 걸리면 참이다")
    void 여러_글자_중_하나라도_걸리면_참이다() {
        assertThat(dictionary.containsAny(List.of("김밥천국", "당뇨 예방 효과"))).isTrue();
        assertThat(dictionary.containsAny(List.of("김밥천국", "10% 할인"))).isFalse();
    }

    @Test
    @DisplayName("파일이 없으면 만들 수 없다")
    void 파일이_없으면_실패한다() {
        assertThatThrownBy(() -> new ForbiddenExpressionDictionary("campaign/moderation/missing.txt"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("주석과 빈 줄만 있는 파일이면 만들 수 없다")
    void 빈_파일이면_실패한다() {
        assertThatThrownBy(() -> new ForbiddenExpressionDictionary("campaign/moderation/empty-forbidden-expressions.txt"))
                .isInstanceOf(IllegalStateException.class);
    }
}
