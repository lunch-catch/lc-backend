package com.launchcatch.member.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.member.entity.AgeGroup;
import com.launchcatch.member.entity.Gender;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * 요청 DTO 의 Bean Validation 을 고정한다. 컨트롤러까지 올리지 않고 검증기만 돌린다.
 * 위반이 났을 때 GlobalExceptionHandler 가 400 COMMON-002 로 바꾸는 것은 거기서 따로 본다.
 */
class MemberRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private Set<String> violatedFields(Object target) {
        return VALIDATOR.validate(target).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    // ---- 온보딩

    @Test
    @DisplayName("온보딩 요청은 모든 값이 있으면 통과한다")
    void 온보딩_요청은_모든_값이_있으면_통과한다() {
        var request = new MemberOnboardingRequest(Gender.FEMALE, AgeGroup.AGE_20S, true, false);

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    @DisplayName("온보딩 요청은 성별이 없으면 거절한다")
    void 온보딩_요청은_성별이_없으면_거절한다() {
        var request = new MemberOnboardingRequest(null, AgeGroup.AGE_20S, true, false);

        assertThat(violatedFields(request)).containsExactly("gender");
    }

    @Test
    @DisplayName("온보딩 요청은 연령대가 없으면 거절한다")
    void 온보딩_요청은_연령대가_없으면_거절한다() {
        var request = new MemberOnboardingRequest(Gender.MALE, null, true, false);

        assertThat(violatedFields(request)).containsExactly("ageGroup");
    }

    @Test
    @DisplayName("온보딩 요청은 두 동의 값이 하나라도 없으면 거절한다")
    void 온보딩_요청은_두_동의_값이_하나라도_없으면_거절한다() {
        var request = new MemberOnboardingRequest(Gender.MALE, AgeGroup.AGE_30S, null, null);

        assertThat(violatedFields(request)).containsExactlyInAnyOrder("locationOptIn", "notificationOptIn");
    }

    // ---- 내 정보 수정

    @Test
    @DisplayName("닉네임은 1자부터 20자까지 통과한다")
    void 닉네임은_1자부터_20자까지_통과한다() {
        assertThat(violatedFields(new MemberUpdateRequest("가", null, null))).isEmpty();
        assertThat(violatedFields(new MemberUpdateRequest("가".repeat(20), null, null))).isEmpty();
    }

    @Test
    @DisplayName("닉네임이 0자이거나 21자이면 거절한다")
    void 닉네임이_0자이거나_21자이면_거절한다() {
        assertThat(violatedFields(new MemberUpdateRequest("", null, null))).contains("nickname");
        assertThat(violatedFields(new MemberUpdateRequest("가".repeat(21), null, null))).containsExactly("nickname");
    }

    @Test
    @DisplayName("공백뿐인 닉네임은 거절한다")
    void 공백뿐인_닉네임은_거절한다() {
        assertThat(violatedFields(new MemberUpdateRequest("   ", null, null))).containsExactly("nickname");
    }

    @Test
    @DisplayName("보낸 필드가 하나도 없으면 수정할 것이 없다고 본다")
    void 보낸_필드가_하나도_없으면_수정할_것이_없다고_본다() {
        assertThat(new MemberUpdateRequest(null, null, null).hasUpdate()).isFalse();
    }

    @Test
    @DisplayName("동의 값 하나만 보내도 수정으로 본다")
    void 동의_값_하나만_보내도_수정으로_본다() {
        assertThat(new MemberUpdateRequest(null, false, null).hasUpdate()).isTrue();
    }

    // ---- 위치

    @ParameterizedTest
    @ValueSource(strings = {"90", "-90", "0", "37.5665123"})
    @DisplayName("위도는 -90 이상 90 이하만 통과한다")
    void 위도는_90_이내만_통과한다(String latitude) {
        var request = new MemberLocationRequest(null, null, new BigDecimal(latitude), BigDecimal.ZERO);

        assertThat(violatedFields(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"90.0000001", "-90.0000001", "91"})
    @DisplayName("위도가 범위를 벗어나면 거절한다")
    void 위도가_범위를_벗어나면_거절한다(String latitude) {
        var request = new MemberLocationRequest(null, null, new BigDecimal(latitude), BigDecimal.ZERO);

        assertThat(violatedFields(request)).containsExactly("latitude");
    }

    @ParameterizedTest
    @ValueSource(strings = {"180", "-180", "0", "126.9779692"})
    @DisplayName("경도는 -180 이상 180 이하만 통과한다")
    void 경도는_180_이내만_통과한다(String longitude) {
        var request = new MemberLocationRequest(null, null, BigDecimal.ZERO, new BigDecimal(longitude));

        assertThat(violatedFields(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"180.0000001", "-180.0000001", "181"})
    @DisplayName("경도가 범위를 벗어나면 거절한다")
    void 경도가_범위를_벗어나면_거절한다(String longitude) {
        var request = new MemberLocationRequest(null, null, BigDecimal.ZERO, new BigDecimal(longitude));

        assertThat(violatedFields(request)).containsExactly("longitude");
    }

    @Test
    @DisplayName("GPS 모드는 좌표만 보내도 통과한다")
    void GPS_모드는_좌표만_보내도_통과한다() {
        var request = new MemberLocationRequest(null, null, new BigDecimal("37.5"), new BigDecimal("127.0"));

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    @DisplayName("주소 검색 모드는 별칭과 주소와 좌표를 함께 보내면 통과한다")
    void 주소_검색_모드는_별칭과_주소와_좌표를_함께_보내면_통과한다() {
        var request = new MemberLocationRequest("집", "서울특별시 중구 세종대로 110",
                new BigDecimal("37.5"), new BigDecimal("127.0"));

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    @DisplayName("위도와 경도는 함께 보내야 한다")
    void 위도와_경도는_함께_보내야_한다() {
        var onlyLatitude = new MemberLocationRequest("집", null, new BigDecimal("37.5"), null);
        var onlyLongitude = new MemberLocationRequest("집", null, null, new BigDecimal("127.0"));

        assertThat(violatedFields(onlyLatitude)).containsExactly("coordinatePair");
        assertThat(violatedFields(onlyLongitude)).containsExactly("coordinatePair");
    }

    @Test
    @DisplayName("좌표가 없으면 주소만으로는 저장하지 않는다")
    void 좌표가_없으면_주소만으로는_저장하지_않는다() {
        var request = new MemberLocationRequest("집", "서울특별시 중구 세종대로 110", null, null);

        assertThat(violatedFields(request)).containsExactly("coordinatePair");
    }

    @Test
    @DisplayName("별칭은 50자, 주소는 255자까지만 허용한다")
    void 별칭은_50자_주소는_255자까지만_허용한다() {
        var tooLong = new MemberLocationRequest("가".repeat(51), "가".repeat(256), BigDecimal.ONE, BigDecimal.ONE);
        var atLimit = new MemberLocationRequest("가".repeat(50), "가".repeat(255), BigDecimal.ONE, BigDecimal.ONE);

        assertThat(violatedFields(tooLong)).containsExactlyInAnyOrder("locationNickname", "roadAddress");
        assertThat(violatedFields(atLimit)).isEmpty();
    }

    // ---- 탈퇴

    @Test
    @DisplayName("탈퇴 요청은 인가 코드와 state 가 비어 있으면 거절한다")
    void 탈퇴_요청은_인가_코드와_state가_비어_있으면_거절한다() {
        var request = new MemberWithdrawalRequest(" ", "", null);

        assertThat(violatedFields(request)).containsExactlyInAnyOrder("authorizationCode", "state");
    }

    @Test
    @DisplayName("탈퇴 사유는 선택이고 255자까지만 허용한다")
    void 탈퇴_사유는_선택이고_255자까지만_허용한다() {
        assertThat(violatedFields(new MemberWithdrawalRequest("code", "state", null))).isEmpty();
        assertThat(violatedFields(new MemberWithdrawalRequest("code", "state", "가".repeat(255)))).isEmpty();
        assertThat(violatedFields(new MemberWithdrawalRequest("code", "state", "가".repeat(256))))
                .containsExactly("reason");
    }
}
