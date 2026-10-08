package com.launchcatch.owner.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.Role;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.owner.dto.OwnerSignupResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.service.OwnerSignupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerSignupControllerTest {
    private final OwnerSignupService service = mock(OwnerSignupService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OwnerSignupController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 정상_가입은_201과_계정_정보만_반환한다() throws Exception {
        when(service.signup(any())).thenReturn(new OwnerSignupResponse(
                "owner@example.com", Role.OWNER, OwnerStatus.ONBOARDING));
        mvc.perform(post("/v1/owners").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"password12\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"code":"SUCCESS","message":"요청이 성공적으로 처리되었습니다.",
                         "data":{"email":"owner@example.com","role":"OWNER","status":"ONBOARDING"}}
                        """, true))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"email\":\"bad-email\",\"password\":\"password12\"}",
            "{\"email\":\"owner@example.com\",\"password\":\"short\"}",
            "{\"email\":\"owner@example.com\",\"password\":\"123456789012345678901\"}",
            "{\"email\":\"owner@example.com\",\"password\":\"          \"}"
    })
    void 입력_오류는_400으로_거절한다(String body) throws Exception {
        mvc.perform(post("/v1/owners").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        verifyNoInteractions(service);
    }

    @Test
    void 중복_이메일은_409로_반환한다() throws Exception {
        when(service.signup(any())).thenThrow(new OwnerException(OwnerErrorCode.EMAIL_ALREADY_EXISTS));
        mvc.perform(post("/v1/owners").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"password12\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER-001"));
    }
}