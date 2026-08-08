package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

/**
 * 主机创建请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "主机创建请求DTO")
public class HostCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主机名称
     */
    @Schema(description = "主机名称", required = true, example = "游戏服务器1")
    @NotBlank(message = "主机名称不能为空")
    private String name;

    /**
     * IP地址
     */
    @Schema(description = "IP地址", required = true, example = "192.168.1.100")
    @NotBlank(message = "IP地址不能为空")
    @Pattern(regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$", message = "IP地址格式不正确")
    private String ip;

    /**
     * SSH端口
     */
    @Schema(description = "SSH端口", example = "22")
    @NotNull(message = "SSH端口不能为空")
    private Integer sshPort = 22;

    /**
     * SSH用户名
     */
    @Schema(description = "SSH用户名", required = true, example = "root")
    @NotBlank(message = "SSH用户名不能为空")
    private String sshUsername;

    /**
     * SSH密码
     */
    @Schema(description = "SSH密码")
    private String sshPassword;

    /**
     * SSH私钥
     */
    @Schema(description = "SSH私钥")
    private String sshPrivateKey;

    /**
     * 标签(JSON数组格式)
     */
    @Schema(description = "标签(JSON数组格式)", example = "[\"游戏服务器\",\"测试环境\"]")
    private String tags;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
