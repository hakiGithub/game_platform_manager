package com.gameplatform.deploy;

import java.util.List;
import java.util.Optional;

/**
 * 版本目录读取结果（design.md §16.3）：条目 + 四态 + 不合规归因。
 *
 * <p>{@code invalidReason} 只在 {@link CatalogState#INVALID} 下非空，且只服务部署日志
 * 说明行的〈校验失败要点〉槽位与验收归因（ui-spec §6.3），不进界面（design.md §16.4）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public record CatalogView(List<VersionEntry> entries, CatalogState state, String invalidReason) {

    public CatalogView {
        entries = entries == null ? List.of() : List.copyOf(entries);
        invalidReason = state == CatalogState.INVALID ? invalidReason : null;
    }

    public static CatalogView absent() {
        return new CatalogView(List.of(), CatalogState.ABSENT, null);
    }

    public static CatalogView empty() {
        return new CatalogView(List.of(), CatalogState.EMPTY, null);
    }

    public static CatalogView invalid(String reason) {
        return new CatalogView(List.of(), CatalogState.INVALID, reason);
    }

    public static CatalogView available(List<VersionEntry> entries) {
        return new CatalogView(entries, CatalogState.AVAILABLE, null);
    }

    /** 向导是否渲染版本控件：条目数为 0 的「可用」没有渲染意义。 */
    public boolean availableForWizard() {
        return state == CatalogState.AVAILABLE && !entries.isEmpty();
    }

    /** 按 {@code versionId} 精确取条目（不做规范化宽容，design.md §5.3 S3）。 */
    public Optional<VersionEntry> findEntry(String versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        return entries.stream().filter(e -> versionId.equals(e.versionId())).findFirst();
    }
}
