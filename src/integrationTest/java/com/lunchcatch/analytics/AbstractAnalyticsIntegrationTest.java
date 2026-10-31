package com.lunchcatch.analytics;

import com.lunchcatch.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractAnalyticsIntegrationTest extends IntegrationTestSupport {
}
