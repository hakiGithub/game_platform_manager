package com.gameplatform.controller;

import com.gameplatform.dto.ApiTokenCreateDTO;
import com.gameplatform.service.ApiTokenService;
import com.gameplatform.vo.ApiTokenCreatedVO;
import com.gameplatform.vo.ApiTokenVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-5: API 令牌管理控制器测试（standaloneSetup + mock Service，ADR-0029）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiTokenControllerTest {

    @Mock
    private ApiTokenService apiTokenService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Jackson2ObjectMapperBuilder 自动注册 jsr310（LocalDateTime 序列化），与 Spring Boot 运行时一致
        objectMapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiTokenController(apiTokenService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /tokens：200 且 data.token 匹配 ^gpm_")
    void createReturnsPlaintextOnce() throws Exception {
        ApiTokenCreatedVO created = ApiTokenCreatedVO.builder()
                .id(1L)
                .name("gpmcli")
                .token("gpm_AAAA-BBBB_CCCC-DDDD_EEEE-FFFF_GGGG-HHHH_IIII-JJJJ")
                .scope("read")
                .expiresAt(LocalDateTime.now().plusDays(365))
                .build();
        when(apiTokenService.create(any(ApiTokenCreateDTO.class))).thenReturn(created);

        mockMvc.perform(post("/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gpmcli\",\"scope\":\"read\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value(org.hamcrest.Matchers.matchesPattern("^gpm_.*")))
                .andExpect(jsonPath("$.data.name").value("gpmcli"));
    }

    @Test
    @DisplayName("GET /tokens：列表 JSON 不含 token / tokenHash 机密字段")
    void listHidesSecrets() throws Exception {
        ApiTokenVO vo = ApiTokenVO.builder()
                .id(1L)
                .name("gpmcli")
                .prefix("gpm_AAAA-BBBB")
                .scope("read")
                .revoked(0)
                .createTime(LocalDateTime.now())
                .build();
        when(apiTokenService.list()).thenReturn(List.of(vo));

        mockMvc.perform(get("/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].prefix").value("gpm_AAAA-BBBB"))
                .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].token").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /tokens/{id}：返回 revoked=1")
    void revokeReturnsRevoked() throws Exception {
        ApiTokenVO vo = ApiTokenVO.builder()
                .id(1L)
                .name("gpmcli")
                .prefix("gpm_AAAA-BBBB")
                .scope("read")
                .revoked(1)
                .revokedAt(LocalDateTime.now())
                .build();
        when(apiTokenService.revoke(1L)).thenReturn(vo);

        mockMvc.perform(delete("/tokens/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.revoked").value(1));
    }

    @Test
    @DisplayName("POST /tokens：非法 scope → 400")
    void invalidScopeRejected() throws Exception {
        mockMvc.perform(post("/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cli\",\"scope\":\"root_all\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /tokens：name 缺失 → 400")
    void blankNameRejected() throws Exception {
        mockMvc.perform(post("/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"read\"}"))
                .andExpect(status().isBadRequest());
    }

}
