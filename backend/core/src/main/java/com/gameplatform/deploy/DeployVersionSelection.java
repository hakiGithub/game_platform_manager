package com.gameplatform.deploy;

import com.gameplatform.common.exception.BusinessException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本选择 → {@code configInfo} 键的三态纯函数（design.md §5.3 / §14.10、B-07）。
 *
 * <p>选择本身没有独立的提交字段：向导把所选版本放进 {@code configInfo.deployVersion}
 * 一并提交（design.md §6.1「无新增字段」），因此本函数的入参就是那份载荷，
 * 「写键」在 S3 下表现为原样保留、从而值必然精确等于声明的 {@code versionId}。
 *
 * <p><b>删键只有这一个合法入口</b>（§14.8 口径 1）：{@code updateInstance} 自合并式改造后
 * 「省略键」等于「不动键」，不再等于「删键」，所以想解除版本要求只能走本类的 S2 分支。
 * 实现者不得按旧直觉在其他写路径上靠省略来删键。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class DeployVersionSelection {

    /** BR-16 合并式写入之后，唯一仍会丢键的入口就是这里。 */
    private static final String VERSION_KEY = DeployVersionCatalogService.VERSION_KEY;

    /** PRD §8.4.3 禁止清单的 ②③ 项（① 项 = 表侧 {@code variables[].name}，由入参给出）。 */
    private static final Set<String> SYSTEM_RESERVED_KEYS =
            Set.of("database", "containerWorkDir", "serviceName", "gameVersion");

    private DeployVersionSelection() {
    }

    /**
     * @param configInfo 部署向导提交载荷，可为 {@code null}
     * @param catalog    {@link DeployVersionCatalogService#read} 的结果
     * @return 应用三态后的载荷；未改动键时返回原实例，改动时返回保留插入序的新 map
     */
    public static Map<String, Object> apply(Map<String, Object> configInfo, CatalogView catalog) {
        if (configInfo == null || !catalog.availableForWizard()) {
            // S1：目录不可用 ⇒ 不写键。键既存时同样原样保留 —— 它正是 BR-12 在运行期的触发对象，
            // 在这里删掉会把「部署失败、实例 ERROR」降级成「按默认版本交付」（AC-20 (b)）
            return configInfo;
        }
        Object selected = configInfo.get(VERSION_KEY);
        if (selected == null) {
            // S2 · 未做选择
            return withoutVersionKey(configInfo);
        }
        boolean defaultChosen = selected instanceof String versionId
                && catalog.findEntry(versionId).map(VersionEntry::defaultEntry).orElse(false);
        // S2 · 显式选默认条目 → 不写且删既存；S3 → 保留精确值（含目录里取不到的既存值）
        return defaultChosen ? withoutVersionKey(configInfo) : configInfo;
    }

    private static Map<String, Object> withoutVersionKey(Map<String, Object> configInfo) {
        if (!configInfo.containsKey(VERSION_KEY)) {
            return configInfo;
        }
        Map<String, Object> kept = new LinkedHashMap<>(configInfo);
        kept.remove(VERSION_KEY);
        return kept;
    }

    /**
     * 提交期 BR-07 撞键校验（PRD §8.4.3）。
     *
     * <p>{@code configInfo} 是一张扁平 map，变量与系统保留键同处一层：若该游戏把某个
     * {@code variables[].name} 取成了 {@code deployVersion}，写版本键就会静默覆盖用户的变量值。
     * 清单保持 PRD 原三项；<b>{@code PLATFORM_IMAGE_TAG} 不进清单</b>——它是声明期保留键，
     * 会合法地出现在 compose 变量类部署的提交载荷里，进清单等于把 AC-14 的正向路径判 400
     * （design.md §14.4.2）。
     *
     * <p>提交期<b>只</b>做这一项校验：目录不可用不得在这里 400，那是 BR-12 的运行期职责
     * （§14.10 末段）。本条是 AC-20 可测性的关键保护，不得反向优化。
     *
     * @throws com.gameplatform.common.exception.BusinessException 撞键（HTTP 业务码 400）
     */
    public static void verifyNoKeyCollision(Map<String, Object> configInfo, List<String> declaredVariableNames) {
        if (configInfo == null || !configInfo.containsKey(VERSION_KEY)) {
            return;
        }
        Set<String> forbidden = new LinkedHashSet<>(SYSTEM_RESERVED_KEYS);
        if (declaredVariableNames != null) {
            forbidden.addAll(declaredVariableNames);
        }
        if (forbidden.contains(VERSION_KEY)) {
            throw new BusinessException("该游戏把变量名声明为「" + VERSION_KEY
                    + "」，与本期的版本选择键同名会互相覆盖，请改用其它变量名");
        }
    }
}
