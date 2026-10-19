package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.owner.dto.StoreCompleteRequest;
import com.launchcatch.owner.repository.OwnerStatusCacheRepository;
import com.launchcatch.owner.service.OwnerStoreCompleteService;
import com.launchcatch.store.service.StoreMenuUploadService;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StoreCompleteIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(5020000000L);
    private static final String PATH = "/v1/owner/store:complete";
    @Autowired JdbcTemplate jdbc;
    @Autowired OwnerStoreCompleteService completion;
    @Autowired StoreMenuUploadService menus;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OwnerStatusCacheRepository cache;
    private long ownerId;
    private long storeId;

    @BeforeEach
    void prepare() {
        ownerId = IDS.incrementAndGet();
        var now = LocalDateTime.now();
        jdbc.update("""
                insert into owner (owner_id,email,password_hash,role,status,created_at,updated_at)
                values (?,?,'hash','OWNER','ONBOARDING',?,?)
                """, ownerId, "complete502-"+ownerId+"@example.com", now, now);
        jdbc.update("""
                insert into store (owner_id,name,category_code,representative_name,business_registration_number,
                  phone,business_verification_status,business_verified_at,kakao_place_id,road_address,
                  latitude,longitude,finalized,created_at,updated_at)
                values (?,'최종 식당','KOREAN','점주','1234567890','02-123-4567','VERIFIED',?,'123','서울 주소',37.5,127,false,?,?)
                """, ownerId, now, now, now);
        storeId = jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, ownerId);
        jdbc.update("""
                insert into owner_terms_agreement (owner_id,required,agreed,agreed_at,created_at,updated_at)
                values (?,true,true,?,?,?)
                """, ownerId, now, now, now);
        jdbc.update("""
                insert into store_registration_progress (owner_id,store_id,resume_step,version,created_at,updated_at)
                values (?,?,'MENU',5,?,?)
                """, ownerId, storeId, now, now);
        for (var day : DayOfWeek.values()) {
            jdbc.update("""
                    insert into store_business_hour (store_id,day_of_week,closed,created_at,updated_at)
                    values (?,?,true,?,?)
                    """, storeId, day.name(), now, now);
        }
        jdbc.update("""
                insert into store_image (store_id,image_type,object_key,upload_status,verified_at,created_at,updated_at)
                values (?,'LOGO',?,'VERIFIED',?,?,?)
                """, storeId, "stores/"+storeId+"/logo.jpg", now, now, now);
    }

    @AfterEach
    void cleanup() {
        cache.delete(ownerId);
        jdbc.update("delete i from store_menu_image i join store_menu m on m.store_menu_id=i.store_menu_id where m.store_id=?", storeId);
        jdbc.update("delete from store_menu where store_id=?", storeId);
        jdbc.update("delete from store_image where store_id=?", storeId);
        jdbc.update("delete from store_business_hour where store_id=?", storeId);
        jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
        jdbc.update("delete from owner_terms_agreement where owner_id=?", ownerId);
        jdbc.update("delete from store where store_id=?", storeId);
        jdbc.update("delete from owner where owner_id=?", ownerId);
    }

    private MockHttpServletRequestBuilder request() {
        return post(PATH).with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":5}");
    }

    private void assertDraft() {
        assertThat(jdbc.queryForObject("select finalized from store where store_id=?", Boolean.class, storeId)).isFalse();
        assertThat(jdbc.queryForObject("select finalized_at from store where store_id=?", LocalDateTime.class, storeId)).isNull();
        assertThat(jdbc.queryForObject("select status from owner where owner_id=?", String.class, ownerId)).isEqualTo("ONBOARDING");
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(5);
    }

    @Test
    void 메뉴_완료_호출_없이_최종_등록하고_조회와_캐시에_ACTIVE를_반영한다() throws Exception {
        cache.save(ownerId, "ONBOARDING");
        mvc.perform(get("/v1/stores/{storeId}", storeId)
                        .with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("STORE-016"));
        mvc.perform(request()).andExpect(status().isOk())
                .andExpect(jsonPath("data.storeId").value(storeId))
                .andExpect(jsonPath("data.finalized").value(true))
                .andExpect(jsonPath("data.finalizedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("data.ownerStatus").value("ACTIVE"))
                .andExpect(jsonPath("data.version").value(6));
        assertThat(cache.find(ownerId)).isEmpty();
        mvc.perform(get("/v1/stores/{storeId}", storeId)
                        .with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.storeId").value(storeId))
                .andExpect(jsonPath("data.name").value("최종 식당"))
                .andExpect(jsonPath("data.businessHours.length()").value(7))
                .andExpect(jsonPath("data.menus").isEmpty());
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.finalized").value(true))
                .andExpect(jsonPath("data.onboarding.resumeStep").isEmpty())
                .andExpect(jsonPath("data.onboarding.canComplete").value(false));
        mvc.perform(get("/v1/owner/onboarding").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.finalized").value(true))
                .andExpect(jsonPath("data.canFinalize").value(false));
        mvc.perform(get("/v1/owner/me").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.status").value("ACTIVE"))
                .andExpect(jsonPath("data.registrationStep").isEmpty());
        var first = jdbc.queryForObject("select finalized_at from store where store_id=?", LocalDateTime.class, storeId);
        mvc.perform(request()).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select finalized_at from store where store_id=?", LocalDateTime.class, storeId)).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void 이미지까지_확인한_선택_메뉴를_포함해_등록한다(int count) throws Exception {
        var now = LocalDateTime.now();
        for (int order = 1; order <= count; order++) {
            jdbc.update("insert into store_menu (store_id,name,price,sort_order,created_at,updated_at) values (?,'메뉴',1000,?,?,?)", storeId, order, now, now);
        }
        jdbc.update("""
                insert into store_menu_image (store_menu_id,object_key,upload_status,verified_at,created_at,updated_at)
                select store_menu_id,concat('menus/',store_menu_id,'.jpg'),'VERIFIED',?,?,? from store_menu where store_id=?
                """, now, now, now, storeId);
        mvc.perform(request()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from store_menu where store_id=?", Integer.class, storeId)).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "delete from owner_terms_agreement where owner_id=?",
        "update store set phone=null where owner_id=?",
        "update store set business_verification_status='FAILED',business_verified_at=null where owner_id=?",
        "update store set road_address=null where owner_id=?",
        "delete h from store_business_hour h join store s on s.store_id=h.store_id where s.owner_id=? and h.day_of_week='SUNDAY'",
        "update store_image i join store s on s.store_id=i.store_id set i.upload_status='PENDING',i.verified_at=null where s.owner_id=?"
    })
    void 필수_데이터가_없으면_두_조회와_최종_등록이_같이_미완료로_판정한다(String sql) throws Exception {
        jdbc.update(sql, ownerId);
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.onboarding.canComplete").value(false));
        mvc.perform(get("/v1/owner/onboarding").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.canFinalize").value(false));
        mvc.perform(request()).andExpect(status().isConflict()).andExpect(jsonPath("code").value("STORE-015"));
        assertDraft();
    }

    @Test
    void 이미지가_없는_메뉴를_등록하면_최종_등록을_거절한다() throws Exception {
        var now = LocalDateTime.now();
        jdbc.update("insert into store_menu (store_id,name,price,sort_order,created_at,updated_at) values (?,'메뉴',1000,1,?,?)", storeId, now, now);
        mvc.perform(request()).andExpect(status().isConflict());
        assertDraft();
    }

    @Test
    void 내부_이미지가_PENDING이면_내_가게_조회에서도_완료로_표시하지_않는다() throws Exception {
        var now = LocalDateTime.now();
        jdbc.update("""
                insert into store_image (store_id,image_type,object_key,sort_order,upload_status,created_at,updated_at)
                values (?,'INTERIOR',?,1,'PENDING',?,?)
                """, storeId, "stores/"+storeId+"/interior.jpg", now, now);
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.onboarding.canComplete").value(false));
        mvc.perform(request()).andExpect(status().isConflict());
        assertDraft();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"version\":null}", "{\"version\":-1}"})
    void 잘못된_요청_버전은_400이다(String body) throws Exception {
        mvc.perform(request().content(body)).andExpect(status().isBadRequest());
        assertDraft();
    }

    @Test
    void 오래된_버전은_409이고_저장값이_유지된다() throws Exception {
        mvc.perform(request().content("{\"version\":4}")).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("STORE-001"));
        assertDraft();
    }

    @Test
    void 미인증과_다른_역할은_등록할_수_없다() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"version\":5}")).andExpect(status().isUnauthorized());
        mvc.perform(request().with(user(new CustomUserDetails(ownerId, Role.MEMBER)))).andExpect(status().isForbidden());
        assertDraft();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    void 허용하지_않는_점주_상태는_거절한다(String state) throws Exception {
        jdbc.update("update owner set status=?,suspended_at=?,withdrawn_at=? where owner_id=?",
                state, LocalDateTime.now(), LocalDateTime.now(), ownerId);
        mvc.perform(request()).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select finalized from store where store_id=?", Boolean.class, storeId)).isFalse();
    }

    @Test
    void 트랜잭션_후속_실패는_가게_확정과_ACTIVE와_버전을_함께_롤백한다() {
        cache.save(ownerId, "ONBOARDING");
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            completion.complete(ownerId, new StoreCompleteRequest(5L));
            jdbc.queryForObject("select count(*) from store where store_id=?", Integer.class, storeId);
            throw new IllegalStateException("후속 처리 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertDraft();
        assertThat(cache.find(ownerId)).contains("ONBOARDING");
    }

    @Test
    void 같은_버전으로_동시_등록하면_한_번만_확정한다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> save = () -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(request()).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(save);
            var second = executor.submit(save);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 403);
        }
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(6);
    }

    @Test
    void 영업시간_수정과_같은_버전의_최종_등록은_함께_반영되지_않는다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var finish = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(request()).andReturn().getResponse().getStatus();
            });
            var save = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(put("/v1/owner/store/hours")
                        .with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":5,\"businessHours\":[]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            var statuses = List.of(finish.get(20, TimeUnit.SECONDS), save.get(20, TimeUnit.SECONDS));
            assertThat(statuses).contains(200);
            assertThat(statuses).doesNotContainSequence(200, 200);
            assertThat(statuses).allMatch(code -> code == 200 || code == 400 || code == 409);
        }
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(6);
        var finalized = jdbc.queryForObject("select finalized from store where store_id=?", Boolean.class, storeId);
        assertThat(jdbc.queryForObject("select status='ACTIVE' from owner where owner_id=?", Boolean.class, ownerId)).isEqualTo(finalized);
    }

    @Test
    void 다른_점주의_가게에_연결된_진행_상태는_거절한다() throws Exception {
        long foreignOwner = IDS.incrementAndGet();
        var now = LocalDateTime.now();
        jdbc.update("""
                insert into store (owner_id,name,category_code,representative_name,created_at,updated_at)
                values (?,'다른 식당','KOREAN','다른 점주',?,?)
                """, foreignOwner, now, now);
        long foreignStore = jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, foreignOwner);
        try {
            jdbc.update("update store_registration_progress set store_id=? where owner_id=?", foreignStore, ownerId);
            mvc.perform(request()).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("STORE-004"));
            assertDraft();
        } finally {
            jdbc.update("update store_registration_progress set store_id=? where owner_id=?", storeId, ownerId);
            jdbc.update("delete from store where store_id=?", foreignStore);
        }
    }

    @Test
    void 확정_이후에는_메뉴를_추가해_불완전한_가게로_되돌릴_수_없다() throws Exception {
        mvc.perform(request()).andExpect(status().isOk());
        assertThatThrownBy(() -> menus.registerMenu(ownerId, "뒤늦은 메뉴", 1000, null))
                .isInstanceOf(com.launchcatch.store.exception.StoreException.class);
        assertThat(jdbc.queryForObject("select count(*) from store_menu where store_id=?", Integer.class, storeId)).isZero();
    }
}
