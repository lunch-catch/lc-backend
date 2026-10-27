package com.lunchcatch.auth.jwt;

import com.lunchcatch.auth.RedisFailureClassifier;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccessTokenCutoffVerifier {
    private final AccessTokenValidAfterRepository repository;

    /*
     * LENIENT는 캐시의 짧은 일시 장애로 서비스 전체를 막지 않기 위한 정책이다.
     * 허용하는 위험은 로그아웃한 AT가 남은 수명 동안, 최대 30분 더 사용되는 것이다.
     * REQUIRED는 지속되는 권한을 만드는 작업 등에 사용하고, 조회 장애 시 AUTH-002로 중단한다.
     */
    public boolean isValidAfter(Role role, Long id, LocalDateTime issuedAt, CutoffPolicy policy) {
        Objects.requireNonNull(policy, "토큰 폐기 기준 검증 정책은 필수입니다.");
        if (issuedAt == null) {
            log.warn("event=AUTH_ACCESS_TOKEN_ISSUED_AT_MISSING role={} subjectId={}", role, id);
            return false;
        }
        try {
            return repository.isValidAfter(role, id, issuedAt);
        } catch (DataAccessException e) {
            if (policy == CutoffPolicy.REQUIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
            }
            log.warn("event=AUTH_ACCESS_TOKEN_VALID_AFTER_CHECK_FAILED role={} subjectId={} reason={} policy={} fallback=PASS_THROUGH",
                    role, id, RedisFailureClassifier.causeLabel(e), policy, e);
            return true;
        }
    }
}
