package com.gameplatform.service;

import cn.hutool.crypto.SecureUtil;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.JwtTokenProvider;
import com.gameplatform.dto.LoginDTO;
import com.gameplatform.entity.User;
import com.gameplatform.mapper.UserMapper;
import com.gameplatform.service.impl.UserServiceImpl;
import com.gameplatform.vo.LoginVO;
import com.gameplatform.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 用户服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("用户服务测试")
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtTokenProvider jwtTokenProvider;


    @InjectMocks
    private UserServiceImpl userService;

    private User testUser;
    private LoginDTO loginDTO;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("admin");
        testUser.setPasswordHash(SecureUtil.sha256("admin123"));
        testUser.setLastLoginTime(LocalDateTime.now());
        testUser.setLastLoginIp("127.0.0.1");

        loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin123");
    }

    @Test
    @DisplayName("登录成功测试")
    void testLoginSuccess() {
        // Given
        when(userMapper.selectByUsername("admin")).thenReturn(testUser);
        when(jwtTokenProvider.generateToken(anyString())).thenReturn("test-token");
        when(jwtTokenProvider.getExpiration()).thenReturn(604800000L);
        when(userMapper.updateLoginInfo(anyLong(), any(LocalDateTime.class), anyString())).thenReturn(1);

        // When
        LoginVO result = userService.login(loginDTO, "127.0.0.1");

        // Then
        assertNotNull(result);
        assertEquals("test-token", result.getToken());
        assertEquals("Bearer", result.getTokenType());
        assertNotNull(result.getUser());
        assertEquals("admin", result.getUser().getUsername());

        verify(userMapper).selectByUsername("admin");
        verify(jwtTokenProvider).generateToken("admin");
        verify(userMapper).updateLoginInfo(eq(1L), any(LocalDateTime.class), eq("127.0.0.1"));
    }

    @Test
    @DisplayName("登录失败-用户不存在")
    void testLoginFailUserNotFound() {
        // Given
        when(userMapper.selectByUsername("admin")).thenReturn(null);

        // When & Then
        assertThrows(BusinessException.class, () -> {
            userService.login(loginDTO, "127.0.0.1");
        });

        verify(userMapper).selectByUsername("admin");
        verify(jwtTokenProvider, never()).generateToken(anyString());
    }

    @Test
    @DisplayName("登录失败-密码错误")
    void testLoginFailWrongPassword() {
        // Given
        loginDTO.setPassword("wrongpassword");
        when(userMapper.selectByUsername("admin")).thenReturn(testUser);

        // When & Then
        assertThrows(BusinessException.class, () -> {
            userService.login(loginDTO, "127.0.0.1");
        });

        verify(userMapper).selectByUsername("admin");
        verify(jwtTokenProvider, never()).generateToken(anyString());
    }

    @Test
    @DisplayName("获取当前用户测试")
    void testGetCurrentUser() {
        // Given
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");
        SecurityContextHolder.setContext(securityContext);

        when(userMapper.selectByUsername("admin")).thenReturn(testUser);

        // When
        UserVO result = userService.getCurrentUser();

        // Then
        assertNotNull(result);
        assertEquals("admin", result.getUsername());

        // Cleanup
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("获取当前用户-未登录")
    void testGetCurrentUserNotLogin() {
        // Given
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(null);
        SecurityContextHolder.setContext(securityContext);

        // When & Then
        assertThrows(BusinessException.class, () -> {
            userService.getCurrentUser();
        });

        // Cleanup
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("修改密码测试")
    void testChangePassword() {
        // Given
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");
        SecurityContextHolder.setContext(securityContext);

        when(userMapper.selectByUsername("admin")).thenReturn(testUser);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        // When
        userService.changePassword("admin123", "newpassword");

        // Then
        verify(userMapper).updateById(any(User.class));

        // Cleanup
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("修改密码-旧密码错误")
    void testChangePasswordWrongOldPassword() {
        // Given
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");
        SecurityContextHolder.setContext(securityContext);

        when(userMapper.selectByUsername("admin")).thenReturn(testUser);

        // When & Then
        assertThrows(BusinessException.class, () -> {
            userService.changePassword("wrongpassword", "newpassword");
        });

        // Cleanup
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("根据用户名获取用户测试")
    void testGetUserByUsername() {
        // Given
        when(userMapper.selectByUsername("admin")).thenReturn(testUser);

        // When
        UserVO result = userService.getUserByUsername("admin");

        // Then
        assertNotNull(result);
        assertEquals("admin", result.getUsername());
    }

    @Test
    @DisplayName("根据用户名获取用户-用户不存在")
    void testGetUserByUsernameNotFound() {
        // Given
        when(userMapper.selectByUsername("nonexistent")).thenReturn(null);

        // When & Then
        assertThrows(BusinessException.class, () -> {
            userService.getUserByUsername("nonexistent");
        });
    }

    @Test
    @DisplayName("登出测试")
    void testLogout() {
        // Given
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");
        SecurityContextHolder.setContext(securityContext);

        // When
        userService.logout();

        // Cleanup
        SecurityContextHolder.clearContext();
    }
}
