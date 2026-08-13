package com.gameplatform.plugin.patch;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 主机能力探测结果（ADR-0006 决策 3 的返回格式契约）
 *
 * <p>由宿主机探测脚本输出的 JSON 解析而来：
 * osType / hostname / arch / currentUser / tools{curl,wget,tar,gzip,bzip2,xz,unzip,
 * bsdtar,sha256sum,shasum,rsync} / tmpFreeKb。探测只在宿主机执行，容器内不探测。</p>
 */
@Data
public class HostCapabilities {

    private String osType;
    private String hostname;
    private String arch;
    private String currentUser;
    private Map<String, Boolean> tools = new HashMap<>();
    private Long tmpFreeKb;

    /** 查询工具是否存在（未记录视为不存在） */
    public boolean hasTool(String tool) {
        return Boolean.TRUE.equals(tools.get(tool));
    }
}
