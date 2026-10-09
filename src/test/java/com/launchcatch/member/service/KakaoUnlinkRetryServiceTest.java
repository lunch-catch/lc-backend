package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.KakaoUnlinkFailure;
import com.launchcatch.member.entity.KakaoUnlinkStopReason;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkRetryServiceTest {

    @Mock KakaoUnlinkFailureRepository failureRepository;
    @Mock KakaoUnlinkClient kakaoUnlinkClient;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;

    private KakaoUnlinkRetryService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        service = new KakaoUnlinkRetryService(
                failureRepository,
                kakaoUnlinkClient,
                new TransactionTemplate(transactionManager));
    }

    @Test
    @DisplayName("해제에 성공하면 대기 행을 지운다")
    void 해제에_성공하면_대기_행을_지운다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);

        service.retryPending();

        verify(kakaoUnlinkClient).unlink("kakao-1");
        verify(failureRepository).delete(failure);
    }

    @Test
    @DisplayName("회원이 재가입했으면 호출하지 않고 대기 행을 지운다")
    void 회원이_재가입했으면_호출하지_않고_대기_행을_지운다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.ACTIVE);

        service.retryPending();

        verify(kakaoUnlinkClient, never()).unlink(anyString());
        verify(failureRepository).delete(failure);
    }

    @Test
    @DisplayName("이미 닫힌 행은 호출하지 않는다")
    void 이미_닫힌_행은_호출하지_않는다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        failure.rejectPermanently();

        service.retryPending();

        verify(kakaoUnlinkClient, never()).unlink(anyString());
        verify(failureRepository, never()).delete(any(KakaoUnlinkFailure.class));
    }

    @Test
    @DisplayName("일시적 실패는 재시도 횟수만 올린다")
    void 일시적_실패는_재시도_횟수만_올린다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        doThrow(new RuntimeException("network")).when(kakaoUnlinkClient).unlink("kakao-1");

        service.retryPending();

        assertThat(failure.getAttemptCount()).isOne();
        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.getStopReason()).isNull();
    }

    @Test
    @DisplayName("한도에 닿은 실패는 소진으로 닫는다")
    void 한도에_닿은_실패는_소진으로_닫는다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        setField(failure, "attemptCount", KakaoUnlinkRetryService.MAX_RETRY_ATTEMPTS - 1);
        doThrow(new RuntimeException("network")).when(kakaoUnlinkClient).unlink("kakao-1");

        service.retryPending();

        assertThat(failure.getAttemptCount()).isEqualTo(KakaoUnlinkRetryService.MAX_RETRY_ATTEMPTS);
        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.getStopReason()).isEqualTo(KakaoUnlinkStopReason.EXHAUSTED);
    }

    @Test
    @DisplayName("카카오가 400으로 거절하면 횟수를 올리지 않고 영구 거부로 닫는다")
    void 카카오가_400으로_거절하면_영구_거부로_닫는다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        doThrow(kakaoResponded(HttpStatus.BAD_REQUEST)).when(kakaoUnlinkClient).unlink("kakao-1");

        service.retryPending();

        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.getStopReason()).isEqualTo(KakaoUnlinkStopReason.REJECTED);
        assertThat(failure.getAttemptCount()).isZero();
    }

    /*
     * 429 는 Admin Key 단위 호출 한도라 일시적이다. KakaoCircuitBreakerConfig 도 5xx 와 같이
     * 서킷 실패로 센다. 여기서 영구 거부로 닫으면 한 번의 제한으로 큐에서 영원히 빠진다.
     */
    @Test
    @DisplayName("429는 영구 거부가 아니라 재시도 대상이다")
    void 호출_한도_초과는_재시도_대상이다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        doThrow(kakaoResponded(HttpStatus.TOO_MANY_REQUESTS)).when(kakaoUnlinkClient).unlink("kakao-1");

        service.retryPending();

        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.getAttemptCount()).isOne();
    }

    /*
     * 401 은 우리 Admin Key 설정 문제다. 포기하면 오설정 기간에 탈퇴한 회원 전부의 카카오
     * 연결이 영구히 남는다. 키를 고치면 그대로 성공할 요청이라 재시도 대상으로 둔다.
     */
    @Test
    @DisplayName("401은 영구 거부가 아니라 재시도 대상이다")
    void 관리자_키_오류는_재시도_대상이다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        doThrow(kakaoResponded(HttpStatus.UNAUTHORIZED)).when(kakaoUnlinkClient).unlink("kakao-1");

        service.retryPending();

        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.getAttemptCount()).isOne();
    }

    /*
     * 서킷이 열려 카카오에 묻지도 못한 경우다. 횟수를 올리면 "다섯 번 시도했는데도 실패" 라는
     * 한도의 뜻이 깨지고, 장애가 길어지면 한 번도 제대로 묻지 못한 채 포기 처리된다.
     */
    @Test
    @DisplayName("서킷이 열렸으면 재시도 횟수를 올리지 않는다")
    void 서킷이_열렸으면_재시도_횟수를_올리지_않는다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        doThrow(CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("kakaoUnlink"))).when(kakaoUnlinkClient).unlink("kakao-1");

        service.retryPending();

        assertThat(failure.getAttemptCount()).isZero();
        assertThat(failure.isResolved()).isFalse();
    }

    /*
     * 해제는 성공했는데 행 삭제가 실패한 경우다. 외부 호출 실패와 한 경로로 묶으면 성공한
     * 해제가 실패로 집계되어 재시도 횟수가 오른다. 남은 행은 다음 사이클이 다시 부를 뿐이다.
     */
    @Test
    @DisplayName("해제 성공 뒤 정리가 실패해도 호출 실패로 세지 않는다")
    void 해제_성공_뒤_정리가_실패해도_호출_실패로_세지_않는다() throws Exception {
        KakaoUnlinkFailure failure = pendingFor(MemberStatus.WITHDRAWN);
        doThrow(new RuntimeException("db down")).when(failureRepository).delete(failure);

        assertThatCode(() -> service.retryPending()).doesNotThrowAnyException();

        verify(kakaoUnlinkClient).unlink("kakao-1");
        assertThat(failure.getAttemptCount()).isZero();
        assertThat(failure.isResolved()).isFalse();
    }

    @Test
    @DisplayName("실패 기록이 실패해도 나머지 행은 계속 처리한다")
    void 실패_기록이_실패해도_나머지_행은_계속_처리한다() throws Exception {
        KakaoUnlinkFailure first = failure(MemberStatus.WITHDRAWN);
        KakaoUnlinkFailure second = failure(MemberStatus.WITHDRAWN);
        setId(second, 11L);
        setField(second, "providerUserId", "kakao-2");
        when(failureRepository.findPendingOldestFirst(any(Pageable.class))).thenReturn(List.of(first, second));
        when(failureRepository.findById(10L))
                .thenReturn(Optional.of(first))
                .thenThrow(new RuntimeException("db down"));
        when(failureRepository.findById(11L)).thenReturn(Optional.of(second));
        doThrow(new RuntimeException("kakao 500")).when(kakaoUnlinkClient).unlink("kakao-1");

        assertThatCode(() -> service.retryPending()).doesNotThrowAnyException();

        verify(kakaoUnlinkClient).unlink("kakao-2");
        verify(failureRepository).delete(second);
        assertThat(first.getAttemptCount()).isZero();
    }

    /*
     * 재확인과 호출 사이에 재가입이 끼어들면 방금 맺은 카카오 연결을 우리가 끊는다. 외부 호출을
     * 트랜잭션 밖에 두는 한 이 창은 없앨 수 없으므로, 지나간 뒤에 탐지만 한다. 대기 행이
     * 사라졌다는 것이 그 신호다.
     */
    @Test
    @DisplayName("호출 중 재가입이 대기 행을 지웠으면 삭제를 시도하지 않는다")
    void 호출_중_재가입이_대기_행을_지웠으면_삭제를_시도하지_않는다() throws Exception {
        KakaoUnlinkFailure failure = failure(MemberStatus.WITHDRAWN);
        when(failureRepository.findPendingOldestFirst(any(Pageable.class))).thenReturn(List.of(failure));
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure), Optional.empty());

        assertThatCode(() -> service.retryPending()).doesNotThrowAnyException();

        verify(kakaoUnlinkClient).unlink("kakao-1");
        verify(failureRepository, never()).delete(any(KakaoUnlinkFailure.class));
    }

    private WebClientResponseException kakaoResponded(HttpStatus status) {
        return WebClientResponseException.create(
                status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    /** 후보 조회와 호출 직전 재확인이 같은 행을 돌려주도록 묶어 둔다. */
    private KakaoUnlinkFailure pendingFor(MemberStatus status) throws Exception {
        KakaoUnlinkFailure failure = failure(status);
        when(failureRepository.findPendingOldestFirst(any(Pageable.class))).thenReturn(List.of(failure));
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure));
        return failure;
    }

    private KakaoUnlinkFailure failure(MemberStatus status) throws Exception {
        Member member = Member.create("kakao-1", "닉네임", null);
        setId(member, 1L);
        setField(member, "status", status);
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.create(member);
        setId(failure, 10L);
        return failure;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
