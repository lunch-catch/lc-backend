package com.launchcatch.coupon;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.time.Clock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = "coupon.daily-limit=3")
@AutoConfigureMockMvc
abstract class AbstractCouponIntegrationTest extends IntegrationTestSupport {

    @MockitoSpyBean Clock clock;
    @MockitoBean OwnerStatusQuery ownerStatusQuery;
    @MockitoBean CampaignQueryService campaignQueryService;
    @MockitoSpyBean CouponQrImageEncoder imageEncoder;
    @MockitoSpyBean MemberQueryService members;
    @MockitoSpyBean CouponEventRepository events;
    @MockitoSpyBean CouponInventoryRepository inventories;
    @MockitoSpyBean MemberCouponRepository memberCouponRepository;
}
