package com.gameplatform.plugin.l4d2.util;

import java.util.regex.Pattern;

/**
 * SteamID 格式转换工具。
 *
 * <p>支持 STEAM_0:1:xxx（SteamID2）与 7656119xxxxxxxxxx（SteamID64）互转。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class SteamIdUtil {

    /** SteamID64 基数：76561197960265728 */
    private static final long STEAM64_BASE = 76561197960265728L;

    private static final Pattern STEAM_ID2_PATTERN =
            Pattern.compile("^STEAM_[0-5]:[01]:\\d+$");
    private static final Pattern STEAM_ID64_PATTERN =
            Pattern.compile("^7656119\\d{10}$");

    private SteamIdUtil() {}

    /** STEAM_0:Y:Z → SteamID64 */
    public static long toSteam64(String steamId2) {
        String[] parts = steamId2.split(":");
        long y = Long.parseLong(parts[1]);
        long z = Long.parseLong(parts[2]);
        return STEAM64_BASE + z * 2 + y;
    }

    /** SteamID64 → STEAM_0:Y:Z */
    public static String toSteamId2(long steam64) {
        long v = steam64 - STEAM64_BASE;
        long y = v % 2;
        long z = v / 2;
        return "STEAM_0:" + y + ":" + z;
    }

    /** 校验 SteamID2 或 SteamID64 格式 */
    public static boolean isValid(String steamId) {
        if (steamId == null || steamId.isEmpty()) return false;
        return STEAM_ID2_PATTERN.matcher(steamId).matches()
                || STEAM_ID64_PATTERN.matcher(steamId).matches();
    }
}
