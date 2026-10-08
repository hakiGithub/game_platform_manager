package com.gameplatform.service;

import com.gameplatform.config.ApiTokenProperties;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.dto.ApiTokenCreateDTO;
import com.gameplatform.entity.ApiToken;
import com.gameplatform.entity.User;
import com.gameplatform.enums.TokenScope;
import com.gameplatform.mapper.ApiTokenMapper;
import com.gameplatform.mapper.UserMapper;
import com.gameplatform.service.impl.ApiTokenServiceImpl;
import com.gameplatform.util.ApiTokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-2/T-3/T-4: API 令牌服务实现测试（mock Mapper，无 Web/Security 全上下文）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiTokenServiceImplTest {

    @Mock
    private ApiTokenMapper apiTokenMapper;

    @Mock
    private UserMapper userMapper;

    private ApiTokenServiceImpl apiTokenService;
    private ApiTokenProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ApiTokenProperties();
        apiTokenService = new ApiTokenServiceImpl(apiTokenMapper, userMapper, properties);

        // 三参构造才会置 authenticated=true
        TestingAuthenticationToken auth = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
        SecurityContextHolder.getContext().setAuthentication(auth);
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        when(userMapper.selectByUsername("admin")).thenReturn(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ApiTokenCreateDTO dto(String name, String scope, Integer days) {
        ApiTokenCreateDTO d = new ApiTokenCreateDTO();
        d.setName(name);
        d.setScope(scope);
        d.setExpiresInDays(days);
        return d;
    }

    @Nested
    @DisplayName("T-2 create")
    class Create {

        @Test
        @DisplayName("落库的是哈希不是明文，投影字段正确")
        void storesHashNotPlaintext() {
            when(apiTokenMapper.insert(any(ApiToken.class))).thenAnswer(inv -> {
                ApiToken e = inv.getArgument(0);
                e.setId(100L);
                return 1;
            });
            when(apiTokenMapper.countActiveByUser(1L)).thenReturn(0);
            when(apiTokenMapper.countActiveByName(1L, "cli")).thenReturn(0);

            var created = apiTokenService.create(dto("cli", "read", 30));

            ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
            verify(apiTokenMapper).insert(captor.capture());
            ApiToken stored = captor.getValue();

            assertEquals(ApiTokenCodec.hash(created.getToken()), stored.getTokenHash(),
                    "落库必须是明文的 SHA-256 哈希");
            assertFalse(stored.getTokenHash().equals(created.getToken()), "明文绝不落库");
            assertEquals(ApiTokenCodec.prefixOf(created.getToken()), stored.getTokenPrefix());
            assertEquals("cli", stored.getName());
            assertEquals("read", stored.getScope());
            assertEquals(1L, stored.getUserId());
            assertEquals(0, stored.getRevoked());
            assertEquals(100L, created.getId());
            assertNotNull(created.getExpiresAt());
            assertTrue(created.getToken().startsWith("gpm_"));
        }

        @Test
        @DisplayName("缺省：scope=read，+365 天")
        void defaults() {
            when(apiTokenMapper.insert(any(ApiToken.class))).thenAnswer(inv -> {
                ApiToken e = inv.getArgument(0);
                e.setId(101L);
                return 1;
            });
            when(apiTokenMapper.countActiveByUser(1L)).thenReturn(0);
            when(apiTokenMapper.countActiveByName(1L, "cli")).thenReturn(0);

            ApiTokenCreateDTO d = new ApiTokenCreateDTO();
            d.setName("cli");
            LocalDateTime before = LocalDateTime.now();
            var created = apiTokenService.create(d);
            LocalDateTime after = LocalDateTime.now();

            assertEquals("read", created.getScope(), "缺省 scope 应为 read");
            assertFalse(created.getExpiresAt().isBefore(before.plusDays(365).minusSeconds(10)),
                    "缺省有效期应约 365 天");
            assertFalse(created.getExpiresAt().isAfter(after.plusDays(365).plusSeconds(10)),
                    "缺省有效期应约 365 天");
        }

        @Test
        @DisplayName("非法输入：name 空白 / expiresInDays 越界 / 重名 / 超配额各自抛 BusinessException")
        void validationErrors() {
            when(apiTokenMapper.countActiveByUser(1L)).thenReturn(0);
            when(apiTokenMapper.countActiveByName(1L, "dup")).thenReturn(1);

            BusinessException blank = assertThrows(BusinessException.class,
                    () -> apiTokenService.create(dto("  ", "read", 30)));
            assertTrue(blank.getMessage().contains("令牌名"));

            BusinessException zeroDays = assertThrows(BusinessException.class,
                    () -> apiTokenService.create(dto("cli", "read", 0)));
            assertTrue(zeroDays.getMessage().contains("1~3650"));

            BusinessException tooManyDays = assertThrows(BusinessException.class,
                    () -> apiTokenService.create(dto("cli", "read", 3651)));
            assertTrue(tooManyDays.getMessage().contains("1~3650"));

            BusinessException dup = assertThrows(BusinessException.class,
                    () -> apiTokenService.create(dto("dup", "read", 30)));
            assertTrue(dup.getMessage().contains("dup"), "重名消息应含令牌名");

            when(apiTokenMapper.countActiveByUser(1L)).thenReturn(20);
            BusinessException quota = assertThrows(BusinessException.class,
                    () -> apiTokenService.create(dto("cli", "read", 30)));
            assertTrue(quota.getMessage().contains("上限"));
        }
    }

    @Nested
    @DisplayName("T-3 authenticate")
    class Authenticate {

        private ApiToken row(String scope, Integer revoked, LocalDateTime expiresAt) {
            ApiToken t = new ApiToken();
            t.setId(7L);
            t.setName("tok");
            t.setTokenPrefix("gpm_prefixxx");
            t.setTokenHash(ApiTokenCodec.hash("gpm_tok"));
            t.setScope(scope);
            t.setUserId(1L);
            t.setRevoked(revoked);
            t.setExpiresAt(expiresAt);
            t.setUsername("admin");
            return t;
        }

        private ApiTokenService.Decision auth() {
            return apiTokenService.authenticate("gpm_tok", "GET");
        }

        @Test
        @DisplayName("查无 → INVALID(not_found)")
        void notFound() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(null);
            ApiTokenService.Decision d = auth();
            assertEquals(ApiTokenService.Decision.Outcome.INVALID, d.getOutcome());
            assertEquals("not_found", d.getReason());
            assertNull(d.getUsername());
        }

        @Test
        @DisplayName("revoked=1 → INVALID(revoked)")
        void revoked() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(row("read", 1, null));
            ApiTokenService.Decision d = auth();
            assertEquals(ApiTokenService.Decision.Outcome.INVALID, d.getOutcome());
            assertEquals("revoked", d.getReason());
        }

        @Test
        @DisplayName("已过期 → INVALID(expired)；恰未过 → ALLOW")
        void expired() {
            when(apiTokenMapper.selectActiveByHash(anyString()))
                    .thenReturn(row("read", 0, LocalDateTime.now().minusSeconds(1)));
            ApiTokenService.Decision d = auth();
            assertEquals(ApiTokenService.Decision.Outcome.INVALID, d.getOutcome());
            assertEquals("expired", d.getReason());

            when(apiTokenMapper.selectActiveByHash(anyString()))
                    .thenReturn(row("read", 0, LocalDateTime.now().plusSeconds(1)));
            ApiTokenService.Decision d2 = auth();
            assertEquals(ApiTokenService.Decision.Outcome.ALLOW, d2.getOutcome());
        }

        @Test
        @DisplayName("expires_at 为 NULL → 永不过期 → ALLOW")
        void noExpiry() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(row("read", 0, null));
            assertEquals(ApiTokenService.Decision.Outcome.ALLOW, auth().getOutcome());
        }

        @Test
        @DisplayName("脏 scope → INVALID（fail-closed，不得当 write）")
        void dirtyScopeFailClosed() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(row("root_all", 0, null));
            ApiTokenService.Decision d = apiTokenService.authenticate("gpm_tok", "GET");
            assertEquals(ApiTokenService.Decision.Outcome.INVALID, d.getOutcome());
            assertNull(d.getScope());
        }

        @Test
        @DisplayName("read：GET/HEAD/OPTIONS → ALLOW；POST/PUT/DELETE/PATCH → SCOPE_DENIED")
        void readScopeByMethod() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(row("read", 0, null));

            for (String safe : new String[]{"GET", "HEAD", "OPTIONS"}) {
                assertEquals(ApiTokenService.Decision.Outcome.ALLOW,
                        apiTokenService.authenticate("gpm_tok", safe).getOutcome(),
                        "read + " + safe + " 应放行");
            }
            for (String unsafe : new String[]{"POST", "PUT", "DELETE", "PATCH"}) {
                ApiTokenService.Decision d = apiTokenService.authenticate("gpm_tok", unsafe);
                assertEquals(ApiTokenService.Decision.Outcome.SCOPE_DENIED, d.getOutcome(),
                        "read + " + unsafe + " 应拒绝");
                assertEquals("scope_read", d.getReason());
            }
        }

        @Test
        @DisplayName("write：POST 也 ALLOW")
        void writeScopeAllowsUnsafe() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(row("write", 0, null));
            ApiTokenService.Decision d = apiTokenService.authenticate("gpm_tok", "POST");
            assertEquals(ApiTokenService.Decision.Outcome.ALLOW, d.getOutcome());
            assertEquals(TokenScope.WRITE, d.getScope());
        }

        @Test
        @DisplayName("ALLOW 时 username 来自 JOIN，tokenId/tokenName/scope 齐全")
        void allowCarriesIdentity() {
            when(apiTokenMapper.selectActiveByHash(anyString())).thenReturn(row("read", 0, null));
            ApiTokenService.Decision d = auth();
            assertEquals(ApiTokenService.Decision.Outcome.ALLOW, d.getOutcome());
            assertEquals(7L, d.getTokenId());
            assertEquals("tok", d.getTokenName());
            assertEquals("admin", d.getUsername());
            assertEquals(TokenScope.READ, d.getScope());
        }
    }

    @Nested
    @DisplayName("T-4 revoke / touch")
    class RevokeAndTouch {

        @Test
        @DisplayName("吊销走 revokeById；未知 id 抛 BusinessException(code=404)")
        void revokeHappyAndNotFound() {
            ApiToken t = new ApiToken();
            t.setId(9L);
            t.setName("tok");
            t.setScope("read");
            t.setRevoked(1);
            when(apiTokenMapper.selectById(9L)).thenReturn(t);
            when(apiTokenMapper.revokeById(eq(9L), any(LocalDateTime.class))).thenReturn(1);

            var vo = apiTokenService.revoke(9L);
            assertEquals(1, vo.getRevoked());
            verify(apiTokenMapper).revokeById(eq(9L), any(LocalDateTime.class));

            when(apiTokenMapper.selectById(404L)).thenReturn(null);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> apiTokenService.revoke(404L));
            assertEquals(404, ex.getCode());
            assertTrue(ex.getMessage().contains("404") || ex.getMessage().contains("404L")
                            || ex.getMessage().contains("不存在"),
                    "消息应可读: " + ex.getMessage());
        }

        @Test
        @DisplayName("已吊销再次吊销：受影响行 0，不报错，不二次覆盖")
        void revokeIdempotent() {
            ApiToken t = new ApiToken();
            t.setId(9L);
            t.setName("tok");
            t.setScope("read");
            t.setRevoked(1);
            when(apiTokenMapper.selectById(9L)).thenReturn(t);
            when(apiTokenMapper.revokeById(eq(9L), any(LocalDateTime.class))).thenReturn(0);

            var vo = apiTokenService.revoke(9L);
            assertEquals(1, vo.getRevoked());
        }

        @Test
        @DisplayName("touch：threshold == now - throttleSeconds；配 0 时 threshold == now")
        void touchThrottle() {
            apiTokenService.touch(5L);
            ArgumentCaptor<LocalDateTime> nowCap = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> thresholdCap = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(apiTokenMapper).touchLastUsed(eq(5L), nowCap.capture(), thresholdCap.capture());
            LocalDateTime expected = nowCap.getValue().minusSeconds(properties.getLastUsedThrottleSeconds());
            assertTrue(!thresholdCap.getValue().isBefore(expected.minusSeconds(1))
                            && !thresholdCap.getValue().isAfter(expected.plusSeconds(1)),
                    "threshold 应为 now - throttleSeconds");

            properties.setLastUsedThrottleSeconds(0);
            apiTokenService.touch(5L);
            ArgumentCaptor<LocalDateTime> now2 = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> th2 = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(apiTokenMapper, org.mockito.Mockito.times(2))
                    .touchLastUsed(eq(5L), now2.capture(), th2.capture());
            assertTrue(Math.abs(java.time.Duration.between(now2.getValue(), th2.getValue()).getSeconds()) <= 1,
                    "节流配 0 时 threshold 应等于 now");
        }

        @Test
        @DisplayName("touch 失败只记日志，绝不抛出")
        void touchNeverThrows() {
            when(apiTokenMapper.touchLastUsed(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("db down"));
            apiTokenService.touch(5L);
            apiTokenService.touch(null);
            verify(apiTokenMapper, never()).touchLastUsed(eq(null), any(), any());
        }
    }

    @Test
    @DisplayName("list：投影不含 tokenHash/明文")
    void listProjectsSafely() {
        ApiToken t = new ApiToken();
        t.setId(9L);
        t.setName("tok");
        t.setTokenPrefix("gpm_abcdef12345");
        t.setTokenHash("deadbeef");
        t.setScope("read");
        t.setUserId(1L);
        t.setRevoked(0);
        t.setCreateTime(LocalDateTime.now());
        when(apiTokenMapper.selectList(any())).thenReturn(List.of(t));

        var list = apiTokenService.list();
        assertEquals(1, list.size());
        var vo = list.get(0);
        assertEquals("gpm_abcdef12345", vo.getPrefix());
        assertEquals("tok", vo.getName());
        // ApiTokenVO 没有 tokenHash/token 字段（编译期已保证）；断言哈希值不出现在投影里
        assertFalse(String.valueOf(vo).contains("deadbeef"));
        assertEquals(9, vo.getId());
        assertEquals(0, vo.getRevoked());
        verify(apiTokenMapper).selectList(any());
    }

}
