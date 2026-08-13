package com.gameplatform.patch;

import com.gameplatform.plugin.patch.HostCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 补丁安装决策引擎单测（ADR-0006 决策 5 的矩阵）
 * TDD：先锁定格式判定与 LAN/WAN 门控四分支，再实现引擎。
 */
@DisplayName("补丁安装决策引擎测试")
class PatchDecisionEngineTest {

    private final PatchDecisionEngine engine = new PatchDecisionEngine();

    private HostCapabilities caps(boolean curl, boolean wget, boolean tar,
                                  boolean gzip, boolean bzip2, boolean xz,
                                  boolean unzip, boolean bsdtar) {
        HostCapabilities c = new HostCapabilities();
        c.setTools(Map.of(
                "curl", curl, "wget", wget, "tar", tar, "gzip", gzip,
                "bzip2", bzip2, "xz", xz, "unzip", unzip, "bsdtar", bsdtar));
        return c;
    }

    /** 全能主机：curl+tar+gzip+bzip2+xz+unzip */
    private HostCapabilities full() {
        return caps(true, false, true, true, true, true, true, false);
    }

    /** 只有 curl 的主机：能下载不能解压 */
    private HostCapabilities downloadOnly() {
        return caps(true, false, false, false, false, false, false, false);
    }

    /** 只有 tar+gzip 的主机：能解压不能下载 */
    private HostCapabilities extractOnly() {
        return caps(false, false, true, true, false, false, false, false);
    }

    /** 无工具主机 */
    private HostCapabilities empty() {
        return caps(false, false, false, false, false, false, false, false);
    }

    // ===== 格式判定 =====

    @Test
    @DisplayName("格式按扩展名推断")
    void formatDetection() {
        assertEquals(PatchFormat.TAR_GZ, PatchFormat.detect("http://a/b/patch.tar.gz", null));
        assertEquals(PatchFormat.TAR_GZ, PatchFormat.detect("http://a/b/patch.tgz?x=1", null));
        assertEquals(PatchFormat.TAR_BZ2, PatchFormat.detect("http://a/b/patch.tar.bz2", null));
        assertEquals(PatchFormat.TAR_XZ, PatchFormat.detect("http://a/b/patch.txz", null));
        assertEquals(PatchFormat.ZIP, PatchFormat.detect("http://a/b/patch.zip", null));
        assertEquals(PatchFormat.GZ, PatchFormat.detect("http://a/b/patch.gz", null));
        assertEquals(PatchFormat.BZ2, PatchFormat.detect("http://a/b/patch.bz2", null));
        assertEquals(PatchFormat.XZ, PatchFormat.detect("http://a/b/patch.xz", null));
        assertEquals(PatchFormat.PLAIN, PatchFormat.detect("http://a/b/patch.vpk", null));
        assertEquals(PatchFormat.PLAIN, PatchFormat.detect("http://a/b/noext", null));
    }

    @Test
    @DisplayName("显式 format 优先于扩展名")
    void explicitFormatWins() {
        assertEquals(PatchFormat.ZIP, PatchFormat.detect("http://a/b/patch.tar.gz", "zip"));
    }

    // ===== 决策矩阵（ADR-0006 决策 5） =====

    @Test
    @DisplayName("能下载+能解压 → 目标自治（LAN/WAN 相同）")
    void selfSufficient() {
        assertEquals(PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT,
                engine.decide(full(), PatchFormat.TAR_GZ, true));
        assertEquals(PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT,
                engine.decide(full(), PatchFormat.TAR_GZ, false));
    }

    @Test
    @DisplayName("能下载+不能解压 → LAN 平台下载+解压推散文件；WAN 报错")
    void canDownloadCannotExtract() {
        assertEquals(PatchStrategy.PLATFORM_DOWNLOAD_PLATFORM_EXTRACT,
                engine.decide(downloadOnly(), PatchFormat.TAR_GZ, true));
        assertEquals(PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT,
                engine.decide(downloadOnly(), PatchFormat.TAR_GZ, false));
    }

    @Test
    @DisplayName("不能下载+能解压 → LAN 平台下载推压缩包远程解压；WAN 报错")
    void canExtractCannotDownload() {
        assertEquals(PatchStrategy.PLATFORM_DOWNLOAD_TARGET_EXTRACT,
                engine.decide(extractOnly(), PatchFormat.TAR_GZ, true));
        assertEquals(PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT,
                engine.decide(extractOnly(), PatchFormat.TAR_GZ, false));
    }

    @Test
    @DisplayName("都不能 → LAN 平台下载+解压推散文件；WAN 报错")
    void neither() {
        assertEquals(PatchStrategy.PLATFORM_DOWNLOAD_PLATFORM_EXTRACT,
                engine.decide(empty(), PatchFormat.TAR_GZ, true));
        assertEquals(PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT,
                engine.decide(empty(), PatchFormat.TAR_GZ, false));
    }

    @Test
    @DisplayName("非压缩包只需下载能力")
    void plainFile() {
        assertEquals(PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT,
                engine.decide(downloadOnly(), PatchFormat.PLAIN, true));
        assertEquals(PatchStrategy.PLATFORM_DOWNLOAD_PLATFORM_EXTRACT,
                engine.decide(extractOnly(), PatchFormat.PLAIN, true));
        assertEquals(PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT,
                engine.decide(extractOnly(), PatchFormat.PLAIN, false));
    }

    @Test
    @DisplayName("zip 解压能力：unzip 或 bsdtar")
    void zipExtractCapability() {
        assertEquals(PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT,
                engine.decide(caps(true, false, false, false, false, false, true, false),
                        PatchFormat.ZIP, false));
        assertEquals(PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT,
                engine.decide(caps(true, false, false, false, false, false, false, true),
                        PatchFormat.ZIP, false));
        assertEquals(PatchStrategy.PLATFORM_DOWNLOAD_PLATFORM_EXTRACT,
                engine.decide(caps(true, false, false, false, false, false, false, false),
                        PatchFormat.ZIP, true));
    }
}
