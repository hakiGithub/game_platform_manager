package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.PluginBackupResource;
import com.gameplatform.plugin.l4d2.extension.PluginBackupSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.backup.BackupContent;
import com.gameplatform.plugin.l4d2.vo.backup.ServerConfigSnapshot;
import com.gameplatform.plugin.l4d2.vo.backup.ServerInfoSnapshot;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L4D2 插件备份服务：创建、列表、详情、还原、删除、重命名。
 *
 * <p>备份内容：已启用插件列表、admins_simple.ini 原文、hostname/motd/host、
 * server.cfg 关键字段。还原时仅覆盖文件，server.cfg 合并与插件禁用/启用留待阶段 2/6。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final Pattern SV_TAGS_PATTERN = Pattern.compile("sv_tags\\s+\"([^\"]+)\"");
    private static final Pattern SV_LOBBY_PATTERN = Pattern.compile("sv_allow_lobby_connect_only\\s+(\\S+)");
    private static final Pattern SV_STEAMGROUP_PATTERN = Pattern.compile("sv_steamgroup\\s+(\\S+)");

    private final ExtensionClient extensionClient;
    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;
    private final Charset gbk = GbkCodecUtil.gbk();

    /**
     * 列出指定实例的所有备份。
     */
    public List<PluginBackupResource> list(Long instanceId) {
        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .build();
        return extensionClient.list(PluginBackupResource.class, opts);
    }

    /**
     * 创建备份：扫描实例文件并聚合为 BackupContent 持久化。
     */
    public PluginBackupResource create(Long instanceId, String name, String description) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        Long hostId = instance.getHostId();

        BackupContent content = new BackupContent();
        content.setEnabledPlugins(scanEnabledPlugins(instanceId));

        try {
            content.setAdminsIniContent(instanceFileService.readTextFile(instanceId, pathResolver.getAdminsIniPath(), gbk));
        } catch (Exception e) {
            log.warn("读取 admins_simple.ini 失败 instanceId={}, err={}", instanceId, e.getMessage());
            content.setAdminsIniContent("");
        }

        ServerInfoSnapshot info = new ServerInfoSnapshot();
        try {
            info.setHostname(instanceFileService.readTextFile(instanceId, pathResolver.getHostnameConfigPath(), gbk));
        } catch (Exception ignored) {
        }
        try {
            info.setMotd(instanceFileService.readTextFile(instanceId, pathResolver.getMotdPath(), gbk));
        } catch (Exception ignored) {
        }
        try {
            info.setHost(instanceFileService.readTextFile(instanceId, pathResolver.getHostInfoPath(), gbk));
        } catch (Exception ignored) {
        }
        content.setServerInfo(info);

        try {
            String cfg = instanceFileService.readTextFile(instanceId, pathResolver.getServerCfgPath(), gbk);
            content.setServerConfig(parseServerConfig(cfg));
        } catch (Exception ignored) {
        }

        PluginBackupResource resource = new PluginBackupResource();
        PluginBackupSpec spec = new PluginBackupSpec();
        spec.setInstanceId(instanceId);
        spec.setHostId(hostId);
        spec.setName(name);
        spec.setDescription(description);
        spec.setCreatedAt(LocalDateTime.now());
        spec.setContent(content);
        spec.setOwner("system");
        resource.setSpec(spec);
        resource.setName(slugify(instanceId + "-" + name));

        extensionClient.create(resource);
        return resource;
    }

    /**
     * 按 ID 获取备份。
     */
    public PluginBackupResource getById(String backupId) {
        return extensionClient.getById(PluginBackupResource.class, backupId).orElse(null);
    }

    /**
     * 还原备份：覆盖 admins_simple.ini 与 hostname/motd/host。
     * server.cfg 合并、插件禁用/启用留待阶段 2/6 实现。
     */
    public void restore(Long instanceId, String backupId) {
        PluginBackupResource backup = getById(backupId);
        if (backup == null || backup.getSpec() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "备份不存在: " + backupId);
        }
        BackupContent content = backup.getSpec().getContent();
        if (content == null) {
            return;
        }
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }

        if (content.getAdminsIniContent() != null) {
            instanceFileService.writeTextFile(instanceId, pathResolver.getAdminsIniPath(), content.getAdminsIniContent());
        }
        if (content.getServerInfo() != null) {
            ServerInfoSnapshot info = content.getServerInfo();
            if (info.getHostname() != null) {
                instanceFileService.writeTextFile(instanceId, pathResolver.getHostnameConfigPath(), info.getHostname());
            }
            if (info.getMotd() != null) {
                instanceFileService.writeTextFile(instanceId, pathResolver.getMotdPath(), info.getMotd());
            }
            if (info.getHost() != null) {
                instanceFileService.writeTextFile(instanceId, pathResolver.getHostInfoPath(), info.getHost());
            }
        }
        // server.cfg 合并、插件禁用/启用：阶段 2/6 实现
    }

    /**
     * 按 ID 删除备份。
     */
    public void delete(String backupId) {
        extensionClient.deleteById(PluginBackupResource.class, backupId);
    }

    /**
     * 重命名备份：更新 spec.name 与资源 name（保持唯一性）。
     */
    public void rename(String backupId, String newName) {
        PluginBackupResource backup = getById(backupId);
        if (backup == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "备份不存在: " + backupId);
        }
        PluginBackupSpec spec = backup.getSpec();
        if (spec == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "备份数据异常: " + backupId);
        }
        spec.setName(newName);
        backup.setName(slugify(spec.getInstanceId() + "-" + newName));
        extensionClient.update(backup);
    }

    private List<String> scanEnabledPlugins(Long instanceId) {
        List<String> names = new ArrayList<>();
        try {
            String pluginPath = pathResolver.getSourceModPluginsPath();
            for (FileInfo f : instanceFileService.listFiles(instanceId, pluginPath)) {
                if (!f.isDirectory() && f.getName().endsWith(".smx")) {
                    names.add(f.getName().substring(0, f.getName().length() - 4));
                }
            }
        } catch (Exception e) {
            log.warn("扫描插件目录失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
        return names;
    }

    private ServerConfigSnapshot parseServerConfig(String cfg) {
        ServerConfigSnapshot snap = new ServerConfigSnapshot();
        Matcher m = SV_TAGS_PATTERN.matcher(cfg);
        if (m.find()) {
            snap.setSvTags(m.group(1));
        }
        m = SV_LOBBY_PATTERN.matcher(cfg);
        if (m.find()) {
            snap.setSvAllowLobbyConnectOnly(m.group(1));
        }
        m = SV_STEAMGROUP_PATTERN.matcher(cfg);
        if (m.find()) {
            snap.setSvSteamgroup(m.group(1));
        }
        return snap;
    }

    private String slugify(String s) {
        return s == null ? "" : s.replaceAll("[^\\p{L}\\p{N}\\-_]", "_");
    }
}
