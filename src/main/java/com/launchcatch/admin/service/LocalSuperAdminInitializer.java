package com.launchcatch.admin.service;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/*
 * 로컬에서 관리자 기능을 시험할 최고 관리자 한 명을 기동할 때 만든다.
 * 관리자는 최고 관리자가 API 로 발급하므로 첫 한 명은 밖에서 넣어야 하는데, 로컬에서는 이 클래스가 그 일을 한다.
 * 개발, 운영 서버는 local 프로필이 아니라 이 빈이 생기지 않는다. 그쪽은 lc-infra 의 seed-admin.sh 가 넣는다.
 * 같은 아이디가 이미 있으면 아무것도 하지 않아 몇 번 기동해도 행이 늘지 않는다.
 * 테스트도 기본 프로필이 local 이라 이 빈이 뜬다. Clock 을 목으로 바꾼 통합 테스트에서 시각이 null 이 되어
 * 컨텍스트가 뜨지 않으므로, 테스트 설정(src/test/resources/application.properties)에서 끈다.
 */
@Slf4j
@Service
@Profile("local")
@ConditionalOnProperty(name = "local.super-admin.enabled", havingValue = "true", matchIfMissing = true)
public class LocalSuperAdminInitializer implements ApplicationRunner {

    static final String LOGIN_ID = "superadmin";
    static final String NAME = "최고관리자";

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public LocalSuperAdminInitializer(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            @Value("${local.super-admin.password:local-superadmin-1234}") String password) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminRepository.findByLoginId(LOGIN_ID).isPresent()) {
            return;
        }
        adminRepository.save(Admin.register(LOGIN_ID, passwordEncoder.encode(password), NAME, Role.SUPER_ADMIN));
        log.info("로컬 최고 관리자를 만들었다. loginId={}", LOGIN_ID);
    }
}
