package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 插件商店启动清理服务（修订版）。
 *
 * <p>对齐 l4d2-server-next CleanDownloadTemp：
 * 在应用启动完成后（HTTP 服务对外提供前），整体清空 .download_temp/ 目录，
 * 删除上次运行残留的临时下载文件。
 *
 * <p><b>设计修订（vs v7 Phase 4.2）：</b>
 * <ul>
 *   <li>原设计 {@code instanceFileService.deleteDirectory(null, ...)} 中 instanceId=null
 *       不被 InstanceFileService SPI 支持（SPI 强制要求 instanceId 非空以解析路径根）</li>
 *   <li>改为通过 {@link InstanceQueryService#listByGameCode(String)} 获取所有 L4D2 实例列表，
 *       对每个实例分别调用 deleteDirectory</li>
 *   <li>单实例清理失败仅记录警告，不阻塞其他实例清理</li>
 *   <li>使用 {@link SmartInitializingSingleton} 而非 {@code ApplicationReadyEvent}，
 *       因为插件 Spring 子容器在 ApplicationReadyEvent 监听器中才创建，
 *       PluginStoreMigration 此时尚未注册，收不到事件</li>
 * </ul>
 *
 * <p>此时不可能存在正在进行的下载任务（HTTP 服务尚未对外），整体清理安全。
 *
 * @author GamePlatform
 * @version 1.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginStoreMigration implements SmartInitializingSingleton {

    private static final String GAME_CODE = "l4d2";

    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;

    /**
     * 插件子容器所有单例 Bean 初始化完成后触发清理。
     *
     * <p>使用 {@link SmartInitializingSingleton} 而非 {@code ApplicationReadyEvent}，
     * 因为插件 Spring 子容器在 ApplicationReadyEvent 的监听器（PluginAutoLoader.onApplicationReady）
     * 中才创建，此时 PluginStoreMigration 尚未注册到容器，无法收到事件。
     * SmartInitializingSingleton 在每个容器（包括插件子容器）刷新时都会触发。
     */
    @Override
    public void afterSingletonsInstantiated() {
        cleanDownloadTemp();
        cleanArchiveInstallTemp();
    }

    /**
     * 清理所有 L4D2 实例的 .download_temp 目录。
     *
     * <p>对齐 l4d2-server-next plugin_store.go CleanDownloadTemp：
     * 遍历所有 L4D2 实例，逐个清理。
     *
     * <p>失败不抛异常，仅记录警告，避免阻塞应用启动。
     * 单实例失败不影响其他实例清理。
     */
    public void cleanDownloadTemp() {
        String tempPath;
        try {
            tempPath = pathResolver.getDownloadTempPath();
        } catch (Exception e) {
            log.warn("解析下载临时目录路径失败（已忽略）: {}", e.getMessage());
            return;
        }
        log.info("启动清理下载临时目录: path={}", tempPath);

        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.listByGameCode(GAME_CODE);
        } catch (Exception e) {
            log.warn("查询 L4D2 实例列表失败（跳过清理）: {}", e.getMessage());
            return;
        }

        if (instances == null || instances.isEmpty()) {
            log.info("无 L4D2 实例，跳过下载临时目录清理");
            return;
        }

        int success = 0;
        int failed = 0;
        for (InstanceVO instance : instances) {
            Long instanceId = instance.getId();
            try {
                instanceFileService.deleteDirectory(instanceId, tempPath, true);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("清理实例 {} 的下载临时目录失败（已忽略）: {}", instanceId, e.getMessage());
            }
        }
        log.info("下载临时目录清理完成: total={}, success={}, failed={}",
                instances.size(), success, failed);
    }

    /**
     * 清理所有 L4D2 实例 plugins_store 下遗留的单插件归档临时目录。
     *
     * <p>归档导入正常路径会在 finally 清理；进程崩溃或主机失联时可能留下
     * {@code .install-*} 与 {@code <plugin>.rollback-*}。启动阶段尚无安装任务，
     * 可安全清理 staging；回滚目录必须优先恢复：只有目标提交标记存在时才删除
     * 备份，否则恢复旧目录，避免在落位中断时丢失旧插件。
     */
    public void cleanArchiveInstallTemp() {
        String pluginsStorePath;
        String installPrefix;
        String rollbackMarker;
        String commitMarker;
        try {
            pluginsStorePath = pathResolver.getPluginsStorePath();
            installPrefix = pathResolver.getArchiveInstallPrefix();
            rollbackMarker = pathResolver.getArchiveRollbackMarker();
            commitMarker = pathResolver.getArchiveCommitMarker();
        } catch (Exception e) {
            log.warn("解析插件库路径失败（已忽略归档 staging 清理）: {}", e.getMessage());
            return;
        }

        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.listByGameCode(GAME_CODE);
        } catch (Exception e) {
            log.warn("查询 L4D2 实例列表失败（跳过归档 staging 清理）: {}", e.getMessage());
            return;
        }
        if (instances == null || instances.isEmpty()) {
            log.info("无 L4D2 实例，跳过归档 staging 清理");
            return;
        }

        int success = 0;
        int failed = 0;
        for (InstanceVO instance : instances) {
            Long instanceId = instance.getId();
            try {
                List<FileInfo> entries = instanceFileService.listFiles(instanceId, pluginsStorePath);
                for (FileInfo entry : entries) {
                    String name = entry.getName();
                    if (name == null) {
                        continue;
                    }
                    String entryPath = pluginsStorePath + "/" + name;
                    if (entryPath.startsWith(installPrefix)) {
                        instanceFileService.deleteDirectory(instanceId, entryPath, true);
                        success++;
                        continue;
                    }
                    int markerIndex = name.indexOf(rollbackMarker);
                    if (markerIndex <= 0) {
                        continue;
                    }
                    String targetName = name.substring(0, markerIndex);
                    String targetPath = pluginsStorePath + "/" + targetName;
                    String commitMarkerPath = targetPath + "/" + commitMarker;
                    if (instanceFileService.exists(instanceId, commitMarkerPath)) {
                        instanceFileService.deleteDirectory(instanceId, entryPath, true);
                    } else {
                        if (instanceFileService.exists(instanceId, targetPath)) {
                            instanceFileService.deleteDirectory(instanceId, targetPath, true);
                        }
                        instanceFileService.moveFile(instanceId, entryPath, targetPath);
                    }
                    success++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("清理或恢复实例 {} 的归档临时目录失败（已忽略）: {}",
                        instanceId, e.getMessage());
            }
        }
        log.info("归档临时目录清理完成: total={}, success={}, failed={}",
                instances.size(), success, failed);
    }
}
