package com.launchcatch.owner;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.owner.repository.OwnerLogoutTokenRepository;
import com.launchcatch.owner.repository.OwnerRefreshTokenRepository;
import com.launchcatch.owner.repository.OwnerAccessTokenRevocationRepository;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.owner.service.OwnerLogoutService;
import com.launchcatch.owner.service.OwnerTokenRefreshService;
import java.time.Clock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/*
 * owner 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 두려는 것이다. Spring 의 컨텍스트 캐시 키는 클래스
 * 수가 아니라 빈 오버라이드와 설정의 집합으로 갈리므로, 그 집합을 여기 모으면 테스트 파일이
 * 여럿이어도 컨텍스트는 하나다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓴다(#277).
 *
 * 전부 스파이다. 목으로 두면 스텁하지 않은 테스트가 null 이나 빈 값을 받아 깨진다. 스파이는
 * 기본적으로 실제 구현에 위임하고, 경쟁이나 장애를 만들어야 하는 테스트만 그 호출을 스텁한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractOwnerIntegrationTest extends IntegrationTestSupport {

    @MockitoSpyBean protected OwnerRepository owners;
    @MockitoSpyBean protected com.launchcatch.owner.repository.OwnerSessionRepository sessions;
    @MockitoSpyBean protected com.launchcatch.owner.repository.OwnerSessionTokenRepository sessionTokens;
    @MockitoSpyBean protected RefreshTokenRepository tokens;
    @MockitoSpyBean protected OwnerRefreshTokenRepository ownerTokens;
    @MockitoSpyBean protected OwnerAccessTokenVersionRepository versions;
    @MockitoSpyBean protected OwnerLogoutTokenRepository logoutTokens;
    @MockitoSpyBean protected OwnerAccessTokenRevocationRepository revocations;
    @MockitoSpyBean protected AccessTokenValidAfterRepository cutoff;
    @MockitoSpyBean protected OwnerLogoutService logoutService;
    @MockitoSpyBean protected OwnerTokenRefreshService refreshService;
    @MockitoSpyBean protected Clock clock;
}
