package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;

import java.util.Set;

/**
 * 危险 CVAR 黑名单：拒绝通过面板修改可能危害服务器的 CVAR。
 *
 * <p>l4d2-server-next 缺失此功能（rcon_password/sv_cheats 等可被任意修改），
 * 本项目主动补全以提升安全性。
 *
 * <p>黑名单覆盖：
 * <ul>
 *   <li>{@code rcon_password} — 修改后可能导致 RCON 失控</li>
 *   <li>{@code sv_cheats} — 启用作弊</li>
 *   <li>{@code sv_consistency} / {@code mp_consistency} — 关闭后允许客户端文件不一致</li>
 *   <li>{@code host_name_store} — 影响服务器识别</li>
 *   <li>{@code sv_rcon_banpenalty} / {@code sv_rcon_minfailures} 等 RCON 反作弊参数</li>
 *   <li>{@code sv_downloadurl} — 修改后可能指向恶意下载源</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class CvarBlacklist {

    private CvarBlacklist() {
    }

    /** 危险 CVAR 名称集合（小写） */
    private static final Set<String> DANGEROUS_CVARS = Set.of(
            "rcon_password",
            "sv_cheats",
            "sv_consistency",
            "mp_consistency",
            "host_name_store",
            "sv_rcon_banpenalty",
            "sv_rcon_minfailures",
            "sv_rcon_maxfailures",
            "sv_rcon_minfailuretime",
            "sv_downloadurl"
    );

    /**
     * 检查 CVAR 是否危险（不抛异常）。
     *
     * @param cvarName CVAR 名称
     * @return true 表示在黑名单中；null/空返回 false
     */
    public static boolean isDangerous(String cvarName) {
        if (cvarName == null || cvarName.isBlank()) {
            return false;
        }
        return DANGEROUS_CVARS.contains(cvarName.toLowerCase());
    }

    /**
     * 校验 CVAR，危险则抛 L4D2PluginException。
     *
     * @param cvarName CVAR 名称
     * @throws L4D2PluginException CVAR 在黑名单或为 null/空
     */
    public static void check(String cvarName) {
        if (cvarName == null || cvarName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "cvarName 不能为空");
        }
        if (DANGEROUS_CVARS.contains(cvarName.toLowerCase())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "禁止修改危险 CVAR: " + cvarName + "（在黑名单中）");
        }
    }
}
