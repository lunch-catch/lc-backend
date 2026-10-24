package com.launchcatch.admin;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.admin.repository.AdminAccessTokenRevocationRepository;
import com.launchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.ops.contract.AuditLogWriter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/*
 * admin 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. Spring 의 컨텍스트 캐시 키는 클래스 수가
 * 아니라 빈 오버라이드와 설정의 집합으로 갈리므로, 그 집합을 여기 모으면 테스트 파일이 여럿이어도
 * 컨텍스트는 하나다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓴다(#277).
 *
 * 전부 스파이다. 목으로 두면 스텁하지 않은 테스트가 null 을 받아 깨진다. 스파이는 기본적으로
 * 실제 구현에 위임하고, 경쟁을 만들어야 하는 테스트만 그 호출을 스텁한다. 스파이에는 when(...)
 * 이 실제 메서드를 먼저 부르므로 doReturn, doAnswer, doThrow 로 스텁한다.
 *
 * jwt.secret 과 컨테이너 주소는 IntegrationTestSupport 가 등록한다. 테스트가 다시 적지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractAdminIntegrationTest extends IntegrationTestSupport {

    @MockitoSpyBean protected JwtTokenProvider jwt;
    @MockitoSpyBean protected RefreshTokenRepository tokens;
    @MockitoSpyBean protected com.launchcatch.admin.repository.AdminSessionRefreshTokenRepository sessionTokens;
    @MockitoSpyBean protected PasswordEncoder encoder;
    @MockitoSpyBean protected AuditLogWriter audit;
    @MockitoSpyBean protected AdminRepository admins;
    @MockitoSpyBean protected AdminAccessTokenVersionRepository versions;
    @MockitoSpyBean protected AdminAccessTokenRevocationRepository revocations;
    @MockitoSpyBean protected AccessTokenValidAfterRepository legacyCutoff;

    /*
     * 트랜잭션 매니저까지 스파이로 둔다. 로그인 보안 테스트가 커밋 경계를 가짜로 만들어야 해서다.
     * 기본은 실제 매니저에 위임하므로 스텁하지 않는 테스트의 실제 트랜잭션과 행 잠금은 그대로다.
     */
    @MockitoSpyBean protected PlatformTransactionManager transactions;
}
