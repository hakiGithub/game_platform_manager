package com.gameplatform.aspect;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.gameplatform.annotation.OperationLog;
import com.gameplatform.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志切面
 * 自动记录操作日志到数据库
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final LogService logService;

    /**
     * 环绕通知，记录操作日志
     */
    @Around("@annotation(com.gameplatform.annotation.OperationLog)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long startTime = System.currentTimeMillis();

        // 获取注解信息
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        OperationLog operationLog = method.getAnnotation(OperationLog.class);

        // 获取请求信息
        HttpServletRequest request = getRequest();
        String ipAddress = getIpAddress(request);

        // 获取当前用户
        String operator = getCurrentUser();

        // 构建日志信息
        String operationType = operationLog.type();
        String operationTarget = operationLog.target();
        String operationContent = buildOperationContent(point, operationLog);

        // 执行方法
        Object result = null;
        String operationResult = "success";
        String errorMessage = null;

        try {
            result = point.proceed();
        } catch (Throwable e) {
            operationResult = "fail";
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long endTime = System.currentTimeMillis();

            // 异步记录日志
            try {
                recordLogAsync(operator, operationType, operationTarget,
                        operationContent, operationResult, ipAddress, errorMessage);
            } catch (Exception e) {
                log.error("记录操作日志失败: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * 异步记录日志
     */
    @Async
    public void recordLogAsync(String operator, String operationType, String operationTarget,
                                String operationContent, String operationResult,
                                String ipAddress, String errorMessage) {
        try {
            logService.log(operator, operationType, operationTarget,
                    operationContent, operationResult, ipAddress, errorMessage);
        } catch (Exception e) {
            log.error("异步记录操作日志失败: {}", e.getMessage());
        }
    }

    /**
     * 获取当前请求
     */
    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    /**
     * 获取客户端IP地址
     */
    private String getIpAddress(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        String ip = request.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // 多个代理时取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }

    /**
     * 获取当前用户
     */
    private String getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                return authentication.getName();
            }
        } catch (Exception e) {
            log.debug("获取当前用户失败: {}", e.getMessage());
        }
        return "system";
    }

    /**
     * 构建操作内容
     */
    private String buildOperationContent(ProceedingJoinPoint point, OperationLog operationLog) {
        try {
            Map<String, Object> content = new HashMap<>();

            // 添加操作描述
            if (StrUtil.isNotBlank(operationLog.description())) {
                content.put("description", operationLog.description());
            }

            // 添加方法参数
            if (operationLog.recordParams()) {
                MethodSignature signature = (MethodSignature) point.getSignature();
                String[] paramNames = signature.getParameterNames();
                Object[] args = point.getArgs();

                if (paramNames != null && args != null && paramNames.length > 0) {
                    Map<String, Object> params = new HashMap<>();
                    for (int i = 0; i < paramNames.length; i++) {
                        // 过滤敏感参数和文件参数
                        Object arg = args[i];
                        if (arg instanceof MultipartFile) {
                            params.put(paramNames[i], "[FILE]");
                        } else if (arg instanceof HttpServletRequest) {
                            params.put(paramNames[i], "[REQUEST]");
                        } else if (paramNames[i].toLowerCase().contains("password")
                                || paramNames[i].toLowerCase().contains("secret")
                                || paramNames[i].toLowerCase().contains("key")
                                || paramNames[i].toLowerCase().contains("token")) {
                            params.put(paramNames[i], "[PROTECTED]");
                        } else {
                            try {
                                // 限制参数值长度
                                String jsonStr = JSONUtil.toJsonStr(arg);
                                if (jsonStr.length() > 500) {
                                    jsonStr = jsonStr.substring(0, 500) + "...";
                                }
                                params.put(paramNames[i], jsonStr);
                            } catch (Exception e) {
                                params.put(paramNames[i], arg != null ? arg.toString() : "null");
                            }
                        }
                    }
                    content.put("params", params);
                }
            }

            return JSONUtil.toJsonStr(content);
        } catch (Exception e) {
            log.warn("构建操作内容失败: {}", e.getMessage());
            return operationLog.description();
        }
    }

}
