package com.gameplatform.patch;

/**
 * 补丁格式（ADR-0006 决策 6：按扩展名判定）
 */
public enum PatchFormat {

    TAR_GZ(true),
    TAR_BZ2(true),
    TAR_XZ(true),
    ZIP(true),
    GZ(true),
    BZ2(true),
    XZ(true),
    /** 非压缩包 */
    PLAIN(false);

    private final boolean archive;

    PatchFormat(boolean archive) {
        this.archive = archive;
    }

    /** 是否为压缩包（需要解压） */
    public boolean isArchive() {
        return archive;
    }

    /**
     * 推断补丁格式：显式声明优先，否则按 URL 扩展名。
     */
    public static PatchFormat detect(String url, String explicitFormat) {
        if (explicitFormat != null && !explicitFormat.isBlank()) {
            return parse(explicitFormat);
        }
        String name = url;
        int query = name.indexOf('?');
        if (query > 0) {
            name = name.substring(0, query);
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return TAR_GZ;
        }
        if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) {
            return TAR_BZ2;
        }
        if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
            return TAR_XZ;
        }
        if (lower.endsWith(".zip")) {
            return ZIP;
        }
        if (lower.endsWith(".gz")) {
            return GZ;
        }
        if (lower.endsWith(".bz2")) {
            return BZ2;
        }
        if (lower.endsWith(".xz")) {
            return XZ;
        }
        return PLAIN;
    }

    private static PatchFormat parse(String explicitFormat) {
        return switch (explicitFormat.trim().toLowerCase()) {
            case "tar.gz", "tgz" -> TAR_GZ;
            case "tar.bz2", "tbz2" -> TAR_BZ2;
            case "tar.xz", "txz" -> TAR_XZ;
            case "zip" -> ZIP;
            case "gz" -> GZ;
            case "bz2" -> BZ2;
            case "xz" -> XZ;
            case "plain", "file" -> PLAIN;
            default -> throw new IllegalArgumentException("不支持的补丁格式: " + explicitFormat);
        };
    }
}
