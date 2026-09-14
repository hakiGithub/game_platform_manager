package com.gameplatform.hosttool;

import com.gameplatform.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 主机环境工具白名录（ADR-0021 决策 1）
 *
 * <p>白名单工具 → 各包管理器下的包名映射。不提供任意命令/任意包名入口；
 * 未识别的包管理器不做猜测，由调用方引导用户用 Web 终端手动安装。</p>
 */
@Component
public class HostToolCatalog {

    /** 白名单工具（展示顺序即面板顺序） */
    public static final List<String> WHITELIST =
            List.of("unzip", "unrar", "7z", "xz", "bzip2", "tar", "gzip", "curl", "wget", "rsync");

    /** 工具中文名（面板展示） */
    private static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
            Map.entry("unzip", "unzip（zip 解压）"),
            Map.entry("unrar", "unrar（rar 解压，Debian 系需 non-free 源）"),
            Map.entry("7z", "p7zip（7z 解压）"),
            Map.entry("xz", "xz（xz 压缩）"),
            Map.entry("bzip2", "bzip2（bz2 压缩）"),
            Map.entry("tar", "tar（tar 打包）"),
            Map.entry("gzip", "gzip（gz 压缩）"),
            Map.entry("curl", "curl（下载）"),
            Map.entry("wget", "wget（下载）"),
            Map.entry("rsync", "rsync（同步）"));

    /** 工具 → 各包管理器包名（null 表示该包管理器无此包） */
    private static final Map<String, Map<String, String>> PACKAGE_NAMES = Map.ofEntries(
            Map.entry("unzip", Map.of(
                    "apt", "unzip", "dnf", "unzip", "yum", "unzip",
                    "apk", "unzip", "pacman", "unzip", "zypper", "unzip")),
            Map.entry("unrar", Map.of(
                    "apt", "unrar", "dnf", "unrar", "yum", "unrar",
                    "apk", "unrar", "pacman", "unrar", "zypper", "unrar")),
            Map.entry("7z", Map.of(
                    "apt", "p7zip-full", "dnf", "p7zip", "yum", "p7zip",
                    "apk", "p7zip", "pacman", "p7zip", "zypper", "p7zip")),
            Map.entry("xz", Map.of(
                    "apt", "xz-utils", "dnf", "xz", "yum", "xz",
                    "apk", "xz", "pacman", "xz", "zypper", "xz")),
            Map.entry("bzip2", Map.of(
                    "apt", "bzip2", "dnf", "bzip2", "yum", "bzip2",
                    "apk", "bzip2", "pacman", "bzip2", "zypper", "bzip2")),
            Map.entry("tar", Map.of(
                    "apt", "tar", "dnf", "tar", "yum", "tar",
                    "apk", "tar", "pacman", "tar", "zypper", "tar")),
            Map.entry("gzip", Map.of(
                    "apt", "gzip", "dnf", "gzip", "yum", "gzip",
                    "apk", "gzip", "pacman", "gzip", "zypper", "gzip")),
            Map.entry("curl", Map.of(
                    "apt", "curl", "dnf", "curl", "yum", "curl",
                    "apk", "curl", "pacman", "curl", "zypper", "curl")),
            Map.entry("wget", Map.of(
                    "apt", "wget", "dnf", "wget", "yum", "wget",
                    "apk", "wget", "pacman", "wget", "zypper", "wget")),
            Map.entry("rsync", Map.of(
                    "apt", "rsync", "dnf", "rsync", "yum", "rsync",
                    "apk", "rsync", "pacman", "rsync", "zypper", "rsync")));

    /** 工具是否在白名单内 */
    public boolean isWhitelisted(String tool) {
        return WHITELIST.contains(tool);
    }

    public String displayName(String tool) {
        return DISPLAY_NAMES.getOrDefault(tool, tool);
    }

    /**
     * 按包管理器拼安装命令（发行版自适应，ADR-0021 决策 2）。
     *
     * @throws BusinessException 包管理器为空/不支持，或该包管理器无此工具的包
     */
    public String buildInstallCommand(String tool, String packageManager) {
        if (!isWhitelisted(tool)) {
            throw new BusinessException("不支持的工具: " + tool + "（仅允许白名单: " + WHITELIST + "）");
        }
        if (packageManager == null || packageManager.isBlank()) {
            throw new BusinessException("未识别到包管理器，无法自动安装，请通过 Web 终端手动安装");
        }
        String pkg = PACKAGE_NAMES.get(tool).get(packageManager);
        if (pkg == null) {
            throw new BusinessException("包管理器 " + packageManager + " 无工具 " + tool + " 的包名映射，请通过 Web 终端手动安装");
        }
        return switch (packageManager) {
            case "apt" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y " + pkg;
            case "dnf" -> "dnf install -y " + pkg;
            case "yum" -> "yum install -y " + pkg;
            case "apk" -> "apk add " + pkg;
            case "pacman" -> "pacman -S --noconfirm " + pkg;
            case "zypper" -> "zypper --non-interactive install " + pkg;
            default -> throw new BusinessException("不支持的包管理器: " + packageManager);
        };
    }
}
