package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.clouddrive.extension.CloudAccountResource;
import com.gameplatform.clouddrive.extension.CloudAccountSpec;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.util.AesUtil;
import com.haki.clouddrive.core.domain.CloudAccount;
import com.haki.clouddrive.core.domain.Mount;
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
@RequiredArgsConstructor
@DependsOn({"databaseInitializer", "extensionStoreInitializer"})
public class CloudAccountService {

    public static final String STATUS_HEALTHY = "HEALTHY";
    public static final String STATUS_UNHEALTHY = "UNHEALTHY";

    private final ExtensionClient hostExtensionClient;
    private final CloudDriveClient client;
    private final ObjectMapper objectMapper;

    /**
     * 启动对账：把扩展表中的账号全量同步进 SDK 引擎。
     * <p>
     * 类上的 {@code @DependsOn} 是必需的：{@code extensions} 表由
     * {@code ExtensionStoreInitializer} 建、核心表由 {@code DatabaseInitializer} 建，
     * 三者同为 {@code @PostConstruct} 且无依赖关系时 Spring 按扫描顺序实例化
     * （{@code clouddrive} 排在 {@code config} 之前），全新空库上本方法会先于建表执行而
     * 抛 {@code no such table: extensions} 并使上下文启动失败。
     */
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
     * value 由服务端按 provider 语义决定（rootPath 类=path，rootFolderId 类=文件夹 ID），
     * 前端无脑回填，避免路径/ID 错位。
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
            for (com.haki.clouddrive.core.model.CloudObject f : client.listAccountFolders(sdk.id(), path)) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", f.name());
                item.put("path", f.path());
                item.put("id", f.id());
                item.put("value", idBased ? f.id() : f.path());
                folders.add(item);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("path", path == null || path.isBlank() ? "/" : path);
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
     * 应用起始目录（ADR-0024 增补）：更新 settings 的 root 字段 + 重建默认挂载
     * （rootPath=选中值）。路径命名空间随之切换，已转存产物需按新命名空间重新寻址。
     */
    public CloudAccountResource applyRootDir(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("起始目录值不能为空");
        }
        CloudAccountResource res = require(name);
        String rootField = rootFieldName(res.getSpec().getProviderType());
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                    AesUtil.decrypt(res.getSpec().getSettingsCipher()));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(rootField, value);
            String newJson = objectMapper.writeValueAsString(node);
            CloudAccountSpec spec = res.getSpec();
            spec.setSettingsCipher(AesUtil.encrypt(newJson));
            spec.setCredentialHint(hintOf(newJson));
            res.setSpec(spec);
            hostExtensionClient.update(res);

            CloudAccount sdk = ensureSdkAccount(res);
            String mp = mountPath(res);
            client.listMounts().stream()
                    .filter(m -> m.mountPath().equals(mp))
                    .findFirst()
                    .ifPresent(m -> client.deleteMount(m.id()));
            client.createMount(sdk.id(), mp, value);
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
        boolean mounted = client.listMounts().stream()
                .anyMatch(m -> m.mountPath().equals(mountPath));
        if (!mounted) {
            client.createMount(sdk.id(), mountPath, "/");
        }
        return sdk;
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
