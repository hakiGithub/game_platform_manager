package com.gameplatform.clouddrive;

import com.gameplatform.plugin.service.CloudDriveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;

/**
 * 云盘宿主能力工厂（ADR-0024）：为插件生成绑定 pluginId 的 {@link CloudDriveService}，
 * 调用方审计标识由服务端写入，插件无法伪造或省略来源。
 * 由 PluginSpringContextFactory 注册进插件子容器。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Service
@RequiredArgsConstructor
public class CloudDriveServiceFactory {

    private final CloudDriveExecutor executor;

    public CloudDriveService forPlugin(String pluginId) {
        return new CloudDriveService() {
            @Override
            public List<CloudFileInfo> list(String accountName, String path, boolean refresh) {
                return executor.list(accountName, path, refresh, pluginId);
            }

            @Override
            public CloudLink link(String accountName, String path) {
                return executor.link(accountName, path, pluginId);
            }

            @Override
            public long download(String accountName, String path, OutputStream target) {
                return executor.download(accountName, path, target, pluginId);
            }

            @Override
            public List<String> transfer(String accountName, String shareUrl, String passcode,
                                         String targetPath, Duration timeout) {
                return executor.transfer(accountName, shareUrl, passcode, targetPath, timeout, pluginId);
            }
        };
    }
}
