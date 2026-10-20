package com.gameplatform.plugin.service;

import com.gameplatform.plugin.service.FileAccessService.CommandResult;
import com.gameplatform.vo.InstanceVO;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * InstanceFileService 抽象基类。
 *
 * 提供路径校验、路由分发的通用逻辑。子类实现 buildRoute 构造具体路由上下文。
 * plugin 模块已依赖 api 模块的 InstanceVO（InstanceQueryService 直接返回 InstanceVO）。
 */
public abstract class AbstractInstanceFileService implements InstanceFileService {

    /**
     * 子类实现：根据实例 + 校验后的相对路径，构造路由上下文。
     * Native 子类填 resolvedPath=installPath+rel，containerId=null；
     * Docker 子类填 resolvedPath=containerWorkDir+rel，containerId=解析后的容器ID。
     */
    protected abstract FileRoute buildRoute(InstanceVO instance, String safeRel);

    /**
     * 路由解析：获取实例 → 校验路径 → 委托子类构造 FileRoute。
     *
     * <p>public：供主应用内部复用同一路由语义（如补丁安装 PatchInstallExecutor，
     * ADR-0006 决策 4「复用 buildRoute 路由」），避免第二套路径解析实现。</p>
     */
    public FileRoute resolveRoute(long instanceId, String relativePath) {
        InstanceVO instance = getInstanceQueryService().getInstanceById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("实例不存在: " + instanceId);
        }
        String safeRel = validateRelativePath(relativePath);
        return buildRoute(instance, safeRel);
    }

    @Override
    public String tailText(long instanceId, String relativePath, int lines,
                           String keyword, Charset charset) {
        if (lines <= 0) {
            throw new IllegalArgumentException("tail 行数必须大于 0: " + lines);
        }
        FileRoute route = resolveRoute(instanceId, relativePath);
        String safeKeyword = keyword == null || keyword.isBlank() ? null : keyword;
        String command;
        if (route.isNative()) {
            command = "tail -n " + lines + " -- " + shellQuote(route.resolvedPath);
            if (safeKeyword != null) {
                command += " | grep -F -- " + shellQuote(safeKeyword);
            }
            command += " 2>/dev/null | base64 -w0 || true";
            CommandResult result = executeHostCommand(route.hostId, command);
            if (result.getExitCode() != 0) {
                throw new RuntimeException("读取日志末尾失败: " + result.getError());
            }
            return decodeBase64(result.getOutput(), charset, "读取日志末尾失败");
        }

        command = "tail -n " + lines + " -- " + shellQuote(route.resolvedPath);
        if (safeKeyword != null) {
            command += " | grep -F -- " + shellQuote(safeKeyword);
        }
        command += " 2>/dev/null | base64 -w0 || true";
        CommandResult result = executeDockerCommand(route, command);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("读取容器日志末尾失败: " + result.getError());
        }
        return decodeBase64(result.getOutput(), charset, "读取容器日志末尾失败");
    }

    private String decodeBase64(String output, Charset charset, String errorPrefix) {
        try {
            String encoded = output == null ? "" : output.replaceAll("\\s+", "");
            return new String(Base64.getDecoder().decode(encoded), charset);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(errorPrefix + ": 远端输出不是有效 Base64", e);
        }
    }

    /** 执行宿主机 SSH 命令。 */
    protected abstract CommandResult executeHostCommand(long hostId, String command);

    /** 在实例容器内执行命令。Native 路由不会调用此方法。 */
    protected abstract CommandResult executeDockerCommand(FileRoute route, String innerCommand);

    /** POSIX shell 单引号转义。 */
    protected String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * 子类提供 InstanceQueryService（用于获取实例）。
     */
    protected abstract InstanceQueryService getInstanceQueryService();

    /**
     * 路径校验：规范化 + 禁止 .. 越界 + 剥离 . 段。
     */
    protected String validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return "";
        String normalized = relativePath.replace("\\", "/").replaceAll("/+", "/");
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("相对路径禁止包含 ..: " + relativePath);
        }
        // 剥离前导 "./" 与段内 "/./"，避免 joinPath 拼接出 "/root/./a/b" 这样的未归一化路径
        normalized = normalized.equals(".") ? "" : normalized.replaceAll("(^|/)\\./", "$1");
        return normalized;
    }

    /**
     * 拼接根目录与相对路径。
     */
    protected String joinPath(String root, String rel) {
        if (rel == null || rel.isEmpty()) return root;
        String r = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        String e = rel.startsWith("/") ? rel.substring(1) : rel;
        return r + "/" + e;
    }

    /**
     * 路由上下文值对象。
     */
    public static class FileRoute {
        public final long instanceId;
        public final long hostId;
        public final String deployType;
        public final String relativePath;
        public final String resolvedPath;
        public final String containerId;  // null 表示 Native 路由

        private FileRoute(long instanceId, long hostId, String deployType,
                          String relativePath, String resolvedPath, String containerId) {
            this.instanceId = instanceId;
            this.hostId = hostId;
            this.deployType = deployType;
            this.relativePath = relativePath;
            this.resolvedPath = resolvedPath;
            this.containerId = containerId;
        }

        public static FileRoute nativeRoute(long instanceId, long hostId, String deployType,
                                             String rel, String resolved) {
            return new FileRoute(instanceId, hostId, deployType, rel, resolved, null);
        }

        public static FileRoute dockerRoute(long instanceId, long hostId, String deployType,
                                             String rel, String resolved, String containerId) {
            return new FileRoute(instanceId, hostId, deployType, rel, resolved, containerId);
        }

        public boolean isNative() { return containerId == null; }
    }
}
