package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.L4D2Constants;
import com.gameplatform.plugin.l4d2.dto.BatchPluginOperationDTO;
import com.gameplatform.plugin.l4d2.dto.BuiltinPluginInstallDTO;
import com.gameplatform.plugin.l4d2.service.BuiltinPluginInstaller;
import com.gameplatform.plugin.l4d2.service.PlatformPluginInstaller;
import com.gameplatform.plugin.l4d2.service.PluginExportService;
import com.gameplatform.plugin.l4d2.service.PluginInstallService;
import com.gameplatform.plugin.l4d2.vo.BuiltinBatchInstallSubmitVO;
import com.gameplatform.plugin.l4d2.vo.BuiltinInstallStatusVO;
import com.gameplatform.plugin.l4d2.vo.BuiltinInstallSubmitVO;
import com.gameplatform.plugin.l4d2.vo.BuiltinPluginVO;
import com.gameplatform.plugin.l4d2.vo.PluginExportTaskVO;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.l4d2.vo.PluginReadmeVO;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.plugin.task.TaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L4D2 插件管理控制器：上传/启用/禁用/删除/批量操作/全量导出。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 插件管理", description = "L4D2 服务器插件管理接口")
@RestController
@RequestMapping("/api/plugin/l4d2/plugins")
@RequiredArgsConstructor
@Validated
public class PluginManageController {

    private final PluginInstallService pluginInstallService;
    private final PluginExportService pluginExportService;
    private final PlatformPluginInstaller platformPluginInstaller;
    private final BuiltinPluginInstaller builtinPluginInstaller;
    private final TaskService taskService;

    /** 最近一次安装任务提交时间记录，key = instanceId:pluginId / instanceId:batch（单人使用场景，内存级防重足够） */
    private final Map<String, Long> recentInstallSubmits = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 获取插件列表。
     */
    @Operation(summary = "获取插件列表", description = "扫描 plugins 与 disabled 目录返回插件列表")
    @GetMapping("/list")
    public Result<List<PluginListVO>> list(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("获取插件列表, instanceId: {}", instanceId);
        return Result.success(pluginInstallService.listPlugins(instanceId));
    }

    /**
     * 检查内置平台插件（SourceMod+Metamod）安装状态。
     */
    @Operation(summary = "检查平台插件安装状态",
            description = "检查内置 SourceMod 1.11 平台插件包是否已部署到 plugins_store 目录")
    @GetMapping("/platform/status")
    public Result<Boolean> platformStatus(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        return Result.success(platformPluginInstaller.isInstalled(instanceId));
    }

    /**
     * 安装内置平台插件（SourceMod + Metamod）。
     *
     * <p>从 classpath 读取内置 ZIP（约 63MB），解压并上传到容器内
     * {@code plugins_store/1.11插件平台linux版/left4dead2/...} 目录。
     * 安装后用户需在插件列表中点"启用"才会真正生效，或通过应用预设自动启用。
     */
    @Operation(summary = "安装内置平台插件",
            description = "从内置 ZIP 安装 SourceMod 1.11 平台插件包到实例容器")
    @PostMapping("/platform/install")
    public Result<String> installPlatform(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("安装内置平台插件, instanceId: {}", instanceId);
        String msg = platformPluginInstaller.install(instanceId);
        return Result.success(msg, msg);
    }

    // ========== 内置插件市场（builtin-plugins.yaml + builtin-plugins/*.zip） ==========

    /**
     * 列出所有内置插件（携带当前实例的安装状态）。
     *
     * <p>返回清单中全部 62 个插件，按分类顺序：platform → required → optional → custom。
     * 每个插件包含 installed 字段，true 表示已安装到该实例的 plugins_store。
     */
    @Operation(summary = "列出内置插件",
            description = "返回 builtin-plugins.yaml 清单中全部插件，含当前实例的安装状态")
    @GetMapping("/builtin/list")
    public Result<List<BuiltinPluginVO>> listBuiltin(
            @Parameter(description = "实例ID，传 null 则不查询安装状态") @RequestParam(required = false) Long instanceId) {
        return Result.success(builtinPluginInstaller.list(instanceId));
    }

    /**
     * 短时防重提交检查：同一 key 在 {@link L4D2Constants#INSTALL_SUBMIT_DEDUP_WINDOW_MS} 窗口内
     * 重复提交直接拒绝。通过检查后记录本次提交时间；提交失败时调用方应
     * 调用 {@link #clearSubmitDedup} 清除记录以允许立即重试。
     *
     * @param dedupKey  防重键（如 instanceId:pluginId）
     * @param actionDesc 动作描述（拼入拒绝提示，如 "插件「X」的安装任务"）
     */
    private void checkSubmitDedup(String dedupKey, String actionDesc) {
        long now = System.currentTimeMillis();
        Long prev = recentInstallSubmits.put(dedupKey, now);
        if (prev != null && now - prev < L4D2Constants.INSTALL_SUBMIT_DEDUP_WINDOW_MS) {
            // 恢复原时间戳，避免重复请求不断刷新窗口
            recentInstallSubmits.put(dedupKey, prev);
            long remainSec = (L4D2Constants.INSTALL_SUBMIT_DEDUP_WINDOW_MS - (now - prev)) / 1000 + 1;
            throw new BusinessException(actionDesc + "已提交，请勿重复提交（约 " + remainSec + "s 后可重试）");
        }
    }

    /** 清除防重记录（提交失败时调用，允许立即重试） */
    private void clearSubmitDedup(String dedupKey) {
        recentInstallSubmits.remove(dedupKey);
    }

    /**
     * 安装单个内置插件（异步）。
     *
     * <p>同步阶段仅校验清单并提交内置插件安装任务到任务中心，
     * ZIP 解压与 SSH 上传由任务异步执行；前端通过 {@link #installBuiltinStatus} 轮询进度。
     * 防重：同一实例+插件在 30s 窗口内重复提交直接拒绝；
     * 同实例同类型任务执行期间由任务中心互斥键兜底。
     */
    @Operation(summary = "安装单个内置插件（异步）",
            description = "提交内置插件安装任务到执行队列，返回 taskId 供轮询进度；短时窗口内防重复提交")
    @PostMapping("/builtin/{pluginId}/install")
    public Result<BuiltinInstallSubmitVO> installBuiltin(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "内置插件ID") @PathVariable String pluginId) {
        BuiltinPluginVO plugin = builtinPluginInstaller.findById(pluginId);
        if (plugin == null) {
            throw new BusinessException("内置插件不存在: " + pluginId);
        }

        // 短时防重提交：窗口内重复提交直接拒绝
        String dedupKey = instanceId + ":" + pluginId;
        checkSubmitDedup(dedupKey, "插件「" + plugin.getName() + "」的安装任务");

        try {
            log.info("提交内置插件安装任务, instanceId: {}, pluginId: {}", instanceId, pluginId);
            String taskId = taskService.submit(TaskSubmitRequest.builder()
                    .taskType(L4D2Constants.TASK_TYPE_BUILTIN_PLUGIN_INSTALL)
                    .source(L4D2Constants.TASK_SOURCE)
                    .scopeType(L4D2Constants.SCOPE_TYPE_INSTANCE)
                    .scopeKey(String.valueOf(instanceId))
                    .payload(Map.of(
                            "instanceId", instanceId,
                            "pluginId", pluginId,
                            "pluginName", plugin.getName()))
                    .build());
            return Result.success("安装任务已提交",
                    new BuiltinInstallSubmitVO(taskId, pluginId, plugin.getName()));
        } catch (Exception e) {
            // 提交失败时清除防重记录，允许立即重试
            clearSubmitDedup(dedupKey);
            throw e;
        }
    }

    /**
     * 查询内置插件安装任务状态（供前端轮询）。
     */
    @Operation(summary = "查询内置插件安装任务状态",
            description = "返回指定任务的瘦身状态（状态/进度/错误信息），供前端轮询")
    @GetMapping("/builtin/install-status")
    public Result<BuiltinInstallStatusVO> installBuiltinStatus(
            @Parameter(description = "任务ID") @RequestParam String taskId) {
        TaskVO task = taskService.getTask(taskId);
        if (task == null) {
            return Result.fail("任务不存在: " + taskId);
        }
        BuiltinInstallStatusVO vo = new BuiltinInstallStatusVO();
        vo.setTaskId(task.getId());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setProgressMessage(task.getProgressMessage());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setResultSummary(task.getResultSummary());
        vo.setResult(task.getResult());
        return Result.success(vo);
    }

    /**
     * 批量安装内置插件（异步）。
     *
     * <p>同步阶段仅校验清单并提交 builtin-plugin-batch-install 任务到任务中心，
     * 由任务按提交顺序逐个安装，单个失败不影响其他；前端通过 {@link #installBuiltinStatus}
     * 轮询进度（进度按已处理数量均分，终态 result 携带每个插件的安装明细）。
     * 防重：同一实例的批装任务在 30s 窗口内重复提交直接拒绝。
     */
    @Operation(summary = "批量安装内置插件（异步）",
            description = "提交批量安装任务到执行队列，返回 taskId 供轮询；单个失败不影响其他")
    @PostMapping("/builtin/batch-install")
    public Result<BuiltinBatchInstallSubmitVO> batchInstallBuiltin(@Valid @RequestBody BuiltinPluginInstallDTO dto) {
        log.info("提交批量安装内置插件任务, instanceId: {}, count: {}",
                dto.getInstanceId(), dto.getPluginIds().size());

        // 校验全部插件 ID 存在于清单，避免整批任务白跑
        for (String id : dto.getPluginIds()) {
            if (builtinPluginInstaller.findById(id) == null) {
                throw new BusinessException("内置插件不存在: " + id);
            }
        }

        // 短时防重提交：同一实例的批装在窗口内不允许重复提交
        String dedupKey = dto.getInstanceId() + ":batch";
        checkSubmitDedup(dedupKey, "批量安装任务");

        try {
            String taskId = taskService.submit(TaskSubmitRequest.builder()
                    .taskType(L4D2Constants.TASK_TYPE_BUILTIN_PLUGIN_BATCH_INSTALL)
                    .source(L4D2Constants.TASK_SOURCE)
                    .scopeType(L4D2Constants.SCOPE_TYPE_INSTANCE)
                    .scopeKey(String.valueOf(dto.getInstanceId()))
                    .payload(Map.of(
                            "instanceId", dto.getInstanceId(),
                            "pluginIds", dto.getPluginIds()))
                    .build());
            return Result.success("批量安装任务已提交",
                    new BuiltinBatchInstallSubmitVO(taskId, dto.getPluginIds().size()));
        } catch (Exception e) {
            clearSubmitDedup(dedupKey);
            throw e;
        }
    }

    /**
     * 上传插件（支持 .smx / .zip / .7z / .vpk）。
     */
    @Operation(summary = "上传插件", description = "上传插件文件并自动安装")
    @PostMapping("/upload")
    public Result<PluginListVO> upload(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件文件") @RequestParam("file") MultipartFile file) {
        log.info("上传插件, instanceId: {}, fileName: {}", instanceId, file.getOriginalFilename());
        return Result.success("插件上传成功", pluginInstallService.installFromUpload(instanceId, file));
    }

    /**
     * 读取插件 README。
     */
    @Operation(summary = "读取插件 README", description = "读取本地插件库中插件的 README.md 内容")
    @GetMapping("/{pluginName}/readme")
    public Result<PluginReadmeVO> readme(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @PathVariable String pluginName) {
        log.info("读取插件 README: instanceId={}, plugin={}", instanceId, pluginName);
        return Result.success(pluginInstallService.readReadme(instanceId, pluginName));
    }

    /**
     * 删除插件。
     */
    @Operation(summary = "删除插件", description = "删除指定的插件及其归零的共享文件")
    @DeleteMapping("/{pluginName}")
    public Result<Void> delete(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @PathVariable String pluginName) {
        log.info("删除插件, instanceId: {}, pluginName: {}", instanceId, pluginName);
        pluginInstallService.deletePlugin(instanceId, pluginName);
        return Result.success();
    }

    /**
     * 旧接口，废弃但保留兼容期。
     */
    @Deprecated
    @Operation(summary = "切换插件状态（已废弃）", description = "旧接口，请使用 enable-load / disable-unload")
    @PutMapping("/{pluginName}/toggle")
    public Result<Void> toggle(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @PathVariable String pluginName,
            @Parameter(description = "是否启用") @RequestParam boolean enabled) {
        log.info("切换插件状态（废弃接口）, instanceId: {}, pluginName: {}, enabled: {}",
                instanceId, pluginName, enabled);
        if (enabled) {
            pluginInstallService.enableAndLoad(instanceId, pluginName);
        } else {
            pluginInstallService.disableAndUnload(instanceId, pluginName);
        }
        return Result.success();
    }

    /**
     * 启用插件（复制文件 + 登记，重启服务器后生效；无 RCON）。
     */
    @Operation(summary = "启用插件", description = "复制插件文件到游戏目录，重启服务器后生效")
    @PostMapping("/enable-load")
    public Result<Void> enableLoad(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @RequestParam String pluginName) {
        log.info("启用插件, instanceId: {}, pluginName: {}", instanceId, pluginName);
        pluginInstallService.enableAndLoad(instanceId, pluginName);
        return Result.success();
    }

    /**
     * 禁用插件（移除引用与文件，重启服务器后完成卸载；无 RCON）。
     */
    @Operation(summary = "禁用插件", description = "移除插件文件引用与游戏目录文件，重启服务器后完成卸载")
    @PostMapping("/disable-unload")
    public Result<Void> disableUnload(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @RequestParam String pluginName) {
        log.info("禁用插件, instanceId: {}, pluginName: {}", instanceId, pluginName);
        pluginInstallService.disableAndUnload(instanceId, pluginName);
        return Result.success();
    }

    /**
     * 批量启用插件。
     */
    @Operation(summary = "批量启用插件", description = "批量启用多个插件（重启服务器后生效）")
    @PostMapping("/batch-enable")
    public Result<Void> batchEnable(@Valid @RequestBody BatchPluginOperationDTO dto) {
        log.info("批量启用插件, instanceId: {}, count: {}", dto.getInstanceId(), dto.getPluginNames().size());
        List<String> errors = pluginInstallService.enableAndLoadBatch(
                dto.getInstanceId(), dto.getPluginNames());
        if (!errors.isEmpty()) {
            return Result.fail("部分插件启用失败: " + String.join("; ", errors));
        }
        return Result.success();
    }

    /**
     * 批量禁用插件。
     */
    @Operation(summary = "批量禁用插件", description = "批量禁用多个插件（重启服务器后完成卸载）")
    @PostMapping("/batch-disable")
    public Result<Void> batchDisable(@Valid @RequestBody BatchPluginOperationDTO dto) {
        log.info("批量禁用插件, instanceId: {}, count: {}", dto.getInstanceId(), dto.getPluginNames().size());
        List<String> errors = pluginInstallService.disableAndUnloadBatch(
                dto.getInstanceId(), dto.getPluginNames());
        if (!errors.isEmpty()) {
            return Result.fail("部分插件禁用失败: " + String.join("; ", errors));
        }
        return Result.success();
    }

    /**
     * 启动全量导出任务。
     */
    @Operation(summary = "启动全量导出", description = "异步扫描实例插件并打包为 ZIP")
    @GetMapping("/export-all/start")
    public Result<String> exportAllStart(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("启动全量导出, instanceId: {}", instanceId);
        return Result.success(pluginExportService.startExport(instanceId));
    }

    /**
     * 查询导出任务状态。
     */
    @Operation(summary = "查询导出任务状态", description = "返回导出任务进度")
    @GetMapping("/export-all/status")
    public Result<PluginExportTaskVO> exportAllStatus(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        return Result.success(pluginExportService.getStatus(instanceId));
    }

    /**
     * 下载导出的 ZIP 文件。
     */
    @Operation(summary = "下载导出文件", description = "返回 ZIP 文件流")
    @GetMapping("/export-all/download")
    public ResponseEntity<FileSystemResource> exportAllDownload(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        File file = pluginExportService.download(instanceId);
        String encodedName = URLEncoder.encode("l4d2-plugins-export-" + instanceId + ".zip", StandardCharsets.UTF_8)
                .replace("+", "%20");
        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(resource);
    }

    /**
     * 取消导出任务。
     */
    @Operation(summary = "取消导出任务", description = "取消正在进行的导出任务")
    @PostMapping("/export-all/cancel")
    public Result<Void> exportAllCancel(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("取消导出任务, instanceId: {}", instanceId);
        pluginExportService.cancel(instanceId);
        return Result.success();
    }
}
