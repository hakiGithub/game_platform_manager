package com.gameplatform.config;

import com.gameplatform.common.result.Result;
import com.gameplatform.enums.TokenScope;
import com.gameplatform.service.ApiTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-6: JWT / API Token 过滤器分支测试（本票最重要的回归护栏，ADR-0029 D3/D5）。
 *
 * <p>钉死两组语义：①gpm_ 分支的认证/401/403/权限裁剪/互斥；②既有 JWT 路径
 * 「过期/垃圾 token 不写响应、交给 EntryPoint」与「用户不存在 → 401」原行为不变。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private ApiTokenService apiTokenService;

    private JwtAuthenticationFilter filter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 记录式 FilterChain：只标记链是否继续。 */
    private static class RecordingChain implements FilterChain {
        boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            invoked = true;
        }
    }

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, objectMapper, apiTokenService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String method, String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/system/health");
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        return req;
    }

    private UserDetails adminUser() {
        return new User("admin", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("① gpm_ 有效：链继续、上下文已认证、权限=ROLE_USER+ROLE_API_TOKEN（无 ROLE_ADMIN）、touch 一次、不进 JWT")
    void gpmTokenValid() throws Exception {
        when(apiTokenService.authenticate(anyString(), eq("GET")))
                .thenReturn(ApiTokenService.Decision.allow(1L, "mytok", "admin", TokenScope.READ));
        when(userDetailsService.loadUserByUsername("admin")).thenReturn(adminUser());

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "gpm_ABCDEF123456aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), response, chain);

        assertTrue(chain.invoked, "有效令牌应继续过滤链");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication instanceof UsernamePasswordAuthenticationToken);
        var authorities = authentication.getAuthorities();
        assertTrue(authorities.stream().anyMatch(a -> "ROLE_USER".equals(a.getAuthority())),
                "应含 ROLE_USER");
        assertTrue(authorities.stream().anyMatch(a -> "ROLE_API_TOKEN".equals(a.getAuthority())),
                "应含 ROLE_API_TOKEN");
        assertFalse(authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())),
                "令牌主体绝不含 ROLE_ADMIN（ADR-0029 D5），即使归属 admin");
        verify(apiTokenService, times(1)).touch(1L);
        verify(jwtTokenProvider, never()).validateToken(any());
    }

    @Test
    @DisplayName("② gpm_ INVALID：链未继续、HTTP 401、体含 \"code\":401")
    void gpmTokenInvalid() throws Exception {
        when(apiTokenService.authenticate(anyString(), anyString()))
                .thenReturn(ApiTokenService.Decision.invalid("not_found"));

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "gpm_nope"), response, chain);

        assertFalse(chain.invoked, "INVALID 不得继续过滤链");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"),
                "响应体应含 code=401: " + response.getContentAsString());
    }

    @Test
    @DisplayName("③ gpm_ SCOPE_DENIED：链未继续、HTTP 403、体含 \"code\":403")
    void gpmTokenScopeDenied() throws Exception {
        when(apiTokenService.authenticate(anyString(), eq("POST")))
                .thenReturn(ApiTokenService.Decision.scopeDenied("scope_read"));

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "gpm_ABCDEF123456aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), response, chain);

        assertFalse(chain.invoked, "SCOPE_DENIED 不得继续过滤链");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":403"),
                "响应体应含 code=403: " + response.getContentAsString());
    }

    @Test
    @DisplayName("④ 有效 JWT：链继续、权限=userDetails 原样（含 ROLE_ADMIN）、不进令牌分支")
    void validJwtUnchanged() throws Exception {
        when(jwtTokenProvider.validateToken("good.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("good.jwt.token")).thenReturn("admin");
        when(userDetailsService.loadUserByUsername("admin")).thenReturn(adminUser());

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "good.jwt.token"), response, chain);

        assertTrue(chain.invoked, "有效 JWT 应继续过滤链");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())),
                "JWT 路径权限应保留 ROLE_ADMIN（userDetails 原样）");
        verify(apiTokenService, never()).authenticate(any(), any());
        verify(apiTokenService, never()).touch(any());
    }

    @Test
    @DisplayName("⑤ 过期/垃圾 JWT：链继续、不写响应（401 留给 EntryPoint）、不进令牌分支")
    void garbageJwtFallsThrough() throws Exception {
        when(jwtTokenProvider.validateToken("garbage")).thenReturn(false);

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "garbage"), response, chain);

        assertTrue(chain.invoked, "垃圾 JWT 应继续过滤链（与 3c5df3e 行为一致）");
        assertFalse(response.isCommitted(), "JWT 无效时过滤器不得写响应，401 留给 EntryPoint");
        assertEquals(200, response.getStatus());
        verify(apiTokenService, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("⑥ JWT 路径用户不存在：401 JSON、链未继续（既有 catch 行为）")
    void jwtPathUserNotFound() throws Exception {
        when(jwtTokenProvider.validateToken("good.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("good.jwt.token")).thenReturn("ghost");
        when(userDetailsService.loadUserByUsername("ghost"))
                .thenThrow(new UsernameNotFoundException("用户不存在: ghost"));

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "good.jwt.token"), response, chain);

        assertFalse(chain.invoked, "用户不存在不得继续过滤链");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"));
        verify(apiTokenService, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("⑦ gpm_ 认证抛异常（DB 故障）：fail-closed 成 401 JSON")
    void gpmTokenDbFailureFailsClosed() throws Exception {
        when(apiTokenService.authenticate(anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "gpm_whatever"), response, chain);

        assertFalse(chain.invoked);
        assertEquals(401, response.getStatus());
        Result<?> body = objectMapper.readValue(response.getContentAsString(), Result.class);
        assertEquals(401, body.getCode());
    }

}
