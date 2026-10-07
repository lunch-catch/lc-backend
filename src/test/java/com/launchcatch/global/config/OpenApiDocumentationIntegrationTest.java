package com.launchcatch.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("회원 인증 OpenAPI 문서에 쿠키 인증과 주요 API 설명을 노출한다")
    void 회원_인증_OpenAPI_문서를_노출한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("LunchCatch API"))
                .andExpect(jsonPath("$.components.securitySchemes.memberAccessToken.in").value("cookie"))
                .andExpect(jsonPath("$.paths['/v1/auth/kakao/authorize'].get.tags[0]").value("회원 인증"))
                .andExpect(jsonPath("$.paths['/v1/auth/kakao/authorize'].get.description").isNotEmpty())
                .andExpect(jsonPath("$.paths['/v1/auth/tokens'].post.requestBody.required").value(true))
                .andExpect(jsonPath("$.paths['/v1/auth/tokens'].post.responses.401.description").isNotEmpty())
                .andExpect(jsonPath("$.paths['/v1/auth/tokens:refresh'].post.parameters[0].in").value("cookie"))
                .andExpect(jsonPath("$.paths['/v1/auth/tokens'].delete.security[0].memberAccessToken").exists());
    }
}
