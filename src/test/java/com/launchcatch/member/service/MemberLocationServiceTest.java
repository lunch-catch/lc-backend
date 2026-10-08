package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.member.dto.MemberLocationRequest;
import com.launchcatch.member.dto.MemberLocationResponse;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberLocationServiceTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.5665123");
    private static final BigDecimal LONGITUDE = new BigDecimal("126.9779692");

    @Mock MemberRepository memberRepository;
    @Mock MemberProfileRepository memberProfileRepository;

    private MemberLocationService service;

    @BeforeEach
    void setUp() {
        service = new MemberLocationService(memberRepository, memberProfileRepository);
    }

    @Test
    @DisplayName("저장은 기존 프로필의 위치를 덮어쓴다")
    void 저장은_기존_프로필의_위치를_덮어쓴다() throws Exception {
        MemberProfile profile = MemberProfile.create(member());
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.of(profile));

        MemberLocationResponse response = service.save(1L, request());

        assertThat(response.locationNickname()).isEqualTo("집");
        assertThat(response.roadAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        assertThat(profile.getLocationNickname()).isEqualTo("집");
        verify(memberProfileRepository, never()).save(any(MemberProfile.class));
    }

    /*
     * 온보딩 전에도 위치를 저장할 수 있어야 한다.
     * member_profile 행이 없으면 이때 만든다.
     */
    @Test
    @DisplayName("프로필이 없으면 만들어서 위치를 저장한다")
    void 프로필이_없으면_만들어서_위치를_저장한다() throws Exception {
        Member member = member();
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.empty());
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberProfileRepository.save(any(MemberProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MemberLocationResponse response = service.save(1L, request());

        assertThat(response.locationNickname()).isEqualTo("집");
        assertThat(member.getProfile()).isNotNull();
        assertThat(member.getProfile().getLatitude()).isEqualTo(LATITUDE);
    }

    // 저장 위치는 반올림하지 않고 DECIMAL(10,7) 에 받은 값 그대로 남긴다.
    @Test
    @DisplayName("좌표는 반올림 없이 그대로 저장된다")
    void 좌표는_반올림_없이_그대로_저장된다() throws Exception {
        MemberProfile profile = MemberProfile.create(member());
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.of(profile));

        MemberLocationResponse response = service.save(1L, request());

        assertThat(response.latitude()).isEqualByComparingTo(LATITUDE);
        assertThat(response.longitude()).isEqualByComparingTo(LONGITUDE);
        assertThat(response.latitude().scale()).isEqualTo(7);
        assertThat(response.longitude().scale()).isEqualTo(7);
    }

    /*
     * 인증을 통과한 토큰의 회원이 DB 에 없는 상태다.
     * 사용자 입력 오류가 아니라 서버 쪽 전제가 깨진 것이라 IllegalStateException 으로 둔다.
     */
    @Test
    @DisplayName("인증된 회원이 없으면 위치를 저장하지 않는다")
    void 인증된_회원이_없으면_위치를_저장하지_않는다() {
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.empty());
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(1L, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authenticated member does not exist");

        verify(memberProfileRepository, never()).save(any(MemberProfile.class));
    }

    @Test
    @DisplayName("조회는 저장된 위치를 돌려준다")
    void 조회는_저장된_위치를_돌려준다() throws Exception {
        MemberProfile profile = MemberProfile.create(member());
        profile.updateLocation("회사", "서울특별시 강남구 테헤란로 1", LATITUDE, LONGITUDE);
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.of(profile));

        MemberLocationResponse response = service.get(1L);

        assertThat(response).isEqualTo(
                new MemberLocationResponse("회사", "서울특별시 강남구 테헤란로 1", LATITUDE, LONGITUDE));
    }

    // 프로필 행이 없는 것은 오류가 아니라 저장한 위치가 없는 상태다.
    @Test
    @DisplayName("프로필이 없으면 빈 위치를 돌려준다")
    void 프로필이_없으면_빈_위치를_돌려준다() {
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.empty());

        MemberLocationResponse response = service.get(1L);

        assertThat(response).isEqualTo(new MemberLocationResponse(null, null, null, null));
    }

    @Test
    @DisplayName("삭제는 위치 네 컬럼을 모두 비운다")
    void 삭제는_위치_네_컬럼을_모두_비운다() throws Exception {
        MemberProfile profile = MemberProfile.create(member());
        profile.updateLocation("집", "서울특별시 중구 세종대로 110", LATITUDE, LONGITUDE);
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.of(profile));

        service.delete(1L);

        assertThat(profile.getLocationNickname()).isNull();
        assertThat(profile.getRoadAddress()).isNull();
        assertThat(profile.getLatitude()).isNull();
        assertThat(profile.getLongitude()).isNull();
    }

    @Test
    @DisplayName("프로필이 없으면 삭제는 아무것도 하지 않는다")
    void 프로필이_없으면_삭제는_아무것도_하지_않는다() {
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.empty());

        service.delete(1L);

        verify(memberRepository, never()).findById(any());
    }

    private MemberLocationRequest request() {
        return new MemberLocationRequest("집", "서울특별시 중구 세종대로 110", LATITUDE, LONGITUDE);
    }

    private Member member() throws Exception {
        Member member = Member.create("kakao-123", "점심헌터", null);
        Field field = member.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(member, 1L);
        return member;
    }
}
