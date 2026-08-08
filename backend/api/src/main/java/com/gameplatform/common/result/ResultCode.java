package com.gameplatform.common.result;

import lombok.Getter;

/**
 * 响应状态码枚举
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Getter
public enum ResultCode {

    // 成功
    SUCCESS(200, "操作成功"),

    // 客户端错误 4xx
    FAILED(400, "操作失败"),
    VALIDATE_FAILED(400, "参数校验失败"),
    UNAUTHORIZED(401, "未授权,请先登录"),
    TOKEN_EXPIRED(401, "Token已过期,请重新登录"),
    TOKEN_INVALID(401, "Token无效"),
    FORBIDDEN(403, "没有相关权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    CONFLICT(409, "资源冲突"),

    // 服务端错误 5xx
    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),

    // 业务错误 1xxx
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_PASSWORD_ERROR(1002, "密码错误"),
    USER_DISABLED(1003, "用户已被禁用"),
    USER_ALREADY_EXISTS(1004, "用户已存在"),
    
    HOST_NOT_FOUND(1101, "主机不存在"),
    HOST_CONNECTION_FAILED(1102, "主机连接失败"),
    HOST_ALREADY_EXISTS(1103, "主机已存在"),
    
    GAME_INSTANCE_NOT_FOUND(1201, "游戏实例不存在"),
    GAME_INSTANCE_ALREADY_RUNNING(1202, "游戏实例已在运行"),
    GAME_INSTANCE_NOT_RUNNING(1203, "游戏实例未运行"),
    
    DEPLOY_FAILED(1301, "部署失败"),
    DEPLOY_CONFIG_ERROR(1302, "部署配置错误"),
    
    BACKUP_NOT_FOUND(1401, "备份不存在"),
    BACKUP_FAILED(1402, "备份失败"),
    RESTORE_FAILED(1403, "恢复失败"),
    
    PLUGIN_NOT_FOUND(1501, "插件不存在"),
    PLUGIN_LOAD_FAILED(1502, "插件加载失败"),
    PLUGIN_ALREADY_EXISTS(1503, "插件已存在"),
    
    FILE_NOT_FOUND(1601, "文件不存在"),
    FILE_UPLOAD_FAILED(1602, "文件上传失败"),
    FILE_DOWNLOAD_FAILED(1603, "文件下载失败"),
    
    // Docker相关错误 17xx
    CONTAINER_NOT_FOUND(1701, "容器不存在"),
    CONTAINER_OPERATION_FAILED(1702, "容器操作失败"),
    CONTAINER_ALREADY_RUNNING(1703, "容器已在运行"),
    CONTAINER_NOT_RUNNING(1704, "容器未运行"),
    CONTAINER_LINK_NOT_FOUND(1705, "容器关联不存在"),
    CONTAINER_LINK_ALREADY_EXISTS(1706, "容器关联已存在"),
    IMAGE_NOT_FOUND(1711, "镜像不存在"),
    IMAGE_DELETE_FAILED(1712, "镜像删除失败"),
    IMAGE_IN_USE(1713, "镜像正在使用中"),
    DOCKER_CONNECTION_FAILED(1721, "Docker连接失败"),
    DOCKER_EXEC_FAILED(1722, "Docker命令执行失败");

    /**
     * 响应码
     */
    private final Integer code;

    /**
     * 响应消息
     */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

}
