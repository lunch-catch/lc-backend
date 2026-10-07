package com.launchcatch.auth.opaque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.Role;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void save는_기본_레코드와_활성_포인터를_하나의_Lua_실행으로_저장한다() {
        RefreshTokenRepository repository = new RefreshTokenRepository(redisTemplate);
        String refreshToken = "refresh-token";
        String hash = TokenHasher.sha256(refreshToken);

        repository.save(refreshToken, 1L, Role.MEMBER, true, Duration.ofMinutes(10));

        ArgumentCaptor<List<String>> keys = listCaptor();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        verifyNoMoreInteractions(redisTemplate);

        assertThat(keys.getValue()).containsExactly(
                "refreshToken:" + hash,
                "activeRefreshToken:MEMBER:1");
        assertThat(arguments.getValue()).containsExactly(
                "1|MEMBER|true",
                hash,
                "600000");
    }

    @Test
    void compareAndRotate는_기본_레코드_회전과_활성_포인터_갱신을_하나의_Lua_실행으로_처리한다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("1|MEMBER|false");
        RefreshTokenRepository repository = new RefreshTokenRepository(redisTemplate);
        String oldRefreshToken = "old-refresh-token";
        String newRefreshToken = "new-refresh-token";
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);

        RefreshTokenRepository.RotateOutcome result = repository.compareAndRotate(
                oldRefreshToken, newRefreshToken, Duration.ofMinutes(10));

        ArgumentCaptor<List<String>> keys = listCaptor();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        verifyNoMoreInteractions(redisTemplate);

        assertThat(result).isEqualTo(RefreshTokenRepository.RotateOutcome.success(
                new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, false)));
        assertThat(keys.getValue()).containsExactly(
                "refreshToken:" + oldHash,
                "refreshToken:" + newHash);
        assertThat(arguments.getValue()).containsExactly(
                "600000",
                "activeRefreshToken:",
                newHash);
    }

    @Test
    void rollbackRotation은_옛_레코드와_활성_포인터를_원자_복구한다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RefreshTokenRepository repository = new RefreshTokenRepository(redisTemplate);

        boolean result = repository.rollbackRotation("old-hash", "new-hash", Role.MEMBER, 1L);

        ArgumentCaptor<List<String>> keys = listCaptor();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        assertThat(result).isTrue();
        assertThat(keys.getValue()).containsExactly(
                "refreshToken:old-hash",
                "refreshToken:new-hash",
                "activeRefreshToken:MEMBER:1");
        assertThat(arguments.getValue()).containsExactly("old-hash", "new-hash");
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<String>> listCaptor() {
        return (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    }
}
