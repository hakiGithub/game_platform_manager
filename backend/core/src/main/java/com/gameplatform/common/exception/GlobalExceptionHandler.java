package com.gameplatform.common.exception;

import com.gameplatform.common.result.Result;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.extension.exception.ExtensionStoreException;
import com.gameplatform.plugin.extension.exception.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: {} - {}", request.getRequestURI(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 数据库唯一约束冲突（兜底处理，业务层应优先捕获并转换为 BusinessException）
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDuplicateKeyException(org.springframework.dao.DuplicateKeyException e) {
        String message = e.getMessage();
        String userMessage = "数据已存在，请检查唯一性字段";
        // 尝试从 SQLite 错误信息中提取约束字段名
        if (message != null && message.contains("UNIQUE constraint failed:")) {
            int idx = message.indexOf("UNIQUE constraint failed:");
            String constraint = message.substring(idx + "UNIQUE constraint failed:".length()).trim();
            userMessage = "数据重复：" + constraint + " 已存在，请更换后重试";
        }
        log.warn("数据库唯一约束冲突: {}", userMessage);
        return Result.fail(ResultCode.CONFLICT.getCode(), userMessage);
    }

    /**
     * 数据完整性违反（外键约束、非空约束等）
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDataIntegrityViolationException(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("数据完整性违反: {}", e.getMessage());
        String message = e.getMessage();
        String userMessage = "数据完整性校验失败，请检查输入";
        if (message != null) {
            if (message.contains("FOREIGN KEY constraint failed")) {
                userMessage = "关联数据不存在，请检查主机、游戏等关联项";
            } else if (message.contains("NOT NULL constraint failed")) {
                int idx = message.indexOf("NOT NULL constraint failed:");
                if (idx >= 0) {
                    String field = message.substring(idx + "NOT NULL constraint failed:".length()).trim();
                    userMessage = "必填字段 " + field + " 不能为空";
                }
            }
        }
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), userMessage);
    }

    /**
     * 扩展资源已存在（create 时 name 冲突）
     */
    @ExceptionHandler(DuplicateExtensionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDuplicateExtensionException(DuplicateExtensionException e) {
        log.warn("扩展资源已存在: {}", e.getMessage());
        return Result.fail(ResultCode.CONFLICT.getCode(), e.getMessage());
    }

    /**
     * 扩展资源不存在
     */
    @ExceptionHandler(ExtensionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleExtensionNotFoundException(ExtensionNotFoundException e) {
        log.warn("扩展资源不存在: {}", e.getMessage());
        return Result.fail(ResultCode.NOT_FOUND.getCode(), e.getMessage());
    }

    /**
     * 扩展资源乐观锁冲突
     */
    @ExceptionHandler(OptimisticLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleOptimisticLockException(OptimisticLockException e) {
        log.warn("扩展资源版本冲突: {}", e.getMessage());
        return Result.fail(ResultCode.CONFLICT.getCode(), e.getMessage());
    }

    /**
     * 扩展资源存储异常（基类兜底）
     */
    @ExceptionHandler(ExtensionStoreException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleExtensionStoreException(ExtensionStoreException e) {
        log.error("扩展资源存储异常: {}", e.getMessage(), e);
        return Result.fail(ResultCode.INTERNAL_SERVER_ERROR.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常 - @Valid
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), message);
    }

    /**
     * 参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), message);
    }

    /**
     * 参数校验异常 - @Validated
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), message);
    }

    /**
     * 缺少请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), "缺少请求参数: " + e.getParameterName());
    }

    /**
     * 参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}", e.getName());
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), "参数类型不匹配: " + e.getName());
    }

    /**
     * 请求体解析失败
     * 提取具体的字段名和类型错误信息，便于调用方定位问题
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        String message = "请求体格式错误";
        Throwable cause = e.getCause();
        if (cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mie) {
            // 类型不匹配：提取字段路径、期望类型、实际类型
            String fieldPath = mie.getPath().isEmpty() ? "未知字段"
                    : mie.getPath().stream()
                            .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                            .collect(Collectors.joining("."));
            String targetType = mie.getTargetType() != null ? mie.getTargetType().getSimpleName() : "未知类型";
            message = String.format("字段「%s」类型错误：期望 %s，请检查请求体格式", fieldPath, targetType);
            log.warn("请求体解析失败 - 字段类型错误: {} (期望: {})", fieldPath, targetType);
        } else if (cause instanceof com.fasterxml.jackson.core.JsonParseException jpe) {
            message = "JSON 格式错误：" + jpe.getOriginalMessage();
            log.warn("请求体解析失败 - JSON 语法错误: {}", jpe.getOriginalMessage());
        } else {
            log.warn("请求体解析失败: {}", e.getMessage());
        }
        return Result.fail(ResultCode.VALIDATE_FAILED.getCode(), message);
    }

    /**
     * 请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMethod());
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 404异常
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.warn("资源不存在: {}", e.getRequestURL());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /**
     * 认证异常
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthenticationException(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        if (e instanceof BadCredentialsException) {
            return Result.fail(ResultCode.USER_PASSWORD_ERROR);
        }
        return Result.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 权限不足异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return Result.fail(ResultCode.FORBIDDEN);
    }

    /**
     * 其他未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常: {} - {}", request.getRequestURI(), e.getMessage(), e);
        return Result.fail(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "系统异常,请联系管理员");
    }

}
