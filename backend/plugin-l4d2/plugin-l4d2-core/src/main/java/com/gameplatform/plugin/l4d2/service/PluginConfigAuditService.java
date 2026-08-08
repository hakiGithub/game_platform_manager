package com.gameplatform.plugin.l4d2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 插件配置修改审计日志服务。
 *
 * <p>对齐 l4d2-server-next audit.go 的 LogOp 模式，记录：
 * <ul>
 *   <li>updateConfig — 持久化修改 CVAR（写文件）</li>
 *   <li>applyTempConfig — 临时应用 CVAR（RCON sm_cvar，不写文件）</li>
 *   <li>restoreDefaults — 恢复默认值</li>
 * </ul>
 *
 * <p>本实现使用本地日志（slf4j）记录，未来可扩展为扩展资源持久化。
 * 关键设计：审计失败不应阻塞主流程，所有记录方法均吞掉异常。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class PluginConfigAuditService {

    /**
     * 记录持久化修改 CVAR 操作。
     *
     * @param instanceId 实例 ID
     * @param hostId     主机 ID
     * @param pluginName 插件名
     * @param cfgFile    cfg 文件相对路径
     * @param cvarName   CVAR 名称（批量修改传 "multiple"）
     * @param oldValue   旧值（批量修改传 "multiple"）
     * @param newValue   新值（批量修改传 "multiple"）
     * @param operator   操作者（如 "system" / 用户名）
     */
    public void logUpdateConfig(Long instanceId, Long hostId, String pluginName,
                                 String cfgFile, String cvarName,
                                 String oldValue, String newValue, String operator) {
        try {
            log.info("[ConfigAudit] UPDATE instanceId={} hostId={} plugin={} cfg={} cvar={} old={} new={} op={} time={}",
                    instanceId, hostId, pluginName, cfgFile, cvarName,
                    oldValue, newValue, operator, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 记录临时应用 CVAR 操作。
     *
     * @param instanceId 实例 ID
     * @param hostId     主机 ID
     * @param pluginName 插件名（临时应用可能未关联插件，传 null）
     * @param cvarName   CVAR 名称
     * @param value      应用值
     * @param operator   操作者
     */
    public void logApplyTempConfig(Long instanceId, Long hostId, String pluginName,
                                    String cvarName, String value, String operator) {
        try {
            log.info("[ConfigAudit] APPLY_TEMP instanceId={} hostId={} plugin={} cvar={} value={} op={} time={}",
                    instanceId, hostId, pluginName, cvarName, value, operator, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 记录恢复默认值操作。
     *
     * @param instanceId   实例 ID
     * @param hostId       主机 ID
     * @param pluginName   插件名
     * @param cfgFile      cfg 文件相对路径
     * @param changedCount 变更的 CVAR 数量
     * @param operator     操作者
     */
    public void logRestoreDefaults(Long instanceId, Long hostId, String pluginName,
                                    String cfgFile, int changedCount, String operator) {
        try {
            log.info("[ConfigAudit] RESTORE_DEFAULTS instanceId={} hostId={} plugin={} cfg={} changed={} op={} time={}",
                    instanceId, hostId, pluginName, cfgFile, changedCount, operator, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }
    }
}
