package com.gameplatform.plugin.patch;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 主机能力探测结果（ADR-0006 决策 3 的返回格式契约，ADR-0021 扩展）
 *
 * <p>由宿主机探测脚本输出的 JSON 解析而来：
 * osType / hostname / arch / currentUser / tools{curl,wget,tar,gzip,bzip2,xz,unzip,
 * bsdtar,sha256sum,shasum,rsync,unrar,7z} / tmpFreeKb /
 * packageManager / docker / sudoNopasswd（后三者 ADR-0021 新增，老脚本输出缺失时为 null/false）。
 * 探测只在宿主机执行，容器内不探测。</p>
 */
@Data
public class HostCapabilities {

    private String osType;
    private String hostname;
    private String arch;
    private String currentUser;
    private Map<String, Boolean> tools = new HashMap<>();
    private Long tmpFreeKb;

    /** 包管理器（apt/dnf/yum/apk/pacman/zypper），无则为 null 或空串 */
    private String packageManager;

    /** Docker 是否可用（命令存在且守护进程可访问） */
    private Boolean docker = Boolean.FALSE;

    /** 是否可无密码提权（root 或免密 sudo） */
    private Boolean sudoNopasswd = Boolean.FALSE;

    /** 查询工具是否存在（未记录视为不存在） */
    public boolean hasTool(String tool) {
        return Boolean.TRUE.equals(tools.get(tool));
    }

    /** Docker 是否可用（未记录视为不可用） */
    public boolean hasDocker() {
        return Boolean.TRUE.equals(docker);
    }
}
