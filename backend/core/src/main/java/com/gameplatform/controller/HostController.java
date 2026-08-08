package com.gameplatform.controller;

import com.gameplatform.annotation.OperationLog;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.dto.HostCreateDTO;
import com.gameplatform.dto.HostUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.Host;
import com.gameplatform.service.HostService;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.HostVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主机管理控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "主机管理", description = "主机相关接口")
@Slf4j
@RestController
@RequestMapping("/hosts")
@RequiredArgsConstructor
@Validated
public class HostController {

    private final HostService hostService;
    private final GamePlatformConfig gamePlatformConfig;
    private final SshUtil sshUtil;
    private final com.gameplatform.service.HostsFileRefresher hostsFileRefresher;

    /**
     * 获取主机列表(分页)
     */
    @Operation(summary = "获取主机列表", description = "分页获取主机列表")
    @GetMapping
    public Result<PageResult<HostVO>> list(PageQueryDTO queryDTO) {
        PageResult<HostVO> result = hostService.pageHosts(queryDTO);
        return Result.success(result);
    }

    /**
     * 获取主机详情
     */
    @Operation(summary = "获取主机详情", description = "根据ID获取主机详情")
    @GetMapping("/{id}")
    public Result<HostVO> getById(@Parameter(description = "主机ID") @PathVariable Long id) {
        HostVO hostVO = hostService.getHostById(id);
        return Result.success(hostVO);
    }

    /**
     * 新增主机
     */
    @Operation(summary = "新增主机", description = "新增主机,SSH私钥加密存储")
    @PostMapping
    @OperationLog(type = "CREATE", target = "HOST", description = "新增主机")
    public Result<HostVO> create(@Valid @RequestBody HostCreateDTO dto) {
        // 加密SSH私钥
        if (dto.getSshPrivateKey() != null && !dto.getSshPrivateKey().isEmpty()) {
            dto.setSshPrivateKey(AesUtil.encrypt(dto.getSshPrivateKey()));
        }
        HostVO hostVO = hostService.createHost(dto);
        return Result.success(hostVO);
    }

    /**
     * 更新主机
     */
    @Operation(summary = "更新主机", description = "更新主机信息")
    @PutMapping("/{id}")
    @OperationLog(type = "UPDATE", target = "HOST", description = "更新主机")
    public Result<HostVO> update(@Parameter(description = "主机ID") @PathVariable Long id,
                                  @Valid @RequestBody HostUpdateDTO dto) {
        dto.setId(id);
        // 加密SSH私钥
        if (dto.getSshPrivateKey() != null && !dto.getSshPrivateKey().isEmpty()) {
            // 判断是否已加密
            if (!AesUtil.isEncrypted(dto.getSshPrivateKey())) {
                dto.setSshPrivateKey(AesUtil.encrypt(dto.getSshPrivateKey()));
            }
        }
        HostVO hostVO = hostService.updateHost(dto);
        return Result.success(hostVO);
    }

    /**
     * 删除主机
     */
    @Operation(summary = "删除主机", description = "删除主机")
    @DeleteMapping("/{id}")
    @OperationLog(type = "DELETE", target = "HOST", description = "删除主机")
    public Result<Void> delete(@Parameter(description = "主机ID") @PathVariable Long id) {
        hostService.deleteHost(id);
        return Result.success();
    }

    /**
     * 测试主机SSH连接
     */
    @Operation(summary = "测试SSH连接", description = "测试主机SSH连接是否正常")
    @PostMapping("/{id}/test")
    @OperationLog(type = "TEST", target = "HOST", description = "测试SSH连接")
    public Result<ConnectionTestResult> testConnection(@Parameter(description = "主机ID") @PathVariable Long id) {
        HostVO host = hostService.getHostById(id);
        if (host == null) {
            return Result.fail("主机不存在");
        }

        // 解密SSH私钥
        String privateKey = null;
        // 需要从数据库获取加密的私钥,这里简化处理

        boolean connected = hostService.testConnection(id);

        ConnectionTestResult result = new ConnectionTestResult();
        result.setConnected(connected);
        result.setMessage(connected ? "连接成功" : "连接失败");
        result.setTestTime(System.currentTimeMillis());

        return Result.success(result);
    }

    /**
     * 获取主机在线状态
     */
    @Operation(summary = "获取主机在线状态", description = "获取主机在线状态")
    @GetMapping("/{id}/status")
    public Result<HostStatusVO> getStatus(@Parameter(description = "主机ID") @PathVariable Long id) {
        HostVO host = hostService.getHostById(id);
        if (host == null) {
            return Result.fail("主机不存在");
        }

        // 刷新状态
        hostService.refreshStatus(id);
        host = hostService.getHostById(id);

        HostStatusVO statusVO = new HostStatusVO();
        statusVO.setId(host.getId());
        statusVO.setStatus(host.getStatus());
        statusVO.setOnlineStatusDesc(host.getOnlineStatusDesc());
        statusVO.setCpuUsage(host.getCpuUsage());
        statusVO.setMemoryUsage(host.getMemoryUsage());
        statusVO.setDiskUsage(host.getDiskUsage());
        statusVO.setLastCheckTime(host.getLastCheckTime());

        return Result.success(statusVO);
    }

    /**
     * 扫描端口占用情况
     */
    @Operation(summary = "扫描端口占用", description = "扫描主机端口占用情况")
    @GetMapping("/{id}/ports")
    public Result<List<PortInfoVO>> scanPorts(@Parameter(description = "主机ID") @PathVariable Long id,
                                               @Parameter(description = "端口范围") @RequestParam(required = false, defaultValue = "1-65535") String portRange) {
        HostVO host = hostService.getHostById(id);
        if (host == null) {
            return Result.fail("主机不存在");
        }

        // TODO 需要通过SSH执行命令扫描端口
        // 这里返回模拟数据
        return Result.success(List.of());
    }

    /**
     * 检查指定端口是否可用
     */
    @Operation(summary = "检查端口占用", description = "通过SSH检查主机上指定端口是否被占用")
    @GetMapping("/{id}/check-port")
    public Result<CheckPortResult> checkPort(@Parameter(description = "主机ID") @PathVariable Long id,
                                              @Parameter(description = "端口号") @RequestParam int port) {
        SshUtil.PortCheckResult result = hostService.checkPort(id, port);

        CheckPortResult vo = new CheckPortResult();
        vo.setAvailable(result.isAvailable());
        vo.setUsedBy(result.getUsedBy());
        return Result.success(vo);
    }

    /**
     * 获取主机资源使用情况
     */
    @Operation(summary = "获取主机资源使用情况", description = "获取主机CPU、内存、磁盘、网络使用情况")
    @GetMapping("/{id}/resources")
    public Result<HostResourceVO> getResources(@Parameter(description = "主机ID") @PathVariable Long id) {
        HostResourceVO resourceVO = hostService.getHostResourceInfo(id);
        if (resourceVO == null) {
            return Result.fail("主机不存在或获取资源信息失败");
        }
        return Result.success(resourceVO);
    }

    /**
     * 预检 hosts 刷新：返回待修改域名清单 + sudo 状态
     */
    @Operation(summary = "预检 hosts 刷新", description = "读取 /etc/hosts 并识别待修改域名，不写入")
    @GetMapping("/{id}/hosts-preview")
    public Result<com.gameplatform.vo.HostsRefreshPreview> previewHostsRefresh(
            @Parameter(description = "主机ID") @PathVariable Long id) {
        try {
            com.gameplatform.vo.HostsRefreshPreview preview = hostsFileRefresher.previewRefresh(id);
            return Result.success(preview);
        } catch (Exception e) {
            log.error("预检 hosts 刷新失败: hostId={}", id, e);
            return Result.fail("预检失败: " + e.getMessage());
        }
    }

    /**
     * 执行 hosts 刷新
     */
    @Operation(summary = "执行 hosts 刷新", description = "将 127.0.0.1 域名改为宿主机 LAN IP")
    @PostMapping("/{id}/hosts-refresh")
    @OperationLog(type = "UPDATE", target = "HOST", description = "刷新宿主机 hosts")
    public Result<com.gameplatform.vo.HostsRefreshResult> refreshHosts(
            @Parameter(description = "主机ID") @PathVariable Long id,
            @RequestBody HostsRefreshRequest request) {
        try {
            com.gameplatform.vo.HostsRefreshResult result =
                    hostsFileRefresher.refreshHosts(id, request.getSudoPassword(), request.getSelectedDomains());
            if (result.isSuccess()) {
                return Result.success(result);
            } else {
                return Result.fail(result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("执行 hosts 刷新失败: hostId={}", id, e);
            return Result.fail("刷新失败: " + e.getMessage());
        }
    }

    // ========== VO ==========

    /**
     * 连接测试结果
     */
    @Data
    public static class ConnectionTestResult {
        /**
         * 是否连接成功
         */
        private Boolean connected;

        /**
         * 消息
         */
        private String message;

        /**
         * 测试时间
         */
        private Long testTime;
    }

    /**
     * 主机状态VO
     */
    @Data
    public static class HostStatusVO {
        /**
         * 主机ID
         */
        private Long id;

        /**
         * 在线状态 0-离线 1-在线
         */
        private Integer status;

        /**
         * 在线状态描述
         */
        private String onlineStatusDesc;

        /**
         * CPU使用率
         */
        private BigDecimal cpuUsage;

        /**
         * 内存使用率
         */
        private BigDecimal memoryUsage;

        /**
         * 磁盘使用率
         */
        private BigDecimal diskUsage;

        /**
         * 最后检测时间
         */
        private java.time.LocalDateTime lastCheckTime;
    }

    /**
     * 端口信息VO
     */
    @Data
    public static class PortInfoVO {
        /**
         * 端口号
         */
        private Integer port;

        /**
         * 协议
         */
        private String protocol;

        /**
         * 状态
         */
        private String state;

        /**
         * 进程名
         */
        private String processName;
    }

    /**
     * 端口检查结果VO
     */
    @Data
    public static class CheckPortResult {
        /**
         * 是否可用(未被占用)
         */
        private Boolean available;

        /**
         * 占用进程名(不可用时)
         */
        private String usedBy;
    }

    /**
     * 资源信息VO
     */
    @Data
    public static class ResourceInfoVO {
        /**
         * 主机ID
         */
        private Long hostId;

        /**
         * 主机名称
         */
        private String name;

        /**
         * CPU使用率
         */
        private BigDecimal cpuUsage;

        /**
         * 内存使用率
         */
        private BigDecimal memoryUsage;

        /**
         * 磁盘使用率
         */
        private BigDecimal diskUsage;

        /**
         * 在线状态
         */
        private Integer status;

        /**
         * 检测时间
         */
        private java.time.LocalDateTime checkTime;
    }

    /**
     * hosts 刷新请求体
     */
    @Data
    public static class HostsRefreshRequest {
        /**
         * sudo 密码（免密 sudo 时为 null）
         */
        private String sudoPassword;

        /**
         * 选中的待改域名清单（null/空 表示刷新全部候选域名；非空表示只刷新指定域名，用于跳过广告屏蔽条目）
         */
        private java.util.List<String> selectedDomains;
    }

}
