package com.gameplatform.controller;

import com.gameplatform.annotation.OperationLog;
import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.dto.InstanceCreateDTO;
import com.gameplatform.dto.InstanceUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.service.DeployService;
import com.gameplatform.service.FileService;
import com.gameplatform.service.HostService;
import com.gameplatform.service.InstanceService;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 游戏实例控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "实例管理", description = "游戏实例相关接口")
@RestController
@RequestMapping("/instances")
@RequiredArgsConstructor
@Validated
public class InstanceController {

    private final InstanceService instanceService;
    private final HostService hostService;
    private final GamePlatformConfig gamePlatformConfig;
    private final FileService fileService;
    private final DeployService deployService;
    private final com.gameplatform.mapper.HostMapper hostMapper;

    /**
     * 获取实例列表(分页)
     */
    @Operation(summary = "获取实例列表", description = "分页获取游戏实例列表")
    @GetMapping
    public Result<PageResult<InstanceVO>> list(PageQueryDTO queryDTO) {
        PageResult<InstanceVO> result = instanceService.pageInstances(queryDTO);
        return Result.success(result);
    }

    /**
     * 获取实例详情（静态数据）
     * <p>
     * 仅返回实例基础信息、配置、状态等静态数据，不包含 CPU/内存/运行时长等动态资源数据。
     * 动态数据请通过 GET /instances/{id}/metrics 接口异步拉取。
     */
    @Operation(summary = "获取实例详情", description = "根据ID获取实例详情（静态数据）")
    @GetMapping("/{id}")
    public Result<InstanceVO> getById(@Parameter(description = "实例ID") @PathVariable Long id) {
        InstanceVO instanceVO = instanceService.getInstanceById(id);
        return Result.success(instanceVO);
    }

    /**
     * 获取实例动态资源数据
     * <p>
     * 返回 CPU 使用率、内存使用率、内存使用文本、运行时长、在线玩家数等实时数据。
     * 该接口可能涉及 SSH/Docker 调用，响应时间较长（数百毫秒至数秒）。
     * 前端应异步调用，不要阻塞页面首屏渲染。
     */
    @Operation(summary = "获取实例动态资源数据", description = "拉取 CPU/内存/运行时长等实时数据")
    @GetMapping("/{id}/metrics")
    public Result<java.util.Map<String, Object>> getMetrics(
            @Parameter(description = "实例ID") @PathVariable Long id) {
        return Result.success(instanceService.getInstanceMetrics(id));
    }

    /**
     * 创建实例(部署游戏)
     */
    @Operation(summary = "创建实例", description = "创建游戏实例并部署")
    @PostMapping
    @OperationLog(type = "CREATE", target = "INSTANCE", description = "创建游戏实例")
    public Result<InstanceVO> create(@Valid @RequestBody InstanceCreateDTO dto) {
        InstanceVO instanceVO = instanceService.createInstance(dto);
        return Result.success(instanceVO);
    }

    /**
     * 更新实例配置
     */
    @Operation(summary = "更新实例配置", description = "更新游戏实例配置")
    @PutMapping("/{id}")
    @OperationLog(type = "UPDATE", target = "INSTANCE", description = "更新实例配置")
    public Result<InstanceVO> update(@Parameter(description = "实例ID") @PathVariable Long id,
                                      @Valid @RequestBody InstanceUpdateDTO dto) {
        dto.setId(id);
        InstanceVO instanceVO = instanceService.updateInstance(dto);
        return Result.success(instanceVO);
    }

    /**
     * 删除实例(卸载)
     */
    @Operation(summary = "删除实例", description = "删除游戏实例并卸载")
    @DeleteMapping("/{id}")
    @OperationLog(type = "DELETE", target = "INSTANCE", description = "删除游戏实例")
    public Result<Void> delete(@Parameter(description = "实例ID") @PathVariable Long id) {
        instanceService.deleteInstance(id);
        return Result.success();
    }

    /**
     * 环境校验
     */
    @Operation(summary = "环境校验", description = "部署前对目标主机的环境进行检查")
    @PostMapping("/check-environment")
    public Result<EnvironmentCheckResultVO> checkEnvironment(@RequestBody EnvironmentCheckRequestDTO dto) {
        DeployAdapter.DeployType deployType = DeployAdapter.DeployType.fromCode(dto.getDeployMethod());
        if (deployType == null) {
            return Result.fail("不支持的部署方式: " + dto.getDeployMethod());
        }

        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("port", dto.getPort());

        DeployService.EnvironmentCheckResult result =
                deployService.checkEnvironment(dto.getHostId(), deployType, config);

        EnvironmentCheckResultVO vo = new EnvironmentCheckResultVO();
        vo.setPassed(result.isPassed());

        // 将后端 Map<String, Boolean> 转换为前端期望的列表结构
        java.util.Map<String, Boolean> checks = result.getChecks();
        if (checks != null) {
            java.util.List<EnvironmentCheckItemVO> items = new java.util.ArrayList<>();
            checks.forEach((key, passed) -> {
                EnvironmentCheckItemVO item = new EnvironmentCheckItemVO();
                item.setKey(mapCheckKey(key));
                item.setLabel(mapCheckLabel(key));
                item.setPassed(passed);
                item.setMessage(passed ? mapCheckSuccessMessage(key) : mapCheckFailMessage(key));
                items.add(item);
            });
            vo.setChecks(items);
        } else {
            vo.setChecks(java.util.Collections.emptyList());
        }

        return Result.success(vo);
    }

    /**
     * 将后端检查项 key 映射为前端期望的 key
     */
    private String mapCheckKey(String backendKey) {
        switch (backendKey) {
            case "sshConnection":
                return "dependencies";
            case "dockerInstalled":
            case "dockerRunning":
            case "dockerComposeInstalled":
                return "docker";
            case "diskSpace":
                return "disk";
            case "memory":
                return "memory";
            case "ports":
                return "port";
            default:
                return backendKey;
        }
    }

    /**
     * 映射检查项标签
     */
    private String mapCheckLabel(String backendKey) {
        switch (backendKey) {
            case "sshConnection":
                return "SSH连接";
            case "dockerInstalled":
                return "Docker已安装";
            case "dockerRunning":
                return "Docker运行中";
            case "dockerComposeInstalled":
                return "Docker Compose已安装";
            case "diskSpace":
                return "磁盘空间";
            case "memory":
                return "内存资源";
            case "ports":
                return "端口可用性";
            default:
                return backendKey;
        }
    }

    /**
     * 映射检查成功消息
     */
    private String mapCheckSuccessMessage(String backendKey) {
        switch (backendKey) {
            case "sshConnection":
                return "SSH连接正常";
            case "dockerInstalled":
                return "Docker已安装";
            case "dockerRunning":
                return "Docker服务运行中";
            case "dockerComposeInstalled":
                return "Docker Compose已安装";
            case "diskSpace":
                return "磁盘空间充足";
            case "memory":
                return "内存资源充足";
            case "ports":
                return "端口可用";
            default:
                return "检查通过";
        }
    }

    /**
     * 映射检查失败消息
     */
    private String mapCheckFailMessage(String backendKey) {
        switch (backendKey) {
            case "sshConnection":
                return "SSH连接失败，请检查主机连接信息";
            case "dockerInstalled":
                return "Docker未安装";
            case "dockerRunning":
                return "Docker服务未运行";
            case "dockerComposeInstalled":
                return "Docker Compose未安装";
            case "diskSpace":
                return "磁盘空间不足";
            case "memory":
                return "内存资源不足";
            case "ports":
                return "端口被占用";
            default:
                return "检查未通过";
        }
    }

    // ========== 环境校验 DTO/VO ==========

    /**
     * 环境校验请求DTO
     */
    @Data
    public static class EnvironmentCheckRequestDTO {
        private Long hostId;
        private Integer port;
        private String deployMethod;
        private Long gameId;
    }

    /**
     * 环境校验结果VO
     */
    @Data
    public static class EnvironmentCheckResultVO {
        private Boolean passed;
        private java.util.List<EnvironmentCheckItemVO> checks;
    }

    /**
     * 环境校验项VO
     */
    @Data
    public static class EnvironmentCheckItemVO {
        private String key;
        private String label;
        private Boolean passed;
        private String message;
    }

    /**
     * 启动实例
     */
    @Operation(summary = "启动实例", description = "启动游戏实例")
    @PostMapping("/{id}/start")
    @OperationLog(type = "START", target = "INSTANCE", description = "启动游戏实例")
    public Result<OperationResultVO> start(@Parameter(description = "实例ID") @PathVariable Long id) {
        boolean success = instanceService.startInstance(id);
        OperationResultVO result = new OperationResultVO();
        result.setSuccess(success);
        result.setMessage(success ? "启动成功" : "启动失败");
        return Result.success(result);
    }

    /**
     * 停止实例
     */
    @Operation(summary = "停止实例", description = "停止游戏实例")
    @PostMapping("/{id}/stop")
    @OperationLog(type = "STOP", target = "INSTANCE", description = "停止游戏实例")
    public Result<OperationResultVO> stop(@Parameter(description = "实例ID") @PathVariable Long id) {
        boolean success = instanceService.stopInstance(id);
        OperationResultVO result = new OperationResultVO();
        result.setSuccess(success);
        result.setMessage(success ? "停止成功" : "停止失败");
        return Result.success(result);
    }

    /**
     * 重启实例
     */
    @Operation(summary = "重启实例", description = "重启游戏实例")
    @PostMapping("/{id}/restart")
    @OperationLog(type = "RESTART", target = "INSTANCE", description = "重启游戏实例")
    public Result<OperationResultVO> restart(@Parameter(description = "实例ID") @PathVariable Long id) {
        boolean success = instanceService.restartInstance(id);
        OperationResultVO result = new OperationResultVO();
        result.setSuccess(success);
        result.setMessage(success ? "重启成功" : "重启失败");
        return Result.success(result);
    }

    /**
     * 获取实例状态
     */
    @Operation(summary = "获取实例状态", description = "获取游戏实例运行状态")
    @GetMapping("/{id}/status")
    public Result<InstanceVO> getStatus(@Parameter(description = "实例ID") @PathVariable Long id) {
        InstanceVO instanceVO = instanceService.getInstanceStatus(id);
        return Result.success(instanceVO);
    }

    @Operation(summary = "获取实例日志", description = "获取游戏实例运行日志")
    @GetMapping("/{id}/logs")
    public Result<LogResultVO> getLogs(@Parameter(description = "实例ID") @PathVariable Long id,
                                        @Parameter(description = "日志行数") @RequestParam(defaultValue = "100") Integer lines,
                                        @Parameter(description = "日志类型") @RequestParam(defaultValue = "stdout") String type) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        LogResultVO logResult = new LogResultVO();
        logResult.setInstanceId(id);
        logResult.setLines(lines);

        // 安装中（INSTALLING）：返回 DeployService 内存中的部署日志
        if (instance.getRunStatus() != null
                && instance.getRunStatus() == DeployAdapter.InstanceStatus.INSTALLING.getCode()) {
            DeployService.DeployTaskStatus taskStatus = deployService.getTaskStatus(id);
            if (taskStatus != null) {
                StringBuilder sb = new StringBuilder();
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
                for (DeployService.LogEntry le : taskStatus.getLogs()) {
                    sb.append("[").append(le.getTime() != null ? le.getTime().format(fmt) : "").append("] ")
                      .append("[").append(le.getLevel()).append("] ")
                      .append(le.getMessage()).append("\n");
                }
                logResult.setContent(sb.toString());
                return Result.success(logResult);
            }
        }

        // 运行中/已停止/异常：通过适配器获取容器/进程日志
        String logContent = instanceService.getInstanceLogs(id, lines);
        logResult.setContent(logContent);

        return Result.success(logResult);
    }

    /**
     * 获取部署进度
     */
    @Operation(summary = "获取部署进度", description = "获取实例部署任务的实时进度和日志")
    @GetMapping("/{id}/deploy-progress")
    public Result<DeployProgressVO> getDeployProgress(@Parameter(description = "实例ID") @PathVariable Long id) {
        DeployService.DeployTaskStatus status = deployService.getTaskStatus(id);
        if (status == null) {
            return Result.fail("部署任务不存在或已完成清理");
        }

        DeployProgressVO vo = new DeployProgressVO();
        vo.setProgress(status.getProgress());
        vo.setStatus(status.getStatus());
        vo.setStatusText(mapStatusText(status.getStatus()));
        vo.setCompleted(status.isCompleted());
        vo.setSuccess(status.isSuccess());
        vo.setError(status.getError());

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        List<LogEntryVO> logVOs = status.getLogs().stream().map(le -> {
            LogEntryVO leVo = new LogEntryVO();
            leVo.setId(le.getId());
            leVo.setLevel(le.getLevel());
            leVo.setMessage(le.getMessage());
            leVo.setStage(le.getStage());
            leVo.setTime(le.getTime() != null ? le.getTime().format(fmt) : "");
            return leVo;
        }).toList();
        vo.setLogs(logVOs);

        return Result.success(vo);
    }

    /**
     * 将 status 字符串映射为中文描述
     */
    private String mapStatusText(String status) {
        if (status == null) return "处理中";
        return switch (status) {
            case "pending" -> "等待中";
            case "preparing" -> "准备中";
            case "downloading" -> "下载中";
            case "installing" -> "安装中";
            case "configuring" -> "配置中";
            case "starting" -> "启动中";
            case "checking" -> "健康检查中";
            case "completed" -> "已完成";
            case "failed" -> "失败";
            default -> "处理中";
        };
    }

    /**
     * 重试部署
     */
    @Operation(summary = "重试部署", description = "对异常状态的实例重新触发部署")
    @PostMapping("/{id}/retry-deploy")
    @OperationLog(type = "DEPLOY", target = "INSTANCE", description = "重试部署实例")
    public Result<Void> retryDeploy(@Parameter(description = "实例ID") @PathVariable Long id) {
        instanceService.retryDeploy(id);
        return Result.success();
    }

    /**
     * 手动恢复中断的部署任务
     * 将所有 run_status=INSTALLING（5，安装中）的实例标记为 ERROR（4，异常）
     */
    @Operation(summary = "恢复中断的部署任务", description = "将所有部署中状态的实例标记为异常，用于清理因应用崩溃遗留的部署状态")
    @PostMapping("/recover-deploying")
    @OperationLog(type = "DEPLOY", target = "INSTANCE", description = "恢复中断的部署任务")
    public Result<Integer> recoverDeployingInstances() {
        int count = instanceService.recoverDeployingInstances();
        return Result.success(count);
    }

    /**
     * 获取实例配置
     */
    @Operation(summary = "获取实例配置", description = "获取游戏实例配置文件内容")
    @GetMapping("/{id}/config")
    public Result<ConfigResultVO> getConfig(@Parameter(description = "实例ID") @PathVariable Long id,
                                             @Parameter(description = "配置文件路径") @RequestParam(required = false) String path) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        ConfigResultVO configResult = new ConfigResultVO();
        configResult.setInstanceId(id);
        configResult.setConfigInfo(instance.getConfigInfo());
        // 实际应通过SSH读取配置文件

        return Result.success(configResult);
    }

    /**
     * 更新实例配置
     */
    @Operation(summary = "更新实例配置", description = "更新游戏实例配置文件")
    @PutMapping("/{id}/config")
    @OperationLog(type = "UPDATE", target = "INSTANCE", description = "更新实例配置文件")
    public Result<Void> updateConfig(@Parameter(description = "实例ID") @PathVariable Long id,
                                      @RequestBody Map<String, Object> config) {
        InstanceUpdateDTO dto = new InstanceUpdateDTO();
        dto.setId(id);
        dto.setConfigInfo(config);
        instanceService.updateInstance(dto);
        return Result.success();
    }

    /**
     * 获取文件列表
     */
    @Operation(summary = "获取文件列表", description = "获取实例目录下的文件列表")
    @GetMapping("/{id}/files")
    public Result<List<FileInfoVO>> listFiles(@Parameter(description = "实例ID") @PathVariable Long id,
                                               @Parameter(description = "目录路径") @RequestParam(defaultValue = "/") String path) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        // 构建完整路径：安装路径 + 相对路径（兼容历史 ~ 未展开的安装路径）
        String installPath = resolveInstallPath(instance);
        String fullPath;
        if (path == null || path.isEmpty() || "/".equals(path)) {
            fullPath = installPath;
        } else {
            // 确保路径拼接正确
            if (installPath.endsWith("/")) {
                fullPath = installPath + (path.startsWith("/") ? path.substring(1) : path);
            } else {
                fullPath = installPath + (path.startsWith("/") ? path : "/" + path);
            }
        }

        // 通过SFTP获取文件列表
        List<FileService.FileInfo> fileInfos = fileService.listFiles(instance.getHostId(), fullPath);

        // 转换为VO
        List<FileInfoVO> voList = fileInfos.stream().map(info -> {
            FileInfoVO vo = new FileInfoVO();
            vo.setName(info.getName());
            // 返回相对路径，去掉安装路径前缀
            String relativePath = info.getPath().substring(installPath.length());
            vo.setPath(relativePath.isEmpty() ? "/" : relativePath);
            vo.setIsDirectory(info.isDirectory());
            vo.setSize(info.getSize());
            vo.setLastModified(info.getLastModified());
            return vo;
        }).toList();

        return Result.success(voList);
    }

    /**
     * 下载文件
     */
    @Operation(summary = "下载文件", description = "下载实例文件")
    @GetMapping("/{id}/files/download")
    public ResponseEntity<byte[]> downloadFile(@Parameter(description = "实例ID") @PathVariable Long id,
                                                @Parameter(description = "文件路径") @RequestParam String path) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }

        // 构建完整路径
        String installPath = resolveInstallPath(instance);
        String fullPath;
        if (path == null || path.isEmpty() || "/".equals(path)) {
            fullPath = installPath;
        } else {
            if (installPath.endsWith("/")) {
                fullPath = installPath + (path.startsWith("/") ? path.substring(1) : path);
            } else {
                fullPath = installPath + (path.startsWith("/") ? path : "/" + path);
            }
        }

        // 通过SFTP下载文件到内存
        byte[] content = fileService.downloadFileToMemory(instance.getHostId(), fullPath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + getFileName(path) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /**
     * 上传文件
     */
    @Operation(summary = "上传文件", description = "上传文件到实例目录")
    @PostMapping("/{id}/files/upload")
    @OperationLog(type = "UPLOAD", target = "INSTANCE", description = "上传文件")
    public Result<UploadResultVO> uploadFile(@Parameter(description = "实例ID") @PathVariable Long id,
                                              @Parameter(description = "目标路径") @RequestParam String path,
                                              @Parameter(description = "文件") @RequestParam("file") MultipartFile file) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        // 构建完整路径
        String installPath = resolveInstallPath(instance);
        String fullPath;
        if (path == null || path.isEmpty() || "/".equals(path)) {
            // 如果只指定了根路径，则文件名使用上传文件的原始文件名
            fullPath = installPath + "/" + file.getOriginalFilename();
        } else {
            if (installPath.endsWith("/")) {
                fullPath = installPath + (path.startsWith("/") ? path.substring(1) : path);
            } else {
                fullPath = installPath + (path.startsWith("/") ? path : "/" + path);
            }
        }

        // 通过SFTP上传文件
        fileService.uploadFile(instance.getHostId(), fullPath, file);

        UploadResultVO result = new UploadResultVO();
        result.setSuccess(true);
        result.setFileName(file.getOriginalFilename());
        result.setFileSize(file.getSize());
        result.setPath(path);

        return Result.success(result);
    }

    /**
     * 删除文件
     */
    @Operation(summary = "删除文件", description = "删除实例文件")
    @DeleteMapping("/{id}/files")
    @OperationLog(type = "DELETE", target = "INSTANCE", description = "删除文件")
    public Result<Void> deleteFile(@Parameter(description = "实例ID") @PathVariable Long id,
                                    @Parameter(description = "文件路径") @RequestParam String path) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        // 构建完整路径
        String installPath = resolveInstallPath(instance);
        String fullPath;
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return Result.fail("文件路径不能为空");
        } else {
            if (installPath.endsWith("/")) {
                fullPath = installPath + (path.startsWith("/") ? path.substring(1) : path);
            } else {
                fullPath = installPath + (path.startsWith("/") ? path : "/" + path);
            }
        }

        // 通过SFTP删除文件
        fileService.deleteFile(instance.getHostId(), fullPath);

        return Result.success();
    }

    /**
     * 根据主机ID获取实例列表
     */
    @Operation(summary = "根据主机获取实例", description = "根据主机ID获取实例列表")
    @GetMapping("/host/{hostId}")
    public Result<List<InstanceVO>> listByHostId(@Parameter(description = "主机ID") @PathVariable Long hostId) {
        List<InstanceVO> instances = instanceService.getInstancesByHostId(hostId);
        return Result.success(instances);
    }

    /**
     * 根据游戏ID获取实例列表
     */
    @Operation(summary = "根据游戏获取实例", description = "根据游戏ID获取实例列表")
    @GetMapping("/game/{gameId}")
    public Result<List<InstanceVO>> listByGameId(@Parameter(description = "游戏ID") @PathVariable Long gameId) {
        List<InstanceVO> instances = instanceService.getInstancesByGameId(gameId);
        return Result.success(instances);
    }

    /**
     * 从路径中获取文件名
     */
    private String getFileName(String path) {
        if (path == null || path.isEmpty()) {
            return "download";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    // ========== VO ==========

    /**
     * 操作结果VO
     */
    @Data
    public static class OperationResultVO {
        /**
         * 是否成功
         */
        private Boolean success;

        /**
         * 消息
         */
        private String message;
    }

    /**
     * 日志结果VO
     */
    @Data
    public static class LogResultVO {
        /**
         * 实例ID
         */
        private Long instanceId;

        /**
         * 日志行数
         */
        private Integer lines;

        /**
         * 日志内容
         */
        private String content;
    }

    /**
     * 部署进度响应 VO
     */
    @Data
    public static class DeployProgressVO {
        private Integer progress;
        private String status;
        private String statusText;
        private List<LogEntryVO> logs;
        private Boolean completed;
        private Boolean success;
        private String error;
    }

    /**
     * 日志条目 VO
     */
    @Data
    public static class LogEntryVO {
        private Long id;
        private String level;
        private String message;
        private String stage;
        private String time;
    }

    /**
     * 配置结果VO
     */
    @Data
    public static class ConfigResultVO {
        /**
         * 实例ID
         */
        private Long instanceId;

        /**
         * 配置信息
         */
        private Map<String, Object> configInfo;
    }

    /**
     * 文件信息VO
     */
    @Data
    public static class FileInfoVO {
        /**
         * 文件名
         */
        private String name;

        /**
         * 路径
         */
        private String path;

        /**
         * 是否为目录
         */
        private Boolean isDirectory;

        /**
         * 文件大小
         */
        private Long size;

        /**
         * 最后修改时间
         */
        private Long lastModified;
    }

    /**
     * 上传结果VO
     */
    @Data
    public static class UploadResultVO {
        /**
         * 是否成功
         */
        private Boolean success;

        /**
         * 文件名
         */
        private String fileName;

        /**
         * 文件大小
         */
        private Long fileSize;

        /**
         * 存储路径
         */
        private String path;
    }

    /**
     * 解析实例安装路径：兼容历史数据中未展开的 ~ 路径
     * （字面 ~ 会致文件管理 SFTP NO_SUCH_FILE）。
     */
    private String resolveInstallPath(InstanceVO instance) {
        String installPath = instance.getInstallPath();
        if (installPath == null || !installPath.startsWith("~/")) {
            return installPath;
        }
        com.gameplatform.entity.Host host = hostMapper.selectById(instance.getHostId());
        if (host != null && host.getSshUser() != null && !host.getSshUser().isBlank()) {
            return "/home/" + host.getSshUser() + installPath.substring(1);
        }
        return installPath;
    }
}
