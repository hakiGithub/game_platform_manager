package com.gameplatform.controller;

import com.gameplatform.annotation.OperationLog;
import com.gameplatform.common.result.Result;
import com.gameplatform.config.JwtTokenProvider;
import com.gameplatform.dto.LoginDTO;
import com.gameplatform.service.UserService;
import com.gameplatform.vo.LoginVO;
import com.gameplatform.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 认证控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "认证管理", description = "认证相关接口")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;

    /**
     * 用户登录
     */
    @Operation(summary = "用户登录", description = "用户登录获取Token")
    @PostMapping("/login")
    @OperationLog(type = "LOGIN", target = "USER", description = "用户登录")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        String ipAddr = getClientIp(request);
        LoginVO loginVO = userService.login(dto, ipAddr);
        return Result.success(loginVO);
    }

    /**
     * 用户登出
     */
    @Operation(summary = "用户登出", description = "用户登出")
    @PostMapping("/logout")
    @OperationLog(type = "LOGOUT", target = "USER", description = "用户登出")
    public Result<Void> logout() {
        userService.logout();
        SecurityContextHolder.clearContext();
        return Result.success();
    }

    /**
     * 获取当前用户信息
     */
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户信息")
    @GetMapping("/info")
    public Result<UserVO> info() {
        UserVO userVO = userService.getCurrentUser();
        return Result.success(userVO);
    }

    /**
     * 修改密码
     */
    @Operation(summary = "修改密码", description = "修改当前用户密码")
    @PutMapping("/password")
    @OperationLog(type = "UPDATE", target = "USER", description = "修改密码")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(dto.getOldPassword(), dto.getNewPassword());
        return Result.success();
    }

    /**
     * 刷新Token
     */
    @Operation(summary = "刷新Token", description = "刷新Token")
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@RequestHeader("Authorization") String authorization) {
        String token = authorization.replace("Bearer ", "");
        String newToken = jwtTokenProvider.refreshToken(token);
        
        LoginVO loginVO = LoginVO.builder()
                .token(newToken)
                .tokenType("Bearer")
                .expiresIn(604800000L) // 7天
                .build();
        
        return Result.success(loginVO);
    }

    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个代理时取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    // ========== DTO ==========

    /**
     * 修改密码DTO
     */
    @Data
    public static class ChangePasswordDTO {
        /**
         * 旧密码
         */
        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;

        /**
         * 新密码
         */
        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }

}
