package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.PluginStoreConfigDTO;
import com.gameplatform.plugin.l4d2.dto.PluginStoreDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.PluginStoreService;
import com.gameplatform.plugin.l4d2.service.StoreConfigService;
import com.gameplatform.plugin.l4d2.util.GitHubErrorTranslator;
import com.gameplatform.plugin.l4d2.vo.PluginStoreConfigVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDetailVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDownloadTaskVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreItemVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreTestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * L4D2 插件商店控制器：浏览 GitHub 插件仓库并下载到实例；仓库配置的查看/保存/测试连接。
 *
 * <p>业务错误统一 HTTP 200 + {@code Result.fail}：未配置仓库返回业务码
 * {@link L4D2PluginException#CODE_STORE_NOT_CONFIGURED}（前端渲染"去配置"引导态），
 * 其余 GitHub 异常经 {@link GitHubErrorTranslator} 翻译为可读文案，不再裸 500。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Tag(name = "L4D2 插件商店", description = "GitHub 插件商店浏览、下载与仓库配置")
@RestController
@RequestMapping("/api/plugin/l4d2/plugin-store")
@RequiredArgsConstructor
@Validated
public class PluginStoreController {

    private final PluginStoreService pluginStoreService;
    private final StoreConfigService storeConfigService;

    /**
     * 商店列表（含分页与关键词过滤）。
     */
    @Operation(summary = "商店列表", description = "查询 GitHub 插件商店列表，支持关键词与分类过滤")
    @GetMapping("/list")
    public Result<List<PluginStoreItemVO>> list(
            @Parameter(description = "关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类") @RequestParam(required = false) String category,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") int size) {
        log.info("查询插件商店列表: keyword={}, category={}, page={}, size={}",
                keyword, category, page, size);
        return guarded(() -> {
            List<PluginStoreItemVO> all = pluginStoreService.list(keyword, category);
            long total = all.size();
            int from = Math.max(0, (page - 1) * size);
            int to = (int) Math.min(total, from + size);
            List<PluginStoreItemVO> pageList = from >= total
                    ? List.of()
                    : all.subList(from, to);
            return Result.success(pageList);
        });
    }

    /**
     * 当前仓库配置（令牌只回传脱敏提示）。
     */
    @Operation(summary = "仓库配置", description = "获取远端插件仓库配置，令牌脱敏回显")
    @GetMapping("/config")
    public Result<PluginStoreConfigVO> getConfig() {
        return Result.success(storeConfigService.get());
    }

    /**
     * 保存仓库配置（令牌留空 = 保留原值；保存后立即清商店缓存生效）。
     */
    @Operation(summary = "保存仓库配置", description = "保存远端插件仓库地址/分支/代理/令牌，立即生效")
    @PutMapping("/config")
    public Result<PluginStoreConfigVO> saveConfig(@RequestBody PluginStoreConfigDTO dto) {
        log.info("保存仓库配置: repo={}", dto == null ? null : dto.getRepo());
        return guarded(() -> {
            PluginStoreConfigVO vo = storeConfigService.save(dto);
            pluginStoreService.evictCache();
            return Result.success("仓库配置已保存", vo);
        });
    }

    /**
     * 测试连接：用表单当前值（未保存）实拉仓库目录树。
     */
    @Operation(summary = "测试连接", description = "用表单当前值试拉仓库清单，返回发现的插件数量")
    @PostMapping("/config/test")
    public Result<PluginStoreTestVO> testConfig(@RequestBody PluginStoreConfigDTO dto) {
        log.info("测试仓库连接: repo={}", dto == null ? null : dto.getRepo());
        return guarded(() -> Result.success(storeConfigService.test(dto)));
    }

    /**
     * 商店详情。
     */
    @Operation(summary = "商店详情", description = "获取插件详情（含 README 与文件列表）")
    @GetMapping("/{pluginId}")
    public Result<PluginStoreDetailVO> detail(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        log.info("查询插件商店详情: pluginId={}", pluginId);
        return guarded(() -> Result.success(pluginStoreService.detail(pluginId)));
    }

    /**
     * README 内容（Markdown 原文）。
     */
    @Operation(summary = "README 内容", description = "获取插件 README Markdown 原文")
    @GetMapping("/{pluginId}/readme")
    public Result<String> readme(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        log.info("查询插件 README: pluginId={}", pluginId);
        return guarded(() -> Result.success(pluginStoreService.readme(pluginId)));
    }

    /**
     * 下载插件到指定实例（异步执行）。
     */
    @Operation(summary = "下载到实例", description = "异步下载插件并安装到指定实例")
    @PostMapping("/download")
    public Result<String> download(@Valid @RequestBody PluginStoreDownloadDTO dto) {
        log.info("下载插件到实例: instanceId={}, pluginId={}",
                dto.getInstanceId(), dto.getPluginId());
        return guarded(() -> {
            String taskId = pluginStoreService.download(dto);
            return Result.success("下载任务已创建", taskId);
        });
    }

    /**
     * 下载任务列表。
     */
    @Operation(summary = "下载任务列表", description = "查询指定实例的下载任务")
    @GetMapping("/tasks")
    public Result<List<PluginStoreDownloadTaskVO>> tasks(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        return Result.success(pluginStoreService.listTasks(instanceId));
    }

    /**
     * 取消下载。
     */
    @Operation(summary = "取消下载", description = "取消指定下载任务")
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancel(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        log.info("取消下载任务: taskId={}", taskId);
        pluginStoreService.cancel(taskId);
        return Result.success();
    }

    /**
     * 商店端点统一异常出口：未配置 → 业务码 1550；GitHub 异常 → 归类可读文案；
     * 其余插件异常 → 原文案。均 HTTP 200 返回，避免前端裸 HTTP 错误。
     */
    private <T> Result<T> guarded(Supplier<Result<T>> action) {
        try {
            return action.get();
        } catch (L4D2PluginException e) {
            L4D2PluginException translated = GitHubErrorTranslator.translate(e);
            if (L4D2PluginException.STORE_NOT_CONFIGURED.equals(translated.getCode())) {
                return Result.fail(L4D2PluginException.CODE_STORE_NOT_CONFIGURED, translated.getMessage());
            }
            log.warn("插件商店操作失败: code={}, message={}", translated.getCode(), translated.getMessage());
            return Result.fail(translated.getMessage());
        }
    }
}
