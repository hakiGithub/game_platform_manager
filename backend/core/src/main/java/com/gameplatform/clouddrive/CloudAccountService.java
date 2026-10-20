package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.clouddrive.extension.CloudAccountResource;
import com.gameplatform.clouddrive.extension.CloudAccountSpec;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.util.AesUtil;
import com.haki.clouddrive.core.domain.CloudAccount;
import com.haki.clouddrive.core.domain.Mount;
import com.haki.clouddrive.core.model.CloudObject;
import com.haki.clouddrive.core.model.DirectoryRef;
import com.haki.clouddrive.core.model.QuotaInfo;
import com.haki.clouddrive.core.provider.ProviderSettings;
import com.haki.clouddrive.sdk.CloudDriveClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 云盘账号管理（ADR-0024）：extensions 共享表为唯一权威源，SDK 数据目录仅是引擎态。
 * <p>
 * 写路径：先落扩展表（凭证 AES 密文），再同步进 CloudDriveClient（addAccount/replaceSettings
 * + 默认挂载 {@code /{providerType}/{name}}）。启动时全量对账同步，凭证失效标 UNHEALTHY 不阻断启动。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@DependsOn("extensionStoreInitializer")
@RequiredArgsConstructor
public class CloudAccountService {

    public static final String STATUS_HEALTHY = "HEALTHY";
    public static final String STATUS_UNHEALTHY = "UNHEALTHY";

    private final ExtensionClient hostExtensionClient;
    private final CloudDriveClient client;
    private final ObjectMapper objectMapper;

    /** 启动对账：把扩展表中的账号全量同步进 SDK 引擎 */
    @PostConstruct
    public void syncOnStartup() {
        List<CloudAccountResource> accounts = hostExtensionClient.listAll(CloudAccountResource.class);
        int ok = 0;
        for (CloudAccountResource res : accounts) {
            try {
                syncToEngine(res);
                hostExtensionClient.updateStatus(CloudAccountResource.class, res.getName(), STATUS_HEALTHY);
                ok++;
            } catch (Exception e) {
                log.warn("[CloudDrive] 账号 [{}] 启动同步失败（标记 UNHEALTHY）: {}", res.getName(), e.getMessage());
                try {
                    hostExtensionClient.updateStatus(CloudAccountResource.class, res.getName(), STATUS_UNHEALTHY);
                } catch (Exception ignore) {
                    // 状态更新失败不阻断其余账号同步
                }
            }
        }
        log.info("[CloudDrive] 启动同步完成：{}/{} 个账号可用", ok, accounts.size());
    }

    public List<CloudAccountResource> listAll() {
        return hostExtensionClient.listAll(CloudAccountResource.class);
    }

    public Optional<CloudAccountResource> get(String name) {
        return hostExtensionClient.get(CloudAccountResource.class, name);
    }

    /**
     * 新增账号（添加即探活，凭证无效直接报错，不产生半可用账号）。
     *
     * @param name          账号唯一标识（用于默认挂载路径，建议小写字母数字连字符）
     * @param providerType  Provider 类型
     * @param settingsJson  ProviderSettings 明文 JSON
     * @param displayName   展示名（可空）
     * @param remark        备注（可空）
     */
    public CloudAccountResource create(String name, String providerType, String settingsJson,
                                       String displayName, String remark) {
        if (get(name).isPresent()) {
            throw new BusinessException("云盘账号已存在: " + name);
        }
        ProviderSettings settings = parseSettings(providerType, settingsJson);

        CloudAccountSpec spec = new CloudAccountSpec();
        spec.setProviderType(providerType);
        spec.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName);
        spec.setSettingsCipher(AesUtil.encrypt(settingsJson));
        spec.setRootPath("/");
        spec.setCredentialHint(hintOf(settingsJson));
        spec.setRemark(remark);

        CloudAccountResource res = new CloudAccountResource();
        res.setName(name);
        res.setSpec(spec);
        res.setStatus(STATUS_HEALTHY);
        hostExtensionClient.create(res);

        try {
            requireHealthy(syncToEngine(res), name);
        } catch (BusinessException e) {
            hostExtensionClient.delete(CloudAccountResource.class, name);
            throw e;
        } catch (Exception e) {
            // 引擎接入失败回滚扩展表，避免表里有、引擎里没有的半状态
            hostExtensionClient.delete(CloudAccountResource.class, name);
            throw new BusinessException("账号接入失败（凭证无效或网盘不可达）: " + e.getMessage());
        }
        return res;
    }

    /** 重贴凭证（凭证过期后的唯一修复路径）；起始目录等未填字段自动保留旧值 */
    public CloudAccountResource replaceCredential(String name, String settingsJson) {
        CloudAccountResource res = require(name);
        settingsJson = mergeMissingRootFields(res, settingsJson);
        ProviderSettings settings = parseSettings(res.getSpec().getProviderType(), settingsJson);

        CloudAccountSpec spec = res.getSpec();
        spec.setSettingsCipher(AesUtil.encrypt(settingsJson));
        spec.setCredentialHint(hintOf(settingsJson));
        res.setSpec(spec);
        hostExtensionClient.update(res);

        try {
            requireHealthy(syncToEngine(res), name);
            return hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_HEALTHY);
        } catch (BusinessException e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw e;
        } catch (Exception e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw new BusinessException("凭证更新失败: " + e.getMessage());
        }
    }

    public void delete(String name) {
        CloudAccountResource res = require(name);
        client.listMounts().stream()
                .filter(m -> m.mountPath().equals(mountPath(res)))
                .findFirst()
                .ifPresent(m -> client.deleteMount(m.id()));
        sdkAccountOf(name).ifPresent(acc -> client.removeAccount(acc.id()));
        hostExtensionClient.delete(CloudAccountResource.class, name);
    }

    /** 更新展示信息（展示名/备注，不动凭证）；必须持久化，不能只改内存对象。 */
    public CloudAccountResource updateDisplay(CloudAccountResource res) {
        hostExtensionClient.update(res);
        return require(res.getName());
    }

    /** 手动探活并回写状态 */
    public CloudAccountResource verify(String name) {
        CloudAccountResource res = require(name);
        CloudAccount sdk;
        try {
            sdk = ensureSdkAccount(res);
        } catch (Exception e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw new BusinessException("账号接入失败: " + e.getMessage());
        }
        try {
            // probeAndStore 只标记健康状态不抛异常，凭证是否有效看返回的 health
            CloudAccount probed = client.verifyAccount(sdk.id());
            requireHealthy(probed, name);
            return hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_HEALTHY);
        } catch (BusinessException e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw e;
        } catch (Exception e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw new BusinessException("账号探活失败: " + e.getMessage());
        }
    }

    public QuotaInfo quota(String name) {
        CloudAccountResource res = require(name);
        CloudAccount sdk;
        try {
            sdk = ensureSdkAccount(res);
        } catch (Exception e) {
            throw new BusinessException("账号接入失败: " + e.getMessage());
        }
        try {
            return client.quota(sdk.id());
        } catch (Exception e) {
            throw new BusinessException("查询配额失败: " + e.getMessage());
        }
    }

    /** 账号默认挂载点（ADR-0024：/{providerType}/{name}，宿主隐式派生） */
    public String mountPath(CloudAccountResource res) {
        return "/" + res.getSpec().getProviderType() + "/" + res.getName();
    }

    /**
     * 浏览账号目录（起始目录下拉用，ADR-0024 增补）：只返回目录；
     * value 始终为账号内路径，供前端直接作为下一次 path 使用。id 仅作展示/审计元数据，
     * 不参与后续寻址，避免 ID 型 Provider 把文件夹 ID 当路径查询。
     */
    public Map<String, Object> listFolders(String name, String path) {
        CloudAccountResource res = require(name);
        CloudAccount sdk;
        try {
            sdk = ensureSdkAccount(res);
        } catch (Exception e) {
            throw new BusinessException("账号接入失败: " + e.getMessage());
        }
        try {
            String rootField = rootFieldName(res.getSpec().getProviderType());
            boolean idBased = "rootFolderId".equals(rootField);
            List<Map<String, Object>> folders = new ArrayList<>();
            for (CloudObject f : client.listAccountFolders(sdk.id(), normalizePath(path))) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", f.name());
                item.put("path", f.path());
                item.put("id", f.id());
                item.put("value", f.path());
                folders.add(item);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("path", normalizePath(path));
            result.put("rootField", rootField);
            result.put("idBased", idBased);
            result.put("folders", folders);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("浏览目录失败: " + e.getMessage());
        }
    }

    /**
     * 应用起始目录（ADR-0024 增补）：value 只接受账号内路径；路径型 Provider 直接写入
     * settings 的 root 字段，ID 型 Provider 先按路径逐级解析出 Provider 原生文件夹 ID，
     * 再重建默认挂载。路径命名空间随之切换，已转存产物需按新命名空间重新寻址。
     */
    public CloudAccountResource applyRootDir(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("起始目录值不能为空");
        }
        String rootPath = normalizePath(value);
        CloudAccountResource res = require(name);
        String providerType = res.getSpec().getProviderType();
        String rootField = rootFieldName(providerType);
        requireMountRootSemantics(providerType, rootField, rootPath);
        try {
            CloudAccount sdk = ensureSdkAccount(res);
            String providerRoot = resolveProviderRoot(sdk.id(), providerType, rootPath);

            if ("rootPath".equals(rootField)) {
                String newJson = withRootField(res, rootField, rootPath);
                CloudAccountSpec spec = res.getSpec();
                spec.setSettingsCipher(AesUtil.encrypt(newJson));
                spec.setCredentialHint(hintOf(newJson));
                res.setSpec(spec);
            }

            CloudAccountSpec spec = res.getSpec();
            spec.setRootPath(rootPath);
            hostExtensionClient.update(res);

            String mp = mountPath(res);
            client.listMounts().stream()
                    .filter(m -> m.mountPath().equals(mp))
                    .findFirst()
                    .ifPresent(m -> client.deleteMount(m.id()));
            client.createMount(sdk.id(), mp, providerRoot);
            return hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_HEALTHY);
        } catch (BusinessException e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw e;
        } catch (Exception e) {
            hostExtensionClient.updateStatus(CloudAccountResource.class, name, STATUS_UNHEALTHY);
            throw new BusinessException("应用起始目录失败: " + e.getMessage());
        }
    }

    /**
     * 重贴凭证的字段合并：重贴对话框只提交凭证类字段，起始目录（rootPath/rootFolderId）
     * 等未提交字段从旧 settings 回填，避免缺字段导致 Settings 解析出 null。
     */
    private String mergeMissingRootFields(CloudAccountResource res, String newSettingsJson) {
        try {
            String rootField = rootFieldName(res.getSpec().getProviderType());
            com.fasterxml.jackson.databind.JsonNode newNode =
                    objectMapper.readTree(newSettingsJson);
            com.fasterxml.jackson.databind.JsonNode oldNode =
                    objectMapper.readTree(AesUtil.decrypt(res.getSpec().getSettingsCipher()));
            com.fasterxml.jackson.databind.JsonNode current = newNode.get(rootField);
            if (current == null || current.isNull() || current.asText("").isBlank()) {
                com.fasterxml.jackson.databind.JsonNode old = oldNode.get(rootField);
                if (old != null && !old.isNull()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) newNode).set(rootField, old);
                }
            }
            return objectMapper.writeValueAsString(newNode);
        } catch (Exception e) {
            return newSettingsJson;
        }
    }

    /** 起始目录字段名：rootPath（路径语义）或 rootFolderId（ID 语义），按 provider schema 识别 */
    private String rootFieldName(String providerType) {        try {
            var descOpt = client.describeProvider(providerType);
            if (descOpt.isPresent() && descOpt.get().settingsSchema() != null) {
                for (var f : descOpt.get().settingsSchema().fields()) {
                    if ("rootFolderId".equals(f.name())) return "rootFolderId";
                    if ("rootPath".equals(f.name())) return "rootPath";
                }
            }
        } catch (Exception ignore) {
            // schema 不可用时按路径语义兜底
        }
        return "rootPath";
    }

    /** 解密凭证并反序列化为该 Provider 的 Settings 对象 */
    public ProviderSettings decryptedSettings(CloudAccountResource res) {
        return parseSettings(res.getSpec().getProviderType(),
                AesUtil.decrypt(res.getSpec().getSettingsCipher()));
    }

    // ── 内部 ────────────────────────────────────────────────

    /**
     * SDK 的 addAccount/replaceSettings/verifyAccount 只标记健康状态、不因凭证无效抛异常，
     * 探活结果必须检查返回的 health（UNHEALTHY 视为凭证/连接失败）。
     */
    private void requireHealthy(CloudAccount sdk, String name) {
        if (sdk == null || sdk.health() != com.haki.clouddrive.core.domain.HealthStatus.HEALTHY) {
            String reason = sdk == null ? "账号未返回" : sdk.healthReason();
            throw new BusinessException("凭证无效或网盘不可达: "
                    + (reason == null ? String.valueOf(sdk == null ? null : sdk.health()) : reason));
        }
    }

    /** 把扩展表账号同步进 SDK 引擎：账号（新增或重贴）+ 默认挂载，幂等；返回探活后的账号 */
    CloudAccount syncToEngine(CloudAccountResource res) throws Exception {
        CloudAccount sdk = ensureSdkAccount(res);
        String mountPath = mountPath(res);
        String rootPath = normalizePath(res.getSpec().getRootPath());
        String providerRoot = resolveProviderRoot(sdk.id(), res.getSpec().getProviderType(), rootPath);
        Optional<Mount> existing = client.listMounts().stream()
                .filter(m -> m.mountPath().equals(mountPath))
                .findFirst();
        String expectedMountRoot = normalizeMountRoot(providerRoot);
        if (existing.isPresent()) {
            Mount mount = existing.get();
            if (!normalizeMountRoot(mount.rootPath()).equals(expectedMountRoot)) {
                client.deleteMount(mount.id());
                client.createMount(sdk.id(), mountPath, providerRoot);
            }
        } else {
            client.createMount(sdk.id(), mountPath, providerRoot);
        }
        return sdk;
    }

    /**
     * 把账号内路径解析为 Provider 原生挂载根值。路径型 Provider 直接返回路径；
     * ID 型 Provider 从账号根逐级列目录、按 CloudObject.path 精确匹配并返回 id。
     * 该值随后只交给 createMount，不会进入 listAccountFolders。
     */
    private String resolveProviderRoot(String sdkAccountId, String providerType, String rootPath)
            throws Exception {
        String normalized = normalizePath(rootPath);
        String rootField = rootFieldName(providerType);
        if (!"rootFolderId".equals(rootField)) {
            return normalized;
        }
        requireMountRootSemantics(providerType, rootField, normalized);
        if ("/".equals(normalized)) {
            return rootFolderIdSentinel(providerType);
        }
        String currentPath = "/";
        String currentId = rootFolderIdSentinel(providerType);
        for (String segment : segments(normalized)) {
            String childPath = DirectoryRef.of(currentPath).resolve(segment).path();
            CloudObject child = client.listAccountFolders(sdkAccountId, currentPath).stream()
                    .filter(CloudObject::directory)
                    .filter(f -> childPath.equals(f.path()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("起始目录不存在: " + childPath));
            currentPath = childPath;
            currentId = child.id();
        }
        return currentId;
    }

    /**
     * Aliyun/Xunlei 的 SDK mount 层目前只会把非空 root 当路径规范化，没有安全的 ID 型
     * mount root 契约。除 cloud189（client 会还原数字 ID）外，子目录起始目录先拒绝，
     * 避免生成 {@code /<id>/...} 这类错误寻址。
     */
    private static void requireMountRootSemantics(String providerType, String rootField, String rootPath) {
        if ("rootFolderId".equals(rootField) && !"cloud189".equals(providerType) && !"/".equals(rootPath)) {
            throw new BusinessException("当前 SDK 尚不支持该网盘用文件夹 ID 作为起始目录；"
                    + "请保持整盘根目录，或等待 SDK mount root 契约修复（见 ticket 07）");
        }
    }

    private String withRootField(CloudAccountResource res, String rootField, String value) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                AesUtil.decrypt(res.getSpec().getSettingsCipher()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(rootField, value);
        return objectMapper.writeValueAsString(node);
    }

    private static String rootFolderIdSentinel(String providerType) {
        return "cloud189".equals(providerType) ? "-11" : "";
    }

    private static String normalizeMountRoot(String rootPath) {
        return rootPath == null || rootPath.isBlank() ? "/" : DirectoryRef.of(rootPath).path();
    }

    private static String normalizePath(String path) {
        return DirectoryRef.of(path).path();
    }

    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : normalizePath(path).split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private CloudAccount ensureSdkAccount(CloudAccountResource res) throws Exception {
        ProviderSettings settings = decryptedSettings(res);
        String name = res.getName();
        Optional<CloudAccount> existing = sdkAccountOf(name);
        if (existing.isEmpty()) {
            return client.addAccount(name, res.getSpec().getProviderType(), settings);
        }
        CloudAccount acc = existing.get();
        client.replaceSettings(acc.id(), settings);
        return client.getAccount(acc.id()).orElseThrow();
    }

    private Optional<CloudAccount> sdkAccountOf(String name) {
        return client.listAccounts().stream()
                .filter(a -> a.name().equals(name))
                .findFirst();
    }

    private ProviderSettings parseSettings(String providerType, String settingsJson) {
        try {
            Class<? extends ProviderSettings> clazz = client.settingsClass(providerType);
            if (clazz == null) {
                throw new BusinessException("不支持的 Provider 类型: " + providerType
                        + "（可用: " + client.providerTypes() + "）");
            }
            return objectMapper.readValue(settingsJson, clazz);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("凭证格式错误，应为该 Provider 的 Settings JSON: " + e.getMessage());
        }
    }

    private CloudAccountResource require(String name) {
        return get(name).orElseThrow(() -> new BusinessException("云盘账号不存在: " + name));
    }

    /** 取 settingsJson 里最长的 secret 值尾部 4 位做脱敏提示 */
    private String hintOf(String settingsJson) {
        try {
            var node = objectMapper.readTree(settingsJson);
            String best = null;
            var it = node.fields();
            while (it.hasNext()) {
                var entry = it.next();
                String v = entry.getValue().asText(null);
                if (v != null && v.length() >= 8 && (best == null || v.length() > best.length())) {
                    best = v;
                }
            }
            return best == null ? "" : "****" + best.substring(best.length() - 4);
        } catch (Exception e) {
            return "";
        }
    }
}
