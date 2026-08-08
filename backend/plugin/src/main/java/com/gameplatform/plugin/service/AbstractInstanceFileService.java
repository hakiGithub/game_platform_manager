package com.gameplatform.plugin.service;

import com.gameplatform.vo.InstanceVO;
import java.nio.charset.Charset;
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
     */
    protected FileRoute resolveRoute(long instanceId, String relativePath) {
        InstanceVO instance = getInstanceQueryService().getInstanceById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("实例不存在: " + instanceId);
        }
        String safeRel = validateRelativePath(relativePath);
        return buildRoute(instance, safeRel);
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
    protected static class FileRoute {
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
