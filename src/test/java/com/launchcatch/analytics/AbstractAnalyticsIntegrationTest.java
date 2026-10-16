package com.launchcatch.analytics;

import com.launchcatch.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractAnalyticsIntegrationTest extends IntegrationTestSupport {
}
