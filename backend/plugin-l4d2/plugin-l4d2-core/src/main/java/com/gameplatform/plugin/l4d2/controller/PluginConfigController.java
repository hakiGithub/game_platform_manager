package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.PluginConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.dto.PluginRestoreDefaultsDTO;
import com.gameplatform.plugin.l4d2.dto.PluginTempConfigDTO;
import com.gameplatform.plugin.l4d2.extension.PluginConfigResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigSpec;
import com.gameplatform.plugin.l4d2.service.SourceModCfgService;
import com.gameplatform.plugin.l4d2.vo.CandidatePathVO;
import com.gameplatform.plugin.l4d2.vo.PluginConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SourceMod cfg 配置项管理控制器。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 插件配置", description = "SourceMod cfg 配置项管理")
@RestController
@RequestMapping("/api/plugin/l4d2/plugin-config")
@RequiredArgsConstructor
@Validated
public class PluginConfigController {

    private final SourceModCfgService sourceModCfgService;

    /**
     * 获取插件配置。
     */
    @Operation(summary = "获取插件配置", description = "读取 SourceMod cfg 配置项列表")
    @GetMapping("/get")
    public Result<PluginConfigVO> get(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @RequestParam String pluginName) {
        log.info("获取插件配置, instanceId: {}, pluginName: {}", instanceId, pluginName);
        PluginConfigResource resource = sourceModCfgService.getConfig(instanceId, pluginName);
        return Result.success(toVO(resource));
    }

    /**
     * 更新插件配置。
     */
    @Operation(summary = "更新插件配置", description = "更新 SourceMod cfg 配置项")
    @PostMapping("/update")
    public Result<Void> update(@Valid @RequestBody PluginConfigUpdateDTO dto) {
        log.info("更新插件配置, instanceId: {}, pluginName: {}", dto.getInstanceId(), dto.getPluginName());
        sourceModCfgService.updateConfig(dto.getInstanceId(), dto.getPluginName(), dto.getItems());
        return Result.success();
    }

    /**
     * 列出候选 cfg 文件路径。
     */
    @Operation(summary = "列出候选 cfg 文件路径", description = "返回插件可能使用的 cfg 候选文件路径及存在性标记")
    @GetMapping("/candidates")
    public Result<List<CandidatePathVO>> candidates(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "插件名称") @RequestParam String pluginName) {
        log.info("列出候选 cfg 路径, instanceId: {}, pluginName: {}", instanceId, pluginName);
        List<CandidatePathVO> list = sourceModCfgService.listCandidates(instanceId, pluginName);
        return Result.success(list);
    }

    /**
     * 临时应用配置（RCON sm_cvar，不写文件）。
     *
     * <p>对齐 l4d2-server-next 临时配置语义：服务器重启后失效。
     */
    @Operation(summary = "临时应用配置", description = "通过 RCON sm_cvar 临时设置 CVAR，不写文件，重启失效")
    @PostMapping("/apply-temp")
    public Result<Void> applyTemp(@Valid @RequestBody PluginTempConfigDTO dto) {
        log.info("临时应用配置, instanceId: {}, cvar: {}, value: {}",
                dto.getInstanceId(), dto.getCvarName(), dto.getCvarValue());
        sourceModCfgService.applyTempConfig(
                dto.getInstanceId(), dto.getCvarName(), dto.getCvarValue());
        return Result.success();
    }

    /**
     * 恢复默认配置（从 CVAR 元数据 Default 字段重建文件）。
     *
     * <p>对齐 l4d2-server-next RestoreSourceModConfig：使用 restoreFormat 写回完整注释块。
     */
    @Operation(summary = "恢复默认配置", description = "从 CVAR 元数据 Default 字段重建 cfg 文件")
    @PostMapping("/restore-defaults")
    public Result<Void> restoreDefaults(@Valid @RequestBody PluginRestoreDefaultsDTO dto) {
        log.info("恢复默认配置, instanceId: {}, pluginName: {}",
                dto.getInstanceId(), dto.getPluginName());
        sourceModCfgService.restoreDefaults(dto.getInstanceId(), dto.getPluginName());
        return Result.success();
    }

    // ===== 私有方法 =====

    private PluginConfigVO toVO(PluginConfigResource resource) {
        if (resource == null || resource.getSpec() == null) {
            return null;
        }
        PluginConfigSpec spec = resource.getSpec();
        PluginConfigVO vo = new PluginConfigVO();
        vo.setPluginName(spec.getPluginName());
        vo.setConfigName(spec.getConfigName());
        vo.setConfigPath(spec.getConfigPath());
        vo.setItems(spec.getItems());
        vo.setRawContent(spec.getRawContent());
        vo.setLastSyncedAt(spec.getLastSyncedAt());
        return vo;
    }
}
