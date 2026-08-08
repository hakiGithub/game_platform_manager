package com.gameplatform.common.exception;

import com.gameplatform.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误消息
     */
    private final String message;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAILED.getCode();
        this.message = message;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.message = message;
    }

    /**
     * 快速创建业务异常
     */
    public static BusinessException of(String message) {
        return new BusinessException(message);
    }

    /**
     * 快速创建业务异常
     */
    public static BusinessException of(ResultCode resultCode) {
        return new BusinessException(resultCode);
    }

    /**
     * 快速创建业务异常
     */
    public static BusinessException of(Integer code, String message) {
        return new BusinessException(code, message);
    }

}
